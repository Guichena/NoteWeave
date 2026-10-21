package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.task.TaskService;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 分类器答 NO 或模型抽取返回空数组时，回落到规则抽取，method 仍标规则路径的 RULE。
 * 不依赖 Spring 上下文，只替换 LLM 客户端。
 */
class MemoryConversationExtractionFallbackTest {

    private static final String PREFERENCE = "以后回答先给结论再展开";
    /** 分类器输出上限，与抽取输出的上限不同，用来区分两次模型调用。 */
    private static final int CLASSIFIER_TOKENS = 10;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void classifierNoStillRunsRuleExtraction() {
        MemoryConversationExtractionService.Extraction extraction = service("no", "[]")
                .extractPreferences(PREFERENCE, null);

        assertThat(extraction.method()).isEqualTo("RULE");
        assertThat(extraction.preferences())
                .extracting(MemoryConversationExtractionService.Preference::statement)
                .containsExactly(PREFERENCE);
        assertThat(extraction.preferences()).allMatch(MemoryConversationExtractionService.Preference::explicit);
    }

    @Test
    void emptyModelArrayFallsBackToRuleExtraction() {
        MemoryConversationExtractionService.Extraction extraction = service("yes", "[]")
                .extractPreferences(PREFERENCE, null);

        assertThat(extraction.method()).isEqualTo("RULE");
        assertThat(extraction.preferences())
                .extracting(MemoryConversationExtractionService.Preference::statement)
                .containsExactly(PREFERENCE);
    }

    @Test
    void modelArrayWithoutStatementsFallsBackToRuleExtraction() {
        MemoryConversationExtractionService.Extraction extraction = service("yes", "[{\"statement\":\"  \"}]")
                .extractPreferences("太长了。别用英文。", null);

        assertThat(extraction.method()).isEqualTo("RULE");
        assertThat(extraction.preferences())
                .extracting(MemoryConversationExtractionService.Preference::statement)
                .containsExactly("太长了", "别用英文");
        assertThat(extraction.preferences()).allMatch(MemoryConversationExtractionService.Preference::feedback);
    }

    @Test
    void classifierNoWithoutRuleCueStaysEmpty() {
        MemoryConversationExtractionService.Extraction extraction = service("no", "[]")
                .extractPreferences("解释一下缓存一致性", null);

        assertThat(extraction.method()).isEqualTo("RULE");
        assertThat(extraction.preferences()).isEmpty();
    }

    @Test
    void modelPreferencesKeepTheLlmMethod() {
        MemoryConversationExtractionService.Extraction extraction = service("yes",
                "[{\"statement\":\"回答不要过长\",\"category\":\"style\",\"explicit\":true}]")
                .extractPreferences(PREFERENCE, null);

        // 模型抽出内容时不掺入规则结果
        assertThat(extraction.method()).isEqualTo("LLM");
        assertThat(extraction.preferences())
                .extracting(MemoryConversationExtractionService.Preference::statement)
                .containsExactly("回答不要过长");
    }

    @Test
    void nonJsonModelOutputStillFallsBackToRuleExtraction() {
        MemoryConversationExtractionService.Extraction extraction = service("yes", "没有找到偏好")
                .extractPreferences(PREFERENCE, null);

        assertThat(extraction.method()).isEqualTo("RULE");
        assertThat(extraction.preferences())
                .extracting(MemoryConversationExtractionService.Preference::statement)
                .containsExactly(PREFERENCE);
    }

    private MemoryConversationExtractionService service(String classifierAnswer, String extractionOutput) {
        ChatLlmClient llm = new ChatLlmClient() {
            @Override
            public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                     Consumer<String> onToken) {
                // 分类器与抽取共用这个方法，按输出上限区分：10 个 token 的那次是分类器
                return maximumOutputTokens == CLASSIFIER_TOKENS ? classifierAnswer : extractionOutput;
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
        return new MemoryConversationExtractionService(mock(JdbcTemplate.class), objectMapper,
                mock(TaskService.class), null, null, null, null, llm, null, null,
                mock(PlatformTransactionManager.class), "noteweave.memory.extraction", true);
    }
}
