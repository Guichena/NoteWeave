package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.memory.MemoryControlPackResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F5-02：来源偏好只在允许的策略内收紧研究范围，缺种子时显式失败。 */
class ResearchSourcePolicyTest {

    @Test
    void workspaceOnlyWithoutSeedsFailsInsteadOfSilentlyUsingWeb() {
        ResearchAcquisitionPolicy policy = new ResearchAcquisitionPolicy(
                ResearchRetrievalMode.WEB_ONLY, List.of(), true, true);

        assertThatThrownBy(() -> ResearchRunCommandService.applySourcePolicy(policy, "workspace_only"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("RESEARCH_SOURCE_POLICY_CONFLICT"));
    }

    @Test
    void workspaceOnlyNarrowsSeededWebRunToSourcesOnly() {
        ResearchAcquisitionPolicy policy = new ResearchAcquisitionPolicy(
                ResearchRetrievalMode.WEB_PLUS_SEEDS, List.of("seed-1"), true, true);

        ResearchAcquisitionPolicy narrowed =
                ResearchRunCommandService.applySourcePolicy(policy, "workspace_only");

        assertThat(narrowed.mode()).isEqualTo(ResearchRetrievalMode.SOURCES_ONLY);
        assertThat(narrowed.seedSourceIds()).containsExactly("seed-1");
        assertThat(narrowed.allowExternalSearch()).isFalse();
        assertThat(narrowed.allowExternalFetch()).isFalse();
    }

    @Test
    void webAllowedLeavesExplicitPolicyUntouched() {
        ResearchAcquisitionPolicy policy = new ResearchAcquisitionPolicy(
                ResearchRetrievalMode.WEB_PLUS_SEEDS, List.of("seed-1"), true, true);

        assertThat(ResearchRunCommandService.applySourcePolicy(policy, "web_allowed"))
                .isSameAs(policy);
        assertThat(ResearchRunCommandService.applySourcePolicy(policy, null)).isSameAs(policy);
    }

    @Test
    void sourcePolicyIsReadFromControlPackEvidencePolicy() {
        MemoryControlPackResponse pack = new MemoryControlPackResponse(
                "RESEARCH", "deep_research", "RESEARCH_RUN",
                List.of(), List.of(), List.of(), List.of(),
                List.of("只用工作台资料"), List.of(), List.of(),
                List.of(), List.of(), null);

        assertThat(ResearchRunCommandService.sourcePolicy(pack)).isEqualTo("workspace_only");
    }
}
