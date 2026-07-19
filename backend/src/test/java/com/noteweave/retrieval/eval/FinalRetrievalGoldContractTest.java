package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class FinalRetrievalGoldContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void qaAndNoteFinalGoldSetsAreSeparateAndContainRefusalCases() throws Exception {
        RetrievalGoldSet qa = readGold("qa-weknora-gold-v1.json");
        RetrievalGoldSet note = readGold("note-marginalia-gold-v1.json");

        assertThat(qa.datasetVersion()).isEqualTo("qa-weknora-gold-v1");
        assertThat(qa.cases()).allMatch(item -> "QA".equals(item.mode()))
                .anyMatch(RetrievalGoldSet.GoldCase::shouldRefuse);
        assertThat(note.datasetVersion()).isEqualTo("note-marginalia-gold-v1");
        assertThat(note.cases()).allMatch(item -> "NOTE".equals(item.mode()))
                .anyMatch(RetrievalGoldSet.GoldCase::shouldRefuse);
    }

    @Test
    void ablationMatrixContainsEveryRequiredFinalVariant() throws Exception {
        JsonNode root = mapper.readTree(resource("qa-note-ablation-matrix-v1.json"));
        assertThat(strings(root.path("qa"))).containsExactlyInAnyOrder(
                "keyword-only", "vector-only", "hybrid-rrf", "hybrid-rrf-rerank",
                "full-pipeline-evidence-budget");
        assertThat(strings(root.path("note"))).containsExactlyInAnyOrder(
                "metadata-only", "metadata-journal", "metadata-semantic",
                "metadata-journal-semantic", "full-recall-relations",
                "full-recall-relations-rerank-quota");
    }

    @Test
    void ablationReportContainsMeasuredResultsForEveryFinalVariant() throws Exception {
        JsonNode root = mapper.readTree(resource("qa-note-ablation-report-v1.json"));
        assertThat(root.path("execution").asText()).isEqualTo("deterministic-gold-replay");
        assertMeasuredVariants(root.path("qa"), Set.of(
                "keyword-only", "vector-only", "hybrid-rrf", "hybrid-rrf-rerank",
                "full-pipeline-evidence-budget"));
        assertMeasuredVariants(root.path("note"), Set.of(
                "metadata-only", "metadata-journal", "metadata-semantic",
                "metadata-journal-semantic", "full-recall-relations",
                "full-recall-relations-rerank-quota"));
    }

    private void assertMeasuredVariants(JsonNode plan, Set<String> expected) {
        assertThat(plan.path("caseCount").asInt()).isPositive();
        assertThat(plan.path("scopeViolationCount").asInt()).isZero();
        assertThat(plan.path("currentSnapshotErrorCount").asInt()).isZero();
        assertThat(plan.path("citationOwnershipErrorCount").asInt()).isZero();
        assertThat(plan.path("degradedCaseCount").asInt()).isZero();
        assertThat(strings(plan.path("variants"))).containsExactlyInAnyOrderElementsOf(expected);
        for (JsonNode variant : plan.path("variants")) {
            assertThat(variant.path("recallAtK").asDouble()).isBetween(0.0, 1.0);
            assertThat(variant.path("mrr").asDouble()).isBetween(0.0, 1.0);
            assertThat(variant.path("ndcgAtK").asDouble()).isBetween(0.0, 1.0);
            assertThat(variant.path("citationCoverage").asDouble()).isBetween(0.0, 1.0);
            assertThat(variant.path("scopeViolationCount").asInt()).isZero();
            assertThat(variant.path("p95LatencyMicros").asLong()).isPositive();
        }
    }

    private RetrievalGoldSet readGold(String name) throws Exception {
        return mapper.readValue(resource(name), RetrievalGoldSet.class);
    }

    private java.io.InputStream resource(String name) {
        return getClass().getResourceAsStream("/retrieval/" + name);
    }

    private Set<String> strings(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false)
                .map(item -> item.isTextual() ? item.asText() : item.path("variant").asText())
                .collect(Collectors.toSet());
    }
}
