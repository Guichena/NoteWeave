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
