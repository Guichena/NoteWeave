package com.noteweave.chat;

import com.noteweave.answer.AnswerRunRef;
import com.noteweave.answer.AnswerGenerationGateway;
import com.noteweave.answer.AnswerGenerationMaterial;
import com.noteweave.answer.AnswerGenerationOrchestrator;
import com.noteweave.answer.AnswerSubmissionService;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.AnswerModeStrategy;
import com.noteweave.answer.strategy.AnswerModeStrategyRegistry;
import com.noteweave.answer.strategy.AnswerPolicy;
import com.noteweave.answer.strategy.AnswerStrategyContractValidator;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.answer.strategy.RetrievalOrchestrator;
import com.noteweave.answer.AnswerLiveEvent;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.conversation.EffectiveRetrievalConfig;
import com.noteweave.conversation.ConversationContextProjectionService;
import com.noteweave.memory.MemoryCompilerService;
import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.quota.WorkloadQuotaService;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.security.AuditActorProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {

    private final JdbcTemplate jdbcTemplate;
    private final MemoryCompilerService memoryCompilerService;
    private final AnswerGenerationGateway answerGenerationGateway;
    private final AnswerGenerationOrchestrator answerGenerationOrchestrator;
    private final AnswerSubmissionService answerSubmissionService;
    private final AnswerModeStrategyRegistry answerModeStrategyRegistry;
    private final RetrievalOrchestrator unifiedRetrievalOrchestrator;
    private final EvidenceCitationAssembler evidenceCitationAssembler;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final AuditActorProvider auditActorProvider;
    private final AnswerStrategyContractValidator strategyContractValidator;
    private final WorkloadQuotaService workloadQuotaService;
    private final ConversationContextProjectionService contextProjectionService;

    public ChatService(
            JdbcTemplate jdbcTemplate,
            MemoryCompilerService memoryCompilerService,
            AnswerGenerationGateway answerGenerationGateway,
            AnswerGenerationOrchestrator answerGenerationOrchestrator,
            AnswerSubmissionService answerSubmissionService,
            AnswerModeStrategyRegistry answerModeStrategyRegistry,
            RetrievalOrchestrator unifiedRetrievalOrchestrator,
            EvidenceCitationAssembler evidenceCitationAssembler,
            WorkspaceAccessGuard workspaceAccessGuard,
            AuditActorProvider auditActorProvider,
            AnswerStrategyContractValidator strategyContractValidator,
            WorkloadQuotaService workloadQuotaService,
            ConversationContextProjectionService contextProjectionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryCompilerService = memoryCompilerService;
        this.answerGenerationGateway = answerGenerationGateway;
        this.answerGenerationOrchestrator = answerGenerationOrchestrator;
        this.answerSubmissionService = answerSubmissionService;
        this.answerModeStrategyRegistry = answerModeStrategyRegistry;
        this.unifiedRetrievalOrchestrator = unifiedRetrievalOrchestrator;
        this.evidenceCitationAssembler = evidenceCitationAssembler;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.auditActorProvider = auditActorProvider;
        this.strategyContractValidator = strategyContractValidator;
        this.workloadQuotaService = workloadQuotaService;
        this.contextProjectionService = contextProjectionService;
    }

    public PreparedAnswerMaterial compilePreparedAnswer(
            String conversationId,
            String userMessageId,
            String workspaceId,
            SendMessageRequest request
    ) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.ANSWER_RUN);
        AnswerMode.parse(request.answerMode());
        workloadQuotaService.requireRate(workspaceId, "chat");
        EffectiveRetrievalConfig retrievalConfig = EffectiveRetrievalConfig.resolve(
                request.retrievalStrategy(), request.retrievalChannels(),
                request.sourceScopeSourceIds(), request.groundingRefs());
        AnswerDraft draft = buildAnswerDraft(conversationId, userMessageId, workspaceId, request, retrievalConfig);
        return new PreparedAnswerMaterial(
                draft.answer(),
                draft.existingCitationIds(),
                draft.chatControlPack(),
                draft.retrievalPlan(),
                draft.evidenceBundle(),
                draft.promptSpec(),
                draft.contextProjection(),
                draft.maximumOutputTokens()
        );
    }

    @Transactional
    public void finalizePreparedAnswer(
            String workspaceId,
            String runId,
            String assistantMessageId,
            String assistantRequestId,
            String answerMode,
            PreparedAnswerMaterial material
    ) {
        jdbcTemplate.update("""
                update conversation_message
                set content = ?
                where workspace_id = ? and id = ? and context_status = 'PENDING'
                """, material.answer(), workspaceId, assistantMessageId);
        int newCitationCount = evidenceCitationAssembler.persist(
                workspaceId, assistantMessageId, material.evidenceBundle(), material.promptSpec());
        bindExistingCitations(
                workspaceId, assistantMessageId, material.existingCitationIds(), newCitationCount);
        memoryCompilerService.logPackUsage(
                workspaceId,
                answerMode,
                "CONVERSATION_MESSAGE",
                assistantMessageId,
                material.chatControlPack()
        );
        answerSubmissionService.prepareExistingRun(
                workspaceId,
                runId,
                assistantMessageId,
                assistantRequestId,
                material.answer(),
                material.retrievalPlan(),
                material.evidenceBundle(),
                material.promptVersion(),
                material.maximumOutputTokens()
        );
    }

    public void stream(String assistantRequestId, java.util.function.Consumer<ChatStreamEvent> eventConsumer) {
        answerGenerationOrchestrator.stream(assistantRequestId, event -> eventConsumer.accept(
                new ChatStreamEvent(event.id(), event.eventType(), event.data())));
    }

    public void streamRun(
            String workspaceId,
            String runId,
            java.util.function.Consumer<ChatStreamEvent> eventConsumer
    ) {
        AnswerRunRef run = answerGenerationOrchestrator.requireRun(workspaceId, runId);
        workspaceAccessGuard.requirePermission(run.workspaceId(), WorkspacePermission.WORKSPACE_READ);
        answerGenerationOrchestrator.streamRun(workspaceId, runId, event -> eventConsumer.accept(
                new ChatStreamEvent(event.id(), event.eventType(), event.data())));
    }

    /** Starts generation after the already-authorized send command. */
    void startRun(String workspaceId, String runId) {
        answerGenerationOrchestrator.startRun(workspaceId, runId);
    }

    public void followRun(
            String workspaceId,
            String runId,
            long after,
            java.util.function.Consumer<AnswerLiveEvent> eventConsumer
    ) {
        AnswerRunRef run = answerGenerationOrchestrator.requireRun(workspaceId, runId);
        workspaceAccessGuard.requirePermission(run.workspaceId(), WorkspacePermission.WORKSPACE_READ);
        answerGenerationOrchestrator.followRun(workspaceId, runId, after, eventConsumer);
    }

    public void requireStreamAccess(String assistantRequestId) {
        AnswerGenerationMaterial message = answerGenerationGateway.load(assistantRequestId);
        workspaceAccessGuard.requirePermission(message.workspaceId(), WorkspacePermission.WORKSPACE_READ);
    }

    private AnswerDraft buildAnswerDraft(
            String conversationId,
            String currentUserMessageId,
            String workspaceId,
            SendMessageRequest request,
            EffectiveRetrievalConfig retrievalConfig
    ) {
        ConversationContext conversationContext = buildConversationContext(
                workspaceId, conversationId, currentUserMessageId, request.content());
        MemoryControlPackResponse chatControlPack = memoryCompilerService.compileChatControlPack(workspaceId, request.answerMode());
        AnswerMode mode = AnswerMode.parse(request.answerMode());
        Set<String> sourceScope = new LinkedHashSet<>(request.sourceScopeSourceIds());
        if (!sourceScope.isEmpty() && mode != AnswerMode.QA) {
            throw new BusinessException(
                    "ANSWER_SOURCE_SCOPE_UNSUPPORTED",
                    "显式资料范围当前仅支持 QA 回答模式"
            );
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_CURRENT_QUESTION, conversationContext.currentQuestion());
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_REQUEST_CONTENT, request.content());
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_QUESTION_TYPE,
                classifyQuestion(conversationContext.currentQuestion()));
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_CONTEXT_APPLIED,
                Boolean.toString(conversationContext.contextApplied()));
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_WINDOW_TURN_COUNT,
                Integer.toString(conversationContext.windowTurnCount()));
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_TOPIC_ANCHOR, conversationContext.topicAnchor());
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_TOPIC_SUMMARY, conversationContext.topicSummary());
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_HAS_CHAT_CONTROLS,
                Boolean.toString(chatControlPack.hasControls()));
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_CHAT_CONTROL_SECTION,
                renderChatControlSection(chatControlPack));
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_TEMPLATE_LABEL, templateLabel(mode));
        AnswerContext context = new AnswerContext(
                workspaceId,
                conversationId,
                currentUserMessageId,
                conversationContext.retrievalQuestion(),
                sourceScope,
                attributes,
                Instant.now()
        );
        AnswerModeStrategy strategy = answerModeStrategyRegistry.require(mode);
        RetrievalPlan strategyPlan = strategy.plan(context);
        RetrievalPlan plan = "NONE".equals(retrievalConfig.strategy())
                ? new RetrievalPlan("none-v1", mode, List.of(), strategyPlan.budget())
                : strategyPlan;
        EvidenceBundle bundle;
        if ("NONE".equals(retrievalConfig.strategy())) {
            bundle = new EvidenceBundle(
                    "none:" + currentUserMessageId,
                    plan.version(),
                    List.of(),
                    false,
                    List.of(),
                    Instant.now(),
                    Map.of("retrieval_disabled", "true")
            );
        } else {
            strategyContractValidator.validatePlan(mode, context, plan);
            bundle = unifiedRetrievalOrchestrator.execute(context, plan);
        }
        PromptSpec prompt = strategy.compose(context, bundle);
        AnswerPolicy policy = strategy.policy();
        strategyContractValidator.validatePromptAndPolicy(
                plan, bundle, prompt, policy);
        String tagged = prefixWithTemplateMarker(prompt.userPrompt(), attributes.get("template_label"));
        return new AnswerDraft(
                tagged,
                selectedWikiCitationIds(bundle),
                chatControlPack,
                plan,
                bundle,
                prompt,
                conversationContext.inputProjection(),
                policy.maximumOutputTokens()
        );
    }

    private String templateLabel(AnswerMode mode) {
        return mode.name().substring(0, 1) + mode.name().substring(1).toLowerCase() + " 链路模板";
    }

    private String prefixWithTemplateMarker(String body, String modeLabel) {
        return "> **[TEMPLATE PLACEHOLDER]** 当前答案为 `" + modeLabel + "` 拼接原型，"
                + "未接 LLM；接入 LLM 后此标记将被移除，由模型综合生成。\n\n" + body;
    }

    static List<String> selectedWikiCitationIds(EvidenceBundle bundle) {
        List<String> selectedEvidenceIds = bundle.evidence().stream()
                .filter(evidence -> "KNOWLEDGE_VERSION".equals(evidence.kind()))
                .map(EvidenceBundle.Evidence::evidenceId)
                .toList();
        List<String> retrievedEvidenceIds = metadataIds(
                bundle, WikiEvidenceRetriever.RETRIEVED_EVIDENCE_IDS_METADATA);
        if (selectedEvidenceIds.equals(retrievedEvidenceIds)) {
            return metadataIds(bundle, WikiEvidenceRetriever.EXISTING_CITATION_IDS_METADATA);
        }
        List<String> selected = new ArrayList<>();
        for (EvidenceBundle.Evidence evidence : bundle.evidence()) {
            if (!"KNOWLEDGE_VERSION".equals(evidence.kind())) {
                continue;
            }
            String value = evidence.metadata().get("citation_ids");
            if (value == null || value.isBlank()) {
                continue;
            }
            java.util.Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .forEach(selected::add);
        }
        return List.copyOf(selected);
    }

    private static List<String> metadataIds(EvidenceBundle bundle, String key) {
        String value = bundle.metadata().get(key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private void appendChatControlSection(StringBuilder builder, MemoryControlPackResponse chatControlPack) {
        if (chatControlPack == null || !chatControlPack.hasControls()) {
            return;
        }
        builder.append("## 表达控制\n");
        appendControlLine(builder, "- 风格约束：", chatControlPack.styleConstraints());
        appendControlLine(builder, "- 结构约束：", chatControlPack.structureConstraints());
        appendControlLine(builder, "- 术语口径：", chatControlPack.terminologyPolicy());
        appendControlLine(builder, "- 禁用路径：", chatControlPack.forbiddenPatterns());
        appendControlLine(builder, "- 交互策略：", chatControlPack.interactionPolicy());
        appendControlLine(builder, "- 复核清单：", chatControlPack.reviewChecklist());
        builder.append("\n");
    }

    private String renderChatControlSection(MemoryControlPackResponse chatControlPack) {
        StringBuilder builder = new StringBuilder();
        appendChatControlSection(builder, chatControlPack);
        return builder.toString();
    }

    private void appendControlLine(StringBuilder builder, String prefix, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append(prefix).append(String.join("；", values)).append("\n");
    }

    private void bindExistingCitations(String workspaceId, String messageId, List<String> citationIds, int sortOffset) {
        for (int i = 0; i < citationIds.size(); i++) {
            jdbcTemplate.update("""
                    insert into message_citation(id, message_id, citation_id, sort_order)
                    select ?, ?, c.id, ?
                    from citation c
                    where c.workspace_id = ? and c.id = ?
                    """, Ids.newId(), messageId, sortOffset + i, workspaceId, citationIds.get(i));
        }
    }

    private ConversationRef findConversation(String conversationId) {
        return jdbcTemplate.query("""
                select id, workspace_id from conversation where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("CONVERSATION_NOT_FOUND", "会话不存在");
            }
            return new ConversationRef(rs.getString("id"), rs.getString("workspace_id"));
        }, conversationId);
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "...";
    }

    private String classifyQuestion(String content) {
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

    private ConversationContext buildConversationContext(
            String workspaceId, String conversationId, String currentUserMessageId, String currentQuestion) {
        String trimmedQuestion = currentQuestion == null ? "" : currentQuestion.trim();
        if (!isContextDependentQuestion(trimmedQuestion)) {
            return emptyConversationContext(trimmedQuestion);
        }
        Integer cutoffSeq = jdbcTemplate.queryForObject(
                "select message_seq from conversation_message where id = ?", Integer.class, currentUserMessageId);
        ConversationContextProjectionService.CompilationProjection projection = contextProjectionService.compile(
                workspaceId, conversationId, cutoffSeq == null ? 0 : cutoffSeq);
        List<ConversationHistoryMessage> history = projection.rawMessages().stream()
                .filter(message -> !message.messageId().equals(currentUserMessageId))
                .map(message -> new ConversationHistoryMessage(message.messageSeq(), message.role(), message.content()))
                .toList();
        List<ConversationTurn> turns = buildConversationTurns(history);
        if (turns.isEmpty()) {
            return emptyConversationContext(trimmedQuestion);
        }
        List<ConversationTurn> workingTurns = buildWorkingTurns(trimmedQuestion, turns);
        if (workingTurns.isEmpty()) {
            return emptyConversationContext(trimmedQuestion);
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
        return new ConversationContext(
                trimmedQuestion,
                retrievalQuestion.toString(),
                true,
                workingTurns.size(),
                topicAnchor,
                topicSummary,
                List.copyOf(workingTurns),
                contextProjectionService.toProjection(projection)
        );
    }

    private ConversationContext emptyConversationContext(String currentQuestion) {
        return new ConversationContext(
                currentQuestion,
                currentQuestion,
                false,
                0,
                "",
                "",
                List.of(),
                new ConversationContextProjectionService.Projection(List.of(), List.of())
        );
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

    private boolean isContextDependentQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim().toLowerCase();
        if (normalized.length() <= 6) {
            return true;
        }
        return containsFollowUpHint(normalized);
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

    private record ConversationRef(String conversationId, String workspaceId) {
    }

    private record AnswerDraft(
            String answer,
            List<String> existingCitationIds,
            MemoryControlPackResponse chatControlPack,
            RetrievalPlan retrievalPlan,
            EvidenceBundle evidenceBundle,
            PromptSpec promptSpec,
            ConversationContextProjectionService.Projection contextProjection,
            int maximumOutputTokens
    ) {
        private String promptVersion() {
            return promptSpec == null ? retrievalPlan.version() : promptSpec.promptVersion();
        }
    }

    public record PreparedAnswerMaterial(
            String answer,
            List<String> existingCitationIds,
            MemoryControlPackResponse chatControlPack,
            RetrievalPlan retrievalPlan,
            EvidenceBundle evidenceBundle,
            PromptSpec promptSpec,
            ConversationContextProjectionService.Projection contextProjection,
            int maximumOutputTokens
    ) {
        public PreparedAnswerMaterial {
            existingCitationIds = existingCitationIds == null ? List.of() : List.copyOf(existingCitationIds);
            contextProjection = contextProjection == null
                    ? new ConversationContextProjectionService.Projection(List.of(), List.of())
                    : contextProjection;
        }

        public String promptVersion() {
            return promptSpec == null ? retrievalPlan.version() : promptSpec.promptVersion();
        }
    }

    private record ConversationHistoryMessage(int messageSeq, String role, String content) {
    }

    private record ConversationTurn(String userQuestion, String assistantAnswerSummary, int messageSeq) {
    }

    private record ConversationContext(
            String currentQuestion,
            String retrievalQuestion,
            boolean contextApplied,
            int windowTurnCount,
            String topicAnchor,
            String topicSummary,
            List<ConversationTurn> workingTurns,
            ConversationContextProjectionService.Projection inputProjection
    ) {
    }
}
