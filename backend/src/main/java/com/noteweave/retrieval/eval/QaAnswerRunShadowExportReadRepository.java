package com.noteweave.retrieval.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns the batch SQL and row mapping used by the QA AnswerRun shadow export. */
@Repository
class QaAnswerRunShadowExportReadRepository {

    private final JdbcTemplate jdbcTemplate;

    QaAnswerRunShadowExportReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Map<String, RunRow> loadRuns(List<String> runIds) {
        if (runIds.size() > 500) {
            throw new IllegalArgumentException(
                    "QA AnswerRun shadow export supports at most 500 cases per batch");
        }
        List<RunRow> rows = jdbcTemplate.query("""
                        select r.id, r.workspace_id, r.conversation_id, r.query_message_id,
                               r.answer_message_id, r.mode, r.status,
                               r.retrieval_plan_version, r.retrieval_plan_json,
                               r.evidence_bundle_json,
                               qm.role as query_role, qm.answer_mode as query_answer_mode,
                               qm.content as query_text,
                               am.role as answer_role, am.answer_mode as answer_answer_mode
                        from answer_run r
                        join conversation_message qm
                          on qm.id = r.query_message_id
                         and qm.workspace_id = r.workspace_id
                         and qm.conversation_id = r.conversation_id
                        join conversation_message am
                          on am.id = r.answer_message_id
                         and am.workspace_id = r.workspace_id
                         and am.conversation_id = r.conversation_id
                        where r.id in (%s)
                        """.formatted(placeholders(runIds.size())),
                (rs, rowNum) -> new RunRow(
                        rs.getString("id"),
                        rs.getString("workspace_id"),
                        rs.getString("conversation_id"),
                        rs.getString("query_message_id"),
                        text(rs.getString("answer_message_id")),
                        rs.getString("mode"),
                        rs.getString("status"),
                        text(rs.getString("retrieval_plan_version")),
                        text(rs.getString("retrieval_plan_json")),
                        text(rs.getString("evidence_bundle_json")),
                        text(rs.getString("query_role")),
                        text(rs.getString("query_answer_mode")),
                        text(rs.getString("query_text")),
                        text(rs.getString("answer_role")),
                        text(rs.getString("answer_answer_mode"))),
                runIds.toArray());
        Map<String, RunRow> byId = new LinkedHashMap<>();
        rows.forEach(row -> {
            if (byId.putIfAbsent(row.id(), row) != null) {
                throw new IllegalStateException("Duplicate AnswerRun row in export batch");
            }
        });
        requireAll(runIds, byId, "AnswerRun");
        return Map.copyOf(byId);
    }

    Map<String, String> loadRetrievalSummaries(List<String> runIds) {
        List<EventRow> rows = jdbcTemplate.query("""
                        select e.answer_run_id, e.payload_json
                        from answer_event e
                        join answer_run r
                          on r.id = e.answer_run_id and r.workspace_id = e.workspace_id
                        where e.answer_run_id in (%s) and e.event_type = 'retrieval.summary'
                        order by e.answer_run_id asc, e.seq asc
                        """.formatted(placeholders(runIds.size())),
                (rs, rowNum) -> new EventRow(
                        rs.getString("answer_run_id"), text(rs.getString("payload_json"))),
                runIds.toArray());
        Map<String, String> byRun = new LinkedHashMap<>();
        for (EventRow row : rows) {
            if (row.payloadJson().isBlank()
                    || byRun.putIfAbsent(row.answerRunId(), row.payloadJson()) != null) {
                throw new IllegalStateException(
                        "Each AnswerRun must have exactly one retrieval.summary event");
            }
        }
        requireAll(runIds, byRun, "retrieval.summary");
        return Map.copyOf(byRun);
    }

    Map<String, List<CitationRow>> loadCitations(List<String> runIds) {
        List<CitationRow> rows = jdbcTemplate.query("""
                        select r.id as answer_run_id,
                               c.id as citation_id,
                               c.workspace_id as citation_workspace_id,
                               c.source_id,
                               c.source_snapshot_id,
                               c.source_chunk_id,
                               mc.sort_order,
                               s.workspace_id as source_workspace_id,
                               ss.source_id as snapshot_source_id,
                               sc.workspace_id as chunk_workspace_id,
                               sc.source_id as chunk_source_id,
                               sc.source_snapshot_id as chunk_snapshot_id
                        from answer_run r
                        join message_citation mc on mc.message_id = r.answer_message_id
                        join citation c on c.id = mc.citation_id
                        join source s on s.id = c.source_id
                        join source_snapshot ss on ss.id = c.source_snapshot_id
                        join source_chunk sc on sc.id = c.source_chunk_id
                        where r.id in (%s)
                        order by r.id asc, mc.sort_order asc, mc.id asc
                        """.formatted(placeholders(runIds.size())),
                (rs, rowNum) -> new CitationRow(
                        rs.getString("answer_run_id"),
                        rs.getString("citation_id"),
                        rs.getString("citation_workspace_id"),
                        rs.getString("source_id"),
                        rs.getString("source_snapshot_id"),
                        rs.getString("source_chunk_id"),
                        rs.getInt("sort_order"),
                        rs.getString("source_workspace_id"),
                        rs.getString("snapshot_source_id"),
                        rs.getString("chunk_workspace_id"),
                        rs.getString("chunk_source_id"),
                        rs.getString("chunk_snapshot_id")),
                runIds.toArray());
        Map<String, List<CitationRow>> mutable = new LinkedHashMap<>();
        rows.forEach(row -> mutable.computeIfAbsent(
                row.answerRunId(), ignored -> new ArrayList<>()).add(row));
        Map<String, List<CitationRow>> immutable = new LinkedHashMap<>();
        mutable.forEach((runId, citations) -> immutable.put(runId, List.copyOf(citations)));
        return Map.copyOf(immutable);
    }

    private <T> void requireAll(List<String> ids, Map<String, T> values, String label) {
        for (String id : ids) {
            if (!values.containsKey(id)) {
                throw new IllegalStateException(label + " is missing for one requested AnswerRun");
            }
        }
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    record RunRow(
            String id,
            String workspaceId,
            String conversationId,
            String queryMessageId,
            String answerMessageId,
            String mode,
            String status,
            String planVersion,
            String planJson,
            String bundleJson,
            String queryRole,
            String queryAnswerMode,
            String queryText,
            String answerRole,
            String answerAnswerMode
    ) {
    }

    private record EventRow(String answerRunId, String payloadJson) {
    }

    record CitationRow(
            String answerRunId,
            String citationId,
            String workspaceId,
            String sourceId,
            String sourceSnapshotId,
            String sourceChunkId,
            int sortOrder,
            String sourceWorkspaceId,
            String snapshotSourceId,
            String chunkWorkspaceId,
            String chunkSourceId,
            String chunkSnapshotId
    ) {
    }
}
