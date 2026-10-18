package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.EvidenceSufficiency;
import com.noteweave.answer.strategy.EvidenceBundle;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EvidenceSufficiencyJudgeTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emptyEvidenceDoesNotCallTheModel() {
        ChatLlmClient llm = enabledClient();

        EvidenceSufficiencyJudge.Judgement judgement = judge(llm, true).judge(
                "问题", bundle());

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_EMPTY);
        verify(llm, never()).streamChat(any(), any(), anyInt(), any());
    }

    @Test
    void disabledSwitchAndUnavailableModelStayNotRun() {
        ChatLlmClient enabled = enabledClient();
        EvidenceSufficiencyJudge.Judgement disabled = judge(enabled, false).judge("问题", bundle(passage(0.9)));
        assertThat(disabled.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(disabled.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_DISABLED);

        ChatLlmClient unavailable = mock(ChatLlmClient.class);
        when(unavailable.isEnabled()).thenReturn(false);
        EvidenceSufficiencyJudge.Judgement missing = judge(unavailable, true).judge("问题", bundle(passage(0.9)));
        assertThat(missing.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(missing.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_UNAVAILABLE);
        verify(enabled, never()).streamChat(any(), any(), anyInt(), any());
        verify(unavailable, never()).streamChat(any(), any(), anyInt(), any());
    }

    @Test
    void lowFusionScoresDoNotDowngradeTheVerdictWhenRerankIsUnavailable() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any())).thenReturn("{\"verdict\":\"SUFFICIENT\"}");
        EvidenceBundle degraded = new EvidenceBundle("bundle", "plan-v1", List.of(passage(0.1)), true,
                List.of("NOTE_SOURCE_RERANK_UNAVAILABLE"), Instant.now());

        EvidenceSufficiencyJudge.Judgement judgement = judge(llm, true).judge("帮我整理一份学习笔记", degraded);

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_RERANK_UNAVAILABLE);
        // 重排正常时同样的低分仍然按阈值降级
        assertThat(judge(llm, true).judge("问题", bundle(passage(0.1))).verdict())
                .isEqualTo(EvidenceSufficiency.INSUFFICIENT);
    }

    @Test
    void invalidOutputIsRetriedOnceThenAccepted() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any()))
                .thenReturn("不是 JSON", "{\"verdict\":\"PARTIAL\"}");

        EvidenceSufficiencyJudge.Judgement judgement = judge(llm, true).judge("问题", bundle(passage(0.8)));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.PARTIAL);
        assertThat(judgement.reasons()).isEmpty();
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).streamChat(any(), prompts.capture(), anyInt(), any());
        assertThat(prompts.getAllValues().get(1)).contains("结构校验");
    }

    @Test
    void secondInvalidOutputAndCallFailureAreNotRun() {
        ChatLlmClient invalid = enabledClient();
        when(invalid.streamChat(any(), any(), anyInt(), any()))
                .thenReturn("{}", "{\"verdict\":\"MAYBE\"}");
        EvidenceSufficiencyJudge.Judgement rejected = judge(invalid, true).judge("问题", bundle(passage(0.8)));
        assertThat(rejected.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(rejected.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_INVALID);
        verify(invalid, times(2)).streamChat(any(), any(), anyInt(), any());

        ChatLlmClient failing = enabledClient();
        when(failing.streamChat(any(), any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("down"));
        EvidenceSufficiencyJudge.Judgement failed = judge(failing, true).judge("问题", bundle(passage(0.8)));
        assertThat(failed.verdict()).isEqualTo(EvidenceSufficiency.NOT_RUN);
        assertThat(failed.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_FAILED);
    }

    @Test
    void highModelScoreKeepsSufficientAndLowScoreCannotBePromoted() {
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, passage(0.80)).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, passage(0.35)).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, passage(0.34)).verdict())
                .isEqualTo(EvidenceSufficiency.PARTIAL);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, passage(0.34)).reasons())
                .containsExactly(EvidenceSufficiencyJudge.REASON_BELOW_035);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, passage(0.15)).verdict())
                .isEqualTo(EvidenceSufficiency.PARTIAL);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, passage(0.149)).verdict())
                .isEqualTo(EvidenceSufficiency.INSUFFICIENT);
        assertThat(combine(EvidenceSufficiency.PARTIAL, passage(0.15)).verdict())
                .isEqualTo(EvidenceSufficiency.PARTIAL);
        assertThat(combine(EvidenceSufficiency.PARTIAL, passage(0.149)).verdict())
                .isEqualTo(EvidenceSufficiency.INSUFFICIENT);
        assertThat(combine(EvidenceSufficiency.PARTIAL, passage(0.149)).reasons())
                .containsExactly(EvidenceSufficiencyJudge.REASON_BELOW_015);
        assertThat(combine(EvidenceSufficiency.INSUFFICIENT, passage(0.95)).verdict())
                .isEqualTo(EvidenceSufficiency.INSUFFICIENT);
        assertThat(combine(EvidenceSufficiency.INSUFFICIENT, passage(0.95)).reasons()).isEmpty();
    }

    @Test
    void rrfFallbackZeroAndNonFiniteScoresDoNotDemote() {
        EvidenceSufficiencyJudge.Judgement rrf = combine(EvidenceSufficiency.SUFFICIENT, passage(0.03));
        assertThat(rrf.verdict()).isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(rrf.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_ABSENT);

        assertThat(combine(EvidenceSufficiency.PARTIAL, passage(0.0)).reasons())
                .containsExactly(EvidenceSufficiencyJudge.REASON_ABSENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, passage(Double.NaN)).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, passage(12.0)).reasons())
                .containsExactly(EvidenceSufficiencyJudge.REASON_ABSENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, passage(0.05)).verdict())
                .isEqualTo(EvidenceSufficiency.INSUFFICIENT);
    }

    @Test
    void wikiPercentScaleUsesTheSeedAndIgnoresExpandedPages() {
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, wiki(80)).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, wiki(35)).verdict())
                .isEqualTo(EvidenceSufficiency.SUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, wiki(34)).verdict())
                .isEqualTo(EvidenceSufficiency.PARTIAL);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, wiki(14)).verdict())
                .isEqualTo(EvidenceSufficiency.INSUFFICIENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, wiki(4)).reasons())
                .containsExactly(EvidenceSufficiencyJudge.REASON_ABSENT);
        assertThat(combine(EvidenceSufficiency.SUFFICIENT, wiki(1)).reasons())
                .containsExactly(EvidenceSufficiencyJudge.REASON_ABSENT);

        EvidenceBundle.Evidence expanded = wiki(90, Map.of(
                WikiEvidenceRetriever.GRAPH_PATH_METADATA, "[\"种子\",\"扩展\"]"));
        EvidenceSufficiencyJudge.Judgement mixed = EvidenceSufficiencyJudge.combine(
                EvidenceSufficiency.SUFFICIENT, List.of(wiki(10), expanded));
        assertThat(mixed.verdict()).isEqualTo(EvidenceSufficiency.INSUFFICIENT);
        assertThat(mixed.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_BELOW_015);

        EvidenceSufficiencyJudge.Judgement onlyExpanded = EvidenceSufficiencyJudge.combine(
                EvidenceSufficiency.PARTIAL, List.of(expanded));
        assertThat(onlyExpanded.verdict()).isEqualTo(EvidenceSufficiency.PARTIAL);
        assertThat(onlyExpanded.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_ABSENT);
    }

    @Test
    void acceptedModelVerdictIsCombinedWithTheStoredScore() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any())).thenReturn("说明\n{\"verdict\":\"SUFFICIENT\"}");

        EvidenceSufficiencyJudge.Judgement judgement = judge(llm, true).judge("问题", bundle(passage(0.20)));

        assertThat(judgement.verdict()).isEqualTo(EvidenceSufficiency.PARTIAL);
        assertThat(judgement.reasons()).containsExactly(EvidenceSufficiencyJudge.REASON_BELOW_035);
        verify(llm, times(1)).streamChat(any(), any(), anyInt(), any());
    }

    private EvidenceSufficiencyJudge.Judgement combine(
            EvidenceSufficiency verdict,
            EvidenceBundle.Evidence evidence
    ) {
        return EvidenceSufficiencyJudge.combine(verdict, List.of(evidence));
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

    private EvidenceBundle.Evidence passage(double rerankScore) {
        return evidence("passage:one", "PASSAGE", rerankScore, Map.of());
    }

    private EvidenceBundle.Evidence wiki(double rerankScore) {
        return wiki(rerankScore, Map.of());
    }

    private EvidenceBundle.Evidence wiki(double rerankScore, Map<String, String> metadata) {
        return evidence("knowledge-version:one", "KNOWLEDGE_VERSION", rerankScore, metadata);
    }

    private EvidenceBundle.Evidence evidence(
            String id,
            String kind,
            double rerankScore,
            Map<String, String> metadata
    ) {
        return new EvidenceBundle.Evidence(
                id, kind, "source", "snapshot", "passage", "item", "version",
                "资料标题", "摘录", "location", 0, rerankScore, rerankScore,
                "scope", Instant.now(), "selected", 4, metadata);
    }
}
