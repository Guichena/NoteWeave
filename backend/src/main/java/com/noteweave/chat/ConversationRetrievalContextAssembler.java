package com.noteweave.chat;

import com.noteweave.conversation.ConversationContextProjectionService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Builds the retrieval-facing context for context-dependent conversation questions. */
@Component
class ConversationRetrievalContextAssembler {

    boolean requiresHistory(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim().toLowerCase();
        return normalized.length() <= 6 || containsFollowUpHint(normalized);
    }

    Context empty(String currentQuestion) {
        String normalized = currentQuestion == null ? "" : currentQuestion.trim();
        return new Context(
                normalized,
                normalized,
                false,
                0,
                "",
                "",
                new ConversationContextProjectionService.Projection(List.of(), List.of())
        );
    }

    Context assemble(
            String currentUserMessageId,
            String currentQuestion,
            ConversationContextProjectionService.CompilationProjection projection,
            ConversationContextProjectionService.Projection inputProjection
    ) {
        String trimmedQuestion = currentQuestion == null ? "" : currentQuestion.trim();
        List<ConversationHistoryMessage> history = projection.rawMessages().stream()
                .filter(message -> !message.messageId().equals(currentUserMessageId))
                .map(message -> new ConversationHistoryMessage(message.messageSeq(), message.role(), message.content()))
                .toList();
        List<ConversationTurn> turns = buildConversationTurns(history);
        if (turns.isEmpty()) {
            return empty(trimmedQuestion);
        }
        List<ConversationTurn> workingTurns = buildWorkingTurns(trimmedQuestion, turns);
        if (workingTurns.isEmpty()) {
            return empty(trimmedQuestion);
        }
        String topicAnchor = buildTopicAnchor(trimmedQuestion, workingTurns);
        String topicSummary = projection.summaryText().isBlank()
                ? buildTopicSummary(topicAnchor, workingTurns, turns)
                : projection.summaryText();
        StringBuilder retrievalQuestion = new StringBuilder();
        retrievalQuestion.append("当前问题：").append(trimmedQuestion);
        if (!topicAnchor.isBlank()) {
            retrievalQuestion.append("\n主题锚点：").append(topicAnchor);
        }
        retrievalQuestion.append("\n连续对话窗口：");
        for (ConversationTurn turn : workingTurns) {
            retrievalQuestion.append("\n- 用户：").append(trim(turn.userQuestion(), 120));
            if (!turn.assistantAnswerSummary().isBlank()) {
                retrievalQuestion.append("\n  助手摘要：").append(trim(turn.assistantAnswerSummary(), 180));
            }
        }
        if (!topicSummary.isBlank()) {
            retrievalQuestion.append("\n前序主题摘要：").append(topicSummary);
        }
        return new Context(
                trimmedQuestion,
                retrievalQuestion.toString(),
                true,
                workingTurns.size(),
                topicAnchor,
                topicSummary,
                inputProjection
        );
    }

    String classifyQuestion(String content) {
        String value = content == null ? "" : content.toLowerCase();
        if (value.contains("比较") || value.contains("对比") || value.contains("区别") || value.contains("compare")) {
            return "comparison";
        }
        if (value.contains("总结") || value.contains("概括") || value.contains("summary")) {
            return "summary";
        }
        if (value.contains("来源") || value.contains("引用") || value.contains("citation") || value.contains("source")) {
            return "source_lookup";
        }
        if (value.contains("为什么") || value.contains("原因") || value.contains("推理") || value.contains("why")) {
            return "reasoning";
        }
        return "definition";
    }

    private List<ConversationTurn> buildConversationTurns(List<ConversationHistoryMessage> history) {
        List<ConversationTurn> turns = new ArrayList<>();
        String pendingUserQuestion = null;
        int pendingUserSeq = 0;
        for (ConversationHistoryMessage message : history) {
            if ("USER".equals(message.role())) {
                pendingUserQuestion = message.content();
                pendingUserSeq = message.messageSeq();
                continue;
            }
            if ("ASSISTANT".equals(message.role()) && pendingUserQuestion != null && !pendingUserQuestion.isBlank()) {
                turns.add(new ConversationTurn(
                        pendingUserQuestion,
                        stripMarkdownForContext(message.content()),
                        pendingUserSeq
                ));
                pendingUserQuestion = null;
                pendingUserSeq = 0;
            }
        }
        return turns;
    }

    private List<ConversationTurn> buildWorkingTurns(String currentQuestion, List<ConversationTurn> turns) {
        List<ConversationTurn> workingTurns = new ArrayList<>();
        String currentAnchorCandidate = normalizeTopicPhrase(currentQuestion);
        for (int i = turns.size() - 1; i >= 0; i--) {
            ConversationTurn turn = turns.get(i);
            if (workingTurns.isEmpty()) {
                workingTurns.add(0, turn);
                continue;
            }
            if (workingTurns.size() >= 3) {
                break;
            }
            if (belongsToCurrentTopic(currentQuestion, currentAnchorCandidate, turn.userQuestion(), workingTurns)) {
                workingTurns.add(0, turn);
                continue;
            }
            break;
        }
        return workingTurns;
    }

    private boolean belongsToCurrentTopic(
            String currentQuestion,
            String currentAnchorCandidate,
            String candidateQuestion,
            List<ConversationTurn> workingTurns
    ) {
        if (candidateQuestion == null || candidateQuestion.isBlank()) {
            return false;
        }
        if (isFollowUpHeavyQuestion(currentQuestion)) {
            if (!currentAnchorCandidate.isBlank() && shareTopicTerms(currentAnchorCandidate, candidateQuestion)) {
                return true;
            }
            for (ConversationTurn workingTurn : workingTurns) {
                if (shareTopicTerms(workingTurn.userQuestion(), candidateQuestion)) {
                    return true;
                }
            }
            return workingTurns.size() == 1;
        }
        return shareTopicTerms(currentQuestion, candidateQuestion);
    }

    private String buildTopicAnchor(String currentQuestion, List<ConversationTurn> workingTurns) {
        String bestAnchor = normalizeTopicPhrase(currentQuestion);
        int bestScore = scoreTopicPhrase(bestAnchor);
        for (int i = workingTurns.size() - 1; i >= 0; i--) {
            String candidate = normalizeTopicPhrase(workingTurns.get(i).userQuestion());
            int score = scoreTopicPhrase(candidate);
            if (score > bestScore) {
                bestAnchor = candidate;
                bestScore = score;
            }
        }
        return bestAnchor;
    }

    private int scoreTopicPhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return 0;
        }
        int score = Math.min(phrase.length(), 60);
        if (containsFollowUpHint(phrase)) {
            score -= 12;
        }
        score += extractTopicTerms(phrase).size() * 8;
        return score;
    }

    private String buildTopicSummary(String topicAnchor, List<ConversationTurn> workingTurns, List<ConversationTurn> allTurns) {
        if (workingTurns.isEmpty() || allTurns.size() <= workingTurns.size()) {
            return "";
        }
        int firstWorkingSeq = workingTurns.get(0).messageSeq();
        List<String> summaries = new ArrayList<>();
        for (ConversationTurn turn : allTurns) {
            if (turn.messageSeq() >= firstWorkingSeq) {
                continue;
            }
            if (!topicAnchor.isBlank() && !shareTopicTerms(topicAnchor, turn.userQuestion())) {
                continue;
            }
            StringBuilder summary = new StringBuilder();
            summary.append("用户问“").append(trim(stripMarkdownForContext(turn.userQuestion()), 36)).append("”");
            if (!turn.assistantAnswerSummary().isBlank()) {
                summary.append("，回答聚焦“").append(trim(turn.assistantAnswerSummary(), 48)).append("”");
            }
            summaries.add(summary.toString());
            if (summaries.size() >= 2) {
                break;
            }
        }
        return String.join("；", summaries);
    }

    private boolean shareTopicTerms(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String normalizedLeft = normalizeTopicPhrase(left);
        String normalizedRight = normalizeTopicPhrase(right);
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) {
            return false;
        }
        if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
            return true;
        }
        Set<String> leftTerms = extractTopicTerms(normalizedLeft);
        Set<String> rightTerms = extractTopicTerms(normalizedRight);
        for (String term : leftTerms) {
            if (rightTerms.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> extractTopicTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String normalized = text.toLowerCase()
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        if (normalized.isBlank()) {
            return terms;
        }
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank() || isNoiseToken(token)) {
                continue;
            }
            if (token.length() >= 2 || token.codePointCount(0, token.length()) >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private boolean isNoiseToken(String token) {
        return switch (token) {
            case "继续", "展开", "补充", "这个", "这个呢", "这个问题", "这些", "那个", "它", "刚才", "上面",
                    "前者", "后者", "第二点", "第三点", "继续说", "继续讲", "再说", "接着", "what", "why",
                    "how", "please" -> true;
            default -> false;
        };
    }

    private String normalizeTopicPhrase(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String normalized = stripMarkdownForContext(question)
                .replaceAll("继续|展开|补充|接着|再说|刚才|上面|前者|后者|这个问题|这个呢|这个|这些|那个|它", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return trim(normalized, 80);
    }

    private boolean isFollowUpHeavyQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim().toLowerCase();
        return normalized.length() <= 12 || containsFollowUpHint(normalized);
    }

    private boolean containsFollowUpHint(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim().toLowerCase();
        return normalized.contains("继续")
                || normalized.contains("接着")
                || normalized.contains("展开")
                || normalized.contains("再说")
                || normalized.contains("补充")
                || normalized.contains("上一轮")
                || normalized.contains("上面")
                || normalized.contains("刚才")
                || normalized.contains("前者")
                || normalized.contains("后者")
                || normalized.contains("这个")
                || normalized.contains("这个呢")
                || normalized.contains("这个问题")
                || normalized.contains("这些")
                || normalized.contains("那个")
                || normalized.contains("它")
                || normalized.contains("第二点")
                || normalized.contains("第三点");
    }

    private String stripMarkdownForContext(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content
                .replace("\r", "")
                .replaceAll("(?m)^##\\s+", "")
                .replaceAll("(?m)^-\\s+", "")
                .replace("`", "")
                .trim();
        return normalized.replace("\n", " ");
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "...";
    }

    record Context(
            String currentQuestion,
            String retrievalQuestion,
            boolean contextApplied,
            int windowTurnCount,
            String topicAnchor,
            String topicSummary,
            ConversationContextProjectionService.Projection inputProjection
    ) {
    }

    private record ConversationHistoryMessage(int messageSeq, String role, String content) {
    }

    private record ConversationTurn(String userQuestion, String assistantAnswerSummary, int messageSeq) {
    }
}
