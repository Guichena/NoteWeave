package com.noteweave.conversation.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.chat.LiveLlmEvalSupport;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 约束抽取评测集的 live 部分：同一份 fixture，改由真实模型自己给 ops，再经真实合并器折叠。
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
 * 判定只看最终 ACTIVE 约束的 {@code slot@scope} 集合，不比 value 原文：同一句话的措辞允许不同，
 * 「抽出了哪个槽位、作用范围是会话还是本话题」才是评测目标。分数只报告，不因得分低而失败；
 * 设置 {@code -Dnoteweave.live.llm.min-score=0.8} 后才按及格线断言。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "noteweave.live.llm", matches = "true")
class ConstraintOpsLiveEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURE = "/conversation/constraint-ops-eval-v1.json";
    /** 单次模型调用的墙钟上限；抽取器失败会再试一次，所以最坏是两倍。 */
    private static final long TIMEOUT_MS = 20_000L;
    private static final double MIN_CONFIDENCE = 0.55;

    @Test
    void liveModelProducesTheExpectedActiveSlots() throws Exception {
        ChatLlmClient client = LiveLlmEvalSupport.client();
        assertThat(client.isEnabled()).as("live 评测的模型客户端必须可用").isTrue();
        ConstraintOpsExtractor extractor = new LlmConstraintOpsExtractor(client, MAPPER, TIMEOUT_MS, MIN_CONFIDENCE);
        ConstraintOpMerger merger = new ConstraintOpMerger(MIN_CONFIDENCE);

        System.out.println("[constraint-ops live] " + LiveLlmEvalSupport.describeTarget());
        JsonNode root = fixture();
        int total = 0;
        int passed = 0;
        for (JsonNode evalCase : root.path("cases")) {
            String caseId = evalCase.path("id").asText();
            total++;
            List<ConstraintOpMerger.Step> steps = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (JsonNode message : evalCase.path("messages")) {
                if (!"USER".equals(message.path("role").asText())) {
                    continue;
                }
                String messageId = message.path("id").asText();
                int seq = message.path("seq").asInt();
                String topicId = message.path("topic").asText();
                ConstraintOpsExtractor.Result result = extractor.extract(new ConstraintOpsExtractor.Request(
                        messageId, seq, message.path("text").asText(), topicId, merger.activeConstraints(steps)));
                if (result instanceof ConstraintOpsExtractor.Result.Ok ok) {
                    steps.add(new ConstraintOpMerger.Step(messageId, seq, topicId, ok.ops()));
                } else {
                    String reason = result instanceof ConstraintOpsExtractor.Result.Unusable unusable
                            ? unusable.reason() : "NO_RESULT";
                    notes.add("seq=" + seq + " unusable=" + reason);
                    steps.add(new ConstraintOpMerger.Step(messageId, seq, topicId, List.of()));
                }
            }
            List<String> expected = expectedActive(evalCase);
            List<String> actual = new ArrayList<>();
            for (ConstraintOpsExtractor.ActiveConstraint active : merger.activeConstraints(steps)) {
                actual.add(slotScope(active.slot() == null ? "" : active.slot().wire(), active.scope()));
            }
            Collections.sort(actual);
            // 模型失败/超时/输出不合规也算这一例失败：不能靠「本轮没抽到」蒙混过关。
            boolean pass = notes.isEmpty() && expected.equals(actual);
            if (pass) {
                passed++;
            }
            // 只打用例 id 与槽位/范围差异：不含消息正文、提示词与密钥。
            System.out.println((pass ? "PASS " : "FAIL ") + caseId
                    + (pass ? "" : " expected=" + expected + " actual=" + actual
                    + (notes.isEmpty() ? "" : " notes=" + notes)));
        }

        double score = total == 0 ? 0.0 : (double) passed / total;
        System.out.println(LiveLlmEvalSupport.scoreLine("constraint-ops live eval", passed, total));
        double minScore = LiveLlmEvalSupport.minScoreOrNaN();
        if (!Double.isNaN(minScore)) {
            assertThat(score).as("constraint-ops live 得分低于阈值").isGreaterThanOrEqualTo(minScore);
        }
    }

    private static List<String> expectedActive(JsonNode evalCase) {
        List<String> expected = new ArrayList<>();
        for (JsonNode row : evalCase.path("expected")) {
            if ("ACTIVE".equals(row.path("status").asText())) {
                expected.add(slotScope(row.path("slot").asText(), row.path("scope").asText()));
            }
        }
        Collections.sort(expected);
        return expected;
    }

    private static String slotScope(String slot, String scope) {
        return slot + "@" + scope;
    }

    private static JsonNode fixture() throws Exception {
        try (InputStream input = ConstraintOpsLiveEvalTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(input).as("找不到评测集 " + FIXTURE).isNotNull();
            return MAPPER.readTree(input);
        }
    }
}
