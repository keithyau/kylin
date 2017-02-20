/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.dict;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.ClassUtil;
import org.apache.kylin.common.util.HadoopUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

public class GlobalDictHDFSStore extends GlobalDictStore {

    static final Logger logger = LoggerFactory.getLogger(GlobalDictHDFSStore.class);
    static final String V1_INDEX_NAME = ".index";
    static final String V2_INDEX_NAME = ".index_v2";
    static final String VERSION_PREFIX = "version_";
    static final int BUFFER_SIZE = 8 * 1024 * 1024;

    private final Path basePath;
    private final Configuration conf;
    private final FileSystem fileSystem;

    protected GlobalDictHDFSStore(String baseDir) throws IOException {
        super(baseDir);
        this.basePath = new Path(baseDir);
        this.conf = HadoopUtil.getCurrentConfiguration();
        this.fileSystem = HadoopUtil.getFileSystem(baseDir);

        if (!fileSystem.exists(basePath)) {
            logger.info("Global dict at {} doesn't exist, create a new one", basePath);
            fileSystem.mkdirs(basePath);
        }

        migrateOldLayout();
    }

    // Previously we put slice files and index file directly in base directory,
    // should migrate to the new versioned layout
    private void migrateOldLayout() throws IOException {
        FileStatus[] sliceFiles = fileSystem.listStatus(basePath, new PathFilter() {
            @Override
            public boolean accept(Path path) {
                return path.getName().startsWith(IndexFormatV1.SLICE_PREFIX);
            }
        });
        Path indexFile = new Path(basePath, V1_INDEX_NAME);

        if (fileSystem.exists(indexFile) && sliceFiles.length > 0) { // old layout
            final long version = System.currentTimeMillis();
            Path tempDir = new Path(basePath, "tmp_" + VERSION_PREFIX + version);
            Path versionDir = getVersionDir(version);

            logger.info("Convert global dict at {} to new layout with version {}", basePath, version);

            fileSystem.mkdirs(tempDir);
            // convert to new layout
            try {
                // copy index and slice files to temp
                FileUtil.copy(fileSystem, indexFile, fileSystem, tempDir, false, conf);
                for (FileStatus sliceFile : sliceFiles) {
                    FileUtil.copy(fileSystem, sliceFile.getPath(), fileSystem, tempDir, false, conf);
                }
                // rename
                fileSystem.rename(tempDir, versionDir);
                // delete index and slices files in base dir
                fileSystem.delete(indexFile, false);
                for (FileStatus sliceFile : sliceFiles) {
                    fileSystem.delete(sliceFile.getPath(), true);
                }

            } finally {
                if (fileSystem.exists(tempDir)) {
                    fileSystem.delete(tempDir, true);
                }
            }
        }
    }

    @Override
    void prepareForWrite(String workingDir) throws IOException {
        // TODO create lock file
        Path working = new Path(workingDir);

        if (fileSystem.exists(working)) {
            fileSystem.delete(working, true);
            logger.info("Working directory {} exits, delete it first", working);
        }

        // when build dict, copy all data into working dir and work on it, avoiding suddenly server crash made data corrupt
        long[] versions = listAllVersions();
        if (versions.length > 0) {
            Path latestVersion = getVersionDir(versions[versions.length - 1]);
            FileUtil.copy(fileSystem, latestVersion, fileSystem, working, false, true, conf);
        } else {
            fileSystem.mkdirs(working);
        }
    }

    @Override
    long[] listAllVersions() throws IOException {
        FileStatus[] versionDirs = fileSystem.listStatus(basePath, new PathFilter() {
            @Override
            public boolean accept(Path path) {
                return path.getName().startsWith(VERSION_PREFIX);
            }
        });
        long[] versions = new long[versionDirs.length];
        for (int i = 0; i < versionDirs.length; i++) {
            Path path = versionDirs[i].getPath();
            versions[i] = Long.parseLong(path.getName().substring(VERSION_PREFIX.length()));
        }
        return versions;
    }

    @Override
    public Path getVersionDir(long version) {
        return new Path(basePath, VERSION_PREFIX + version);
    }

    @Override
    GlobalDictMetadata getMetadata(long version) throws IOException {
        Path versionDir = getVersionDir(version);
        FileStatus[] indexFiles = fileSystem.listStatus(versionDir, new PathFilter() {
            @Override
            public boolean accept(Path path) {
                return path.getName().startsWith(V1_INDEX_NAME);
            }
        });
        checkState(indexFiles.length == 1, "zero or more than one index file found: %s", Arrays.toString(indexFiles));

        IndexFormat format;
        String indexFile = indexFiles[0].getPath().getName();
        if (V2_INDEX_NAME.equals(indexFile)) {
            format = new IndexFormatV2(fileSystem, conf);
        } else if (V1_INDEX_NAME.equals(indexFile)) {
            format = new IndexFormatV1(fileSystem, conf);
        } else {
            throw new RuntimeException("Unknown index file: " + indexFile);
        }

        return format.readIndexFile(versionDir);
    }

    @Override
    DictSlice readSlice(String directory, String sliceFileName) {
        Path path = new Path(directory, sliceFileName);
        logger.info("read slice from {}", path);
        try (FSDataInputStream input = fileSystem.open(path, BUFFER_SIZE)) {
            return DictSlice.deserializeFrom(input);
        } catch (IOException e) {
            throw new RuntimeException(String.format("read slice %s failed", path), e);
        }
    }

    @Override
    String writeSlice(String workingDir, DictSliceKey key, DictNode slice) {
        //write new slice
        String sliceFile = IndexFormatV2.sliceFileName(key);
        Path path = new Path(workingDir, sliceFile);

        logger.info("write slice with key {} into file {}", key, path);
        try (FSDataOutputStream out = fileSystem.create(path, true, BUFFER_SIZE)) {
            byte[] bytes = slice.buildTrieBytes();
            out.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(String.format("write slice with key %s into file %s failed", key, path), e);
        }
        return sliceFile;
    }

    @Override
    void deleteSlice(String workingDir, String sliceFileName) {
        Path path = new Path(workingDir, sliceFileName);
        logger.info("delete slice at {}", path);
        try {
            if (fileSystem.exists(path)) {
                fileSystem.delete(path, false);
            }
        } catch (IOException e) {
            throw new RuntimeException(String.format("delete slice at %s failed", path), e);
        }
    }

    @Override
    void commit(String workingDir, GlobalDictMetadata metadata) throws IOException {
        Path workingPath = new Path(workingDir);

        // delete v1 index file
        Path oldIndexFile = new Path(workingPath, V1_INDEX_NAME);
        if (fileSystem.exists(oldIndexFile)) {
            fileSystem.delete(oldIndexFile, false);
        }
        // write v2 index file
        IndexFormat index = new IndexFormatV2(fileSystem, conf);
        index.writeIndexFile(workingPath, metadata);
        index.sanityCheck(workingPath, metadata);

        // copy working dir to newVersion dir
        Path newVersionPath = new Path(basePath, VERSION_PREFIX + System.currentTimeMillis());
        fileSystem.rename(workingPath, newVersionPath);

        cleanUp();
    }

    // Check versions count, delete expired versions
    private void cleanUp() throws IOException {
        long[] versions = listAllVersions();
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < versions.length - maxVersions; i++) {
            if (versions[i] + versionTTL < timestamp) {
                fileSystem.delete(getVersionDir(versions[i]), true);
            }
        }
    }

    @Override
    String copyToAnotherMeta(KylinConfig srcConfig, KylinConfig dstConfig) throws IOException {
        checkArgument(baseDir.startsWith(srcConfig.getHdfsWorkingDirectory()), "Please check why current directory {} doesn't belong to source working directory {}", baseDir, srcConfig.getHdfsWorkingDirectory());

        final String dstBaseDir = baseDir.replaceFirst(srcConfig.getHdfsWorkingDirectory(), dstConfig.getHdfsWorkingDirectory());

        long[] versions = listAllVersions();
        if (versions.length == 0) { // empty dict, nothing to copy
            return dstBaseDir;
        }

        Path srcVersionDir = getVersionDir(versions[versions.length - 1]);
        Path dstVersionDir = new Path(srcVersionDir.toString().replaceFirst(srcConfig.getHdfsWorkingDirectory(), dstConfig.getHdfsWorkingDirectory()));
        FileSystem dstFS = dstVersionDir.getFileSystem(conf);
        if (dstFS.exists(dstVersionDir)) {
            dstFS.delete(dstVersionDir, true);
        }
        FileUtil.copy(fileSystem, srcVersionDir, dstFS, dstVersionDir, false, true, conf);

        return dstBaseDir;
    }

    public interface IndexFormat {
        GlobalDictMetadata readIndexFile(Path dir) throws IOException;

        void writeIndexFile(Path dir, GlobalDictMetadata metadata) throws IOException;

        void sanityCheck(Path dir, GlobalDictMetadata metadata) throws IOException;
    }

    public static class IndexFormatV1 implements IndexFormat {
        static final String SLICE_PREFIX = "cached_";

        protected final FileSystem fs;
        protected final Configuration conf;

        protected IndexFormatV1(FileSystem fs, Configuration conf) {
            this.fs = fs;
            this.conf = conf;
        }

        @Override
        public GlobalDictMetadata readIndexFile(Path dir) throws IOException {
            Path indexFile = new Path(dir, V1_INDEX_NAME);
            try (FSDataInputStream in = fs.open(indexFile)) {
                int baseId = in.readInt();
                int maxId = in.readInt();
                int maxValueLength = in.readInt();
                int nValues = in.readInt();
                String converterName = in.readUTF();
                BytesConverter converter;
                try {
                    converter = ClassUtil.forName(converterName, BytesConverter.class).newInstance();
                } catch (Exception e) {
                    throw new RuntimeException("Fail to instantiate BytesConverter: " + converterName, e);
                }

                int nSlices = in.readInt();
                TreeMap<DictSliceKey, String> sliceFileMap = new TreeMap<>();
                for (int i = 0; i < nSlices; i++) {
                    DictSliceKey key = new DictSliceKey();
                    key.readFields(in);
                    sliceFileMap.put(key, sliceFileName(key));
                }
                // make sure first key is always ""
                String firstFile = sliceFileMap.remove(sliceFileMap.firstKey());
                sliceFileMap.put(DictSliceKey.START_KEY, firstFile);

                return new GlobalDictMetadata(baseId, maxId, maxValueLength, nValues, converter, sliceFileMap);
            }
        }

        //only for test
        @Override
        public void writeIndexFile(Path dir, GlobalDictMetadata metadata) throws IOException {
            Path indexFile = new Path(dir, V1_INDEX_NAME);
            try (FSDataOutputStream out = fs.create(indexFile, true)) {
                out.writeInt(metadata.baseId);
                out.writeInt(metadata.maxId);
                out.writeInt(metadata.maxValueLength);
                out.writeInt(metadata.nValues);
                out.writeUTF(metadata.bytesConverter.getClass().getName());
                out.writeInt(metadata.sliceFileMap.size());
                for (Map.Entry<DictSliceKey, String> entry : metadata.sliceFileMap.entrySet()) {
                    entry.getKey().write(out);
                }
            }
        }

        @Override
        public void sanityCheck(Path dir, GlobalDictMetadata metadata) throws IOException {
            throw new UnsupportedOperationException("sanityCheck V1 format is no longer supported");
        }

        public static String sliceFileName(DictSliceKey key) {
            return SLICE_PREFIX + key;
        }
    }

    public static class IndexFormatV2 extends IndexFormatV1 {
        static final String SLICE_PREFIX = "cached_";
        static final int MINOR_VERSION_V1 = 0x01;

        protected IndexFormatV2(FileSystem fs, Configuration conf) {
            super(fs, conf);
        }

        @Override
        public GlobalDictMetadata readIndexFile(Path dir) throws IOException {
            Path indexFile = new Path(dir, V2_INDEX_NAME);
            try (FSDataInputStream in = fs.open(indexFile)) {
                byte minorVersion = in.readByte(); // include a header to allow minor format changes
                if (minorVersion != MINOR_VERSION_V1) {
                    throw new RuntimeException("Unsupported minor version " + minorVersion);
                }
                int baseId = in.readInt();
                int maxId = in.readInt();
                int maxValueLength = in.readInt();
                int nValues = in.readInt();
                String converterName = in.readUTF();
                BytesConverter converter;
                try {
                    converter = ClassUtil.forName(converterName, BytesConverter.class).newInstance();
                } catch (Exception e) {
                    throw new RuntimeException("Fail to instantiate BytesConverter: " + converterName, e);
                }

                int nSlices = in.readInt();
                TreeMap<DictSliceKey, String> sliceFileMap = new TreeMap<>();
                for (int i = 0; i < nSlices; i++) {
                    DictSliceKey key = new DictSliceKey();
                    key.readFields(in);
                    String sliceFileName = in.readUTF();
                    sliceFileMap.put(key, sliceFileName);
                }

                return new GlobalDictMetadata(baseId, maxId, maxValueLength, nValues, converter, sliceFileMap);
            }
        }

        @Override
        public void writeIndexFile(Path dir, GlobalDictMetadata metadata) throws IOException {
            Path indexFile = new Path(dir, V2_INDEX_NAME);
            try (FSDataOutputStream out = fs.create(indexFile, true)) {
                out.writeByte(MINOR_VERSION_V1);
                out.writeInt(metadata.baseId);
                out.writeInt(metadata.maxId);
                out.writeInt(metadata.maxValueLength);
                out.writeInt(metadata.nValues);
                out.writeUTF(metadata.bytesConverter.getClass().getName());
                out.writeInt(metadata.sliceFileMap.size());
                for (Map.Entry<DictSliceKey, String> entry : metadata.sliceFileMap.entrySet()) {
                    entry.getKey().write(out);
                    out.writeUTF(entry.getValue());
                }
            }
        }

        @Override
        public void sanityCheck(Path dir, GlobalDictMetadata metadata) throws IOException {
            for (Map.Entry<DictSliceKey, String> entry : metadata.sliceFileMap.entrySet()) {
                if (!fs.exists(new Path(dir, entry.getValue()))) {
                    throw new RuntimeException("The slice file " + entry.getValue() + " for the key: " + entry.getKey() + " must be existed!");
                }
            }
        }

        public static String sliceFileName(DictSliceKey key) {
            return String.format("%s%d_%d", SLICE_PREFIX, System.currentTimeMillis(), key.hashCode());
        }
    }
}
