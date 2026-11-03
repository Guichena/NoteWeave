package com.noteweave.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * F2-04：从用户消息里抽取会话前提。
 *
 * <p>只接受用户自己陈述的前提；转述他人的说法（third-party）不写入。模型输出必须是
 * 结构合法的 JSON，解析失败或未知取值整轮丢弃，不猜测、不阻断回答。抽取结果按来源
 * 消息与抽取版本幂等，由 {@link PremiseResolver} 折叠。</p>
 */
@Component
public class LlmConversationPremiseExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmConversationPremiseExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String EXTRACTION_VERSION = "premise-extraction-v1";
    private static final int MAX_OPERATIONS = 6;
    private static final int MAX_OUTPUT_TOKENS = 400;

    private static final String SYSTEM_PROMPT = """
            你是会话前提抽取器。只抽取用户在这一条消息里自己陈述、可被后续追问引用的前提。
            只输出一个 JSON 对象，不要 Markdown：
            {"premises":[{"action":"assert|correct|retract",
              "relation":"reference|assumption|correction|third-party",
              "subject_key":"主体.属性","value":"陈述取值",
              "scope":"this-turn|current-topic|conversation",
              "topic_id":"","target_premise_id":"","confidence":0.0}]}
            规则：
            - 只抽用户自己的陈述；“别人说/据说/如果”属于 third-party 或 assumption，不写成事实。
            - subject_key 用稳定的“主体.属性”，不要带句子。
            - 更正同一主体同一属性用 correct，并给出新取值；撤回用 retract，value 为空。
            - 没有可靠撤回目标时不要输出 retract。
            - 最多 6 条；没有前提返回 {"premises":[]}。
            """;

    private final ChatLlmClient llmClient;

    public LlmConversationPremiseExtractor(ChatLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public List<PremiseOp> extract(
            String workspaceId,
            String conversationId,
            String sourceMessageId,
            int sourceSeq,
            String content
    ) {
        if (llmClient == null || !llmClient.isEnabled()
                || content == null || content.isBlank()
                || sourceMessageId == null || sourceMessageId.isBlank() || sourceSeq < 1) {
            return List.of();
        }
        String output;
        try {
            output = llmClient.streamChatInSession(
                    ChatLlmClient.routingSessionId("conversation.premise.extract", workspaceId, sourceMessageId),
                    SYSTEM_PROMPT,
                    "用户消息：\n" + bounded(content),
                    MAX_OUTPUT_TOKENS,
                    token -> { });
        } catch (RuntimeException ex) {
            log.warn("会话前提抽取调用失败: {}", ex.getMessage());
            return List.of();
        }
        try {
            return parse(output, sourceMessageId, sourceSeq);
        } catch (RuntimeException ex) {
            log.warn("会话前提抽取输出不合规: {}", ex.getMessage());
            return List.of();
        }
    }

    static List<PremiseOp> parse(String output, String sourceMessageId, int sourceSeq) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        String json = output.strip();
        if (json.startsWith("```")) {
            int firstLine = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) {
                json = json.substring(firstLine + 1, lastFence).strip();
            }
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception ex) {
            return List.of();
        }
        JsonNode premises = root.path("premises");
        if (!premises.isArray() || premises.size() > MAX_OPERATIONS) {
            return List.of();
        }
        List<PremiseOp> operations = new ArrayList<>();
        int index = 0;
        for (JsonNode node : premises) {
            PremiseOp.Action action = PremiseOp.Action.fromWire(node.path("action").asText(""));
            PremiseOp.Relation relation = PremiseOp.Relation.fromWire(node.path("relation").asText(""));
            ConversationPremise.PremiseScope scope = scope(node.path("scope").asText("")).orElse(null);
            String subjectKey = node.path("subject_key").asText("").strip();
            String value = node.path("value").asText("").strip();
            if (action == null || relation == null || scope == null || subjectKey.isBlank()) {
                continue;
            }
            if (relation == PremiseOp.Relation.THIRD_PARTY_STATEMENT) {
                continue;
            }
            double confidence = node.path("confidence").asDouble(PremiseOp.DEFAULT_CONFIDENCE);
            if (!(confidence >= 0.0d && confidence <= 1.0d)) {
                confidence = PremiseOp.DEFAULT_CONFIDENCE;
            }
            if (action.writesValue() && value.isBlank()) {
                continue;
            }
            try {
                operations.add(new PremiseOp(
                        action, relation, subjectKey, value, scope,
                        node.path("topic_id").asText(""),
                        node.path("target_premise_id").asText(""),
                        sourceMessageId, sourceSeq, EXTRACTION_VERSION, confidence, index++));
            } catch (IllegalArgumentException ex) {
                // 单条不合规不影响其他条目
            }
        }
        return List.copyOf(operations);
    }

    private static java.util.Optional<ConversationPremise.PremiseScope> scope(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ConversationPremise.PremiseScope scope : ConversationPremise.PremiseScope.values()) {
            if (scope.wire().equals(normalized)) {
                return java.util.Optional.of(scope);
            }
        }
        return java.util.Optional.empty();
    }

    private static String bounded(String content) {
        return content.length() <= 4_000 ? content : content.substring(0, 4_000);
    }
}
