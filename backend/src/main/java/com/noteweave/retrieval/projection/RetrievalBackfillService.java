package com.noteweave.retrieval.projection;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.CreateIndexBuild;
import com.noteweave.retrieval.projection.RetrievalIndexBuildRepository.IndexBuild;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RetrievalBackfillService {
    private static final String PROVIDER = "openai-compatible";

    private final JdbcTemplate jdbcTemplate;
    private final NoteWeaveProperties properties;
    private final RetrievalIndexBuildRepository buildRepository;
    private final SourceRetrievalProjectionService projectionService;
    private final RetrievalIndexManager indexManager;

    public RetrievalBackfillService(
            JdbcTemplate jdbcTemplate,
            NoteWeaveProperties properties,
            RetrievalIndexBuildRepository buildRepository,
            SourceRetrievalProjectionService projectionService,
            RetrievalIndexManager indexManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.buildRepository = buildRepository;
        this.projectionService = projectionService;
        this.indexManager = indexManager;
    }

    public BackfillResult rebuildWorkspace(String workspaceId) {
        List<SnapshotTarget> targets = currentSnapshots(workspaceId);
        long qaExpected = targets.stream().mapToLong(SnapshotTarget::chunkCount).sum();
        long noteExpected = targets.size();
        String model = properties.embedding().model();
        int dimensions = properties.embedding().dimensions();
        String embeddingVersion = model + ":" + dimensions;
        String generation = "build-" + UUID.randomUUID().toString().substring(0, 8);
        String qaTarget = RetrievalIndexNames.physical(
                ProjectionType.QA_CHUNK, SourceRetrievalProjectionService.QA_SCHEMA_VERSION,
                embeddingVersion + ":" + generation, workspaceId);
        String noteTarget = RetrievalIndexNames.physical(
                ProjectionType.NOTE_SOURCE, SourceRetrievalProjectionService.NOTE_SCHEMA_VERSION,
                embeddingVersion + ":" + generation, workspaceId);
        IndexBuild qaBuild = createBuild(workspaceId, ProjectionType.QA_CHUNK, qaTarget,
                SourceRetrievalProjectionService.QA_SCHEMA_VERSION, embeddingVersion, qaExpected);
        IndexBuild noteBuild = createBuild(workspaceId, ProjectionType.NOTE_SOURCE, noteTarget,
                SourceRetrievalProjectionService.NOTE_SCHEMA_VERSION, embeddingVersion, noteExpected);
        indexManager.createIndex(ProjectionType.QA_CHUNK, qaTarget, dimensions);
        indexManager.createIndex(ProjectionType.NOTE_SOURCE, noteTarget, dimensions);
        buildRepository.startBackfill(qaBuild.id());
        buildRepository.startBackfill(noteBuild.id());
        List<String> failedSources = new ArrayList<>();
        try {
            for (SnapshotTarget target : targets) {
                try {
                    projectionService.projectSnapshotToIndexes(
                            workspaceId, target.sourceId(), target.snapshotId(), qaTarget, noteTarget);
                } catch (RuntimeException ex) {
                    failedSources.add(target.sourceId());
                }
            }
            long qaReady = readyCount(workspaceId, ProjectionType.QA_CHUNK, qaTarget);
            long noteReady = readyCount(workspaceId, ProjectionType.NOTE_SOURCE, noteTarget);
            long qaFailed = Math.max(0, qaExpected - qaReady);
            long noteFailed = Math.max(0, noteExpected - noteReady);
            buildRepository.updateCounts(qaBuild.id(), qaReady, qaFailed);
            buildRepository.updateCounts(noteBuild.id(), noteReady, noteFailed);
            if (!failedSources.isEmpty() || qaReady != qaExpected || noteReady != noteExpected) {
                buildRepository.fail(qaBuild.id(), "RETRIEVAL_BACKFILL_INCOMPLETE");
                buildRepository.fail(noteBuild.id(), "RETRIEVAL_BACKFILL_INCOMPLETE");
                return result(workspaceId, qaBuild.id(), noteBuild.id(), failedSources);
            }
            buildRepository.startVerification(qaBuild.id());
            buildRepository.startVerification(noteBuild.id());
            buildRepository.startAliasSwitch(qaBuild.id());
            buildRepository.startAliasSwitch(noteBuild.id());
            indexManager.switchAliases(java.util.Map.of(
                    RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, workspaceId), qaTarget,
                    RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, workspaceId), noteTarget));
            buildRepository.complete(qaBuild.id());
            buildRepository.complete(noteBuild.id());
            return result(workspaceId, qaBuild.id(), noteBuild.id(), List.of());
        } catch (RuntimeException ex) {
            failIfActive(qaBuild.id(), "RETRIEVAL_BACKFILL_FAILED");
            failIfActive(noteBuild.id(), "RETRIEVAL_BACKFILL_FAILED");
            throw ex;
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
                buildRepository.findByWorkspace(workspaceId), projectionCounts);
    }

    private IndexBuild createBuild(String workspaceId, ProjectionType type, String targetIndex,
                                   String schema, String embeddingVersion, long expected) {
        return buildRepository.create(new CreateIndexBuild(workspaceId, type, null, targetIndex,
                PROVIDER, properties.embedding().model(), properties.embedding().dimensions(),
                embeddingVersion, schema, expected));
    }

    private List<SnapshotTarget> currentSnapshots(String workspaceId) {
        return jdbcTemplate.query("""
                select s.id source_id, ss.id snapshot_id, count(c.id) chunk_count
                from source s
                join source_snapshot ss on ss.source_id = s.id
                  and ss.version_no = (select max(x.version_no) from source_snapshot x where x.source_id = s.id)
                join source_chunk c on c.source_snapshot_id = ss.id
                where s.workspace_id = ? and s.status <> 'DELETED' and ss.parse_status = 'PARSED'
                group by s.id, ss.id order by s.id
                """, (rs, rowNum) -> new SnapshotTarget(rs.getString("source_id"),
                rs.getString("snapshot_id"), rs.getLong("chunk_count")), workspaceId);
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

    private BackfillResult result(String workspaceId, String qaBuildId, String noteBuildId,
                                  List<String> failedSources) {
        return new BackfillResult(workspaceId, buildRepository.findById(qaBuildId),
                buildRepository.findById(noteBuildId), List.copyOf(failedSources));
    }

    private record SnapshotTarget(String sourceId, String snapshotId, long chunkCount) { }
    public record ProjectionCount(String projectionType, String status, long count) { }
    public record Coverage(long sourceCount, long readySourceCount,
                           long qaReadySourceCount, long noteReadySourceCount) { }
    public record RetrievalStatus(String workspaceId, Coverage coverage,
                                  List<IndexBuild> builds, List<ProjectionCount> projections) { }
    public record BackfillResult(String workspaceId, IndexBuild qaBuild,
                                 IndexBuild noteBuild, List<String> failedSourceIds) { }
}
