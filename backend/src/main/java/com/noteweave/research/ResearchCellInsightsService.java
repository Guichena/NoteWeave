package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 当前单元格、等价证据及已持久化的语义冲突查询。 */
@Service
class ResearchCellInsightsService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResearchClaimClusterService clusters;
    ResearchCellInsightsService(JdbcTemplate jdbc, ObjectMapper mapper, ResearchClaimClusterService clusters) {
        this.jdbc = jdbc; this.mapper = mapper; this.clusters = clusters;
    }

    List<Map<String, Object>> cells(String workspaceId, String runId) {
        Integer count = jdbc.queryForObject("select count(*) from research_run where id = ? and workspace_id = ?", Integer.class, runId, workspaceId);
        if (count == null || count != 1) throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
        return jdbc.query("""
                select cell.*, coalesce(rr.source_title, cell.cell_key) as entity_label,
                    coalesce(col.label, cell.column_key) as column_label
                from research_cell cell
                left join research_row rr on rr.id = cell.research_row_id
                left join research_column col on col.research_run_id = cell.research_run_id and col.column_key = cell.column_key
                where cell.research_run_id = ? order by cell.cell_key
                """, (rs, n) -> {
            String cellKey = rs.getString("cell_key");
            String value = rs.getString("candidate_value");
            if (value == null) value = "";
            Set<String> keys = new java.util.TreeSet<>();
            for (var cluster : clusters.clusters(runId, cellKey)) if (value.equals(cluster.get("claim_text"))) {
                for (Object key : (List<?>) cluster.get("evidence_keys")) keys.add(String.valueOf(key));
            }
            if (keys.isEmpty() && rs.getString("evidence_refs_json") != null) clusters.read(rs.getString("evidence_refs_json")).forEach(key -> keys.add(key.asText()));
            List<Map<String, Object>> claims = new ArrayList<>();
            List<ResearchSourceIndependence.Source> sources = new ArrayList<>();
            for (String key : keys) jdbc.query("select claim_text, source_domain, lineage_digest, quote_text, support_score_ppm from source_evidence where research_run_id = ? and evidence_key = ?",
                    row -> {
                        String domain = row.getString("source_domain");
                        claims.add(Map.of("evidence_key", key, "claim_text", text(row.getString("claim_text")), "source_domain", ResearchSourceIndependence.registeredDomain(domain)));
                        if (domain != null && !domain.isBlank()) sources.add(new ResearchSourceIndependence.Source(domain, text(row.getString("lineage_digest")), text(row.getString("quote_text")), row.getInt("support_score_ppm")));
                    }, runId, key);
            var score = ResearchSourceIndependence.score(sources);
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("cell_key", cellKey); cell.put("entity_label", rs.getString("entity_label")); cell.put("column_label", rs.getString("column_label"));
            String status = rs.getString("cell_status"), semantic = rs.getString("semantic_audit_status");
            if ("VERIFIED".equals(status) && !Set.of("NOT_REQUIRED", "PASS").contains(semantic)) status = "SEMANTIC_" + semantic;
            cell.put("cell_status", status); cell.put("candidate_value", value); cell.put("equivalent_claims", claims);
            cell.put("independent_source_count", score.independentSources());
            cell.put("confidence_ppm", claims.isEmpty() ? 0 : score.confidencePpm());
            cell.put("conflicts", conflicts(runId, cellKey, value));
            return cell;
        }, runId);
    }

    private List<Map<String, Object>> conflicts(String runId, String cellKey, String value) {
        return jdbc.query("select reason_code, decision_status, notes_json, evidence_ids_json from research_verifier_decision where research_run_id = ? and target_id = ? and decision_type = 'GLOBAL_CONFLICT' order by created_at, id",
                (rs, n) -> {
                    boolean semantic = "SEMANTIC_CONTRADICTION".equals(rs.getString(1));
                    String other = "";
                    if (semantic && rs.getString(3) != null) {
                        JsonNode notes = clusters.read(rs.getString(3));
                        other = value.equals(notes.path("left_claim").asText()) ? notes.path("right_claim").asText() : notes.path("left_claim").asText();
                    } else if (rs.getString(4) != null) {
                        for (JsonNode key : clusters.read(rs.getString(4))) {
                            List<String> candidates = jdbc.queryForList("select claim_text from source_evidence where research_run_id = ? and evidence_key = ?", String.class, runId, key.asText());
                            if (!candidates.isEmpty() && !value.equals(candidates.get(0))) { other = text(candidates.get(0)); break; }
                        }
                    }
                    return Map.<String, Object>of("kind", semantic ? "SEMANTIC_CONTRADICTION" : "TYPED_FACT_CONTRADICTION", "other_value", other, "status", "RESOLVED".equals(rs.getString(2)) ? "RESOLVED" : "OPEN");
                }, runId, cellKey);
    }
    private static String text(String value) { return value == null ? "" : value; }
}
