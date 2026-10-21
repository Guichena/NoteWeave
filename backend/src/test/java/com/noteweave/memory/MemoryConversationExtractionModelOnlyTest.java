package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.task.TaskService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 抽取只由模型决定：没有关键词兜底，模型失败重试一次后本轮不抽取并把任务标成可重试。
 * 不依赖 Spring 上下文，只替换 LLM 客户端。
 */
class MemoryConversationExtractionModelOnlyTest {

    private static final String PREFERENCE = "以后回答先给结论再展开";
    private static final String PREFERENCE_JSON =
            "[{\"statement\":\"" + PREFERENCE + "\",\"category\":\"structure\",\"explicit\":true}]";
    /** 长期措辞最密集的说法：以前会命中 DURABLE_CUE，现在只能由模型判断。 */
    private static final String DURABLE_CUE_MESSAGE = "记住：以后每次回答都不要使用表情符号";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void anyMessageWithinTheQueueLimitIsQueued() {
        assertThat(MemoryConversationExtractionService.shouldExtract("以后回答都用中文")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("解释一下缓存一致性")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("太长了")).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("  ")).isFalse();
        assertThat(MemoryConversationExtractionService.shouldExtract("a".repeat(4_001))).isFalse();
    }

    @Test
    void classifierNoSkipsExtractionWithoutRuleFallback() {
        ScriptedClient client = new ScriptedClient("no", PREFERENCE_JSON);

        MemoryConversationExtractionService.Extraction extraction =
                service(client).extractPreferences(DURABLE_CUE_MESSAGE, null);

        assertThat(extraction.method()).isEqualTo(MemoryConversationExtractionService.METHOD_CLASSIFIED_NO);
        assertThat(extraction.preferences()).isEmpty();
        assertThat(extraction.retryable()).isFalse();
        // 分类器判 NO 之后不再调用抽取，也不再用关键词补一遍
        assertThat(client.extractionCalls).isZero();
    }

    @Test
    void emptyModelArrayIsFinalEvenForDurableWording() {
        ScriptedClient client = new ScriptedClient("yes", MemoryExtractionModelStub.NO_PREFERENCE);

        MemoryConversationExtractionService.Extraction extraction =
                service(client).extractPreferences(DURABLE_CUE_MESSAGE, null);

        assertThat(extraction.method()).isEqualTo(MemoryConversationExtractionService.METHOD_LLM);
        assertThat(extraction.preferences()).isEmpty();
        assertThat(extraction.retryable()).isFalse();
        // 空数组是终局回答，不再重试
        assertThat(client.extractionCalls).isEqualTo(1);
    }

    @Test
    void nonJsonOutputIsRetriedOnceThenGivesUp() {
        ScriptedClient client = new ScriptedClient("yes", "没有找到偏好", "还是没有找到");

        MemoryConversationExtractionService.Extraction extraction =
                service(client).extractPreferences(DURABLE_CUE_MESSAGE, null);

        assertThat(extraction.method()).isEqualTo(MemoryConversationExtractionService.METHOD_LLM_FAILED);
        assertThat(extraction.preferences()).isEmpty();
        assertThat(extraction.retryable()).isTrue();
        assertThat(client.extractionCalls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("上次输出不是合法的 JSON 数组");
    }

    @Test
    void retryAfterInvalidOutputStillUsesTheModelResult() {
        ScriptedClient client = new ScriptedClient("yes", "没有找到偏好", PREFERENCE_JSON);

        MemoryConversationExtractionService.Extraction extraction =
                service(client).extractPreferences(DURABLE_CUE_MESSAGE, null);

        assertThat(extraction.method()).isEqualTo(MemoryConversationExtractionService.METHOD_LLM);
        assertThat(extraction.preferences())
                .extracting(MemoryConversationExtractionService.Preference::statement)
                .containsExactly(PREFERENCE);
        assertThat(extraction.retryable()).isFalse();
        assertThat(client.extractionCalls).isEqualTo(2);
    }

    @Test
    void callFailureIsRetriedOnceThenGivesUp() {
        ScriptedClient client = new ScriptedClient("yes", ScriptedClient.THROW, ScriptedClient.THROW);

        MemoryConversationExtractionService.Extraction extraction =
                service(client).extractPreferences(DURABLE_CUE_MESSAGE, null);

        assertThat(extraction.method()).isEqualTo(MemoryConversationExtractionService.METHOD_LLM_FAILED);
        assertThat(extraction.preferences()).isEmpty();
        assertThat(extraction.retryable()).isTrue();
        assertThat(client.extractionCalls).isEqualTo(2);
    }

    @Test
    void modelPreferencesAreUsedAsIsWithoutRuleAdditions() {
        ScriptedClient client = new ScriptedClient("yes", PREFERENCE_JSON);

        MemoryConversationExtractionService.Extraction extraction =
                service(client).extractPreferences(DURABLE_CUE_MESSAGE, null);

        assertThat(extraction.method()).isEqualTo(MemoryConversationExtractionService.METHOD_LLM);
        assertThat(extraction.preferences()).singleElement().satisfies(preference -> {
            assertThat(preference.statement()).isEqualTo(PREFERENCE);
            assertThat(preference.category()).isEqualTo("structure");
            assertThat(preference.explicit()).isTrue();
            assertThat(preference.feedback()).isFalse();
            assertThat(preference.assignment().slot()).isEqualTo("format.structure");
        });
        assertThat(extraction.retryable()).isFalse();
    }

    @Test
    void unavailableClientSkipsExtractionWithoutRuleFallback() {
        ScriptedClient client = new ScriptedClient("yes", PREFERENCE_JSON);
        client.enabled = false;

        MemoryConversationExtractionService.Extraction extraction =
                service(client).extractPreferences(DURABLE_CUE_MESSAGE, null);

        assertThat(extraction.method()).isEqualTo(MemoryConversationExtractionService.METHOD_LLM_UNAVAILABLE);
        assertThat(extraction.preferences()).isEmpty();
        // 模型没配置属于常态，不算“待重抽”
        assertThat(extraction.retryable()).isFalse();
        assertThat(client.extractionCalls).isZero();
    }

    @Test
    void unavailableClassifierSkipsExtraction() {
        ScriptedClient client = new ScriptedClient("不确定", PREFERENCE_JSON);

        MemoryConversationExtractionService.Extraction extraction =
                service(client).extractPreferences(DURABLE_CUE_MESSAGE, null);

        assertThat(extraction.method()).isEqualTo(MemoryConversationExtractionService.METHOD_LLM_UNAVAILABLE);
        assertThat(extraction.preferences()).isEmpty();
        assertThat(client.extractionCalls).isZero();
    }

    @Test
    void feedbackFieldAloneDecidesWhetherTheAnswerWasDisliked() {
        MemoryConversationExtractionService.Extraction complaint =
                new MemoryConversationExtractionService.Extraction(List.of(
                        new MemoryConversationExtractionService.Preference(
                                "太长了", "style", false, null, null, "FORBID", true)), "LLM");
        assertThat(MemoryConversationExtractionService.dissatisfied(complaint)).isTrue();

        // 显式长期偏好即使谈长度也不是对上一轮的不满
        MemoryConversationExtractionService.Extraction instruction =
                new MemoryConversationExtractionService.Extraction(List.of(
                        new MemoryConversationExtractionService.Preference("回答不要过长", "style", true)), "LLM");
        assertThat(MemoryConversationExtractionService.dissatisfied(instruction)).isFalse();

        // 模型什么都没抽出来时不再按关键词猜负面反馈
        assertThat(MemoryConversationExtractionService.dissatisfied(new MemoryConversationExtractionService.Extraction(
                List.of(), "LLM"))).isFalse();
        assertThat(MemoryConversationExtractionService.dissatisfied(new MemoryConversationExtractionService.Extraction(
                List.of(), MemoryConversationExtractionService.METHOD_CLASSIFIED_NO))).isFalse();
    }

    @Test
    void modelSlotDecidesTheSlotAndThePromptListsTheEnum() {
        ScriptedClient client = new ScriptedClient("yes",
                "[{\"statement\":\"以后回答都用中文\",\"category\":\"style\",\"explicit\":true,"
                        + "\"slot\":\"format.table\",\"value\":\"表格\",\"polarity\":\"REQUIRE\"}]");

        MemoryConversationExtractionService.Extraction extraction =
                service(client).extractPreferences("以后回答都用中文", null);

        // 槽位以模型输出为准，即使语句里有「中文」这类旧词表也不再改写
        assertThat(extraction.preferences()).singleElement()
                .satisfies(preference -> assertThat(preference.assignment().slot()).isEqualTo("format.table"));
        // 提示词里给出完整枚举，模型不需要猜槽位名
        assertThat(client.systemPrompts).singleElement().satisfies(prompt ->
                assertThat(MemorySlots.ALLOWED_SLOTS).allSatisfy(slot -> assertThat(prompt).contains(slot)));
    }

    @Test
    void invalidModelSlotFallsBackToTheModelCategory() {
        ScriptedClient missing = new ScriptedClient("yes",
                "[{\"statement\":\"以后回答都用中文\",\"category\":\"style\",\"explicit\":true}]");
        assertThat(service(missing).extractPreferences("以后回答都用中文", null).preferences())
                .singleElement()
                .satisfies(preference -> assertThat(preference.assignment().slot()).isEqualTo("answer.style"));

        ScriptedClient invalid = new ScriptedClient("yes",
                "[{\"statement\":\"以后回答先给结论\",\"category\":\"structure\",\"explicit\":true,"
                        + "\"slot\":\"不是槽位\"}]");
        assertThat(service(invalid).extractPreferences("以后回答先给结论", null).preferences())
                .singleElement()
                .satisfies(preference -> assertThat(preference.assignment().slot()).isEqualTo("format.structure"));
    }

    @Test
    void modelFailureLeavesTheTaskRetryableAndSkipsFollowUpFeedback() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService taskService = mock(TaskService.class);
        MemoryApplicationFeedbackService feedback = mock(MemoryApplicationFeedbackService.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(invocation -> ((String) invocation.getArgument(0)).contains("from task where id")
                        ? "RUNNING" : "conversation:c-1");

        MemoryConversationExtractionService.ExtractionResult result = service(jdbc, taskService, feedback)
                .persistExtraction("task-1", "ws-1", "msg-1", "user-1",
                        new MemoryConversationExtractionService.Extraction(List.of(),
                                MemoryConversationExtractionService.METHOD_LLM_FAILED, true));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.method()).isEqualTo(MemoryConversationExtractionService.METHOD_LLM_FAILED);
        verify(taskService).failTask("task-1", MemoryConversationExtractionService.RETRY_PHASE,
                "记忆抽取模型不可用，本轮未抽取", MemoryConversationExtractionService.RETRY_PHASE, true);
        verify(taskService, never()).completeTask(anyString(), anyString(), anyString(), anyString());
        // 判不出这一轮是否不满，就不写跟进反馈，避免把沉默记成接受
        verifyNoInteractions(feedback);
    }

    private MemoryConversationExtractionService service(ChatLlmClient client) {
        return service(mock(JdbcTemplate.class), mock(TaskService.class), null, client);
    }

    private MemoryConversationExtractionService service(JdbcTemplate jdbc, TaskService taskService,
                                                        MemoryApplicationFeedbackService feedback) {
        return service(jdbc, taskService, feedback, new ScriptedClient("yes"));
    }

    private MemoryConversationExtractionService service(JdbcTemplate jdbc, TaskService taskService,
                                                        MemoryApplicationFeedbackService feedback,
                                                        ChatLlmClient client) {
        MemoryConversationExtractionService service = new MemoryConversationExtractionService(jdbc, objectMapper,
                taskService, null, null, null, null, client, null, null,
                mock(PlatformTransactionManager.class), "noteweave.memory.extraction", true);
        if (feedback != null) {
            service.setApplicationFeedback(feedback);
        }
        return service;
    }

    /** 按顺序返回抽取输出的模型桩：分类器固定答给定标签，抽取调用可以被 THROW 打断。 */
    private static final class ScriptedClient implements ChatLlmClient {

        static final String THROW = "THROW";

        private final String classifierAnswer;
        private final Deque<String> extractionOutputs = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();
        private final List<String> systemPrompts = new ArrayList<>();
        private int extractionCalls;
        private boolean enabled = true;

        ScriptedClient(String classifierAnswer, String... extractionOutputs) {
            this.classifierAnswer = classifierAnswer;
            this.extractionOutputs.addAll(List.of(extractionOutputs));
        }

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            if (maximumOutputTokens == MemoryExtractionModelStub.CLASSIFIER_TOKENS) {
                return classifierAnswer;
            }
            extractionCalls++;
            prompts.add(userPrompt);
            systemPrompts.add(systemPrompt);
            String output = extractionOutputs.isEmpty()
                    ? MemoryExtractionModelStub.NO_PREFERENCE : extractionOutputs.removeFirst();
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
