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
    private final WikiIngestService service = new WikiIngestService(
            jdbcTemplate,
            workspaceQueryPort,
            knowledgeQueryService,
            knowledgeCommandService,
            knowledgeGovernanceService,
            taskCommandPort,
            new ObjectMapper(),
            messagingMode,
            transactionExecutor
    );

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
    void orphanedSentWikiTaskShouldBeCancelledByBackgroundReconciliation() {
        when(jdbcTemplate.queryForList(
                anyString(), eq(String.class), any(Timestamp.class)))
                .thenReturn(List.of("task-orphaned"));

        int reconciled = service.reconcileOrphanedPendingWikiTasks();

        org.assertj.core.api.Assertions.assertThat(reconciled).isEqualTo(1);
        verify(taskCommandPort).cancelTask(
                "task-orphaned",
                "WIKI_DELIVERY_ORPHANED",
                "Wiki 消息已发送但消费者长时间未接管，任务已安全取消，可通过资料重建重新执行",
                "");
    }
}
