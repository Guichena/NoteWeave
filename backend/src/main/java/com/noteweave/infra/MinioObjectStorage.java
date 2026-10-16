package com.noteweave.infra;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.ObjectStorage;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储实现。对接 docker-compose 中的 MinIO 服务。
 * <p>
 * bucket 规范见 {@code docs/资料基础设施详细设计.md} §5.1：
 *   - noteweave-source  原始文件
 *   - noteweave-derived  解析正文、结构、索引
 *   - noteweave-export   导出文件
 */
@Component
@ConditionalOnProperty(name = "noteweave.storage.backend", havingValue = "minio", matchIfMissing = true)
public class MinioObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);

    private final MinioClient client;
    private final List<String> buckets;

    public MinioObjectStorage(NoteWeaveProperties properties) {
        NoteWeaveProperties.Minio cfg = properties.storage().minio();
        this.client = MinioClient.builder()
                .endpoint(cfg.endpoint())
                .credentials(cfg.accessKey(), cfg.secretKey())
                .region(cfg.region())
                .build();
        this.buckets = List.of(cfg.bucketSource(), cfg.bucketDerived(), cfg.bucketExport());
    }

    @PostConstruct
    void ensureBuckets() {
        for (String bucket : buckets) {
            try {
                boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("MinIO bucket created: {}", bucket);
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to ensure MinIO bucket " + bucket, ex);
            }
        }
    }

    @Override
    public Path write(String bucket, String objectKey, byte[] content) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(detectContentType(objectKey))
                    .build());
            return Paths.get(bucket, objectKey);
        } catch (Exception ex) {
            throw new IllegalStateException("minio write failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public byte[] read(String bucket, String objectKey) {
        try (InputStream stream = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("minio read failed: " + bucket + "/" + objectKey, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("minio read failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public InputStream openStream(String bucket, String objectKey) {
        try { return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build()); }
        catch (Exception ex) { throw new IllegalStateException("对象读取失败：" + bucket + "/" + objectKey, ex); }
    }

    @Override
    public void compose(String sourceBucket, List<String> sourceKeys, String targetBucket, String targetKey) {
        if (sourceKeys == null || sourceKeys.isEmpty()) throw new IllegalArgumentException("合并对象列表不能为空");
        if (sourceBucket.equals(targetBucket) && sourceKeys.contains(targetKey)) {
            throw new IllegalArgumentException("合并目标不能覆盖输入对象");
        }
        try {
            long total = 0;
            boolean serverSide = true;
            for (int index = 0; index < sourceKeys.size(); index++) {
                long size = client.statObject(io.minio.StatObjectArgs.builder().bucket(sourceBucket)
                        .object(sourceKeys.get(index)).build()).size();
                total = Math.addExact(total, size);
                if (index < sourceKeys.size() - 1 && size < 5L * 1024 * 1024) serverSide = false;
            }
            if (serverSide) {
                client.composeObject(io.minio.ComposeObjectArgs.builder().bucket(targetBucket).object(targetKey)
                        .sources(sourceKeys.stream().map(key -> io.minio.ComposeSource.builder()
                                .bucket(sourceBucket).object(key).build()).toList()).build());
            } else {
                // 历史小分片不满足 multipart copy 的下限，逐个打开流后直接上传合并对象。
                try (InputStream input = sequentialStream(sourceBucket, sourceKeys)) {
                    client.putObject(PutObjectArgs.builder().bucket(targetBucket).object(targetKey)
                            .stream(input, total, -1).contentType(detectContentType(targetKey)).build());
                }
            }
        } catch (Exception ex) { throw new IllegalStateException("MinIO 对象合并失败", ex); }
    }

    private InputStream sequentialStream(String bucket, List<String> keys) {
        return new InputStream() {
            private int index;
            private InputStream current;
            private boolean closed;
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
                if (closed) throw new IOException("对象合并流已经关闭");
                if (length == 0) return 0;
                while (true) {
                    if (current == null) {
                        if (index == keys.size()) return -1;
                        current = openStream(bucket, keys.get(index++));
                    }
                    int read = current.read(bytes, offset, length);
                    if (read != -1) return read;
                    current.close();
                    current = null;
                }
            }
            @Override public void close() throws IOException {
                closed = true;
                if (current != null) current.close();
            }
        };
    }

    @Override
    public void writeFile(String bucket, String objectKey, java.nio.file.Path file) {
        // 按文件大小流式上传，SDK 会对大文件自动分段上传，不把整个文件读入内存
        try (InputStream stream = java.nio.file.Files.newInputStream(file)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, java.nio.file.Files.size(file), -1)
                    .contentType(detectContentType(objectKey))
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("minio write failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public void readToFile(String bucket, String objectKey, java.nio.file.Path target) {
        try (InputStream stream = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            java.nio.file.Files.copy(stream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            throw new IllegalStateException("minio read failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public void copy(String sourceBucket, String sourceKey, String targetBucket, String targetKey) {
        // 服务端复制，数据不经过应用
        try {
            client.copyObject(io.minio.CopyObjectArgs.builder()
                    .bucket(targetBucket)
                    .object(targetKey)
                    .source(io.minio.CopySource.builder().bucket(sourceBucket).object(sourceKey).build())
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("minio copy failed: " + sourceBucket + "/" + sourceKey
                    + " -> " + targetBucket + "/" + targetKey, ex);
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        try {
            client.statObject(io.minio.StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException ex) {
            if (isObjectNotFoundCode(ex.errorResponse().code())) {
                return false;
            }
            throw new IllegalStateException("minio stat failed: " + bucket + "/" + objectKey, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("minio stat failed: " + bucket + "/" + objectKey, ex);
        }
    }

    static boolean isObjectNotFoundCode(String code) {
        return "NoSuchKey".equals(code) || "NoSuchObject".equals(code);
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("minio delete failed: " + bucket + "/" + objectKey, ex);
        }
    }

    @Override
    public List<StoredObject> list(String bucket, String prefix, String afterKey, int limit) {
        if (limit < 1 || limit > 10_000 || !prefix.endsWith("/") || prefix.contains("..")) {
            throw new IllegalArgumentException("invalid object inventory prefix or limit");
        }
        ArrayList<StoredObject> objects = new ArrayList<>();
        try {
            for (var result : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).startAfter(afterKey == null ? "" : afterKey)
                    .recursive(true).build())) {
                var item = result.get();
                objects.add(new StoredObject(item.objectName(), item.lastModified().toInstant()));
                if (objects.size() >= limit) break;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("minio list failed: " + bucket + "/" + prefix, ex);
        }
        return List.copyOf(objects);
    }

    @Override
    public String backendName() {
        return "minio";
    }

    private String detectContentType(String objectKey) {
        String lower = objectKey.toLowerCase();
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return "application/octet-stream";
    }
}
