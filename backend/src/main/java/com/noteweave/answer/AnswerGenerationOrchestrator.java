package com.noteweave.answer;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
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

    public AnswerGenerationOrchestrator(
            AnswerRunService answerRunService,
            AnswerCancellationRegistry cancellationRegistry,
            SessionEventMux runEventMux,
            ConversationEventMux conversationEventMux,
            AnswerGenerationGateway generationGateway,
            @Qualifier("answerEventScheduler") ScheduledExecutorService eventScheduler
    ) {
        this.answerRunService = answerRunService;
        this.cancellationRegistry = cancellationRegistry;
        this.runEventMux = runEventMux;
        this.conversationEventMux = conversationEventMux;
        this.generationGateway = generationGateway;
        this.eventScheduler = eventScheduler;
    }

    public void stream(String assistantRequestId, Consumer<AnswerDeliveryEvent> consumer) {
        AnswerGenerationMaterial material = generationGateway.load(assistantRequestId);
        AnswerRunRef run = answerRunService.requireByAssistantRequest(assistantRequestId);
        if (run != null && "COMPLETED".equals(run.status())) {
            replayCompleted(material, consumer);
            return;
        }
        if (run != null && ("FAILED".equals(run.status()) || "CANCELLED".equals(run.status()))) {
            deliver(consumer, new AnswerDeliveryEvent("1", "chat.failed", run.status()));
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
        String streamed;
        try (BufferedAnswerDeltaEmitter deltaEmitter = new BufferedAnswerDeltaEmitter(
                eventScheduler,
                batch -> emitDelta(consumer, fallbackSequence, batch, run, streamOwner, firstToken))) {
            streamed = generationGateway.generate(
                    generationGateway.prepareDraft(material.content()),
                    material.maximumOutputTokens(),
                    deltaEmitter::accept);
            deltaEmitter.flush();
        } catch (AnswerRunCancelledException ex) {
            AnswerLiveEvent terminal = publishTerminal(run, "answer.cancelled", "CANCELLED");
            emitTerminal(consumer, fallbackSequence, terminal, "chat.failed", "回答已取消");
            return;
        } catch (Exception ex) {
            if (run != null) {
                answerRunService.advanceEventSequence(
                        run.workspaceId(), run.runId(), runEventMux.currentSequence(run.runId()));
                answerRunService.fail(run.workspaceId(), run.runId(), streamOwner,
                        "ANSWER_STREAM_FAILED", ex.getMessage());
                AnswerLiveEvent terminal = publishTerminal(run, "answer.failed", ex.getMessage());
                emitTerminal(consumer, fallbackSequence, terminal, "chat.failed",
                        "回答流式调用失败：" + ex.getMessage());
                return;
            }
            emit(consumer, fallbackSequence, "chat.failed", "回答流式调用失败：" + ex.getMessage(), null);
            return;
        }

        if (run != null && cancellationRegistry.isCancelled(run.runId())) {
            AnswerLiveEvent terminal = publishTerminal(run, "answer.cancelled", "CANCELLED");
            emitTerminal(consumer, fallbackSequence, terminal, "chat.failed", "回答已取消");
            return;
        }
        for (String citation : material.citationLines()) {
            emit(consumer, fallbackSequence, "chat.citation", citation, run);
        }
        if (streamed != null && !streamed.isBlank()) {
            generationGateway.persistContent(material.workspaceId(), material.messageId(), streamed);
        }
        if (run == null) {
            emit(consumer, fallbackSequence, "chat.completed", material.messageId(), null);
            return;
        }

        answerRunService.advanceEventSequence(
                run.workspaceId(), run.runId(), runEventMux.currentSequence(run.runId()));
        answerRunService.persistCitationEvents(run.workspaceId(), run.runId(), material.citationLines());
        answerRunService.complete(
                run.workspaceId(), run.runId(), streamOwner, streamed == null ? "" : streamed, null);
        cancellationRegistry.clear(run.runId());
        AnswerLiveEvent terminal = publishTerminal(run, "answer.completed", material.messageId());
        emitTerminal(consumer, fallbackSequence, terminal, "chat.completed", material.messageId());
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
        generationGateway.replayChunks(material.content())
                .forEach(piece -> emit(consumer, sequence, "chat.delta", piece, null));
        material.citationLines().forEach(citation ->
                emit(consumer, sequence, "chat.citation", citation, null));
        emit(consumer, sequence, "chat.completed", material.messageId(), null);
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
            deliver(consumer, new AnswerDeliveryEvent("1", "chat.failed", current.status()));
            return;
        }
        followRun(run.workspaceId(), run.runId(), 0, event -> deliver(
                consumer,
                new AnswerDeliveryEvent(
                        Long.toString(event.sequence()),
                        legacyType(event.eventType()),
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
            AtomicBoolean firstToken
    ) {
        ensureNotCancelled(run);
        if (run != null && firstToken.compareAndSet(false, true)) {
            answerRunService.markFirstToken(run.workspaceId(), run.runId(), streamOwner);
        }
        emit(consumer, fallbackSequence, "chat.delta", token, run);
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
            AnswerLiveEvent event = runEventMux.publish(run.runId(), canonicalType(legacyType), safeData);
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

    private String canonicalType(String legacyType) {
        return switch (legacyType) {
            case "chat.delta" -> "answer.delta";
            case "chat.citation" -> "citation.upsert";
            case "chat.completed" -> "answer.completed";
            case "chat.failed" -> "answer.failed";
            default -> legacyType;
        };
    }

    private String legacyType(String canonicalType) {
        return switch (canonicalType) {
            case "answer.delta" -> "chat.delta";
            case "citation.upsert" -> "chat.citation";
            case "answer.completed" -> "chat.completed";
            case "answer.failed", "answer.cancelled" -> "chat.failed";
            default -> canonicalType;
        };
    }

    private static final class AnswerRunCancelledException extends RuntimeException {
    }
}
