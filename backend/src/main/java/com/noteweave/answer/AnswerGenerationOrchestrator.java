package com.noteweave.answer;

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
    private final ScheduledExecutorService eventScheduler;
    private final ScheduledExecutorService leaseScheduler;

    public AnswerGenerationOrchestrator(
            AnswerRunService answerRunService,
            AnswerCancellationRegistry cancellationRegistry,
            SessionEventMux runEventMux,
            ConversationEventMux conversationEventMux,
            AnswerGenerationGateway generationGateway,
            @Qualifier("answerEventScheduler") ScheduledExecutorService eventScheduler,
            @Qualifier("answerLeaseScheduler") ScheduledExecutorService leaseScheduler
    ) {
        this.answerRunService = answerRunService;
        this.cancellationRegistry = cancellationRegistry;
        this.runEventMux = runEventMux;
        this.conversationEventMux = conversationEventMux;
        this.generationGateway = generationGateway;
        this.eventScheduler = eventScheduler;
        this.leaseScheduler = leaseScheduler;
    }

    public void stream(String assistantRequestId, Consumer<AnswerDeliveryEvent> consumer) {
        AnswerGenerationMaterial material = generationGateway.load(assistantRequestId);
        AnswerRunRef run = answerRunService.requireByAssistantRequest(assistantRequestId);
        if (run != null && "COMPLETED".equals(run.status())) {
            replayCompleted(material, consumer);
            return;
        }
        if (run != null && ("FAILED".equals(run.status()) || "CANCELLED".equals(run.status()))) {
            deliver(consumer, new AnswerDeliveryEvent(
                    "1",
                    "CANCELLED".equals(run.status()) ? "answer.cancelled" : "answer.failed",
                    run.status()));
            return;
        }

        String streamOwner;
        try {
            streamOwner = run == null ? null : answerRunService.claimStream(run.workspaceId(), run.runId());
        } catch (com.noteweave.common.BusinessException ex) {
            if (run == null || !"ANSWER_RUN_ALREADY_STREAMING".equals(ex.code())) {
                throw ex;
            }
            followExistingGeneration(material, run, consumer);
            return;
        }
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
            streamed = generationGateway.generate(
                    generationGateway.prepareDraft(material.content()),
                    material.maximumOutputTokens(),
                    deltaEmitter::accept);
            deltaEmitter.flush();
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
                answerRunService.advanceEventSequence(
                        run.workspaceId(), run.runId(), runEventMux.currentSequence(run.runId()));
                answerRunService.fail(run.workspaceId(), run.runId(), streamOwner,
                        "ANSWER_STREAM_FAILED", ex.getMessage());
                AnswerLiveEvent terminal = publishTerminal(run, "answer.failed", ex.getMessage());
                emitTerminal(consumer, fallbackSequence, terminal, "answer.failed",
                        "回答流式调用失败：" + ex.getMessage());
                return;
            }
            emit(consumer, fallbackSequence, "answer.failed", "回答流式调用失败：" + ex.getMessage(), null);
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
        for (String citation : material.citationLines()) {
            emit(consumer, fallbackSequence, "citation.upsert", citation, run);
        }
        generationGateway.persistContent(material.workspaceId(), material.messageId(), streamed);
        if (run == null) {
            emit(consumer, fallbackSequence, "answer.completed", material.messageId(), null);
            return;
        }

        answerRunService.advanceEventSequence(
                run.workspaceId(), run.runId(), runEventMux.currentSequence(run.runId()));
        answerRunService.persistCitationEvents(run.workspaceId(), run.runId(), material.citationLines());
        AnswerRunService.CompletionOutcome completionOutcome = answerRunService.complete(
                run.workspaceId(), run.runId(), streamOwner, streamed == null ? "" : streamed, null);
        if (completionOutcome == AnswerRunService.CompletionOutcome.CANCELLED) {
            persistAndEmitCancellation(run, streamOwner, fallbackSequence, consumer);
            return;
        }
        if (completionOutcome == AnswerRunService.CompletionOutcome.FAILED) {
            AnswerLiveEvent terminal = publishTerminal(run, "answer.failed", "FAILED");
            emitTerminal(consumer, fallbackSequence, terminal, "answer.failed", "回答生成失败");
            return;
        }
        cancellationRegistry.clear(run.runId());
        AnswerLiveEvent terminal = publishTerminal(run, "answer.completed", material.messageId());
        emitTerminal(consumer, fallbackSequence, terminal, "answer.completed", material.messageId());
    }

    public void streamRun(String workspaceId, String runId, Consumer<AnswerDeliveryEvent> consumer) {
        AnswerRunRef run = answerRunService.requireRef(workspaceId, runId);
        stream(run.assistantRequestId(), consumer);
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
            Consumer<AnswerDeliveryEvent> consumer
    ) {
        AtomicLong sequence = new AtomicLong();
        if (material.content() == null || material.content().isBlank()) {
            emit(consumer, sequence, "answer.failed", "ANSWER_LLM_EMPTY_RESPONSE", null);
            return;
        }
        generationGateway.replayChunks(material.content())
                .forEach(piece -> emit(consumer, sequence, "answer.delta", piece, null));
        material.citationLines().forEach(citation ->
                emit(consumer, sequence, "citation.upsert", citation, null));
        emit(consumer, sequence, "answer.completed", material.messageId(), null);
    }

    private void followExistingGeneration(
            AnswerGenerationMaterial material,
            AnswerRunRef run,
            Consumer<AnswerDeliveryEvent> consumer
    ) {
        AnswerRunRef current = answerRunService.requireRef(run.workspaceId(), run.runId());
        if ("COMPLETED".equals(current.status())) {
            replayCompleted(material, consumer);
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
