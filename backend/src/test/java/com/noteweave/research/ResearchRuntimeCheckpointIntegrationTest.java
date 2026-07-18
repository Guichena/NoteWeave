package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.task.TaskService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResearchRuntimeCheckpointIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TaskService taskService;
    @Autowired ResearchRunService researchRunService;

    @Test
    void runtimeCheckpointShouldPersistEveryRoundIdempotently() {
        String workspaceId = UUID.randomUUID().toString();
        String researchRunId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'checkpoint-test', 'ACTIVE')",
                workspaceId
        );
        String taskId = taskService.createTask(
                workspaceId, "RESEARCH_RUN", "RESEARCH_RUN", researchRunId, "PLANNING", "checkpoint test"
        );
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key, source_scope_json, control_pack_json, status
                ) values (?, ?, ?, 'checkpoint?', 'DEFAULT', '[]', '{}', 'RUNNING')
                """, researchRunId, workspaceId, taskId);

        Map<String, Object> checkpoint = Map.of(
                "checkpoint_no", 1,
                "snapshot_type", "RESEARCH_LOOP_CHECKPOINT",
                "state_ledger", Map.of("active_branch_id", "branch-main", "rows", List.of(), "cells", List.of()),
                "loop_decision", Map.of("decision", "READ_MORE"),
                "evidence_cards", List.of(),
                "read_windows", List.of()
        );
        researchRunService.persistRuntimeCheckpoint(
                taskId, Map.of("research_checkpoint_candidate", checkpoint)
        );
        researchRunService.persistRuntimeCheckpoint(
                taskId, Map.of("research_checkpoint_candidate", checkpoint)
        );

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from research_execution_checkpoint where research_run_id = ? and checkpoint_no = 1",
                Integer.class,
                researchRunId
        );
        assertThat(count).isEqualTo(1);
        assertThat(researchRunService.getCheckpoint(workspaceId, researchRunId, 1).checkpointNo()).isEqualTo(1);

        Map<String, Object> conflictingCheckpoint = Map.of(
                "checkpoint_no", 1,
                "snapshot_type", "RESEARCH_LOOP_CHECKPOINT",
                "state_ledger", Map.of("active_branch_id", "branch-other", "rows", List.of(), "cells", List.of()),
                "loop_decision", Map.of("decision", "COUNTERFACTUAL_RECHECK"),
                "evidence_cards", List.of(),
                "read_windows", List.of()
        );
        assertThatThrownBy(() -> researchRunService.persistRuntimeCheckpoint(
                taskId, Map.of("research_checkpoint_candidate", conflictingCheckpoint)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable research checkpoint conflict");
    }
}
