package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F3-01：R0 研究问题编译。
 *
 * <p>从第二期冻结快照里的最近消息生成独立研究问题与带来源要点，使用独立 purpose
 * {@code research.brief.compile}；输出做结构校验，失败时带错误原因重试一次。两次都失败
 * 就退回有来源的确定性上下文并显式记录降级，不把无法消解的指代当作已经理解。</p>
 */
public final class ResearchBriefQuestionCompiler {

    public static final String PURPOSE = "research.brief.compile";
    public static final String DEGRADATION_UNAVAILABLE = "BRIEF_COMPILER_UNAVAILABLE";
    public static final String DEGRADATION_INVALID = "BRIEF_COMPILER_INVALID";
    public static final String DEGRADATION_ERROR = "BRIEF_COMPILER_ERROR";
    private static final int MAX_QUESTION_CHARS = 1_000;
    private static final int MAX_POINT_CHARS = 300;
    private static final int MAX_POINTS = 8;
    private static final int MAX_OUTPUT_TOKENS = 500;

    private static final String SYSTEM_PROMPT = """
            你是研究问题编译器。只输出一个 JSON 对象，不要 Markdown 代码块，不要解释。
            结构：
            {"research_question":"独立、可检索、保留主语与范围的研究问题",
             "key_points":[{"text":"带来源的要点","source_message_id":"消息 ID"}]}
            规则：
            - 追问里的“它/这个/那点”等指代必须还原成明确对象；无法还原时不要猜。
            - 研究问题保留原问题的范围、日期、对象和输出要求。
            - 每个要点必须来自给出的消息，source_message_id 必须是消息列表里的 ID。
            - 最多 8 个要点，每个要点不超过 300 字。
            """;

    private ResearchBriefQuestionCompiler() {
    }

    public static Result compile(
            ChatLlmClient llmClient,
            ObjectMapper objectMapper,
            String workspaceId,
            String researchRunId,
            String originalQuestion,
            List<Map<String, Object>> messages
    ) {
        List<Map<String, Object>> bounded = messages == null ? List.of() : messages.stream()
                .limit(20)
                .toList();
        Set<String> messageIds = bounded.stream()
                .map(message -> String.valueOf(message.get("message_id")))
                .filter(id -> !id.isBlank() && !"null".equals(id))
                .collect(Collectors.toSet());
        if (llmClient == null || !llmClient.isEnabled()) {
            return fallback(bounded, originalQuestion, DEGRADATION_UNAVAILABLE);
        }
        String userPrompt = userPrompt(originalQuestion, bounded);
        String error = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String output = llmClient.streamChatInSession(
                        ChatLlmClient.routingSessionId(PURPOSE, workspaceId, researchRunId),
                        SYSTEM_PROMPT,
                        userPrompt + (error.isEmpty() ? "" : "\n\n上次输出不合法：" + error + "。请只返回合法 JSON。"),
                        MAX_OUTPUT_TOKENS,
                        token -> { });
                Result parsed = parse(objectMapper, output, messageIds);
                if (parsed != null) {
                    return parsed;
                }
                error = "结构校验未通过";
            } catch (RuntimeException ex) {
                error = "调用失败";
            }
        }
        return fallback(bounded, originalQuestion, DEGRADATION_INVALID);
    }

    private static Result parse(ObjectMapper objectMapper, String output, Set<String> messageIds) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String json = output.strip();
        if (json.startsWith("```")) {
            int firstLine = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) {
                json = json.substring(firstLine + 1, lastFence).strip();
            }
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String question = node.path("research_question").asText("").strip();
            if (question.isBlank() || question.length() > MAX_QUESTION_CHARS) {
                return null;
            }
            JsonNode pointsNode = node.path("key_points");
            if (!pointsNode.isArray() || pointsNode.isEmpty() || pointsNode.size() > MAX_POINTS) {
                return null;
            }
            List<Map<String, Object>> points = new ArrayList<>();
            for (JsonNode point : pointsNode) {
                String text = point.path("text").asText("").strip();
                String sourceMessageId = point.path("source_message_id").asText("").strip();
                if (text.isBlank() || text.length() > MAX_POINT_CHARS
                        || !messageIds.contains(sourceMessageId)) {
                    return null;
                }
                points.add(Map.of("text", text, "source_message_id", sourceMessageId));
            }
            return new Result(question, List.copyOf(points), List.of(), false);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Result fallback(List<Map<String, Object>> messages, String originalQuestion,
                                   String reason) {
        String question = originalQuestion == null ? "" : originalQuestion.strip();
        List<Map<String, Object>> keyPoints = new ArrayList<>();
        int userMessagesSeen = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, Object> message = messages.get(index);
            if (!"USER".equals(String.valueOf(message.get("role")))) {
                continue;
            }
            String content = String.valueOf(message.get("content"));
            if (content.isBlank()) {
                continue;
            }
            if (userMessagesSeen++ == 0) {
                question = content.strip();
                continue;
            }
            String text = content.strip();
            if (text.length() > MAX_POINT_CHARS) {
                text = text.substring(0, MAX_POINT_CHARS);
            }
            keyPoints.add(Map.of(
                    "text", text,
                    "source_message_id", String.valueOf(message.get("message_id"))));
            if (keyPoints.size() >= 3) {
                break;
            }
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            question = question.substring(0, MAX_QUESTION_CHARS);
        }
        return new Result(question, List.copyOf(keyPoints), List.of(reason), true);
    }

    private static String userPrompt(String originalQuestion, List<Map<String, Object>> messages) {
        StringBuilder builder = new StringBuilder("原问题：\n")
                .append(originalQuestion == null ? "" : originalQuestion.strip())
                .append("\n\n冻结上下文消息：\n");
        int budget = 8_000;
        for (Map<String, Object> message : messages) {
            String id = String.valueOf(message.get("message_id"));
            String role = String.valueOf(message.get("role"));
            String content = String.valueOf(message.get("content"));
            String line = "[" + id + "] " + role + ": " + content + "\n";
            if (line.length() > budget) {
                line = line.substring(0, Math.max(0, budget));
            }
            if (line.isEmpty()) {
                break;
            }
            builder.append(line);
            budget -= line.length();
            if (budget <= 0) {
                break;
            }
        }
        return builder.toString();
    }

    public record Result(
            String researchQuestion,
            List<Map<String, Object>> keyPoints,
            List<String> degradationReasons,
            boolean degraded
    ) {
        public Result {
            keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        }
    }
}
