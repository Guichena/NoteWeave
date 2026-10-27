package com.noteweave.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.task.TaskCommandPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GeneratedSourceService {

    private static final String SOURCE_BUCKET = "noteweave-source";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorage storage;
    private final SourceParsePort sourceParsePort;
    private final AuditActorProvider auditActorProvider;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final TaskCommandPort taskCommandPort;
    private final SourceTopicNames topicNames;
    private final SourceMessagingMode messagingMode;
    private final ObjectMapper objectMapper;
    /** SRC-01 回滚开关：关掉后回到事务内同步解析。 */
    private boolean asyncParseEnabled = true;

    @Value("${noteweave.generated-source.async-parse-enabled:true}")
    void configureAsyncParse(boolean enabled) {
        this.asyncParseEnabled = enabled;
    }

    public GeneratedSourceService(
            JdbcTemplate jdbcTemplate,
            ObjectStorage storage,
            SourceParsePort sourceParsePort,
            AuditActorProvider auditActorProvider,
            SourceCatalogVersionService sourceCatalogVersionService,
            TaskCommandPort taskCommandPort,
            SourceTopicNames topicNames,
            SourceMessagingMode messagingMode,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.storage = storage;
        this.sourceParsePort = sourceParsePort;
        this.auditActorProvider = auditActorProvider;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.taskCommandPort = taskCommandPort;
        this.topicNames = topicNames;
        this.messagingMode = messagingMode;
        this.objectMapper = objectMapper;
    }

    /**
     * 文件对象生命周期设施（NR13）：Artifact 另存与上传共用 acquire/republish 协议，
     * 不再裸复用 DELETE_CLAIMED/DELETED 行。未注入时（直连构造的测试）保留 legacy 路径。
     */
    private com.noteweave.storage.FileObjectLifecycleService fileLifecycle;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFileLifecycle(com.noteweave.storage.FileObjectLifecycleService fileLifecycle) {
        this.fileLifecycle = fileLifecycle;
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
        // NR20：幂等复用只覆盖可用生命周期——已删除的生成来源不再作为保存结果返回，
        // 再次保存创建新来源，用户拿到真实成功而不是指向已删除资料的假成功。
        // SRC-03：内容摘要也进幂等键。只按 (generated_by, generated_ref_id) 复用时，
        // 同一条消息改过内容再保存会拿到旧来源，用户以为保存成功、其实内容还是旧的。
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        List<GeneratedSourceResult> existing = jdbcTemplate.query("""
                select s.id, s.status, s.parse_status, s.index_status, s.generated_by, s.generated_ref_id
                from source s
                join file_object f on f.id = s.file_object_id
                where s.workspace_id = ? and s.generated_by = ? and s.generated_ref_id = ?
                  and s.status <> 'DELETED' and f.sha256 = ?
                order by s.created_at asc, s.id asc
                limit 1
                """, (rs, rowNum) -> new GeneratedSourceResult(
                rs.getString("id"),
                rs.getString("status"),
                rs.getString("parse_status"),
                rs.getString("index_status"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id")
        ), workspaceId, generatedBy, generatedRefId, digest);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        FileObjectRef fileObject = getOrCreateFileObject(workspaceId, title, digest, bytes);
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String snapshotObjectKey = "workspace/%s/generated/%s/%s/%s/final.md".formatted(
                workspaceId,
                sanitize(generatedBy),
                sanitize(generatedRefId),
                // DATA-03：键里带快照 id。此前同一引用重新生成会复用同一个键，
                // 新写入会覆盖旧快照的正文，旧来源的清理任务也可能把新对象删掉。
                snapshotId
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
        String taskId = taskCommandPort.createTask(
                workspaceId, "SOURCE_PARSE", "SOURCE", sourceId, "PARSING", "资料解析与切片");
        if (asyncParseEnabled && messagingMode.isAsyncEnabled()) {
            // SRC-01：保存接口只写对象、元数据与 outbox，解析交给异步消费，
            // 不再在事务里同步解析（Note 保存请求此前可挂 180s）。
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("sourceId", sourceId);
            payload.put("snapshotId", snapshotId);
            payload.put("workspaceId", workspaceId);
            // SRC-04：把任务代次带进消息。人工重驱会复用 task id 并递增代次，
            // 死信收尾时比对，重驱之前的那条投递自动过期。
            Integer taskGeneration = taskGenerationOrNull(taskId);
            if (taskGeneration != null) payload.put("taskGeneration", taskGeneration);
            jdbcTemplate.update("""
                    insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                    values (?, ?, ?, ?, ?, 'READY')
                    """, Ids.newId(), taskId, topicNames.parse(), sourceId,
                    Json.write(objectMapper, payload));
        } else {
            // 未启用 Kafka（本地/测试）或显式回滚时保留同步路径，保证保存后立即可用
            taskCommandPort.startTask(taskId);
            sourceParsePort.parseAndIndex(workspaceId, sourceId, snapshotId, bytes);
        }
        // Wiki 编译由资料索引完成后的 SourceRetrievalReadyEvent 触发
        return load(workspaceId, sourceId);
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
        if (fileLifecycle != null) {
            // NR13：与上传共用生命周期协议——DELETE_CLAIMED 明确待重试，DELETED 换新 key 换代，
            // 不再裸复用待删除对象（旧票据不可变 key 不会误删新来源对象）
            String freshKey = "workspace/%s/file_object/%s-%s.md".formatted(
                    workspaceId, java.util.UUID.randomUUID().toString().replace("-", ""), sanitize(title));
            com.noteweave.storage.FileObjectLifecycleService.AcquireResult acquired =
                    fileLifecycle.acquireOrPrepare(workspaceId, digest, bytes.length, "text/markdown", freshKey);
            if (acquired.activated()) {
                storage.write(SOURCE_BUCKET, acquired.objectKey(), bytes);
            } else if (!storage.exists(SOURCE_BUCKET, acquired.objectKey())) {
                // 恢复共享 ACTIVE 行缺失的对象：对象属于全部引用者
                storage.write(SOURCE_BUCKET, acquired.objectKey(), bytes);
            }
            return new FileObjectRef(acquired.fileObjectId(), acquired.objectKey());
        }
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
    /** SRC-04：任务代次。精简测试 schema 可能没有 task 表，取不到就按「不带代次」处理。 */
    private Integer taskGenerationOrNull(String taskId) {
        try {
            return jdbcTemplate.queryForObject(
                    "select generation from task where id = ?", Integer.class, taskId);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
