package com.noteweave.research;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.text.Normalizer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

/** Raw-byte parser that rejects duplicate keys and numeric coercion before DTO binding. */
@Component
public class ResearchAgentCompletionEnvelopeParser {

    public static final int MAX_PAYLOAD_BYTES = ResearchAgentCompletionCanonicalizer.MAX_CANONICAL_BYTES;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema_version", "task_id", "worker_instance_id", "lease_epoch", "fencing_token",
            "execution_key", "task_snapshot_digest", "termination_reason", "budget_usage", "telemetry",
            "trace_digest", "evidence", "candidates", "envelope_digest"
    );
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "evidence_key", "window_id", "source_id", "source_title", "search_query", "read_focus",
            "quote_text", "claim_text", "relation_type", "support_score_ppm", "conflict_score_ppm",
            "snapshot_status"
    );
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "candidate_key", "cell_key", "base_cell_version", "candidate_value", "evidence_keys", "confidence_ppm"
    );

    private final ObjectMapper mapper;

    public ResearchAgentCompletionEnvelopeParser() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.mapper = new ObjectMapper(factory);
        this.mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public ResearchAgentCompletionEnvelope parse(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw invalid("Completion request size is invalid");
        }
        try {
            String raw = strictUtf8(payload);
            if (!raw.isEmpty() && raw.codePointAt(0) == 0xfeff) {
                throw invalid("Completion request must be BOM-free UTF-8");
            }
            JsonNode root;
            try (com.fasterxml.jackson.core.JsonParser jsonParser = mapper.getFactory().createParser(raw)) {
                root = mapper.readTree(jsonParser);
                if (jsonParser.nextToken() != null) throw invalid("Completion request has trailing JSON tokens");
            }
            if (root == null || !root.isObject()) throw invalid("Completion request must be a JSON object");
            rejectInvalidUnicodeScalars(root);
            rejectNormalizedKeyCollisions(root);
            exactFields(root, ROOT_FIELDS, "completion");
            integral(root, "lease_epoch");
            integral(root, "fencing_token");
            JsonNode budget = object(root, "budget_usage");
            exactFields(budget, ResearchAgentCompletionCanonicalizer.WORKER_USAGE_KEYS, "budget_usage");
            budget.fieldNames().forEachRemaining(field -> integral(budget, field));
            JsonNode telemetry = object(root, "telemetry");
            exactFields(telemetry, ResearchAgentCompletionCanonicalizer.TELEMETRY_KEYS, "telemetry");
            telemetry.fieldNames().forEachRemaining(field -> integral(telemetry, field));
            JsonNode evidence = array(root, "evidence");
            evidence.forEach(item -> {
                if (!item.isObject()) throw invalid("Evidence item must be an object");
                exactFields(item, EVIDENCE_FIELDS, "evidence");
                integral(item, "support_score_ppm");
                integral(item, "conflict_score_ppm");
            });
            JsonNode candidates = array(root, "candidates");
            candidates.forEach(item -> {
                if (!item.isObject()) throw invalid("Candidate item must be an object");
                exactFields(item, CANDIDATE_FIELDS, "candidate");
                integral(item, "base_cell_version");
                integral(item, "confidence_ppm");
                JsonNode keys = array(item, "evidence_keys");
                keys.forEach(key -> {
                    if (!key.isTextual()) throw invalid("Candidate evidence key must be a string");
                });
            });
            return mapper.treeToValue(root, ResearchAgentCompletionEnvelope.class);
        } catch (BusinessException exception) {
            throw exception;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw invalid("Completion request JSON is invalid");
        }
    }

    private void exactFields(JsonNode node, Set<String> expected, String scope) {
        Set<String> actual = StreamSupport.stream(
                        ((Iterable<String>) () -> node.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());
        if (!actual.equals(expected)) throw invalid(scope + " contains missing or unknown fields");
    }

    private void rejectNormalizedKeyCollisions(JsonNode node) {
        if (node.isObject()) {
            Set<String> normalized = new HashSet<>();
            node.fields().forEachRemaining(entry -> {
                String key = Normalizer.normalize(entry.getKey(), Normalizer.Form.NFC);
                if (!normalized.add(key)) throw invalid("JSON object keys collide after NFC normalization");
                rejectNormalizedKeyCollisions(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::rejectNormalizedKeyCollisions);
        }
    }

    private void rejectInvalidUnicodeScalars(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (!validScalars(entry.getKey())) throw invalid("JSON object key contains an invalid Unicode scalar");
                rejectInvalidUnicodeScalars(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::rejectInvalidUnicodeScalars);
        } else if (node.isTextual() && !validScalars(node.textValue())) {
            throw invalid("JSON string contains an invalid Unicode scalar");
        }
    }

    private boolean validScalars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) return false;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private String strictUtf8(byte[] payload) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException exception) {
            throw invalid("Completion request is not strict UTF-8");
        }
    }

    private JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) throw invalid(field + " must be an object");
        return value;
    }

    private JsonNode array(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) throw invalid(field + " must be an array");
        return value;
    }

    private void integral(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " must be a bounded decimal integer");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException("RESEARCH_AGENT_COMPLETION_INVALID", message);
    }
}
