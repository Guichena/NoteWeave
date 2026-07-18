package com.noteweave.source;

import com.noteweave.common.BusinessException;
import com.noteweave.conversation.RunReplayRedactionService;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.task.TaskCommandPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final SourceWikiCommandPort wikiCommandPort;
    private final AuditActorProvider auditActorProvider;
    private final SourceCatalogCache sourceCatalogCache;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final MeterRegistry meterRegistry;
    private final TaskCommandPort taskCommandPort;
    private final ApplicationEventPublisher eventPublisher;
    private final RunReplayRedactionService runReplayRedactionService;

    public SourceService(
            JdbcTemplate jdbcTemplate,
            WorkspaceAccessGuard workspaceAccessGuard,
            SourceWikiCommandPort wikiCommandPort,
            AuditActorProvider auditActorProvider,
            SourceCatalogCache sourceCatalogCache,
            SourceCatalogVersionService sourceCatalogVersionService,
            MeterRegistry meterRegistry,
            TaskCommandPort taskCommandPort,
            ApplicationEventPublisher eventPublisher,
            RunReplayRedactionService runReplayRedactionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.wikiCommandPort = wikiCommandPort;
        this.auditActorProvider = auditActorProvider;
        this.sourceCatalogCache = sourceCatalogCache;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.meterRegistry = meterRegistry;
        this.taskCommandPort = taskCommandPort;
        this.eventPublisher = eventPublisher;
        this.runReplayRedactionService = runReplayRedactionService;
    }

    public List<SourceResponse> listSources(String workspaceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.WORKSPACE_READ);
        long catalogVersion = sourceCatalogVersionService.current(workspaceId);
        return sourceCatalogCache.get(workspaceId, catalogVersion)
                .orElseGet(() -> loadSources(workspaceId, catalogVersion));
    }

    private List<SourceResponse> loadSources(String workspaceId, long catalogVersion) {
        long startedAt = System.nanoTime();
        List<SourceResponse> sources;
        try {
            sources = jdbcTemplate.query("""
                    select id, title, source_type, status, parse_status, index_status,
                           coalesce(generated_by, '') as generated_by,
                           coalesce(generated_ref_id, '') as generated_ref_id,
                           updated_at
                    from source
                    where workspace_id = ? and status <> 'DELETED'
                    order by updated_at desc, id desc
                    """, (rs, rowNum) -> new SourceResponse(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("source_type"),
                    rs.getString("status"),
                    rs.getString("parse_status"),
                    rs.getString("index_status"),
                    rs.getString("generated_by"),
                    rs.getString("generated_ref_id"),
                    toInstant(rs.getTimestamp("updated_at"))
            ), workspaceId);
        } catch (RuntimeException exception) {
            recordDatabaseLoad("error", startedAt);
            throw exception;
        }
        recordDatabaseLoad("success", startedAt);
        sourceCatalogCache.put(workspaceId, catalogVersion, sources);
        return sources;
    }

    private void recordDatabaseLoad(String result, long startedAt) {
        meterRegistry.timer("noteweave.source.catalog.db.load", "result", result)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    @Transactional
    public DeleteSourceResponse deleteSource(String workspaceId, String sourceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.SOURCE_WRITE);
        String actor = auditActorProvider.currentOrSystem("SOURCE");
        SourceRef source = loadSource(workspaceId, sourceId);
        jdbcTemplate.update("""
                update source
                set status = 'DELETED', parse_status = 'DELETED', index_status = 'DELETED',
                    updated_by = ?, updated_at = current_timestamp
                where workspace_id = ? and id = ?
                """, actor, workspaceId, sourceId);
        jdbcTemplate.update("""
                update source_snapshot
                set parse_status = 'DELETED', index_status = 'DELETED'
                where source_id = ?
                """, sourceId);
        runReplayRedactionService.redactDeletedSource(workspaceId, sourceId);
        cancelPendingSourceTasks(workspaceId, sourceId);
        String cleanupObjectKey = releaseFileObject(source.fileObjectId()) ? source.objectKey() : "";
        sourceCatalogVersionService.bump(workspaceId);
        String wikiTaskId = wikiCommandPort.requestSourceRetract(workspaceId, sourceId, source.title());
        eventPublisher.publishEvent(new SourceDeletedEvent(workspaceId, sourceId, cleanupObjectKey));
        return new DeleteSourceResponse(sourceId, "DELETED", wikiTaskId);
    }

    private boolean releaseFileObject(String fileObjectId) {
        jdbcTemplate.update("""
                update file_object
                set ref_count = case when ref_count > 0 then ref_count - 1 else 0 end
                where id = ?
                """, fileObjectId);
        Integer remaining = jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?", Integer.class, fileObjectId);
        return remaining != null && remaining == 0;
    }

    private void cancelPendingSourceTasks(String workspaceId, String sourceId) {
        List<String> taskIds = jdbcTemplate.queryForList("""
                select id from task
                where workspace_id = ? and target_type = 'SOURCE' and target_id = ?
                  and task_type in ('SOURCE_PARSE', 'WIKI_INGEST')
                  and task_status in ('PENDING', 'RUNNING', 'WAITING')
                """, String.class, workspaceId, sourceId);
        for (String taskId : taskIds) {
            jdbcTemplate.update("""
                    update task_outbox
                    set status = 'CANCELLED', claimed_at = null, lease_owner = null, lease_until = null,
                        last_error = 'SOURCE_DELETED'
                    where task_id = ? and status in ('READY', 'PROCESSING')
                    """, taskId);
            taskCommandPort.cancelTask(taskId, "SOURCE_DELETED", "资料已删除，已取消后续处理", sourceId);
        }
    }

    private SourceRef loadSource(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select s.id, s.title, s.status, s.file_object_id, f.object_key
                from source s
                join file_object f on f.id = s.file_object_id
                where s.workspace_id = ? and s.id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            if ("DELETED".equals(rs.getString("status"))) {
                throw new BusinessException("SOURCE_ALREADY_DELETED", "资料已经删除");
            }
            return new SourceRef(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("file_object_id"),
                    rs.getString("object_key"));
        }, workspaceId, sourceId);
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record SourceRef(String sourceId, String title, String fileObjectId, String objectKey) {
    }
}
