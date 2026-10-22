package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 合并判定的调用预算必须容得下推理模型的 {@code reasoning_content}。
 * <p>
 * live 评测里推理模型在 {@code max_tokens=40} 时流正常结束但 content 为空，
 * 客户端抛「LLM stream completed without content」，合并判定永远失败；
 * 与 {@code MemoryExtractionClassifier} 一样取 600。判定逻辑本身不变：
 * 空 content 视为一次失败，只重试一次，仍失败就不合并。
 */
class MemoryConsolidationServiceTest {

    @Test
    void mergeJudgeBudgetIsLargeEnoughForReasoningModels() {
        ScriptedClient client = new ScriptedClient("{\"merge\": true}");

        assertThat(service(client).decideMerge("回答简短", "回答别太长")).isTrue();

        assertThat(client.requestedTokens).containsExactly(MemoryConsolidationService.MAX_OUTPUT_TOKENS);
        // 只答一个布尔值也用不到几十个 token，这里要的是「推理阶段有预算」而不是更大的答案空间
        assertThat(MemoryConsolidationService.MAX_OUTPUT_TOKENS).isGreaterThanOrEqualTo(512);
    }

    @Test
    void emptyContentIsTreatedAsFailureAndRetriedOnce() {
        ScriptedClient client = new ScriptedClient("", "{\"merge\": false}");

        assertThat(service(client).decideMerge("回答简短", "回答别太长")).isFalse();

        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("上次输出不合规");
        // 重试不再压缩预算，否则推理模型仍然只会吐 reasoning_content
        assertThat(client.requestedTokens).containsOnly(MemoryConsolidationService.MAX_OUTPUT_TOKENS);
    }

    @Test
    void persistentEmptyContentMeansNoMerge() {
        ScriptedClient client = new ScriptedClient("", "");

        assertThat(service(client).decideMerge("回答简短", "回答别太长")).isNull();

        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void invalidOutputIsRetriedOnce() {
        ScriptedClient client = new ScriptedClient("不是 JSON", "{\"merge\": true}");

        assertThat(service(client).decideMerge("回答简短", "回答别太长")).isTrue();

        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void callFailureGivesUpWithoutRetrying() {
        ScriptedClient client = new ScriptedClient(
                new IllegalStateException("LLM stream call failed: LLM stream completed without content"));

        assertThat(service(client).decideMerge("回答简短", "回答别太长")).isNull();

        // 现有语义不变：调用失败即时放弃，只有输出不合规才重试一次
        assertThat(client.calls).isEqualTo(1);
    }

    private static MemoryConsolidationService service(ScriptedClient client) {
        return new MemoryConsolidationService(
                mock(JdbcTemplate.class), client, mock(MemoryStatementMatcher.class),
                mock(MemoryEvidenceService.class), mock(CanonicalMemoryReviewService.class), true, 90);
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
