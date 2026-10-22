package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 措辞判断的调用预算必须容得下推理模型的 {@code reasoning_content}。
 * <p>
 * live 评测里推理模型在 {@code max_tokens=80} 时流正常结束但 content 为空，
 * 客户端抛「LLM stream completed without content」，判断永远失败；
 * 与 {@code MemoryExtractionClassifier} 一样取 600。判定逻辑本身不变：
 * 空 content 视为一次失败，只重试一次，仍失败就不改措辞。
 */
class MemoryWordingJudgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void judgeBudgetIsLargeEnoughForReasoningModels() {
        ScriptedClient client = new ScriptedClient("{\"more_specific\": true}");

        assertThat(judge(client).moreSpecific("回答简短", "回答简短，先给结论")).isTrue();

        assertThat(client.requestedTokens).containsExactly(MemoryWordingJudge.MAX_OUTPUT_TOKENS);
        // 只答一个布尔值也用不到几十个 token，这里要的是「推理阶段有预算」而不是更大的答案空间
        assertThat(MemoryWordingJudge.MAX_OUTPUT_TOKENS).isGreaterThanOrEqualTo(512);
    }

    @Test
    void emptyContentIsTreatedAsFailureAndRetriedOnce() {
        ScriptedClient client = new ScriptedClient("", "{\"more_specific\": false}");

        assertThat(judge(client).moreSpecific("回答简短", "回答不要超过三句")).isFalse();

        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("上次输出不合规");
        // 重试不再压缩预算，否则推理模型仍然只会吐 reasoning_content
        assertThat(client.requestedTokens).containsOnly(MemoryWordingJudge.MAX_OUTPUT_TOKENS);
    }

    @Test
    void persistentEmptyContentLeavesTheWordingUnchanged() {
        ScriptedClient client = new ScriptedClient("", "");

        assertThat(judge(client).moreSpecific("回答简短", "回答不要超过三句")).isFalse();

        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void callFailureIsRetriedOnceThenLeavesTheWordingUnchanged() {
        ScriptedClient client = new ScriptedClient(
                new IllegalStateException("LLM stream call failed: LLM stream completed without content"),
                "{\"more_specific\": true}");

        assertThat(judge(client).moreSpecific("回答简短", "回答简短，先给结论")).isTrue();

        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("上次调用失败");
    }

    @Test
    void identicalOrMissingTextIsNotSentToTheModel() {
        ScriptedClient client = new ScriptedClient();

        MemoryWordingJudge judge = judge(client);

        assertThat(judge.moreSpecific("回答简短", "回答简短")).isFalse();
        assertThat(judge.moreSpecific(null, "回答简短")).isFalse();
        assertThat(client.calls).isZero();
    }

    private MemoryWordingJudge judge(ScriptedClient client) {
        return new MemoryWordingJudge(client, objectMapper);
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
