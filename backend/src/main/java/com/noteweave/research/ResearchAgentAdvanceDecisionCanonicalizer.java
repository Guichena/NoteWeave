package com.noteweave.research;

import com.noteweave.common.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical, stable input fingerprint for a future MA4I advancement transaction. */
public final class ResearchAgentAdvanceDecisionCanonicalizer {

    private ResearchAgentAdvanceDecisionCanonicalizer() { }

    public static CanonicalDecision canonicalize(DecisionInput input) {
        List<String> cells = input.targetCellKeys() == null ? List.of() : input.targetCellKeys().stream().sorted().toList();
        Map<String, Object> facts = new LinkedHashMap<>();
        if (input.facts() != null) input.facts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> facts.put(entry.getKey(), entry.getValue()));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("decision_kind", input.decisionKind());
        value.put("wave_no", input.waveNo());
        value.put("predecessor_checkpoint_seq", input.predecessorCheckpointSeq());
        value.put("target_cell_keys", cells);
        value.put("facts", facts);
        String json = Json.write(new ObjectMapper(), value);
        return new CanonicalDecision(json, sha256(json));
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder("sha256:");
            for (byte item : bytes) output.append(String.format("%02x", item));
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record DecisionInput(String decisionKind, int waveNo, int predecessorCheckpointSeq,
                                List<String> targetCellKeys, Map<String, Object> facts) { }
    public record CanonicalDecision(String canonicalJson, String digest) { }
}
