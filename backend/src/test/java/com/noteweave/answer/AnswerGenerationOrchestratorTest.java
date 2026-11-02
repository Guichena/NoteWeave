package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.generation.CitationBinding;
import com.noteweave.answer.generation.GenerationContract;
import com.noteweave.answer.generation.GenerationContractGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AnswerGenerationOrchestratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void generationMustForwardPersistedConversationInsteadOfPerMessageRequestId() {
        AnswerRunService runs = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        when(runs.requireByAssistantRequest("new-request-id"))
                .thenReturn(new AnswerRunRef("answer-run", "ws", "stable-conversation", "message", "new-request-id", "RUNNING"));
        when(gateway.load("new-request-id"))
                .thenReturn(new AnswerGenerationMaterial("message", "ws", "draft", List.of(), 1200));
        when(gateway.prepareDraft("draft")).thenReturn("prepared");
        when(gateway.generateInConversation(
                org.mockito.ArgumentMatchers.eq("ws"), org.mockito.ArgumentMatchers.eq("stable-conversation"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("prepared"), org.mockito.ArgumentMatchers.eq(1200), any()))
                .thenReturn("");

        orchestrator(runs, gateway).stream("new-request-id", ignored -> {});

        verify(gateway).generateInConversation(
                org.mockito.ArgumentMatchers.eq("ws"), org.mockito.ArgumentMatchers.eq("stable-conversation"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("prepared"), org.mockito.ArgumentMatchers.eq(1200), any());
        verify(gateway, never()).generate(any(), anyString(), anyInt(), any());
    }

    @Test
    void legacyGenerationMustNotCompleteWhenProviderReturnsBlankContent() {
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        when(answerRunService.requireByAssistantRequest("request-1")).thenReturn(null);
        when(gateway.load("request-1"))
                .thenReturn(new AnswerGenerationMaterial("message-1", "workspace-1", "draft", List.of(), 100));
        when(gateway.prepareDraft("draft")).thenReturn("prepared");
        when(gateway.generate(any(), anyString(), anyInt(), any())).thenReturn("  ");

        AnswerGenerationOrchestrator orchestrator = orchestrator(answerRunService, gateway);
        List<AnswerDeliveryEvent> events = new ArrayList<>();

        orchestrator.stream("request-1", events::add);

        assertThat(events).extracting(AnswerDeliveryEvent::eventType).containsExactly("answer.failed");
        assertThat(events.get(0).data()).isEqualTo("ANSWER_LLM_EMPTY_RESPONSE");
        verify(gateway, never()).persistContent(anyString(), anyString(), anyString());
    }

    @Test
    void completedReplayMustNotEmitSuccessForBlankHistoricalContent() {
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        when(answerRunService.requireByAssistantRequest("request-2"))
                .thenReturn(new AnswerRunRef("run-2", "workspace-1", "conversation-1", "message-2", "request-2", "COMPLETED"));
        when(gateway.load("request-2"))
                .thenReturn(new AnswerGenerationMaterial("message-2", "workspace-1", "", List.of(), 100));

        AnswerGenerationOrchestrator orchestrator = orchestrator(answerRunService, gateway);
        List<AnswerDeliveryEvent> events = new ArrayList<>();

        orchestrator.stream("request-2", events::add);

        assertThat(events).extracting(AnswerDeliveryEvent::eventType).containsExactly("answer.failed");
        assertThat(events.get(0).data()).isEqualTo("ANSWER_LLM_EMPTY_RESPONSE");
        verify(gateway, never()).replayChunks(any());
    }

    @Test
    void evidenceReadyMustBeDeliveredBeforeFirstAnswerDeltaWhenContractFrozen() throws Exception {
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        stubLiveRun(answerRunService, gateway, "request-live", "answer ", "[1]");

        List<AnswerDeliveryEvent> events = new ArrayList<>();
        orchestrator(answerRunService, gateway).stream("request-live", events::add);

        List<String> types = events.stream().map(AnswerDeliveryEvent::eventType).toList();
        assertThat(types).contains("evidence.ready", "answer.delta");
        assertThat(types.indexOf("evidence.ready")).isLessThan(types.indexOf("answer.delta"));
        assertThat(types.indexOf("evidence.ready")).isZero();

        JsonNode payload = JSON.readTree(events.get(0).data());
        assertThat(payload.path("protocol_version").asText()).isEqualTo("citation-binding-v1");
        assertThat(payload.path("allowed_evidence_ids").isArray()).isTrue();
        assertThat(payload.path("allowed_evidence_ids").get(0).asText()).isEqualTo("passage:c1");
        JsonNode binding = payload.path("citation_bindings").get(0);
        assertThat(binding.path("number").asInt()).isEqualTo(1);
        assertThat(binding.path("evidence_id").asText()).isEqualTo("passage:c1");
        assertThat(binding.path("citation_ids").get(0).asText()).isEqualTo("citation-1");
    }

    @Test
    void evidenceReadyFieldNamesMustMatchFrontendParser() throws Exception {
        // 前端 parseCitationProtocolEvent 只认这三个下划线键名；改名字段会静默退回旧协议
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        stubLiveRun(answerRunService, gateway, "request-keys", "answer ", "[1]");

        List<AnswerDeliveryEvent> events = new ArrayList<>();
        orchestrator(answerRunService, gateway).stream("request-keys", events::add);

        JsonNode payload = JSON.readTree(events.get(0).data());
        assertThat(keysOf(payload)).containsExactly("protocol_version", "allowed_evidence_ids", "citation_bindings", "protocol_state");
        assertThat(payload.path("protocol_state").asText()).isEqualTo("BINDING_V1");
        assertThat(keysOf(payload.path("citation_bindings").get(0)))
                .containsExactly("number", "evidence_id", "citation_ids");
    }

    @Test
    void legacyRunWithoutContractMustNotEmitEvidenceReady() {
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        when(answerRunService.requireByAssistantRequest("request-legacy")).thenReturn(null);
        when(gateway.load("request-legacy"))
                .thenReturn(new AnswerGenerationMaterial("message-1", "workspace-1", "draft", List.of(), 100));
        when(gateway.prepareDraft("draft")).thenReturn("prepared");
        when(gateway.generate(any(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<String> sink = invocation.getArgument(3);
                    sink.accept("旧回答 [1]");
                    return "旧回答 [1]";
                });

        List<AnswerDeliveryEvent> events = new ArrayList<>();
        orchestrator(answerRunService, gateway).stream("request-legacy", events::add);

        assertThat(events).extracting(AnswerDeliveryEvent::eventType)
                .contains("answer.delta")
                .doesNotContain("evidence.ready");
    }

    @Test
    void completedReplayMustEmitEvidenceReadyBeforeReplayedDeltas() {
        AnswerRunService answerRunService = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        when(answerRunService.requireByAssistantRequest("request-replay"))
                .thenReturn(new AnswerRunRef("run-replay", "workspace-1", "conversation-1", "message-2", "request-replay", "COMPLETED"));
        when(gateway.load("request-replay")).thenReturn(new AnswerGenerationMaterial(
                "message-2", "workspace-1", "已完成正文 [1]", List.of("标题 | 摘录"), 100, contract()));
        when(gateway.replayChunks("已完成正文 [1]")).thenReturn(List.of("已完成", "正文 [1]"));

        List<AnswerDeliveryEvent> events = new ArrayList<>();
        orchestrator(answerRunService, gateway).stream("request-replay", events::add);

        List<String> types = events.stream().map(AnswerDeliveryEvent::eventType).toList();
        assertThat(types.indexOf("evidence.ready")).isZero();
        assertThat(types.indexOf("evidence.ready")).isLessThan(types.indexOf("answer.delta"));
        assertThat(types).contains("answer.completed");
    }

    @Test
    void returnOnlyAdapterMustEmitAndPersistItsCanonicalText() {
        AnswerRunService runs = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        when(gateway.load("return-only"))
                .thenReturn(new AnswerGenerationMaterial("message", "workspace", "draft", List.of(), 100));
        when(gateway.prepareDraft("draft")).thenReturn("prepared");
        when(gateway.generate(any(), anyString(), anyInt(), any())).thenReturn("仅返回完整正文");
        List<AnswerDeliveryEvent> events = new ArrayList<>();
        orchestrator(runs, gateway).stream("return-only", events::add);
        assertThat(events.stream().filter(event -> event.eventType().equals("answer.delta"))
                .map(AnswerDeliveryEvent::data).collect(java.util.stream.Collectors.joining()))
                .isEqualTo("仅返回完整正文");
        verify(gateway).persistContent("workspace", "message", "仅返回完整正文");
        assertThat(events).extracting(AnswerDeliveryEvent::eventType).contains("answer.completed");
    }

    @Test
    void revokedEvidenceMustPreserveBusinessCodeAndNeverCallModel() {
        AnswerRunService runs = mock(AnswerRunService.class);
        AnswerGenerationGateway gateway = mock(AnswerGenerationGateway.class);
        stubLiveRun(runs, gateway, "revoked", "不应生成");
        GenerationContractGuard guard = mock(GenerationContractGuard.class);
        org.mockito.Mockito.doThrow(new com.noteweave.common.BusinessException(
                "ANSWER_EVIDENCE_REVOKED", "证据已撤销", org.springframework.http.HttpStatus.CONFLICT))
                .when(guard).requireEligible(anyString(), anyString(), any());
        AnswerGenerationOrchestrator orchestrator = new AnswerGenerationOrchestrator(
                runs, mock(AnswerCancellationRegistry.class), sequentialEventMux(),
                mock(ConversationEventMux.class), gateway, guard,
                mock(ScheduledExecutorService.class), mock(ScheduledExecutorService.class), JSON);
        List<AnswerDeliveryEvent> events = new ArrayList<>();
        orchestrator.stream("revoked", events::add);
        verify(gateway, never()).generateInConversation(anyString(), anyString(), any(), anyString(), anyInt(), any());
        verify(runs).fail("workspace-1", "run-live", "stream-owner", "ANSWER_EVIDENCE_REVOKED", "证据已撤销");
        assertThat(events).filteredOn(event -> event.eventType().equals("answer.failed"))
                .allSatisfy(event -> assertThat(event.data()).contains("ANSWER_EVIDENCE_REVOKED"));
    }

    /** 有冻结契约的实时生成：模型吐两个 token，运行在收尾前一直可写。 */
    private void stubLiveRun(
            AnswerRunService answerRunService,
            AnswerGenerationGateway gateway,
            String requestId,
            String... tokens
    ) {
        when(answerRunService.requireByAssistantRequest(requestId))
                .thenReturn(new AnswerRunRef("run-live", "workspace-1", "conversation-1", "message-1", requestId, "GENERATING"));
        when(answerRunService.claimStream("workspace-1", "run-live")).thenReturn("stream-owner");
        when(answerRunService.currentEventSequence("workspace-1", "run-live")).thenReturn(0L);
        when(answerRunService.complete(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(AnswerRunService.CompletionOutcome.COMPLETED);
        when(gateway.load(requestId)).thenReturn(new AnswerGenerationMaterial(
                "message-1", "workspace-1", "draft", List.of(), 100, contract()));
        when(gateway.prepareDraft("draft")).thenReturn("prepared");
        when(gateway.generateInConversation(anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<String> sink = invocation.getArgument(5);
                    for (String token : tokens) {
                        sink.accept(token);
                    }
                    return String.join("", tokens);
                });
    }

    /** 与冻结契约一致的最小契约：两条证据、编号 1 与 2。 */
    private GenerationContract contract() {
        return new GenerationContract(
                GenerationContract.SCHEMA_VERSION,
                "QA",
                "prompt-v1",
                "system prompt",
                false,
                List.of("passage:c1"),
                List.of(new CitationBinding(1, "passage:c1", List.of("citation-1"))),
                "bundle-1",
                Map.of("passage:c1", "hash-1"),
                List.of()
        );
    }

    private List<String> keysOf(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private AnswerGenerationOrchestrator orchestrator(
            AnswerRunService answerRunService,
            AnswerGenerationGateway gateway
    ) {
        return orchestrator(answerRunService, gateway, sequentialEventMux());
    }

    private AnswerGenerationOrchestrator orchestrator(
            AnswerRunService answerRunService,
            AnswerGenerationGateway gateway,
            SessionEventMux runEventMux
    ) {
        return new AnswerGenerationOrchestrator(
                answerRunService,
                mock(AnswerCancellationRegistry.class),
                runEventMux,
                mock(ConversationEventMux.class),
                gateway,
                mock(GenerationContractGuard.class),
                mock(ScheduledExecutorService.class),
                mock(ScheduledExecutorService.class),
                JSON);
    }

    /** mock 的 mux 默认 publish 返回 null，会让 emit 取不到事件序列号；这里按调用递增。 */
    private SessionEventMux sequentialEventMux() {
        SessionEventMux mux = mock(SessionEventMux.class);
        AtomicLong sequence = new AtomicLong();
        when(mux.publish(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new AnswerLiveEvent(
                        sequence.incrementAndGet(),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        java.time.Instant.now()));
        when(mux.publishAt(anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> new AnswerLiveEvent(
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        java.time.Instant.now()));
        when(mux.currentSequence(anyString())).thenAnswer(invocation -> sequence.get());
        return mux;
    }
}