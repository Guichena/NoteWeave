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
 * 关键词/正则规则删除后，原先挂在规则投影器上的合并与 scope 场景（同槽顶替、跨 scope 保留、
 * 跨话题隔离、targetId 更正/撤回、重申幂等、互补槽位共存）改在这里用 stub 模型 ops 回放，
 * 语义仍由真实 {@link ConstraintOpMerger} 判定，不依赖 Spring 上下文或数据库。
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

    @Test
    void laterValueInTheSameTopicSupersedesTheEarlierOne() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"language","value":"英文","scope":"current-topic","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"ADD","slot":"language","value":"中文","scope":"current-topic","confidence":0.9}]
                """);

        List<ConversationConstraintProjectorV2.Constraint> rows =
                service(client).project(messages("这份报告用英文。", "用中文。"), segments);

        ConversationConstraintProjectorV2.Constraint english = row(rows, ConstraintIds.of("m1", "language", 0));
        ConversationConstraintProjectorV2.Constraint chinese = row(rows, ConstraintIds.of("m3", "language", 0));
        // 同一槽位同一 scope 里的新取值直接顶掉旧取值，不需要用户说出"更正"。
        assertThat(rows).hasSize(2);
        assertThat(english.kind()).isEqualTo("LANGUAGE");
        assertThat(english.status()).isEqualTo("REVOKED");
        assertThat(english.invalidAfterSeq()).isEqualTo(3);
        assertThat(english.supersededBy()).isEqualTo(chinese.id());
        assertThat(chinese.status()).isEqualTo("ACTIVE");
        assertThat(chinese.scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(chinese.text()).isEqualTo("中文");
        assertThat(chinese.invalidAfterSeq()).isNull();
        assertThat(chinese.supersededBy()).isNull();
    }

    @Test
    void conversationWidePreferenceSurvivesATopicScopedValue() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"language","value":"英文","scope":"conversation","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"ADD","slot":"language","value":"中文","scope":"current-topic","confidence":0.9}]
                """);

        List<ConversationConstraintProjectorV2.Constraint> rows =
                service(client).project(messages("以后都用英文。", "这次回答只用中文。"), segments);

        ConversationConstraintProjectorV2.Constraint english = row(rows, ConstraintIds.of("m1", "language", 0));
        ConversationConstraintProjectorV2.Constraint chinese = row(rows, ConstraintIds.of("m3", "language", 0));
        // 本话题的取值只是局部补充：会话级偏好不被撤回，两条并存。
        assertThat(rows).hasSize(2);
        assertThat(english.scope()).isEqualTo("CONVERSATION");
        assertThat(english.status()).isEqualTo("ACTIVE");
        assertThat(english.invalidAfterSeq()).isNull();
        assertThat(chinese.scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(chinese.status()).isEqualTo("ACTIVE");
        assertThat(rows).extracting(ConversationConstraintProjectorV2.Constraint::supersededBy)
                .containsOnlyNulls();
    }

    @Test
    void complementarySlotsStayActiveTogetherAcrossLengthDimensions() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"conclusion","scope":"conversation","confidence":0.9},
                 {"op":"ADD","slot":"table","scope":"conversation","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"ADD","slot":"length-limit","value":"300字","scope":"conversation","confidence":0.9},
                 {"op":"ADD","slot":"detail","value":"简短","scope":"conversation","confidence":0.9}]
                """);

        List<ConversationConstraintProjectorV2.Constraint> rows = service(client)
                .project(messages("以后先给结论，用表格对比。", "以后回答不超过300字，并且简短一点。"), segments);

        // 槽位不同就不互斥：结论/表格/字数上限/详略四个维度可以同时生效。
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(ConversationConstraintProjectorV2.Constraint::id)
                .containsExactly(
                        ConstraintIds.of("m1", "conclusion", 0),
                        ConstraintIds.of("m1", "table", 1),
                        ConstraintIds.of("m3", "length-limit", 0),
                        ConstraintIds.of("m3", "detail", 1));
        assertThat(rows).extracting(ConversationConstraintProjectorV2.Constraint::kind)
                .containsOnly("FORMAT");
        assertThat(rows).extracting(ConversationConstraintProjectorV2.Constraint::scope)
                .containsOnly("CONVERSATION");
        assertThat(rows).extracting(ConversationConstraintProjectorV2.Constraint::status)
                .containsOnly("ACTIVE");
        assertThat(rows).extracting(ConversationConstraintProjectorV2.Constraint::supersededBy)
                .containsOnlyNulls();
        assertThat(row(rows, ConstraintIds.of("m3", "detail", 1)).text()).isEqualTo("简短");
    }

    @Test
    void newValueOfTheSameSlotRevokesTheOldOneWithoutTouchingOtherSlots() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"length-limit","value":"300字","scope":"conversation","confidence":0.9},
                 {"op":"ADD","slot":"detail","value":"简短","scope":"conversation","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"ADD","slot":"detail","value":"详细","scope":"conversation","confidence":0.9}]
                """);

        List<ConversationConstraintProjectorV2.Constraint> rows = service(client)
                .project(messages("以后回答不超过300字，并且简短一点。", "以后回答详细一点。"), segments);

        ConversationConstraintProjectorV2.Constraint cap = row(rows, ConstraintIds.of("m1", "length-limit", 0));
        ConversationConstraintProjectorV2.Constraint brief = row(rows, ConstraintIds.of("m1", "detail", 1));
        ConversationConstraintProjectorV2.Constraint detailed = row(rows, ConstraintIds.of("m3", "detail", 0));
        // 详略维度里的新取向顶掉旧取向；字数上限是另一个槽位，不受影响。
        assertThat(brief.status()).isEqualTo("REVOKED");
        assertThat(brief.invalidAfterSeq()).isEqualTo(3);
        assertThat(brief.supersededBy()).isEqualTo(detailed.id());
        assertThat(detailed.status()).isEqualTo("ACTIVE");
        assertThat(detailed.text()).isEqualTo("详细");
        assertThat(cap.status()).isEqualTo("ACTIVE");
        assertThat(cap.invalidAfterSeq()).isNull();
    }

    @Test
    void revokeByTargetIdRetiresOnlyThatRowAndThePromptListsActiveIds() {
        String tableId = ConstraintIds.of("m1", "table", 0);
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"table","scope":"conversation","confidence":0.9},
                 {"op":"ADD","slot":"citation","scope":"conversation","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"REVOKE","targetId":"%s","confidence":0.9}]
                """.formatted(tableId));

        List<ConversationConstraintProjectorV2.Constraint> rows =
                service(client).project(messages("以后用表格，并标注来源。", "表格不要了。"), segments);

        // 撤回按模型给出的 targetId 命中，只作废这一行，不新增行、不牵连引用槽位。
        assertThat(rows).hasSize(2);
        ConversationConstraintProjectorV2.Constraint table = row(rows, tableId);
        assertThat(table.status()).isEqualTo("REVOKED");
        assertThat(table.invalidAfterSeq()).isEqualTo(3);
        assertThat(table.supersededBy()).isNull();
        assertThat(row(rows, ConstraintIds.of("m1", "citation", 1)).status()).isEqualTo("ACTIVE");
        // targetId 只能引用提示词里给出的 ACTIVE 列表，所以第二次调用必须带上这一行的 id。
        assertThat(client.userPrompts.get(1)).contains("\"id\":\"" + tableId + "\"");
    }

    @Test
    void updateWithTargetIdRevokesAcrossScopesAndExplicitThisTurnNarrows() {
        String englishId = ConstraintIds.of("m1", "language", 0);
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"language","value":"英文","scope":"conversation","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"UPDATE","slot":"language","value":"中文","scope":"this-turn",
                  "targetId":"%s","confidence":0.9}]
                """.formatted(englishId));

        List<ConversationConstraintProjectorV2.Constraint> rows =
                service(client).project(messages("以后用英文。", "这次改用中文。"), segments);

        ConversationConstraintProjectorV2.Constraint english = row(rows, englishId);
        ConversationConstraintProjectorV2.Constraint chinese = row(rows, ConstraintIds.of("m3", "language", 0));
        // 显式更正允许跨 scope 撤回；"this-turn" 落库时折成 CURRENT_TOPIC。
        assertThat(english.status()).isEqualTo("REVOKED");
        assertThat(english.invalidAfterSeq()).isEqualTo(3);
        assertThat(english.supersededBy()).isEqualTo(chinese.id());
        assertThat(chinese.status()).isEqualTo("ACTIVE");
        assertThat(chinese.scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(chinese.text()).isEqualTo("中文");
        assertThat(client.userPrompts.get(1)).contains("\"id\":\"" + englishId + "\"");
    }

    @Test
    void constraintsInDifferentTopicsDoNotRevokeEachOther() {
        List<TopicSegmenterV2.Segment> twoTopics = List.of(
                new TopicSegmenterV2.Segment("segment-1", "topic-1", 1, 2, "CONFIDENT", "TEST",
                        TopicSegmenterV2.RULE_VERSION),
                new TopicSegmenterV2.Segment("segment-2", "topic-2", 3, 3, "CONFIDENT", "TEST",
                        TopicSegmenterV2.RULE_VERSION));
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"table","scope":"current-topic","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"ADD","slot":"table","scope":"current-topic","confidence":0.9}]
                """);

        List<ConversationConstraintProjectorV2.Constraint> rows = service(client)
                .project(messages("用表格对比 RDB 和 AOF。", "换个话题，用表格列出过期键删除策略。"), twoTopics);

        // 当前话题的 scope 键带话题 id：换了话题，同槽位同取值的要求各自生效。
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ConversationConstraintProjectorV2.Constraint::scope)
                .containsOnly("CURRENT_TOPIC");
        assertThat(rows).extracting(ConversationConstraintProjectorV2.Constraint::status)
                .containsOnly("ACTIVE");
        assertThat(row(rows, ConstraintIds.of("m3", "table", 0)).invalidAfterSeq()).isNull();
    }

    @Test
    void restatingTheSameValueInTheSameScopeKeepsTheOriginalRow() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("""
                [{"op":"ADD","slot":"language","value":"英文","scope":"conversation","confidence":0.9}]
                """);
        client.outputs.add("""
                [{"op":"ADD","slot":"language","value":"英文","scope":"conversation","confidence":0.9}]
                """);

        List<ConversationConstraintProjectorV2.Constraint> rows =
                service(client).project(messages("以后用英文。", "以后也继续用英文。"), segments);

        // 同值重申是确认：不新建重复行，也不把旧行标成 REVOKED。
        assertThat(rows).singleElement().satisfies(constraint -> {
            assertThat(constraint.id()).isEqualTo(ConstraintIds.of("m1", "language", 0));
            assertThat(constraint.sourceMessageId()).isEqualTo("m1");
            assertThat(constraint.validFromSeq()).isEqualTo(1);
            assertThat(constraint.scope()).isEqualTo("CONVERSATION");
            assertThat(constraint.status()).isEqualTo("ACTIVE");
            assertThat(constraint.invalidAfterSeq()).isNull();
            assertThat(constraint.supersededBy()).isNull();
        });
    }

    @Test
    void reprojectionIsStableAndDoesNotRevokeItself() {
        String ops = """
                [{"op":"ADD","slot":"table","scope":"conversation","confidence":0.9}]
                """;
        ScriptedClient first = new ScriptedClient();
        first.outputs.add(ops);
        ScriptedClient second = new ScriptedClient();
        second.outputs.add(ops);
        List<TopicSegmenterV2.Message> messages =
                List.of(new TopicSegmenterV2.Message("m1", 1, "USER", "以后都用表格。"));

        List<ConversationConstraintProjectorV2.Constraint> once = service(first).project(messages, segments);
        List<ConversationConstraintProjectorV2.Constraint> twice = service(second).project(messages, segments);

        // 同样的输入必须给出同样的 id、状态和撤回指针；孤立约束不会撤回自己。
        assertThat(twice).isEqualTo(once);
        assertThat(once).singleElement().satisfies(constraint -> {
            assertThat(constraint.status()).isEqualTo("ACTIVE");
            assertThat(constraint.invalidAfterSeq()).isNull();
            assertThat(constraint.supersededBy()).isNull();
        });
    }

    @Test
    void emptyModelArrayIsFinalAndNoRuleFallbackRuns() {
        // 这两句在旧规则路径下一定会被抽成约束；模型说没有就真的没有，不存在关键词兜底。
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("[]");
        client.outputs.add("""
                [{"op":"NOOP"}]
                """);

        List<ConversationConstraintProjectorV2.Constraint> rows =
                service(client).project(messages("以后都用表格对比。", "不要用英文，用中文写。"), segments);

        assertThat(rows).isEmpty();
        // 每条消息只调用一次：空数组是终局结论，不触发重试，也没有第二条规则路径。
        assertThat(client.calls).isEqualTo(2);
    }

    private ConversationConstraintProjectionV2Service service(ScriptedClient client) {
        return new ConversationConstraintProjectionV2Service(
                new LlmConstraintOpsExtractor(client, MAPPER, TIMEOUT_MS, MIN_CONFIDENCE), merger);
    }

    /** m2 是助手消息：投影必须跳过它。 */
    private static List<TopicSegmenterV2.Message> messages() {
        return messages("以后都用表格", "表格不要了");
    }

    /** 同一条用户轮次形状（USER / ASSISTANT / USER），只替换两句用户原文。 */
    private static List<TopicSegmenterV2.Message> messages(String first, String third) {
        return List.of(
                new TopicSegmenterV2.Message("m1", 1, "USER", first),
                new TopicSegmenterV2.Message("m2", 2, "ASSISTANT", "好的"),
                new TopicSegmenterV2.Message("m3", 3, "USER", third));
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
        /** 记录每次调用的用户提示词，用来断言 ACTIVE 列表（targetId 的候选）确实交给了模型。 */
        final List<String> userPrompts = new ArrayList<>();
        int calls;
        boolean enabled = true;

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            calls++;
            userPrompts.add(userPrompt);
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
