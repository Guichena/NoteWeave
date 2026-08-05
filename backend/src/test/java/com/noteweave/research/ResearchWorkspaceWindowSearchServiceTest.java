package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResearchWorkspaceWindowSearchServiceTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchWorkspaceWindowSearchService searchService;

    private String taskId;
    private String snapshotId;
    private String secondWindowId;
    private ResearchAgentTaskService.ClaimedTask claim;

    @BeforeEach
    void setUp() {
        String workspaceId = Ids.newId();
        String runId = Ids.newId();
        String parentTaskId = Ids.newId();
        String fileId = Ids.newId();
        String sourceId = Ids.newId();
        snapshotId = Ids.newId();
        String chunkId = Ids.newId();
        String firstWindowId = Ids.newId();
        secondWindowId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'window-search', 'ACTIVE')", workspaceId);
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, ref_count)
                values (?, ?, ?, ?, 10, 1)
                """, fileId, workspaceId, "test/window-search", "a".repeat(64));
        jdbcTemplate.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
                values (?, ?, ?, 'Long source', 'UPLOAD', 'READY', 'PARSED', 'INDEXED')
                """, sourceId, workspaceId, fileId);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'PARSED', 'INDEXED')
                """, snapshotId, sourceId, fileId, "test/window-search", "a".repeat(64));
        jdbcTemplate.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate)
                values (?, ?, ?, ?, 1, 'combined source text', 10)
                """, chunkId, workspaceId, sourceId, snapshotId);
        jdbcTemplate.update("insert into source_window(id, source_chunk_id, window_no, content) values (?, ?, 1, 'introductory material only')", firstWindowId, chunkId);
        jdbcTemplate.update("insert into source_window(id, source_chunk_id, window_no, content) values (?, ?, 2, 'the hidden calibration constant is cobalt-47')", secondWindowId, chunkId);
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode)
                values (?, ?, ?, 'find calibration constant', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId);
        String rowId = Ids.newId();
        jdbcTemplate.update("insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')", rowId, runId);
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, 'entity-1:answer', 'answer', 'old', 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), runId, rowId);
        taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "window-search-task", "window-search-idem", 1, "DEEP_CELL", "entity-1", "branch-main",
                1, 1, List.of("entity-1:answer"), Map.of("search_calls", 1),
                List.of(new ResearchAgentTaskService.TargetCellBinding("entity-1:answer", 0)),
                new ResearchAgentTaskService.TaskExecutionContext("research-default", Map.of(
                        "source_scope", List.of(Map.of(
                                "source_id", sourceId, "source_title", "Long source",
                                "source_snapshot_id", snapshotId, "source_window_id", firstWindowId,
                                "sample_text", "introductory material only"))),
                        Map.of("query", "find calibration constant")))).taskId();
        claim = taskService.claimTask(new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 60));
    }

    @Test
    void shouldFindRelevantTextInASecondWindowOfTheFrozenSnapshot() {
        List<ResearchWorkspaceWindowSearchService.WindowHit> hits = searchService.search(command("worker-a"));

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).sourceWindowId()).isEqualTo(secondWindowId);
        assertThat(hits.get(0).sourceSnapshotId()).isEqualTo(snapshotId);
        assertThat(hits.get(0).windowText()).contains("cobalt-47");
    }

    @Test
    void shouldRejectAWorkerThatDoesNotOwnTheCurrentLease() {
        assertThatThrownBy(() -> searchService.search(command("worker-b")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_STALE_LEASE");
    }

    private ResearchWorkspaceWindowSearchService.SearchCommand command(String worker) {
        return new ResearchWorkspaceWindowSearchService.SearchCommand(
                taskId, worker, claim.leaseEpoch(), claim.fencingToken(),
                List.of("What is the hidden calibration constant cobalt-47?"), 8);
    }
}
