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
import com.noteweave.conversation.ContextV2RolloutService;
import com.noteweave.conversation.ContextV2ShadowSnapshotService;
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
    private final ConversationRetrievalContextAssembler conversationContextAssembler;
    private final ContextV2RolloutService contextV2Rollout;
    private final ContextV2ShadowSnapshotService contextV2Snapshots;

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
            ConversationContextProjectionService contextProjectionService,
            ConversationRetrievalContextAssembler conversationContextAssembler,
            ContextV2RolloutService contextV2Rollout,
            ContextV2ShadowSnapshotService contextV2Snapshots
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
        this.conversationContextAssembler = conversationContextAssembler;
        this.contextV2Rollout = contextV2Rollout;
        this.contextV2Snapshots = contextV2Snapshots;
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
                draft.frozenContextV2(),
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
        requireRunStreamAccess(workspaceId, runId);
        streamAuthorizedRun(workspaceId, runId, eventConsumer);
    }

    public void requireRunStreamAccess(String workspaceId, String runId) {
        AnswerRunRef run = answerGenerationOrchestrator.requireRun(workspaceId, runId);
        workspaceAccessGuard.requirePermission(run.workspaceId(), WorkspacePermission.WORKSPACE_READ);
    }

    void streamAuthorizedRun(
            String workspaceId,
            String runId,
            java.util.function.Consumer<ChatStreamEvent> eventConsumer
    ) {
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
        requireRunStreamAccess(workspaceId, runId);
        followAuthorizedRun(workspaceId, runId, after, eventConsumer);
    }

    void followAuthorizedRun(
            String workspaceId,
            String runId,
            long after,
            java.util.function.Consumer<AnswerLiveEvent> eventConsumer
    ) {
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
        ConversationRetrievalContextAssembler.Context conversationContext = buildConversationContext(
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
                conversationContextAssembler.classifyQuestion(conversationContext.currentQuestion()));
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_CONTEXT_APPLIED,
                Boolean.toString(conversationContext.contextApplied()));
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_WINDOW_TURN_COUNT,
                Integer.toString(conversationContext.windowTurnCount()));
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_TOPIC_ANCHOR, conversationContext.topicAnchor());
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_TOPIC_SUMMARY, conversationContext.topicSummary());
        String v2UserControls = renderV2UserConstraints(conversationContext.frozenV2());
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_HAS_CHAT_CONTROLS,
                Boolean.toString(chatControlPack.hasControls() || !v2UserControls.isBlank()));
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_CHAT_CONTROL_SECTION,
                renderChatControlSection(chatControlPack) + v2UserControls);
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
                conversationContext.frozenV2(),
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

    static String renderV2UserConstraints(ContextV2ShadowSnapshotService.FrozenAnswer frozen) {
        if (frozen == null || frozen.projection().constraints().isEmpty()) return "";
        StringBuilder builder = new StringBuilder("## 当前有效的用户约束（冻结会话）\n");
        frozen.projection().constraints().forEach(rule -> builder.append("- ")
                .append(rule.text()).append("\n"));
        return builder.append("\n").toString();
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

    private ConversationRetrievalContextAssembler.Context buildConversationContext(
            String workspaceId, String conversationId, String currentUserMessageId, String currentQuestion) {
        String trimmedQuestion = currentQuestion == null ? "" : currentQuestion.trim();
        if (contextV2Rollout.activeEnabled(workspaceId)) {
            var frozen = contextV2Snapshots.readReadyForAnswer(
                    workspaceId, conversationId, currentUserMessageId);
            if (frozen != null) {
                return conversationContextAssembler.assembleV2(
                        currentUserMessageId, trimmedQuestion, frozen);
            }
        }
        if (!conversationContextAssembler.requiresHistory(trimmedQuestion)) {
            return conversationContextAssembler.empty(trimmedQuestion);
        }
        Integer cutoffSeq = jdbcTemplate.queryForObject(
                "select message_seq from conversation_message where id = ?", Integer.class, currentUserMessageId);
        ConversationContextProjectionService.CompilationProjection projection = contextProjectionService.compile(
                workspaceId, conversationId, cutoffSeq == null ? 0 : cutoffSeq);
        return conversationContextAssembler.assemble(
                currentUserMessageId,
                trimmedQuestion,
                projection,
                contextProjectionService.toProjection(projection)
        );
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
            ContextV2ShadowSnapshotService.FrozenAnswer frozenContextV2,
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
            ContextV2ShadowSnapshotService.FrozenAnswer frozenContextV2,
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

}
