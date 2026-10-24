package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchSourceScopeUnknownTest {
    @Test
    void unknownProductBranchAndTimeAreKeptButMismatchesAreRejected() {
        assertThat(reasons("UNKNOWN", "UNKNOWN", "UNKNOWN", "CURRENT", false)).isEmpty();
        assertThat(reasons("MISMATCH", "APPLICABLE", "APPLICABLE", "CURRENT", false)).containsExactly("SOURCE_PRODUCT_MISMATCH");
        assertThat(reasons("MATCH", "MISMATCH", "APPLICABLE", "CURRENT", false)).containsExactly("SOURCE_BRANCH_MISMATCH");
        assertThat(reasons("MATCH", "APPLICABLE", "AFTER_CUTOFF", "HISTORICAL", false)).containsExactly("SOURCE_TIME_AFTER_CUTOFF");
    }

    @Test
    void historicalSourceCannotProveCurrentStatusUnlessTheCellAlsoAsksForHistory() {
        assertThat(reasons("MATCH", "APPLICABLE", "HISTORICAL", "CURRENT", false)).containsExactly("SOURCE_TIME_HISTORICAL");
        assertThat(reasons("MATCH", "APPLICABLE", "HISTORICAL", "CURRENT", true)).isEmpty();
        assertThat(reasons("MATCH", "APPLICABLE", "HISTORICAL", "HISTORICAL", false)).isEmpty();
    }

    @Test
    void eachCellUsesItsOwnTemporalModeWhenTheQuestionAsksForBoth() {
        String question = "Python 的无 GIL 自由线程构建从哪个版本开始提供，目前处于什么状态？引用 PEP 和官方文档。";
        assertThat(ResearchSourceScopePolicy.cellTemporalMode(question, "Python 的无 GIL 自由线程构建从哪个版本开始提供")).isEqualTo("HISTORICAL");
        assertThat(ResearchSourceScopePolicy.cellTemporalMode(question, "目前处于什么状态")).isEqualTo("CURRENT");
        assertThat(ResearchSourceScopePolicy.cellTemporalMode(question, "Governing PEP identifiers")).isEqualTo("UNSPECIFIED");
        assertThat(ResearchSourceScopePolicy.cellTemporalMode("Kubernetes 当前推荐的 Sidecar 声明方式", "声明字段")).isEqualTo("CURRENT");
        assertThat(ResearchSourceScopePolicy.temporalMode("Which release first added the feature")).isEqualTo("HISTORICAL");
        assertThat(ResearchSourceScopePolicy.temporalMode("Since which version is it available")).isEqualTo("HISTORICAL");
    }

    private List<String> reasons(String product, String branch, String time, String temporalMode, boolean allowHistorical) {
        Map<String, Object> check = Map.of("cluster_key", "cluster:a", "cell_key", "row:a",
                "product", product, "branch", branch, "time", time, "date", "", "date_quote", "", "date_kind", "UNKNOWN");
        var envelope = new ResearchAgentCompletionEnvelope("research-agent-completion.v2", "task", "worker", 1, 1,
                "execution", "snapshot", "CANDIDATES_PROPOSED", Map.of("llm_calls", 1L), Map.of(), "trace",
                List.of(), List.of(), null, Map.of("source_scope_checks", Map.of("schema_version",
                "research-source-scope-checks.v1", "model_calls", 1, "checks", List.of(check))), "digest");
        var mapper = new ObjectMapper();
        var cluster = mapper.valueToTree(Map.of("cluster_key", "cluster:a", "cell_key", "row:a", "evidence_keys", List.of("ev:a")));
        var scope = mapper.valueToTree(Map.of("temporal_mode", temporalMode, "as_of_date", "2026-10-24",
                "allow_historical_facts", allowHistorical));
        var source = new ResearchAgentCompletionCommitter.TrustedEvidenceSource("source", "title", "https://docs.example.test/howto/",
                "provider", "adapter", "EXTERNAL_ARCHIVED", "snapshot", "WEB", "docs.example.test", "lineage", "archived text");
        var reasons = new ArrayList<String>();
        ResearchSourceScopeChecks.check(envelope, cluster, scope, Map.of("ev:a", source), reasons);
        return reasons;
    }
}
