package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class QaEvidenceRelevancePolicyTest {

    @Test
    void shouldAcceptCrossLanguageProductSummaryWhenProductAndVersionAnchorMatch() {
        var evaluation = QaEvidenceRelevancePolicy.evaluate(
                "What are the main NoteWeave v2 capabilities and module boundaries?",
                "README.md",
                "NoteWeave v2 是研究工作台，包含问答 RAG、Note、Wiki、Deep Research，"
                        + "并由 Java 主系统和 Python Workers 分工实现。"
        );

        assertThat(evaluation.relevant()).isTrue();
        assertThat(evaluation.matchedTermCount()).isEqualTo(2);
        assertThat(evaluation.queryTermCoverage()).isEqualTo(0.4d);
    }

    @Test
    void shouldRejectGenericProductMatchesWhenDistinctQuestionTermsAreAbsent() {
        assertThat(QaEvidenceRelevancePolicy.isRelevant(
                "How does NoteWeave implement quantum banana theorem proof generation?",
                "README.md",
                "NoteWeave v2 is a research workspace implemented by Java and Python."
        )).isFalse();

        assertThat(QaEvidenceRelevancePolicy.isRelevant(
                "How do I capture and compile a reviewed QA retrieval annotation draft?",
                "README.md",
                "The project uses a QA retrieval pipeline and has frontend and backend modules."
        )).isFalse();
    }

    @Test
    void shouldAcceptWorkflowAndSafetyEvidenceWithMultipleSpecificMatches() {
        assertThat(QaEvidenceRelevancePolicy.isRelevant(
                "How do I capture and compile a reviewed QA retrieval annotation draft?",
                "QA retrieval evaluation",
                "Use capture-draft, review the annotation, then run compile-reviewed."
        )).isTrue();

        assertThat(QaEvidenceRelevancePolicy.isRelevant(
                "How are HTTP fetch, SSRF, prompt injection, and citation support verified?",
                "ResearchAgent capability coverage",
                "HTTP fetch and SSRF are covered by redirect, IP pinning, and content boundary tests."
        )).isTrue();
    }

    @Test
    void shouldUseHanBigramsAndRequireSingleTermEvidenceMatch() {
        assertThat(QaEvidenceRelevancePolicy.isRelevant(
                "模块边界如何划分？",
                "系统设计",
                "模块边界由主系统、检索模块和前端模块共同定义。"
        )).isTrue();
        assertThat(QaEvidenceRelevancePolicy.isRelevant("query", "title", "content")).isFalse();
        assertThat(QaEvidenceRelevancePolicy.isRelevant("query", "title", "query content")).isTrue();
    }

    @Test
    void shouldAdmitFirstSectionContinuationWithoutExpandingTransitively() {
        var candidates = List.of(
                candidate("source", 0,
                        "NoteWeave v2 是研究工作台，包含 Java 与 Python 技术分工。", 14.0d),
                candidate("source", 1,
                        "项目结构包括 docs、backend、frontend 与改造计划目录。", 8.0d),
                candidate("source", 2,
                        "Docker Compose 启动方式与历史设计输入。", 7.0d)
        );

        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                "What are the main NoteWeave v2 capabilities and module boundaries?", candidates))
                .containsExactly(0, 1);
    }

    @Test
    void shouldRejectLowCoverageCandidatesAndKeepRefusalWhenNoAnchorExists() {
        var artifactCandidates = List.of(
                candidate("backend", 4, "Artifact Worker network endpoints.", 10.9d),
                candidate("backend", 0,
                        "Artifact Version 保存、再生成、比较、回滚并导出 PDF。", 9.7d),
                candidate("backend", 1,
                        "Artifact Version worker control and local runtime settings.", 3.6d)
        );
        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                "How are Artifact versions saved, regenerated, compared, rolled back, and exported?",
                artifactCandidates)).isEmpty();

        var refusalCandidates = List.of(
                candidate("root", 0, "NoteWeave v2 is a research workspace.", 13.0d),
                candidate("root", 1, "The project has backend and frontend modules.", 10.0d)
        );
        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                "How does NoteWeave implement quantum banana theorem proof generation?",
                refusalCandidates)).isEmpty();
    }

    @Test
    void shouldAdmitExplicitDistinctiveIdentifiersWithoutAdmittingUnsupportedClaims() {
        var candidates = List.of(
                candidate("anchor", 0,
                        "AnchorTurnShared is the primary reading source.", 9.0d),
                candidate("neighbor", 0,
                        "TurnNeighborOnly is a co-cited source.", 8.0d)
        );

        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                "请同时总结 AnchorTurnShared 和 TurnNeighborOnly", candidates))
                .containsExactly(0, 1);
        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                "How does AnchorTurnShared implement quantum banana theorem proof generation?",
                candidates)).isEmpty();

        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                "请总结 ResearchReportCitationToken 的含义",
                List.of(candidate("report", 0,
                        "ResearchReportCitationToken is only present in the saved report output.",
                        7.0d))))
                .containsExactly(0);
        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                "请总结 AlphaMemory 的关键要求",
                List.of(candidate("memory", 0,
                        "AlphaMemory 说明回答应该先给结论，再给依据和引用。", 7.0d))))
                .containsExactly(0);
    }

    @Test
    void shouldFocusConversationQueriesOnCurrentQuestionAndTopicAnchor() {
        String query = """
                当前问题：第二点为什么重要
                主题锚点：先介绍 AlphaSpec 的关键要求
                连续对话窗口：
                - 用户：把它分成两点讲
                  助手摘要：这里包含很长但与候选正文无关的回答模板和控制信息
                """;

        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                query,
                List.of(candidate("alpha", 0,
                        "AlphaSpec 的第二个关键要求是证据留痕，这一点很重要。", 9.0d))))
                .containsExactly(0);
    }

    @Test
    void shouldKeepV1FreeOfDistinctiveIdentifierAndConversationFocusExtensions() {
        var identifierCandidate = candidate("report", 0,
                "ResearchReportCitationToken is only present in the saved report output.", 7.0d);

        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                QaEvidenceRelevancePolicy.POLICY_VERSION_V1,
                "请总结 ResearchReportCitationToken 的含义",
                List.of(identifierCandidate))).isEmpty();
        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                QaEvidenceRelevancePolicy.POLICY_VERSION_V2,
                "请总结 ResearchReportCitationToken 的含义",
                List.of(identifierCandidate))).containsExactly(0);

        String conversationQuery = """
                当前问题：第二点为什么重要
                主题锚点：先介绍 AlphaSpec 的关键要求
                连续对话窗口：
                - 用户：请总结完全无关的模板、历史记录、格式控制和额外背景
                  助手摘要：这里还包含大量与候选正文无关的内容
                """;
        var focusedCandidate = candidate("alpha", 0,
                "AlphaSpec 的第二个关键要求是证据留痕，这一点很重要。", 9.0d);

        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                QaEvidenceRelevancePolicy.POLICY_VERSION_V1,
                conversationQuery,
                List.of(focusedCandidate))).isEmpty();
        assertThat(QaEvidenceRelevancePolicy.admittedIndexes(
                QaEvidenceRelevancePolicy.POLICY_VERSION_V2,
                conversationQuery,
                List.of(focusedCandidate))).containsExactly(0);
    }

    @Test
    void shouldRejectUnknownPolicyVersionInsteadOfFallingBackToV2() {
        assertThatThrownBy(() -> QaEvidenceRelevancePolicy.admittedIndexes(
                "qa-lexical-sufficiency-unknown",
                "query",
                List.of(candidate("source", 0, "query content", 1.0d))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported QA relevance policy version");
    }

    private QaEvidenceRelevancePolicy.Candidate candidate(
            String sourceId,
            int chunkNo,
            String content,
            double score
    ) {
        return new QaEvidenceRelevancePolicy.Candidate(
                sourceId, "snapshot", chunkNo, "README.md", content, score);
    }
}
