package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class TopicBoundaryJudgeTest {

    @Test
    void invalidOutputIsRetriedOnce() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("nope");
        client.outputs.add("{\"decision\":\"NEW\"}");

        TopicBoundaryJudge.Decision decision = TopicBoundaryJudge.judge("当前消息", client);

        assertThat(decision.decision()).isEqualTo("NEW");
        assertThat(decision.skipped()).isFalse();
        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("不合规");
    }

    @Test
    void returnRequiresATopicIndex() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("{\"decision\":\"RETURN\"}");
        client.outputs.add("前文 {\"decision\":\"RETURN\",\"topic_index\":1} 结束");

        TopicBoundaryJudge.Decision decision = TopicBoundaryJudge.judge("当前消息", client);

        assertThat(decision.decision()).isEqualTo("RETURN");
        assertThat(decision.topicIndex()).isEqualTo(1);
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void unavailableModelStaysNotRun() {
        ScriptedClient client = new ScriptedClient();
        client.enabled = false;

        TopicBoundaryJudge.Decision decision = TopicBoundaryJudge.judge("当前消息", client);

        assertThat(decision.skipped()).isTrue();
        assertThat(decision.degradationReason()).isEqualTo(TopicBoundaryJudge.NOT_RUN);
        assertThat(client.calls).isZero();
    }

    @Test
    void twoFailuresStayNotRun() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("THROW");
        client.outputs.add("{\"decision\":\"MAYBE\"}");

        TopicBoundaryJudge.Decision decision = TopicBoundaryJudge.judge("当前消息", client);

        assertThat(decision.skipped()).isTrue();
        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("调用失败");
    }

    @Test
    void judgeBudgetIsLargeEnoughForReasoningModels() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("{\"decision\":\"SAME\"}");

        TopicBoundaryJudge.judge("当前消息", client);

        // 推理模型会先把预算花在 reasoning_content 上，预算太小就只会拿到空 content
        assertThat(client.requestedTokens).containsExactly(TopicBoundaryJudge.MAX_OUTPUT_TOKENS);
        assertThat(TopicBoundaryJudge.MAX_OUTPUT_TOKENS).isGreaterThanOrEqualTo(512);
    }

    @Test
    void emptyContentIsTreatedAsFailureAndRetriedOnce() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("");
        client.outputs.add("{\"decision\":\"NEW\"}");

        TopicBoundaryJudge.Decision decision = TopicBoundaryJudge.judge("当前消息", client);

        assertThat(decision.decision()).isEqualTo("NEW");
        assertThat(decision.skipped()).isFalse();
        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("不合规");
        // 重试不再压缩预算，否则推理模型仍然只会吐 reasoning_content
        assertThat(client.requestedTokens).containsOnly(TopicBoundaryJudge.MAX_OUTPUT_TOKENS);
    }

    @Test
    void persistentEmptyContentStaysNotRun() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("");
        client.outputs.add("");

        TopicBoundaryJudge.Decision decision = TopicBoundaryJudge.judge("当前消息", client);

        assertThat(decision.skipped()).isTrue();
        assertThat(decision.degradationReason()).isEqualTo(TopicBoundaryJudge.NOT_RUN);
        assertThat(client.calls).isEqualTo(2);
    }

    private static final class ScriptedClient implements ChatLlmClient {
        private final List<String> outputs = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private final List<Integer> requestedTokens = new ArrayList<>();
        private int calls;
        private boolean enabled = true;

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                  Consumer<String> onToken) {
            calls++;
            prompts.add(userPrompt);
            requestedTokens.add(maximumOutputTokens);
            if (outputs.isEmpty()) {
                return "";
            }
            String output = outputs.remove(0);
            if ("THROW".equals(output)) {
                throw new IllegalStateException("scripted failure");
            }
            return output;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }
}
