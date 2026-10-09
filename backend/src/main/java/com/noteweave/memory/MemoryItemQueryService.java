package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

/** 记忆列表的只读查询：当前生效版本与待确认版本，附带晋升时的门控打分。 */
@Service
public class MemoryItemQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final CurrentUserProvider currentUserProvider;
    private final MemoryCandidatePolicy candidatePolicy;

    public MemoryItemQueryService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceAccessGuard workspaceAccessGuard,
            CurrentUserProvider currentUserProvider,
            MemoryCandidatePolicy candidatePolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.currentUserProvider = currentUserProvider;
        this.candidatePolicy = candidatePolicy;
    }

    public List<MemoryItemResponse> listItems(String workspaceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        String userId = currentUserProvider.requireUserId();
        Map<String, SignalEvidence> signals = loadSignalEvidence(workspaceId);
        // 个人范围的记忆只对其所有者可见，与编译时的范围规则一致
        return jdbcTemplate.query("""
                select i.id as item_id, i.memory_scope, i.status as item_status, i.review_status,
                       i.utility_score, i.application_count, i.last_confirmed_at,
                       r.id as revision_id, r.status as revision_status, r.version_no, r.display_text,
                       r.provenance_type, r.normalized_value_json, r.created_at,
                       c.evidence_gate_status, c.marginal_utility_score, c.risk_score as candidate_risk_score,
                       c.scope_status, c.conflict_status as candidate_conflict_status, c.policy_version,
                       c.created_from_signal_ids_json
                from memory_item i
                join memory_runtime_revision r on r.memory_item_id = i.id
                left join memory_candidate c
                  on r.provenance_type = 'MEMORY_CANDIDATE' and c.id = r.provenance_ref
                where i.workspace_id = ?
                  and i.status <> 'DELETED'
                  and (i.memory_scope <> 'USER' or i.owner_user_id = ?)
                  and (r.status = 'PROPOSED' or (r.status = 'ACTIVE' and r.id = i.current_revision_id))
                order by case when r.status = 'PROPOSED' then 0 else 1 end, r.created_at desc, r.id
                """, (rs, rowNum) -> {
            JsonNode payload = readJson(rs.getString("normalized_value_json"));
            SignalEvidence evidence = firstSignal(rs.getString("created_from_signal_ids_json"), signals);
            String conflictStatus = firstText(
                    rs.getString("candidate_conflict_status"),
                    text(payload, "conflict_status"),
                    "NO_CONFLICT");
            return new MemoryItemResponse(
                    rs.getString("item_id"),
                    rs.getString("revision_id"),
                    rs.getString("memory_scope"),
                    rs.getString("item_status"),
                    rs.getString("review_status"),
                    rs.getString("revision_status"),
                    rs.getInt("version_no"),
                    rs.getString("display_text"),
                    firstText(text(payload, "candidate_type"), null, "PREFERENCE"),
                    stringList(payload == null ? null : payload.get("task_neighborhoods")),
                    rs.getString("provenance_type"),
                    rs.getDouble("utility_score"),
                    rs.getInt("application_count"),
                    conflictStatus,
                    rs.getString("evidence_gate_status") == null ? null : new MemoryGateResponse(
                            gateResult(rs.getString("evidence_gate_status"), rs.getDouble("marginal_utility_score"),
                                    rs.getDouble("candidate_risk_score"), rs.getString("scope_status"), conflictStatus),
                            evidence.sourceType(),
                            evidence.confidence(),
                            candidatePolicy.minimumEvidenceConfidence(),
                            rs.getDouble("marginal_utility_score"),
                            candidatePolicy.minimumAutoPromotionUtility(),
                            rs.getDouble("candidate_risk_score"),
                            candidatePolicy.reviewRequiredRisk(),
                            rs.getString("scope_status"),
                            conflictStatus,
                            rs.getString("policy_version"),
                            evidence.sourceRef()),
                    toInstant(rs.getTimestamp("last_confirmed_at")),
                    toInstant(rs.getTimestamp("created_at")));
        }, workspaceId, userId);
    }

    /** 与 MemoryCandidateGate 的判定一致：全部条件满足才直接生效。 */
    private String gateResult(String evidenceGateStatus, double utility, double risk, String scopeStatus,
                              String conflictStatus) {
        boolean ready = "PASS".equals(evidenceGateStatus)
                && utility >= candidatePolicy.minimumAutoPromotionUtility()
                && risk < candidatePolicy.reviewRequiredRisk()
                && "VALID".equals(scopeStatus)
                && !"CONFLICTING_ACTIVE_MEMORY".equals(conflictStatus);
        return ready ? "READY" : "NEEDS_REVIEW";
    }

    private Map<String, SignalEvidence> loadSignalEvidence(String workspaceId) {
        Map<String, SignalEvidence> signals = new HashMap<>();
        jdbcTemplate.query("""
                select id, source_type, source_id, confidence_score from memory_signal where workspace_id = ?
                """, (RowCallbackHandler) rs -> signals.put(rs.getString("id"),
                new SignalEvidence(rs.getString("source_type"), rs.getDouble("confidence_score"),
                        rs.getString("source_id"))), workspaceId);
        return signals;
    }

    private SignalEvidence firstSignal(String signalIdsJson, Map<String, SignalEvidence> signals) {
        for (String signalId : stringList(readJson(signalIdsJson))) {
            SignalEvidence evidence = signals.get(signalId);
            if (evidence != null) {
                return evidence;
            }
        }
        return new SignalEvidence("", 0, null);
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            });
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String firstText(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : fallback;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record SignalEvidence(String sourceType, double confidence, String sourceRef) {
    }
}
