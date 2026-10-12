package com.noteweave.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.List;
import java.util.Locale;

/** 槽位无法直接判定时，让模型在最相似的几条记忆里选择动作。 */
public final class MemoryConflictJudge {
    static final int ATTEMPTS = 2;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM_PROMPT = """
            你判断一条新的偏好和已有记忆是否冲突。
            只输出一个 JSON 对象，不要解释：
            {"action":"ADD|MERGE|SUPERSEDE|NOOP","target_id":"已有记忆的 id，ADD 时可以留空"}
            ADD 表示可以并存。MERGE 表示应并入某条已有记忆。SUPERSEDE 表示新偏好应替换某条已有记忆。
            NOOP 表示新偏好和某条已有记忆等价，不需要新增。target_id 必须来自给定列表。""";

    private MemoryConflictJudge() {
    }

    public static MemorySlotConflict.Outcome judge(
            ChatLlmClient client,
            String statement,
            MemorySlots.Assignment incoming,
            List<MemoryVectorIndex.Hit> hits
    ) {
        if (hits == null || hits.isEmpty()) {
            return MemorySlotConflict.none();
        }
        MemoryVectorIndex.Hit first = hits.get(0);
        if (client == null || !client.isEnabled()) {
            return MemorySlotConflict.checkNotRun(first.itemId(), first.text(), "MEMORY_CONFLICT_JUDGE_UNAVAILABLE");
        }
        String prompt = prompt(statement, incoming, hits);
        String reason = "MEMORY_CONFLICT_JUDGE_OUTPUT_INVALID";
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String user = attempt == 0 ? prompt : prompt + "\n上次输出无法解析。请只输出一个 JSON 对象。";
            try {
                String output = client.streamChat(SYSTEM_PROMPT, user, 400, token -> { });
                Decision decision = parse(output);
                if (decision != null) {
                    return toOutcome(decision, hits);
                }
                reason = "MEMORY_CONFLICT_JUDGE_OUTPUT_INVALID";
            } catch (RuntimeException ex) {
                reason = "MEMORY_CONFLICT_JUDGE_CALL_FAILED";
            }
        }
        return MemorySlotConflict.checkNotRun(first.itemId(), first.text(), reason);
    }

    private static MemorySlotConflict.Outcome toOutcome(Decision decision, List<MemoryVectorIndex.Hit> hits) {
        MemoryVectorIndex.Hit target = resolve(decision.targetId(), hits);
        return switch (decision.action()) {
            case "ADD" -> MemorySlotConflict.none();
            case "NOOP" -> new MemorySlotConflict.Outcome(
                    "NONE", "EXISTING_EQUIVALENT", "NOOP", target.itemId(), target.text(), List.of());
            case "MERGE", "SUPERSEDE" -> new MemorySlotConflict.Outcome(
                    "SEMANTIC", "CONFLICTING_ACTIVE_MEMORY", decision.action(),
                    target.itemId(), target.text(), List.of());
            default -> MemorySlotConflict.checkNotRun(
                    hits.get(0).itemId(), hits.get(0).text(), "MEMORY_CONFLICT_JUDGE_OUTPUT_INVALID");
        };
    }

    private static MemoryVectorIndex.Hit resolve(String targetId, List<MemoryVectorIndex.Hit> hits) {
        if (targetId != null && !targetId.isBlank()) {
            for (MemoryVectorIndex.Hit hit : hits) {
                if (targetId.equals(hit.itemId())) {
                    return hit;
                }
            }
        }
        return hits.get(0);
    }

    private static String prompt(String statement, MemorySlots.Assignment incoming, List<MemoryVectorIndex.Hit> hits) {
        StringBuilder builder = new StringBuilder();
        builder.append("新偏好：").append(statement == null ? "" : statement).append('\n');
        if (incoming != null) {
            builder.append("槽位：").append(incoming.slot())
                    .append("\n取值：").append(incoming.value())
                    .append("\n极性：").append(incoming.polarity())
                    .append('\n');
        }
        builder.append("已有记忆：\n");
        for (MemoryVectorIndex.Hit hit : hits) {
            builder.append("- id=").append(hit.itemId())
                    .append(" slot=").append(hit.slot())
                    .append(" text=").append(hit.text())
                    .append('\n');
        }
        return builder.toString();
    }

    private static Decision parse(String output) {
        if (output == null) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(output.substring(start, end + 1));
            String action = root.path("action").asText("").trim().toUpperCase(Locale.ROOT);
            if (!action.equals("ADD") && !action.equals("MERGE")
                    && !action.equals("SUPERSEDE") && !action.equals("NOOP")) {
                return null;
            }
            String targetId = root.path("target_id").asText("");
            return new Decision(action, targetId);
        } catch (Exception ex) {
            return null;
        }
    }

    private record Decision(String action, String targetId) {
    }
}
