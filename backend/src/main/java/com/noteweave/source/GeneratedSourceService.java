package com.noteweave.source;

import com.noteweave.common.Ids;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.security.AuditActorProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GeneratedSourceService {

    private static final String SOURCE_BUCKET = "noteweave-source";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorage storage;
    private final SourceParsePort sourceParsePort;
    private final SourceWikiCommandPort wikiCommandPort;
    private final AuditActorProvider auditActorProvider;
    private final SourceCatalogVersionService sourceCatalogVersionService;

    public GeneratedSourceService(
            JdbcTemplate jdbcTemplate,
            ObjectStorage storage,
            SourceParsePort sourceParsePort,
            SourceWikiCommandPort wikiCommandPort,
            AuditActorProvider auditActorProvider,
            SourceCatalogVersionService sourceCatalogVersionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.storage = storage;
        this.sourceParsePort = sourceParsePort;
        this.wikiCommandPort = wikiCommandPort;
        this.auditActorProvider = auditActorProvider;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
    }

    @Transactional
    public GeneratedSourceResult saveMarkdown(
            String workspaceId,
            String title,
            String markdown,
            String sourceType,
            String generatedBy,
            String generatedRefId
    ) {
        List<GeneratedSourceResult> existing = jdbcTemplate.query("""
                select id, status, parse_status, index_status, generated_by, generated_ref_id
                from source
                where workspace_id = ? and generated_by = ? and generated_ref_id = ?
                order by created_at asc, id asc
                limit 1
                """, (rs, rowNum) -> new GeneratedSourceResult(
                rs.getString("id"),
                rs.getString("status"),
                rs.getString("parse_status"),
                rs.getString("index_status"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id")
        ), workspaceId, generatedBy, generatedRefId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        FileObjectRef fileObject = getOrCreateFileObject(workspaceId, title, digest, bytes);
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String snapshotObjectKey = "workspace/%s/generated/%s/%s/final.md".formatted(
                workspaceId,
                sanitize(generatedBy),
                sanitize(generatedRefId)
        );
        storage.write(SOURCE_BUCKET, snapshotObjectKey, bytes);
        String actor = auditActorProvider.currentOrSystem("GENERATED_SOURCE");
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, source_type, status,
                    parse_status, index_status, generated_by, generated_ref_id, created_by, updated_by
                ) values (?, ?, ?, ?, ?, 'PROCESSING', 'PENDING', 'PENDING', ?, ?, ?, ?)
                """,
                sourceId,
                workspaceId,
                fileObject.id(),
                title,
                sourceType,
                generatedBy,
                generatedRefId,
                actor,
                actor
        );
        sourceCatalogVersionService.bump(workspaceId);
        jdbcTemplate.update("""
                insert into source_snapshot(
                    id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status
                ) values (?, ?, ?, 1, ?, ?, 'PENDING', 'PENDING')
                """, snapshotId, sourceId, fileObject.id(), snapshotObjectKey, digest);
        sourceParsePort.parseAndIndex(workspaceId, sourceId, snapshotId, bytes);
        GeneratedSourceResult result = load(workspaceId, sourceId);
        if (!"DISABLED".equals(result.indexStatus())) {
            wikiCommandPort.requestSourceIngest(workspaceId, sourceId);
        }
        return result;
    }

    private GeneratedSourceResult load(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select id, status, parse_status, index_status, generated_by, generated_ref_id
                from source where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new IllegalStateException("generated source was not persisted");
            }
            return new GeneratedSourceResult(
                    rs.getString("id"),
                    rs.getString("status"),
                    rs.getString("parse_status"),
                    rs.getString("index_status"),
                    rs.getString("generated_by"),
                    rs.getString("generated_ref_id")
            );
        }, workspaceId, sourceId);
    }

    private FileObjectRef getOrCreateFileObject(
            String workspaceId,
            String title,
            String digest,
            byte[] bytes
    ) {
        List<FileObjectRef> existing = jdbcTemplate.query("""
                select id, object_key from file_object where workspace_id = ? and sha256 = ?
                """, (rs, rowNum) -> new FileObjectRef(
                rs.getString("id"),
                rs.getString("object_key")
        ), workspaceId, digest);
        if (!existing.isEmpty()) {
            FileObjectRef ref = existing.get(0);
            if (!storage.exists(SOURCE_BUCKET, ref.objectKey())) {
                storage.write(SOURCE_BUCKET, ref.objectKey(), bytes);
            }
            jdbcTemplate.update("update file_object set ref_count = ref_count + 1 where id = ?", ref.id());
            return ref;
        }
        String id = Ids.newId();
        String objectKey = "workspace/%s/file_object/%s-%s.md".formatted(
                workspaceId,
                digest,
                sanitize(title)
        );
        storage.write(SOURCE_BUCKET, objectKey, bytes);
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type, ref_count)
                values (?, ?, ?, ?, ?, 'text/markdown', 1)
                """, id, workspaceId, objectKey, digest, bytes.length);
        return new FileObjectRef(id, objectKey);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String sanitize(String value) {
        String normalized = value == null || value.isBlank() ? "generated-source" : value;
        return normalized.replaceAll("[^\\p{IsHan}a-zA-Z0-9._-]+", "-");
    }

    private record FileObjectRef(String id, String objectKey) {
    }
}
