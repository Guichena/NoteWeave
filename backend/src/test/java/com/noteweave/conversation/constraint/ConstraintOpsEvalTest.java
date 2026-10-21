package com.noteweave.conversation.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.conversation.ConversationConstraintProjectionV2Service;
import com.noteweave.conversation.ConversationConstraintProjectorV2;
import com.noteweave.conversation.TopicSegmenterV2;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 约束抽取评测集的离线部分：stub 模型回放 fixture 里的「理想 ops」，真实合并器与真实接线负责落行。
 * <p>
 * 断言的是整条链路的结果，而不是某段规则：稳定 id（{@code messageId:slot:opIndex}）、落库 kind、
 * scope、极性（{@code REQUIRE}/{@code FORBID}）、ACTIVE/REVOKED、撤回的 {@code invalid_after_seq}
 * 与 {@code superseded_by}。因此 fixture 同时是
 * 「模型应该怎么答」的说明和「接线有没有接对」的回归。不发任何网络请求，也不依赖 Spring 上下文。
 * <p>
 * {@code targetId} 里的 {@code $active:<slot>} 由测试桩按提示词里给出的 ACTIVE 列表解析，
 * 模拟模型只能引用已有约束 id 的约束；live 评测里这一步由真实模型完成。
 */
class ConstraintOpsEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long TIMEOUT_MS = 2_000L;
    private static final double MIN_CONFIDENCE = 0.55;
    private static final String FIXTURE = "/conversation/constraint-ops-eval-v1.json";
    private static final String ACTIVE_REF_PREFIX = "$active:";
    private static final Pattern ACTIVE_REF = Pattern.compile("\\$active:([a-z][a-z-]*)");

    @Test
    void idealModelOpsProduceTheExpectedRows() throws Exception {
        JsonNode root = fixture();
        List<String> caseIds = new ArrayList<>();
        for (JsonNode evalCase : root.path("cases")) {
            String caseId = evalCase.path("id").asText();
            caseIds.add(caseId);
            List<TopicSegmenterV2.Message> messages = messages(evalCase);
            FixtureOpsClient client = new FixtureOpsClient(idealOps(evalCase));

            List<ConversationConstraintProjectorV2.Constraint> rows = service(client)
                    .project(messages, segments(evalCase));

            // 行数必须与 fixture 一致：多一行说明模型 ops 被重复落库，少一行说明某个 op 被静默丢掉。
            assertThat(rows).as(caseId).hasSize(evalCase.path("expected").size());
            // 理想输出一次就该通过校验：每条 USER 消息恰好一次调用，没有触发重试。
            assertThat(client.calls).as(caseId + ": 每条 USER 消息只调用一次模型").isEqualTo(userMessageCount(evalCase));
            for (JsonNode expected : evalCase.path("expected")) {
                assertRow(rows, expected, caseId);
            }
        }
        // 点名的评测语义必须都在集子里，避免以后删用例时悄悄丢掉覆盖。
        assertThat(caseIds).contains(
                "dangling-this-not-constraint",
                "chinese-then-english-then-keep-english",
                "do-not-forget-english",
                "english-then-chinese-replaces",
                "not-the-point-keep-english",
                "this-time-chinese-only",
                "remember-short-answers",
                "brief-then-detailed",
                "no-table-use-bullets",
                "each-time-conclusion-this-time-not-exception");
    }

    /** fixture 自身也要合法：理想 ops 必须能过生产校验器，{@code $active:} 只能引用此前已生效的槽位。 */
    @Test
    void fixtureIdealOpsAreSchemaValidAndReferenceLiveConstraints() throws Exception {
        for (JsonNode evalCase : fixture().path("cases")) {
            String caseId = evalCase.path("id").asText();
            Map<String, Integer> seqOf = seqByMessage(evalCase);
            for (JsonNode entry : evalCase.path("ideal_ops")) {
                String messageId = entry.path("message_id").asText();
                assertThat(seqOf).as(caseId + ": ideal_ops 指向不存在的消息 " + messageId).containsKey(messageId);
                assertThat(roleOf(evalCase, messageId)).as(caseId + ": 只有 USER 消息才抽取").isEqualTo("USER");

                JsonNode ops = entry.path("ops");
                assertThat(ops.isArray()).as(caseId + ": ops 必须是数组").isTrue();
                assertThat(ops.size()).as(caseId + ": 每条消息 1.." + ConstraintOpSchema.MAX_OPS + " 个 op")
                        .isBetween(1, ConstraintOpSchema.MAX_OPS);

                ConstraintOpSchema.ParseResult parsed =
                        ConstraintOpSchema.parse(MAPPER, MAPPER.writeValueAsString(ops));
                assertThat(parsed.ok())
                        .as(caseId + ": 理想 ops 不合法: " + parsed.failure())
                        .isTrue();

                for (JsonNode op : ops) {
                    String targetId = op.path("targetId").asText("");
                    if (!targetId.startsWith(ACTIVE_REF_PREFIX)) {
                        continue;
                    }
                    String slot = targetId.substring(ACTIVE_REF_PREFIX.length());
                    assertThat(ConstraintSlot.fromWire(slot)).as(caseId + ": 占位符槽位未知 " + slot).isNotNull();
                    assertThat(slotIsLiveAt(evalCase, slot, seqOf.get(messageId)))
                            .as(caseId + ": " + targetId + " 在该消息之前没有生效行")
                            .isTrue();
                }
            }
        }
    }

    /** 用公开构造器接线：门槛值与 fixture 一致，内部构造的合并器与显式 new 的等价。 */
    private ConversationConstraintProjectionV2Service service(ChatLlmClient client) {
        return new ConversationConstraintProjectionV2Service(
                new LlmConstraintOpsExtractor(client, MAPPER, TIMEOUT_MS, MIN_CONFIDENCE),
                MIN_CONFIDENCE);
    }

    private static void assertRow(List<ConversationConstraintProjectorV2.Constraint> rows,
                                  JsonNode expected, String caseId) {
        String messageId = expected.path("source_message_id").asText();
        String slotWire = expected.path("slot").asText();
        int opIndex = expected.path("op_index").asInt();
        ConstraintSlot slot = ConstraintSlot.fromWire(slotWire);
        assertThat(slot).as(caseId + ": 未知槽位 " + slotWire).isNotNull();

        String id = ConstraintIds.of(messageId, slotWire, opIndex);
        ConversationConstraintProjectorV2.Constraint row = rows.stream()
                .filter(candidate -> id.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(caseId + ": 缺少约束行 " + id));

        assertThat(row.sourceMessageId()).as(caseId).isEqualTo(messageId);
        assertThat(row.kind()).as(caseId).isEqualTo(slot.dbKind());
        assertThat(row.scope()).as(caseId).isEqualTo(expected.path("scope").asText());
        assertThat(row.status()).as(caseId).isEqualTo(expected.path("status").asText());
        // 极性是落库列：禁止项必须落成 FORBID，不能只靠正文里的「不要使用表格」。
        assertThat(row.polarity()).as(caseId)
                .isEqualTo(ConstraintPolarity.fromWire(expected.path("polarity").asText("require")));
        assertThat(row.text()).as(caseId).isEqualTo(expected.path("value").asText());
        if (expected.hasNonNull("invalid_after_seq")) {
            assertThat(row.invalidAfterSeq()).as(caseId)
                    .isEqualTo(expected.get("invalid_after_seq").asInt());
        } else {
            assertThat(row.invalidAfterSeq()).as(caseId).isNull();
        }
        if (expected.hasNonNull("superseded_by")) {
            assertThat(row.supersededBy()).as(caseId)
                    .isEqualTo(idOf(expected.get("superseded_by").asText()));
        } else {
            assertThat(row.supersededBy()).as(caseId).isNull();
        }
    }

    /** fixture 里的行引用写成 {@code messageId:slot:opIndex}，解析成与生产一致的稳定 id。 */
    private static String idOf(String reference) {
        int index = reference.lastIndexOf(':');
        int slotIndex = reference.lastIndexOf(':', index - 1);
        assertThat(slotIndex).as("行引用格式应为 messageId:slot:opIndex，实际 " + reference).isPositive();
        return ConstraintIds.of(reference.substring(0, slotIndex),
                reference.substring(slotIndex + 1, index),
                Integer.parseInt(reference.substring(index + 1)));
    }

    private static boolean slotIsLiveAt(JsonNode evalCase, String slot, int seq) {
        Map<String, Integer> seqOf = seqByMessage(evalCase);
        for (JsonNode row : evalCase.path("expected")) {
            if (!slot.equals(row.path("slot").asText())) {
                continue;
            }
            Integer from = seqOf.get(row.path("source_message_id").asText());
            if (from == null) {
                continue;
            }
            int until = row.hasNonNull("invalid_after_seq")
                    ? row.get("invalid_after_seq").asInt() : Integer.MAX_VALUE;
            // 撤回发生在本条消息的 ops 应用之后，所以 seq == invalid_after_seq 时旧行对模型仍然可见。
            if (from < seq && seq <= until) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> seqByMessage(JsonNode evalCase) {
        Map<String, Integer> seqOf = new LinkedHashMap<>();
        for (JsonNode message : evalCase.path("messages")) {
            seqOf.put(message.path("id").asText(), message.path("seq").asInt());
        }
        return seqOf;
    }

    private static String roleOf(JsonNode evalCase, String messageId) {
        for (JsonNode message : evalCase.path("messages")) {
            if (messageId.equals(message.path("id").asText())) {
                return message.path("role").asText();
            }
        }
        return "";
    }

    private static int userMessageCount(JsonNode evalCase) {
        int count = 0;
        for (JsonNode message : evalCase.path("messages")) {
            if ("USER".equals(message.path("role").asText())) {
                count++;
            }
        }
        return count;
    }

    private static List<String> idealOps(JsonNode evalCase) throws Exception {
        Map<String, JsonNode> opsByMessage = new LinkedHashMap<>();
        for (JsonNode entry : evalCase.path("ideal_ops")) {
            opsByMessage.put(entry.path("message_id").asText(), entry.path("ops"));
        }
        List<String> outputs = new ArrayList<>();
        for (JsonNode message : evalCase.path("messages")) {
            if (!"USER".equals(message.path("role").asText())) {
                continue;
            }
            String messageId = message.path("id").asText();
            JsonNode ops = opsByMessage.remove(messageId);
            if (ops == null) {
                throw new AssertionError("fixture 缺少消息 " + messageId + " 的理想 ops");
            }
            outputs.add(MAPPER.writeValueAsString(ops));
        }
        if (!opsByMessage.isEmpty()) {
            throw new AssertionError("fixture 给了非 USER 消息的 ops: " + opsByMessage.keySet());
        }
        return outputs;
    }

    private static List<TopicSegmenterV2.Message> messages(JsonNode evalCase) {
        List<TopicSegmenterV2.Message> messages = new ArrayList<>();
        for (JsonNode message : evalCase.path("messages")) {
            messages.add(new TopicSegmenterV2.Message(
                    message.path("id").asText(),
                    message.path("seq").asInt(),
                    message.path("role").asText(),
                    message.path("text").asText()));
        }
        return messages;
    }

    /** 相邻同话题的消息合成一段；段状态固定 CONFIDENT，只用来给 CURRENT_TOPIC 划隔离边界。 */
    private static List<TopicSegmenterV2.Segment> segments(JsonNode evalCase) {
        List<TopicSegmenterV2.Segment> segments = new ArrayList<>();
        String topic = null;
        int start = 0;
        int end = 0;
        for (JsonNode message : evalCase.path("messages")) {
            String next = message.path("topic").asText();
            int seq = message.path("seq").asInt();
            if (topic == null) {
                topic = next;
                start = seq;
            } else if (!topic.equals(next)) {
                segments.add(segment(segments.size(), topic, start, end));
                topic = next;
                start = seq;
            }
            end = seq;
        }
        if (topic != null) {
            segments.add(segment(segments.size(), topic, start, end));
        }
        return segments;
    }

    private static TopicSegmenterV2.Segment segment(int index, String topic, int start, int end) {
        return new TopicSegmenterV2.Segment("segment-" + (index + 1), topic, start, end,
                "CONFIDENT", "TEST", TopicSegmenterV2.RULE_VERSION);
    }

    private static JsonNode fixture() throws Exception {
        try (InputStream input = ConstraintOpsEvalTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(input).as("找不到评测集 " + FIXTURE).isNotNull();
            return MAPPER.readTree(input);
        }
    }

    /**
     * 按顺序回放 fixture 的理想 ops，并把 {@code $active:<slot>} 换成提示词里该槽位的 ACTIVE id。
     * 不共享状态、不发网络请求。
     */
    private static final class FixtureOpsClient implements ChatLlmClient {

        private final List<String> outputs;
        int calls;

        FixtureOpsClient(List<String> outputs) {
            this.outputs = new ArrayList<>(outputs);
        }

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            calls++;
            if (outputs.isEmpty()) {
                throw new IllegalStateException("没有更多预置 ops");
            }
            return resolveActiveRefs(userPrompt, outputs.remove(0));
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    private static String resolveActiveRefs(String userPrompt, String opsJson) {
        if (!opsJson.contains(ACTIVE_REF_PREFIX)) {
            return opsJson;
        }
        Map<String, String> ids = activeIds(userPrompt);
        Matcher matcher = ACTIVE_REF.matcher(opsJson);
        StringBuilder resolved = new StringBuilder(opsJson.length());
        while (matcher.find()) {
            String slot = matcher.group(1);
            String id = ids.get(slot);
            if (id == null) {
                throw new IllegalStateException("提示词里没有槽位 " + slot + " 的 ACTIVE 约束");
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(id));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    /** 提示词里 ACTIVE 列表独占一行，紧跟在「当前 ACTIVE 约束…」之后。 */
    private static Map<String, String> activeIds(String userPrompt) {
        int marker = userPrompt.indexOf("当前 ACTIVE 约束");
        int lineStart = marker < 0 ? -1 : userPrompt.indexOf('\n', marker);
        int lineEnd = lineStart < 0 ? -1 : userPrompt.indexOf('\n', lineStart + 1);
        if (lineStart < 0 || lineEnd < 0) {
            throw new IllegalStateException("提示词里找不到 ACTIVE 约束列表");
        }
        try {
            JsonNode active = MAPPER.readTree(userPrompt.substring(lineStart + 1, lineEnd));
            Map<String, String> ids = new LinkedHashMap<>();
            for (JsonNode item : active) {
                ids.putIfAbsent(item.path("slot").asText(), item.path("id").asText());
            }
            return ids;
        } catch (Exception ex) {
            throw new IllegalStateException("ACTIVE 约束列表不是合法 JSON", ex);
        }
    }
}
