package com.noteweave.knowledge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.source.SourceMessagingMode;
import com.noteweave.task.TaskCommandPort;
import com.noteweave.workspace.WorkspaceQueryPort;
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
    void disabledWikiShouldCancelIngestTaskWithoutStartingWork() {
        when(workspaceQueryPort.isWikiEnabled("workspace-1")).thenReturn(false);

        service.runSourceIngestNow("task-1", "workspace-1", "source-1");

        verify(taskCommandPort).cancelTask(
                "task-1", "WIKI_DISABLED", "Wiki 构建已关闭，跳过资料 ingest", "source-1");
        verifyNoInteractions(jdbcTemplate, knowledgeQueryService, knowledgeCommandService, knowledgeGovernanceService);
    }

    @Test
    void disabledWikiShouldCancelRetractTaskWithoutStartingWork() {
        when(workspaceQueryPort.isWikiEnabled("workspace-1")).thenReturn(false);

        service.runSourceRetractNow("task-2", "workspace-1", "source-1");

        verify(taskCommandPort).cancelTask(
                "task-2", "WIKI_DISABLED", "Wiki 构建已关闭，跳过资料 retract", "source-1");
        verifyNoInteractions(jdbcTemplate, knowledgeQueryService, knowledgeCommandService, knowledgeGovernanceService);
    }

    @Test
    void failedIngestShouldRecordFailedTerminalStateWithoutKafkaRedelivery() {
        when(workspaceQueryPort.isWikiEnabled("workspace-1")).thenReturn(true);
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
    }

    @Test
    void failedRetractShouldRecordFailedTerminalStateWithoutKafkaRedelivery() {
        when(workspaceQueryPort.isWikiEnabled("workspace-1")).thenReturn(true);
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
    }
}
