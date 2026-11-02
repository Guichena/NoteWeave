package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** F3-01：R0 输出结构校验、一次修正重试与确定性降级。 */
class ResearchBriefQuestionCompilerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validOutputBecomesIndependentQuestionWithSourcedPoints() {
        ScriptedLlm llm = new ScriptedLlm("""
                {"research_question":"OpenSERP 与托管搜索在部署与成本上的差异",
                 "key_points":[{"text":"我们正在比较 OpenSERP 与托管搜索","source_message_id":"m1"}]}
                """);
        List<Map<String, Object>> messages = List.of(
                Map.of("message_id", "m1", "role", "USER", "content", "比较 OpenSERP 与托管搜索"),
                Map.of("message_id", "m2", "role", "ASSISTANT", "content", "可以从部署和成本看"));

        ResearchBriefQuestionCompiler.Result result = ResearchBriefQuestionCompiler.compile(
                llm, mapper, "w", "run-1", "它的部署代价呢", messages);

        assertThat(result.degraded()).isFalse();
        assertThat(result.researchQuestion()).contains("OpenSERP");
        assertThat(result.keyPoints()).singleElement()
                .satisfies(point -> assertThat(point.get("source_message_id")).isEqualTo("m1"));
        assertThat(llm.lastSessionId).startsWith("noteweave-research.brief.compile-");
    }

    @Test
    void invalidOutputIsRetriedOnceAndFallsBackToLastUserMessage() {
        ScriptedLlm llm = new ScriptedLlm(
                "{\"research_question\":\"\",\"key_points\":[]}",
                "{\"research_question\":\"仍不合法\",\"key_points\":[{\"text\":\"x\",\"source_message_id\":\"missing\"}]}");
        List<Map<String, Object>> messages = List.of(
                Map.of("message_id", "m1", "role", "USER", "content", "上一轮的具体问题"),
                Map.of("message_id", "m2", "role", "ASSISTANT", "content", "上一轮回答"));

        ResearchBriefQuestionCompiler.Result result = ResearchBriefQuestionCompiler.compile(
                llm, mapper, "w", "run-1", "那它呢", messages);

        assertThat(result.degraded()).isTrue();
        assertThat(result.researchQuestion()).isEqualTo("上一轮的具体问题");
        assertThat(result.keyPoints()).isEmpty();
        assertThat(result.degradationReasons())
                .contains(ResearchBriefQuestionCompiler.DEGRADATION_INVALID);
        assertThat(llm.calls).isEqualTo(2);
    }

    @Test
    void disabledModelUsesDeterministicContextWithoutPretendingToUnderstand() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.enabled = false;

        ResearchBriefQuestionCompiler.Result result = ResearchBriefQuestionCompiler.compile(
                llm, mapper, "w", "run-1", "原问题",
                List.of(Map.of("message_id", "m1", "role", "USER", "content", "带来源的上一问")));

        assertThat(result.degraded()).isTrue();
        assertThat(result.researchQuestion()).isEqualTo("带来源的上一问");
        assertThat(result.degradationReasons())
                .containsExactly(ResearchBriefQuestionCompiler.DEGRADATION_UNAVAILABLE);
    }

    private static final class ScriptedLlm implements ChatLlmClient {
        private final Queue<String> outputs = new ArrayDeque<>();
        private boolean enabled = true;
        private int calls;
        private String lastSessionId;

        private ScriptedLlm(String... outputs) {
            this.outputs.addAll(List.of(outputs));
        }

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            calls++;
            String output = outputs.isEmpty() ? "" : outputs.remove();
            onToken.accept(output);
            return output;
        }

        @Override
        public String streamChatInSession(String sessionId, String systemPrompt, String userPrompt,
                                          int maximumOutputTokens, Consumer<String> onToken) {
            lastSessionId = sessionId;
            return streamChat(systemPrompt, userPrompt, maximumOutputTokens, onToken);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }
}
