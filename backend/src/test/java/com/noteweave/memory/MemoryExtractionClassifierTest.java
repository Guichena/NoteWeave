package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 分类器的输出预算必须容得下推理模型的 {@code reasoning_content}。
 * <p>
 * live 评测里 deepseek-v4.1-flash 在 {@code max_tokens=10} 时流正常结束但 content 为空，
 * 客户端抛「LLM stream completed without content」，分类器全部 UNAVAILABLE，
 * 记忆抽取因此一条都走不到；约束抽取用 600 就没问题。
 */
class MemoryExtractionClassifierTest {

    @Test
    void classifierBudgetIsLargeEnoughForReasoningModels() {
        ScriptedClient client = new ScriptedClient("yes");

        assertThat(MemoryExtractionClassifier.decide(client, "以后回答先给结论")).isEqualTo("YES");

        assertThat(client.requestedTokens).containsExactly(MemoryExtractionClassifier.MAX_OUTPUT_TOKENS);
        // 只答 yes/no 也用不到几十个 token，这里要的是「推理阶段有预算」而不是更大的答案空间
        assertThat(MemoryExtractionClassifier.MAX_OUTPUT_TOKENS).isGreaterThanOrEqualTo(512);
    }

    @Test
    void emptyContentFailureIsRetriedOnceAndThenSucceeds() {
        ScriptedClient client = new ScriptedClient(
                new IllegalStateException("LLM stream call failed: LLM stream completed without content"), "yes");

        assertThat(MemoryExtractionClassifier.decide(client, "太长了")).isEqualTo("YES");

        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("上次调用失败");
        // 重试不再压缩预算，否则推理模型仍然只会吐 reasoning_content
        assertThat(client.requestedTokens).containsOnly(MemoryExtractionClassifier.MAX_OUTPUT_TOKENS);
    }

    @Test
    void blankContentWithoutExceptionIsRetriedOnce() {
        ScriptedClient client = new ScriptedClient("", "no");

        assertThat(MemoryExtractionClassifier.decide(client, "解释一下缓存一致性")).isEqualTo("NO");

        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("上次输出不合规");
    }

    @Test
    void persistentEmptyContentStillEndsAsUnavailable() {
        ScriptedClient client = new ScriptedClient(
                new IllegalStateException("LLM stream call failed: LLM stream completed without content"),
                new IllegalStateException("LLM stream call failed: LLM stream completed without content"));

        assertThat(MemoryExtractionClassifier.decide(client, "今天天气怎么样")).isEqualTo("UNAVAILABLE");

        // 失败策略不变：两次都不给结论就判不可用，由调用方标 LLM_UNAVAILABLE
        assertThat(client.calls).isEqualTo(2);
    }

    /** 记录每次调用的预算与用户提示词；脚本值是异常时抛出，模拟空 content 的失败流。 */
    private static final class ScriptedClient implements ChatLlmClient {

        private final List<Object> outputs = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private final List<Integer> requestedTokens = new ArrayList<>();
        private int calls;

        ScriptedClient(Object... outputs) {
            this.outputs.addAll(List.of(outputs));
        }

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            calls++;
            prompts.add(userPrompt);
            requestedTokens.add(maximumOutputTokens);
            Object output = outputs.isEmpty() ? "" : outputs.remove(0);
            if (output instanceof RuntimeException failure) {
                throw failure;
            }
            return (String) output;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
