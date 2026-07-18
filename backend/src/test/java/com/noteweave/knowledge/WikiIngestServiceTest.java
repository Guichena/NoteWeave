package com.noteweave.knowledge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    private final WikiIngestService service = new WikiIngestService(
            jdbcTemplate,
            workspaceQueryPort,
            knowledgeQueryService,
            knowledgeCommandService,
            knowledgeGovernanceService,
            taskCommandPort,
            new ObjectMapper(),
            messagingMode
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
}
