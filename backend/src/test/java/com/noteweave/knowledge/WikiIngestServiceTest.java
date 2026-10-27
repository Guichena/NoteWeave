package com.noteweave.knowledge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.source.SourceMessagingMode;
import com.noteweave.task.TaskCommandPort;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.util.List;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WikiIngestServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final WorkspaceQueryPort workspaceQueryPort = mock(WorkspaceQueryPort.class);
    private final KnowledgeQueryService knowledgeQueryService = mock(KnowledgeQueryService.class);
    private final KnowledgeCommandService knowledgeCommandService = mock(KnowledgeCommandService.class);
    private final KnowledgeGovernanceService knowledgeGovernanceService = mock(KnowledgeGovernanceService.class);
    private final TaskCommandPort taskCommandPort = mock(TaskCommandPort.class);
    private final SourceMessagingMode messagingMode = mock(SourceMessagingMode.class);
    private final WikiIngestTransactionExecutor transactionExecutor = mock(WikiIngestTransactionExecutor.class);
    private final WikiWorkspaceLock workspaceLock = mock(WikiWorkspaceLock.class);
    private final WikiIngestService service = new WikiIngestService(
            jdbcTemplate,
            workspaceQueryPort,
            knowledgeQueryService,
            knowledgeCommandService,
            knowledgeGovernanceService,
            taskCommandPort,
            new ObjectMapper(),
            messagingMode,
            transactionExecutor,
            workspaceLock
    );

    @org.junit.jupiter.api.BeforeEach
    void grantWorkspaceLockByDefault() {
        when(workspaceLock.keyForWorkspace(anyString())).thenReturn("wiki:workspace:workspace-1");
        when(workspaceLock.tryAcquire(anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void selectedConceptsShouldOnlyReadTheRelatedConceptSection() {
        // 自动链接会把正文里出现的页面标题改成 [[标题]]，这些不算资料选中的概念
        String content = "# [[cache]]-[[consistency]].md\n\n## 资料摘要\n\n[[缓存一致性方案]] 正文\n\n"
                + "## 页面导航\n\n- [[Wiki Index]]\n\n## 关联概念\n\n- [[延迟双删]]\n- [[binlog 订阅]]\n\n"
                + "## 关键内容\n\n写请求先更新数据库 [[写请求先更新数据库]]\n";

        org.assertj.core.api.Assertions.assertThat(WikiIngestService.selectedConcepts(content))
                .containsExactly("延迟双删", "binlog 订阅");
        org.assertj.core.api.Assertions.assertThat(WikiIngestService.selectedConcepts("# 无概念\n\n## 页面导航\n")).isEmpty();
    }

    @Test
    void readySourceIngestRunsWithoutUserSessionCheck() {
        // 资料索引完成事件在 Kafka 消费线程中触发，没有登录用户，只能按数据库里的工作台开关判断
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(1);
        when(messagingMode.isAsyncEnabled()).thenReturn(true);
        when(transactionExecutor.execute(any())).thenReturn("task-9");

        org.assertj.core.api.Assertions.assertThat(
                service.enqueueSourceIngestForReadySource("workspace-1", "source-1")).isEqualTo("task-9");
        verifyNoInteractions(workspaceQueryPort);
    }

    @Test
    void readySourceIngestSkipsWorkspacesWithWikiDisabled() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(0);

        org.assertj.core.api.Assertions.assertThat(
                service.enqueueSourceIngestForReadySource("workspace-1", "source-1")).isEmpty();
        verifyNoInteractions(workspaceQueryPort, transactionExecutor);
    }

    @Test
    void disabledWikiShouldCancelIngestTaskWithoutStartingWork() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(0);

        service.runSourceIngestNow("task-1", "workspace-1", "source-1");

        verify(taskCommandPort).cancelTask(
                "task-1", "WIKI_DISABLED", "Wiki 构建已关闭，跳过资料 ingest", "source-1");
        verifyNoInteractions(workspaceQueryPort, knowledgeQueryService, knowledgeCommandService, knowledgeGovernanceService);
    }

    @Test
    void disabledWikiShouldCancelRetractTaskWithoutStartingWork() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(0);

        service.runSourceRetractNow("task-2", "workspace-1", "source-1");

        verify(taskCommandPort).cancelTask(
                "task-2", "WIKI_DISABLED", "Wiki 构建已关闭，跳过资料 retract", "source-1");
        verifyNoInteractions(workspaceQueryPort, knowledgeQueryService, knowledgeCommandService, knowledgeGovernanceService);
    }

    @Test
    void failedIngestShouldRecordFailedTerminalStateWithoutKafkaRedelivery() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(1);
        // 重试前会核对来源状态：资料仍 READY 才继续 ingest
        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                eq("workspace-1"), eq("source-1")))
                .thenReturn(List.of("READY"));
        when(transactionExecutor.execute(any()))
                .thenThrow(new IllegalStateException("source unavailable"));

        service.runSourceIngestNow("task-1", "workspace-1", "source-1");

        verify(taskCommandPort).startTask("task-1");
        verify(taskCommandPort).failTask(
                "task-1", "WIKI_INDEXED_FAILED", "Wiki ingest 失败：source unavailable",
                "WIKI_INGEST_FAILED", true);
        verify(taskCommandPort, never()).completeTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(workspaceQueryPort);
    }

    @Test
    void failedRetractShouldRecordFailedTerminalStateWithoutKafkaRedelivery() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(1);
        when(transactionExecutor.execute(any()))
                .thenThrow(new IllegalStateException("retract unavailable"));

        service.runSourceRetractNow("task-2", "workspace-1", "source-1");

        verify(taskCommandPort).startTask("task-2");
        verify(taskCommandPort).failTask(
                "task-2", "WIKI_RETRACTED_FAILED", "Wiki retract 失败：retract unavailable",
                "WIKI_RETRACT_FAILED", true);
        verify(taskCommandPort, never()).completeTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(workspaceQueryPort);
    }

    @Test
    void newerIngestShouldCancelSupersededPendingTaskBeforeStarting() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                eq("workspace-1"), eq("source-1")))
                .thenReturn(List.of("READY"));
        when(jdbcTemplate.queryForList(
                anyString(),
                eq(String.class),
                eq("task-current"),
                eq("workspace-1"),
                eq("source-1"),
                eq("WIKI_INGEST")))
                .thenReturn(List.of("task-stale"));
        when(transactionExecutor.execute(any()))
                .thenThrow(new IllegalStateException("source unavailable"));

        service.runSourceIngestNow("task-current", "workspace-1", "source-1");

        verify(taskCommandPort).cancelTask(
                "task-stale",
                "WIKI_SUPERSEDED",
                "较新的 Wiki ingest 任务已接管同一资料，取消旧的待处理任务",
                "source-1");
        verify(taskCommandPort).startTask("task-current");
    }

    @Test
    void retractShouldDeletePagesThroughInternalEntryWithoutUserSession() {
        // Kafka 消费线程没有 HTTP 用户；删除必须走内部执行入口，否则会抛 AUTHENTICATION_REQUIRED
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                eq("task-2"), eq("workspace-1"), eq("source-1"), eq("WIKI_RETRACT")))
                .thenReturn(List.of());
        when(knowledgeQueryService.findSourceBackedWikiItemIds("workspace-1", "source-1"))
                .thenReturn(List.of("page-a", "page-b"));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("workspace-1")))
                .thenReturn(List.of());
        when(transactionExecutor.execute(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());

        service.runSourceRetractNow("task-2", "workspace-1", "source-1");

        verify(knowledgeCommandService).deleteItemForInternalExecution("workspace-1", "page-a");
        verify(knowledgeCommandService).deleteItemForInternalExecution("workspace-1", "page-b");
        verify(knowledgeCommandService, never()).deleteItem(anyString());
        verify(taskCommandPort).completeTask(
                eq("task-2"), eq("WIKI_RETRACTED"), anyString(), eq("source-1"));
    }

    @Test
    void orphanedSentWikiTaskShouldBeCancelledByBackgroundReconciliation() {
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Timestamp.class)))
                .thenReturn(List.of(new WikiIngestService.OrphanedTask(
                        "task-orphaned", "workspace-1", "source-1", "WIKI_RETRACT")));

        int reconciled = service.reconcileOrphanedPendingWikiTasks();

        org.assertj.core.api.Assertions.assertThat(reconciled).isEqualTo(1);
        verify(taskCommandPort).cancelTask(
                "task-orphaned",
                "WIKI_DELIVERY_ORPHANED",
                "Wiki 消息已发送但消费者长时间未接管，任务已安全取消，可通过资料重建重新执行",
                "");
    }

    @Test
    void orphanedIngestIsRequeuedWhenItsSnapshotIsStillCurrent() {
        // WIKI-04：消息丢了但快照仍然有效时重新入队，不把这次编译静默丢掉
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Timestamp.class)))
                .thenReturn(List.of(new WikiIngestService.OrphanedTask(
                        "task-orphaned", "workspace-1", "source-1", "WIKI_INGEST")));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("task-orphaned")))
                .thenReturn(List.of("{\"sourceSnapshotId\":\"snapshot-1\"}"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq("source-1"), eq("snapshot-1")))
                .thenReturn(1);
        when(transactionExecutor.execute(any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });

        service.reconcileOrphanedPendingWikiTasks();

        verify(taskCommandPort).cancelTask(
                "task-orphaned",
                "WIKI_DELIVERY_ORPHANED",
                "Wiki 消息已发送但消费者长时间未接管，任务已取消，快照仍然有效时已重新入队",
                "");
        verify(taskCommandPort).createTask(
                "workspace-1", "WIKI_INGEST", "SOURCE", "source-1",
                "QUEUED", "Wiki 消息投递中断，快照仍然有效，已重新入队");
    }

    @Test
    void orphanedIngestForAStaleSnapshotIsOnlyCancelled() {
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Timestamp.class)))
                .thenReturn(List.of(new WikiIngestService.OrphanedTask(
                        "task-orphaned", "workspace-1", "source-1", "WIKI_INGEST")));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("task-orphaned")))
                .thenReturn(List.of("{\"sourceSnapshotId\":\"snapshot-old\"}"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq("source-1"), eq("snapshot-old")))
                .thenReturn(0);

        service.reconcileOrphanedPendingWikiTasks();

        verify(taskCommandPort).cancelTask(
                "task-orphaned",
                "WIKI_DELIVERY_ORPHANED",
                "Wiki 消息已发送但消费者长时间未接管，任务已取消，快照仍然有效时已重新入队",
                "");
        verify(taskCommandPort, never()).createTask(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void busyWorkspaceShouldFailIngestTaskWithoutStartingWork() {
        // WIKI-02：同一工作台已有 Wiki 任务在写入时，新任务必须让路，不能并发重灌
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                eq("workspace-1"), eq("source-1")))
                .thenReturn(List.of("READY"));
        when(workspaceLock.tryAcquire(anyString(), anyString(), any())).thenReturn(false);

        service.runSourceIngestNow("task-3", "workspace-1", "source-1");

        verify(taskCommandPort).failTask(
                "task-3", "WIKI_WORKSPACE_BUSY",
                "同一工作台已有 Wiki 任务在写入，本次 ingest 稍后重试", "WIKI_WORKSPACE_BUSY", true);
        verify(transactionExecutor, never()).execute(any());
        verify(workspaceLock, never()).release(anyString(), anyString());
    }

    @Test
    void retractShouldKeepDeletionsWhenReingestOfOtherSourcesFails() {
        // WIKI-02：删除落地后，其余来源重灌失败不得回滚删除，也不得把任务判为失败
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("workspace-1")))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class),
                eq("task-4"), eq("workspace-1"), eq("source-1"), eq("WIKI_RETRACT")))
                .thenReturn(List.of());
        when(knowledgeQueryService.findSourceBackedWikiItemIds("workspace-1", "source-1"))
                .thenReturn(List.of("page-a"));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("workspace-1")))
                .thenReturn(List.of("source-2", "source-3"));
        when(transactionExecutor.execute(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());

        service.runSourceRetractNow("task-4", "workspace-1", "source-1");

        verify(knowledgeCommandService).deleteItemForInternalExecution("workspace-1", "page-a");
        verify(taskCommandPort, never()).failTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(taskCommandPort).completeTask(
                eq("task-4"),
                eq("WIKI_RETRACTED"),
                org.mockito.ArgumentMatchers.contains("2 个来源重灌失败"),
                eq("source-1"));
        verify(workspaceLock).release("wiki:workspace:workspace-1", "task-4");
    }
}
