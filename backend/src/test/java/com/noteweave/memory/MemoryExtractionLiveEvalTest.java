package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.chat.LiveLlmEvalSupport;
import com.noteweave.task.TaskService;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 记忆/反馈评测集的 live 部分：同一份 fixture，改由真实模型自己判断，走真实抽取方法。
 * <p>
 * 只在同时满足两个条件时运行：
 * <ul>
 *   <li>环境变量 {@code DEEPSEEK_API_KEY} 非空；</li>
 *   <li>系统属性 {@code -Dnoteweave.live.llm=true}。</li>
 * </ul>
 * 接口地址、模型名、密钥与超时的读取顺序见 {@link LiveLlmEvalSupport}：默认
 * {@code https://api.deepseek.com/v1/chat/completions} + {@code deepseek-chat}，
 * 可用 {@code NOTEWEAVE_LLM_ENDPOINT}/{@code NOTEWEAVE_LLM_MODEL}/{@code NOTEWEAVE_LLM_API_KEY}
 * 或同名 {@code -Dnoteweave.live.llm.*} 覆盖；密钥永远不会被打印。
 * <p>
 * 判定看四件事：抽到几条偏好、explicit、feedback，以及 fixture 里给了 {@code expected.judgment}
 * 的用例还要看反馈判断的 feedback 与 polarity（「谢谢，就这样」应判成正面反馈，
 * 「变量名太长」「这段代码有 bug」都不该判成对上一条回答的反馈）；
 * 偏好条数允许 fixture 给出 {@code preference_count_max}（例如「变量名太长」记不记都行，
 * 但不能记成负面反馈）。写不写跟进反馈由 {@link MemoryFeedbackAspectJudge} 的独立判定决定。
 * slot/polarity 的归类差异不影响 live 判定，那部分由离线评测按理想输出精确断言。
 * 模型调用失败（{@code LLM_FAILED}）或不可用（{@code LLM_UNAVAILABLE}）一律记该例失败，
 * 不能用「本轮没抽到」冒充正确答案。
 * 分数只报告，不因得分低而失败；设置 {@code -Dnoteweave.live.llm.min-score=0.8} 后才按及格线断言。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "noteweave.live.llm", matches = "true")
class MemoryExtractionLiveEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURE = "/memory/memory-extraction-eval-v1.json";

    @Test
    void liveModelProducesTheExpectedPreferencesAndFeedback() throws Exception {
        ChatLlmClient client = LiveLlmEvalSupport.client();
        assertThat(client.isEnabled()).as("live 评测的模型客户端必须可用").isTrue();
        MemoryConversationExtractionService service = service(client);

        System.out.println("[memory-extraction live] " + LiveLlmEvalSupport.describeTarget());
        JsonNode root = fixture();
        int total = 0;
        int passed = 0;
        for (JsonNode evalCase : root.path("cases")) {
            String caseId = evalCase.path("id").asText();
            total++;
            MemoryConversationExtractionService.Extraction extraction = service.extractPreferences(
                    evalCase.path("message").asText(), previousAnswer(evalCase));
            List<MemoryConversationExtractionService.Preference> preferences = extraction.preferences();
            int count = preferences.size();
            boolean anyFeedback = preferences.stream()
                    .anyMatch(MemoryConversationExtractionService.Preference::feedback);
            boolean allExplicit = preferences.stream()
                    .allMatch(MemoryConversationExtractionService.Preference::explicit);

            JsonNode expected = evalCase.path("expected");
            boolean modelAnswered = !MemoryConversationExtractionService.METHOD_LLM_FAILED.equals(extraction.method())
                    && !MemoryConversationExtractionService.METHOD_LLM_UNAVAILABLE.equals(extraction.method());
            int minCount = expected.path("preference_count").asInt();
            int maxCount = expected.has("preference_count_max")
                    ? expected.path("preference_count_max").asInt() : minCount;
            boolean pass = modelAnswered
                    && count >= minCount && count <= maxCount;
            if (expected.has("all_explicit")) {
                pass &= allExplicit == expected.path("all_explicit").asBoolean();
            }
            if (expected.has("any_feedback")) {
                pass &= anyFeedback == expected.path("any_feedback").asBoolean();
            }
            MemoryFeedbackAspectJudge.Judgment judgment = null;
            JsonNode expectedJudgment = expected.path("judgment");
            if (!expectedJudgment.isMissingNode() && !expectedJudgment.isNull()) {
                judgment = MemoryFeedbackAspectJudge.judge(evalCase.path("message").asText(), client, MAPPER);
                // 模型没给出结论只能是模型侧的问题，不能算这个用例答对
                pass &= !judgment.notJudged();
                pass &= judgment.feedback() == expectedJudgment.path("feedback").asBoolean();
                pass &= judgment.polarity().equals(expectedJudgment.path("polarity").asText());
                if (expectedJudgment.has("aspect")) {
                    pass &= judgment.aspect().equals(expectedJudgment.path("aspect").asText());
                }
                if (expectedJudgment.has("aspect_not")) {
                    pass &= !judgment.aspect().equals(expectedJudgment.path("aspect_not").asText());
                }
            }
            if (pass) {
                passed++;
            }
            // 只打用例 id 与判定结果：不含消息正文、提示词与密钥。
            System.out.println((pass ? "PASS " : "FAIL ") + caseId + " count=" + count
                    + " method=" + extraction.method()
                    + " explicit=" + allExplicit + " feedback=" + anyFeedback
                    + " judged=" + (judgment == null ? "-" : judgment.feedback() + "/" + judgment.polarity()));
        }

        double score = total == 0 ? 0.0 : (double) passed / total;
        System.out.println(LiveLlmEvalSupport.scoreLine("memory-extraction live eval", passed, total));
        double minScore = LiveLlmEvalSupport.minScoreOrNaN();
        if (!Double.isNaN(minScore)) {
            assertThat(score).as("memory-extraction live 得分低于阈值").isGreaterThanOrEqualTo(minScore);
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
        try (InputStream input = MemoryExtractionLiveEvalTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(input).as("找不到评测集 " + FIXTURE).isNotNull();
            return MAPPER.readTree(input);
        }
    }
}
