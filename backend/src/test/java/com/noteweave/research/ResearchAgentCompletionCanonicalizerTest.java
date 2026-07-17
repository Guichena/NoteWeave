package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** MA4G-1 cross-runtime completion.v1 contract and canonical digest red tests. */
class ResearchAgentCompletionCanonicalizerTest {

    private final ResearchAgentCompletionCanonicalizer canonicalizer = new ResearchAgentCompletionCanonicalizer();
    private final ResearchAgentCompletionEnvelopeParser parser = new ResearchAgentCompletionEnvelopeParser();

    @Test
    void shouldOrderCanonicalObjectKeysByUnsignedUtf8LikePython() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("😀", "emoji");
        value.put("\uE000", "private-use");

        assertThat(canonicalizer.canonicalJsonValue(value))
                .isEqualTo("{\"\uE000\":\"private-use\",\"😀\":\"emoji\"}");
    }

    @Test
    void shouldIgnoreMapAndIdentityListInputOrder() {
        ResearchAgentCompletionEnvelope first = envelope(goldenPayloadEvidence(), goldenPayloadCandidates());
        List<ResearchAgentCompletionEnvelope.Evidence> reversedEvidence = new ArrayList<>(first.evidence());
        java.util.Collections.reverse(reversedEvidence);
        List<ResearchAgentCompletionEnvelope.Candidate> reversedCandidates = new ArrayList<>(first.candidates());
        java.util.Collections.reverse(reversedCandidates);
        ResearchAgentCompletionEnvelope.Candidate firstReversed = reversedCandidates.get(0);
        List<String> reversedKeys = new ArrayList<>(firstReversed.evidenceKeys());
        java.util.Collections.reverse(reversedKeys);
        reversedCandidates.set(0, firstReversed.withEvidenceKeys(reversedKeys));
        Map<String, Long> reversedBudget = reversed(first.budgetUsage());
        Map<String, Long> reversedTelemetry = reversed(first.telemetry());
        ResearchAgentCompletionEnvelope second = copy(first, reversedBudget, reversedTelemetry,
                reversedEvidence, reversedCandidates, null);

        assertThat(canonicalizer.digest(first)).isEqualTo(canonicalizer.digest(second));
        assertThat(canonicalizer.canonicalBytes(first)).containsExactly(canonicalizer.canonicalBytes(second));
    }

    @Test
    void shouldMatchSharedPythonGoldenVectorWithNfcCompactUtf8AndEscaping() {
        ResearchAgentCompletionEnvelope.Evidence evidence = goldenPayloadEvidence().get(0);
        ResearchAgentCompletionEnvelope.Candidate candidate = new ResearchAgentCompletionEnvelope.Candidate(
                "candidate-b", "entity-1:result", 0, "Cafe\u0301 / 结论 \"B\" \\",
                List.of("evidence-b"), 800_000
        );
        ResearchAgentCompletionEnvelope envelope = envelope(List.of(evidence), List.of(candidate));
        String canonical = new String(canonicalizer.canonicalBytes(envelope), StandardCharsets.UTF_8);

        assertThat(canonical).doesNotContain(": ").doesNotContain(", ")
                .contains("Café / 结论")
                .contains("\\n")
                .contains("\\\"B\\\"")
                .contains("\\\\");
        // Shared with workers/research-worker/tests/test_ma4g_completion_contract.py.
        assertThat(canonicalizer.digest(envelope))
                .isEqualTo("sha256:e45e94f3afc622fcddbba9794c481ace8b9003d5aa97e7b637b6593b2a5abeda");
    }

    @Test
    void shouldChangeDigestWhenAnySemanticContentChanges() {
        ResearchAgentCompletionEnvelope original = envelope(goldenPayloadEvidence(), goldenPayloadCandidates());
        ResearchAgentCompletionEnvelope changedIdentity = new ResearchAgentCompletionEnvelope(
                original.schemaVersion(), original.taskId() + "-changed", original.workerInstanceId(),
                original.leaseEpoch(), original.fencingToken(), original.executionKey(),
                original.taskSnapshotDigest(), original.terminationReason(), original.budgetUsage(),
                original.telemetry(), original.traceDigest(), original.evidence(), original.candidates(), null
        );
        ResearchAgentCompletionEnvelope.Evidence changedEvidence = new ResearchAgentCompletionEnvelope.Evidence(
                "evidence-b", "window-b", "source-b", "乙：引号 \" 与反斜杠 \\",
                "怎么验证？\n第二行", "方法 / 结果", "different quote", "结论 B", "SUPPORTS",
                800_000, 0, "WORKSPACE"
        );
        ResearchAgentCompletionEnvelope changedContent = copy(original, original.budgetUsage(), original.telemetry(),
                List.of(changedEvidence, original.evidence().get(1)), original.candidates(), null);

        assertThat(canonicalizer.digest(changedIdentity)).isNotEqualTo(canonicalizer.digest(original));
        assertThat(canonicalizer.digest(changedContent)).isNotEqualTo(canonicalizer.digest(original));
    }

    @Test
    void shouldRejectUnknownDuplicateFloatBooleanAndCrossEnvelopeReferences() {
        String valid = canonicalizer.canonicalJson(envelope(goldenPayloadEvidence(), goldenPayloadCandidates()), true);
        String unknown = valid.substring(0, valid.length() - 1) + ",\"future_field\":true}";
        String duplicate = valid.replaceFirst(
                "\"task_id\":\"00000000-0000-0000-0000-000000000001\"",
                "\"task_id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"task_id\":\"00000000-0000-0000-0000-000000000002\"");
        String floatScore = valid.replace("\"support_score_ppm\":800000", "\"support_score_ppm\":0.8");
        String booleanUsage = valid.replace("\"search_calls\":1", "\"search_calls\":true");

        assertInvalid(unknown);
        assertInvalid(duplicate);
        assertInvalid(floatScore);
        assertInvalid(booleanUsage);

        ResearchAgentCompletionEnvelope invalidReference = copy(
                envelope(goldenPayloadEvidence(), goldenPayloadCandidates()),
                envelope(goldenPayloadEvidence(), goldenPayloadCandidates()).budgetUsage(),
                envelope(goldenPayloadEvidence(), goldenPayloadCandidates()).telemetry(),
                goldenPayloadEvidence(),
                List.of(new ResearchAgentCompletionEnvelope.Candidate(
                        "candidate-a", "entity-1:method", 3, "结论 A", List.of("outside"), 900_000)),
                null
        );
        assertThatThrownBy(() -> canonicalizer.validateAndVerify(invalidReference))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_COMPLETION_INVALID");
    }

    @Test
    void shouldCanonicalizeEmojiAndLineSeparatorsAndUseCodePointLengths() {
        ResearchAgentCompletionEnvelope original = envelope(goldenPayloadEvidence(), goldenPayloadCandidates());
        ResearchAgentCompletionEnvelope.Evidence first = original.evidence().get(0);
        String unicode = "emoji 😀 / literal \u2028 / paragraph \u2029";
        ResearchAgentCompletionEnvelope.Evidence unicodeEvidence = new ResearchAgentCompletionEnvelope.Evidence(
                first.evidenceKey(), first.windowId(), first.sourceId(), unicode, first.searchQuery(), first.readFocus(),
                first.quoteText(), first.claimText(), first.relationType(), first.supportScorePpm(),
                first.conflictScorePpm(), first.snapshotStatus());
        ResearchAgentCompletionEnvelope unicodeEnvelope = copy(original, original.budgetUsage(), original.telemetry(),
                List.of(unicodeEvidence, original.evidence().get(1)), original.candidates(), null);
        String full = canonicalizer.canonicalJson(unicodeEnvelope, true);
        String escaped = full.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");

        ResearchAgentCompletionEnvelope parsedLiteral = parser.parse(full.getBytes(StandardCharsets.UTF_8));
        ResearchAgentCompletionEnvelope parsedEscaped = parser.parse(escaped.getBytes(StandardCharsets.UTF_8));
        assertThat(canonicalizer.validateAndVerify(parsedLiteral).digest())
                .isEqualTo(canonicalizer.validateAndVerify(parsedEscaped).digest());
        assertThat(canonicalizer.canonicalBytes(unicodeEnvelope)).contains("😀".getBytes(StandardCharsets.UTF_8));

        String threeHundredEmoji = "😀".repeat(300);
        ResearchAgentCompletionEnvelope.Evidence boundary = new ResearchAgentCompletionEnvelope.Evidence(
                first.evidenceKey(), first.windowId(), first.sourceId(), threeHundredEmoji,
                first.searchQuery(), first.readFocus(), first.quoteText(), first.claimText(), first.relationType(),
                first.supportScorePpm(), first.conflictScorePpm(), first.snapshotStatus());
        ResearchAgentCompletionEnvelope atLimit = copy(original, original.budgetUsage(), original.telemetry(),
                List.of(boundary, original.evidence().get(1)), original.candidates(), null);
        canonicalizer.validateAndVerify(atLimit);
        ResearchAgentCompletionEnvelope overLimit = copy(original, original.budgetUsage(), original.telemetry(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        first.evidenceKey(), first.windowId(), first.sourceId(), threeHundredEmoji + "😀",
                        first.searchQuery(), first.readFocus(), first.quoteText(), first.claimText(), first.relationType(),
                        first.supportScorePpm(), first.conflictScorePpm(), first.snapshotStatus()),
                        original.evidence().get(1)), original.candidates(), null);
        assertInvalidEnvelope(overLimit);
    }

    @Test
    void shouldUseSharedUnicodeWhitespaceSetWithoutTrimmingBodyText() {
        ResearchAgentCompletionEnvelope original = envelope(goldenPayloadEvidence(), goldenPayloadCandidates());
        ResearchAgentCompletionEnvelope edgeNbsp = new ResearchAgentCompletionEnvelope(
                original.schemaVersion(), original.taskId(), "\u00a0worker-a", original.leaseEpoch(),
                original.fencingToken(), original.executionKey(), original.taskSnapshotDigest(),
                original.terminationReason(), original.budgetUsage(), original.telemetry(), original.traceDigest(),
                original.evidence(), original.candidates(), original.envelopeDigest());
        assertInvalidEnvelope(edgeNbsp);

        ResearchAgentCompletionEnvelope.Evidence first = original.evidence().get(0);
        ResearchAgentCompletionEnvelope blankQuery = copy(original, original.budgetUsage(), original.telemetry(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        first.evidenceKey(), first.windowId(), first.sourceId(), first.sourceTitle(), "\u2003",
                        first.readFocus(), first.quoteText(), first.claimText(), first.relationType(),
                        first.supportScorePpm(), first.conflictScorePpm(), first.snapshotStatus()),
                        original.evidence().get(1)), original.candidates(), null);
        assertInvalidEnvelope(blankQuery);

        ResearchAgentCompletionEnvelope internalNbsp = copy(original, original.budgetUsage(), original.telemetry(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        first.evidenceKey(), first.windowId(), first.sourceId(), first.sourceTitle(), "query\u00a0body",
                        first.readFocus(), first.quoteText(), first.claimText(), first.relationType(),
                        first.supportScorePpm(), first.conflictScorePpm(), first.snapshotStatus()),
                        original.evidence().get(1)), original.candidates(), null);
        assertThat(canonicalizer.validateAndVerify(internalNbsp).canonicalJson()).contains("query\u00a0body");
    }

    @Test
    void shouldDeepFreezeValidatedEnvelopeAndReceiptAgainstMutation() {
        List<ResearchAgentCompletionEnvelope.Evidence> evidenceBacking = new ArrayList<>(goldenPayloadEvidence());
        List<String> evidenceKeysBacking = new ArrayList<>(List.of("evidence-b", "evidence-a"));
        ResearchAgentCompletionEnvelope.Candidate firstCandidate = new ResearchAgentCompletionEnvelope.Candidate(
                "candidate-b", "entity-1:result", 0, "conclusion-b", evidenceKeysBacking, 800_000);
        List<ResearchAgentCompletionEnvelope.Candidate> candidateBacking = new ArrayList<>(List.of(
                firstCandidate,
                new ResearchAgentCompletionEnvelope.Candidate(
                        "candidate-a", "entity-1:method", 3, "conclusion-a", List.of("evidence-a"), 900_000)));
        Map<String, Long> budgetBacking = new LinkedHashMap<>();
        budgetBacking.put("llm_calls", 0L);
        budgetBacking.put("search_calls", 1L);
        budgetBacking.put("fetch_calls", 1L);
        budgetBacking.put("read_calls", 1L);
        budgetBacking.put("extract_calls", 1L);
        budgetBacking.put("evidence_cards", 2L);
        budgetBacking.put("candidates_submitted", 2L);
        Map<String, Long> telemetryBacking = new LinkedHashMap<>();
        telemetryBacking.put("search_hits", 2L);
        telemetryBacking.put("documents", 1L);
        telemetryBacking.put("windows", 2L);
        ResearchAgentCompletionEnvelope unsigned = new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", "00000000-0000-0000-0000-000000000001", "worker-a", 2, 7,
                "deep-cell:00000000-0000-0000-0000-000000000001:2:7",
                "sha256:" + "1".repeat(64), "CANDIDATES_PROPOSED", budgetBacking, telemetryBacking,
                "sha256:" + "2".repeat(64), evidenceBacking, candidateBacking, null);
        ResearchAgentCompletionEnvelope signed = unsigned.withEnvelopeDigest(canonicalizer.digest(unsigned));
        ResearchAgentCompletionCanonicalizer.ValidatedEnvelope validated = canonicalizer.validateAndVerify(signed);
        String canonicalBefore = validated.canonicalJson();
        String digestBefore = validated.digest();

        budgetBacking.put("llm_calls", 999L);
        telemetryBacking.put("documents", 999L);
        evidenceBacking.clear();
        candidateBacking.clear();
        evidenceKeysBacking.clear();

        assertThat(validated.canonicalJson()).isEqualTo(canonicalBefore);
        assertThat(validated.digest()).isEqualTo(digestBefore);
        assertThat(validated.envelope().budgetUsage().get("llm_calls")).isZero();
        assertThat(validated.envelope().evidence()).hasSize(2);
        assertThat(validated.envelope().candidates()).hasSize(2);
        assertThat(validated.envelope().candidates().get(0).evidenceKeys()).containsExactly("evidence-b", "evidence-a");
        assertThatThrownBy(() -> validated.envelope().budgetUsage().put("llm_calls", 3L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> validated.envelope().telemetry().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> validated.envelope().evidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> validated.envelope().candidates().get(0).evidenceKeys().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        List<ResearchAgentCompletionReceipt.MergeReceipt> acceptedBacking = new ArrayList<>(List.of(
                new ResearchAgentCompletionReceipt.MergeReceipt("entity-1:method", 3, 4,
                        "ACCEPTED", "VERIFIED_AND_VERSION_MATCHED")));
        Map<String, Long> reservedBacking = new LinkedHashMap<>(Map.of("llm_calls", 2L));
        Map<String, Long> consumedBacking = new LinkedHashMap<>(Map.of("llm_calls", 1L));
        Map<String, Long> releasedBacking = new LinkedHashMap<>(Map.of("llm_calls", 1L));
        ResearchAgentCompletionReceipt receipt = new ResearchAgentCompletionReceipt(
                "research-agent-completion-receipt.v1", "completion-1", "execution-1", signed.taskId(),
                signed.envelopeDigest(), "sha256:" + "3".repeat(64), false, 2, 2,
                acceptedBacking, new ArrayList<>(),
                new ResearchAgentCompletionReceipt.BudgetReceipt(
                        "SETTLED", reservedBacking, consumedBacking, releasedBacking));
        acceptedBacking.clear();
        reservedBacking.put("llm_calls", 99L);
        consumedBacking.clear();
        releasedBacking.clear();

        assertThat(receipt.acceptedMerges()).hasSize(1);
        assertThat(receipt.budget().reserved()).containsEntry("llm_calls", 2L);
        assertThat(receipt.budget().consumed()).containsEntry("llm_calls", 1L);
        assertThat(receipt.budget().released()).containsEntry("llm_calls", 1L);
        assertThatThrownBy(() -> receipt.acceptedMerges().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> receipt.budget().reserved().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectCanonicalEnvelopeThatExceedsRawByteLimitEvenForDirectServiceCalls() {
        List<ResearchAgentCompletionEnvelope.Evidence> evidence = new ArrayList<>();
        String body = "x".repeat(16_384);
        for (int index = 0; index < 12; index++) {
            evidence.add(new ResearchAgentCompletionEnvelope.Evidence(
                    "evidence-" + index, "window-" + index, "source-" + index, "source",
                    "query", "focus", body, body, "SUPPORTS", 1_000_000, 0, "WORKSPACE"));
        }
        ResearchAgentCompletionEnvelope unsigned = new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", "00000000-0000-0000-0000-000000000001", "worker-a", 1, 1,
                "deep-cell:00000000-0000-0000-0000-000000000001:1:1",
                "sha256:" + "1".repeat(64), "EVIDENCE_ONLY",
                Map.of("llm_calls", 0L, "search_calls", 0L, "fetch_calls", 0L, "read_calls", 0L,
                        "extract_calls", 0L, "evidence_cards", 12L, "candidates_submitted", 0L),
                Map.of("search_hits", 0L, "documents", 0L, "windows", 0L),
                "sha256:" + "2".repeat(64), evidence, List.of(), null);
        ResearchAgentCompletionEnvelope signed = unsigned.withEnvelopeDigest(canonicalizer.digest(unsigned));

        assertInvalidEnvelope(signed);
    }

    private void assertInvalid(String raw) {
        assertThatThrownBy(() -> parser.parse(raw.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_COMPLETION_INVALID");
    }

    private void assertInvalidEnvelope(ResearchAgentCompletionEnvelope envelope) {
        assertThatThrownBy(() -> canonicalizer.validateAndVerify(envelope))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_COMPLETION_INVALID");
    }

    private ResearchAgentCompletionEnvelope envelope(
            List<ResearchAgentCompletionEnvelope.Evidence> evidence,
            List<ResearchAgentCompletionEnvelope.Candidate> candidates
    ) {
        Map<String, Long> budget = new LinkedHashMap<>();
        budget.put("llm_calls", 0L);
        budget.put("search_calls", 1L);
        budget.put("fetch_calls", 1L);
        budget.put("read_calls", 1L);
        budget.put("extract_calls", 1L);
        budget.put("evidence_cards", (long) evidence.size());
        budget.put("candidates_submitted", (long) candidates.size());
        Map<String, Long> telemetry = new LinkedHashMap<>();
        telemetry.put("search_hits", 2L);
        telemetry.put("documents", 1L);
        telemetry.put("windows", 2L);
        ResearchAgentCompletionEnvelope envelope = new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", "00000000-0000-0000-0000-000000000001", "worker-a", 2, 7,
                "deep-cell:00000000-0000-0000-0000-000000000001:2:7",
                "sha256:" + "1".repeat(64), "CANDIDATES_PROPOSED",
                budget, telemetry, "sha256:" + "2".repeat(64), evidence, candidates, null
        );
        return envelope.withEnvelopeDigest(canonicalizer.digest(envelope));
    }

    private List<ResearchAgentCompletionEnvelope.Evidence> goldenPayloadEvidence() {
        return List.of(
                new ResearchAgentCompletionEnvelope.Evidence(
                        "evidence-b", "window-b", "source-b", "乙：引号 \" 与反斜杠 \\",
                        "怎么验证？\n第二行", "方法 / 结果", "原文 B", "结论 B", "SUPPORTS",
                        800_000, 0, "WORKSPACE"),
                new ResearchAgentCompletionEnvelope.Evidence(
                        "evidence-a", "window-a", "source-a", "甲", "怎么验证？", "方法",
                        "原文 A", "结论 A", "SUPPORTS", 900_000, 0, "WORKSPACE")
        );
    }

    private List<ResearchAgentCompletionEnvelope.Candidate> goldenPayloadCandidates() {
        return List.of(
                new ResearchAgentCompletionEnvelope.Candidate(
                        "candidate-b", "entity-1:result", 0, "结论 B",
                        List.of("evidence-b", "evidence-a"), 800_000),
                new ResearchAgentCompletionEnvelope.Candidate(
                        "candidate-a", "entity-1:method", 3, "结论 A",
                        List.of("evidence-a"), 900_000)
        );
    }

    private ResearchAgentCompletionEnvelope copy(
            ResearchAgentCompletionEnvelope source,
            Map<String, Long> budget,
            Map<String, Long> telemetry,
            List<ResearchAgentCompletionEnvelope.Evidence> evidence,
            List<ResearchAgentCompletionEnvelope.Candidate> candidates,
            String digest
    ) {
        ResearchAgentCompletionEnvelope result = new ResearchAgentCompletionEnvelope(
                source.schemaVersion(), source.taskId(), source.workerInstanceId(), source.leaseEpoch(),
                source.fencingToken(), source.executionKey(), source.taskSnapshotDigest(), source.terminationReason(),
                budget, telemetry, source.traceDigest(), evidence, candidates, digest
        );
        return result.withEnvelopeDigest(canonicalizer.digest(result));
    }

    private Map<String, Long> reversed(Map<String, Long> source) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(source.entrySet());
        java.util.Collections.reverse(entries);
        Map<String, Long> result = new LinkedHashMap<>();
        entries.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
