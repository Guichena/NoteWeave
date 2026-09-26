package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Shared claim/completion authority for research-agent task snapshots. */
@Component
class ResearchAgentTaskSnapshotCanonicalizer {
    static final String SCHEMA_V1 = "research-agent-task-snapshot.v1";
    static final String SCHEMA_V2 = "research-agent-task-snapshot.v2";
    static final String SCHEMA_V3 = "research-agent-task-snapshot.v3";
    static final String SCHEMA_VERSION = SCHEMA_V1;

    private final ObjectMapper objectMapper;

    ResearchAgentTaskSnapshotCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    CanonicalSnapshot canonicalize(SnapshotInput input) {
        if (!SCHEMA_V1.equals(input.schemaVersion())
                && !SCHEMA_V2.equals(input.schemaVersion())
                && !SCHEMA_V3.equals(input.schemaVersion())) throw invalid();
        List<Map<String, Object>> targetBindings = readList(
                input.targetBindingsJson(), "WIDE_DISCOVERY".equals(input.role()));
        Map<String, Object> budget = readMap(input.budgetJson());
        Map<String, Object> context = readMap(input.executionContextJson());
        Object providerKey = context.get("provider_key");
        Object sourcePolicy = context.get("source_policy");
        Object queryPolicy = context.get("query_policy");
        if (!(providerKey instanceof String provider && !provider.isBlank())
                || !(sourcePolicy instanceof Map<?, ?> source && !source.isEmpty())
                || !(queryPolicy instanceof Map<?, ?> query && !query.isEmpty())) {
            throw invalid();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schema_version", input.schemaVersion());
        snapshot.put("task_id", input.taskId());
        snapshot.put("research_run_id", input.runId());
        snapshot.put("workspace_id", input.workspaceId());
        snapshot.put("role", input.role());
        snapshot.put("entity_id", input.entityId());
        snapshot.put("branch_id", input.branchId());
        snapshot.put("plan_revision", input.planRevision());
        snapshot.put("entity_set_version", input.entitySetVersion());
        snapshot.put("lease_epoch", input.leaseEpoch());
        snapshot.put("fencing_token", input.fencingToken());
        snapshot.put("target_cells", targetBindings);
        snapshot.put("budget", budget);
        snapshot.put("provider_key", providerKey);
        snapshot.put("source_policy", sourcePolicy);
        snapshot.put("query_policy", queryPolicy);
        if (SCHEMA_V3.equals(input.schemaVersion())) {
            Object researchIntent = context.get("research_intent");
            Object controlPack = context.get("control_pack");
            if (!(researchIntent instanceof Map<?, ?> intent && !intent.isEmpty())
                    || !(controlPack instanceof Map<?, ?> controls && !controls.isEmpty())) {
                throw invalid();
            }
            snapshot.put("research_intent", researchIntent);
            snapshot.put("control_pack", controlPack);
        }
        if (SCHEMA_V2.equals(input.schemaVersion()) || SCHEMA_V3.equals(input.schemaVersion())) {
            if (input.logicalTaskKey() == null || input.logicalTaskKey().isBlank()
                    || input.candidateQuorum() < 1 || input.candidateQuorum() > 2
                    || input.candidateSlot() < 1 || input.candidateSlot() > input.candidateQuorum()
                    || (input.candidateQuorum() == 2 && (input.quorumGroupKey() == null
                    || input.quorumGroupKey().isBlank() || !input.highRisk()))) {
                throw invalid();
            }
            snapshot.put("logical_task_key", input.logicalTaskKey());
            if (input.quorumGroupKey() != null) {
                snapshot.put("quorum_group_key", input.quorumGroupKey());
            }
            snapshot.put("candidate_quorum", input.candidateQuorum());
            snapshot.put("candidate_slot", input.candidateSlot());
            snapshot.put("high_risk", input.highRisk());
        }
        String digest = sha256(Json.write(objectMapper, ResearchCanonicalJson.canonicalize(snapshot)));
        snapshot.put("snapshot_digest", digest);
        return new CanonicalSnapshot(Json.write(objectMapper, ResearchCanonicalJson.canonicalize(snapshot)), digest);
    }

    private Map<String, Object> readMap(String raw) {
        try {
            Map<String, Object> result = objectMapper.readValue(raw, new TypeReference<>() { });
            if (result == null) throw invalid();
            return result;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private List<Map<String, Object>> readList(String raw, boolean allowEmpty) {
        try {
            List<Map<String, Object>> result = objectMapper.readValue(raw, new TypeReference<>() { });
            if (result == null || (!allowEmpty && result.isEmpty())) throw invalid();
            return result;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("sha256:");
            for (byte item : digest) output.append(String.format("%02x", item));
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BusinessException invalid() {
        return new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_INVALID", "Task snapshot JSON is invalid");
    }

    record SnapshotInput(
            String schemaVersion,
            String taskId,
            String runId,
            String workspaceId,
            String role,
            String entityId,
            String branchId,
            int planRevision,
            int entitySetVersion,
            int leaseEpoch,
            long fencingToken,
            String targetBindingsJson,
            String budgetJson,
            String executionContextJson,
            String logicalTaskKey,
            String quorumGroupKey,
            int candidateQuorum,
            int candidateSlot,
            boolean highRisk
    ) {
        SnapshotInput(
                String taskId, String runId, String workspaceId, String role, String entityId, String branchId,
                int planRevision, int entitySetVersion, int leaseEpoch, long fencingToken,
                String targetBindingsJson, String budgetJson, String executionContextJson
        ) {
            this(SCHEMA_V1, taskId, runId, workspaceId, role, entityId, branchId, planRevision,
                    entitySetVersion, leaseEpoch, fencingToken, targetBindingsJson, budgetJson,
                    executionContextJson, null, null, 1, 1, false);
        }
    }

    record CanonicalSnapshot(String json, String digest) { }
}
