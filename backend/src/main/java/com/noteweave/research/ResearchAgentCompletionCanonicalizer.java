package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Cross-runtime canonical JSON and validation for MA4G completion envelopes. */
@Component
public class ResearchAgentCompletionCanonicalizer {

    public static final String SCHEMA_VERSION = "research-agent-completion.v1";
    public static final int MAX_CANONICAL_BYTES = 262_144;
    public static final int MAX_EVIDENCE = 12;
    public static final int MAX_CANDIDATES = 3;
    public static final Set<String> WORKER_USAGE_KEYS = Set.of(
            "llm_calls", "search_calls", "fetch_calls", "read_calls", "extract_calls",
            "evidence_cards", "candidates_submitted"
    );
    public static final Set<String> TELEMETRY_KEYS = Set.of("search_hits", "documents", "windows");
    private static final Set<String> TERMINATION_REASONS = Set.of(
            "CANDIDATES_PROPOSED", "EVIDENCE_ONLY", "NO_SUPPORTED_CANDIDATE"
    );
    private static final Set<String> RELATIONS = Set.of("SUPPORTS", "WEAK_SUPPORT", "CONFLICTS");
    private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9][a-z0-9._:-]*");
    private static final Pattern TASK_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final long MAX_COUNTER = 1_000_000L;

    private final ObjectMapper canonicalMapper = new ObjectMapper();

    public byte[] canonicalBytes(ResearchAgentCompletionEnvelope envelope) {
        return canonicalJson(envelope, false).getBytes(StandardCharsets.UTF_8);
    }

    public String canonicalJson(ResearchAgentCompletionEnvelope envelope, boolean includeEnvelopeDigest) {
        try {
            return canonicalMapper.writeValueAsString(canonicalEnvelope(envelope, includeEnvelopeDigest));
        } catch (JsonProcessingException exception) {
            throw invalid("Completion envelope cannot be canonicalized");
        }
    }

    public String digest(ResearchAgentCompletionEnvelope envelope) {
        return sha256(canonicalBytes(envelope));
    }

    public String canonicalDigest(Object value) {
        try {
            return sha256(canonicalMapper.writeValueAsBytes(canonicalize(value)));
        } catch (JsonProcessingException exception) {
            throw invalid("Completion content cannot be canonicalized");
        }
    }

    public String domainSeparatedDigest(String domain, Object value) {
        if (domain == null || domain.isBlank() || !SAFE_KEY.matcher(domain).matches()) {
            throw invalid("Canonical digest domain is invalid");
        }
        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(canonicalize(value));
            byte[] prefix = (domain + "\n").getBytes(StandardCharsets.US_ASCII);
            byte[] payload = new byte[prefix.length + canonical.length];
            System.arraycopy(prefix, 0, payload, 0, prefix.length);
            System.arraycopy(canonical, 0, payload, prefix.length, canonical.length);
            return sha256(payload);
        } catch (JsonProcessingException exception) {
            throw invalid("Completion content cannot be canonicalized");
        }
    }

    public String canonicalJsonValue(Object value) {
        try {
            return canonicalMapper.writeValueAsString(canonicalize(value));
        } catch (JsonProcessingException exception) {
            throw invalid("Completion content cannot be canonicalized");
        }
    }

    public ValidatedEnvelope validateAndVerify(ResearchAgentCompletionEnvelope envelope) {
        validate(envelope);
        if (!digestShape(envelope.envelopeDigest())) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_DIGEST_MISMATCH",
                    "Completion envelope digest is missing or malformed");
        }
        ResearchAgentCompletionEnvelope normalized = normalizeEnvelope(envelope);
        String digestInputJson = canonicalJson(normalized, false);
        String canonicalJson = canonicalJson(normalized, true);
        if (canonicalJson.getBytes(StandardCharsets.UTF_8).length > MAX_CANONICAL_BYTES) {
            throw invalid("Canonical completion envelope exceeds the byte limit");
        }
        String serverDigest = sha256(digestInputJson.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(serverDigest.getBytes(StandardCharsets.US_ASCII),
                envelope.envelopeDigest().getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_DIGEST_MISMATCH",
                    "Completion envelope digest does not match canonical content");
        }
        return new ValidatedEnvelope(normalized, canonicalJson, digestInputJson, serverDigest);
    }

    void validateServerCellKey(String cellKey) {
        identifier(cellKey, 160, "cell_key");
    }

    public void validate(ResearchAgentCompletionEnvelope envelope) {
        if (envelope == null || !SCHEMA_VERSION.equals(envelope.schemaVersion())) {
            throw invalid("Unsupported completion schema");
        }
        safeIdentifier(envelope.taskId(), 36, "task_id");
        if (!TASK_UUID.matcher(envelope.taskId()).matches()) throw invalid("task_id must be a canonical lowercase UUID");
        safeIdentifier(envelope.workerInstanceId(), 128, "worker_instance_id");
        safeIdentifier(envelope.executionKey(), 160, "execution_key");
        if (envelope.leaseEpoch() < 1 || envelope.fencingToken() < 1) throw invalid("Lease identity is invalid");
        if (!digestShape(envelope.taskSnapshotDigest()) || !digestShape(envelope.traceDigest())) {
            throw invalid("Snapshot or trace digest is invalid");
        }
        if (!TERMINATION_REASONS.contains(envelope.terminationReason())) {
            throw invalid("Termination reason is invalid");
        }
        validateCounters(envelope.budgetUsage(), WORKER_USAGE_KEYS, "budget_usage");
        validateCounters(envelope.telemetry(), TELEMETRY_KEYS, "telemetry");
        List<ResearchAgentCompletionEnvelope.Evidence> evidence = envelope.evidence();
        List<ResearchAgentCompletionEnvelope.Candidate> candidates = envelope.candidates();
        if (evidence == null || candidates == null || evidence.size() > MAX_EVIDENCE || candidates.size() > MAX_CANDIDATES) {
            throw invalid("Completion child count exceeds the contract");
        }
        if (envelope.budgetUsage().get("evidence_cards") != evidence.size()
                || envelope.budgetUsage().get("candidates_submitted") != candidates.size()) {
            throw invalid("Completion budget counters do not match envelope content");
        }
        if ("CANDIDATES_PROPOSED".equals(envelope.terminationReason()) && candidates.isEmpty()) {
            throw invalid("Candidate completion has no candidates");
        }
        if (("EVIDENCE_ONLY".equals(envelope.terminationReason())
                || "NO_SUPPORTED_CANDIDATE".equals(envelope.terminationReason())) && !candidates.isEmpty()) {
            throw invalid("Non-candidate completion contains candidates");
        }
        if ("EVIDENCE_ONLY".equals(envelope.terminationReason()) && evidence.isEmpty()) {
            throw invalid("Evidence-only completion has no evidence");
        }
        if ("NO_SUPPORTED_CANDIDATE".equals(envelope.terminationReason()) && !evidence.isEmpty()) {
            throw invalid("No-result completion must not contain evidence");
        }

        Set<String> evidenceKeys = new HashSet<>();
        for (ResearchAgentCompletionEnvelope.Evidence item : evidence) {
            validateEvidence(item);
            if (!evidenceKeys.add(item.evidenceKey())) throw invalid("Duplicate evidence_key");
        }
        Set<String> candidateKeys = new HashSet<>();
        Set<String> cells = new HashSet<>();
        for (ResearchAgentCompletionEnvelope.Candidate item : candidates) {
            validateCandidate(item);
            if (!candidateKeys.add(item.candidateKey())) throw invalid("Duplicate candidate_key");
            if (!cells.add(item.cellKey())) throw invalid("Duplicate candidate cell_key");
            Set<String> referenced = new HashSet<>(item.evidenceKeys());
            if (referenced.size() != item.evidenceKeys().size() || !evidenceKeys.containsAll(referenced)) {
                throw invalid("Candidate evidence_keys are duplicated or outside the envelope");
            }
        }
    }

    private void validateEvidence(ResearchAgentCompletionEnvelope.Evidence item) {
        if (item == null) throw invalid("Evidence item is null");
        safeIdentifier(item.evidenceKey(), 64, "evidence_key");
        identifier(item.windowId(), 64, "window_id");
        identifier(item.sourceId(), 64, "source_id");
        text(item.sourceTitle(), 300, false, "source_title");
        text(item.searchQuery(), 4096, false, "search_query");
        text(item.readFocus(), 4096, false, "read_focus");
        text(item.quoteText(), 16_384, false, "quote_text");
        text(item.claimText(), 16_384, false, "claim_text");
        if (!RELATIONS.contains(item.relationType()) || !"WORKSPACE".equals(item.snapshotStatus())
                || item.supportScorePpm() < 0 || item.supportScorePpm() > 1_000_000
                || item.conflictScorePpm() < 0 || item.conflictScorePpm() > 1_000_000) {
            throw invalid("Evidence score, relation, or snapshot status is invalid");
        }
    }

    private void validateCandidate(ResearchAgentCompletionEnvelope.Candidate item) {
        if (item == null) throw invalid("Candidate item is null");
        safeIdentifier(item.candidateKey(), 160, "candidate_key");
        identifier(item.cellKey(), 160, "cell_key");
        text(item.candidateValue(), 16_384, false, "candidate_value");
        if (item.baseCellVersion() < 0 || item.baseCellVersion() == Integer.MAX_VALUE
                || item.confidencePpm() < 0 || item.confidencePpm() > 1_000_000
                || item.evidenceKeys() == null || item.evidenceKeys().isEmpty()) {
            throw invalid("Candidate version, confidence, or evidence references are invalid");
        }
        item.evidenceKeys().forEach(key -> safeIdentifier(key, 64, "candidate evidence_key"));
    }

    private void validateCounters(Map<String, Long> counters, Set<String> exactKeys, String field) {
        if (counters == null || !counters.keySet().equals(exactKeys)) {
            throw invalid(field + " must contain exactly the supported keys");
        }
        counters.forEach((key, value) -> {
            if (value == null || value < 0 || value > MAX_COUNTER) throw invalid(field + " contains an invalid counter");
        });
    }

    private Map<String, Object> canonicalEnvelope(ResearchAgentCompletionEnvelope envelope, boolean includeDigest) {
        if (envelope == null) throw invalid("Completion envelope is null");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", envelope.schemaVersion());
        result.put("task_id", envelope.taskId());
        result.put("worker_instance_id", envelope.workerInstanceId());
        result.put("lease_epoch", envelope.leaseEpoch());
        result.put("fencing_token", envelope.fencingToken());
        result.put("execution_key", envelope.executionKey());
        result.put("task_snapshot_digest", envelope.taskSnapshotDigest());
        result.put("termination_reason", envelope.terminationReason());
        result.put("budget_usage", envelope.budgetUsage());
        result.put("telemetry", envelope.telemetry());
        result.put("trace_digest", envelope.traceDigest());
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (envelope.evidence() != null) envelope.evidence().stream()
                .sorted(java.util.Comparator.comparing(ResearchAgentCompletionEnvelope.Evidence::evidenceKey))
                .forEach(item -> evidence.add(evidenceMap(item)));
        result.put("evidence", evidence);
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (envelope.candidates() != null) envelope.candidates().stream()
                .sorted(java.util.Comparator.comparing(ResearchAgentCompletionEnvelope.Candidate::candidateKey))
                .forEach(item -> candidates.add(candidateMap(item)));
        result.put("candidates", candidates);
        if (includeDigest) result.put("envelope_digest", envelope.envelopeDigest());
        @SuppressWarnings("unchecked")
        Map<String, Object> canonical = (Map<String, Object>) canonicalize(result);
        return canonical;
    }

    private Map<String, Object> evidenceMap(ResearchAgentCompletionEnvelope.Evidence item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evidence_key", item.evidenceKey());
        result.put("window_id", item.windowId());
        result.put("source_id", item.sourceId());
        result.put("source_title", item.sourceTitle());
        result.put("search_query", item.searchQuery());
        result.put("read_focus", item.readFocus());
        result.put("quote_text", item.quoteText());
        result.put("claim_text", item.claimText());
        result.put("relation_type", item.relationType());
        result.put("support_score_ppm", item.supportScorePpm());
        result.put("conflict_score_ppm", item.conflictScorePpm());
        result.put("snapshot_status", item.snapshotStatus());
        return result;
    }

    private Map<String, Object> candidateMap(ResearchAgentCompletionEnvelope.Candidate item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidate_key", item.candidateKey());
        result.put("cell_key", item.cellKey());
        result.put("base_cell_version", item.baseCellVersion());
        result.put("candidate_value", item.candidateValue());
        result.put("evidence_keys", item.evidenceKeys() == null ? null : item.evidenceKeys().stream().sorted().toList());
        result.put("confidence_ppm", item.confidencePpm());
        return result;
    }

    private Object canonicalize(Object value) {
        if (value instanceof String text) return Normalizer.normalize(text, Normalizer.Form.NFC);
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> sorted = new TreeMap<>(ResearchAgentBinaryOrder.UTF8);
            raw.forEach((key, child) -> sorted.put(
                    Normalizer.normalize(String.valueOf(key), Normalizer.Form.NFC), canonicalize(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonicalize).toList();
        return value;
    }

    private void safeIdentifier(String value, int max, String field) {
        identifier(value, max, field);
        if (!SAFE_KEY.matcher(value).matches()) throw invalid(field + " must use lowercase ASCII stable-key syntax");
    }

    private void identifier(String value, int max, String field) {
        if (value == null || contractBlank(value) || !validUnicode(value) || hasContractEdgeWhitespace(value)
                || value.codePointCount(0, value.length()) > max
                || !Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw invalid(field + " is invalid");
        }
    }

    private void text(String value, int max, boolean allowBlank, String field) {
        if (value == null || !validUnicode(value) || value.codePointCount(0, value.length()) > max
                || (!allowBlank && contractBlank(value))) throw invalid(field + " is invalid");
    }

    private boolean contractBlank(String value) {
        return value.codePoints().allMatch(this::contractWhitespace);
    }

    private boolean hasContractEdgeWhitespace(String value) {
        return !value.isEmpty() && (contractWhitespace(value.codePointAt(0))
                || contractWhitespace(value.codePointBefore(value.length())));
    }

    private boolean contractWhitespace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000d)
                || codePoint == 0x0020 || codePoint == 0x0085 || codePoint == 0x00a0
                || codePoint == 0x1680 || (codePoint >= 0x2000 && codePoint <= 0x200a)
                || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f
                || codePoint == 0x205f || codePoint == 0x3000;
    }

    private boolean validUnicode(String value) {
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

    private ResearchAgentCompletionEnvelope normalizeEnvelope(ResearchAgentCompletionEnvelope source) {
        List<ResearchAgentCompletionEnvelope.Evidence> evidence = source.evidence().stream().map(item ->
                new ResearchAgentCompletionEnvelope.Evidence(
                        nfc(item.evidenceKey()), nfc(item.windowId()), nfc(item.sourceId()), nfc(item.sourceTitle()),
                        nfc(item.searchQuery()), nfc(item.readFocus()), nfc(item.quoteText()), nfc(item.claimText()),
                        nfc(item.relationType()), item.supportScorePpm(), item.conflictScorePpm(),
                        nfc(item.snapshotStatus()))).toList();
        List<ResearchAgentCompletionEnvelope.Candidate> candidates = source.candidates().stream().map(item ->
                new ResearchAgentCompletionEnvelope.Candidate(
                        nfc(item.candidateKey()), nfc(item.cellKey()), item.baseCellVersion(),
                        nfc(item.candidateValue()), item.evidenceKeys().stream().map(this::nfc).toList(),
                        item.confidencePpm())).toList();
        return new ResearchAgentCompletionEnvelope(
                nfc(source.schemaVersion()), nfc(source.taskId()), nfc(source.workerInstanceId()),
                source.leaseEpoch(), source.fencingToken(), nfc(source.executionKey()),
                nfc(source.taskSnapshotDigest()), nfc(source.terminationReason()),
                Map.copyOf(new TreeMap<>(source.budgetUsage())),
                Map.copyOf(new TreeMap<>(source.telemetry())),
                nfc(source.traceDigest()), evidence, candidates, nfc(source.envelopeDigest()));
    }

    private String nfc(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private boolean digestShape(String value) {
        return value != null && DIGEST.matcher(value).matches();
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder("sha256:");
            for (byte item : digest) output.append(String.format("%02x", item));
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException("RESEARCH_AGENT_COMPLETION_INVALID", message);
    }

    public record ValidatedEnvelope(
            ResearchAgentCompletionEnvelope envelope,
            String canonicalJson,
            String digestInputJson,
            String digest
    ) { }
}
