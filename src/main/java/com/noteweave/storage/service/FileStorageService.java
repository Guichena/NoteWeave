package com.noteweave.storage.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.storage.config.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    public void putObject(String bucket, String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucket(bucket);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, size, -1)
                    .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                    .build());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.STORAGE_OPERATION_FAILED, "failed to put object: " + ex.getMessage());
        }
    }

    public boolean objectExists(String bucket, String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public void composeObject(String bucket, String targetObjectKey, List<String> sourceObjectKeys) {
        try {
            ensureBucket(bucket);
            List<ComposeSource> sources = sourceObjectKeys.stream()
                    .map(source -> ComposeSource.builder().bucket(bucket).object(source).build())
                    .toList();
            minioClient.composeObject(ComposeObjectArgs.builder()
                    .bucket(bucket)
                    .object(targetObjectKey)
                    .sources(sources)
                    .build());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.STORAGE_OPERATION_FAILED, "failed to compose object: " + ex.getMessage());
        }
    }

    public StatObjectResponse statObject(String bucket, String objectKey) {
        try {
            return minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.STORAGE_OBJECT_NOT_FOUND, "object not found: " + objectKey);
        }
    }

    public InputStream getObject(String bucket, String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.STORAGE_OBJECT_NOT_FOUND, "object not found: " + objectKey);
        }
    }

    public void removeObject(String bucket, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.STORAGE_OPERATION_FAILED, "failed to remove object: " + ex.getMessage());
        }
    }

    public String devBucket() {
        return storageProperties.minio().bucket();
    }

    public String testBucket() {
        return storageProperties.minio().testBucket();
    }

    public void ensureBucket(String bucket) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.STORAGE_OPERATION_FAILED, "failed to ensure bucket: " + bucket);
        }
    }
}
