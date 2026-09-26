package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owns the SQL and row mapping for the research-run read model.
 *
 * <p>The facade still owns workspace authorization and read-model orchestration. This module only
 * loads persisted rows in the order and shape required by that facade.
 */
@Repository
public class ResearchRunReadRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchRunReadRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<ResearchRunListRow> listRows(String workspaceId) {
        return listRows(workspaceId, 50, 0);
    }

    public List<ResearchRunListRow> listRows(String workspaceId, int limit, int offset) {
        return jdbcTemplate.query("""
                select id, workspace_id, task_id, question, profile_key, source_scope_json,
                       resumed_from_research_run_id, resumed_from_checkpoint_no, status,
                       final_report_title, final_report_markdown, report_source_id, created_at, updated_at
                from research_run
                where workspace_id = ?
                  and (context_snapshot_id is null or exists (
                    select 1 from run_input_snapshot frozen
                    where frozen.id = research_run.context_snapshot_id
                      and frozen.research_run_id = research_run.id
                      and frozen.replay_availability = 'FULL'
                  ))
                order by updated_at desc, created_at desc, id desc
                limit ? offset ?
                """, (rs, rowNum) -> new ResearchRunListRow(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("task_id"),
                rs.getString("question"),
                rs.getString("profile_key"),
                rs.getString("source_scope_json"),
                blank(rs.getString("resumed_from_research_run_id")),
                (Integer) rs.getObject("resumed_from_checkpoint_no"),
                rs.getString("status"),
                blank(rs.getString("final_report_title")),
                rs.getString("final_report_markdown"),
                blank(rs.getString("report_source_id")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        ), workspaceId, limit, offset);
    }

    public ResearchRunReadBundle loadBatch(List<String> researchRunIds) {
        if (researchRunIds == null || researchRunIds.isEmpty()) {
            return ResearchRunReadBundle.empty();
        }
        Map<String, List<ResearchTraceResponse>> traces = loadTracesByRunIds(researchRunIds);
        Map<String, List<Map<String, Object>>> branches = loadPersistedBranchesByRunIds(researchRunIds);
        Map<String, List<Map<String, Object>>> rows = loadPersistedRowsByRunIds(researchRunIds);
        Map<String, List<Map<String, Object>>> cells = loadPersistedCellsByRunIds(researchRunIds);
        Map<String, List<Map<String, Object>>> sourceEvidence = loadPersistedSourceEvidenceByRunIds(researchRunIds);
        Map<String, List<Map<String, Object>>> verifierDecisions = loadPersistedVerifierDecisionsByRunIds(
                researchRunIds,
                sourceEvidence
        );
        Map<String, List<Map<String, Object>>> cellEvidence = loadPersistedCellEvidenceByRunIds(researchRunIds);
        return new ResearchRunReadBundle(
                traces,
                branches,
                rows,
                cells,
                sourceEvidence,
                verifierDecisions,
                cellEvidence
        );
    }

    public ResearchRunDetailRow findDetail(String workspaceId, String researchRunId) {
        return jdbcTemplate.query("""
                select id, workspace_id, task_id, question, profile_key, research_intent_json,
                       source_scope_json, control_pack_json, resumed_from_research_run_id,
                       resumed_from_checkpoint_no, status, completion_terminal_state, final_report_title,
                       final_report_markdown, trace_summary, report_source_id, created_at, updated_at
                from research_run
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
            }
            return new ResearchRunDetailRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    rs.getString("question"),
                    rs.getString("profile_key"),
                    rs.getString("research_intent_json"),
                    rs.getString("source_scope_json"),
                    rs.getString("control_pack_json"),
                    rs.getString("resumed_from_research_run_id"),
                    (Integer) rs.getObject("resumed_from_checkpoint_no"),
                    rs.getString("status"),
                    blank(rs.getString("completion_terminal_state")),
                    rs.getString("final_report_title"),
                    rs.getString("final_report_markdown"),
                    rs.getString("trace_summary"),
                    rs.getString("report_source_id"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, workspaceId, researchRunId);
    }

    public void requireRun(String workspaceId, String researchRunId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from research_run
                where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, researchRunId);
        if (count == null || count <= 0) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
        }
    }

    private Map<String, List<ResearchTraceResponse>> loadTracesByRunIds(List<String> researchRunIds) {
        return groupedValues(jdbcTemplate.query("""
                select research_run_id, id, trace_type, trace_message, payload_json, created_at
                from research_trace
                where research_run_id in (%s)
                order by research_run_id, created_at asc, id asc
                """.formatted(placeholders(researchRunIds.size())), (rs, rowNum) -> new RunValue<>(
                rs.getString("research_run_id"),
                new ResearchTraceResponse(
                        rs.getString("id"),
                        rs.getString("trace_type"),
                        rs.getString("trace_message"),
                        readPayloadMap(rs.getString("payload_json")),
                        toInstant(rs.getTimestamp("created_at"))
                )
        ), researchRunIds.toArray()));
    }

    private Map<String, List<Map<String, Object>>> loadPersistedBranchesByRunIds(List<String> researchRunIds) {
        return groupedValues(jdbcTemplate.query("""
                select research_run_id, branch_key, parent_branch_id, branch_reason, branch_status,
                       hypothesis_summary, target_evidence_ids_json, created_round
                from research_branch
                where research_run_id in (%s)
                order by research_run_id, created_at asc, id asc
                """.formatted(placeholders(researchRunIds.size())), (rs, rowNum) -> {
            LinkedHashMap<String, Object> branch = new LinkedHashMap<>();
            branch.put("branch_id", rs.getString("branch_key"));
            branch.put("parent_branch_id", blankIfNull(rs.getString("parent_branch_id")));
            branch.put("branch_reason", rs.getString("branch_reason"));
            branch.put("status", rs.getString("branch_status"));
            branch.put("hypothesis_summary", blankIfNull(rs.getString("hypothesis_summary")));
            branch.put("target_evidence_ids", readStringList(rs.getString("target_evidence_ids_json")));
            branch.put("created_round", rs.getInt("created_round"));
            return new RunValue<>(rs.getString("research_run_id"), branch);
        }, researchRunIds.toArray()));
    }

    private Map<String, List<Map<String, Object>>> loadPersistedRowsByRunIds(List<String> researchRunIds) {
        return groupedValues(jdbcTemplate.query("""
                select rr.research_run_id, rr.row_key, rb.branch_key, rr.source_id, rr.source_title,
                       rr.search_query, rr.read_focus, coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id, evidence_id, row_status,
                       relation_type, support_score, conflict_score, support_level, verification_status,
                       verifier_note, repair_hint
                from research_row rr
                left join research_branch rb on rb.id = rr.branch_id
                left join source s on s.id = rr.source_id
                where rr.research_run_id in (%s)
                order by rr.research_run_id, rr.created_at asc, rr.id asc
                """.formatted(placeholders(researchRunIds.size())), (rs, rowNum) -> {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("row_id", rs.getString("row_key"));
            row.put("branch_id", blankIfNull(rs.getString("branch_key")));
            row.put("source_id", blankIfNull(rs.getString("source_id")));
            row.put("source_title", blankIfNull(rs.getString("source_title")));
            row.put("generated_by", blankIfNull(rs.getString("generated_by")));
            row.put("generated_ref_id", blankIfNull(rs.getString("generated_ref_id")));
            row.put("search_query", blankIfNull(rs.getString("search_query")));
            row.put("read_focus", blankIfNull(rs.getString("read_focus")));
            row.put("evidence_id", blankIfNull(rs.getString("evidence_id")));
            row.put("row_status", rs.getString("row_status"));
            row.put("relation_type", blankIfNull(rs.getString("relation_type")));
            row.put("support_score", rs.getBigDecimal("support_score"));
            row.put("conflict_score", rs.getBigDecimal("conflict_score"));
            row.put("support_level", blankIfNull(rs.getString("support_level")));
            row.put("verification_status", blankIfNull(rs.getString("verification_status")));
            row.put("verifier_note", blankIfNull(rs.getString("verifier_note")));
            row.put("repair_hint", blankIfNull(rs.getString("repair_hint")));
            return new RunValue<>(rs.getString("research_run_id"), row);
        }, researchRunIds.toArray()));
    }

    private Map<String, List<Map<String, Object>>> loadPersistedCellsByRunIds(List<String> researchRunIds) {
        return groupedValues(jdbcTemplate.query("""
                select rc.research_run_id, rc.cell_key, rr.row_key, rb.branch_key, rc.column_key,
                       rc.candidate_value, rc.cell_status, rc.confidence_score, rc.evidence_refs_json,
                       rc.last_verifier_decision, rc.repair_count
                from research_cell rc
                join research_row rr on rr.id = rc.research_row_id
                left join research_branch rb on rb.id = rc.branch_id
                where rc.research_run_id in (%s)
                order by rc.research_run_id, rc.created_at asc, rc.id asc
                """.formatted(placeholders(researchRunIds.size())), (rs, rowNum) -> {
            LinkedHashMap<String, Object> cell = new LinkedHashMap<>();
            cell.put("cell_id", rs.getString("cell_key"));
            cell.put("row_id", rs.getString("row_key"));
            cell.put("branch_id", blankIfNull(rs.getString("branch_key")));
            cell.put("column_key", rs.getString("column_key"));
            cell.put("candidate_value", blankIfNull(rs.getString("candidate_value")));
            cell.put("status", rs.getString("cell_status"));
            cell.put("confidence", rs.getBigDecimal("confidence_score"));
            cell.put("evidence_refs", readStringList(rs.getString("evidence_refs_json")));
            cell.put("last_verifier_decision", blankIfNull(rs.getString("last_verifier_decision")));
            cell.put("repair_count", rs.getInt("repair_count"));
            return new RunValue<>(rs.getString("research_run_id"), cell);
        }, researchRunIds.toArray()));
    }

    private Map<String, List<Map<String, Object>>> loadPersistedSourceEvidenceByRunIds(
            List<String> researchRunIds
    ) {
        return groupedValues(jdbcTemplate.query("""
                select se.research_run_id, se.evidence_key, se.window_id, se.source_id, se.source_title,
                       se.source_url, se.provider, se.adapter, coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id, se.search_query, se.read_focus,
                       se.quote_text, se.claim_text, se.relation_type, se.support_score, se.conflict_score,
                       se.snapshot_status, se.snapshot_key
                from source_evidence se
                left join source s on s.id = se.source_id
                where se.research_run_id in (%s)
                order by se.research_run_id, se.created_at asc, se.id asc
                """.formatted(placeholders(researchRunIds.size())), (rs, rowNum) -> {
            LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("evidence_id", rs.getString("evidence_key"));
            evidence.put("window_id", blankIfNull(rs.getString("window_id")));
            evidence.put("source_id", blankIfNull(rs.getString("source_id")));
            evidence.put("source_title", blankIfNull(rs.getString("source_title")));
            evidence.put("generated_by", blankIfNull(rs.getString("generated_by")));
            evidence.put("generated_ref_id", blankIfNull(rs.getString("generated_ref_id")));
            evidence.put("source_url", blankIfNull(rs.getString("source_url")));
            evidence.put("provider", blankIfNull(rs.getString("provider")));
            evidence.put("adapter", blankIfNull(rs.getString("adapter")));
            evidence.put("search_query", blankIfNull(rs.getString("search_query")));
            evidence.put("read_focus", blankIfNull(rs.getString("read_focus")));
            evidence.put("quote_text", blankIfNull(rs.getString("quote_text")));
            evidence.put("claim_text", blankIfNull(rs.getString("claim_text")));
            evidence.put("relation_type", blankIfNull(rs.getString("relation_type")));
            evidence.put("support_score", rs.getBigDecimal("support_score"));
            evidence.put("conflict_score", rs.getBigDecimal("conflict_score"));
            evidence.put("snapshot_status", blankIfNull(rs.getString("snapshot_status")));
            evidence.put("snapshot_key", blankIfNull(rs.getString("snapshot_key")));
            return new RunValue<>(rs.getString("research_run_id"), evidence);
        }, researchRunIds.toArray()));
    }

    private Map<String, List<Map<String, Object>>> loadPersistedVerifierDecisionsByRunIds(
            List<String> researchRunIds,
            Map<String, List<Map<String, Object>>> sourceEvidenceByRunId
    ) {
        return groupedValues(jdbcTemplate.query("""
                select rvd.research_run_id, rvd.id, rb.branch_key, rvd.decision_scope,
                       rvd.decision_type, rvd.reason_code, rvd.target_id, rvd.evidence_ids_json,
                       rvd.action_text, rvd.decision_status, rvd.notes_json
                from research_verifier_decision rvd
                left join research_branch rb on rb.id = rvd.branch_id
                where rvd.research_run_id in (%s)
                order by rvd.research_run_id, rvd.created_at asc, rvd.id asc
                """.formatted(placeholders(researchRunIds.size())), (rs, rowNum) -> {
            String researchRunId = rs.getString("research_run_id");
            List<String> evidenceIds = readStringList(rs.getString("evidence_ids_json"));
            LinkedHashMap<String, Object> decision = new LinkedHashMap<>();
            decision.put("decision_id", rs.getString("id"));
            decision.put("branch_id", blankIfNull(rs.getString("branch_key")));
            decision.put("decision_scope", rs.getString("decision_scope"));
            decision.put("decision_type", rs.getString("decision_type"));
            decision.put("reason_code", rs.getString("reason_code"));
            decision.put("target_id", blankIfNull(rs.getString("target_id")));
            decision.put("evidence_ids", evidenceIds);
            decision.put("action", blankIfNull(rs.getString("action_text")));
            decision.put("status", blankIfNull(rs.getString("decision_status")));
            decision.put("notes", readStructuredJson(rs.getString("notes_json")));
            List<Map<String, Object>> sourceSamples = buildEvidenceSourceSamples(
                    evidenceIds,
                    buildSourceEvidenceById(sourceEvidenceByRunId.getOrDefault(researchRunId, List.of())),
                    2
            );
            decision.put("source_samples", sourceSamples);
            decision.put("source_sample_count", sourceSamples.size());
            return new RunValue<>(researchRunId, decision);
        }, researchRunIds.toArray()));
    }

    private Map<String, List<Map<String, Object>>> loadPersistedCellEvidenceByRunIds(
            List<String> researchRunIds
    ) {
        return groupedValues(jdbcTemplate.query("""
                select rce.research_run_id, rc.cell_key, rr.row_key, rce.evidence_key,
                       se.source_id, se.source_title, se.source_url,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id
                from research_cell_evidence rce
                join research_cell rc on rc.id = rce.research_cell_id
                join research_row rr on rr.id = rc.research_row_id
                join source_evidence se on se.id = rce.source_evidence_id
                left join source s on s.id = se.source_id
                where rce.research_run_id in (%s)
                order by rce.research_run_id, rce.created_at asc, rce.id asc
                """.formatted(placeholders(researchRunIds.size())), (rs, rowNum) -> {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("cell_id", rs.getString("cell_key"));
            item.put("row_id", rs.getString("row_key"));
            item.put("evidence_id", rs.getString("evidence_key"));
            item.put("source_id", blankIfNull(rs.getString("source_id")));
            item.put("source_title", blankIfNull(rs.getString("source_title")));
            item.put("generated_by", blankIfNull(rs.getString("generated_by")));
            item.put("generated_ref_id", blankIfNull(rs.getString("generated_ref_id")));
            item.put("source_url", blankIfNull(rs.getString("source_url")));
            return new RunValue<>(rs.getString("research_run_id"), item);
        }, researchRunIds.toArray()));
    }

    private Map<String, Map<String, Object>> buildSourceEvidenceById(List<Map<String, Object>> sourceEvidence) {
        if (sourceEvidence == null || sourceEvidence.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Map<String, Object>> evidenceById = new LinkedHashMap<>();
        for (Map<String, Object> evidence : sourceEvidence) {
            String evidenceId = stringValue(evidence.get("evidence_id"));
            if (!evidenceId.isBlank()) {
                evidenceById.put(evidenceId, evidence);
            }
        }
        return evidenceById;
    }

    private List<Map<String, Object>> buildEvidenceSourceSamples(
            List<String> evidenceIds,
            Map<String, Map<String, Object>> sourceEvidenceById,
            int limit
    ) {
        if (evidenceIds == null || evidenceIds.isEmpty() || sourceEvidenceById.isEmpty() || limit <= 0) {
            return List.of();
        }
        LinkedHashMap<String, Map<String, Object>> orderedSamples = new LinkedHashMap<>();
        for (String evidenceId : evidenceIds) {
            String normalizedEvidenceId = blankToNull(evidenceId);
            if (normalizedEvidenceId == null || orderedSamples.size() >= limit) {
                continue;
            }
            Map<String, Object> evidence = sourceEvidenceById.get(normalizedEvidenceId);
            if (evidence == null) {
                continue;
            }
            String sourceKey = defaultIfBlank(
                    stringValue(evidence.get("source_id")),
                    "evidence:" + normalizedEvidenceId
            );
            if (orderedSamples.containsKey(sourceKey)) {
                continue;
            }
            LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
            sample.put("evidence_id", normalizedEvidenceId);
            sample.put("source_id", blankIfNull(stringValue(evidence.get("source_id"))));
            sample.put("source_title", blankIfNull(stringValue(evidence.get("source_title"))));
            sample.put("generated_by", blankIfNull(stringValue(evidence.get("generated_by"))));
            sample.put("generated_ref_id", blankIfNull(stringValue(evidence.get("generated_ref_id"))));
            sample.put("search_query", blankIfNull(stringValue(evidence.get("search_query"))));
            sample.put("read_focus", blankIfNull(stringValue(evidence.get("read_focus"))));
            sample.put("relation_type", blankIfNull(stringValue(evidence.get("relation_type"))));
            orderedSamples.put(sourceKey, sample);
        }
        return new ArrayList<>(orderedSamples.values());
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private <T> Map<String, List<T>> groupedValues(List<RunValue<T>> rows) {
        LinkedHashMap<String, List<T>> grouped = new LinkedHashMap<>();
        for (RunValue<T> row : rows) {
            grouped.computeIfAbsent(row.researchRunId(), ignored -> new ArrayList<>()).add(row.value());
        }
        return grouped;
    }

    private Map<String, Object> readPayloadMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_TRACE_PAYLOAD_PARSE_FAILED", "研究轨迹载荷解析失败");
        }
    }

    List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> value = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            if (value == null || value.stream().anyMatch(item -> item == null || item.isBlank())) {
                throw new BusinessException(
                        "RESEARCH_READ_MODEL_LIST_PARSE_FAILED",
                        "研究读取模型列表载荷必须是非空字符串数组");
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    "RESEARCH_READ_MODEL_LIST_PARSE_FAILED",
                    "研究读模型列表载荷解析失败");
        }
    }

    Object readStructuredJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
                throw new BusinessException(
                        "RESEARCH_READ_MODEL_JSON_PARSE_FAILED",
                        "研究读模型结构化载荷必须是对象或数组");
            }
            return value;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    "RESEARCH_READ_MODEL_JSON_PARSE_FAILED",
                    "研究读模型结构化载荷解析失败");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record RunValue<T>(String researchRunId, T value) {
    }
}

record ResearchRunReadBundle(
        Map<String, List<ResearchTraceResponse>> tracesByRunId,
        Map<String, List<Map<String, Object>>> branchesByRunId,
        Map<String, List<Map<String, Object>>> rowsByRunId,
        Map<String, List<Map<String, Object>>> cellsByRunId,
        Map<String, List<Map<String, Object>>> sourceEvidenceByRunId,
        Map<String, List<Map<String, Object>>> verifierDecisionsByRunId,
        Map<String, List<Map<String, Object>>> cellEvidenceByRunId
) {
    static ResearchRunReadBundle empty() {
        return new ResearchRunReadBundle(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
}

record ResearchRunDetailRow(
        String researchRunId,
        String workspaceId,
        String taskId,
        String question,
        String profileKey,
        String researchIntentJson,
        String sourceScopeJson,
        String controlPackJson,
        String resumedFromResearchRunId,
        Integer resumedFromCheckpointNo,
        String status,
        String completionTerminalState,
        String finalReportTitle,
        String finalReportMarkdown,
        String traceSummary,
        String reportSourceId,
        Instant createdAt,
        Instant updatedAt
) {
}
