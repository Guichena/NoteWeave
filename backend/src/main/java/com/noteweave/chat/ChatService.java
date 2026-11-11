package com.noteweave.chat;

import com.noteweave.answer.AnswerRunRef;
import com.noteweave.answer.AnswerGenerationGateway;
import com.noteweave.answer.AnswerGenerationMaterial;
import com.noteweave.answer.generation.CitationAssemblyResult;
import com.noteweave.answer.generation.GenerationContract;
import com.noteweave.answer.generation.GenerationContractCompiler;
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
import com.noteweave.conversation.ConversationWorkingSetService;
import com.noteweave.conversation.EffectiveRetrievalConfig;
import com.noteweave.conversation.ConversationContextProjectionService;
import com.noteweave.conversation.ContextV2RolloutService;
import com.noteweave.conversation.ContextV2ShadowSnapshotService;
import com.noteweave.conversation.ConversationTopicRoutingService;
import com.noteweave.conversation.TopicRoute;
import com.noteweave.conversation.constraint.ConstraintPolarity;
import com.noteweave.memory.MemoryCompilerService;
import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.quota.WorkloadQuotaService;
import com.noteweave.retrieval.QueryRewrite;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.security.AuditActorProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {

    /** 控制文本归一化时忽略的空白与常见标点，仅用于判断约束是否重复。 */
    private static final Pattern CONTROL_TEXT_NOISE = Pattern.compile("[\\s\\u3000\\p{P}]+");

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
    private final QueryRewriteService queryRewriteService;
    private final EvidenceSufficiencyJudge evidenceSufficiencyJudge;
    private final ContextV2RolloutService contextV2Rollout;
    private final ContextV2ShadowSnapshotService contextV2Snapshots;
    private final ResearchReportGrounding reportGrounding;
    private final ConversationWorkingSetService workingSetService;
    private final com.noteweave.research.ResearchCellFollowUpService researchCellFollowUpService;
    private final com.noteweave.conversation.ConversationSessionPreferenceService sessionPreferenceService;
    private final com.noteweave.context.LlmConversationPremiseExtractor premiseExtractor;
    private final com.noteweave.context.ConversationPremiseService premiseService;
    private final java.util.concurrent.Executor premiseExecutor;
    /** 每个会话尚未完成的前提抽取链：同一会话按发送顺序折叠前提。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Void>>
            premiseChains = new java.util.concurrent.ConcurrentHashMap<>();
    /** 下一轮准备上下文前最多等待上一轮前提抽取的时间，超时就按已落库的前提继续。 */
    static final long PREVIOUS_PREMISE_WAIT_MILLIS = 3_000L;
    private static final String CONSTRAINT_CHAIN_PREFIX = "constraint:";

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
            QueryRewriteService queryRewriteService,
            EvidenceSufficiencyJudge evidenceSufficiencyJudge,
            ContextV2RolloutService contextV2Rollout,
            ContextV2ShadowSnapshotService contextV2Snapshots,
            ResearchReportGrounding reportGrounding,
            ConversationWorkingSetService workingSetService,
            com.noteweave.research.ResearchCellFollowUpService researchCellFollowUpService,
            com.noteweave.conversation.ConversationSessionPreferenceService sessionPreferenceService,
            com.noteweave.context.LlmConversationPremiseExtractor premiseExtractor,
            com.noteweave.context.ConversationPremiseService premiseService,
            @org.springframework.beans.factory.annotation.Qualifier("conversationPremiseExecutor")
            java.util.concurrent.Executor premiseExecutor
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
        this.queryRewriteService = queryRewriteService;
        this.evidenceSufficiencyJudge = evidenceSufficiencyJudge;
        this.contextV2Rollout = contextV2Rollout;
        this.contextV2Snapshots = contextV2Snapshots;
        this.reportGrounding = reportGrounding;
        this.workingSetService = workingSetService;
        this.researchCellFollowUpService = researchCellFollowUpService;
        this.sessionPreferenceService = sessionPreferenceService;
        this.premiseExtractor = premiseExtractor;
        this.premiseService = premiseService;
        this.premiseExecutor = premiseExecutor;
    }

    public PreparedAnswerMaterial compilePreparedAnswer(
            String conversationId,
            String userMessageId,
            String workspaceId,
            SendMessageRequest request
    ) {
        requireAnswerAdmission(conversationId, workspaceId, request);
        return compileAdmittedAnswer(conversationId, userMessageId, workspaceId, request,
                AnswerPreparationListener.NONE);
    }

    /**
     * 发消息时同步执行的准入检查：权限、回答模式、限流和检索参数组合。
     * 这些检查只读库、不调用模型，失败时请求直接返回对应的错误码；
     * 通过后耗时的改写、检索和判定可以放到后台执行。
     */
    public void requireAnswerAdmission(String conversationId, String workspaceId, SendMessageRequest request) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.ANSWER_RUN);
        AnswerMode mode = AnswerMode.parse(request.answerMode());
        workloadQuotaService.requireRate(workspaceId, "chat");
        EffectiveRetrievalConfig retrievalConfig = EffectiveRetrievalConfig.resolve(
                request.retrievalStrategy(), request.retrievalChannels(),
                request.sourceScopeSourceIds(), request.groundingRefs());
        validateRetrievalRequest(workspaceId, conversationId, mode, request, retrievalConfig);
    }

    /**
     * 准入通过之后的回答准备：整理上下文、改写查询、检索、判定证据充分性并组装提示。
     * 不再做限流计数，调用方必须先通过 {@link #requireAnswerAdmission}。
     */
    public PreparedAnswerMaterial compileAdmittedAnswer(
            String conversationId,
            String userMessageId,
            String workspaceId,
            SendMessageRequest request,
            AnswerPreparationListener listener
    ) {
        return compileAdmittedAnswer(conversationId, userMessageId, workspaceId, request, listener,
                ContextFreeze.NONE);
    }

    /**
     * 会话上下文冻结推迟到后台准备时使用：先等上一轮的后台抽取，再冻结本轮上下文，
     * 然后把本轮的前提抽取和约束补抽排到后台，最后整理上下文、改写、检索。
     */
    public PreparedAnswerMaterial compileAdmittedAnswer(
            String conversationId,
            String userMessageId,
            String workspaceId,
            SendMessageRequest request,
            AnswerPreparationListener listener,
            ContextFreeze contextFreeze
    ) {
        ContextFreeze freeze = contextFreeze == null ? ContextFreeze.NONE : contextFreeze;
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.ANSWER_RUN);
        AnswerMode.parse(request.answerMode());
        EffectiveRetrievalConfig retrievalConfig = EffectiveRetrievalConfig.resolve(
                request.retrievalStrategy(), request.retrievalChannels(),
                request.sourceScopeSourceIds(), request.groundingRefs());
        AnswerPreparationListener stages = safe(listener);
        PreparationTimer timer = new PreparationTimer();
        stages.stage(AnswerPreparationListener.STAGE_UNDERSTANDING, Map.of());
        // 话题由查询改写模型判断：改写在冻结之前发起，与等待上一轮后台抽取同时进行，
        // 改写给出的话题编号决定本轮问题归入哪个话题，冻结时据此选择话题摘要
        RoutedRewrite routed = freeze.freeze() == null ? null
                : startRoutedRewrite(workspaceId, conversationId, userMessageId, request.content());
        // 上一轮的前提抽取在后台执行，这里最多等一小会儿，让本轮上下文尽量看到上一轮的前提
        awaitPendingPremises(conversationId);
        timer.mark("premise_wait");
        TopicRoute topicRoute = null;
        if (freeze.freeze() != null) {
            if (routed != null) {
                // 话题编号在改写输出的最前面，读到就冻结；检索要的独立查询随后在准备里等
                topicRoute = routed.routing().resolve(routed.progressive().awaitRoute());
                timer.mark("rewrite");
            }
            freeze.freeze().accept(topicRoute);
            timer.mark("context_freeze");
        }
        // 本轮消息的前提抽取不进入关键路径：本轮问题原文已经在提示里，抽取结果服务于后续追问
        schedulePremiseExtraction(workspaceId, conversationId, userMessageId, request.content());
        if (freeze.catchUp() != null) {
            scheduleConversationBackground(CONSTRAINT_CHAIN_PREFIX + conversationId, freeze.catchUp());
        }
        // 交互式回答的重排限时：重排服务卡住时按已有降级继续，不让用户等满重试预算
        AnswerDraft draft = com.noteweave.retrieval.provider.InteractiveRerankBudget.run(() -> buildAnswerDraft(
                conversationId, userMessageId, workspaceId, request, retrievalConfig, timer, stages, routed));
        // 分阶段耗时：发消息后到开始生成之间的等待都在这里，后续提速以这些数字为准
        PREPARATION_LOG.info("answer preparation timing workspace={} mode={} topic_route={} {} total_ms={} "
                        + "evidence={} sufficiency={} retrieval_degraded={}",
                workspaceId, request.answerMode(), topicRoute == null ? "RULE" : topicRoute.wire(),
                timer.render(), timer.totalMillis(),
                draft.evidenceBundle().evidence().size(), draft.evidenceSufficiency(),
                String.join(",", draft.evidenceBundle().degradationReasons()));
        return new PreparedAnswerMaterial(
                draft.answer(),
                draft.existingCitationIds(),
                draft.chatControlPack(),
                draft.retrievalPlan(),
                draft.evidenceBundle(),
                draft.promptSpec(),
                draft.contextProjection(),
                draft.frozenContextV2(),
                draft.maximumOutputTokens(),
                draft.evidenceSufficiency(),
                draft.taskParameters(),
                draft.taskParametersUnsupported(),
                draft.taskParametersMemoryRefs()
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
        new CitationBindingService(jdbcTemplate).lockEvidenceParents(
                workspaceId, material.evidenceBundle(), material.existingCitationIds());
        new com.noteweave.answer.AnswerRunLifecycleGuard(jdbcTemplate)
                .lockForPreparation(workspaceId, runId, material.existingCitationIds());
        int preparedMessage = jdbcTemplate.update("""
                update conversation_message
                set content = ?
                where workspace_id = ? and id = ? and context_status = 'PENDING'
                """, material.answer(), workspaceId, assistantMessageId);
        if (preparedMessage != 1) {
            throw new BusinessException("ANSWER_RUN_REVOKED", "回答消息不再允许准备");
        }
        CitationAssemblyResult citationAssembly = evidenceCitationAssembler.persist(
                workspaceId, assistantMessageId, material.evidenceBundle(), material.promptSpec());
        CitationBindingService.BindResult bound = new CitationBindingService(jdbcTemplate).bindToMessage(
                workspaceId, assistantMessageId, material.existingCitationIds(), citationAssembly.persistedCount());
        citationAssembly = new CitationAssemblyResult(citationAssembly.bindings().stream()
                .map(binding -> new com.noteweave.answer.generation.CitationBinding(
                        binding.number(), binding.evidenceId(), binding.citationIds().stream()
                                .map(id -> bound.copiedIds().getOrDefault(id, id)).toList()))
                .toList(), citationAssembly.persistedCount());
        // 引用落库之后冻结生成契约：编号绑定与本次实际生成的引用行一一对应
        GenerationContract generationContract = GenerationContractCompiler.compile(
                answerMode, material.promptSpec(), material.evidenceBundle(), citationAssembly);
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
                material.maximumOutputTokens(),
                material.evidenceSufficiency(),
                generationContract
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

    /** 记录回答准备各阶段的耗时，只用于日志。 */
    static final class PreparationTimer {
        private final long startedAt = System.nanoTime();
        private long lastMark = startedAt;
        private final Map<String, Long> stages = new LinkedHashMap<>();

        void mark(String stage) {
            long now = System.nanoTime();
            stages.merge(stage, (now - lastMark) / 1_000_000L, Long::sum);
            lastMark = now;
        }

        long totalMillis() {
            return (System.nanoTime() - startedAt) / 1_000_000L;
        }

        String render() {
            StringBuilder text = new StringBuilder();
            stages.forEach((stage, millis) -> {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append(stage).append("_ms=").append(millis);
            });
            return text.toString();
        }
    }

    private static final org.slf4j.Logger PREPARATION_LOG =
            org.slf4j.LoggerFactory.getLogger("com.noteweave.chat.AnswerPreparationTiming");

    private AnswerDraft buildAnswerDraft(
            String conversationId,
            String currentUserMessageId,
            String workspaceId,
            SendMessageRequest request,
            EffectiveRetrievalConfig retrievalConfig,
            PreparationTimer timer,
            AnswerPreparationListener stages
    ) {
        return buildAnswerDraft(conversationId, currentUserMessageId, workspaceId, request, retrievalConfig,
                timer, stages, null);
    }

    private AnswerDraft buildAnswerDraft(
            String conversationId,
            String currentUserMessageId,
            String workspaceId,
            SendMessageRequest request,
            EffectiveRetrievalConfig retrievalConfig,
            PreparationTimer timer,
            AnswerPreparationListener stages,
            RoutedRewrite routed
    ) {
        ConversationRetrievalContextAssembler.Context conversationContext = buildConversationContext(
                workspaceId, conversationId, currentUserMessageId, request.content());
        timer.mark("context");
        // 渐进改写：alternatives 之前的字段齐全就先检索，alternatives 在后台继续生成。
        // 冻结之前已经发起改写（用于判断话题）时直接沿用，不再调用第二次
        QueryRewriteService.ProgressiveRewrite progressive = routed != null
                ? routed.progressive()
                : queryRewriteService.rewriteProgressive(conversationContext, rewriteExecutor);
        QueryRewrite dispatched = progressive.awaitDispatchable();
        timer.mark("rewrite");
        AnswerDraft draft = draftWithRewrite(conversationContext, dispatched, conversationId,
                currentUserMessageId, workspaceId, request, retrievalConfig, timer, stages);
        if (progressive.dispatchedPartial()) {
            QueryRewrite complete = progressive.awaitComplete();
            timer.mark("rewrite_tail");
            if (requiresFullRewriteRetrieval(dispatched, complete, draft.evidenceBundle())) {
                // 完整改写与提前检索的输入不一致，或召回不足需要 alternatives 扩展：按完整结果重新检索
                PREPARATION_LOG.info("answer preparation rerun with complete rewrite workspace={} sameFields={}",
                        workspaceId, QueryRewriteService.sameExceptAlternatives(dispatched, complete));
                draft = draftWithRewrite(conversationContext, complete, conversationId,
                        currentUserMessageId, workspaceId, request, retrievalConfig, timer, stages);
                timer.mark("rewrite_rerun");
            }
        }
        return draft;
    }

    /** 检索前提早交出的改写不足以代表最终结果时返回 true。 */
    static boolean requiresFullRewriteRetrieval(QueryRewrite dispatched, QueryRewrite complete, EvidenceBundle bundle) {
        // 规则判定可独立检索、只为话题判断调用了模型：模型也认为可以独立检索时，检索输入就是原句，不必重来
        boolean standaloneConfirmed = "RULE_STANDALONE".equals(dispatched.method()) && complete.standalone();
        if (!standaloneConfirmed && !QueryRewriteService.sameExceptAlternatives(dispatched, complete)) {
            return true;
        }
        if (complete.alternatives().isEmpty() || complete.alternatives().equals(dispatched.alternatives())) {
            return false;
        }
        // 只有召回不足时检索才会用到 alternatives；检索步骤会记下是否满足扩展条件
        return bundle != null && bundle.trace() != null && bundle.trace().steps().stream()
                .anyMatch(step -> step.measurements() != null
                        && Long.valueOf(1L).equals(step.measurements().get(QaHybridRetriever.LOW_RECALL_EXPANSION_ELIGIBLE)));
    }

    /**
     * 冻结之前发起的查询改写。只在会话上下文推迟到后台冻结、工作区使用 Context v2、并且已有历史消息时发起；
     * 首轮问题、规则判定可独立检索的问题不调用模型，这时没有话题编号，冻结照常按规则切分。
     */
    private RoutedRewrite startRoutedRewrite(String workspaceId, String conversationId,
                                             String userMessageId, String question) {
        if (!topicRoutingEnabled || topicRouting == null || !contextV2Rollout.activeEnabled(workspaceId)) {
            return null;
        }
        ConversationTopicRoutingService.RoutingContext routing;
        try {
            routing = topicRouting.load(workspaceId, conversationId, userMessageId);
        } catch (RuntimeException ex) {
            PREPARATION_LOG.info("topic routing context unavailable workspace={} type={}",
                    workspaceId, ex.getClass().getSimpleName());
            return null;
        }
        if (!routing.hasHistory()) {
            return null;
        }
        ConversationRetrievalContextAssembler.Context routingContext =
                conversationContextAssembler.routing(question, routing);
        return new RoutedRewrite(routing, queryRewriteService.rewriteProgressive(
                routingContext, routing.renderTopics(), rewriteExecutor));
    }

    /** 冻结之前发起的改写，以及判断话题用的材料。 */
    private record RoutedRewrite(ConversationTopicRoutingService.RoutingContext routing,
                                 QueryRewriteService.ProgressiveRewrite progressive) {
    }

    private ConversationTopicRoutingService topicRouting;
    private boolean topicRoutingEnabled = true;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTopicRouting(ConversationTopicRoutingService topicRouting) {
        this.topicRouting = topicRouting;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTopicRoutingEnabled(
            @org.springframework.beans.factory.annotation.Value(
                    "${noteweave.conversation.topic-routing.enabled:true}") boolean enabled) {
        this.topicRoutingEnabled = enabled;
    }

    /** 渐进改写使用的线程池；未注入时（例如单元测试）退回同步改写。 */
    private java.util.concurrent.Executor rewriteExecutor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRewriteExecutor(
            @org.springframework.beans.factory.annotation.Qualifier("retrievalBranchExecutor")
            java.util.concurrent.Executor rewriteExecutor
    ) {
        this.rewriteExecutor = rewriteExecutor;
    }

    private AnswerDraft draftWithRewrite(
            ConversationRetrievalContextAssembler.Context conversationContext,
            QueryRewrite rewrite,
            String conversationId,
            String currentUserMessageId,
            String workspaceId,
            SendMessageRequest request,
            EffectiveRetrievalConfig retrievalConfig,
            PreparationTimer timer,
            AnswerPreparationListener stages
    ) {
        stages.stage(AnswerPreparationListener.STAGE_RETRIEVING, Map.of());
        MemoryControlPackResponse chatControlPack = memoryCompilerService.compileChatControlPack(
                workspaceId, request.answerMode(), rewrite.standaloneQuery());
        var preferenceParameters = com.noteweave.preference.PreferenceParameterCompiler
                .compile(chatControlPack);
        AnswerMode mode = AnswerMode.parse(request.answerMode());
        Set<String> sourceScope = new LinkedHashSet<>(request.sourceScopeSourceIds());
        boolean reportChannel = retrievalConfig.channels().contains("REPORT");
        Set<String> reportScope = validateRetrievalRequest(workspaceId, conversationId, mode, request, retrievalConfig);
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
        String preferenceLine = preferenceParameters.promptLine();
        MemoryControlPackResponse filteredControlPack = withoutConsumedPreferences(
                chatControlPack, preferenceParameters.consumed());
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_HAS_CHAT_CONTROLS,
                Boolean.toString(filteredControlPack.hasControls() || !v2UserControls.isBlank()
                        || !preferenceLine.isBlank()));
        String controlSection = renderChatControlSection(filteredControlPack, conversationContext.frozenV2(),
                !conversationContext.contextApplied());
        if (!preferenceLine.isBlank()) {
            controlSection = controlSection.isBlank()
                    ? preferenceLine : controlSection + "\n\n" + preferenceLine;
        }
        var sessionRequirements = sessionPreferenceService.activeRequirementTexts(workspaceId, conversationId);
        if (!sessionRequirements.isEmpty()) {
            String sessionLine = "会话要求（只用于本对话）：" + String.join("；", sessionRequirements);
            controlSection = controlSection.isBlank()
                    ? sessionLine : controlSection + "\n\n" + sessionLine;
        }
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_CHAT_CONTROL_SECTION, controlSection);
        attributes.put("conversation_session_requirement_count",
                Integer.toString(sessionRequirements.size()));
        if (!sessionRequirements.isEmpty()) {
            attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_HAS_CHAT_CONTROLS, "true");
        }
        attributes.put("task_parameters", preferenceLine);
        String unsupportedParameters = preferenceParameters.unsupported().stream()
                .map(item -> item.targetKey() + ":" + item.reason())
                .reduce((left, right) -> left + "," + right).orElse("");
        attributes.put("task_parameters_unsupported", unsupportedParameters);
        String preferenceMemoryRefs = chatControlPack.memoryReferences().stream()
                .map(reference -> reference.memoryObjectId() + "@" + reference.memoryVersionId())
                .reduce((left, right) -> left + "," + right).orElse("");
        attributes.put("task_parameters_memory_refs", preferenceMemoryRefs);
        attributes.put(NoteAnswerModeStrategy.ATTRIBUTE_TEMPLATE_LABEL, templateLabel(mode));
        // F4-01：工作集只提供选择线索与信号摘要，正文与权限仍由各模式检索自己把关。
        Integer currentSeq = jdbcTemplate.queryForObject("""
                select message_seq from conversation_message where id = ? and workspace_id = ?
                """, Integer.class, currentUserMessageId, workspaceId);
        var workingSet = workingSetService.derive(workspaceId, conversationId,
                currentSeq == null ? 0 : currentSeq);
        attributes.put("context_working_set_schema_version", workingSet.schemaVersion());
        attributes.put("context_working_set_size", Integer.toString(workingSet.entries().size()));
        attributes.put("context_working_set_sources", workingSet.entries().stream()
                .filter(entry -> entry.kind() == com.noteweave.context.ConversationWorkingSet.EntryKind.SOURCE)
                .map(entry -> entry.refId()).distinct()
                .reduce((left, right) -> left + "," + right).orElse(""));
        attributes.put("context_working_set_research_runs", workingSet.entries().stream()
                .filter(entry -> entry.kind() == com.noteweave.context.ConversationWorkingSet.EntryKind.RESEARCH_RUN)
                .map(entry -> entry.refId()).distinct()
                .reduce((left, right) -> left + "," + right).orElse(""));
        attributes.put("context_working_set_knowledge_weights", workingSet.entries().stream()
                .filter(entry -> entry.kind() == com.noteweave.context.ConversationWorkingSet.EntryKind.KNOWLEDGE_PAGE)
                .map(entry -> entry.refId() + ":" + entry.weight())
                .reduce((left, right) -> left + "," + right).orElse(""));
        var cellMatches = researchCellFollowUpService.match(workspaceId, conversationId,
                conversationContext.currentQuestion(), currentSeq == null ? 0 : currentSeq);
        attributes.put("context_research_cell_matches", cellMatches.stream()
                .map(match -> match.cellId() + ":" + match.status())
                .reduce((left, right) -> left + "," + right).orElse(""));
        attributes.put("context_research_cell_ambiguous",
                Boolean.toString(cellMatches.stream().anyMatch(match -> match.ambiguous())));
        String cellBoundary = com.noteweave.research.ResearchCellFollowUpService.unverifiedBoundary(cellMatches);
        if (!cellBoundary.isBlank()) {
            attributes.put("context_research_cell_unverified_boundary", "true");
        }
        // 已定位且已核实的单元格证据需要进入本轮研究报告范围，才能通过所有权守卫。
        Set<String> effectiveReportScope = new java.util.LinkedHashSet<>(reportScope);
        cellMatches.stream()
                .filter(match -> !match.ambiguous() && "VERIFIED".equals(match.status()))
                .map(match -> match.researchRunId())
                .forEach(effectiveReportScope::add);
        attributes.putAll(rewrite.attributes(conversationContext.currentQuestion()));
        attributes.put(QaAnswerModeStrategy.ATTRIBUTE_WORKSPACE_CHANNEL,
                Boolean.toString(!reportChannel || retrievalConfig.channels().contains("WORKSPACE")));
        if (conversationContext.contextApplied()) {
            attributes.put(ConversationWindow.ATTRIBUTE, conversationContext.generationContext());
            // 上下文契约、视图与计数同源：排障时可核对提示、冻结快照与预算记录
            attributes.put("context_contract_sha256", conversationContext.contextContractSha256());
            attributes.put("context_view_sha256", conversationContext.contextViewSha256());
            attributes.put("context_estimated_tokens",
                    Integer.toString(conversationContext.contextEstimatedTokens()));
            attributes.put("context_layer_tokens", conversationContext.contextLayerTokens().toString());
            attributes.put("context_degradation_reasons",
                    String.join(",", conversationContext.contextDegradations()));
        }
        AnswerContext context = new AnswerContext(
                workspaceId,
                conversationId,
                currentUserMessageId,
                rewrite.standaloneQuery(),
                sourceScope,
                attributes,
                Instant.now(),
                effectiveReportScope
        );
        AnswerModeStrategy strategy = answerModeStrategyRegistry.require(mode);
        RetrievalPlan strategyPlan = strategy.plan(context);
        if (!reportScope.isEmpty()) {
            strategyPlan = reportGrounding.withReportStep(
                    strategyPlan, context, retrievalConfig.channels().contains("WORKSPACE"));
        }
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
            // NR21：显式声明的来源范围（sourceScope）已进入 AnswerContext.evidenceScope，
            // 检索计划必须遵守声明范围——旧 sourceScope 兼容路径不得绕过（见 buildAnswerDraft）
            strategyContractValidator.validatePlan(mode, context, plan);
            timer.mark("plan");
            bundle = unifiedRetrievalOrchestrator.execute(context, plan);
        }
        timer.mark("retrieval");
        stages.stage(AnswerPreparationListener.STAGE_RETRIEVED,
                Map.of("evidence_count", bundle.evidence().size()));
        EvidenceSufficiencyJudge.Judgement sufficiency = evidenceSufficiencyJudge.judge(
                context.query(), bundle);
        timer.mark("sufficiency");
        PromptSpec prompt = EvidenceSufficiencyPrompts.apply(
                strategy.compose(context, bundle), sufficiency.verdict(), bundle, request.content(),
                sufficiency.deferredToAnswer());
        AnswerPolicy policy = strategy.policy();
        strategyContractValidator.validatePromptAndPolicy(
                plan, bundle, prompt, policy);
        String tagged = prefixWithTemplateMarker(prompt.userPrompt(), attributes.get("template_label"));
        if (!cellBoundary.isBlank()) {
            tagged = tagged + "\n\n" + cellBoundary;
        }
        return new AnswerDraft(
                tagged,
                selectedWikiCitationIds(bundle),
                chatControlPack,
                plan,
                bundle,
                prompt,
                conversationContext.inputProjection(),
                conversationContext.frozenV2(),
                policy.maximumOutputTokens(),
                sufficiency.verdict().name(),
                preferenceLine,
                unsupportedParameters,
                preferenceMemoryRefs
        );
    }

    /** 资料范围与检索通道的组合检查，返回本轮允许取证的研究报告范围。 */
    private Set<String> validateRetrievalRequest(
            String workspaceId,
            String conversationId,
            AnswerMode mode,
            SendMessageRequest request,
            EffectiveRetrievalConfig retrievalConfig
    ) {
        if (!request.sourceScopeSourceIds().isEmpty() && mode != AnswerMode.QA) {
            throw new BusinessException(
                    "ANSWER_SOURCE_SCOPE_UNSUPPORTED",
                    "显式资料范围当前仅支持 QA 回答模式"
            );
        }
        // NR21：WEB 渠道仍没有检索实现，明确拒绝而不是静默回退为工作台检索
        if (retrievalConfig.channels().contains("WEB")) {
            throw new BusinessException(
                    "RETRIEVAL_CHANNEL_UNSUPPORTED",
                    "WEB 检索通道尚未开放：请使用工作台检索（WORKSPACE）、研究报告（REPORT）或显式 NONE 关闭检索",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
        if (retrievalConfig.channels().contains("REPORT") && mode != AnswerMode.QA) {
            throw new BusinessException(
                    "RETRIEVAL_CHANNEL_UNSUPPORTED",
                    "研究报告取证（REPORT）目前只支持问答模式",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
        return reportGrounding.resolve(workspaceId, conversationId, retrievalConfig);
    }

    private static AnswerPreparationListener safe(AnswerPreparationListener listener) {
        if (listener == null) {
            return AnswerPreparationListener.NONE;
        }
        return (stage, data) -> {
            try {
                listener.stage(stage, data);
            } catch (RuntimeException ex) {
                PREPARATION_LOG.debug("answer preparation stage callback failed stage={}: {}", stage, ex.getMessage());
            }
        };
    }

    /** 等待同一会话里尚未完成的前提抽取，最多 {@link #PREVIOUS_PREMISE_WAIT_MILLIS} 毫秒。 */
    void awaitPendingPremises(String conversationId) {
        // 前提抽取和约束补抽各自一条链，两条共用同一个等待上限
        long deadline = System.nanoTime() + PREVIOUS_PREMISE_WAIT_MILLIS * 1_000_000L;
        for (String key : java.util.List.of(conversationId, CONSTRAINT_CHAIN_PREFIX + conversationId)) {
            java.util.concurrent.CompletableFuture<Void> pending = premiseChains.get(key);
            if (pending == null || pending.isDone()) {
                continue;
            }
            long remaining = Math.max(0L, deadline - System.nanoTime());
            try {
                pending.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException ex) {
                PREPARATION_LOG.info("previous background extraction not finished chain={} reason={}",
                        key, ex.getClass().getSimpleName());
            }
        }
    }

    /**
     * 把本轮消息的前提抽取排到后台。同一会话的抽取按提交顺序串行执行，保证更正与撤回按消息顺序折叠；
     * 线程池已满时放弃本轮抽取，前提抽取本来就允许失败。
     */
    void schedulePremiseExtraction(String workspaceId, String conversationId,
                                   String userMessageId, String content) {
        scheduleConversationBackground(conversationId,
                () -> extractPremises(workspaceId, conversationId, userMessageId, content));
    }

    /**
     * 同一条链上的后台任务按提交顺序串行执行；线程池已满时放弃本次任务，这些后台抽取本来就允许失败。
     * 日志上下文带到后台线程，模型调用的 usage 才能按工作区归属。
     */
    private void scheduleConversationBackground(String chainKey, Runnable task) {
        java.util.Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();
        Runnable withContext = () -> {
            java.util.Map<String, String> previous = org.slf4j.MDC.getCopyOfContextMap();
            if (logContext != null) {
                org.slf4j.MDC.setContextMap(logContext);
            }
            try {
                task.run();
            } catch (RuntimeException ex) {
                PREPARATION_LOG.info("conversation background task failed chain={} type={}",
                        chainKey, ex.getClass().getSimpleName());
            } finally {
                if (previous == null) {
                    org.slf4j.MDC.clear();
                } else {
                    org.slf4j.MDC.setContextMap(previous);
                }
            }
        };
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>> created =
                new java.util.concurrent.atomic.AtomicReference<>();
        premiseChains.compute(chainKey, (key, previous) -> {
            java.util.concurrent.CompletableFuture<Void> base = previous == null
                    ? java.util.concurrent.CompletableFuture.completedFuture(null)
                    : previous.exceptionally(ignored -> null);
            java.util.concurrent.CompletableFuture<Void> next;
            try {
                next = base.thenRunAsync(withContext, premiseExecutor);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                PREPARATION_LOG.warn("conversation background task skipped, executor saturated chain={}", chainKey);
                return previous;
            }
            created.set(next);
            return next;
        });
        java.util.concurrent.CompletableFuture<Void> mine = created.get();
        if (mine != null) {
            mine.whenComplete((ignored, failure) -> premiseChains.remove(chainKey, mine));
        }
    }

    /**
     * 推迟到后台准备的会话上下文冻结。freeze 在等待上一轮后台抽取之后、整理上下文之前执行；
     * catchUp 排到后台，补抽本轮问题的约束。两者为空表示上下文已在请求线程里冻结。
     */
    public record ContextFreeze(java.util.function.Consumer<TopicRoute> freeze, Runnable catchUp) {
        public static final ContextFreeze NONE = new ContextFreeze(null, null);
    }

    /**
     * F2-04：抽取并折叠当前消息的会话前提。失败不阻断回答；
     * 重复执行由来源消息 + 抽取版本幂等。
     */
    private void extractPremises(String workspaceId, String conversationId,
                                 String userMessageId, String content) {
        try {
            Integer seq = jdbcTemplate.queryForObject("""
                    select message_seq from conversation_message where id = ? and workspace_id = ?
                    """, Integer.class, userMessageId, workspaceId);
            if (seq == null) {
                return;
            }
            var operations = premiseExtractor.extract(
                    workspaceId, conversationId, userMessageId, seq, content);
            if (!operations.isEmpty()) {
                premiseService.apply(workspaceId, conversationId, operations);
            }
        } catch (RuntimeException ignored) {
            // 前提抽取失败不应阻断本轮回答
        }
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

    private static void appendChatControlSection(StringBuilder builder, MemoryControlPackResponse chatControlPack,
                                                Set<String> suppressedKeys) {
        if (chatControlPack == null || !chatControlPack.hasControls()) {
            return;
        }
        Set<String> seen = new HashSet<>(suppressedKeys);
        List<String> styleConstraints = distinctControlValues(chatControlPack.styleConstraints(), seen);
        List<String> structureConstraints = distinctControlValues(chatControlPack.structureConstraints(), seen);
        List<String> terminologyPolicy = distinctControlValues(chatControlPack.terminologyPolicy(), seen);
        List<String> forbiddenPatterns = distinctControlValues(chatControlPack.forbiddenPatterns(), seen);
        List<String> evidencePolicy = distinctControlValues(chatControlPack.evidencePolicy(), seen);
        List<String> interactionPolicy = distinctControlValues(chatControlPack.interactionPolicy(), seen);
        List<String> reviewChecklist = distinctControlValues(chatControlPack.reviewChecklist(), seen);
        if (styleConstraints.isEmpty() && structureConstraints.isEmpty() && terminologyPolicy.isEmpty()
                && forbiddenPatterns.isEmpty() && evidencePolicy.isEmpty() && interactionPolicy.isEmpty()
                && reviewChecklist.isEmpty()) {
            return;
        }
        builder.append("## 表达控制\n");
        appendControlLine(builder, "- 风格约束：", styleConstraints);
        appendControlLine(builder, "- 结构约束：", structureConstraints);
        appendControlLine(builder, "- 术语口径：", terminologyPolicy);
        appendControlLine(builder, "- 禁用路径：", forbiddenPatterns);
        appendControlLine(builder, "- 证据口径：", evidencePolicy);
        appendControlLine(builder, "- 交互策略：", interactionPolicy);
        appendControlLine(builder, "- 复核清单：", reviewChecklist);
        builder.append("\n");
    }

    /**
     * 同一用户约束可能同时出现在控制包与 v2 冻结约束中，按归一化文本去重，
     * 已由冻结约束（优先保留）表达过的控制项不再重复输出。
     */
    private static List<String> distinctControlValues(List<String> values, Set<String> seen) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> distinct = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String key = normalizeControlText(value);
            if (!key.isEmpty() && !seen.add(key)) {
                continue;
            }
            distinct.add(value);
        }
        return distinct;
    }

    /** 去空白与常见标点，仅用于判断两条控制文本是否表达同一约束。 */
    static String normalizeControlText(String text) {
        if (text == null) {
            return "";
        }
        return CONTROL_TEXT_NOISE.matcher(text).replaceAll("");
    }

    static Set<String> frozenConstraintKeys(ContextV2ShadowSnapshotService.FrozenAnswer frozen) {
        if (frozen == null) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        frozen.projection().constraints().forEach(rule -> {
            String key = normalizeControlText(rule.text());
            if (!key.isEmpty()) {
                keys.add(key);
            }
        });
        return keys;
    }

    /** 已进入结构化参数的原文从旧控制包中移除，保证同一要求只有一套实际输入。 */
    private static MemoryControlPackResponse withoutConsumedPreferences(
            MemoryControlPackResponse pack, java.util.Set<String> consumed) {
        if (pack == null || consumed == null || consumed.isEmpty()) {
            return pack;
        }
        return new MemoryControlPackResponse(
                pack.packType(), pack.targetKey(), pack.taskNeighborhood(),
                filterConsumed(pack.styleConstraints(), consumed),
                filterConsumed(pack.structureConstraints(), consumed),
                filterConsumed(pack.terminologyPolicy(), consumed),
                filterConsumed(pack.forbiddenPatterns(), consumed),
                filterConsumed(pack.evidencePolicy(), consumed),
                filterConsumed(pack.interactionPolicy(), consumed),
                filterConsumed(pack.reviewChecklist(), consumed),
                pack.memoryObjectIds(), pack.memoryReferences(), pack.compilationTrace());
    }

    private static java.util.List<String> filterConsumed(java.util.List<String> values,
                                                         java.util.Set<String> consumed) {
        if (values == null || values.isEmpty()) {
            return java.util.List.of();
        }
        return values.stream()
                .filter(value -> value == null || !consumed.contains(value.strip()))
                .toList();
    }

    /** 合并控制包与 v2 冻结用户约束，同一约束（归一化文本相同）只保留一次。 */
    static String renderChatControlSection(MemoryControlPackResponse chatControlPack,
                                           ContextV2ShadowSnapshotService.FrozenAnswer frozen) {
        return renderChatControlSection(chatControlPack, frozen, true);
    }

    /**
     * @param renderFrozenConstraints 对话窗口已经带着同一批冻结约束时传 false：控制包里重复的控制项仍然
     *                                按冻结约束去重，但不再单独渲染一遍，避免提示词里出现两份用户约束
     */
    static String renderChatControlSection(MemoryControlPackResponse chatControlPack,
                                           ContextV2ShadowSnapshotService.FrozenAnswer frozen,
                                           boolean renderFrozenConstraints) {
        StringBuilder builder = new StringBuilder();
        appendChatControlSection(builder, chatControlPack, frozenConstraintKeys(frozen));
        return renderFrozenConstraints
                ? builder.append(renderV2UserConstraints(frozen)).toString()
                : builder.toString();
    }

    /**
     * 冻结约束逐行渲染：正文本身已经是可直接执行的完整指令，禁止项在落库时就是「不要使用表格」
     * 这种单次否定的句子，所以这里不再前置「禁止：」，否则会出现「禁止：不要使用表格」的双重否定。
     * 极性仍结构化保存，用于别的渲染入口与统计。
     */
    static String renderV2UserConstraints(ContextV2ShadowSnapshotService.FrozenAnswer frozen) {
        if (frozen == null || frozen.projection().constraints().isEmpty()) return "";
        StringBuilder builder = new StringBuilder("## 当前有效的用户约束（冻结会话）\n");
        frozen.projection().constraints().forEach(rule -> builder
                .append("- ")
                .append(rule.text()).append("\n"));
        return builder.append("\n").toString();
    }

    private static void appendControlLine(StringBuilder builder, String prefix, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append(prefix).append(String.join("；", values)).append("\n");
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
        String actorId = auditActorProvider.currentOrSystem("CHAT");
        if (contextV2Rollout.activeEnabled(workspaceId)) {
            var frozen = contextV2Snapshots.readReadyForAnswer(
                    workspaceId, conversationId, currentUserMessageId);
            if (frozen != null) {
                return conversationContextAssembler.assembleV2(
                        workspaceId, actorId, currentUserMessageId, trimmedQuestion, frozen);
            }
        }
        if (!conversationContextAssembler.requiresHistory(trimmedQuestion)) {
            return conversationContextAssembler.empty(trimmedQuestion);
        }
        Integer cutoffSeq = jdbcTemplate.queryForObject(
                "select message_seq from conversation_message where id = ?", Integer.class, currentUserMessageId);
        int cutoff = cutoffSeq == null ? 0 : cutoffSeq;
        ConversationContextProjectionService.CompilationProjection projection = contextProjectionService.compile(
                workspaceId, conversationId, cutoff);
        return conversationContextAssembler.assemble(
                workspaceId,
                actorId,
                conversationId,
                currentUserMessageId,
                trimmedQuestion,
                projection,
                contextProjectionService.toProjection(projection),
                loadConversationLedger(workspaceId, conversationId, cutoff),
                loadPremises(workspaceId, conversationId, cutoff)
        );
    }

    /** 冻结截止内仍生效的会话前提；前提只作为未经核实的条件进入提示。 */
    private java.util.List<com.noteweave.context.ConversationPremise> loadPremises(
            String workspaceId, String conversationId, int cutoffSeq) {
        return jdbcTemplate.query("""
                select id, workspace_id, conversation_id, subject_key, premise_text, source_message_id,
                       source_seq, scope, topic_id, valid_from_seq, invalid_after_seq, status,
                       superseded_by, extraction_version
                from conversation_premise
                where workspace_id = ? and conversation_id = ? and status = 'ACTIVE'
                  and valid_from_seq <= ?
                  and (invalid_after_seq is null or invalid_after_seq > ?)
                order by source_seq asc, id asc
                """, (rs, rowNum) -> new com.noteweave.context.ConversationPremise(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("conversation_id"),
                rs.getString("subject_key"),
                rs.getString("premise_text"),
                rs.getString("source_message_id"),
                rs.getInt("source_seq"),
                com.noteweave.context.ConversationPremise.PremiseScope.fromDb(rs.getString("scope")),
                rs.getString("topic_id"),
                rs.getInt("valid_from_seq"),
                (Integer) rs.getObject("invalid_after_seq"),
                rs.getString("status"),
                rs.getString("superseded_by"),
                rs.getString("extraction_version")
        ), workspaceId, conversationId, cutoffSeq, cutoffSeq);
    }

    /**
     * 请求冻结时读取的账本：删除消息不携带正文，进行中/失败/取消的助手回合按状态投影成占位或句柄。
     * 这是冻结输入的一部分，后续完成的新内容不会写回本次上下文。
     */
    private java.util.List<com.noteweave.context.ConversationLedgerEntry> loadConversationLedger(
            String workspaceId, String conversationId, int cutoffSeq) {
        return jdbcTemplate.query("""
                select m.id, m.message_seq, m.role, coalesce(m.answer_mode, '') as answer_mode,
                       coalesce(m.context_status, 'CURRENT') as context_status,
                       case when m.context_status = 'DELETED' then '' else m.content end as content,
                       coalesce(r.status, '') as run_status
                from conversation_message m
                left join answer_run r on r.answer_message_id = m.id and r.workspace_id = m.workspace_id
                where m.workspace_id = ? and m.conversation_id = ? and m.message_seq <= ?
                order by m.message_seq asc
                """, (rs, rowNum) -> new com.noteweave.context.ConversationLedgerEntry(
                rs.getString("id"),
                rs.getInt("message_seq"),
                rs.getString("role"),
                rs.getString("answer_mode"),
                ledgerStatus(rs.getString("context_status"), rs.getString("role"),
                        rs.getString("run_status")),
                ledgerText(rs.getString("context_status"), rs.getString("run_status"),
                        rs.getString("content")),
                "", "", ""
        ), workspaceId, conversationId, cutoffSeq);
    }

    private String ledgerStatus(String contextStatus, String role, String runStatus) {
        if ("DELETED".equals(contextStatus)) {
            return com.noteweave.context.ConversationLedgerEntry.STATUS_DELETED;
        }
        if (!"ASSISTANT".equals(role)) {
            return com.noteweave.context.ConversationLedgerEntry.STATUS_CURRENT;
        }
        return switch (runStatus == null ? "" : runStatus) {
            case "CANCELLED" -> com.noteweave.context.ConversationLedgerEntry.STATUS_CANCELLED;
            case "FAILED", "REVOKED" -> com.noteweave.context.ConversationLedgerEntry.STATUS_FAILED;
            case "CREATED", "RETRIEVING", "PREPARING", "GENERATING", "FINALIZING" ->
                    com.noteweave.context.ConversationLedgerEntry.STATUS_PENDING;
            default -> com.noteweave.context.ConversationLedgerEntry.STATUS_CURRENT;
        };
    }

    private String ledgerText(String contextStatus, String runStatus, String content) {
        if ("DELETED".equals(contextStatus) || "FAILED".equals(runStatus)
                || "REVOKED".equals(runStatus) || "CANCELLED".equals(runStatus)
                || "CREATED".equals(runStatus) || "RETRIEVING".equals(runStatus)
                || "PREPARING".equals(runStatus) || "GENERATING".equals(runStatus)
                || "FINALIZING".equals(runStatus)) {
            return "";
        }
        return content == null ? "" : content;
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
            int maximumOutputTokens,
            String evidenceSufficiency,
            String taskParameters,
            String taskParametersUnsupported,
            String taskParametersMemoryRefs
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
            int maximumOutputTokens,
            String evidenceSufficiency,
            String taskParameters,
            String taskParametersUnsupported,
            String taskParametersMemoryRefs
    ) {
        public PreparedAnswerMaterial(
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
            this(answer, existingCitationIds, chatControlPack, retrievalPlan, evidenceBundle, promptSpec,
                    contextProjection, frozenContextV2, maximumOutputTokens, null, null, null, null);
        }

        public PreparedAnswerMaterial(
                String answer,
                List<String> existingCitationIds,
                MemoryControlPackResponse chatControlPack,
                RetrievalPlan retrievalPlan,
                EvidenceBundle evidenceBundle,
                PromptSpec promptSpec,
                ConversationContextProjectionService.Projection contextProjection,
                ContextV2ShadowSnapshotService.FrozenAnswer frozenContextV2,
                int maximumOutputTokens,
                String evidenceSufficiency
        ) {
            this(answer, existingCitationIds, chatControlPack, retrievalPlan, evidenceBundle, promptSpec,
                    contextProjection, frozenContextV2, maximumOutputTokens, evidenceSufficiency, null, null, null);
        }

        public PreparedAnswerMaterial(
                String answer,
                List<String> existingCitationIds,
                MemoryControlPackResponse chatControlPack,
                RetrievalPlan retrievalPlan,
                EvidenceBundle evidenceBundle,
                PromptSpec promptSpec,
                ConversationContextProjectionService.Projection contextProjection,
                ContextV2ShadowSnapshotService.FrozenAnswer frozenContextV2,
                int maximumOutputTokens,
                String evidenceSufficiency,
                String taskParameters,
                String taskParametersUnsupported
        ) {
            this(answer, existingCitationIds, chatControlPack, retrievalPlan, evidenceBundle, promptSpec,
                    contextProjection, frozenContextV2, maximumOutputTokens, evidenceSufficiency,
                    taskParameters, taskParametersUnsupported, null);
        }

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
