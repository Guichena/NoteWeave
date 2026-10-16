package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.source.SourceChunkContextEnhancer.ChunkInput;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class SourceChunkContextEnhancerTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private final ObjectMapper mapper = new ObjectMapper();
    private ChatLlmClient llm;
    private NoteWeaveProperties configuration;
    private SimpleMeterRegistry meters;
    private AtomicInteger calls;
    private static final SourceChunkContextProperties DEFAULTS =
            new SourceChunkContextProperties(true, "source-context-v1", 1000, 48000, 600, 12000);

    @BeforeEach
    void prepare() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:context-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table source_chunk(id varchar(64) primary key, workspace_id varchar(64),
                    source_id varchar(64), source_snapshot_id varchar(64), heading varchar(300), content clob);
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V146__source_chunk_context.sql"))
                .execute(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        llm = mock(ChatLlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        configuration = mock(NoteWeaveProperties.class);
        when(configuration.llm()).thenReturn(new NoteWeaveProperties.Llm(true, "http://test/v1", "model-a", "", 60));
        meters = new SimpleMeterRegistry();
        calls = new AtomicInteger();
    }

    @Test
    void alignsShuffledResultsAndPreservesOriginalContentWhileSavingBeforeEmbedding() {
        var chunks = List.of(insert("w", "s", "v", "a", "章节", "真实原文甲"),
                insert("w", "s", "v", "b", "章节", "真实原文乙"));
        respond("[{\"chunk_id\":\"b\",\"context\":\"乙主题\"},{\"chunk_id\":\"a\",\"context\":\"甲主题\"}]");
        var enhancer = enhancer(DEFAULTS);
        var result = enhancer.enhance("w", "s", "v", "资料", "摘要", chunks, calls::addAndGet);
        assertThat(result.contexts()).containsExactly("甲主题", "乙主题");
        assertThat(result.reasons()).containsExactly("", "");
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select content from source_chunk where id='a'", String.class)).isEqualTo("真实原文甲");
        assertThat(jdbc.queryForObject("select context_text from source_chunk where id='a'", String.class)).isEqualTo("甲主题");
        var resumed = enhancer.enhance("w", "s", "v", "资料", "摘要", chunks, calls::addAndGet);
        assertThat(resumed.modelCalls()).isZero();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(meters.get("noteweave.source.context.llm.calls").tag("purpose", "source.chunk.context")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void persistsContextIndependentlyOfLaterPipelineRollback() {
        var chunk = insert("w", "s", "v", "a", "章节", "正文");
        respond("[{\"chunk_id\":\"a\",\"context\":\"真实主题\"}]");
        var enhancer = enhancer(DEFAULTS);
        var outer = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            enhancer.enhance("w", "s", "v", "资料", "", List.of(chunk), calls::addAndGet);
            throw new IllegalStateException("向量服务失败");
        })).hasMessage("向量服务失败");
        assertThat(jdbc.queryForObject("select context_text from source_chunk where id='a'", String.class)).isEqualTo("真实主题");
        assertThat(enhancer.enhance("w", "s", "v", "资料", "", List.of(chunk), calls::addAndGet).modelCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"chunk_id\":\"a\",\"context\":\"说明\",\"extra\":true}]",
            "[{\"chunk_id\":\"a\",\"context\":1}]",
            "[{\"chunk_id\":\"unknown\",\"context\":\"说明\"}]",
            "[{\"chunk_id\":\"a\",\"context\":\"说明\",\"context\":\"重复\"}]",
            "[{\"chunk_id\":\"a\",\"context\":\"说明\"}] {}",
            "[]"
    })
    void rejectsInvalidSchemasAndRetriesExactlyOnceWithError(String invalid) {
        var chunk = insert("w", "s", "v", "a", "章节", "正文");
        List<String> prompts = new ArrayList<>();
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(1));
            return invalid;
        });
        var enhancer = enhancer(DEFAULTS);
        var result = enhancer.enhance("w", "s", "v", "资料", "", List.of(chunk), calls::addAndGet);
        assertThat(result.contexts()).containsExactly("");
        assertThat(result.reasons()).containsExactly("SOURCE_CONTEXT_OUTPUT_INVALID");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(prompts.get(1)).contains("上次结构校验失败");
        assertThat(enhancer.enhance("w", "s", "v", "资料", "", List.of(chunk), calls::addAndGet).modelCalls()).isZero();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from source_chunk_context_cache", Integer.class)).isZero();
    }

    @Test
    void rejectsDuplicateChunkIdsInResponseEvenWhenArraySizeMatches() {
        var chunks = List.of(insert("w", "s", "v", "a", "章节", "甲"), insert("w", "s", "v", "b", "章节", "乙"));
        respond("[{\"chunk_id\":\"a\",\"context\":\"甲\"},{\"chunk_id\":\"a\",\"context\":\"乙\"}]");
        assertThat(enhancer(DEFAULTS).enhance("w", "s", "v", "资料", "", chunks, calls::addAndGet).reasons())
                .containsExactly("SOURCE_CONTEXT_OUTPUT_INVALID", "SOURCE_CONTEXT_OUTPUT_INVALID");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void correctsFirstInvalidOutputAndChargesBothAttempts() {
        var chunk = insert("w", "s", "v", "a", "章节", "正文");
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenReturn("invalid",
                "[{\"chunk_id\":\"a\",\"context\":\"真实主题\"}]");
        var result = enhancer(DEFAULTS).enhance("w", "s", "v", "资料", "", List.of(chunk), calls::addAndGet);
        assertThat(result.contexts()).containsExactly("真实主题");
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void callFailureSavesEmptyContextAndRestoresWithoutRepeatingTheRequest() {
        var chunk = insert("w", "s", "v", "a", "章节", "正文");
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenThrow(new IllegalStateException("连接失败"));
        var enhancer = enhancer(DEFAULTS);
        assertThat(enhancer.enhance("w", "s", "v", "资料", "", List.of(chunk), calls::addAndGet).reasons())
                .containsExactly("SOURCE_CONTEXT_LLM_CALL_FAILED");
        assertThat(enhancer.enhance("w", "s", "v", "资料", "", List.of(chunk), calls::addAndGet).modelCalls()).isZero();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void budgetCallbackFailurePropagatesBeforeActualModelAttempt() {
        var chunk = insert("w", "s", "v", "a", "章节", "正文");
        assertThatThrownBy(() -> enhancer(DEFAULTS).enhance("w", "s", "v", "资料", "", List.of(chunk), increment -> {
            throw new IllegalStateException("批次调用预算耗尽");
        })).hasMessage("批次调用预算耗尽");
        verify(llm, never()).streamChat(anyString(), anyString(), anyInt(), any());
        assertThat(jdbc.queryForObject("select context_text from source_chunk where id='a'", String.class)).isNull();
    }

    @Test
    void exhaustedPersistentBudgetSavesDegradationWithoutAnAdditionalModelCall() {
        var chunk = insert("w", "s", "v", "a", "章节", "正文");
        var enhancer = enhancer(DEFAULTS);
        AtomicInteger ledger = new AtomicInteger(2);
        var result = enhancer.enhance("w", "s", "v", "资料", "", List.of(chunk), increment -> {
            if (ledger.get() + increment > 2) {
                throw new BusinessException("SOURCE_CONTEXT_CALL_BUDGET_EXHAUSTED", "本批上下文调用预算已耗尽");
            }
            ledger.addAndGet(increment);
        });
        assertThat(result.modelCalls()).isZero();
        assertThat(result.contexts()).containsExactly("");
        assertThat(result.reasons()).containsExactly("SOURCE_CONTEXT_CALL_BUDGET_EXHAUSTED");
        assertThat(jdbc.queryForObject("select context_reason from source_chunk where id='a'", String.class))
                .isEqualTo("SOURCE_CONTEXT_CALL_BUDGET_EXHAUSTED");
        assertThat(enhancer.enhance("w", "s", "v", "资料", "", List.of(chunk), increment -> {
            throw new AssertionError("恢复已保存上下文不应再次预留预算");
        }).modelCalls()).isZero();
        assertThat(ledger.get()).isEqualTo(2);
        verify(llm, never()).streamChat(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void staleLeaseBusinessExceptionStillPropagatesWithoutSavingContext() {
        var chunk = insert("w", "s", "v", "a", "章节", "正文");
        assertThatThrownBy(() -> enhancer(DEFAULTS).enhance("w", "s", "v", "资料", "", List.of(chunk), increment -> {
            throw new BusinessException("SOURCE_EMBEDDING_BATCH_LEASE_LOST", "批次租约已失效");
        })).isInstanceOf(BusinessException.class).hasMessage("批次租约已失效");
        verify(llm, never()).streamChat(anyString(), anyString(), anyInt(), any());
        assertThat(jdbc.queryForObject("select context_text from source_chunk where id='a'", String.class)).isNull();
        assertThat(jdbc.queryForObject("select context_reason from source_chunk where id='a'", String.class)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from source_chunk_context_cache", Integer.class)).isZero();
    }

    @Test
    void cacheReusesOnlyTheSameWorkspaceAndCompleteInputAcrossNewChunkIds() {
        var original = insert("w", "s", "v", "a", "章节", "正文");
        dynamicResponse();
        var enhancer = enhancer(DEFAULTS);
        enhancer.enhance("w", "s", "v", "资料", "摘要", List.of(original), calls::addAndGet);
        var same = insert("w", "s", "v2", "b", "章节", "正文");
        assertThat(enhancer.enhance("w", "s", "v2", "资料", "摘要", List.of(same), calls::addAndGet).modelCalls()).isZero();
        var other = insert("other", "s2", "v3", "c", "章节", "正文");
        assertThat(enhancer.enhance("other", "s2", "v3", "资料", "摘要", List.of(other), calls::addAndGet).modelCalls()).isEqualTo(1);
        var renamed = insert("w", "s", "v4", "d", "章节", "正文");
        assertThat(enhancer.enhance("w", "s", "v4", "新标题", "摘要", List.of(renamed), calls::addAndGet).modelCalls()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void fullInputHashIncludesTheTailBeyondThePromptExcerpt() {
        dynamicResponse();
        var enhancer = enhancer(DEFAULTS);
        String prefix = "真实正文".repeat(400);
        var first = insert("w", "s", "v", "a", "章节", prefix + "甲");
        var changed = insert("w", "s", "v2", "b", "章节", prefix + "乙");
        enhancer.enhance("w", "s", "v", "资料", "摘要", List.of(first), calls::addAndGet);
        assertThat(enhancer.enhance("w", "s", "v2", "资料", "摘要", List.of(changed), calls::addAndGet).modelCalls()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select context_input_hash from source_chunk where id='a'", String.class))
                .isNotEqualTo(jdbc.queryForObject("select context_input_hash from source_chunk where id='b'", String.class));
    }

    @Test
    void sixtyFourInputsAllKeepAnExcerptWithinTheTotalPromptLimit() throws Exception {
        List<ChunkInput> chunks = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            chunks.add(insert("w", "s", "v", "c" + index, "真实章节".repeat(50), "独立正文".repeat(1000) + index));
        }
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            String user = invocation.getArgument(1);
            assertThat(user.length()).isLessThanOrEqualTo(DEFAULTS.maxInputChars());
            JsonNode input = mapper.readTree(user);
            assertThat(input.path("chunks").size()).isEqualTo(64);
            List<Object> results = new ArrayList<>();
            for (JsonNode row : input.path("chunks")) {
                assertThat(row.path("excerpt").textValue()).isNotEmpty();
                assertThat(row.path("excerpt_truncated").booleanValue()).isTrue();
                results.add(java.util.Map.of("chunk_id", row.path("chunk_id").textValue(), "context", "所属章节正文"));
            }
            return mapper.writeValueAsString(results);
        });
        var result = enhancer(DEFAULTS).enhance("w", "s", "v", "资料", "摘要", chunks, calls::addAndGet);
        assertThat(result.contexts()).hasSize(64).allMatch(value -> value.equals("所属章节正文"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void retryErrorIsIncludedInTheSameTotalInputBudget() throws Exception {
        var bounded = new SourceChunkContextProperties(true, "source-context-v1", 1000, 4096, 600, 12000);
        List<ChunkInput> chunks = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            chunks.add(insert("w", "s", "v", java.util.UUID.randomUUID().toString(),
                    "章节".repeat(145), "真实正文".repeat(250) + index));
        }
        List<String> prompts = new ArrayList<>();
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            prompts.add(invocation.getArgument(1));
            return "无效输出";
        });
        var result = enhancer(bounded).enhance("w", "s", "v", "资料", "摘要", chunks, calls::addAndGet);
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(prompts).hasSize(2).allMatch(prompt -> prompt.length() <= bounded.maxInputChars());
        assertThat(prompts.get(1)).contains("上次结构校验失败");
        JsonNode initial = mapper.readTree(prompts.get(0));
        assertThat(initial.path("chunks").size()).isEqualTo(10);
        for (JsonNode item : initial.path("chunks")) {
            assertThat(item.path("excerpt").textValue()).isNotEmpty();
        }
    }

    @Test
    void disabledOrUnavailableModelDoesNotInventContextOrChargeCalls() {
        var chunk = insert("w", "s", "v", "a", "章节", "正文");
        var off = new SourceChunkContextProperties(false, "source-context-v1", 1000, 48000, 600, 12000);
        assertThat(enhancer(off).enhance("w", "s", "v", "资料", "", List.of(chunk), calls::addAndGet).reasons())
                .containsExactly("SOURCE_CONTEXT_DISABLED");
        when(llm.isEnabled()).thenReturn(false);
        assertThat(enhancer(DEFAULTS).enhance("w", "s", "v", "资料", "", List.of(chunk), calls::addAndGet).reasons())
                .containsExactly("SOURCE_CONTEXT_LLM_UNAVAILABLE");
        assertThat(calls.get()).isZero();
    }

    @Test
    void changingModelOrPromptConfigurationInvalidatesTheContextVersion() {
        var initial = enhancer(DEFAULTS).currentContextPromptVersion();
        when(configuration.llm()).thenReturn(new NoteWeaveProperties.Llm(true, "http://test/v1", "model-b", "", 60));
        assertThat(enhancer(DEFAULTS).currentContextPromptVersion()).isNotEqualTo(initial);
        assertThat(enhancer(new SourceChunkContextProperties(true, "source-context-v2", 1000, 48000, 600, 12000))
                .currentContextPromptVersion()).isNotEqualTo(initial);
    }

    @Test
    void rejectsForeignIdentityAndOriginalTextMismatchBeforeCallingModel() {
        var chunk = insert("w", "s", "v", "a", "章节", "正文");
        var enhancer = enhancer(DEFAULTS);
        assertThatThrownBy(() -> enhancer.enhance("other", "s", "v", "资料", "", List.of(chunk), calls::addAndGet))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> enhancer.enhance("w", "s", "v", "资料", "",
                List.of(new ChunkInput("a", "章节", "伪造正文")), calls::addAndGet)).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isZero();
    }

    private SourceChunkContextEnhancer enhancer(SourceChunkContextProperties settings) {
        return new SourceChunkContextEnhancer(jdbc, llm, mapper, settings, configuration, meters, transactionManager);
    }

    private ChunkInput insert(String workspace, String source, String snapshot, String id, String heading, String content) {
        jdbc.update("insert into source_chunk(id,workspace_id,source_id,source_snapshot_id,heading,content) values (?,?,?,?,?,?)",
                id, workspace, source, snapshot, heading, content);
        return new ChunkInput(id, heading, content);
    }

    private void respond(String output) {
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenReturn(output);
    }

    private void dynamicResponse() {
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            List<Object> rows = new ArrayList<>();
            for (JsonNode row : mapper.readTree((String) invocation.getArgument(1)).path("chunks")) {
                rows.add(java.util.Map.of("chunk_id", row.path("chunk_id").textValue(), "context", "真实主题"));
            }
            return mapper.writeValueAsString(rows);
        });
    }
}
