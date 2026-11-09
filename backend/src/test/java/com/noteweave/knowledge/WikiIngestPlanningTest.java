package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.task.TaskCommandPort;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Wiki 编译的模型调用必须在事务之外：事务内会对资料行与片段行加排他锁，
 * 持锁期间调用模型会让引用同一资料的问答在生成前一直等待（实测约 16 秒）。
 * 写入阶段仍按同一套读取复核输入，变化时退回事务内完整编译。
 */
class WikiIngestPlanningTest {

    private static final String WS = "ws-1";

    private JdbcTemplate jdbc;
    private final AtomicBoolean inTransaction = new AtomicBoolean();
    private final WikiIngestTransactionExecutor transactionExecutor = mock(WikiIngestTransactionExecutor.class);
    private final TaskCommandPort taskCommandPort = mock(TaskCommandPort.class);
    private final WikiWorkspaceLock workspaceLock = mock(WikiWorkspaceLock.class);
    private final WikiConceptSuggester suggester = mock(WikiConceptSuggester.class);
    private final WikiConceptWriter writer = mock(WikiConceptWriter.class);
    private WikiIngestService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wiki-planning-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table workspace(id varchar(36) primary key, status varchar(32), wiki_enabled boolean)");
        jdbc.execute("""
                create table task(id varchar(36) primary key, workspace_id varchar(36), target_id varchar(36),
                    task_type varchar(64), task_status varchar(32), created_at timestamp default current_timestamp)
                """);
        jdbc.execute("""
                create table source(id varchar(36) primary key, workspace_id varchar(36) not null,
                    status varchar(32) not null, index_status varchar(32) not null, title varchar(300),
                    summary varchar(1000), tags_json varchar(1000), source_type varchar(64),
                    updated_at timestamp default current_timestamp)
                """);
        jdbc.execute("""
                create table source_snapshot(id varchar(36) primary key, source_id varchar(36) not null,
                    version_no int not null, index_status varchar(32) not null)
                """);
        jdbc.execute("""
                create table source_chunk(id varchar(36) primary key, workspace_id varchar(36) not null,
                    source_id varchar(36) not null, source_snapshot_id varchar(36) not null, chunk_no int not null,
                    heading varchar(300), content clob, location_info varchar(300),
                    projection_status varchar(32) not null)
                """);
        jdbc.execute("""
                create table knowledge_item(id varchar(36) primary key, workspace_id varchar(36), item_type varchar(32),
                    status varchar(32), title varchar(300), latest_version_id varchar(36),
                    updated_at timestamp default current_timestamp)
                """);
        jdbc.execute("create table knowledge_version(id varchar(36) primary key, content clob)");
        jdbc.update("insert into workspace values (?, 'ACTIVE', true)", WS);
        jdbc.update("""
                insert into source(id, workspace_id, status, index_status, title, summary, tags_json, source_type)
                values ('s1', ?, 'READY', 'INDEXED', '青禾计划', '阅读推广', '[]', 'DOC')
                """, WS);
        jdbc.update("insert into source_snapshot values ('snap-1', 's1', 1, 'INDEXED')");
        for (int i = 1; i <= 2; i++) {
            jdbc.update("""
                    insert into source_chunk values (?, ?, 's1', 'snap-1', ?, '', ?, '', 'PROJECTED')
                    """, "s1-chunk-" + i, WS, i, "青禾计划正文" + i);
        }

        when(workspaceLock.keyForWorkspace(anyString())).thenReturn("wiki:workspace:" + WS);
        when(workspaceLock.tryAcquire(anyString(), anyString(), any())).thenReturn(true);
        when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
            inTransaction.set(true);
            try {
                return ((Supplier<Object>) invocation.getArgument(0)).get();
            } finally {
                inTransaction.set(false);
            }
        });
        when(suggester.suggest(anyString(), anyString(), anyString(), anyString(), anyList())).thenAnswer(invocation -> {
            assertThat(inTransaction).as("概念选择不能在事务内调用模型").isFalse();
            return List.of("青禾计划");
        });
        when(writer.summarize(anyString(), anyList())).thenAnswer(invocation -> {
            assertThat(inTransaction).as("概念摘要不能在事务内调用模型").isFalse();
            return "青禾计划是阅读推广项目。";
        });

        WikiIngestService real = new WikiIngestService(
                jdbc, null, null, null, null, taskCommandPort, new ObjectMapper(), null,
                transactionExecutor, workspaceLock);
        real.setConceptSuggester(suggester);
        real.setConceptWriter(writer);
        service = spy(real);
        // 页面写入依赖完整知识库表结构，另有测试覆盖；这里只核对写入发生在事务内、使用的是哪份输入
        doAnswer(invocation -> {
            assertThat(inTransaction).as("页面与引用写入必须在事务内").isTrue();
            return mock(KnowledgeItemResponse.class);
        }).when(service).writeIngest(anyString(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void modelCallsRunBeforeTheShortWriteTransaction() {
        service.runSourceIngestNow("task-1", WS, "s1");

        verify(suggester, times(1)).suggest(anyString(), anyString(), anyString(), anyString(), anyList());
        verify(writer, times(1)).summarize(eq("青禾计划"), anyList());
        verify(service, times(1)).writeIngest(eq(WS), any(), anyList(), anyList(), anyList());
        verify(taskCommandPort).completeTask(eq("task-1"), eq("WIKI_INDEXED"), anyString(), any());
    }

    @Test
    void unchangedInputsReuseThePlannedSummary() {
        WikiIngestService.IngestPlan plan = service.planIngest(WS, "s1");

        transactionExecutor.execute(() -> service.applyIngest(WS, "s1", plan));

        verify(writer, times(1)).summarize(anyString(), anyList());
        verify(service).writeIngest(eq(WS), eq(plan.source()), eq(plan.chunks()), eq(plan.concepts()),
                eq(plan.conceptDrafts()));
    }

    @Test
    void chunkChangedAfterPlanningIsNotWrittenFromTheStalePlan() {
        WikiIngestService.IngestPlan plan = service.planIngest(WS, "s1");
        jdbc.update("update source_chunk set content = '青禾计划正文1（已更新）' where id = 's1-chunk-1'");

        Object written = transactionExecutor.execute(() -> service.applyIngest(WS, "s1", plan));

        assertThat(written).isNull();
        verify(service, times(0)).writeIngest(anyString(), any(), anyList(), anyList(), anyList());
    }

    @Test
    void inputsChangedDuringPlanningAreReplannedOutsideTheTransaction() {
        AtomicBoolean changed = new AtomicBoolean();
        when(writer.summarize(anyString(), anyList())).thenAnswer(invocation -> {
            assertThat(inTransaction).as("概念摘要不能在事务内调用模型").isFalse();
            // 第一次规划调用模型期间资料被重新解析：这一份规划不能写入
            if (changed.compareAndSet(false, true)) {
                jdbc.update("update source_chunk set content = '青禾计划正文1（已更新）' where id = 's1-chunk-1'");
            }
            return "青禾计划是阅读推广项目。";
        });

        service.runSourceIngestNow("task-2", WS, "s1");

        verify(writer, times(2)).summarize(anyString(), anyList());
        verify(service, times(1)).writeIngest(eq(WS), any(),
                org.mockito.ArgumentMatchers.argThat((List<WikiIngestService.ChunkForWiki> chunks) ->
                        chunks.stream().anyMatch(chunk -> chunk.content().contains("已更新"))),
                anyList(), anyList());
        verify(taskCommandPort).completeTask(eq("task-2"), eq("WIKI_INDEXED"), anyString(), any());
    }

    @Test
    void sourceNoLongerReadyAtWriteTimeFailsInsteadOfWritingPlannedPages() {
        WikiIngestService.IngestPlan plan = service.planIngest(WS, "s1");
        jdbc.update("update source set status = 'DELETED' where id = 's1'");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactionExecutor.execute(() -> service.applyIngest(WS, "s1", plan)))
                .isInstanceOf(IllegalStateException.class);
        verify(service, times(0)).writeIngest(anyString(), any(), anyList(), anyList(), anyList());
    }
}
