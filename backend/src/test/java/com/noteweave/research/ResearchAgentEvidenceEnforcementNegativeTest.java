package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DR-302 negative-case report: every reason code produced by
 * {@code ResearchEvidenceQualificationService#validateEvidence} / {@code #validateBinding}
 * must end in a real rejection, not just in an audit row.
 *
 * <p>The report class is split by <em>reachability through the completion envelope</em>:</p>
 *
 * <ol>
 *   <li><b>Reachable</b> (7 codes): a completion whose candidate would be promoted by the
 *       literal merge rule must now be rejected end to end. Assertions cover the receipt,
 *       the canonical cell state (no value/version/evidence_ref mutation) and the persisted
 *       {@code research_evidence_validation} verdict.</li>
 *   <li><b>Blocked before qualification</b> (4 codes): the completion envelope contract
 *       (canonicalizer {@code validate}) already refuses the malformed payload, so the
 *       qualification reason code can never be produced by a real completion. The negative
 *       case is proven at the layer that actually enforces it.</li>
 *   <li><b>Authority-derived, unreachable through the envelope</b> (4 codes): the trusted
 *       authority map is rebuilt server-side by
 *       {@code ResearchAgentCompletionCommitter#validateEvidenceAuthority}, which either
 *       fails closed ({@code RESEARCH_AGENT_COMPLETION_EVIDENCE_UNGROUNDED}) or yields a
 *       complete, well-formed source identity. Those codes are therefore proven directly on
 *       the qualification service with a forged authority map.</li>
 * </ol>
 *
 * <p>No main-code behaviour is changed here; this class only observes it.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentEvidenceEnforcementNegativeTest {

    private static final Map<String, Long> RESERVED = Map.of(
            "llm_calls", 2L,
            "search_calls", 2L,
            "fetch_calls", 2L,
            "read_calls", 2L,
            "extract_calls", 2L,
            "evidence_cards", 3L,
            "evidence_appended", 3L,
            "candidates_submitted", 2L,
            "candidate_merges_accepted", 1L,
            "candidate_merges_rejected", 1L);
    private static final String VALID_LINEAGE = "a".repeat(64);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentCommandOutboxService outboxService;
    @Autowired private ResearchBudgetAndCheckpointService budgetService;
    @Autowired private ResearchAgentCompletionService completionService;
    @Autowired private ResearchAgentCompletionCanonicalizer canonicalizer;
    @Autowired private ResearchAgentFeatureFlagService featureFlags;
    @Autowired private ResearchEvidenceQualificationService qualification;

    // ---------------------------------------------------------------- group 1: reachable

    @Test
    void shouldRejectNumberValueContradictionWithoutPromotion() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(),
                candidateEnvelope(fixture, "trusted quote 0", "value-1", "SUPPORTS", "value-1"));

        assertThat(featureFlags.currentEnabled(ResearchAgentFeatureFlagService.STRICT_EVIDENCE)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                select validation_mode from research_evidence_validation
                where completion_id = ? and candidate_id is not null
                """, String.class, receipt.completionId())).isEqualTo("SHADOW");
        assertCandidateRejectedWithoutPromotion(
                fixture, receipt, "NUMBER_VALUE_CONTRADICTION", "EVIDENCE_QUALIFICATION_REJECTED");
    }

    @Test
    void shouldRejectUnitContradictionWithoutPromotion() {
        Fixture fixture = fixture("prefix latency 100 s suffix");

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(),
                candidateEnvelope(fixture, "latency 100 s", "latency 100 ms", "SUPPORTS", "latency 100 ms"));

        assertCandidateRejectedWithoutPromotion(
                fixture, receipt, "UNIT_CONTRADICTION", "EVIDENCE_QUALIFICATION_REJECTED");
    }

    @Test
    void shouldRejectDateValueContradictionWithoutPromotion() {
        Fixture fixture = fixture("prefix revenue in 2020 suffix");

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(),
                candidateEnvelope(fixture, "revenue in 2020", "revenue in 2019", "SUPPORTS", "revenue in 2019"));

        assertCandidateRejectedWithoutPromotion(
                fixture, receipt, "DATE_VALUE_CONTRADICTION", "EVIDENCE_QUALIFICATION_REJECTED");
    }

    @Test
    void shouldRejectComparisonDirectionContradictionWithoutPromotion() {
        Fixture fixture = fixture("prefix growth decreased 5 suffix");

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(),
                candidateEnvelope(fixture, "growth decreased 5", "growth increased 5", "SUPPORTS", "growth increased 5"));

        assertCandidateRejectedWithoutPromotion(
                fixture, receipt, "COMPARISON_DIRECTION_CONTRADICTION", "EVIDENCE_QUALIFICATION_REJECTED");
    }

    @Test
    void shouldRejectTypedFactMissingFromCitationSpanWithoutPromotion() {
        Fixture fixture = fixture("prefix trusted statement suffix");

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(),
                candidateEnvelope(fixture, "trusted statement", "value 42", "SUPPORTS", "value 42"));

        assertCandidateRejectedWithoutPromotion(
                fixture, receipt, "TYPED_FACT_MISSING_FROM_CITATION_SPAN", "EVIDENCE_QUALIFICATION_REJECTED");
    }

    @Test
    void shouldRejectRelationNotSupportingWithoutPromotion() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(),
                candidateEnvelope(fixture, "trusted quote 0", "value-0", "WEAK_SUPPORT", "value-0"));

        assertCandidateRejectedWithoutPromotion(
                fixture, receipt, "RELATION_NOT_SUPPORTING", "NOT_ENOUGH_INFO");
    }

    @Test
    void shouldRejectClaimCandidateMismatchWithoutPromotion() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(),
                candidateEnvelope(fixture, "trusted quote 0", "alpha claim", "SUPPORTS", "value-0"));

        assertCandidateRejectedWithoutPromotion(
                fixture, receipt, "CLAIM_CANDIDATE_MISMATCH", "NOT_ENOUGH_INFO");
    }

    // ------------------------------------------------------------- group 2: control group

    @Test
    void shouldStillPromoteASupportsCandidateWhoseExactQuoteCarriesTheClaimTypedFact() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(),
                candidateEnvelope(fixture, "trusted quote 0", "value-0", "SUPPORTS", "value-0"));

        assertThat(receipt.rejectedMerges()).isEmpty();
        assertThat(receipt.acceptedMerges()).containsExactly(new ResearchAgentCompletionReceipt.MergeReceipt(
                fixture.cellKey(), 0, 1, "ACCEPTED", "VERIFIED_AND_VERSION_MATCHED"));
        assertThat(jdbcTemplate.queryForMap("""
                select cell_status, cell_version, candidate_value, evidence_refs_json
                from research_cell where research_run_id = ? and cell_key = ?
                """, fixture.runId(), fixture.cellKey()))
                .containsEntry("cell_status", "VERIFIED")
                .containsEntry("cell_version", 1)
                .containsEntry("candidate_value", "value-0");
        assertThat(String.valueOf(jdbcTemplate.queryForObject("""
                select evidence_refs_json from research_cell where research_run_id = ? and cell_key = ?
                """, String.class, fixture.runId(), fixture.cellKey())))
                .contains("ev-" + fixture.taskId().substring(0, 8));
        // The exact citation span carries the claim's typed fact (number 0): ENTAILLED, not merely present.
        assertThat(jdbcTemplate.queryForObject("""
                select typed_facts_json from research_evidence_validation
                where completion_id = ? and candidate_id is not null
                """, String.class, receipt.completionId())).contains("ENTAILED");
        assertThat(jdbcTemplate.queryForObject("""
                select final_status from research_evidence_validation
                where completion_id = ? and candidate_id is not null
                """, String.class, receipt.completionId())).isEqualTo("QUALIFIED");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_cell_evidence rce
                join source_evidence se on se.id = rce.source_evidence_id
                where rce.research_run_id = ? and se.agent_completion_id = ?
                """, Integer.class, fixture.runId(), receipt.completionId())).isEqualTo(1);
    }

    // ------------------------------------- group 3: refused by the envelope contract first

    @Test
    void shouldRefuseAnEmptyQuoteBeforeQualificationCanReachIt() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");

        assertRejectedBeforeQualification(fixture,
                candidateEnvelope(fixture, "", "value-0", "SUPPORTS", "value-0"),
                "RESEARCH_AGENT_COMPLETION_INVALID");
    }

    @Test
    void shouldRefuseAnEmptyClaimBeforeQualificationCanReachIt() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");

        assertRejectedBeforeQualification(fixture,
                candidateEnvelope(fixture, "trusted quote 0", "", "SUPPORTS", ""),
                "RESEARCH_AGENT_COMPLETION_INVALID");
    }

    @Test
    void shouldRefuseAnUnknownRelationBeforeQualificationCanReachIt() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");

        assertRejectedBeforeQualification(fixture,
                candidateEnvelope(fixture, "trusted quote 0", "value-0", "UNSUPPORTED", "value-0"),
                "RESEARCH_AGENT_COMPLETION_INVALID");
    }

    @Test
    void shouldRefuseAnUnqualifiedSnapshotStatusBeforeQualificationCanReachIt() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");

        assertRejectedBeforeQualification(fixture, signed(new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", fixture.taskId(), "worker-a", fixture.leaseEpoch(),
                fixture.fencingToken(), fixture.executionKey(), fixture.snapshotDigest(),
                "CANDIDATES_PROPOSED", usage(1, 1), Map.of("search_hits", 1L, "documents", 1L, "windows", 1L),
                "sha256:" + "3".repeat(64),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        "ev-" + fixture.taskId().substring(0, 8), "window-0", "source-0", "Source 0",
                        "dr302 question", "field-0", "trusted quote 0", "value-0", "SUPPORTS",
                        900_000, 0, "UNARCHIVED")),
                List.of(new ResearchAgentCompletionEnvelope.Candidate(
                        "cand-" + fixture.taskId().substring(0, 8), fixture.cellKey(), 0, "value-0",
                        List.of("ev-" + fixture.taskId().substring(0, 8)), 900_000)), null)),
                "RESEARCH_AGENT_COMPLETION_INVALID");
    }

    // ------------------------------------- group 4: authority-derived codes, service layer

    @Test
    void shouldRejectEvidenceWhoseTrustedAuthorityIsMissing() {
        ResearchEvidenceQualificationService.BindingValidation validation =
                validationFor("value-0", "trusted quote 0", "SUPPORTS", null, 1);

        assertThat(validation.finalStatus()).isEqualTo("REJECTED");
        assertThat(validation.deterministicStatus()).isEqualTo("FAIL");
        assertThat(validation.reasonCodes()).contains("SOURCE_AUTHORITY_MISSING");
    }

    @Test
    void shouldRejectEvidenceWhoseTrustedAuthorityHasNoSourceDomain() {
        ResearchEvidenceQualificationService.BindingValidation validation = validationFor(
                "value-0", "trusted quote 0", "SUPPORTS",
                trustedSource("source-0", "", VALID_LINEAGE, "WORKSPACE"), 1);

        assertThat(validation.finalStatus()).isEqualTo("REJECTED");
        assertThat(validation.reasonCodes()).contains("SOURCE_DOMAIN_MISSING");
    }

    @Test
    void shouldRejectEvidenceWhoseTrustedAuthorityHasAnInvalidLineageDigest() {
        ResearchEvidenceQualificationService.BindingValidation validation = validationFor(
                "value-0", "trusted quote 0", "SUPPORTS",
                trustedSource("source-0", "workspace-source:source-0", "not-a-digest", "WORKSPACE"), 1);

        assertThat(validation.finalStatus()).isEqualTo("REJECTED");
        assertThat(validation.reasonCodes()).contains("LINEAGE_DIGEST_INVALID");
    }

    @Test
    void shouldRejectEvidenceWhoseTrustedAuthorityIsNotAQualifiedSnapshot() {
        ResearchEvidenceQualificationService.BindingValidation validation = validationFor(
                "value-0", "trusted quote 0", "SUPPORTS",
                trustedSource("source-0", "workspace-source:source-0", VALID_LINEAGE, "UNARCHIVED"), 1);

        assertThat(validation.finalStatus()).isEqualTo("REJECTED");
        assertThat(validation.reasonCodes()).containsExactly("SNAPSHOT_NOT_QUALIFIED");
    }

    @Test
    void shouldRejectACandidateWithoutOneIndependentQualifiedSource() {
        // Authority is absent, so no independent source identity can be counted for a
        // single-slot (candidate_quorum = 1) candidate.
        ResearchEvidenceQualificationService.BindingValidation validation =
                validationFor("value-0", "trusted quote 0", "SUPPORTS", null, 1);

        assertThat(validation.finalStatus()).isEqualTo("REJECTED");
        assertThat(validation.reasonCodes()).contains("QUALIFIED_SOURCE_REQUIRED");
    }

    @Test
    void shouldRejectAnEmptyQuoteAtTheQualificationLayer() {
        ResearchEvidenceQualificationService.BindingValidation validation = validationFor(
                "value-0", "", "SUPPORTS",
                trustedSource("source-0", "workspace-source:source-0", VALID_LINEAGE, "WORKSPACE"), 1);

        assertThat(validation.finalStatus()).isEqualTo("REJECTED");
        assertThat(validation.reasonCodes()).contains("QUOTE_EMPTY");
    }

    @Test
    void shouldRejectAnEmptyClaimAtTheQualificationLayer() {
        ResearchEvidenceQualificationService.BindingValidation validation = validationFor(
                "", "trusted quote 0", "SUPPORTS",
                trustedSource("source-0", "workspace-source:source-0", VALID_LINEAGE, "WORKSPACE"), 1);

        assertThat(validation.finalStatus()).isEqualTo("REJECTED");
        assertThat(validation.reasonCodes()).contains("CLAIM_EMPTY");
    }

    @Test
    void shouldRejectAnUnknownRelationAtTheQualificationLayer() {
        ResearchEvidenceQualificationService.BindingValidation validation = validationFor(
                "value-0", "trusted quote 0", "UNSUPPORTED",
                trustedSource("source-0", "workspace-source:source-0", VALID_LINEAGE, "WORKSPACE"), 1);

        assertThat(validation.finalStatus()).isEqualTo("REJECTED");
        assertThat(validation.reasonCodes()).contains("RELATION_UNKNOWN");
    }

    // ------------- group 5: DR-112 — pin the invariants that make a dead branch unreachable

    /**
     * Guards Canonicalizer invariant #1: a candidate must reference at least one evidence key.
     * While this holds, {@code ResearchEvidenceQualificationService.validateBinding}'s
     * {@code evidence.isEmpty()} branch (and its {@code QUALIFIED_SOURCE_REQUIRED}) can never
     * resolve to a validation row.
     */
    @Test
    void shouldRejectACandidateWithNoEvidenceKeysBeforeAnEmptyBindingCanReachQualification() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");

        assertRejectedBeforeQualification(fixture,
                candidateEnvelopeWithEvidenceKeys(fixture, List.of()),
                "RESEARCH_AGENT_COMPLETION_INVALID",
                "evidence references are invalid");
    }

    /**
     * Guards Canonicalizer invariant #2: every candidate evidence key must be a subset of the
     * envelope's evidence keys. While this holds, {@code evaluate()}'s {@code evidenceByKey}
     * lookups can never leave a candidate binding dangling.
     */
    @Test
    void shouldRejectACandidateReferencingAnEvidenceKeyOutsideTheEnvelope() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");
        ResearchAgentCompletionEnvelope envelope =
                candidateEnvelopeWithEvidenceKeys(fixture, List.of("ev-does-not-exist"));

        // Non-vacuous: the single referenced key is unique, so only the "outside the envelope"
        // clause of the subset check can fire — never the duplicate-size clause.
        assertThat(envelope.candidates().get(0).evidenceKeys()).containsExactly("ev-does-not-exist");

        assertRejectedBeforeQualification(fixture, envelope,
                "RESEARCH_AGENT_COMPLETION_INVALID",
                "duplicated or outside the envelope");
    }

    /**
     * Guards Canonicalizer invariant #3: a candidate's evidence keys must be unique. While this
     * holds, the envelope's duplicate-evidence rejection keeps {@code evidenceByKey} unambiguous
     * and the subset check cannot be satisfied by a repeated key.
     */
    @Test
    void shouldRejectACandidateWithDuplicateEvidenceKeys() {
        Fixture fixture = fixture("prefix trusted quote 0 suffix");
        String evidenceKey = "ev-" + fixture.taskId().substring(0, 8);
        ResearchAgentCompletionEnvelope envelope = candidateEnvelopeWithEvidenceKeys(
                fixture, List.of(evidenceKey, evidenceKey));

        // Non-vacuous: the referenced key IS present in the envelope evidence, so the only
        // failing condition is referenced.size()(1) != evidenceKeys.size()(2) — the duplicate
        // clause, not the "outside the envelope" clause.
        assertThat(envelope.evidence()).anySatisfy(evidence ->
                assertThat(evidence.evidenceKey()).isEqualTo(evidenceKey));

        assertRejectedBeforeQualification(fixture, envelope,
                "RESEARCH_AGENT_COMPLETION_INVALID",
                "duplicated or outside the envelope");
    }

    // ------------------------------------------------------------------ fixture plumbing

    /**
     * Asserts the completion was rejected <em>by qualification</em> and left the canonical
     * ledger untouched.
     *
     * <p>{@code expectedMergeReason} distinguishes the two rejection origins: a candidate whose
     * literal binding passes ({@code SUPPORTS} + value equality) is rejected with
     * {@code EVIDENCE_QUALIFICATION_REJECTED} — the DR-301 gate reversing the pre-DR-301
     * promotion; a candidate that the literal rule already refuses carries {@code NOT_ENOUGH_INFO}.
     * Asserting it keeps each row of the negative report honest about which layer rejected.</p>
     */
    private void assertCandidateRejectedWithoutPromotion(
            Fixture fixture, ResearchAgentCompletionReceipt receipt,
            String reasonCode, String expectedMergeReason) {
        assertThat(receipt.acceptedMerges()).isEmpty();
        assertThat(receipt.rejectedMerges()).singleElement().satisfies(merge -> {
            assertThat(merge.cellKey()).isEqualTo(fixture.cellKey());
            assertThat(merge.fromVersion()).isZero();
            assertThat(merge.toVersion()).isEqualTo(merge.fromVersion());
            assertThat(merge.decision()).isEqualTo("REJECTED");
            assertThat(merge.reasonCode()).isEqualTo(expectedMergeReason);
        });
        assertThat(jdbcTemplate.queryForMap("""
                select candidate_value, cell_status, cell_version, evidence_refs_json, last_merge_id
                from research_cell where research_run_id = ? and cell_key = ?
                """, fixture.runId(), fixture.cellKey()))
                .containsEntry("candidate_value", "old-0")
                .containsEntry("cell_status", "CANDIDATE_READY")
                .containsEntry("cell_version", 0)
                .containsEntry("evidence_refs_json", null)
                .containsEntry("last_merge_id", null);
        assertThat(jdbcTemplate.queryForObject("""
                select final_status from research_evidence_validation
                where completion_id = ? and candidate_id is not null
                """, String.class, receipt.completionId())).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject("""
                select reason_codes_json from research_evidence_validation
                where completion_id = ? and candidate_id is not null
                """, String.class, receipt.completionId())).contains(reasonCode);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_evidence where research_run_id = ?",
                Integer.class, fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_cell_evidence rce
                join source_evidence se on se.id = rce.source_evidence_id
                where rce.research_run_id = ? and se.agent_completion_id = ?
                """, Integer.class, fixture.runId(), receipt.completionId())).isZero();
    }

    private void assertRejectedBeforeQualification(
            Fixture fixture, ResearchAgentCompletionEnvelope envelope, String expectedCode) {
        assertRejectedBeforeQualification(fixture, envelope, expectedCode, null);
    }

    /**
     * Asserts the canonicalizer refused the envelope <em>before</em> qualification ran, leaving
     * zero qualification and zero ledger writes.
     *
     * <p>{@code expectedMessageFragment} pins <em>which</em> canonicalizer rule fired, so a case
     * cannot pass vacuously by being intercepted by an earlier, unrelated check. When it is
     * {@code null} only the error code is asserted.</p>
     */
    private void assertRejectedBeforeQualification(
            Fixture fixture, ResearchAgentCompletionEnvelope envelope,
            String expectedCode, String expectedMessageFragment) {
        // active_task_id / updated_at are excluded: a successful commit legitimately releases
        // the task's cell binding. The negative case is that no qualification fact is produced
        // and no candidate value, version or evidence reference is touched.
        String cellBefore = cellFingerprint(fixture);

        assertThatThrownBy(() -> completionService.complete(fixture.taskId(), envelope))
                .isInstanceOfSatisfying(BusinessException.class, business -> {
                    assertThat(business.code()).isEqualTo(expectedCode);
                    if (expectedMessageFragment != null) {
                        assertThat(business.getMessage()).contains(expectedMessageFragment);
                    }
                });

        assertThat(cellFingerprint(fixture)).isEqualTo(cellBefore);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_completion where research_agent_task_id = ?",
                Integer.class, fixture.taskId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_evidence where research_run_id = ?",
                Integer.class, fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_evidence_validation where research_run_id = ?",
                Integer.class, fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_evidence where research_run_id = ?",
                Integer.class, fixture.runId())).isZero();
    }

    private String cellFingerprint(Fixture fixture) {
        return String.valueOf(jdbcTemplate.queryForList("""
                select cell_key, candidate_value, cell_status, cell_version, evidence_refs_json,
                       last_merge_id, last_verifier_decision, plan_revision, entity_set_version
                from research_cell where research_run_id = ? order by cell_key
                """, fixture.runId()));
    }

    private ResearchEvidenceQualificationService.BindingValidation validationFor(
            String claim, String quote, String relation,
            ResearchAgentCompletionCommitter.TrustedEvidenceSource source, int candidateQuorum) {
        ResearchAgentCompletionEnvelope.Evidence evidence = new ResearchAgentCompletionEnvelope.Evidence(
                "ev-0", "window-0", "source-0", "Source 0", "dr302 question", "field-0",
                quote, claim, relation, 900_000, 0, "WORKSPACE");
        ResearchAgentCompletionEnvelope.Candidate candidate = new ResearchAgentCompletionEnvelope.Candidate(
                "cand-0", "entity-1:field-0", 0, claim, List.of("ev-0"), 900_000);
        ResearchAgentCompletionEnvelope envelope = new ResearchAgentCompletionEnvelope(
                ResearchAgentCompletionCanonicalizer.SCHEMA_VERSION, "00000000-0000-0000-0000-000000000000",
                "worker-a", 1, 1L, "exec-0", "sha256:" + "3".repeat(64), "CANDIDATES_PROPOSED",
                usage(1, 1), Map.of("search_hits", 1L, "documents", 1L, "windows", 1L),
                "sha256:" + "3".repeat(64), List.of(evidence), List.of(candidate), null);
        Map<String, ResearchAgentCompletionCommitter.TrustedEvidenceSource> authority =
                source == null ? Map.of() : Map.of("ev-0", source);
        ResearchEvidenceQualificationService.QualificationBatch batch =
                qualification.evaluate(taskRow(candidateQuorum), envelope, authority);
        return batch.validations().stream()
                .filter(item -> "cand-0".equals(item.candidateKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No candidate binding validation was produced"));
    }

    private ResearchAgentCompletionCommitter.TrustedEvidenceSource trustedSource(
            String sourceId, String domain, String lineage, String snapshotStatus) {
        return new ResearchAgentCompletionCommitter.TrustedEvidenceSource(
                sourceId, "Source 0", null, "workspace", "workspace", snapshotStatus, null,
                "workspace-source:" + sourceId, domain, lineage);
    }

    private ResearchAgentCompletionCommitter.TaskRow taskRow(int candidateQuorum) {
        return new ResearchAgentCompletionCommitter.TaskRow(
                "task-0", "run-0", "CLAIMED", "DEEP_CELL", "entity-1", "main",
                1, 1, "[]", "{}", "{}", "research-agent-task-snapshot.v1", null, "worker-a",
                1, 1L, null, null, candidateQuorum, 1, true);
    }

    private ResearchAgentCompletionEnvelope candidateEnvelope(
            Fixture fixture, String quote, String claim, String relation, String candidateValue) {
        String evidenceKey = "ev-" + fixture.taskId().substring(0, 8);
        return signed(new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", fixture.taskId(), "worker-a", fixture.leaseEpoch(),
                fixture.fencingToken(), fixture.executionKey(), fixture.snapshotDigest(),
                "CANDIDATES_PROPOSED", usage(1, 1),
                Map.of("search_hits", 1L, "documents", 1L, "windows", 1L),
                "sha256:" + "3".repeat(64),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        evidenceKey, "window-0", "source-0", "Source 0", "dr302 question", "field-0",
                        quote, claim, relation, 900_000, 0, "WORKSPACE")),
                List.of(new ResearchAgentCompletionEnvelope.Candidate(
                        "cand-" + fixture.taskId().substring(0, 8), fixture.cellKey(), 0, candidateValue,
                        List.of(evidenceKey), 900_000)), null));
    }

    /**
     * DR-112: builds an otherwise-valid completion whose candidate references an arbitrary set of
     * evidence keys, used to drive the Canonicalizer invariants that keep the empty-binding branch
     * unreachable. The single envelope evidence keeps {@code evidence_cards} consistent so the
     * rejection comes from the candidate-reference rule, not the counter check.
     */
    private ResearchAgentCompletionEnvelope candidateEnvelopeWithEvidenceKeys(
            Fixture fixture, List<String> candidateEvidenceKeys) {
        String evidenceKey = "ev-" + fixture.taskId().substring(0, 8);
        return signed(new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", fixture.taskId(), "worker-a", fixture.leaseEpoch(),
                fixture.fencingToken(), fixture.executionKey(), fixture.snapshotDigest(),
                "CANDIDATES_PROPOSED", usage(1, 1),
                Map.of("search_hits", 1L, "documents", 1L, "windows", 1L),
                "sha256:" + "3".repeat(64),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        evidenceKey, "window-0", "source-0", "Source 0", "dr302 question", "field-0",
                        "trusted quote 0", "value-0", "SUPPORTS", 900_000, 0, "WORKSPACE")),
                List.of(new ResearchAgentCompletionEnvelope.Candidate(
                        "cand-" + fixture.taskId().substring(0, 8), fixture.cellKey(), 0, "value-0",
                        candidateEvidenceKeys, 900_000)), null));
    }

    private Map<String, Long> usage(long evidenceCards, long candidatesSubmitted) {
        return Map.of(
                "llm_calls", 0L, "search_calls", 1L, "fetch_calls", 1L, "read_calls", 1L,
                "extract_calls", 1L, "evidence_cards", evidenceCards,
                "candidates_submitted", candidatesSubmitted);
    }

    private ResearchAgentCompletionEnvelope signed(ResearchAgentCompletionEnvelope envelope) {
        return envelope.withEnvelopeDigest(canonicalizer.digest(envelope));
    }

    private Fixture fixture(String sampleText) {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "dr302-" + runId);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode)
                values (?, ?, ?, 'dr302 question', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId);
        String rowId = Ids.newId();
        jdbcTemplate.update(
                "insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')",
                rowId, runId);
        String cellKey = "entity-1:field-0";
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, ?, 'field-0', 'old-0', 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), runId, rowId, cellKey);
        Map<String, Object> snapshotBudget = new LinkedHashMap<>();
        RESERVED.forEach(snapshotBudget::put);
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "dr302-task-" + runId, "dr302-idem-" + runId, 1, "DEEP_CELL", "entity-1", "main",
                1, 1, List.of(cellKey), snapshotBudget,
                List.of(new ResearchAgentTaskService.TargetCellBinding(cellKey, 0)),
                new ResearchAgentTaskService.TaskExecutionContext("research-fake",
                        Map.of("source_scope", List.of(Map.of(
                                "source_id", "source-0", "source_title", "Source 0",
                                "sample_text", sampleText))),
                        Map.of("query", "dr302 question")))).taskId();
        outboxService.enqueue(taskId);
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, taskId, "dr302-budget-" + taskId, RESERVED));
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 300));
        return new Fixture(runId, taskId, cellKey, claim.leaseEpoch(), claim.fencingToken(),
                claim.snapshotDigest(), "deep-cell:" + taskId + ":" + claim.leaseEpoch() + ":" + claim.fencingToken());
    }

    private record Fixture(
            String runId,
            String taskId,
            String cellKey,
            int leaseEpoch,
            long fencingToken,
            String snapshotDigest,
            String executionKey
    ) { }
}
