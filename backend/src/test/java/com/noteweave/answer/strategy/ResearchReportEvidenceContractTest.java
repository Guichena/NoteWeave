package com.noteweave.answer.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** REPORT 通道在公共层的约束：计划只能声明本轮的研究报告，证据只能来自这些报告。 */
class ResearchReportEvidenceContractTest {

    private final AnswerStrategyContractValidator validator = new AnswerStrategyContractValidator();
    private final EvidenceScopeGuard scopeGuard = new EvidenceScopeGuard();

    @Test
    void planMustDeclareExactlyTheReportsOfThisTurn() {
        AnswerContext context = context(Set.of("run-b", "run-a"));
        assertThatCode(() -> validator.validatePlan(AnswerMode.QA, context, plan(AnswerMode.QA, "run-a,run-b")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validatePlan(AnswerMode.QA, context, plan(AnswerMode.QA, "run-a")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validatePlan(AnswerMode.NOTE, context, plan(AnswerMode.NOTE, "run-a,run-b")))
                .isInstanceOf(BusinessException.class);
        // 声明了研究报告却没有 REPORT 步骤，也不允许
        RetrievalPlan workspaceOnly = new RetrievalPlan("qa-v1", AnswerMode.QA,
                List.of(new RetrievalPlan.Step("QA_PASSAGE", 10, 1, Map.of("workspace_id", "workspace"))),
                new RetrievalPlan.Budget(6, 8_000, 0, 0));
        assertThatThrownBy(() -> validator.validatePlan(AnswerMode.QA, context, workspaceOnly))
                .isInstanceOf(BusinessException.class);
        // 普通步骤不能夹带研究范围
        RetrievalPlan smuggled = new RetrievalPlan("qa-v1", AnswerMode.QA,
                List.of(new RetrievalPlan.Step("QA_PASSAGE", 10, 1, Map.of("workspace_id", "workspace",
                        ResearchReportEvidence.FILTER_RESEARCH_RUN_IDS, "run-a"))),
                new RetrievalPlan.Budget(6, 8_000, 0, 0));
        assertThatThrownBy(() -> validator.validatePlan(AnswerMode.QA, context(Set.of()), smuggled))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void researchEvidenceMustBelongToADeclaredReport() {
        AnswerContext context = context(Set.of("run-a"));
        RetrievalPlan plan = plan(AnswerMode.QA, "run-a");
        assertThatCode(() -> scopeGuard.requireAllowed(context, plan, List.of(evidence("run-a", "0123456789abcdef"))))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> scopeGuard.requireAllowed(context, plan,
                List.of(evidence("run-other", "0123456789abcdef"))))
                .isInstanceOf(BusinessException.class);
        // 证据编号必须与访问范围、证据键一致，不能借用别的报告的身份
        EvidenceBundle.Evidence forged = new EvidenceBundle.Evidence(
                ResearchReportEvidence.evidenceId("run-other", "0123456789abcdef"), ResearchReportEvidence.KIND,
                null, null, "0123456789abcdef", null, null, "title", "excerpt", null, 0, 0, 0,
                ResearchReportEvidence.accessScope("run-a"), Instant.EPOCH, "test", 7, Map.of());
        assertThatThrownBy(() -> scopeGuard.requireAllowed(context, plan, List.of(forged)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ownershipIdentityCarriesTheResearchRun() {
        EvidenceOwnershipPort.EvidenceIdentity identity =
                EvidenceOwnershipPort.EvidenceIdentity.from(evidence("run-a", "0123456789abcdef"));
        assertThat(identity.researchRunId()).isEqualTo("run-a");
        assertThat(identity.passageId()).isEqualTo("0123456789abcdef");
    }

    @Test
    void budgeterSkipsEmptyExcerptsInsteadOfCountingThemAsFree() {
        EvidenceBundle.Evidence empty = new EvidenceBundle.Evidence(
                "empty", "PASSAGE", "s", "ss", "p", null, null, "t", " ", null, 9, 9, 9,
                "workspace-source:s", Instant.EPOCH, "test", 0, Map.of());
        List<EvidenceBundle.Evidence> selected = new EvidenceBudgeter().select(
                List.of(empty, evidence("run-a", "0123456789abcdef")), new RetrievalPlan.Budget(1, 100, 0, 0));
        assertThat(selected).extracting(EvidenceBundle.Evidence::passageId).containsExactly("0123456789abcdef");
    }

    private AnswerContext context(Set<String> reportScope) {
        return new AnswerContext("workspace", "conversation", "message", "query",
                Set.of(), Map.of(), Instant.EPOCH, reportScope);
    }

    private RetrievalPlan plan(AnswerMode mode, String reportIds) {
        return new RetrievalPlan("qa-v1+research-report-v1", mode,
                List.of(new RetrievalPlan.Step(ResearchReportEvidence.CHANNEL, 6, 1,
                        Map.of("workspace_id", "workspace", ResearchReportEvidence.FILTER_RESEARCH_RUN_IDS, reportIds))),
                new RetrievalPlan.Budget(6, 8_000, 0, 0));
    }

    private EvidenceBundle.Evidence evidence(String researchRunId, String key) {
        return new EvidenceBundle.Evidence(
                ResearchReportEvidence.evidenceId(researchRunId, key), ResearchReportEvidence.KIND,
                null, null, key, null, null, "Kafka 文档", "网页原文：Kafka only orders within a partition.",
                "https://kafka.apache.org", 1, 1, 0, ResearchReportEvidence.accessScope(researchRunId),
                Instant.EPOCH, "research_report_citation", 40, Map.of());
    }
}
