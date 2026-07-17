package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentRunAdvancementServiceTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentRunAdvancementService service;
    private String runId;

    @BeforeEach
    void setUp() {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        runId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'advance', 'ACTIVE')", workspaceId);
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, runId);
        jdbcTemplate.update("insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode) values (?, ?, ?, 'q', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')", runId, workspaceId, parentTaskId);
    }

    @Test
    void shouldWriteOneCheckpointAndReplaySameAdvanceReceipt() {
        var first = service.advance(command("advance:wave-1", 0, "sha256:" + "1".repeat(64)));
        var replay = service.advance(command("advance:wave-1", 0, "sha256:" + "1".repeat(64)));

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.checkpointId()).isEqualTo(first.checkpointId());
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_checkpoint where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_run_advancement where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void shouldRejectStalePredecessorAndLegacyMode() {
        service.advance(command("advance:wave-1", 0, "sha256:" + "2".repeat(64)));
        assertThatThrownBy(() -> service.advance(command("advance:wave-2", 0, "sha256:" + "3".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_ADVANCEMENT_STALE_CURSOR");
        jdbcTemplate.update("update research_run set agent_execution_mode = 'SEQUENTIAL_V1' where id = ?", runId);
        assertThatThrownBy(() -> service.advance(command("advance:legacy", 1, "sha256:" + "4".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_ADVANCEMENT_MODE_INVALID");
    }

    @Test
    void shouldAllowExactlyOneCoordinatorToAdvanceTheSamePredecessor() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CyclicBarrier(2);
        try {
            Future<String> first = pool.submit(() -> advanceAfterGate(gate, "advance:owner-a", "sha256:" + "5".repeat(64)));
            Future<String> second = pool.submit(() -> advanceAfterGate(gate, "advance:owner-b", "sha256:" + "6".repeat(64)));

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder("COMMITTED", "RESEARCH_AGENT_ADVANCEMENT_STALE_CURSOR");
            assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_checkpoint where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_run_advancement where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private String advanceAfterGate(CyclicBarrier gate, String key, String digest) throws Exception {
        gate.await();
        try {
            service.advance(command(key, 0, digest));
            return "COMMITTED";
        } catch (BusinessException exception) {
            return exception.code();
        }
    }

    private ResearchAgentRunAdvancementService.AdvanceCommand command(String key, int expectedSeq, String digest) {
        return new ResearchAgentRunAdvancementService.AdvanceCommand(
                runId, key, "coordinator-a", expectedSeq, digest, 1, 1, 0, 1,
                "ledger:" + digest, 1, 1, 1, Map.of("state", "ok"), Map.of("decision", "NEXT_WAVE"));
    }
}
