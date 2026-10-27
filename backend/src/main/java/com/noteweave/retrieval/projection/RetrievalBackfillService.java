package com.noteweave.retrieval.projection;

import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.CreateIndexBuild;
import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.IndexBuild;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import com.noteweave.source.SourceCatalogVersionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetrievalBackfillService {
    private static final Logger log = LoggerFactory.getLogger(RetrievalBackfillService.class);
    private static final String PROVIDER = "openai-compatible";

    private final JdbcTemplate jdbcTemplate;
    private final NoteWeaveProperties properties;
    private final RetrievalIndexBuildRepository buildRepository;
    private final SourceRetrievalProjectionService projectionService;
    private final RetrievalIndexManager indexManager;
    private final SourceCatalogVersionService sourceCatalogVersionService;
    private final RetrievalBackfillSourceFinalizer sourceFinalizer;
    private final RetrievalRebuildLockRepository lockRepository;
    private final Executor rebuildExecutor;
    private final int pageSize;

    public RetrievalBackfillService(
            JdbcTemplate jdbcTemplate,
            NoteWeaveProperties properties,
            RetrievalIndexBuildRepository buildRepository,
            SourceRetrievalProjectionService projectionService,
            RetrievalIndexManager indexManager,
            SourceCatalogVersionService sourceCatalogVersionService,
            RetrievalBackfillSourceFinalizer sourceFinalizer,
            RetrievalRebuildLockRepository lockRepository,
            @Qualifier("retrievalRebuildExecutor") Executor rebuildExecutor,
            @Value("${noteweave.retrieval.rebuild-page-size:200}") int pageSize
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.buildRepository = buildRepository;
        this.projectionService = projectionService;
        this.indexManager = indexManager;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.sourceFinalizer = sourceFinalizer;
        this.lockRepository = lockRepository;
        this.rebuildExecutor = rebuildExecutor;
        this.pageSize = Math.max(1, pageSize);
    }

    /**
     * 启动一次全量重建（IDX-04）。抢到工作台锁后立刻返回构建单号，
     * 实际回填在专用线程池里按来源分页跑完，大工作台不会把请求线程占死。
     * 同一工作台已有重建在跑时直接拒绝，两次重建不会互相覆盖别名。
     */
    public BackfillResult startRebuild(String workspaceId) {
        String token = UUID.randomUUID().toString();
        if (!lockRepository.tryAcquire(workspaceId, token)) {
            throw new BusinessException("RETRIEVAL_REBUILD_IN_PROGRESS",
                    "该工作台已有索引重建在进行中，请等待完成后重试");
        }
        IndexBuild qaBuild;
        IndexBuild noteBuild;
        RebuildJob job;
        try {
            long catalogVersion = sourceCatalogVersionService.current(workspaceId);
            Counts expected = expectedCounts(workspaceId);
            int dimensions = properties.embedding().dimensions();
            String embeddingVersion = projectionService.effectiveProjectionEmbeddingVersion();
            String generation = "build-" + UUID.randomUUID().toString().substring(0, 8);
            String qaTarget = RetrievalIndexNames.physical(
                    ProjectionType.QA_CHUNK, SourceRetrievalProjectionService.QA_SCHEMA_VERSION,
                    embeddingVersion + ":" + generation, workspaceId);
            String noteTarget = RetrievalIndexNames.physical(
                    ProjectionType.NOTE_SOURCE, SourceRetrievalProjectionService.NOTE_SCHEMA_VERSION,
                    embeddingVersion + ":" + generation, workspaceId);
            qaBuild = createBuild(workspaceId, ProjectionType.QA_CHUNK, qaTarget,
                    SourceRetrievalProjectionService.QA_SCHEMA_VERSION, embeddingVersion, expected.qaChunks());
            noteBuild = createBuild(workspaceId, ProjectionType.NOTE_SOURCE, noteTarget,
                    SourceRetrievalProjectionService.NOTE_SCHEMA_VERSION, embeddingVersion, expected.notes());
            indexManager.createIndex(ProjectionType.QA_CHUNK, qaTarget, dimensions);
            indexManager.createIndex(ProjectionType.NOTE_SOURCE, noteTarget, dimensions);
            buildRepository.startBackfill(qaBuild.id());
            buildRepository.startBackfill(noteBuild.id());
            job = new RebuildJob(workspaceId, token, qaBuild.id(), noteBuild.id(),
                    qaTarget, noteTarget, catalogVersion, expected.qaChunks(), expected.notes());
        } catch (RuntimeException ex) {
            lockRepository.release(workspaceId, token);
            throw ex;
        }
        try {
            rebuildExecutor.execute(() -> runRebuild(job));
        } catch (RuntimeException rejected) {
            lockRepository.release(workspaceId, token);
            failIfActive(qaBuild.id(), "RETRIEVAL_REBUILD_REJECTED");
            failIfActive(noteBuild.id(), "RETRIEVAL_REBUILD_REJECTED");
            throw new BusinessException("RETRIEVAL_REBUILD_BUSY", "索引重建队列已满，请稍后重试");
        }
        return result(workspaceId, qaBuild.id(), noteBuild.id(), List.of());
    }

    /** 后台重建主体：分页回填、核对总数、切别名、收尾，最后一定释放工作台锁。 */
    void runRebuild(RebuildJob job) {
        List<String> failedSources = new ArrayList<>();
        try {
            backfillPages(job, failedSources);
            requireStableCatalog(job.workspaceId(), job.catalogVersion());
            long qaReady = readyCount(job.workspaceId(), ProjectionType.QA_CHUNK, job.qaTarget());
            long noteReady = readyCount(job.workspaceId(), ProjectionType.NOTE_SOURCE, job.noteTarget());
            buildRepository.updateCounts(job.qaBuildId(), qaReady, Math.max(0, job.qaExpected() - qaReady));
            buildRepository.updateCounts(job.noteBuildId(), noteReady, Math.max(0, job.noteExpected() - noteReady));
            if (!failedSources.isEmpty() || qaReady != job.qaExpected() || noteReady != job.noteExpected()) {
                buildRepository.fail(job.qaBuildId(), "RETRIEVAL_BACKFILL_INCOMPLETE");
                buildRepository.fail(job.noteBuildId(), "RETRIEVAL_BACKFILL_INCOMPLETE");
                log.warn("索引重建未通过核对，保持原别名: workspaceId={}, qaReady={}/{}, noteReady={}/{}, failedSources={}",
                        job.workspaceId(), qaReady, job.qaExpected(), noteReady, job.noteExpected(),
                        failedSources.size());
                return;
            }
            buildRepository.startVerification(job.qaBuildId());
            buildRepository.startVerification(job.noteBuildId());
            buildRepository.startAliasSwitch(job.qaBuildId());
            buildRepository.startAliasSwitch(job.noteBuildId());
            indexManager.switchAliases(Map.of(
                    RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, job.workspaceId()), job.qaTarget(),
                    RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, job.workspaceId()), job.noteTarget()));
            requireStableCatalog(job.workspaceId(), job.catalogVersion());
            finalizePages(job);
            buildRepository.complete(job.qaBuildId());
            buildRepository.complete(job.noteBuildId());
        } catch (RuntimeException ex) {
            failIfActive(job.qaBuildId(), "RETRIEVAL_BACKFILL_FAILED");
            failIfActive(job.noteBuildId(), "RETRIEVAL_BACKFILL_FAILED");
            // 失败的新索引不会被别名引用，保留下来供排查，日志里给出名字方便人工清理
            log.warn("索引重建失败: workspaceId={}, qaTarget={}, noteTarget={}, cause={}",
                    job.workspaceId(), job.qaTarget(), job.noteTarget(), ex.toString());
        } finally {
            lockRepository.release(job.workspaceId(), job.token());
        }
    }

    private void backfillPages(RebuildJob job, List<String> failedSources) {
        String cursor = "";
        while (true) {
            List<SnapshotTarget> page = snapshotPage(job.workspaceId(), cursor);
            if (page.isEmpty()) {
                return;
            }
            for (SnapshotTarget target : page) {
                try {
                    projectionService.projectSnapshotToIndexes(
                            job.workspaceId(), target.sourceId(), target.snapshotId(),
                            job.qaTarget(), job.noteTarget());
                } catch (RuntimeException ex) {
                    failedSources.add(target.sourceId());
                }
            }
            if (page.size() < pageSize) {
                return;
            }
            cursor = page.get(page.size() - 1).sourceId();
        }
    }

    /** 切别名之后分页收尾：逐来源把片段与快照标记为已索引，避免一次性把全部来源读进内存。 */
    private void finalizePages(RebuildJob job) {
        String cursor = "";
        while (true) {
            List<SnapshotTarget> page = snapshotPage(job.workspaceId(), cursor);
            if (page.isEmpty()) {
                return;
            }
            sourceFinalizer.markIndexed(job.workspaceId(), page);
            if (page.size() < pageSize) {
                return;
            }
            cursor = page.get(page.size() - 1).sourceId();
        }
    }

    public RetrievalStatus status(String workspaceId) {
        List<ProjectionCount> projectionCounts = jdbcTemplate.query("""
                select projection_type, status, count(*) count_value
                from retrieval_projection where workspace_id = ?
                group by projection_type, status order by projection_type, status
                """, (rs, rowNum) -> new ProjectionCount(rs.getString("projection_type"),
                rs.getString("status"), rs.getLong("count_value")), workspaceId);
        Coverage coverage = jdbcTemplate.queryForObject("""
                select count(*) source_count,
                       sum(case when s.status = 'READY' and s.index_status = 'INDEXED' then 1 else 0 end) ready_source_count,
                       sum(case when qa.qa_count = chunks.chunk_count and chunks.chunk_count > 0 then 1 else 0 end) qa_ready_source_count,
                       sum(case when note.note_count = 1 then 1 else 0 end) note_ready_source_count
                from source s
                join source_snapshot ss on ss.source_id = s.id
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                left join (select source_snapshot_id, count(*) chunk_count from source_chunk group by source_snapshot_id) chunks
                  on chunks.source_snapshot_id = ss.id
                left join (select source_snapshot_id, count(distinct entity_id) qa_count from retrieval_projection
                           where projection_type = 'QA_CHUNK' and status = 'READY' group by source_snapshot_id) qa
                  on qa.source_snapshot_id = ss.id
                left join (select source_snapshot_id, count(distinct entity_id) note_count from retrieval_projection
                           where projection_type = 'NOTE_SOURCE' and status = 'READY' group by source_snapshot_id) note
                  on note.source_snapshot_id = ss.id
                where s.workspace_id = ? and s.status <> 'DELETED'
                """, (rs, rowNum) -> new Coverage(rs.getLong("source_count"),
                rs.getLong("ready_source_count"), rs.getLong("qa_ready_source_count"),
                rs.getLong("note_ready_source_count")), workspaceId);
        return new RetrievalStatus(workspaceId, coverage,
                buildRepository.findByWorkspace(workspaceId), projectionCounts,
                lockRepository.acquiredAt(workspaceId).orElse(null));
    }

    private IndexBuild createBuild(String workspaceId, ProjectionType type, String targetIndex,
                                   String schema, String embeddingVersion, long expected) {
        return buildRepository.create(new CreateIndexBuild(workspaceId, type, null, targetIndex,
                PROVIDER, properties.embedding().model(), properties.embedding().dimensions(),
                embeddingVersion, schema, expected));
    }

    /** 按来源主键做键集分页：大工作台不会一次把全部来源读进内存。 */
    private List<SnapshotTarget> snapshotPage(String workspaceId, String cursor) {
        return jdbcTemplate.query("""
                select s.id source_id, ss.id snapshot_id, count(c.id) chunk_count
                from source s
                join source_snapshot ss on ss.source_id = s.id
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                join source_chunk c on c.source_snapshot_id = ss.id
                where s.workspace_id = ? and s.status <> 'DELETED' and ss.parse_status = 'PARSED'
                  and s.id > ?
                group by s.id, ss.id order by s.id limit ?
                """, (rs, rowNum) -> new SnapshotTarget(rs.getString("source_id"),
                rs.getString("snapshot_id"), rs.getLong("chunk_count")), workspaceId, cursor, pageSize);
    }

    /** 期望数量用两条计数查询算出，不必先把所有来源读出来再求和。 */
    private Counts expectedCounts(String workspaceId) {
        Long qaChunks = jdbcTemplate.queryForObject("""
                select count(*) from source_chunk c
                join source_snapshot ss on ss.id = c.source_snapshot_id
                join source s on s.id = c.source_id
                where s.workspace_id = ? and s.status <> 'DELETED' and ss.parse_status = 'PARSED'
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                """, Long.class, workspaceId);
        Long notes = jdbcTemplate.queryForObject("""
                select count(distinct s.id) from source s
                join source_snapshot ss on ss.source_id = s.id
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                join source_chunk c on c.source_snapshot_id = ss.id
                where s.workspace_id = ? and s.status <> 'DELETED' and ss.parse_status = 'PARSED'
                """, Long.class, workspaceId);
        return new Counts(qaChunks == null ? 0 : qaChunks, notes == null ? 0 : notes);
    }

    private void failIfActive(String id, String code) {
        try { buildRepository.fail(id, code); } catch (IllegalStateException ignored) { }
    }

    private long readyCount(String workspaceId, ProjectionType type, String targetIndex) {
        Long count = jdbcTemplate.queryForObject("""
                select count(distinct entity_id) from retrieval_projection
                where workspace_id = ? and projection_type = ? and target_index = ? and status = 'READY'
                """, Long.class, workspaceId, type.name(), targetIndex);
        return count == null ? 0 : count;
    }

    void requireStableCatalog(String workspaceId, long expectedVersion) {
        if (sourceCatalogVersionService.current(workspaceId) != expectedVersion) {
            throw new IllegalStateException(
                    "Source catalog changed during retrieval backfill; rebuild must be retried");
        }
    }

    private BackfillResult result(String workspaceId, String qaBuildId, String noteBuildId,
                                  List<String> failedSources) {
        return new BackfillResult(workspaceId, buildRepository.findById(qaBuildId),
                buildRepository.findById(noteBuildId), List.copyOf(failedSources));
    }

    record RebuildJob(
            String workspaceId,
            String token,
            String qaBuildId,
            String noteBuildId,
            String qaTarget,
            String noteTarget,
            long catalogVersion,
            long qaExpected,
            long noteExpected
    ) {
    }

    private record Counts(long qaChunks, long notes) { }

    public record SnapshotTarget(String sourceId, String snapshotId, long chunkCount) { }
    public record ProjectionCount(String projectionType, String status, long count) { }
    public record Coverage(long sourceCount, long readySourceCount,
                           long qaReadySourceCount, long noteReadySourceCount) { }
    public record RetrievalStatus(String workspaceId, Coverage coverage,
                                  List<IndexBuild> builds, List<ProjectionCount> projections,
                                  java.time.Instant rebuildStartedAt) { }
    public record BackfillResult(String workspaceId, IndexBuild qaBuild,
                                 IndexBuild noteBuild, List<String> failedSourceIds) { }
}
