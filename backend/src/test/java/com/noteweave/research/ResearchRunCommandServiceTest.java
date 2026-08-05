package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.storage.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ResearchRunCommandServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResearchRunCommandService commandService;

    @Autowired
    private ObjectStorage storage;

    @Test
    void resumesCanonicalAgentRunFromExecutionCheckpoint() {
        String workspaceId = createWorkspace("command-resume");
        ResearchRunResponse sourceRun = commandService.createRun(workspaceId, request());
        String objectKey = "workspace/%s/research/%s/checkpoints/1.json".formatted(
                workspaceId, sourceRun.researchRunId());
        byte[] checkpointPayload = "{\"checkpoint_no\":1}".getBytes(StandardCharsets.UTF_8);
        storage.write("noteweave-derived", objectKey, checkpointPayload);
        jdbcTemplate.update("""
                insert into research_execution_checkpoint(
                    id, research_run_id, checkpoint_no, snapshot_type, object_key,
                    payload_sha256, content_size, active_branch_key, final_loop_decision, summary_json
                ) values (?, ?, 1, 'LOOP_END', ?, ?, ?, 'branch-main', 'CONTINUE', '{}')
                """,
                Ids.newId(), sourceRun.researchRunId(), objectKey,
                ResearchCheckpointIntegrity.sha256(checkpointPayload), checkpointPayload.length
        );

        ResearchRunResponse resumed = commandService.resumeFromCheckpoint(
                workspaceId, sourceRun.researchRunId(), 1);

        assertThat(resumed.status()).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForMap("""
                select resumed_from_research_run_id, resumed_from_checkpoint_no, agent_execution_mode
                from research_run where id = ?
                """, resumed.researchRunId()))
                .containsEntry("RESUMED_FROM_RESEARCH_RUN_ID", sourceRun.researchRunId())
                .containsEntry("RESUMED_FROM_CHECKPOINT_NO", 1)
                .containsEntry("AGENT_EXECUTION_MODE", "INCREMENTAL_V1");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_cell where research_run_id = ?
                """, Integer.class, resumed.researchRunId())).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("""
                select task_status from task where id = ?
                """, String.class, resumed.taskId())).isEqualTo("RUNNING");
    }

    @Test
    void rejectsResumeWhenCheckpointPayloadDigestDoesNotMatch() {
        String workspaceId = createWorkspace("command-resume-corrupt");
        ResearchRunResponse sourceRun = commandService.createRun(workspaceId, request());
        String objectKey = "workspace/%s/research/%s/checkpoints/1-corrupt.json".formatted(
                workspaceId, sourceRun.researchRunId());
        byte[] payload = "{\"checkpoint_no\":1}".getBytes(StandardCharsets.UTF_8);
        storage.write("noteweave-derived", objectKey, payload);
        jdbcTemplate.update("""
                insert into research_execution_checkpoint(
                    id, research_run_id, checkpoint_no, snapshot_type, object_key,
                    payload_sha256, content_size, active_branch_key, final_loop_decision, summary_json
                ) values (?, ?, 1, 'LOOP_END', ?, ?, ?, 'branch-main', 'CONTINUE', '{}')
                """, Ids.newId(), sourceRun.researchRunId(), objectKey, "f".repeat(64), payload.length);

        assertThatThrownBy(() -> commandService.resumeFromCheckpoint(
                workspaceId, sourceRun.researchRunId(), 1))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RESEARCH_CHECKPOINT_CORRUPTED"));
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_run where resumed_from_research_run_id = ?
                """, Integer.class, sourceRun.researchRunId())).isZero();
    }

    @Test
    void switchesOnlyToSupportedExecutionModes() {
        String workspaceId = createWorkspace("command-mode");
        ResearchRunResponse run = commandService.createRun(workspaceId, request());

        commandService.setAgentExecutionMode(run.researchRunId(), "sequential_v2");
        assertThat(jdbcTemplate.queryForObject("""
                select agent_execution_mode from research_run where id = ?
                """, String.class, run.researchRunId())).isEqualTo("SEQUENTIAL_V2");

        assertThatThrownBy(() -> commandService.setAgentExecutionMode(run.researchRunId(), "unknown"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RESEARCH_AGENT_EXECUTION_MODE_INVALID"));
    }

    private String createWorkspace(String name) {
        String workspaceId = Ids.newId();
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, status)
                values (?, 'local-user', ?, 'ACTIVE')
                """, workspaceId, name);
        return workspaceId;
    }

    private CreateResearchRunRequest request() {
        return new CreateResearchRunRequest(
                "How should canonical research commands be isolated?",
                "DEFAULT",
                "Verify command ownership",
                "Markdown report",
                List.of("Preserve checkpoint lineage"),
                null,
                "STANDARD",
                "TECHNICAL",
                List.of(),
                "WEB_ONLY",
                List.of()
        );
    }
}
