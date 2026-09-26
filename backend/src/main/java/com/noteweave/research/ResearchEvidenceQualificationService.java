package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Canonical three-layer evidence qualification for atomic research completions. */
@Service
class ResearchEvidenceQualificationService {
    static final String SCHEMA_VERSION = "research-evidence-validation.v1";
    private static final String DIGEST_DOMAIN = "research-evidence-validation.v1";
    /**
     * DR-303: package-private so the global-conflict detector reuses the <em>same</em> source
     * identity vocabulary as {@link #independentSourceCount} instead of re-deriving it.
     */
    static final Pattern LINEAGE = Pattern.compile("(?:sha256:)?[0-9a-f]{64}");
    private static final Set<String> TWO_LABEL_PUBLIC_SUFFIXES = Set.of(
            "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.jp", "com.cn", "com.hk", "com.sg");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final ResearchTypedClaimValidator typedClaimValidator;
    private final boolean strict;
    private final ResearchAgentFeatureFlagService featureFlags;

    ResearchEvidenceQualificationService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchTypedClaimValidator typedClaimValidator,
            ResearchAgentFeatureFlagService featureFlags,
            @Value("${noteweave.research.strict-research-evidence-validation:false}") boolean strict
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.typedClaimValidator = typedClaimValidator;
        this.featureFlags = featureFlags;
        this.strict = strict;
    }

    QualificationBatch evaluate(
            ResearchAgentCompletionCommitter.TaskRow task,
            ResearchAgentCompletionEnvelope envelope,
            Map<String, ResearchAgentCompletionCommitter.TrustedEvidenceSource> authority
    ) {
        Map<String, ResearchAgentCompletionEnvelope.Evidence> evidenceByKey = new HashMap<>();
        envelope.evidence().forEach(item -> evidenceByKey.put(item.evidenceKey(), item));
        List<BindingValidation> validations = new ArrayList<>();

        for (ResearchAgentCompletionEnvelope.Candidate candidate : envelope.candidates()) {
            List<ResearchAgentCompletionEnvelope.Evidence> bound = candidate.evidenceKeys().stream()
                    .map(evidenceByKey::get).toList();
            List<String> reasons = validateBinding(task, candidate, bound, authority);
            String finalStatus = reasons.isEmpty() ? "QUALIFIED" : "REJECTED";
            for (ResearchAgentCompletionEnvelope.Evidence evidence : bound) {
                validations.add(validation(candidate, evidence, authority.get(evidence.evidenceKey()),
                        finalStatus, reasons));
            }
        }
        Set<String> boundKeys = envelope.candidates().stream()
                .flatMap(candidate -> candidate.evidenceKeys().stream()).collect(java.util.stream.Collectors.toSet());
        for (ResearchAgentCompletionEnvelope.Evidence evidence : envelope.evidence()) {
            if (boundKeys.contains(evidence.evidenceKey())) continue;
            List<String> reasons = validateEvidence(evidence, authority.get(evidence.evidenceKey()));
            validations.add(validation(null, evidence, authority.get(evidence.evidenceKey()),
                    reasons.isEmpty() ? "QUALIFIED_UNBOUND" : "REJECTED", reasons));
        }

        List<BindingValidation> rejected = validations.stream()
                .filter(item -> "REJECTED".equals(item.finalStatus())).toList();
        boolean gating = strict && featureFlags.enabledForRun(
                task.runId(), ResearchAgentFeatureFlagService.STRICT_EVIDENCE);
        if (gating && !rejected.isEmpty()) {
            throw new BusinessException("RESEARCH_EVIDENCE_QUALIFICATION_FAILED",
                    "Completion evidence failed canonical qualification: "
                            + String.join(",", rejected.stream().flatMap(item -> item.reasonCodes().stream())
                            .distinct().sorted().toList()));
        }
        return new QualificationBatch(List.copyOf(validations), gating ? "GATING" : "SHADOW");
    }

    void persist(
            String runId,
            String completionId,
            QualificationBatch batch,
            Map<String, ResearchAgentCompletionCommitter.EvidencePersisted> evidence,
            List<ResearchAgentCompletionCommitter.CandidatePersisted> candidates,
            List<ResearchAgentCompletionCommitter.CellRow> cells
    ) {
        Map<String, ResearchAgentCompletionCommitter.CandidatePersisted> candidateByKey = new HashMap<>();
        candidates.forEach(item -> candidateByKey.put(item.envelope().candidateKey(), item));
        Map<String, String> cellIdByKey = new HashMap<>();
        cells.forEach(cell -> cellIdByKey.put(cell.cellKey(), cell.id()));
        for (BindingValidation item : batch.validations()) {
            ResearchAgentCompletionCommitter.EvidencePersisted persistedEvidence = evidence.get(item.evidenceKey());
            ResearchAgentCompletionCommitter.CandidatePersisted persistedCandidate =
                    item.candidateKey() == null ? null : candidateByKey.get(item.candidateKey());
            if (persistedEvidence == null || (item.candidateKey() != null && persistedCandidate == null)) {
                throw new IllegalStateException("Qualified evidence binding was not persisted atomically");
            }
            Map<String, Object> digestInput = new LinkedHashMap<>();
            digestInput.put("schema_version", SCHEMA_VERSION);
            digestInput.put("mode", batch.mode());
            digestInput.put("run_id", runId);
            digestInput.put("completion_id", completionId);
            digestInput.put("evidence_id", persistedEvidence.id());
            digestInput.put("candidate_id", persistedCandidate == null ? null : persistedCandidate.id());
            digestInput.put("cell_key", item.cellKey());
            digestInput.put("deterministic_status", item.deterministicStatus());
            digestInput.put("semantic_status", item.semanticStatus());
            digestInput.put("final_status", item.finalStatus());
            digestInput.put("reason_codes", item.reasonCodes());
            digestInput.put("typed_facts", item.typedFacts());
            String validatorDigest = canonicalizer.domainSeparatedDigest(DIGEST_DOMAIN, digestInput);
            String bindingKey = item.candidateKey() == null
                    ? "evidence:" + item.evidenceKey()
                    : "candidate:" + item.candidateKey();
            jdbcTemplate.update("""
                    insert into research_evidence_validation(
                        id, research_run_id, evidence_id, candidate_id, cell_id, binding_key,
                        validation_schema_version, validation_mode, deterministic_status,
                        semantic_status, final_status, reason_codes_json, typed_facts_json,
                        input_digest, validator_digest, completion_id
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), runId, persistedEvidence.id(),
                    persistedCandidate == null ? null : persistedCandidate.id(),
                    item.cellKey() == null ? null : cellIdByKey.get(item.cellKey()), bindingKey,
                    SCHEMA_VERSION, batch.mode(), item.deterministicStatus(), item.semanticStatus(),
                    item.finalStatus(), Json.write(objectMapper, item.reasonCodes()),
                    Json.write(objectMapper, item.typedFacts()), item.inputDigest(), validatorDigest, completionId);
        }
    }

    private List<String> validateBinding(
            ResearchAgentCompletionCommitter.TaskRow task,
            ResearchAgentCompletionEnvelope.Candidate candidate,
            List<ResearchAgentCompletionEnvelope.Evidence> evidence,
            Map<String, ResearchAgentCompletionCommitter.TrustedEvidenceSource> authority
    ) {
        List<String> reasons = new ArrayList<>();
        for (ResearchAgentCompletionEnvelope.Evidence item : evidence) {
            reasons.addAll(validateEvidence(item, authority.get(item.evidenceKey())));
            if (!"SUPPORTS".equals(item.relationType())) reasons.add("RELATION_NOT_SUPPORTING");
            if (!candidate.candidateValue().equals(item.claimText())) reasons.add("CLAIM_CANDIDATE_MISMATCH");
        }
        // DR-112: this branch is unreachable in the live completion path and is kept only as a
        // defensive backstop. Three independent Canonicalizer invariants make `evidence` (the
        // bindings resolved from candidate.evidenceKeys()) provably non-empty:
        //   1. validateCandidate rejects a candidate whose evidenceKeys() is empty;
        //   2. validate rejects candidate keys that are not a subset of the envelope's evidence
        //      keys ("Candidate evidence_keys are duplicated or outside the envelope");
        //   3. validate rejects duplicate evidence_key entries in the envelope, so the
        //      evidenceByKey lookup in evaluate() can never return null.
        // Trap for the future: evaluate() records a validation row only inside its
        // `for (Evidence evidence : bound)` loop, so if any invariant above is ever relaxed and
        // this branch fires, the rejection produces NO validation row and stays invisible to
        // QualificationBatch.rejectedCandidateKeys() — the per-candidate gate would silently
        // fail open. Whoever relaxes the invariants must also make this rejection materialize
        // as a candidate-only validation row. Pinned by
        // ResearchAgentEvidenceEnforcementNegativeTest (DR-112 group 5).
        if (evidence.isEmpty()) reasons.add("QUALIFIED_SOURCE_REQUIRED");
        if (task.candidateQuorum() == 1 && independentSourceCount(evidence, authority) < 1) {
            reasons.add("QUALIFIED_SOURCE_REQUIRED");
        }
        return reasons.stream().distinct().sorted().toList();
    }

    private List<String> validateEvidence(
            ResearchAgentCompletionEnvelope.Evidence evidence,
            ResearchAgentCompletionCommitter.TrustedEvidenceSource source
    ) {
        List<String> reasons = new ArrayList<>();
        if (source == null || !evidence.sourceId().equals(source.sourceId())) reasons.add("SOURCE_AUTHORITY_MISSING");
        if (evidence.quoteText() == null || evidence.quoteText().isBlank()) reasons.add("QUOTE_EMPTY");
        if (evidence.claimText() == null || evidence.claimText().isBlank()) reasons.add("CLAIM_EMPTY");
        ResearchTypedClaimValidator.Validation typed = typedClaimValidator.validate(
                evidence.claimText(), evidence.quoteText());
        if ("CONTRADICTED".equals(typed.status())) reasons.addAll(typed.reasonCodes());
        if ("UNKNOWN".equals(typed.status())) reasons.addAll(typed.reasonCodes());
        if (!Set.of("SUPPORTS", "WEAK_SUPPORT", "CONFLICTS").contains(evidence.relationType())) {
            reasons.add("RELATION_UNKNOWN");
        }
        if (source != null) {
            if (source.sourceDomain() == null || source.sourceDomain().isBlank()) reasons.add("SOURCE_DOMAIN_MISSING");
            if (source.lineageDigest() == null
                    || !LINEAGE.matcher(source.lineageDigest().toLowerCase(Locale.ROOT)).matches()) {
                reasons.add("LINEAGE_DIGEST_INVALID");
            }
            if (!Set.of("WORKSPACE", "EXTERNAL_ARCHIVED").contains(source.snapshotStatus())) {
                reasons.add("SNAPSHOT_NOT_QUALIFIED");
            }
        }
        return reasons;
    }

    private int independentSourceCount(
            List<ResearchAgentCompletionEnvelope.Evidence> evidence,
            Map<String, ResearchAgentCompletionCommitter.TrustedEvidenceSource> authority
    ) {
        List<SourceIdentity> accepted = new ArrayList<>();
        evidence.stream().sorted(Comparator.comparing(ResearchAgentCompletionEnvelope.Evidence::evidenceKey))
                .map(item -> authority.get(item.evidenceKey())).filter(java.util.Objects::nonNull)
                .map(item -> new SourceIdentity(registeredDomain(item.sourceDomain()), item.lineageDigest()))
                .filter(SourceIdentity::complete).forEach(identity -> {
                    boolean overlaps = accepted.stream().anyMatch(existing ->
                            existing.domain().equals(identity.domain()) || existing.lineage().equals(identity.lineage()));
                    if (!overlaps) accepted.add(identity);
                });
        return accepted.size();
    }

    private BindingValidation validation(
            ResearchAgentCompletionEnvelope.Candidate candidate,
            ResearchAgentCompletionEnvelope.Evidence evidence,
            ResearchAgentCompletionCommitter.TrustedEvidenceSource source,
            String finalStatus,
            List<String> reasons
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schema_version", SCHEMA_VERSION);
        input.put("candidate_key", candidate == null ? null : candidate.candidateKey());
        input.put("cell_key", candidate == null ? null : candidate.cellKey());
        input.put("evidence_key", evidence.evidenceKey());
        input.put("claim", evidence.claimText());
        input.put("quote", evidence.quoteText());
        input.put("relation", evidence.relationType());
        input.put("source_domain", source == null ? null : registeredDomain(source.sourceDomain()));
        input.put("lineage_digest", source == null ? null : source.lineageDigest());
        String inputDigest = canonicalizer.domainSeparatedDigest(DIGEST_DOMAIN, input);
        ResearchTypedClaimValidator.Validation typed = typedClaimValidator.validate(
                evidence.claimText(), evidence.quoteText());
        return new BindingValidation(
                candidate == null ? null : candidate.candidateKey(),
                candidate == null ? null : candidate.cellKey(), evidence.evidenceKey(),
                reasons.isEmpty() ? "PASS" : "FAIL", "NOT_RUN", finalStatus,
                List.copyOf(reasons), typed.asMap(), inputDigest);
    }

    /**
     * DR-303: package-private static so the global-conflict detector normalizes source domains
     * exactly like {@link #independentSourceCount} (same registrable-domain reduction).
     */
    static String registeredDomain(String domain) {
        if (domain == null) return "";
        String normalized = domain.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("workspace-source:") || normalized.startsWith("external-source:")) {
            return normalized;
        }
        String[] labels = normalized.split("\\.");
        if (labels.length <= 2) return normalized;
        String suffix = labels[labels.length - 2] + "." + labels[labels.length - 1];
        return TWO_LABEL_PUBLIC_SUFFIXES.contains(suffix)
                ? labels[labels.length - 3] + "." + suffix
                : suffix;
    }

    record QualificationBatch(List<BindingValidation> validations, String mode) {
        /**
         * Candidate keys whose evidence bindings failed canonical qualification.
         *
         * <p>Every candidate carries at least one binding and the binding status is the
         * candidate-level verdict, so a rejected candidate is exactly a candidate that
         * owns at least one REJECTED binding. The result is sorted so merge planning and
         * its post-write check stay byte-for-byte deterministic.</p>
         */
        Set<String> rejectedCandidateKeys() {
            return validations.stream()
                    .filter(item -> "REJECTED".equals(item.finalStatus()) && item.candidateKey() != null)
                    .map(BindingValidation::candidateKey)
                    .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        }
    }
    record BindingValidation(
            String candidateKey,
            String cellKey,
            String evidenceKey,
            String deterministicStatus,
            String semanticStatus,
            String finalStatus,
            List<String> reasonCodes,
            Map<String, Object> typedFacts,
            String inputDigest
    ) { }
    private record SourceIdentity(String domain, String lineage) {
        boolean complete() {
            return domain != null && !domain.isBlank() && lineage != null && LINEAGE.matcher(lineage).matches();
        }
    }
}
