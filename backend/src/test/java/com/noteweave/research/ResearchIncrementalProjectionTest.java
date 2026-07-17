package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.worker.WorkerCompleteRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ResearchIncrementalProjectionTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchRunService researchRunService;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentProjectionService projectionService;

    private String workspaceId;
    private String taskId;
    private String runId;

    @BeforeEach
    void setUp() {
        workspaceId = Ids.newId();
        taskId = Ids.newId();
        runId = Ids.newId();
        String rowId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')", workspaceId, "projection-test");
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", taskId, workspaceId, runId);
        jdbcTemplate.update("insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status) values (?, ?, ?, 'q', 'DEFAULT', '[]', 'RUNNING')", runId, workspaceId, taskId);
        jdbcTemplate.update("insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')", rowId, runId);
        jdbcTemplate.update("insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, cell_status, repair_count) values (?, ?, ?, 'entity-1:method', 'method', 'CANDIDATE_READY', 0)", Ids.newId(), runId, rowId);
    }

    @Test
    void legacyWorkerCompletionShouldNotBypassIncrementalCanonicalFinalization() {
        jdbcTemplate.update(
                "update research_run set agent_execution_mode = 'INCREMENTAL_V1' where id = ?",
                runId);
        taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "agent-task", "agent-idem", 1, "DEEP_CELL", "entity-1", "branch-main", 0, 1,
                List.of("entity-1:method"), Map.of("llm_calls", 1)
        ));

        assertThatThrownBy(() -> researchRunService.completeFromWorker(taskId, new WorkerCompleteRequest(
                "MARKDOWN", "incremental report", Map.of(), "incremental", List.of()
        ))).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED");
        Map<String, Object> projection = projectionService.project(runId);

        assertThat(jdbcTemplate.queryForObject("select count(*) from research_cell where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(projection.get("execution_mode")).isEqualTo("INCREMENTAL_V1");
        assertThat((Integer) projection.get("task_count")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from research_run where id = ?", String.class, runId))
                .isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_trace where research_run_id = ? and trace_type = 'FINAL_REPORT'", Integer.class, runId)).isZero();
    }
}
