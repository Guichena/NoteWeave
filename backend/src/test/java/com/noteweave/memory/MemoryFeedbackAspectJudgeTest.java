package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 反馈判断只由模型决定：没有关键词或正则判定，代码只校验模型输出的 JSON。
 * 失败策略与记忆抽取一致：调用失败、空输出或不合规输出只重试一次，仍失败记为未能判断。
 */
class MemoryFeedbackAspectJudgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void modelDecidesFeedbackPolarityAndAspect() {
        ScriptedClient client = new ScriptedClient(
                "{\"feedback\":true,\"polarity\":\"NEGATIVE\",\"aspect\":\"LENGTH\"}");

        MemoryFeedbackAspectJudge.Judgment judgment =
                MemoryFeedbackAspectJudge.judge("这一段比我需要的长太多了", client, objectMapper);

        assertThat(judgment.feedback()).isTrue();
        assertThat(judgment.polarity()).isEqualTo(MemoryFeedbackAspectJudge.POLARITY_NEGATIVE);
        assertThat(judgment.aspect()).isEqualTo("LENGTH");
        assertThat(judgment.degradationReason()).isNull();
        assertThat(judgment.notJudged()).isFalse();
        assertThat(client.calls).isEqualTo(1);
        // 提示词把三个字段都写清楚，模型不需要猜字段名
        assertThat(client.systemPrompts).singleElement().satisfies(prompt -> {
            assertThat(prompt).contains("feedback").contains("polarity").contains("aspect");
            assertThat(prompt).contains("POSITIVE").contains("NEGATIVE").contains("NEUTRAL");
        });
    }

    @Test
    void wordingThatUsedToHitKeywordRulesIsNowDecidedByTheModel() {
        // 旧实现按词表把这些说法判成 LENGTH/LANGUAGE/FORMAT/STYLE/CONTENT；
        // 现在同样的文本只由模型回答，模型说 UNKNOWN 就是 UNKNOWN。
        ScriptedClient client = new ScriptedClient(
                "{\"feedback\":true,\"polarity\":\"NEGATIVE\",\"aspect\":\"UNKNOWN\"}");

        MemoryFeedbackAspectJudge.Judgment judgment =
                MemoryFeedbackAspectJudge.judge("太长了，别用英文，换成分点，语气软一点", client, objectMapper);

        assertThat(judgment.aspect()).isEqualTo("UNKNOWN");
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void reasoningModelGetsEnoughOutputBudget() {
        ScriptedClient client = new ScriptedClient(
                "{\"feedback\":true,\"polarity\":\"POSITIVE\",\"aspect\":\"UNKNOWN\"}");

        MemoryFeedbackAspectJudge.judge("谢谢，这样就行", client, objectMapper);

        // 推理模型会先把预算花在 reasoning_content 上，预算太小就只会拿到空 content
        assertThat(client.requestedTokens).containsExactly(MemoryFeedbackAspectJudge.MAX_OUTPUT_TOKENS);
        assertThat(MemoryFeedbackAspectJudge.MAX_OUTPUT_TOKENS).isGreaterThanOrEqualTo(512);
    }

    @Test
    void invalidModelOutputIsRetriedOnce() {
        ScriptedClient client = new ScriptedClient(
                "不是 JSON",
                "{\"feedback\":true,\"polarity\":\"NEGATIVE\",\"aspect\":\"LANGUAGE\"}");

        MemoryFeedbackAspectJudge.Judgment judgment =
                MemoryFeedbackAspectJudge.judge("这段没说到点上", client, objectMapper);

        assertThat(judgment.aspect()).isEqualTo("LANGUAGE");
        assertThat(judgment.degradationReason()).isNull();
        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("不合规");
    }

    @Test
    void illegalEnumValueIsNotAPassedJudgment() {
        // 字段齐全但枚举不在允许范围内：和不合规一样重试一次，仍不合规就记为未能判断
        ScriptedClient client = new ScriptedClient(
                "{\"feedback\":true,\"polarity\":\"满意\",\"aspect\":\"LENGTH\"}",
                "{\"feedback\":true,\"polarity\":\"NEGATIVE\",\"aspect\":\"长度\"}");

        MemoryFeedbackAspectJudge.Judgment judgment =
                MemoryFeedbackAspectJudge.judge("太长了", client, objectMapper);

        assertThat(judgment.aspect()).isEqualTo(MemoryFeedbackAspectJudge.ASPECT_UNKNOWN);
        assertThat(judgment.polarity()).isEqualTo(MemoryFeedbackAspectJudge.POLARITY_NEUTRAL);
        assertThat(judgment.degradationReason()).isEqualTo(MemoryFeedbackAspectJudge.ASPECT_NOT_RUN);
        assertThat(judgment.notJudged()).isTrue();
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void missingFieldsAreNotAPassedJudgment() {
        ScriptedClient client = new ScriptedClient(
                "{\"aspect\":\"LENGTH\"}",
                "{\"feedback\":true,\"polarity\":\"NEGATIVE\"}");

        assertThat(MemoryFeedbackAspectJudge.judge("太长了", client, objectMapper).degradationReason())
                .isEqualTo(MemoryFeedbackAspectJudge.ASPECT_NOT_RUN);
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void callFailureIsRetriedOnceThenGivesUp() {
        ScriptedClient client = new ScriptedClient(ScriptedClient.THROW, ScriptedClient.THROW);

        MemoryFeedbackAspectJudge.Judgment judgment =
                MemoryFeedbackAspectJudge.judge("太长了", client, objectMapper);

        assertThat(judgment.degradationReason()).isEqualTo(MemoryFeedbackAspectJudge.ASPECT_NOT_RUN);
        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("调用失败");
    }

    @Test
    void unavailableModelIsUnknownRatherThanAPassedJudgment() {
        ScriptedClient client = new ScriptedClient();
        client.enabled = false;

        MemoryFeedbackAspectJudge.Judgment judgment =
                MemoryFeedbackAspectJudge.judge("这段没说到点上", client, objectMapper);

        assertThat(judgment.aspect()).isEqualTo(MemoryFeedbackAspectJudge.ASPECT_UNKNOWN);
        assertThat(judgment.degradationReason()).isEqualTo(MemoryFeedbackAspectJudge.ASPECT_NOT_RUN);
        assertThat(client.calls).isZero();
    }

    @Test
    void configuredClientIsTheOnlyThingThatMakesTheJudgeAvailable() {
        ScriptedClient client = new ScriptedClient(
                "{\"feedback\":true,\"polarity\":\"NEGATIVE\",\"aspect\":\"LENGTH\"}");
        MemoryFeedbackAspectJudge judge = new MemoryFeedbackAspectJudge(client, objectMapper);

        assertThat(judge.available()).isTrue();
        assertThat(judge.judge("太长了").aspect()).isEqualTo("LENGTH");
        assertThat(new MemoryFeedbackAspectJudge(null, objectMapper).available()).isFalse();

        client.enabled = false;
        // 没配置模型属于常态：判断不可用，且不留下一次调用
        assertThat(judge.available()).isFalse();
        assertThat(judge.judge("太长了").degradationReason())
                .isEqualTo(MemoryFeedbackAspectJudge.ASPECT_NOT_RUN);
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void blankTextIsNotSentToTheModel() {
        ScriptedClient client = new ScriptedClient();

        MemoryFeedbackAspectJudge.Judgment judgment = MemoryFeedbackAspectJudge.judge("  ", client, objectMapper);

        assertThat(judgment.aspect()).isEqualTo(MemoryFeedbackAspectJudge.ASPECT_UNKNOWN);
        // 没有文本可判断属于常态，不算降级
        assertThat(judgment.degradationReason()).isNull();
        assertThat(client.calls).isZero();
    }

    /** 按顺序返回预置输出的模型桩；脚本值是 THROW 时抛异常，模拟调用失败。 */
    private static final class ScriptedClient implements ChatLlmClient {

        static final String THROW = "THROW";

        private final List<String> outputs = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private final List<String> systemPrompts = new ArrayList<>();
        private final List<Integer> requestedTokens = new ArrayList<>();
        private int calls;
        private boolean enabled = true;

        ScriptedClient(String... outputs) {
            this.outputs.addAll(List.of(outputs));
        }

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            calls++;
            prompts.add(userPrompt);
            systemPrompts.add(systemPrompt);
            requestedTokens.add(maximumOutputTokens);
            String output = outputs.isEmpty() ? "" : outputs.remove(0);
            if (THROW.equals(output)) {
                throw new IllegalStateException("model unavailable");
            }
            return output;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }
}
