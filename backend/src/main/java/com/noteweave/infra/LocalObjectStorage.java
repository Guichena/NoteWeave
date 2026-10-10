package com.noteweave.infra;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.ObjectStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.List;
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
        this.root = properties.storage().localRoot().toAbsolutePath().normalize();
    }

    @Override
    public Path write(String bucket, String objectKey, byte[] content) {
        try {
            Path target = resolveTarget(bucket, objectKey);
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
            return Files.readAllBytes(resolveTarget(bucket, objectKey));
        } catch (IOException ex) {
            throw new IllegalStateException("read object failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public void writeFile(String bucket, String objectKey, Path file) {
        try {
            Path target = resolveTarget(bucket, objectKey);
            Files.createDirectories(target.getParent());
            Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("write object failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public void readToFile(String bucket, String objectKey, Path target) {
        try {
            Files.copy(resolveTarget(bucket, objectKey), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("read object failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public void copy(String sourceBucket, String sourceKey, String targetBucket, String targetKey) {
        try {
            Path target = resolveTarget(targetBucket, targetKey);
            Files.createDirectories(target.getParent());
            Files.copy(resolveTarget(sourceBucket, sourceKey), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("copy object failed: " + sourceBucket + "/" + sourceKey, ex);
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        return Files.exists(resolveTarget(bucket, objectKey));
    }

    @Override
    public void delete(String bucket, String objectKey) {
        Path target = resolveTarget(bucket, objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new IllegalStateException("delete object failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public List<StoredObject> list(String bucket, String prefix, String afterKey, int limit) {
        if (limit < 1 || limit > 10_000 || !prefix.endsWith("/") || prefix.contains("..")) {
            throw new IllegalArgumentException("invalid object inventory prefix or limit");
        }
        Path bucketRoot = resolveTarget(bucket, "");
        Path directory = resolveTarget(bucket, prefix);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> {
                        try {
                            String key = bucketRoot.relativize(path).toString().replace('\\', '/');
                            return new StoredObject(key, Files.getLastModifiedTime(
                                    path, LinkOption.NOFOLLOW_LINKS).toInstant());
                        } catch (IOException ex) {
                            throw new IllegalStateException("read object inventory failed", ex);
                        }
                    })
                    .sorted(java.util.Comparator.comparing(StoredObject::key))
                    .filter(object -> object.key().compareTo(afterKey == null ? "" : afterKey) > 0)
                    .limit(limit).toList();
        } catch (IOException ex) {
            throw new IllegalStateException("list object inventory failed", ex);
        }
    }

    @Override
    public String backendName() {
        return "local";
    }

    private Path resolveTarget(String bucket, String objectKey) {
        Path bucketRoot = root.resolve(bucket).normalize();
        if (!bucketRoot.startsWith(root)) {
            throw new IllegalArgumentException("bucket escapes local object storage root");
        }
        Path target = bucketRoot.resolve(objectKey).normalize();
        if (!target.startsWith(bucketRoot)) {
            throw new IllegalArgumentException("object key escapes storage bucket");
        }
        return target;
    }
}
