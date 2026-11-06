package com.noteweave.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.AnswerGenerationOrchestrator;
import com.noteweave.answer.AnswerLiveEvent;
import com.noteweave.answer.AnswerRunRef;
import com.noteweave.answer.AnswerRunService;
import com.noteweave.answer.ConversationEventMux;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Json;
import com.noteweave.conversation.ConversationTurnModule;
import com.noteweave.conversation.SubmitTurnCommand;
import com.noteweave.conversation.TurnReceipt;
import com.noteweave.security.CurrentUserProvider;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 发消息的调度：决定回答准备在请求线程里同步完成，还是创建 PREPARING 运行后立即返回、在后台准备。
 *
 * <p>后台准备时，准入检查（权限、限流、检索参数组合）仍在请求线程里执行，错误码与同步提交一致；
 * 查询改写、检索和证据判定放到后台，过程中通过会话事件流推送 {@code answer.stage}，
 * 前端据此显示理解问题、检索到几条资料、正在撰写。准备失败时推送 {@code answer.failed}。</p>
 */
@Service
public class AnswerTurnDispatcher {

    static final String STAGE_EVENT = "answer.stage";
    private static final Logger log = LoggerFactory.getLogger(AnswerTurnDispatcher.class);

    private final ConversationTurnModule turnModule;
    private final ChatService chatService;
    private final AnswerRunService answerRunService;
    private final AnswerGenerationOrchestrator orchestrator;
    private final ConversationEventMux conversationEventMux;
    private final CurrentUserProvider currentUserProvider;
    private final ObjectMapper objectMapper;
    private final Executor preparationExecutor;
    private final Executor answerIoExecutor;
    private final boolean asyncPreparation;

    public AnswerTurnDispatcher(
            ConversationTurnModule turnModule,
            ChatService chatService,
            AnswerRunService answerRunService,
            AnswerGenerationOrchestrator orchestrator,
            ConversationEventMux conversationEventMux,
            CurrentUserProvider currentUserProvider,
            ObjectMapper objectMapper,
            @Qualifier("answerPreparationExecutor") Executor preparationExecutor,
            @Qualifier("answerIoExecutor") Executor answerIoExecutor,
            @Value("${noteweave.answer.async-preparation:true}") boolean asyncPreparation
    ) {
        this.turnModule = turnModule;
        this.chatService = chatService;
        this.answerRunService = answerRunService;
        this.orchestrator = orchestrator;
        this.conversationEventMux = conversationEventMux;
        this.currentUserProvider = currentUserProvider;
        this.objectMapper = objectMapper;
        this.preparationExecutor = preparationExecutor;
        this.answerIoExecutor = answerIoExecutor;
        this.asyncPreparation = asyncPreparation;
    }

    public TurnReceipt submit(SubmitTurnCommand command) {
        if (!asyncPreparation) {
            TurnReceipt receipt = turnModule.submitNewTurn(command);
            startGeneration(receipt);
            return receipt;
        }
        long acceptStarted = System.nanoTime();
        // 会话上下文冻结（话题切分嵌入、约束抽取）放到后台准备里做，接口只写库和做准入检查
        ConversationTurnModule.TurnStart start = turnModule.beginNewTurnDeferringContext(command);
        ConversationTurnModule.PendingAnswerPreparation pending = start.pending();
        if (pending == null) {
            // 研究回合或重复提交：与同步路径一样，问答运行若还没开始就补一次启动
            startGeneration(start.receipt());
            return start.receipt();
        }
        long turnCreated = System.nanoTime();
        turnModule.admitAnswerPreparation(pending);
        long admitted = System.nanoTime();
        String userId = currentUserProvider.currentUserId().orElse(null);
        try {
            // 日志上下文（请求号、工作区等）带到后台线程：日志可追踪，模型调用的 usage 也能按工作区归属
            java.util.Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();
            preparationExecutor.execute(() -> {
                java.util.Map<String, String> previous = org.slf4j.MDC.getCopyOfContextMap();
                if (logContext != null) {
                    org.slf4j.MDC.setContextMap(logContext);
                }
                try {
                    CurrentUserProvider.runAs(userId, () -> prepareInBackground(pending));
                } finally {
                    if (previous == null) {
                        org.slf4j.MDC.clear();
                    } else {
                        org.slf4j.MDC.setContextMap(previous);
                    }
                }
            });
        } catch (RejectedExecutionException saturated) {
            // 准备线程池已满时退回请求线程同步准备，用户只是等得久一些，消息不会丢
            log.warn("answer preparation executor saturated, preparing inline runId={}",
                    pending.receipt().answerRunId());
            prepareInBackground(pending);
        }
        log.info("answer accept timing runId={} create_turn_ms={} admission_ms={} total_ms={}",
                pending.receipt().answerRunId(),
                (turnCreated - acceptStarted) / 1_000_000L,
                (admitted - turnCreated) / 1_000_000L,
                (System.nanoTime() - acceptStarted) / 1_000_000L);
        return start.receipt();
    }

    private void prepareInBackground(ConversationTurnModule.PendingAnswerPreparation pending) {
        TurnReceipt receipt = pending.receipt();
        AnswerPreparationListener stages = (stage, data) -> publishStage(pending, stage, data);
        try {
            turnModule.completeAdmittedPreparation(pending, stages);
        } catch (RuntimeException failure) {
            publishFailure(pending, failure);
            return;
        }
        stages.stage(AnswerPreparationListener.STAGE_WRITING, Map.of());
        AnswerRunRef run = answerRunService.requireRef(pending.workspaceId(), receipt.answerRunId());
        try {
            answerIoExecutor.execute(() -> startRun(run));
        } catch (RejectedExecutionException saturated) {
            // 生成线程池已满时就在准备线程里生成，避免运行停在 GENERATING 却没人认领
            startRun(run);
        }
    }

    private void startGeneration(TurnReceipt receipt) {
        if (!"ANSWER".equals(receipt.executionKind())) {
            return;
        }
        AnswerRunRef run = answerRunService.requireByAssistantRequest(receipt.assistantRequestId());
        if (run == null || "PREPARING".equals(run.status())) {
            // 仍在准备的运行由准备线程在完成后启动，这里再启动只会占用一个线程空等
            return;
        }
        try {
            answerIoExecutor.execute(() -> startRun(run));
        } catch (RejectedExecutionException ignored) {
            // The canonical SSE endpoint can still recover a run when the execution queue is saturated.
        }
    }

    private void startRun(AnswerRunRef run) {
        try {
            chatService.startRun(run.workspaceId(), run.runId());
        } catch (BusinessException ex) {
            if (!"ANSWER_RUN_ALREADY_STREAMING".equals(ex.code())) {
                log.warn("answer run start failed runId={} code={}", run.runId(), ex.code());
            }
        } catch (RuntimeException ex) {
            log.warn("answer run start failed runId={}", run.runId(), ex);
        }
    }

    private void publishStage(
            ConversationTurnModule.PendingAnswerPreparation pending,
            String stage,
            Map<String, Object> data
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        if (data != null) {
            payload.putAll(data);
        }
        try {
            conversationEventMux.publish(
                    pending.conversationId(),
                    pending.receipt().answerRunId(),
                    new AnswerLiveEvent(0L, STAGE_EVENT, Json.write(objectMapper, payload), Instant.now()));
        } catch (RuntimeException ex) {
            // 阶段提示只用于展示，推送失败不影响回答
            log.debug("answer stage publish failed runId={} stage={}: {}",
                    pending.receipt().answerRunId(), stage, ex.getMessage());
        }
    }

    private void publishFailure(ConversationTurnModule.PendingAnswerPreparation pending, RuntimeException failure) {
        String code = failure instanceof BusinessException business ? business.code() : "TURN_PREPARATION_FAILED";
        log.warn("background answer preparation failed runId={} code={}", pending.receipt().answerRunId(), code);
        try {
            orchestrator.publishPreparationFailure(pending.workspaceId(), pending.receipt().answerRunId());
        } catch (RuntimeException ex) {
            log.warn("answer preparation failure could not be published runId={}",
                    pending.receipt().answerRunId(), ex);
        }
    }
}
