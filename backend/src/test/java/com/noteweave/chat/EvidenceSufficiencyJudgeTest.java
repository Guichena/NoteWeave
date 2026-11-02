package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.EvidenceSufficiency;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.ScoreInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceSufficiencyJudgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emptyEvidenceIsNotRun() {
        EvidenceSufficiencyJudge.Judgement judgement =
                judge(enabledClient(), true).judge("问题", bundle());

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_EMPTY);
    }

    @Test
    void disabledJudgeIsNotRun() {
        EvidenceSufficiencyJudge.Judgement judgement =
                judge(enabledClient(), false).judge("问题", bundle(passage(0.9)));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_DISABLED);
    }

    @Test
    void missingLlmIsNotRun() {
        ChatLlmClient llm = mock(ChatLlmClient.class);
        when(llm.isEnabled()).thenReturn(false);

        EvidenceSufficiencyJudge.Judgement judgement =
                judge(llm, true).judge("问题", bundle(passage(0.9)));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_UNAVAILABLE);
    }

    @Test
    void invalidOutputIsNotRun() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenReturn("不是 JSON");

        EvidenceSufficiencyJudge.Judgement judgement =
                judge(llm, true).judge("问题", bundle(passage(0.9)));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_INVALID);
    }

    @Test
    void failingCallIsNotRun() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new IllegalStateException("provider down"));

        EvidenceSufficiencyJudge.Judgement judgement =
                judge(llm, true).judge("问题", bundle(passage(0.9)));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_FAILED);
    }

    @Test
    void providerScoreAtOrAbovePartialThresholdKeepsSufficient() {
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, provider(0.80)).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, provider(0.35)).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
    }

    @Test
    void providerScoreBetweenThresholdsDowngradesSufficientToPartial() {
        EvidenceSufficiencyJudge.Judgement judgement =
                combine(EvidenceSufficiency.SUFFICIENT, provider(0.34));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.PARTIAL);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_BELOW_035);
    }

    @Test
    void providerScoreBelowInsufficientThresholdDowngradesToInsufficient() {
        EvidenceSufficiencyJudge.Judgement sufficient =
                combine(EvidenceSufficiency.SUFFICIENT, provider(0.149));
        EvidenceSufficiencyJudge.Judgement partial =
                combine(EvidenceSufficiency.PARTIAL, provider(0.149));

        assertThat(sufficient.verdict()).isEqualTo(EvidenceSufficiency.INSUFFICIENT);
        assertThat(sufficient.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_BELOW_015);
        assertThat(partial.verdict()).isEqualTo(EvidenceSufficiency.INSUFFICIENT);
        assertThat(partial.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_BELOW_015);
    }

    @Test
    void insufficientVerdictIsNeverRaisedByScores() {
        EvidenceSufficiencyJudge.Judgement judgement =
                combine(EvidenceSufficiency.INSUFFICIENT, provider(0.95));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.INSUFFICIENT);
        assertThat(judgement.reasons()).isEmpty();
    }

    @Test
    void nonProviderScoresDoNotTriggerThresholds() {
        // 模型后备分与融合分都带着数值，但不是提供方重排分，不参与门槛
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, scored(ScoreInfo.llmFallback("llm-listwise-v1", 0.02))).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, scored(ScoreInfo.lexical("", 0.02))).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, scored(ScoreInfo.fusion())).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, scored(ScoreInfo.graph())).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, scored(ScoreInfo.unknown())).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
    }

    @Test
    void missingProviderScoreKeepsModelVerdict() {
        EvidenceSufficiencyJudge.Judgement judgement =
                combine(EvidenceSufficiency.SUFFICIENT, scored(ScoreInfo.fusion()));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_NO_PROVIDER_SCORE);
    }

    @Test
    void outOfRangeOrNonFiniteProviderScoresAreIgnored() {
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, provider(Double.NaN)).reasons())
                .containsExactly(EvidenceSufficiencyJudge.REASON_NO_PROVIDER_SCORE);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, provider(12.0)).reasons())
                .containsExactly("PROVIDER_THRESHOLD_POLICY_NOT_APPLICABLE");
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, provider(-1.0)).reasons())
                .containsExactly("PROVIDER_THRESHOLD_POLICY_NOT_APPLICABLE");
    }

    @Test
    void highestProviderScoreWinsAcrossBundle() {
        EvidenceBundle.Evidence low = provider(0.10);
        EvidenceBundle.Evidence high = new EvidenceBundle.Evidence(
                "passage:two", "PASSAGE", "source", "snapshot", "passage", "", "",
                "另一份资料", "摘录", "location", 0, 0.9, 0.9,
                "scope", Instant.now(), "selected", 4, Map.of(), ScoreInfo.provider("rerank-v1", 0.90, ScoreInfo.UNIT_INTERVAL, ScoreInfo.KNOWN_PROVIDER_015_035));

        EvidenceSufficiencyJudge.Judgement judgement =
                EvidenceSufficiencyJudge.combine(EvidenceSufficiency.SUFFICIENT, List.of(low, high));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(judgement.reasons()).isEmpty();
    }

    @Test
    void graphExpansionDoesNotCountAsProviderScore() {
        EvidenceBundle.Evidence graph = new EvidenceBundle.Evidence(
                "knowledge-version:expanded", "KNOWLEDGE_VERSION", "", "", "", "item", "version",
                "扩展页", "摘录", "location", 0, 0.9, 0.9,
                "scope", Instant.now(), "wiki-page-graph", 4,
                Map.of(WikiEvidenceRetriever.GRAPH_PATH_METADATA, "encoded"), ScoreInfo.graph());

        EvidenceSufficiencyJudge.Judgement judgement =
                EvidenceSufficiencyJudge.combine(EvidenceSufficiency.SUFFICIENT, List.of(graph));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_NO_PROVIDER_SCORE);
    }

    @Test
    void judgeReadsTheHitPassageInsteadOfTheLeadingAdjacentContext() {
        String content = "[adjacent chunk 3 - 背景]\n" + "前".repeat(300) + "\n\n命中段落：RRF K 取 60"
                + "\n\n[adjacent chunk 5]\n后续内容";

        assertThat(EvidenceSufficiencyJudge.hitText(content)).isEqualTo("命中段落：RRF K 取 60");
        assertThat(EvidenceSufficiencyJudge.hitText("没有相邻段落\n\n第二段")).isEqualTo("没有相邻段落\n\n第二段");
        assertThat(EvidenceSufficiencyJudge.hitText("[adjacent chunk 1]\n只有相邻段落"))
                .isEqualTo("[adjacent chunk 1]\n只有相邻段落");
    }

    private EvidenceSufficiencyJudge.Judgement combine(
            EvidenceSufficiency verdict,
            EvidenceBundle.Evidence evidence
    ) {
        return EvidenceSufficiencyJudge.combine(verdict, List.of(evidence));
    }

    private EvidenceSufficiencyJudge judge(ChatLlmClient llm, boolean enabled) {
        return new EvidenceSufficiencyJudge(llm, objectMapper, enabled);
    }

    private ChatLlmClient enabledClient() {
        ChatLlmClient llm = mock(ChatLlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        return llm;
    }

    private EvidenceBundle bundle(EvidenceBundle.Evidence... evidence) {
        return new EvidenceBundle("bundle", "plan-v1", List.of(evidence), false, List.of(), Instant.now());
    }

    private EvidenceBundle.Evidence provider(double value) {
        return scored(ScoreInfo.provider("rerank-v1", value, ScoreInfo.UNIT_INTERVAL, ScoreInfo.KNOWN_PROVIDER_015_035));
    }

    private EvidenceBundle.Evidence scored(ScoreInfo scoreInfo) {
        return new EvidenceBundle.Evidence(
                "passage:one", "PASSAGE", "source", "snapshot", "passage", "", "",
                "资料标题", "摘录", "location", 0, 0.9, 0.9,
                "scope", Instant.now(), "selected", 4, Map.of(), scoreInfo);
    }

    private EvidenceBundle.Evidence passage(double value) {
        return provider(value);
    }
}
