package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.RetrievalExecutionTrace;
import com.noteweave.conversation.ConversationContextProjectionService;
import com.noteweave.retrieval.QueryRewrite;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 渐进改写：alternatives 之前的字段齐全就可以检索，最终结果仍与一次性改写完全一致。 */
class QueryRewriteProgressiveTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationRetrievalContextAssembler assembler = new ConversationRetrievalContextAssembler();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private static final List<String> STREAM = List.of(
            "{\"s\":false,\"q\":\"AnchorAlpha 的第二点为什么重要\",",
            "\"k\":\"AnchorAlpha 第二点\",\"t\":\"reasoning\",\"m\":[\"AnchorAlpha\"],",
            "\"l\":[\"证据窗口\"],\"h\":[\"资料定位\"],\"lang\":\"zh\"",
            ",\"a\":",
            "[\"AnchorAlpha 证据窗口\"]}");

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void dispatchesBeforeAlternativesFinishAndCompletesWithIdenticalFields() throws Exception {
        CountDownLatch releaseTail = new CountDownLatch(1);
        ChatLlmClient llm = streamingClient(STREAM, 4, releaseTail);

        QueryRewriteService.ProgressiveRewrite progressive = service(llm)
                .rewriteProgressive(followUp("它为什么重要"), executor);
        QueryRewrite dispatched = progressive.awaitDispatchable();

        // alternatives 仍在生成时已经可以检索
        assertThat(progressive.dispatchedPartial()).isTrue();
        assertThat(dispatched.alternatives()).isEmpty();
        assertThat(dispatched.standaloneQuery()).isEqualTo("AnchorAlpha 的第二点为什么重要");
        assertThat(dispatched.method()).isEqualTo("LLM");

        releaseTail.countDown();
        QueryRewrite complete = progressive.awaitComplete();
        QueryRewrite reference = service(streamingClient(STREAM, -1, null)).rewrite(followUp("它为什么重要"));

        assertThat(complete).isEqualTo(reference);
        assertThat(complete.alternatives()).containsExactly("AnchorAlpha 证据窗口");
        assertThat(QueryRewriteService.sameExceptAlternatives(dispatched, complete)).isTrue();
    }

    @Test
    void oldFieldOrderDoesNotDispatchEarly() {
        List<String> oldOrder = List.of(
                "{\"s\":false,\"q\":\"AnchorAlpha 的第二点为什么重要\",\"k\":\"AnchorAlpha 第二点\"",
                ",\"a\":[\"AnchorAlpha 证据窗口\"],\"t\":\"reasoning\",\"m\":[\"AnchorAlpha\"],",
                "\"l\":[\"证据窗口\"],\"h\":[\"资料定位\"],\"lang\":\"zh\"}");
        QueryRewriteService.ProgressiveRewrite progressive = service(streamingClient(oldOrder, -1, null))
                .rewriteProgressive(followUp("它为什么重要"), executor);

        QueryRewrite dispatched = progressive.awaitDispatchable();

        // 缺 question_type 等字段时前缀校验不通过，只能等完整结果
        assertThat(progressive.dispatchedPartial()).isFalse();
        assertThat(dispatched.alternatives()).containsExactly("AnchorAlpha 证据窗口");
    }

    @Test
    void firstTurnAndMissingExecutorStaySynchronous() {
        ChatLlmClient llm = streamingClient(STREAM, -1, null);
        QueryRewriteService.ProgressiveRewrite first = service(llm)
                .rewriteProgressive(assembler.empty("PostgreSQL 的 GIN 索引怎么用"), executor);
        assertThat(first.dispatchedPartial()).isFalse();
        assertThat(first.awaitDispatchable().method()).isEqualTo("RULE");

        QueryRewriteService.ProgressiveRewrite noExecutor = service(llm)
                .rewriteProgressive(followUp("它为什么重要"), null);
        assertThat(noExecutor.awaitDispatchable().alternatives()).containsExactly("AnchorAlpha 证据窗口");
        assertThat(noExecutor.dispatchedPartial()).isFalse();
    }

    @Test
    void rerunWhenFieldsDifferOrLowRecallNeedsAlternatives() {
        QueryRewrite dispatched = rewrite(List.of());
        QueryRewrite complete = rewrite(List.of("AnchorAlpha 证据窗口"));

        assertThat(ChatService.requiresFullRewriteRetrieval(dispatched, complete, bundle(0L))).isFalse();
        assertThat(ChatService.requiresFullRewriteRetrieval(dispatched, complete, bundle(1L))).isTrue();
        assertThat(ChatService.requiresFullRewriteRetrieval(dispatched, rewrite(List.of()), bundle(1L))).isFalse();

        QueryRewrite fallback = new QueryRewrite(false, "它为什么重要", "它为什么重要 AnchorAlpha", List.of(),
                "reasoning", List.of("anchoralpha"), List.of(), List.of(), "zh", "RULE",
                List.of("QUERY_REWRITE_OUTPUT_INVALID"));
        assertThat(ChatService.requiresFullRewriteRetrieval(dispatched, fallback, bundle(0L))).isTrue();
    }

    /** 话题编号放在最前面，随提前交出的结果一起给出；话题列表进入改写提示。 */
    @Test
    void topicRouteComesWithTheDispatchedResultAndTheTopicListReachesThePrompt() {
        List<String> routed = new java.util.ArrayList<>(STREAM);
        routed.set(0, "{\"r\":\"T2\",\"s\":false,\"q\":\"AnchorAlpha 的第二点为什么重要\",");
        ChatLlmClient llm = streamingClient(routed, -1, null);
        String topics = "T1（最近在聊）：台南旅行\nT2：缓存一致性方案\n  摘要：讨论了延迟双删与订阅 binlog";

        QueryRewriteService.ProgressiveRewrite progressive = service(llm)
                .rewriteProgressive(followUp("那第二种呢"), topics, executor);
        QueryRewrite dispatched = progressive.awaitDispatchable();

        assertThat(progressive.dispatchedRoute()).isEqualTo("T2");
        assertThat(dispatched.standaloneQuery()).isEqualTo("AnchorAlpha 的第二点为什么重要");
        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llm).streamChat(any(), prompt.capture(), anyInt(), any());
        assertThat(prompt.getValue()).contains("话题列表：", "T2：缓存一致性方案");
        // 重复调用返回同一份结果
        assertThat(progressive.awaitDispatchable()).isSameAs(dispatched);
    }

    @Test
    void invalidOrMissingTopicRouteDoesNotFailTheRewrite() {
        List<String> numeric = new java.util.ArrayList<>(STREAM);
        numeric.set(0, "{\"r\":2,\"s\":false,\"q\":\"AnchorAlpha 的第二点为什么重要\",");
        QueryRewriteService.ProgressiveRewrite progressive = service(streamingClient(numeric, -1, null))
                .rewriteProgressive(followUp("那第二种呢"), "T1（最近在聊）：缓存", executor);
        assertThat(progressive.awaitDispatchable().method()).isEqualTo("LLM");
        assertThat(progressive.dispatchedRoute()).isNull();

        QueryRewriteService.ProgressiveRewrite legacy = service(streamingClient(STREAM, -1, null))
                .rewriteProgressive(followUp("它为什么重要"), executor);
        assertThat(legacy.awaitDispatchable().method()).isEqualTo("LLM");
        assertThat(legacy.dispatchedRoute()).isNull();
    }

    private static QueryRewrite rewrite(List<String> alternatives) {
        return new QueryRewrite(false, "AnchorAlpha 的第二点为什么重要", "它为什么重要 AnchorAlpha 第二点",
                alternatives, "reasoning", List.of("AnchorAlpha"), List.of("证据窗口"), List.of("资料定位"),
                "zh", "LLM", List.of());
    }

    private static EvidenceBundle bundle(long lowRecall) {
        RetrievalExecutionTrace.StepTrace step = new RetrievalExecutionTrace.StepTrace(
                0, "QA_PASSAGE", 20, 0, 0, 0L, false, List.of(),
                Map.of(QaHybridRetriever.LOW_RECALL_EXPANSION_ELIGIBLE, lowRecall), List.of());
        RetrievalExecutionTrace trace = new RetrievalExecutionTrace(RetrievalExecutionTrace.SCHEMA_VERSION,
                "qa-v2", 0L, 0, 0, 0, 0, List.of(step), List.of());
        return new EvidenceBundle("b", "qa-v2", List.of(), false, List.of(), Instant.now(), Map.of(), trace);
    }

    /** 逐段回调模型输出；holdBefore 段之前等待 release，用来模拟 alternatives 仍在生成。 */
    @SuppressWarnings("unchecked")
    private static ChatLlmClient streamingClient(List<String> chunks, int holdBefore, CountDownLatch release) {
        ChatLlmClient llm = mock(ChatLlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        when(llm.streamChat(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Consumer<String> onToken = invocation.getArgument(3);
            StringBuilder all = new StringBuilder();
            for (int index = 0; index < chunks.size(); index++) {
                if (index == holdBefore && release != null) {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                }
                onToken.accept(chunks.get(index));
                all.append(chunks.get(index));
            }
            return all.toString();
        });
        return llm;
    }

    private QueryRewriteService service(ChatLlmClient llm) {
        return new QueryRewriteService(llm, objectMapper, assembler, true);
    }

    private ConversationRetrievalContextAssembler.Context followUp(String question) {
        return new ConversationRetrievalContextAssembler.Context(
                question,
                "当前问题：" + question + "\n主题锚点：AnchorAlpha",
                true,
                2,
                "AnchorAlpha",
                "前序在讨论 AnchorAlpha",
                new ConversationContextProjectionService.Projection(List.of(), List.of()),
                null);
    }
}
