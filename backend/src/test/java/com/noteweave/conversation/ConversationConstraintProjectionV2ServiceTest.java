package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.conversation.constraint.ConstraintIds;
import com.noteweave.conversation.constraint.ConstraintOpMerger;
import com.noteweave.conversation.constraint.LlmConstraintOpsExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 约束投影接线的单元测试：真实抽取器 + 真实合并器，只有模型客户端是脚本化的 stub，
 * 因此不发任何网络请求，也不依赖 Spring 上下文或数据库。
 * <p>
 * 覆盖三条验收语义：
 * <ul>
 *   <li>模型成功：ops 经合并器落到快照；</li>
 *   <li>模型失败：只重试一次，仍失败则该消息本轮不产出行，已有约束保持不变；</li>
 *   <li>{@code NOOP}：不新增、不撤回，任何约束都不变。</li>
 * </ul>
 */
class ConversationConstraintProjectionV2ServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MS = 2_000L;
    private static final double MIN_CONFIDENCE = 0.55;

    private final ConstraintOpMerger merger = new ConstraintOpMerger(MIN_CONFIDENCE);
    private final List<TopicSegmenterV2.Segment> segments = List.of(
            new TopicSegmenterV2.Segment("segment-1", "topic-1", 1, 9, "CONFIDENT", "TEST",
                    TopicSegmenterV2.RULE_VERSION));

    @Test
    void modelOpsAreAppliedThroughTheMerger() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"table","value":"format.table","scope":"conversation","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"ADD","slot":"conclusion","scope":"conversation","confidence":0.8}]
                """);

        List<ConversationConstraintProjectorV2.Constraint> rows =
                service(client).project(messages(), segments);

        assertThat(rows).hasSize(2);
        ConversationConstraintProjectorV2.Constraint table = row(rows, ConstraintIds.of("m1", "table", 0));
        assertThat(table.kind()).isEqualTo("FORMAT");
        assertThat(table.scope()).isEqualTo("CONVERSATION");
        assertThat(table.text()).isEqualTo("format.table");
        assertThat(table.status()).isEqualTo("ACTIVE");
        assertThat(table.invalidAfterSeq()).isNull();
        ConversationConstraintProjectorV2.Constraint conclusion =
                row(rows, ConstraintIds.of("m3", "conclusion", 0));
        assertThat(conclusion.text()).isEqualTo("format.conclusion");
        // 助手消息永远不进模型，也不产出约束。
        assertThat(client.calls).isEqualTo(2);
        assertThat(rows).extracting(ConversationConstraintProjectorV2.Constraint::sourceMessageId)
                .containsOnly("m1", "m3");
    }

    @Test
    void noopLeavesEveryConstraintUnchanged() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"table","scope":"conversation","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"NOOP"}]
                """);

        List<ConversationConstraintProjectorV2.Constraint> rows =
                service(client).project(messages(), segments);

        assertThat(rows).hasSize(1);
        ConversationConstraintProjectorV2.Constraint table = rows.get(0);
        assertThat(table.id()).isEqualTo(ConstraintIds.of("m1", "table", 0));
        assertThat(table.status()).isEqualTo("ACTIVE");
        assertThat(table.invalidAfterSeq()).isNull();
        assertThat(table.supersededBy()).isNull();
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void failedExtractionIsRetriedOnceAndLeavesTheRoundUnchanged() {
        // 第一轮：m1 给出表格要求，m3 明确说没有约束（NOOP）——这是终局结论。
        ScriptedClient noopRound = new ScriptedClient();
        noopRound.outputs.add("""
                [{"op":"ADD","slot":"table","scope":"conversation","confidence":0.9}]
                """);
        noopRound.outputs.add("""
                [{"op":"NOOP"}]
                """);
        List<ConversationConstraintProjectorV2.Constraint> before =
                service(noopRound).project(messages(), segments);
        assertThat(before).hasSize(1);

        // 第二轮：m1 照旧成功，m3 的模型调用连续失败两次。
        ScriptedClient failing = new ScriptedClient();
        failing.outputs.add("""
                [{"op":"ADD","slot":"table","scope":"conversation","confidence":0.9}]
                """);
        failing.outputs.add("THROW");
        failing.outputs.add("THROW");
        List<ConversationConstraintProjectorV2.Constraint> afterFailure =
                service(failing).project(messages(), segments);

        // 只重试一次：m1 一次调用，m3 两次调用。
        assertThat(failing.calls).isEqualTo(3);
        // 本轮与上一轮逐字段相同：没有新增、没有撤回，已有约束的 id/status/scope/文本全都没变。
        assertThat(afterFailure).isEqualTo(before);
        assertThat(afterFailure.get(0).status()).isEqualTo("ACTIVE");
        assertThat(afterFailure.get(0).invalidAfterSeq()).isNull();
        assertThat(afterFailure.get(0).supersededBy()).isNull();
    }

    @Test
    void malformedOutputIsRetriedOnceAndKeepsTheRoundEmpty() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("这次我看没有约束");
        client.outputs.add("{\"op\":\"ADD\"}");

        List<ConversationConstraintProjectorV2.Constraint> rows = service(client)
                .project(List.of(new TopicSegmenterV2.Message("m1", 1, "USER", "以后都用表格")), segments);

        assertThat(rows).isEmpty();
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    void unavailableModelProducesNoConstraints() {
        ScriptedClient client = new ScriptedClient();
        client.enabled = false;

        assertThat(service(client).project(messages(), segments)).isEmpty();
        assertThat(client.calls).isZero();
    }

    @Test
    void versionMarksTheModelOnlyPath() {
        assertThat(service(new ScriptedClient()).version()).isEqualTo("model-only:constraint-ops-v1");
    }

    private ConversationConstraintProjectionV2Service service(ScriptedClient client) {
        return new ConversationConstraintProjectionV2Service(
                new LlmConstraintOpsExtractor(client, MAPPER, TIMEOUT_MS, MIN_CONFIDENCE), merger);
    }

    /** m2 是助手消息：投影必须跳过它。 */
    private static List<TopicSegmenterV2.Message> messages() {
        return List.of(
                new TopicSegmenterV2.Message("m1", 1, "USER", "以后都用表格"),
                new TopicSegmenterV2.Message("m2", 2, "ASSISTANT", "好的，以后用表格"),
                new TopicSegmenterV2.Message("m3", 3, "USER", "表格不要了"));
    }

    private static ConversationConstraintProjectorV2.Constraint row(
            List<ConversationConstraintProjectorV2.Constraint> rows, String id) {
        return rows.stream().filter(row -> id.equals(row.id())).findFirst()
                .orElseThrow(() -> new AssertionError("missing constraint " + id));
    }

    /**
     * 脚本化的模型客户端：按序返回预置输出，{@code "THROW"} 模拟调用失败。
     * 与 {@code LlmConstraintOpsExtractorTest} 里的同名 stub 同一套风格，不共享状态。
     */
    private static final class ScriptedClient implements ChatLlmClient {

        private static final String THROW = "THROW";

        final List<String> outputs = new ArrayList<>();
        int calls;
        boolean enabled = true;

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            calls++;
            if (outputs.isEmpty()) {
                return "";
            }
            String output = outputs.remove(0);
            if (THROW.equals(output)) {
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
