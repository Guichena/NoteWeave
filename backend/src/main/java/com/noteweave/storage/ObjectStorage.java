package com.noteweave.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Application-facing object storage port. */
public interface ObjectStorage {

    Path write(String bucket, String objectKey, byte[] content);

    byte[] read(String bucket, String objectKey);

    /** 调用方负责关闭流；生产实现不把整个对象读入堆内存。 */
    default java.io.InputStream openStream(String bucket, String objectKey) {
        return new java.io.ByteArrayInputStream(read(bucket, objectKey));
    }

    /** 按顺序合并对象。支持服务端合并的实现覆盖此方法。 */
    default void compose(String sourceBucket, List<String> sourceKeys, String targetBucket, String targetKey) {
        if (sourceKeys == null || sourceKeys.isEmpty()) throw new IllegalArgumentException("合并对象列表不能为空");
        Path temporary = null;
        try {
            temporary = Files.createTempFile("noteweave-compose-", ".bin");
            try (var output = Files.newOutputStream(temporary)) {
                for (String key : sourceKeys) {
                    try (var input = openStream(sourceBucket, key)) { input.transferTo(output); }
                }
            }
            writeFile(targetBucket, targetKey, temporary);
        } catch (IOException ex) {
            throw new IllegalStateException("对象合并失败", ex);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    boolean exists(String bucket, String objectKey);

    void delete(String bucket, String objectKey);

    /** Bounded inventory for reconciliation of a controlled object-key prefix. */
    List<StoredObject> list(String bucket, String prefix, String afterKey, int limit);

    /** 把本地文件流式写入对象存储，避免大文件整体读入内存。默认实现退化为整体读写。 */
    default void writeFile(String bucket, String objectKey, Path file) {
        try {
            write(bucket, objectKey, Files.readAllBytes(file));
        } catch (IOException ex) {
            throw new IllegalStateException("read local file failed: " + file, ex);
        }
    }

    /** 把对象流式下载到本地文件。默认实现退化为整体读写。 */
    default void readToFile(String bucket, String objectKey, Path target) {
        try {
            Files.write(target, read(bucket, objectKey));
        } catch (IOException ex) {
            throw new IllegalStateException("write local file failed: " + target, ex);
        }
    }

    /** 复制对象；支持服务端复制的实现不经过应用内存。 */
    default void copy(String sourceBucket, String sourceKey, String targetBucket, String targetKey) {
        write(targetBucket, targetKey, read(sourceBucket, sourceKey));
    }

    record StoredObject(String key, Instant lastModified) {}

    String backendName();
}
