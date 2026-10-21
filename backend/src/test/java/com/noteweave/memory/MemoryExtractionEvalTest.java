package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.task.TaskService;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 记忆/反馈评测集的离线部分：stub 模型回放 fixture 里的「理想输出」，走真实的抽取方法
 * （分类器 → 抽取 → 解析 → 负面反馈判定），不发网络请求、不依赖 Spring 上下文。
 * <p>
 * 断言的是模型输出被如何理解：抽到几条偏好、explicit 与 feedback 字段、以及链路据此判定的
 * {@code dissatisfied}（决定是否写「对上一轮回答不满」的跟进反馈）。这三个字段都只由模型决定，
 * 没有关键词正则兜底，所以 fixture 就是行为契约。
 */
class MemoryExtractionEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURE = "/memory/memory-extraction-eval-v1.json";

    @Test
    void idealModelOutputsProduceTheExpectedPreferencesAndFeedback() throws Exception {
        JsonNode root = fixture();
        List<String> caseIds = new ArrayList<>();
        for (JsonNode evalCase : root.path("cases")) {
            String caseId = evalCase.path("id").asText();
            caseIds.add(caseId);
            JsonNode expected = evalCase.path("expected");
            FixtureClient client = new FixtureClient(MAPPER.writeValueAsString(evalCase.path("ideal_output")));

            MemoryConversationExtractionService.Extraction extraction =
                    service(client).extractPreferences(evalCase.path("message").asText(), previousAnswer(evalCase));

            assertThat(extraction.method()).as(caseId).isEqualTo(MemoryConversationExtractionService.METHOD_LLM);
            assertThat(extraction.retryable()).as(caseId + ": 合法 JSON 不该被标成待重抽").isFalse();
            assertThat(client.extractionCalls).as(caseId + ": 理想输出一次通过，不触发重试").isEqualTo(1);
            assertPreferences(caseId, extraction, expected);
        }
        // 点名用例必须在集子里：记忆/反馈的三条判定各自对应一个明确的期望。
        assertThat(caseIds).contains(
                "remember-short-answers-is-a-preference-not-feedback",
                "explicit-memory-question-is-not-a-memory",
                "variable-name-too-long-is-not-user-feedback");
    }

    /** fixture 自身也要说得通：条数与理想输出一致，每条都有 statement，最多 3 条。 */
    @Test
    void fixtureIdealOutputsMatchTheirDeclaredCounts() throws Exception {
        for (JsonNode evalCase : fixture().path("cases")) {
            String caseId = evalCase.path("id").asText();
            JsonNode ideal = evalCase.path("ideal_output");
            assertThat(ideal.isArray()).as(caseId + ": ideal_output 必须是数组").isTrue();
            assertThat(ideal.size()).as(caseId + ": 抽取上限是 3 条").isBetween(0, 3);
            assertThat(ideal.size()).as(caseId + ": 期望条数与理想输出不一致")
                    .isEqualTo(evalCase.path("expected").path("preference_count").asInt());
            JsonNode expected = evalCase.path("expected");
            if (expected.has("preference_count_max")) {
                assertThat(expected.path("preference_count_max").asInt())
                        .as(caseId + ": live 上限不能小于离线精确条数")
                        .isGreaterThanOrEqualTo(expected.path("preference_count").asInt());
            }
            for (JsonNode preference : ideal) {
                assertThat(preference.path("statement").asText()).as(caseId + ": statement 不能为空").isNotBlank();
            }
        }
    }

    private static void assertPreferences(String caseId,
                                          MemoryConversationExtractionService.Extraction extraction,
                                          JsonNode expected) {
        List<MemoryConversationExtractionService.Preference> preferences = extraction.preferences();
        assertThat(preferences).as(caseId).hasSize(expected.path("preference_count").asInt());

        if (expected.has("all_explicit")) {
            boolean allExplicit = expected.path("all_explicit").asBoolean();
            assertThat(preferences).as(caseId + ": explicit 只由模型字段决定")
                    .allMatch(preference -> preference.explicit() == allExplicit);
        }
        if (expected.has("any_feedback")) {
            boolean anyFeedback = preferences.stream()
                    .anyMatch(MemoryConversationExtractionService.Preference::feedback);
            assertThat(anyFeedback).as(caseId + ": feedback 只由模型字段决定")
                    .isEqualTo(expected.path("any_feedback").asBoolean());
        }
        // dissatisfied 是链路真正用来决定「写不写跟进反馈」的判定，必须与 feedback 字段一致。
        assertThat(MemoryConversationExtractionService.dissatisfied(extraction))
                .as(caseId + ": 负面反馈判定")
                .isEqualTo(expected.path("dissatisfied").asBoolean());

        if (!preferences.isEmpty() && expected.has("slot")) {
            assertThat(preferences.get(0).assignment().slot()).as(caseId)
                    .isEqualTo(expected.path("slot").asText());
        }
        if (!preferences.isEmpty() && expected.has("polarity")) {
            assertThat(preferences.get(0).assignment().polarity()).as(caseId)
                    .isEqualTo(expected.path("polarity").asText());
        }
    }

    private static String previousAnswer(JsonNode evalCase) {
        JsonNode previous = evalCase.path("previous_answer");
        return previous.isNull() || previous.isMissingNode() ? null : previous.asText();
    }

    private static MemoryConversationExtractionService service(ChatLlmClient client) {
        return new MemoryConversationExtractionService(
                mock(JdbcTemplate.class), MAPPER, mock(TaskService.class),
                null, null, null, null, client, null, null,
                mock(PlatformTransactionManager.class), "noteweave.memory.extraction", true);
    }

    private static JsonNode fixture() throws Exception {
        try (InputStream input = MemoryExtractionEvalTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(input).as("找不到评测集 " + FIXTURE).isNotNull();
            return MAPPER.readTree(input);
        }
    }

    /** 分类器固定答 yes，抽取调用返回 fixture 的理想输出；不共享状态、不发网络请求。 */
    private static final class FixtureClient implements ChatLlmClient {

        private final String extractionOutput;
        int extractionCalls;

        FixtureClient(String extractionOutput) {
            this.extractionOutput = extractionOutput;
        }

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            if (maximumOutputTokens == MemoryExtractionModelStub.CLASSIFIER_TOKENS) {
                return "yes";
            }
            extractionCalls++;
            return extractionOutput;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}
