package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 保存当前单元格的全部候选簇，不把未选中的结论当作已经合并的候选。 */
@Service
class ResearchClaimClusterService {
    private static final Set<String> FIELDS = Set.of("cluster_key", "cell_key", "claim_text", "evidence_keys", "confidence_ppm", "equivalent_claims");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ResearchClaimClusterService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    static void validateClusters(Object raw) {
        JsonNode clusters = new ObjectMapper().valueToTree(raw);
        // 每个单元格最多 6 个候选簇；一个任务最多覆盖 6 个单元格
        if (!clusters.isArray() || clusters.size() > 36) throw invalid();
        java.util.Map<String, Integer> perCell = new java.util.HashMap<>();
        for (JsonNode cluster : clusters) {
            if (perCell.merge(cluster.path("cell_key").asText(""), 1, Integer::sum) > 6) throw invalid();
        }
        Set<String> seen = new HashSet<>(), evidence = new HashSet<>();
        for (JsonNode cluster : clusters) {
            if (!cluster.isObject() || !fields(cluster).equals(FIELDS)) throw invalid();
            text(cluster.get("cluster_key"), 64); text(cluster.get("cell_key"), 160); text(cluster.get("claim_text"), 16384);
            JsonNode keys = cluster.get("evidence_keys"), claims = cluster.get("equivalent_claims");
            if (!seen.add(cluster.get("cluster_key").asText()) || !cluster.get("cluster_key").asText().matches("[a-z0-9][a-z0-9._:-]*")
                    || !cluster.get("confidence_ppm").isIntegralNumber() || cluster.get("confidence_ppm").asLong() < 0
                    || cluster.get("confidence_ppm").asLong() > 950000 || !keys.isArray() || keys.isEmpty() || keys.size() > 6
                    || !claims.isArray() || claims.size() != keys.size()) throw invalid();
            Set<String> referenced = new HashSet<>(), described = new HashSet<>();
            for (JsonNode key : keys) { text(key, 64); if (!referenced.add(key.asText()) || !evidence.add(key.asText())) throw invalid(); }
            boolean representative = false;
            for (JsonNode claim : claims) {
                if (!claim.isObject() || !fields(claim).equals(Set.of("evidence_key", "claim_text", "source_domain"))) throw invalid();
                text(claim.get("evidence_key"), 64); text(claim.get("claim_text"), 16384); text(claim.get("source_domain"), 300);
                if (!described.add(claim.get("evidence_key").asText())) throw invalid();
                representative |= cluster.get("claim_text").equals(claim.get("claim_text"));
            }
            if (!referenced.equals(described) || !representative) throw invalid();
        }
    }

    private static Set<String> fields(JsonNode node) { Set<String> keys = new HashSet<>(); node.fieldNames().forEachRemaining(keys::add); return keys; }
    private static void text(JsonNode node, int limit) {
        if (node == null || !node.isTextual() || node.asText().isBlank() || node.asText().codePointCount(0, node.asText().length()) > limit) throw invalid();
    }
    private static BusinessException invalid() { return new BusinessException("RESEARCH_CLAIM_CLUSTER_INVALID", "候选簇或等价证据不符合约定"); }

    void persist(ResearchAgentCompletionCommitter.TaskRow task, ResearchAgentCompletionEnvelope envelope, String completionId) {
        if (ResearchAgentRoles.submitsRoleResult(task.role())) return;
        Object raw = envelope.extractionDiagnostics() == null ? null : envelope.extractionDiagnostics().get("claim_clusters");
        JsonNode clusters;
        if (raw == null) {
            var fallback = mapper.createArrayNode();
            for (var candidate : envelope.candidates()) {
                if (candidate.evidenceKeys().stream().map(key -> envelope.evidence().stream()
                        .filter(item -> item.evidenceKey().equals(key)).findFirst().orElseThrow())
                        .anyMatch(item -> !"SUPPORTS".equals(item.relationType()) || !ResearchEquivalentClaims.supports(candidate, item.evidenceKey(), item.claimText()))) continue;
                List<Map<String, Object>> equivalent = new ArrayList<>();
                for (String key : candidate.evidenceKeys()) {
                    var evidence = envelope.evidence().stream().filter(item -> item.evidenceKey().equals(key)).findFirst().orElseThrow();
                    String domain = jdbc.queryForObject("select source_domain from source_evidence where research_run_id = ? and evidence_key = ?", String.class, task.runId(), key);
                    equivalent.add(Map.of("evidence_key", key, "claim_text", evidence.claimText(), "source_domain", domain));
                }
                fallback.add(mapper.valueToTree(Map.of("cluster_key", "cluster:" + UUID.nameUUIDFromBytes(candidate.candidateKey().getBytes(StandardCharsets.UTF_8)),
                        "cell_key", candidate.cellKey(), "claim_text", candidate.candidateValue(), "evidence_keys", candidate.evidenceKeys(),
                        "confidence_ppm", Math.min(950000, candidate.confidencePpm()), "equivalent_claims", equivalent)));
            }
            clusters = fallback;
        } else { validateClusters(raw); clusters = mapper.valueToTree(raw); }
        Map<String, Integer> targets = new HashMap<>();
        read(task.targetBindingsJson()).forEach(binding -> targets.put(binding.path("cell_id").asText(), binding.path("expected_version").asInt()));
        Map<String, ResearchAgentCompletionEnvelope.Evidence> evidence = new HashMap<>();
        envelope.evidence().forEach(item -> evidence.put(item.evidenceKey(), item));
        Set<String> changed = new HashSet<>();
        for (JsonNode cluster : clusters) {
            String cellKey = cluster.path("cell_key").asText();
            if (!targets.containsKey(cellKey)) throw invalid();
            List<ResearchSourceIndependence.Source> sources = new ArrayList<>();
            for (JsonNode claim : cluster.path("equivalent_claims")) {
                String key = claim.path("evidence_key").asText();
                var card = evidence.get(key);
                if (card == null || !"SUPPORTS".equals(card.relationType()) || !card.claimText().equals(claim.path("claim_text").asText())) throw invalid();
                sources.addAll(jdbc.query("select source_domain, lineage_digest, quote_text, support_score_ppm from source_evidence where research_run_id = ? and evidence_key = ?",
                        (rs, n) -> new ResearchSourceIndependence.Source(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)), task.runId(), key));
            }
            int confidence = ResearchSourceIndependence.score(sources).confidencePpm();
            // 展示用域名也由已落盘的来源确定，不沿用 Worker 声明的域名。
            List<Map<String, Object>> equivalent = new ArrayList<>();
            for (JsonNode claim : cluster.path("equivalent_claims")) {
                String domain = jdbc.queryForObject("select source_domain from source_evidence where research_run_id = ? and evidence_key = ?", String.class, task.runId(), claim.path("evidence_key").asText());
                equivalent.add(Map.of("evidence_key", claim.path("evidence_key").asText(), "claim_text", claim.path("claim_text").asText(), "source_domain", ResearchSourceIndependence.registeredDomain(domain)));
            }
            jdbc.update("""
                    insert into research_claim_cluster(id, research_run_id, task_id, completion_id, cluster_key, cell_key,
                        base_cell_version, plan_revision, entity_set_version, claim_text, evidence_keys_json, equivalent_claims_json, confidence_ppm)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), task.runId(), task.id(), completionId, cluster.path("cluster_key").asText(), cellKey,
                    targets.get(cellKey), task.planRevision(), task.entitySetVersion(), cluster.path("claim_text").asText(),
                    Json.write(mapper, cluster.path("evidence_keys")), Json.write(mapper, equivalent), confidence);
            changed.add(cellKey);
        }
        for (var candidate : raw == null ? List.<ResearchAgentCompletionEnvelope.Candidate>of() : envelope.candidates()) {
            boolean represented = false;
            for (JsonNode cluster : clusters) {
                Set<String> keys = new HashSet<>(); cluster.path("evidence_keys").forEach(key -> keys.add(key.asText()));
                represented |= candidate.cellKey().equals(cluster.path("cell_key").asText())
                        && candidate.candidateValue().equals(cluster.path("claim_text").asText()) && keys.equals(new HashSet<>(candidate.evidenceKeys()));
            }
            if (!represented) throw invalid();
        }
        String flags = jdbc.queryForObject("select agent_feature_flags_json from research_run where id = ?", String.class, task.runId());
        boolean auditEnabled = flags != null && read(flags).path("evidence_audit_v1").asBoolean();
        for (String cell : changed) jdbc.update("""
                update research_cell set semantic_revision = semantic_revision + 1, semantic_audit_status = ?, updated_at = current_timestamp
                where research_run_id = ? and cell_key = ?
                """, auditEnabled || clusters(task.runId(), cell).size() > 1 ? "PENDING" : "NOT_REQUIRED", task.runId(), cell);
    }

    List<Map<String, Object>> clusters(String runId, String cellKey) {
        List<JsonNode> stored = jdbc.query("""
                select cluster.* from research_claim_cluster cluster join research_cell cell
                    on cell.research_run_id = cluster.research_run_id and cell.cell_key = cluster.cell_key
                left join research_cell_merge current_merge on current_merge.research_run_id = cell.research_run_id
                    and current_merge.cell_key = cell.cell_key and current_merge.decision = 'ACCEPTED'
                    and current_merge.result_cell_version = cell.cell_version
                    and (current_merge.merge_key = cell.last_merge_id or current_merge.id = cell.last_merge_id)
                left join research_agent_candidate current_candidate on current_candidate.id = current_merge.candidate_id
                left join research_agent_task cluster_task on cluster_task.id = cluster.task_id
                where cluster.research_run_id = ? and cluster.cell_key = ?
                    and cluster.plan_revision = cell.plan_revision and cluster.entity_set_version = cell.entity_set_version
                    and cluster.base_cell_version = coalesce(current_merge.expected_cell_version, cell.cell_version)
                    and (current_merge.id is null or cluster.task_id = current_candidate.task_id
                        or (current_candidate.quorum_group_key is not null and cluster_task.quorum_group_key = current_candidate.quorum_group_key))
                order by cluster.cluster_key
                """, (rs, n) -> mapper.valueToTree(Map.of("cluster_key", rs.getString("cluster_key"), "claim_text", rs.getString("claim_text"),
                "evidence_keys", read(rs.getString("evidence_keys_json")), "equivalent_claims", read(rs.getString("equivalent_claims_json")))), runId, cellKey);
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        stored.forEach(cluster -> grouped.computeIfAbsent(cluster.path("claim_text").asText(), ignored -> new ArrayList<>()).add(cluster));
        List<Map<String, Object>> result = new ArrayList<>();
        grouped.forEach((claim, entries) -> {
            Set<String> keys = new java.util.TreeSet<>();
            entries.forEach(entry -> entry.path("evidence_keys").forEach(key -> keys.add(key.asText())));
            result.add(Map.of("cluster_key", entries.get(0).path("cluster_key").asText(), "claim_text", claim,
                    "evidence_keys", List.copyOf(keys), "equivalent_claims", entries.stream().flatMap(entry ->
                    java.util.stream.StreamSupport.stream(entry.path("equivalent_claims").spliterator(), false)).toList()));
        });
        return List.copyOf(result);
    }

    JsonNode read(String json) {
        try { JsonNode node = mapper.readTree(json); return node.isTextual() ? mapper.readTree(node.asText()) : node; }
        catch (Exception exception) { throw invalid(); }
    }
}
