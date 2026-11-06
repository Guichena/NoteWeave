package com.noteweave.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.generation.CitationMarkerNormalizer;
import com.noteweave.answer.generation.GenerationContractGuard;
import com.noteweave.common.Json;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AnswerGenerationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnswerGenerationOrchestrator.class);

    private final AnswerRunService answerRunService;
    private final AnswerCancellationRegistry cancellationRegistry;
    private final SessionEventMux runEventMux;
    private final ConversationEventMux conversationEventMux;
    private final AnswerGenerationGateway generationGateway;
    private final GenerationContractGuard contractGuard;
    private final ScheduledExecutorService eventScheduler;
    private final ScheduledExecutorService leaseScheduler;
    private final ObjectMapper objectMapper;

    public AnswerGenerationOrchestrator(
            AnswerRunService answerRunService,
            AnswerCancellationRegistry cancellationRegistry,
            SessionEventMux runEventMux,
            ConversationEventMux conversationEventMux,
            AnswerGenerationGateway generationGateway,
            GenerationContractGuard contractGuard,
            @Qualifier("answerEventScheduler") ScheduledExecutorService eventScheduler,
            @Qualifier("answerLeaseScheduler") ScheduledExecutorService leaseScheduler,
            ObjectMapper objectMapper
    ) {
        this.answerRunService = answerRunService;
        this.cancellationRegistry = cancellationRegistry;
        this.runEventMux = runEventMux;
        this.conversationEventMux = conversationEventMux;
        this.generationGateway = generationGateway;
        this.contractGuard = contractGuard;
        this.eventScheduler = eventScheduler;
        this.leaseScheduler = leaseScheduler;
        this.objectMapper = objectMapper;
    }

    public void stream(String assistantRequestId, Consumer<AnswerDeliveryEvent> consumer) {
        java.util.concurrent.atomic.AtomicInteger emitted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger bodyEvents = new java.util.concurrent.atomic.AtomicInteger();
        Consumer<AnswerDeliveryEvent> counting = event -> {
            emitted.incrementAndGet();
            consumer.accept(event);
            if ("answer.delta".equals(event.eventType()) || "answer.completed".equals(event.eventType())) {
                bodyEvents.incrementAndGet();
            }
        };
        try {
            streamInternal(assistantRequestId, counting, emitted, bodyEvents);
        } finally {
            log.debug("stream finished requestId={} emitted={}", assistantRequestId, emitted.get());
        }
    }

    private void streamInternal(
            String assistantRequestId,
            Consumer<AnswerDeliveryEvent> consumer,
            java.util.concurrent.atomic.AtomicInteger emitted,
            java.util.concurrent.atomic.AtomicInteger bodyEvents
    ) {
        AnswerRunRef run = answerRunService.requireByAssistantRequest(assistantRequestId);
        log.debug("stream begin requestId={} runId={} status={}",
                assistantRequestId, run == null ? null : run.runId(), run == null ? null : run.status());
        if (run != null && ("FAILED".equals(run.status()) || "CANCELLED".equals(run.status()))) {
            deliver(consumer, new AnswerDeliveryEvent("", "CANCELLED".equals(run.status())
                    ? "answer.cancelled" : "answer.failed", terminalData(run)));
            return;
        }
        if (run != null) answerRunService.requireReplayReadable(run.workspaceId(), run.runId());
        AnswerGenerationMaterial material = generationGateway.load(assistantRequestId);
        if (run != null && "COMPLETED".equals(run.status())) {
            log.debug("stream branch=replay-completed runId={} contentLen={}",
                    run.runId(), material.content() == null ? -1 : material.content().length());
            replayCompleted(material, run, consumer);
            return;
        }
        if (run != null && ("FAILED".equals(run.status()) || "CANCELLED".equals(run.status()))) {
            deliver(consumer, new AnswerDeliveryEvent(
                    "1",
                    "CANCELLED".equals(run.status()) ? "answer.cancelled" : "answer.failed",
                    terminalData(run)));
            return;
        }

        String streamOwner;
        try {
            streamOwner = run == null ? null : answerRunService.claimStream(run.workspaceId(), run.runId());
        } catch (com.noteweave.common.BusinessException ex) {
            if (run == null || !"ANSWER_RUN_ALREADY_STREAMING".equals(ex.code())) {
                throw ex;
            }
            log.debug("stream branch=follow-existing runId={}", run.runId());
            followExistingGeneration(material, run, consumer);
            return;
        }
        if (run != null && streamOwner == null) {
            // 认领失败说明运行已被其他流接管或已经收尾，此时进入生成分支必然抛撤销
            log.debug("stream branch=claim-null runId={}", run.runId());
            followExistingGeneration(material, run, consumer);
            return;
        }
        log.debug("stream branch=claim runId={} owner={}", run == null ? null : run.runId(), streamOwner);
        if (run != null) {
            runEventMux.seed(run.runId(), answerRunService.currentEventSequence(run.workspaceId(), run.runId()));
        }
        AtomicLong fallbackSequence = new AtomicLong();
        AtomicBoolean firstToken = new AtomicBoolean();
        AtomicBoolean leaseValid = new AtomicBoolean(true);
        ScheduledFuture<?> leaseRenewal = startLeaseRenewal(run, streamOwner, leaseValid);
        String streamed;
        try (BufferedAnswerDeltaEmitter deltaEmitter = new BufferedAnswerDeltaEmitter(
                eventScheduler,
                batch -> emitDelta(consumer, fallbackSequence, batch, run, streamOwner, firstToken, leaseValid))) {
            String draft = generationGateway.prepareDraft(material.content());
            // 冻结与生成之间证据可能失效：调用模型之前按冻结契约复核，失效时整个运行失败
            if (run != null) {
                answerRunService.requireStreamEligible(run.workspaceId(), run.runId(), streamOwner);
                contractGuard.requireEligible(run.workspaceId(), run.runId(), material.contract());
            }
            // 引用绑定必须先于首个正文增量下发，前端角标才能从第一段正文起按绑定渲染；
            // 放在契约复核之后，避免替一次注定失败的运行广播绑定
            emitEvidenceReady(consumer, fallbackSequence, material, run, leaseValid);
            // 越界编号与未选页面名在这里改成普通文字；流式、返回值与落库都用同一份规范文本
            CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(material.contract());
            StringBuilder normalized = new StringBuilder();
            AtomicBoolean receivedCallback = new AtomicBoolean();
            long generationStarted = System.nanoTime();
            Consumer<String> sink = token -> {
                if (token != null && !token.isEmpty()) {
                    if (receivedCallback.compareAndSet(false, true)) {
                        // 模型首字耗时：从发起生成请求到收到第一段正文，用于和准备阶段计时拼出完整首字时间
                        log.info("answer generation first token runId={} first_token_ms={}",
                                run == null ? null : run.runId(), (System.nanoTime() - generationStarted) / 1_000_000L);
                    }
                    receivedCallback.set(true);
                }
                String safe = normalizer.accept(token);
                if (!safe.isEmpty()) {
                    normalized.append(safe);
                    deltaEmitter.accept(safe);
                }
            };
            String returned;
            if (run == null) {
                returned = generationGateway.generate(
                        material.contract(), draft, material.maximumOutputTokens(), sink);
            } else {
                returned = generationGateway.generateInConversation(
                        run.workspaceId(), run.conversationId(), material.contract(), draft,
                        material.maximumOutputTokens(), sink);
            }
            // 仅返回整段正文的适配器也必须经过同一个规范化入口；已有回调时不重复追加返回值。
            if (!receivedCallback.get() && returned != null && !returned.isBlank()) {
                sink.accept(returned);
            }
            String tail = normalizer.flush();
            if (!tail.isEmpty()) {
                normalized.append(tail);
                deltaEmitter.accept(tail);
            }
            deltaEmitter.flush();
            // 正文生成结束就关闭发射器，收尾阶段的迟到批量下发不再可能触发撤销判定
            deltaEmitter.close();
            streamed = normalized.toString();
            if (run != null) {
                answerRunService.recordCitationNormalization(run.workspaceId(), run.runId(), streamOwner,
                        normalizer.outOfRangeNumbers(), normalizer.unknownPages());
            }
        } catch (AnswerRunCancelledException ex) {
            persistAndEmitCancellation(run, streamOwner, fallbackSequence, consumer);
            return;
        } catch (AnswerStreamLeaseLostException ex) {
            if (run != null) {
                followExistingGeneration(material, run, consumer);
            }
            return;
        } catch (Exception ex) {
            if (run != null) {
                AnswerRunRef current = answerRunService.requireRef(run.workspaceId(), run.runId());
                String currentStatus = current == null ? null : current.status();
                if ("COMPLETED".equals(currentStatus)) {
                    // 收尾已经成功，迟到的批量下发异常不能改写结果；
                    // 但本订阅一个事件都没发出时，客户端会拿到空流，必须补一次完成回放
                    if (bodyEvents.get() == 0) {
                        log.warn("Recover empty stream after completion: {}", ex.getMessage());
                        AnswerGenerationMaterial finalMaterial = generationGateway.load(assistantRequestId);
                        replayCompleted(finalMaterial == null ? material : finalMaterial, run, consumer);
                    } else {
                        log.warn("Ignore generation error after completion: {}", ex.getMessage());
                    }
                    return;
                }
                if ("CANCELLED".equals(currentStatus)) {
                    persistAndEmitCancellation(run, streamOwner, fallbackSequence, consumer);
                    return;
                }
            }
            String errorCode = ex instanceof com.noteweave.common.BusinessException business
                    ? business.code() : "ANSWER_STREAM_FAILED";
            String terminalMessage = errorCode + ": " + (ex.getMessage() == null ? "" : ex.getMessage());
            if (run != null) {
                answerRunService.advanceEventSequence(
                        run.workspaceId(), run.runId(), runEventMux.currentSequence(run.runId()));
                answerRunService.fail(run.workspaceId(), run.runId(), streamOwner,
                        errorCode, ex.getMessage());
                AnswerLiveEvent terminal = publishTerminal(run, "answer.failed", terminalMessage);
                emitTerminal(consumer, fallbackSequence, terminal, "answer.failed", terminalMessage);
                return;
            }
            emit(consumer, fallbackSequence, "answer.failed", terminalMessage, null);
            return;
        } finally {
            if (leaseRenewal != null) {
                leaseRenewal.cancel(false);
            }
        }

        if (!leaseValid.get()) {
            followExistingGeneration(material, run, consumer);
            return;
        }
        if (run != null && cancellationRegistry.isCancelled(run.runId())) {
            persistAndEmitCancellation(run, streamOwner, fallbackSequence, consumer);
            return;
        }
        if (streamed == null || streamed.isBlank()) {
            if (run == null) {
                emit(consumer, fallbackSequence, "answer.failed", "ANSWER_LLM_EMPTY_RESPONSE", null);
                return;
            }
            answerRunService.advanceEventSequence(
                    run.workspaceId(), run.runId(), runEventMux.currentSequence(run.runId()));
            answerRunService.fail(
                    run.workspaceId(), run.runId(), streamOwner,
                    "ANSWER_LLM_EMPTY_RESPONSE", "Answer generation produced no content");
            AnswerLiveEvent terminal = publishTerminal(run, "answer.failed", "ANSWER_LLM_EMPTY_RESPONSE");
            emitTerminal(consumer, fallbackSequence, terminal, "answer.failed", "ANSWER_LLM_EMPTY_RESPONSE");
            return;
        }
        if (run == null) {
            for (String citation : material.citationLines()) {
                emit(consumer, fallbackSequence, "citation.upsert", citation, null);
            }
            // 没有运行记录的旧路径自己写回；有运行记录时正文写回并入 complete 的同一事务
            generationGateway.persistContent(material.workspaceId(), material.messageId(), streamed);
            emit(consumer, fallbackSequence, "answer.completed", material.messageId(), null);
            return;
        }

        try {
            for (String citation : material.citationLines()) {
                answerRunService.requireStreamEligible(run.workspaceId(), run.runId(), streamOwner);
                emit(consumer, fallbackSequence, "citation.upsert", citation, run);
            }
            answerRunService.advanceEventSequence(
                    run.workspaceId(), run.runId(), runEventMux.currentSequence(run.runId()));
            answerRunService.persistCitationEvents(run.workspaceId(), run.runId(), material.citationLines());
            contractGuard.requireEligible(run.workspaceId(), run.runId(), material.contract());
            AnswerRunService.CompletionOutcome completionOutcome = answerRunService.complete(
                    run.workspaceId(), run.runId(), streamOwner, streamed == null ? "" : streamed, null);
            if (completionOutcome == AnswerRunService.CompletionOutcome.SUPERSEDED) {
                // 租约已被别的流接管：收尾归当前持有者，本流转去跟随，不写任何状态
                followExistingGeneration(material, run, consumer);
                return;
            }
            if (completionOutcome == AnswerRunService.CompletionOutcome.CANCELLED) {
                persistAndEmitCancellation(run, streamOwner, fallbackSequence, consumer);
                return;
            }
            if (completionOutcome == AnswerRunService.CompletionOutcome.FAILED) {
                AnswerLiveEvent failed = publishTerminal(run, "answer.failed", "FAILED");
                emitTerminal(consumer, fallbackSequence, failed, "answer.failed", "回答生成失败");
                return;
            }
            cancellationRegistry.clear(run.runId());
            AnswerLiveEvent terminal = publishTerminal(run, "answer.completed", material.messageId());
            emitTerminal(consumer, fallbackSequence, terminal, "answer.completed", material.messageId());
        } catch (RuntimeException ex) {
            // 运行已经完成时，收尾阶段的迟到异常不能把结果改写成失败
            String currentStatus = answerRunService.requireRef(run.workspaceId(), run.runId()).status();
            if ("COMPLETED".equals(currentStatus)) {
                log.warn("Ignore finalization error after completion: {}", ex.getMessage());
                return;
            }
            if ("CANCELLED".equals(currentStatus)) {
                emitTerminal(consumer, fallbackSequence, null, "answer.cancelled", "CANCELLED");
                return;
            }
            // ANS-01：收尾失败必须显式落 FAILED 并发出终态事件，
            // 不能让运行停在 GENERATING、把客户端晾在打开的流上。
            failFinalization(run, streamOwner, ex);
            String errorCode = ex instanceof com.noteweave.common.BusinessException business
                    ? business.code() : "ANSWER_FINALIZE_FAILED";
            AnswerLiveEvent failed = publishTerminal(run, "answer.failed", errorCode);
            emitTerminal(consumer, fallbackSequence, failed, "answer.failed",
                    errorCode + ": " + (ex.getMessage() == null ? "" : ex.getMessage()));
        }
    }

    /** 收尾异常落库；运行已经是终态时以既有状态为准，不覆盖也不上抛。 */
    private void failFinalization(AnswerRunRef run, String streamOwner, RuntimeException failure) {
        try {
            String errorCode = failure instanceof com.noteweave.common.BusinessException business
                    ? business.code() : "ANSWER_FINALIZE_FAILED";
            answerRunService.fail(run.workspaceId(), run.runId(), streamOwner, errorCode, failure.getMessage());
        } catch (RuntimeException ignored) {
            log.warn("回答收尾失败时无法标记运行失败: run={}, cause={}", run.runId(), failure.toString());
        }
    }

    public void streamRun(String workspaceId, String runId, Consumer<AnswerDeliveryEvent> consumer) {
        AnswerRunRef run = answerRunService.requireRef(workspaceId, runId);
        stream(run.assistantRequestId(), consumer);
    }

    /**
     * 后台回答准备失败：运行已经标成 FAILED，这里把终态推给会话流和运行流上的订阅者。
     * 没有这一步，发消息接口已经返回的前端只能等到超时后查快照才知道失败。
     */
    public void publishPreparationFailure(String workspaceId, String runId) {
        AnswerRunRef run = answerRunService.requireRef(workspaceId, runId);
        if (!"FAILED".equals(run.status())) {
            return;
        }
        publishTerminal(run, "answer.failed", terminalData(run));
    }

    public AnswerRunRef requireRun(String workspaceId, String runId) {
        return answerRunService.requireRef(workspaceId, runId);
    }

    public void startRun(String workspaceId, String runId) {
        streamRun(workspaceId, runId, ignored -> { });
    }

    public void followRun(
            String workspaceId,
            String runId,
            long after,
            Consumer<AnswerLiveEvent> consumer
    ) {
        answerRunService.requireRef(workspaceId, runId);
        runEventMux.seed(runId, answerRunService.currentEventSequence(workspaceId, runId));
        runEventMux.follow(runId, after, consumer, Duration.ofSeconds(125));
    }

    private void replayCompleted(
            AnswerGenerationMaterial material,
            AnswerRunRef run,
            Consumer<AnswerDeliveryEvent> consumer
    ) {
        Consumer<AnswerDeliveryEvent> guardedConsumer = event -> {
            if (run != null) answerRunService.requireReplayReadable(run.workspaceId(), run.runId());
            consumer.accept(event);
        };
        AtomicLong sequence = new AtomicLong();
        if (material.content() == null || material.content().isBlank()) {
            emit(guardedConsumer, sequence, "answer.failed", "ANSWER_LLM_EMPTY_RESPONSE", null);
            return;
        }
        // 已完成回放同样先给绑定，新订阅与重连都不必等补拉证据清单
        emitEvidenceReady(guardedConsumer, sequence, material, null, null);
        generationGateway.replayChunks(material.content())
                .forEach(piece -> emit(guardedConsumer, sequence, "answer.delta", piece, null));
        material.citationLines().forEach(citation ->
                emit(guardedConsumer, sequence, "citation.upsert", citation, null));
        emit(guardedConsumer, sequence, "answer.completed", material.messageId(), null);
    }

    private void followExistingGeneration(
            AnswerGenerationMaterial material,
            AnswerRunRef run,
            Consumer<AnswerDeliveryEvent> consumer
    ) {
        AnswerRunRef current = answerRunService.requireRef(run.workspaceId(), run.runId());
        if (current == null) {
            current = run;
        }
        if ("COMPLETED".equals(current.status())) {
            // 首次加载到的可能是生成中的草稿，完成回放必须用落库后的最终正文
            AnswerGenerationMaterial finalMaterial = generationGateway.load(run.assistantRequestId());
            replayCompleted(finalMaterial == null ? material : finalMaterial, run, consumer);
            return;
        }
        if ("FAILED".equals(current.status()) || "CANCELLED".equals(current.status())) {
            deliver(consumer, new AnswerDeliveryEvent(
                    "1",
                    "CANCELLED".equals(current.status()) ? "answer.cancelled" : "answer.failed",
                    current.status()));
            return;
        }
        followRun(run.workspaceId(), run.runId(), 0, event -> deliver(
                consumer,
                new AnswerDeliveryEvent(
                        Long.toString(event.sequence()),
                        event.eventType(),
                        event.data()
                )
        ));
    }

    /**
     * 终态事件的数据：取消回状态词，失败回具体原因。
     */
    private String terminalData(AnswerRunRef run) {
        if ("CANCELLED".equals(run.status())) {
            return "CANCELLED";
        }
        String summary = answerRunService.failureSummary(run.workspaceId(), run.runId());
        return summary.isBlank() ? run.status() : summary;
    }

    /**
     * 下发 {@code evidence.ready}：把冻结契约里的允许集合与编号绑定交给前端。
     *
     * <p>历史运行没有契约时直接跳过，前端保持旧协议解析。有运行记录时走与
     * {@code answer.delta} 相同的 mux 与序列号通道，并沿用租约与取消检查，
     * 保证它排在首个正文增量之前。</p>
     */
    private void emitEvidenceReady(
            Consumer<AnswerDeliveryEvent> consumer,
            AtomicLong fallbackSequence,
            AnswerGenerationMaterial material,
            AnswerRunRef run,
            AtomicBoolean leaseValid
    ) {
        EvidenceReadyEvent payload = EvidenceReadyEvent.from(material.contract());
        if (payload == null) {
            return;
        }
        if (leaseValid != null && !leaseValid.get()) {
            throw new AnswerStreamLeaseLostException();
        }
        ensureNotCancelled(run);
        emit(consumer, fallbackSequence, EvidenceReadyEvent.EVENT_TYPE,
                Json.write(objectMapper, payload), run);
    }

    private void emitDelta(
            Consumer<AnswerDeliveryEvent> consumer,
            AtomicLong fallbackSequence,
            String token,
            AnswerRunRef run,
            String streamOwner,
            AtomicBoolean firstToken,
            AtomicBoolean leaseValid
    ) {
        if (!leaseValid.get()) {
            throw new AnswerStreamLeaseLostException();
        }
        ensureNotCancelled(run);
        if (run != null) {
            try {
                answerRunService.requireStreamEligible(run.workspaceId(), run.runId(), streamOwner);
            } catch (com.noteweave.common.BusinessException revoked) {
                // 收尾已经把运行推进到终态时，晚到的批量下发只是迟到，不能改写已定的结果
                AnswerRunRef current = answerRunService.requireRef(run.workspaceId(), run.runId());
                String status = current == null ? null : current.status();
                if (!"GENERATING".equals(status)) {
                    // 收尾已经开始或已经结束，这批增量只是迟到，不能改写结果
                    log.debug("Drop late delta after status {}: {}", status, revoked.getMessage());
                    return;
                }
                throw revoked;
            }
        }
        if (run != null && firstToken.compareAndSet(false, true)) {
            answerRunService.markFirstToken(run.workspaceId(), run.runId(), streamOwner);
        }
        emit(consumer, fallbackSequence, "answer.delta", token, run);
    }

    private void emit(
            Consumer<AnswerDeliveryEvent> consumer,
            AtomicLong fallbackSequence,
            String legacyType,
            String data,
            AnswerRunRef run
    ) {
        String safeData = data == null ? "" : data;
        long sequence;
        if (run == null) {
            sequence = fallbackSequence.incrementAndGet();
        } else {
            AnswerLiveEvent event = runEventMux.publish(run.runId(), legacyType, safeData);
            conversationEventMux.publish(run.conversationId(), run.runId(), event);
            sequence = event.sequence();
        }
        deliver(consumer, new AnswerDeliveryEvent(Long.toString(sequence), legacyType, safeData));
    }

    private AnswerLiveEvent publishTerminal(AnswerRunRef run, String type, String data) {
        if (run == null) {
            return null;
        }
        long sequence = answerRunService.currentEventSequence(run.workspaceId(), run.runId());
        AnswerLiveEvent event = runEventMux.publishAt(run.runId(), sequence, type, data == null ? "" : data);
        conversationEventMux.publish(run.conversationId(), run.runId(), event);
        return event;
    }

    private void emitTerminal(
            Consumer<AnswerDeliveryEvent> consumer,
            AtomicLong fallbackSequence,
            AnswerLiveEvent terminal,
            String legacyType,
            String data
    ) {
        String id = terminal == null
                ? Long.toString(fallbackSequence.incrementAndGet())
                : Long.toString(terminal.sequence());
        deliver(consumer, new AnswerDeliveryEvent(id, legacyType, data == null ? "" : data));
    }

    private void deliver(Consumer<AnswerDeliveryEvent> consumer, AnswerDeliveryEvent event) {
        try {
            consumer.accept(event);
        } catch (RuntimeException ex) {
            log.debug("Answer subscriber disconnected; generation continues: {}", ex.getMessage());
        }
    }

    private void ensureNotCancelled(AnswerRunRef run) {
        if (run != null && cancellationRegistry.isCancelled(run.runId())) {
            throw new AnswerRunCancelledException();
        }
    }

    private ScheduledFuture<?> startLeaseRenewal(
            AnswerRunRef run,
            String streamOwner,
            AtomicBoolean leaseValid
    ) {
        if (run == null || streamOwner == null) {
            return null;
        }
        return leaseScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!answerRunService.renewStreamLease(run.workspaceId(), run.runId(), streamOwner)) {
                    leaseValid.set(false);
                }
            } catch (RuntimeException ex) {
                leaseValid.set(false);
                log.warn("Answer stream lease renewal failed for run {}: {}", run.runId(), ex.getMessage());
            }
        }, 45, 45, TimeUnit.SECONDS);
    }

    private void persistAndEmitCancellation(
            AnswerRunRef run,
            String streamOwner,
            AtomicLong fallbackSequence,
            Consumer<AnswerDeliveryEvent> consumer
    ) {
        if (run == null) {
            emitTerminal(consumer, fallbackSequence, null, "answer.failed", "回答已取消");
            return;
        }
        answerRunService.advanceEventSequence(
                run.workspaceId(), run.runId(), runEventMux.currentSequence(run.runId()));
        AnswerRunResponse cancelled = answerRunService.cancelFromStream(
                run.workspaceId(), run.runId(), streamOwner);
        cancellationRegistry.clear(run.runId());
        String terminalType = "CANCELLED".equals(cancelled.status())
                ? "answer.cancelled"
                : "answer." + cancelled.status().toLowerCase();
        AnswerLiveEvent terminal = publishTerminal(run, terminalType, cancelled.status());
        boolean completed = "COMPLETED".equals(cancelled.status());
        String message = "CANCELLED".equals(cancelled.status()) ? "回答已取消" : cancelled.status();
        emitTerminal(consumer, fallbackSequence, terminal,
                completed ? "answer.completed" : terminalType, message);
    }

    private static final class AnswerRunCancelledException extends RuntimeException {
    }

    private static final class AnswerStreamLeaseLostException extends RuntimeException {
    }
}
