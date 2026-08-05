package com.noteweave.infra;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.ObjectStorage;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
    public boolean exists(String bucket, String objectKey) {
        try {
            client.statObject(io.minio.StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return true;
        } catch (Exception ex) {
            return false;
        }
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
