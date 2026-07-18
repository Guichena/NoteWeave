package com.noteweave.infra;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.ObjectStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地文件系统对象存储实现，仅用于开发或离线场景。
 * 默认 backend=minio，要切到本地需显式设置 {@code noteweave.storage.backend=local}。
 */
@Component
@ConditionalOnProperty(name = "noteweave.storage.backend", havingValue = "local")
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalObjectStorage(NoteWeaveProperties properties) {
        this.root = properties.storage().localRoot();
    }

    @Override
    public Path write(String bucket, String objectKey, byte[] content) {
        try {
            Path target = root.resolve(bucket).resolve(objectKey).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("write object failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public byte[] read(String bucket, String objectKey) {
        try {
            return Files.readAllBytes(root.resolve(bucket).resolve(objectKey).normalize());
        } catch (IOException ex) {
            throw new IllegalStateException("read object failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        return Files.exists(root.resolve(bucket).resolve(objectKey).normalize());
    }

    @Override
    public void delete(String bucket, String objectKey) {
        Path bucketRoot = root.resolve(bucket).normalize();
        Path target = bucketRoot.resolve(objectKey).normalize();
        if (!target.startsWith(bucketRoot)) {
            throw new IllegalArgumentException("object key escapes storage bucket");
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new IllegalStateException("delete object failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public String backendName() {
        return "local";
    }
}
