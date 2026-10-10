package com.noteweave.research;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Materializes the Workspace-owned report, adopted sources, and durable research notes. */
@Service
public class ResearchCollectionService {
    private final JdbcTemplate jdbcTemplate;
    private final ResearchContextV2Gate contextGate;

    public ResearchCollectionService(JdbcTemplate jdbcTemplate, ResearchContextV2Gate contextGate) {
        this.jdbcTemplate = jdbcTemplate;
        this.contextGate = contextGate;
    }

    @Transactional
    public ResearchCollectionResponse materialize(String runId) {
        contextGate.requireReadable(runId);
        Run run = requireCompletedRun(null, runId, true);
        String collectionId = jdbcTemplate.query("""
                select id from research_collection where research_run_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, runId);
        if (collectionId == null) {
            collectionId = Ids.newId();
            jdbcTemplate.update("""
                    insert into research_collection(id, workspace_id, research_run_id, title, status)
                    values (?, ?, ?, ?, 'ACTIVE')
                    """, collectionId, run.workspaceId(), run.id(), run.title());
        } else {
            jdbcTemplate.update("""
                    update research_collection set title = ?, status = 'ACTIVE', updated_at = current_timestamp
                    where id = ?
                    """, run.title(), collectionId);
        }
        materializeSources(run, collectionId);
        materializeNotes(run, collectionId);
        return get(run.workspaceId(), run.id());
    }

    public ResearchCollectionResponse get(String workspaceId, String runId) {
        contextGate.requireReadable(runId);
        Run run = requireCompletedRun(workspaceId, runId, false);
        CollectionRow collection = jdbcTemplate.query("""
                select id, title, status, created_at, updated_at
                from research_collection where workspace_id = ? and research_run_id = ?
                """, rs -> rs.next() ? new CollectionRow(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()) : null,
                workspaceId, runId);
        if (collection == null) {
            throw new BusinessException("RESEARCH_COLLECTION_NOT_FOUND", "Research Collection does not exist");
        }
        List<ResearchCollectionResponse.AdoptedSource> sources = jdbcTemplate.query("""
                select source_kind, evidence_id, source_id, source_snapshot_key, source_url,
                       source_domain, title, excerpt, citation_count
                from research_collection_source where collection_id = ? order by adopted_at, id
                """, (rs, rowNum) -> new ResearchCollectionResponse.AdoptedSource(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9)), collection.id());
        List<ResearchCollectionResponse.Note> notes = jdbcTemplate.query("""
                select note_key, note_type, title, content, evidence_refs_json
                from research_note where collection_id = ? and note_status = 'ACTIVE'
                order by note_type, note_key
                """, (rs, rowNum) -> new ResearchCollectionResponse.Note(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                collection.id());
        return new ResearchCollectionResponse(
                collection.id(), run.workspaceId(), run.id(), collection.title(), collection.status(),
                new ResearchCollectionResponse.Report(run.title(), run.markdown()), sources, notes,
                collection.createdAt(), collection.updatedAt());
    }

    private void materializeSources(Run run, String collectionId) {
        List<SourceCandidate> candidates = jdbcTemplate.query("""
                select item.evidence_id, item.source_id, item.source_snapshot_id, item.title, item.excerpt,
                       external.source_url, external.source_domain
                from research_evidence_manifest manifest
                join research_evidence_manifest_item item on item.manifest_id = manifest.id
                left join research_external_snapshot external
                  on external.research_run_id = manifest.research_run_id
                 and external.snapshot_key = item.source_snapshot_id
                where manifest.research_run_id = ?
                order by item.rank_no
                """, (rs, rowNum) -> new SourceCandidate(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7)), run.id());
        Map<String, SourceAggregate> aggregated = new LinkedHashMap<>();
        for (SourceCandidate candidate : candidates) {
            String key = sourceKey(candidate);
            aggregated.compute(key, (ignored, current) -> current == null
                    ? new SourceAggregate(candidate, 1)
                    : new SourceAggregate(current.candidate(), current.citationCount() + 1));
        }
        for (Map.Entry<String, SourceAggregate> entry : aggregated.entrySet()) {
            SourceAggregate aggregate = entry.getValue();
            SourceCandidate source = aggregate.candidate();
            Integer existing = jdbcTemplate.queryForObject("""
                    select count(*) from research_collection_source where collection_id = ? and source_key = ?
                    """, Integer.class, collectionId, entry.getKey());
            if (existing != null && existing > 0) {
                jdbcTemplate.update("""
                        update research_collection_source set citation_count = ?, excerpt = ?
                        where collection_id = ? and source_key = ?
                        """, aggregate.citationCount(), source.excerpt(), collectionId, entry.getKey());
            } else {
                jdbcTemplate.update("""
                        insert into research_collection_source(
                            id, collection_id, source_key, source_kind, evidence_id, source_id,
                            source_snapshot_key, source_url, source_domain, title, excerpt, citation_count
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Ids.newId(), collectionId, entry.getKey(),
                        isWebSource(source) ? "WEB" : "SEED", source.evidenceId(), source.sourceId(),
                        source.snapshotKey(), source.sourceUrl(), source.sourceDomain(), source.title(),
                        source.excerpt(), aggregate.citationCount());
            }
        }
    }

    private void materializeNotes(Run run, String collectionId) {
        List<NoteCandidate> notes = jdbcTemplate.query("""
                select cell_key, column_key, candidate_value, evidence_refs_json
                from research_cell
                where research_run_id = ? and cell_status = 'VERIFIED' and candidate_value is not null
                order by cell_key
                """, (rs, rowNum) -> new NoteCandidate(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), run.id());
        for (NoteCandidate note : notes) {
            String type = note.columnKey() != null && note.columnKey().toLowerCase().contains("limitation")
                    ? "LIMITATION" : "FINDING";
            Integer existing = jdbcTemplate.queryForObject("""
                    select count(*) from research_note where collection_id = ? and note_key = ?
                    """, Integer.class, collectionId, note.cellKey());
            if (existing != null && existing > 0) {
                jdbcTemplate.update("""
                        update research_note set note_type = ?, title = ?, content = ?, evidence_refs_json = ?,
                            note_status = 'ACTIVE', updated_at = current_timestamp
                        where collection_id = ? and note_key = ?
                        """, type, note.columnKey(), note.content(), note.evidenceRefsJson(), collectionId, note.cellKey());
            } else {
                jdbcTemplate.update("""
                        insert into research_note(
                            id, collection_id, note_key, note_type, title, content, evidence_refs_json
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """, Ids.newId(), collectionId, note.cellKey(), type,
                        note.columnKey(), note.content(), note.evidenceRefsJson());
            }
        }
        if (notes.isEmpty()) {
            String noteKey = "report-summary";
            Integer existing = jdbcTemplate.queryForObject("""
                    select count(*) from research_note where collection_id = ? and note_key = ?
                    """, Integer.class, collectionId, noteKey);
            if (existing == null || existing == 0) {
                jdbcTemplate.update("""
                        insert into research_note(
                            id, collection_id, note_key, note_type, title, content, evidence_refs_json
                        ) values (?, ?, ?, 'SUMMARY', ?, ?, '[]')
                        """, Ids.newId(), collectionId, noteKey, run.title(), boundedSummary(run.markdown()));
            }
        }
    }

    private Run requireCompletedRun(String workspaceId, String runId, boolean lock) {
        String sql = """
                select id, workspace_id, status, final_report_title, final_report_markdown
                from research_run where id = ?%s
                """.formatted(lock ? " for update" : "");
        Run run = jdbcTemplate.query(sql, rs -> rs.next() ? new Run(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) : null, runId);
        if (run == null || (workspaceId != null && !workspaceId.equals(run.workspaceId()))) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "Research run does not exist");
        }
        if (!"COMPLETED".equals(run.status()) || run.markdown() == null || run.markdown().isBlank()) {
            throw new BusinessException("RESEARCH_COLLECTION_NOT_READY", "Research run is not ready for collection");
        }
        return run;
    }

    private String sourceKey(SourceCandidate source) {
        if (source.snapshotKey() != null && !source.snapshotKey().isBlank()) return "snapshot:" + source.snapshotKey();
        if (source.sourceId() != null && !source.sourceId().isBlank()) return "source:" + source.sourceId();
        return "evidence:" + source.evidenceId();
    }

    private boolean isWebSource(SourceCandidate source) {
        return source.sourceUrl() != null
                || (source.sourceId() != null && source.sourceId().startsWith("external:"));
    }

    private String boundedSummary(String markdown) {
        String value = markdown == null ? "" : markdown.trim();
        return value.length() <= 4_000 ? value : value.substring(0, 4_000);
    }

    private record Run(String id, String workspaceId, String status, String title, String markdown) { }
    private record CollectionRow(String id, String title, String status, java.time.Instant createdAt,
                                 java.time.Instant updatedAt) { }
    private record SourceCandidate(String evidenceId, String sourceId, String snapshotKey, String title,
                                   String excerpt, String sourceUrl, String sourceDomain) { }
    private record SourceAggregate(SourceCandidate candidate, int citationCount) { }
    private record NoteCandidate(String cellKey, String columnKey, String content, String evidenceRefsJson) { }
}
