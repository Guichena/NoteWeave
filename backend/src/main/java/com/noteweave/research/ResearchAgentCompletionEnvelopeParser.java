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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Raw-byte parser that rejects duplicate keys and numeric coercion before DTO binding. */
@Component
public class ResearchAgentCompletionEnvelopeParser {

    private static final Logger log = LoggerFactory.getLogger(ResearchAgentCompletionEnvelopeParser.class);

    public static final int MAX_PAYLOAD_BYTES = ResearchAgentCompletionCanonicalizer.MAX_CANONICAL_BYTES;
    private static final Set<String> ROOT_FIELDS_V1 = Set.of(
            "schema_version", "task_id", "worker_instance_id", "lease_epoch", "fencing_token",
            "execution_key", "task_snapshot_digest", "termination_reason", "budget_usage", "telemetry",
            "trace_digest", "evidence", "candidates", "envelope_digest"
    );
    private static final Set<String> ROOT_FIELDS_V2_ROLE_RESULT = Set.of(
            "schema_version", "task_id", "worker_instance_id", "lease_epoch", "fencing_token",
            "execution_key", "task_snapshot_digest", "termination_reason", "budget_usage", "telemetry",
            "trace_digest", "evidence", "candidates", "role_result", "envelope_digest"
    );
    private static final Set<String> ROOT_FIELDS_V2_EXTRACTION = Set.of(
            "schema_version", "task_id", "worker_instance_id", "lease_epoch", "fencing_token",
            "execution_key", "task_snapshot_digest", "termination_reason", "budget_usage", "telemetry",
            "trace_digest", "evidence", "candidates", "extraction_diagnostics", "envelope_digest"
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
            String schemaVersion = root.path("schema_version").asText();
            String terminationReason = root.path("termination_reason").asText();
            boolean v2 = "research-agent-completion.v2".equals(schemaVersion);
            boolean roleResultShape = v2 && "ROLE_RESULT".equals(terminationReason);
            exactFields(root, !v2 ? ROOT_FIELDS_V1
                    : roleResultShape ? ROOT_FIELDS_V2_ROLE_RESULT : ROOT_FIELDS_V2_EXTRACTION, "completion");
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
            if (roleResultShape) {
                object(root, "role_result");
            } else if (v2) {
                validateExtractionDiagnostics(object(root, "extraction_diagnostics"));
            }
            return mapper.treeToValue(root, ResearchAgentCompletionEnvelope.class);
        } catch (BusinessException exception) {
            throw exception;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            log.warn("Completion JSON binding failed: type={}, reason={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
            throw invalid("Completion request JSON is invalid");
        }
    }

    private void exactFields(JsonNode node, Set<String> expected, String scope) {
        if (!fieldNames(node).equals(expected)) throw invalid(scope + " contains missing or unknown fields");
    }

    private Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(
                        ((Iterable<String>) () -> node.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());
    }

    private void validateExtractionDiagnostics(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("extraction_diagnostics must be an object");
        Set<String> actual = fieldNames(node);
        if (!actual.containsAll(ResearchAgentCompletionCanonicalizer.EXTRACTION_DIAGNOSTICS_REQUIRED_KEYS)
                || !ResearchAgentCompletionCanonicalizer.EXTRACTION_DIAGNOSTICS_KEYS.containsAll(actual)) {
            throw invalid("extraction_diagnostics contains missing or unknown fields");
        }
        JsonNode schema = node.get("schema_version");
        if (schema == null || !schema.isTextual()
                || !ResearchAgentCompletionCanonicalizer.EXTRACTION_DIAGNOSTICS_SCHEMA.equals(schema.textValue())) {
            throw invalid("extraction_diagnostics schema_version is invalid");
        }
        JsonNode termination = node.get("termination_reason");
        if (termination == null || !termination.isTextual() || !ResearchAgentCompletionCanonicalizer
                .EXTRACTION_TERMINATION_REASONS.contains(termination.textValue())) {
            throw invalid("extraction_diagnostics termination_reason is invalid");
        }
        String reason = termination.textValue();
        long accepted = extractionCounter(node.get("accepted_count"), "accepted_count");
        long rejected = extractionCounter(node.get("rejected_count"), "rejected_count");
        if (accepted == 0 && ResearchAgentCompletionCanonicalizer.EXTRACTION_ACCEPTED_REASON.equals(reason)) {
            throw invalid("zero accepted cards cannot terminate as ACCEPTED_CARDS");
        }
        if (accepted >= 1 && !ResearchAgentCompletionCanonicalizer.EXTRACTION_ACCEPTED_REASON.equals(reason)) {
            throw invalid("accepted cards must terminate as ACCEPTED_CARDS");
        }
        JsonNode counts = node.get("rejection_counts");
        if (counts == null || !counts.isObject()) throw invalid("rejection_counts must be an object");
        long total = 0;
        Iterator<Map.Entry<String, JsonNode>> iterator = counts.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (!ResearchAgentCompletionCanonicalizer.EXTRACTION_FAILURE_REASONS.contains(entry.getKey())) {
                throw invalid("rejection_counts key is not a known reason");
            }
            long value = extractionCounter(entry.getValue(), "rejection_counts");
            if (value < 1) throw invalid("rejection_counts value must be positive");
            total += value;
        }
        if (total != rejected) throw invalid("rejection_counts must sum to rejected_count");
        validateExtractionProviderReceipt(node.get("provider_receipt"));
        JsonNode samples = node.get("rejection_samples");
        if (samples != null) {
            if (!samples.isArray()
                    || samples.size() > ResearchAgentCompletionCanonicalizer.MAX_EXTRACTION_REJECTION_SAMPLES
                    || samples.size() > rejected) {
                throw invalid("extraction_diagnostics rejection_samples are invalid");
            }
            samples.forEach(this::validateExtractionRejectionSample);
        }
    }

    private void validateExtractionProviderReceipt(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("provider_receipt must be an object");
        exactFields(node, ResearchAgentCompletionCanonicalizer.EXTRACTION_PROVIDER_RECEIPT_KEYS, "provider_receipt");
        JsonNode purpose = node.get("purpose");
        JsonNode transport = node.get("transport");
        if (purpose == null || !purpose.isTextual() || !"research.extract".equals(purpose.textValue())
                || transport == null || !transport.isTextual()
                || !"openai-compatible".equals(transport.textValue())) {
            throw invalid("provider_receipt identity is invalid");
        }
        JsonNode model = node.get("model");
        if (model == null || !model.isTextual()
                || model.textValue().codePointCount(0, model.textValue().length())
                > ResearchAgentCompletionCanonicalizer.MAX_EXTRACTION_MODEL_CHARS) {
            throw invalid("provider_receipt model is invalid");
        }
        extractionCounter(node.get("call_count"), "provider_receipt.call_count");
        JsonNode digest = node.get("response_digest");
        if (digest == null || !digest.isTextual() || (!digest.textValue().isEmpty()
                && !digest.textValue().matches("sha256:[0-9a-f]{64}"))) {
            throw invalid("provider_receipt response_digest is invalid");
        }
        boundedIntegral(node.get("response_chars"), "provider_receipt.response_chars",
                ResearchAgentCompletionCanonicalizer.MAX_EXTRACTION_RESPONSE_CHARS);
    }

    private void validateExtractionRejectionSample(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid("rejection sample must be an object");
        exactFields(node, ResearchAgentCompletionCanonicalizer.EXTRACTION_REJECTION_SAMPLE_KEYS, "rejection_sample");
        JsonNode reason = node.get("reason");
        if (reason == null || !reason.isTextual()
                || !ResearchAgentCompletionCanonicalizer.EXTRACTION_FAILURE_REASONS.contains(reason.textValue())) {
            throw invalid("rejection sample reason is invalid");
        }
        sampleText(node.get("window_id"), ResearchAgentCompletionCanonicalizer.MAX_EXTRACTION_WINDOW_CHARS,
                "rejection sample window_id");
        sampleText(node.get("column_key"), ResearchAgentCompletionCanonicalizer.MAX_EXTRACTION_COLUMN_CHARS,
                "rejection sample column_key");
        sampleText(node.get("detail"), ResearchAgentCompletionCanonicalizer.MAX_EXTRACTION_DETAIL_CHARS,
                "rejection sample detail");
    }

    private void sampleText(JsonNode value, int max, String field) {
        if (value == null || !value.isTextual()
                || value.textValue().codePointCount(0, value.textValue().length()) > max) {
            throw invalid(field + " is invalid");
        }
    }

    private long extractionCounter(JsonNode value, String field) {
        return boundedIntegral(value, field, 1_000_000L);
    }

    private long boundedIntegral(JsonNode value, String field, long maximum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " must be a bounded decimal integer");
        }
        long result = value.longValue();
        if (result < 0 || result > maximum) throw invalid(field + " is out of range");
        return result;
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
