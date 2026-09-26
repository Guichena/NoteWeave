package com.noteweave.upload;

import com.noteweave.storage.ObjectStorage;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UploadTempObjectCleanupService {

    private static final Logger log = LoggerFactory.getLogger(UploadTempObjectCleanupService.class);
    private static final String BUCKET_SOURCE = "noteweave-source";
    private static final int BATCH_LIMIT = 100;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorage storage;
    private final Duration staleAfter;

    public UploadTempObjectCleanupService(
            JdbcTemplate jdbcTemplate,
            ObjectStorage storage,
            @Value("${noteweave.upload.cleanup-stale-after-seconds:86400}") long staleAfterSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.storage = storage;
        this.staleAfter = Duration.ofSeconds(Math.max(60, staleAfterSeconds));
    }

    public void cleanupAfterCommit(String uploadId) {
        cleanupUpload(uploadId);
    }

    @Scheduled(fixedDelayString = "${noteweave.upload.cleanup-delay-ms:60000}")
    public void cleanupScheduled() {
        try {
            cleanupBatch();
        } catch (RuntimeException ex) {
            log.error("Upload temporary object cleanup batch failed", ex);
        }
    }

    private void cleanupBatch() {
        List<UploadChunkRef> rows = jdbcTemplate.query("""
                select c.upload_id, c.object_key, u.status, u.updated_at
                from upload_chunk c
                join document_upload u on u.id = c.upload_id
                where u.status in ('COMPLETED', 'EXPIRED', 'UPLOADING')
                order by u.updated_at asc, c.created_at asc
                limit ?
                """, (rs, rowNum) -> new UploadChunkRef(
                rs.getString("upload_id"),
                rs.getString("object_key"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()
        ), BATCH_LIMIT);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, List<UploadChunkRef>> byUpload = new LinkedHashMap<>();
        rows.forEach(row -> byUpload.computeIfAbsent(row.uploadId(), ignored -> new ArrayList<>()).add(row));
        Instant staleBoundary = Instant.now().minus(staleAfter);
        byUpload.forEach((uploadId, uploadRows) -> {
            UploadChunkRef first = uploadRows.get(0);
            if ("UPLOADING".equals(first.status())) {
                if (first.updatedAt().isAfter(staleBoundary)) {
                    return;
                }
                int expired = jdbcTemplate.update("""
                        update document_upload
                        set status = 'EXPIRED', updated_at = current_timestamp
                        where id = ? and status = 'UPLOADING' and updated_at <= ?
                        """, uploadId, Timestamp.from(first.updatedAt()));
                if (expired != 1) {
                    return;
                }
            }
            cleanupUpload(uploadId);
        });
    }

    private void cleanupUpload(String uploadId) {
        List<String> objectKeys = jdbcTemplate.queryForList(
                "select object_key from upload_chunk where upload_id = ?",
                String.class,
                uploadId
        );
        if (objectKeys.isEmpty()) {
            return;
        }
        for (String objectKey : objectKeys) {
            try {
                storage.delete(BUCKET_SOURCE, objectKey);
            } catch (RuntimeException ex) {
                log.warn("Upload temporary object cleanup failed: uploadId={}, objectKey={}", uploadId, objectKey, ex);
                return;
            }
        }
        jdbcTemplate.update("delete from upload_chunk where upload_id = ?", uploadId);
    }

    private record UploadChunkRef(
            String uploadId,
            String objectKey,
            String status,
            Instant updatedAt
    ) {
    }
}
