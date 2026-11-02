package com.noteweave.chat;

import com.noteweave.common.BusinessException;
import com.noteweave.context.ContextBudget;
import com.noteweave.context.ContextBudgetPlanner;
import com.noteweave.context.ContextCallSite;
import com.noteweave.context.ContextContract;
import com.noteweave.context.ContextItemForm;
import com.noteweave.context.ContextLayer;
import com.noteweave.context.ContextRefType;
import com.noteweave.context.ContextRenderer;
import com.noteweave.context.ContextView;
import com.noteweave.context.RenderedContext;
import com.noteweave.conversation.ConversationContextProjectionService;
import com.noteweave.conversation.ContextProjectionV2;
import com.noteweave.conversation.ContextV2ShadowSnapshotService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Builds the retrieval-facing context for context-dependent conversation questions. */
@Component
class ConversationRetrievalContextAssembler {

    /** 上下文编译实现版本；选择规则变化时必须递增，契约身份会随之改变。 */
    static final String COMPILER_VERSION = "conversation-context-v2";

    private static final int DEFAULT_MODEL_WINDOW_TOKENS = 8_192;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 2_400;

    private final ContextRenderer renderer = new ContextRenderer();
    private final ContextBudgetPlanner budgetPlanner = new ContextBudgetPlanner();

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
                normalized,
                false,
                0,
                "",
                "",
                new ConversationContextProjectionService.Projection(List.of(), List.of()),
                null,
                "",
                "",
                0,
                Map.of(),
                List.of()
        );
    }

    Context assemble(
            String workspaceId,
            String actorId,
            String conversationId,
            String currentUserMessageId,
            String currentQuestion,
            ConversationContextProjectionService.CompilationProjection projection,
            ConversationContextProjectionService.Projection inputProjection
    ) {
        return assembleInternal(workspaceId, actorId, conversationId, currentUserMessageId,
                currentQuestion, projection, inputProjection,
                DEFAULT_MODEL_WINDOW_TOKENS, DEFAULT_MAX_OUTPUT_TOKENS);
    }

    /** 测试与嵌入调用可显式给出窗口，生产入口使用默认预算。 */
    Context assembleWithBudget(
            String workspaceId,
            String actorId,
            String conversationId,
            String currentUserMessageId,
            String currentQuestion,
            ConversationContextProjectionService.CompilationProjection projection,
            ConversationContextProjectionService.Projection inputProjection,
            int modelWindowTokens,
            int maximumOutputTokens
    ) {
        return assembleInternal(workspaceId, actorId, conversationId, currentUserMessageId,
                currentQuestion, projection, inputProjection, modelWindowTokens, maximumOutputTokens);
    }

    private Context assembleInternal(
            String workspaceId,
            String actorId,
            String conversationId,
            String currentUserMessageId,
            String currentQuestion,
            ConversationContextProjectionService.CompilationProjection projection,
            ConversationContextProjectionService.Projection inputProjection,
            int modelWindowTokens,
            int maximumOutputTokens
    ) {
        String trimmedQuestion = currentQuestion == null ? "" : currentQuestion.trim();
        List<HistoryMessage> history = projection.rawMessages().stream()
                .filter(message -> !message.messageId().equals(currentUserMessageId))
                .map(message -> new HistoryMessage(
                        message.messageId(),
                        message.messageSeq(),
                        message.role(),
                        "ASSISTANT".equals(message.role())
                                ? stripMarkdownForContext(message.content())
                                : message.content()))
                .toList();
        if (history.isEmpty()) {
            return empty(trimmedQuestion);
        }
        List<ConversationTurn> turns = buildConversationTurns(history);
        if (turns.isEmpty()) {
            return empty(trimmedQuestion);
        }
        List<ConversationTurn> workingTurns = buildWorkingTurns(trimmedQuestion, turns);
        String topicAnchor = buildTopicAnchor(trimmedQuestion, workingTurns);
        String topicSummary = projection.summaryText().isBlank()
                ? buildTopicSummary(topicAnchor, workingTurns, turns)
                : projection.summaryText();
        List<SummaryCandidate> summaries = topicSummary.isBlank()
                ? List.of()
                : List.of(new SummaryCandidate("context-summary", 0, 0, topicSummary));
        int currentSeq = projection.rawMessages().stream()
                .filter(message -> message.messageId().equals(currentUserMessageId))
                .mapToInt(message -> message.messageSeq())
                .findFirst().orElse(0);
        Budgeted budget = budget(
                workspaceId, actorId, conversationId, currentSeq,
                modelWindowTokens, maximumOutputTokens,
                trimmedQuestion, currentSeq, currentUserMessageId,
                history, summaries, List.of(), List.of());
        return new Context(
                trimmedQuestion,
                budget.retrievalText(),
                budget.generationText(),
                true,
                workingTurns.size(),
                topicAnchor,
                topicSummary,
                inputProjection,
                null,
                budget.generationContract().identitySha256(),
                budget.generationView().identitySha256(),
                budget.generationView().estimatedTokens(),
                budget.generationRendered().sectionTokens(),
                budget.degradations()
        );
    }

    Context assembleV2(
            String workspaceId,
            String actorId,
            String currentUserMessageId,
            String currentQuestion,
            ContextV2ShadowSnapshotService.FrozenAnswer frozen
    ) {
        ContextProjectionV2 projection = frozen.projection();
        String trimmedQuestion = currentQuestion == null ? "" : currentQuestion.trim();
        if (!trimmedQuestion.equals(projection.currentInput().trim())) {
            throw new IllegalArgumentException("Frozen Context input differs from the current request");
        }
        List<ContextProjectionV2.RawMessage> rawHistory = projection.rawTail().stream()
                .filter(message -> !currentUserMessageId.equals(message.messageId()))
                .toList();
        boolean hasContext = !rawHistory.isEmpty() || !projection.topicSummaries().isEmpty()
                || !projection.constraints().isEmpty();
        if (!hasContext) {
            return empty(trimmedQuestion);
        }
        List<HistoryMessage> history = rawHistory.stream()
                .map(message -> new HistoryMessage(
                        message.messageId(), message.seq(), message.role(), message.text()))
                .toList();
        List<ConversationTurn> turns = buildConversationTurns(history);
        String topicAnchor = turns.isEmpty() ? "" : buildTopicAnchor(trimmedQuestion,
                buildWorkingTurns(trimmedQuestion, turns));
        int windowTurnCount = (int) history.stream()
                .filter(message -> "USER".equals(message.role()))
                .count();
        List<SummaryCandidate> summaries = projection.topicSummaries().stream()
                .filter(summary -> summary.text() != null && !summary.text().isBlank())
                .map(summary -> new SummaryCandidate(
                        summary.revisionId(), summary.startSeq(), summary.endSeq(), summary.text()))
                .toList();
        List<ConstraintCandidate> constraints = projection.constraints().stream()
                .filter(rule -> rule.text() != null && !rule.text().isBlank())
                .map(rule -> new ConstraintCandidate(
                        rule.constraintId(), rule.validFromSeq(), rule.text()))
                .toList();
        int currentSeq = projection.cutoffSeq();
        Budgeted budget = budget(
                workspaceId, actorId, projection.conversationId(), projection.cutoffSeq(),
                DEFAULT_MODEL_WINDOW_TOKENS, DEFAULT_MAX_OUTPUT_TOKENS,
                trimmedQuestion, currentSeq, currentUserMessageId,
                history, summaries, constraints, List.of());
        String topicSummary = summaries.stream()
                .map(SummaryCandidate::text)
                .reduce((first, second) -> first + "\n" + second).orElse("");
        return new Context(
                trimmedQuestion,
                budget.retrievalText(),
                budget.generationText(),
                true,
                windowTurnCount,
                topicAnchor,
                topicSummary,
                new ConversationContextProjectionService.Projection(List.of(), List.of()),
                frozen,
                budget.generationContract().identitySha256(),
                budget.generationView().identitySha256(),
                budget.generationView().estimatedTokens(),
                budget.generationRendered().sectionTokens(),
                budget.degradations()
        );
    }

    /**
     * 统一预算：检索改写与生成提示共用同一份冻结材料，分两次按各自调用点的层顺序和预算选择。
     * 最近原文优先保留；放不下的条目记录剔除原因，不在渲染阶段偷偷截字。
     */
    private Budgeted budget(
            String workspaceId,
            String actorId,
            String conversationId,
            int cutoffSeq,
            int modelWindowTokens,
            int maximumOutputTokens,
            String currentQuestion,
            int currentSeq,
            String currentMessageId,
            List<HistoryMessage> history,
            List<SummaryCandidate> summaries,
            List<ConstraintCandidate> constraints,
            List<PremiseCandidate> premises
    ) {
        ContextContract retrievalContract = contract(ContextCallSite.C2,
                workspaceId, actorId, conversationId, cutoffSeq,
                modelWindowTokens, maximumOutputTokens);
        ContextContract generationContract = contract(ContextCallSite.C6,
                workspaceId, actorId, conversationId, cutoffSeq,
                modelWindowTokens, maximumOutputTokens);
        Selection retrieval = select(retrievalContract, currentQuestion, currentSeq,
                currentMessageId, history, summaries, constraints, premises, true);
        Selection generation = select(generationContract, currentQuestion, currentSeq,
                currentMessageId, history, summaries, constraints, premises, false);
        return new Budgeted(
                operationText(retrieval),
                operationText(generation),
                generationContract,
                generation.view(),
                generation.rendered(),
                generation.degradations()
        );
    }

    private ContextContract contract(
            ContextCallSite callSite,
            String workspaceId,
            String actorId,
            String conversationId,
            int cutoffSeq,
            int modelWindowTokens,
            int maximumOutputTokens
    ) {
        int window = Math.max(1, modelWindowTokens);
        int outputReserve = Math.max(callSite.outputReserveTokens(), Math.max(0, maximumOutputTokens));
        int hardLimit = window - outputReserve - callSite.safetyMarginTokens();
        if (hardLimit < 1) {
            throw new BusinessException("CONTEXT_WINDOW_EXHAUSTED",
                    "模型窗口扣除输出预留后没有可用的上下文预算");
        }
        int target = Math.min(callSite.targetTokens(), hardLimit);
        return ContextContract.create(
                callSite,
                COMPILER_VERSION,
                workspaceId == null || workspaceId.isBlank() ? "workspace" : workspaceId,
                actorId == null || actorId.isBlank() ? "session" : actorId,
                conversationId,
                cutoffSeq,
                Math.max(1, target),
                hardLimit,
                outputReserve,
                callSite.safetyMarginTokens(),
                ContextRenderer.estimatorVersion(),
                ContextContract.FrozenVersions.none());
    }

    private Selection select(
            ContextContract contract,
            String currentQuestion,
            int currentSeq,
            String currentMessageId,
            List<HistoryMessage> history,
            List<SummaryCandidate> summaries,
            List<ConstraintCandidate> constraints,
            List<PremiseCandidate> premises,
            boolean includeCurrentInput
    ) {
        ContextBudgetPlanner.Allocation allocation = budgetPlanner.allocate(
                contract, Map.of(ContextLayer.EVIDENCE, 0));
        List<ContextView.Item> items = new ArrayList<>();
        List<ContextView.Exclusion> exclusions = new ArrayList<>();
        LinkedHashSet<String> degradations = new LinkedHashSet<>();
        int totalTokens = 0;
        for (ContextLayer layer : contract.ordering()) {
            int layerCap = allocation.targetOf(layer);
            int layerUsed = 0;
            List<ContextView.Item> layerCandidates = candidatesFor(
                    layer, currentQuestion, currentSeq, currentMessageId,
                    history, summaries, constraints, premises, includeCurrentInput);
            if (layerCandidates.isEmpty()) {
                continue;
            }
            if (layer == ContextLayer.RAW_MESSAGE) {
                // 最近一轮优先原文：预算不够时先保留最新消息，再回溯更早的原文
                List<ContextView.Item> kept = new ArrayList<>();
                int keptTokens = 0;
                for (int index = layerCandidates.size() - 1; index >= 0; index--) {
                    ContextView.Item item = layerCandidates.get(index);
                    if (keptTokens + item.tokens() <= layerCap
                            && totalTokens + keptTokens + item.tokens() <= contract.hardLimitTokens()) {
                        kept.add(item);
                        keptTokens += item.tokens();
                        continue;
                    }
                    exclusions.add(new ContextView.Exclusion(item.refType(), item.refId(), layer,
                            "CONTEXT_RAW_MESSAGE_OVER_BUDGET", "本轮预算不足以保留该条原文"));
                }
                kept.sort(java.util.Comparator.comparingInt(ContextView.Item::startSeq));
                items.addAll(kept);
                totalTokens += keptTokens;
                if (kept.size() < layerCandidates.size()) {
                    degradations.add("CONTEXT_RAW_MESSAGE_DROPPED");
                }
                continue;
            }
            for (ContextView.Item item : layerCandidates) {
                boolean mandatoryInput = layer == ContextLayer.CURRENT_INPUT;
                boolean fitsLayer = layerUsed + item.tokens() <= layerCap;
                boolean fitsTotal = totalTokens + item.tokens() <= contract.hardLimitTokens();
                if (mandatoryInput && !fitsTotal) {
                    throw new BusinessException("CONTEXT_INPUT_OVER_BUDGET",
                            "当前问题本身超过上下文硬上限，无法在不截断的情况下生成提示");
                }
                if (fitsLayer && fitsTotal) {
                    items.add(item);
                    layerUsed += item.tokens();
                    totalTokens += item.tokens();
                    continue;
                }
                exclusions.add(new ContextView.Exclusion(item.refType(), item.refId(), layer,
                        "CONTEXT_LAYER_BUDGET_EXCEEDED", "该层预算不足以保留此条材料"));
                degradations.add("CONTEXT_" + layer.wire().toUpperCase(java.util.Locale.ROOT) + "_DROPPED");
            }
        }
        ContextView view = ContextView.of(contract, items, exclusions, List.copyOf(degradations));
        return new Selection(contract, view, renderer.render(view), List.copyOf(degradations));
    }

    private List<ContextView.Item> candidatesFor(
            ContextLayer layer,
            String currentQuestion,
            int currentSeq,
            String currentMessageId,
            List<HistoryMessage> history,
            List<SummaryCandidate> summaries,
            List<ConstraintCandidate> constraints,
            List<PremiseCandidate> premises,
            boolean includeCurrentInput
    ) {
        List<ContextView.Item> candidates = new ArrayList<>();
        switch (layer) {
            case CURRENT_INPUT -> {
                if (includeCurrentInput) {
                    candidates.add(ContextView.Item.of(layer, ContextItemForm.RAW, ContextRefType.MESSAGE,
                            currentMessageId == null || currentMessageId.isBlank()
                                    ? "current-input" : currentMessageId,
                            Math.max(0, currentSeq), Math.max(0, currentSeq), currentQuestion));
                }
            }
            case TOPIC_SUMMARY -> {
                for (SummaryCandidate summary : summaries) {
                    candidates.add(ContextView.Item.of(layer, ContextItemForm.TOPIC_SUMMARY,
                            ContextRefType.TOPIC_SUMMARY, summary.refId(),
                            summary.startSeq(), summary.endSeq(), summary.text()));
                }
            }
            case MESSAGE_SUMMARY -> {
                // 消息摘要由后续分层压缩任务填充；当前不把原文伪装成摘要
            }
            case RAW_MESSAGE -> {
                for (HistoryMessage message : history) {
                    candidates.add(ContextView.Item.of(layer, ContextItemForm.RAW,
                            ContextRefType.MESSAGE, message.id(), message.seq(), message.seq(),
                            message.content()));
                }
            }
            case USER_CONSTRAINT -> {
                for (ConstraintCandidate constraint : constraints) {
                    candidates.add(ContextView.Item.of(layer, ContextItemForm.STRUCTURED,
                            ContextRefType.CONSTRAINT, constraint.refId(),
                            constraint.sourceSeq(), constraint.sourceSeq(), constraint.text()));
                }
            }
            case CONVERSATION_PREMISE -> {
                for (PremiseCandidate premise : premises) {
                    candidates.add(ContextView.Item.of(layer, ContextItemForm.STRUCTURED,
                            ContextRefType.PREMISE, premise.refId(),
                            premise.sourceSeq(), premise.sourceSeq(), premise.text()));
                }
            }
            default -> {
            }
        }
        return candidates;
    }

    private String operationText(Selection selection) {
        return selection.rendered().text().strip();
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

    private List<ConversationTurn> buildConversationTurns(List<HistoryMessage> history) {
        List<ConversationTurn> turns = new ArrayList<>();
        String pendingUserQuestion = null;
        int pendingUserSeq = 0;
        for (HistoryMessage message : history) {
            if ("USER".equals(message.role())) {
                pendingUserQuestion = message.content();
                pendingUserSeq = message.seq();
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
            String generationContext,
            boolean contextApplied,
            int windowTurnCount,
            String topicAnchor,
            String topicSummary,
            ConversationContextProjectionService.Projection inputProjection,
            ContextV2ShadowSnapshotService.FrozenAnswer frozenV2,
            String contextContractSha256,
            String contextViewSha256,
            int contextEstimatedTokens,
            Map<String, Integer> contextLayerTokens,
            List<String> contextDegradations
    ) {
        public Context {
            contextLayerTokens = contextLayerTokens == null ? Map.of() : Map.copyOf(contextLayerTokens);
            contextDegradations = contextDegradations == null ? List.of() : List.copyOf(contextDegradations);
        }

        /** 兼容手工构造（测试与旧调用点）：没有独立生成上下文时退回检索上下文。 */
        public Context(
                String currentQuestion,
                String retrievalQuestion,
                boolean contextApplied,
                int windowTurnCount,
                String topicAnchor,
                String topicSummary,
                ConversationContextProjectionService.Projection inputProjection,
                ContextV2ShadowSnapshotService.FrozenAnswer frozenV2
        ) {
            this(currentQuestion, retrievalQuestion, retrievalQuestion, contextApplied, windowTurnCount,
                    topicAnchor, topicSummary, inputProjection, frozenV2,
                    "", "", 0, Map.of(), List.of());
        }
    }

    private record HistoryMessage(String id, int seq, String role, String content) {
    }

    private record SummaryCandidate(String refId, int startSeq, int endSeq, String text) {
    }

    private record ConstraintCandidate(String refId, int sourceSeq, String text) {
    }

    private record PremiseCandidate(String refId, int sourceSeq, String text) {
    }

    private record Selection(
            ContextContract contract,
            ContextView view,
            RenderedContext rendered,
            List<String> degradations
    ) {
    }

    private record Budgeted(
            String retrievalText,
            String generationText,
            ContextContract generationContract,
            ContextView generationView,
            RenderedContext generationRendered,
            List<String> degradations
    ) {
    }

    private record ConversationTurn(String userQuestion, String assistantAnswerSummary, int messageSeq) {
    }
}
