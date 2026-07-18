package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import java.sql.Timestamp;
import java.time.Instant;
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
class ResearchAgentCommandOutboxServiceTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentCommandOutboxService outboxService;

    private String runId;
    private String agentTaskId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        runId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')", workspaceId, "outbox-test");
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, runId);
        jdbcTemplate.update("insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode) values (?, ?, ?, 'q', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')", runId, workspaceId, parentTaskId);
        agentTaskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "outbox-agent-task", "outbox-agent-idem", 1, "DEEP_CELL", "entity-1", "branch-main", 0, 1,
                List.of("entity-1:method"), Map.of("llm_calls", 1)
        )).taskId();
    }

    @Test
    void shouldEnqueueIdempotentMinimalVersionedCommand() throws Exception {
        ResearchAgentCommandOutboxService.CommandReceipt first = outboxService.enqueue(agentTaskId);
        ResearchAgentCommandOutboxService.CommandReceipt replay = outboxService.enqueue(agentTaskId);

        assertThat(first.outboxId()).isEqualTo(replay.outboxId());
        String payloadJson = jdbcTemplate.queryForObject("select payload_json from research_agent_outbox where id = ?", String.class, first.outboxId());
        Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
        assertThat(payload).containsEntry("schema_version", "research-agent-command.v1")
                .containsEntry("research_run_id", runId)
                .containsEntry("agent_task_id", agentTaskId);
        assertThat(payload).doesNotContainKeys("target_cells", "budget", "api_key", "prompt", "source_scope");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_outbox where research_agent_task_id = ?", Integer.class, agentTaskId)).isEqualTo(1);
    }

    @Test
    void shouldPublishReadyCommandThenMarkOutboxSent() {
        outboxService.enqueue(agentTaskId);
        java.util.List<String> published = new java.util.ArrayList<>();
        ResearchAgentCommandDispatcher dispatcher = new ResearchAgentCommandDispatcher(
                jdbcTemplate,
                (topic, messageKey, payloadJson) -> published.add(topic + ":" + messageKey + ":" + payloadJson)
        );

        ResearchAgentCommandDispatcher.DispatchResponse response = dispatcher.dispatchReadyForRun(runId, 10);

        assertThat(response.dispatchedCount()).isEqualTo(1);
        assertThat(published).hasSize(1);
        assertThat(published.get(0)).contains("noteweave.research.agent.command", agentTaskId);
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_outbox where research_agent_task_id = ?", String.class, agentTaskId)).isEqualTo("SENT");
    }

    @Test
    void shouldDispatchHighestPriorityReadyCommandBeforeFifoOrder() {
        outboxService.enqueue(agentTaskId);
        String urgentTaskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "urgent-agent-task", "urgent-agent-idem", 1, "COUNTERFACTUAL", "entity-2", "branch-main", 0, 1,
                List.of("entity-2:answer"), Map.of("llm_calls", 1)
        )).taskId();
        outboxService.enqueue(urgentTaskId);
        jdbcTemplate.update("update research_agent_task set priority_score = 10, priority_reason = 'NORMAL' where id = ?", agentTaskId);
        jdbcTemplate.update("update research_agent_task set priority_score = 100, priority_reason = 'HIGH_RISK' where id = ?", urgentTaskId);
        java.util.List<String> publishedKeys = new java.util.ArrayList<>();
        ResearchAgentCommandDispatcher dispatcher = new ResearchAgentCommandDispatcher(
                jdbcTemplate, (topic, messageKey, payloadJson) -> publishedKeys.add(messageKey));

        var response = dispatcher.dispatchReadyForRun(runId, 1);

        assertThat(response.dispatchedCount()).isEqualTo(1);
        assertThat(publishedKeys).containsExactly(urgentTaskId);
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_outbox where research_agent_task_id = ?", String.class, agentTaskId))
                .isEqualTo("READY");
    }

    @Test
    void shouldDispatchOnlyReadyCommandsForRequestedRun() {
        outboxService.enqueue(agentTaskId);
        String anotherRunId = createRunAndAgentTask("another-run");
        String anotherAgentTaskId = jdbcTemplate.queryForObject(
                "select id from research_agent_task where research_run_id = ?", String.class, anotherRunId);
        outboxService.enqueue(anotherAgentTaskId);
        java.util.List<String> publishedKeys = new java.util.ArrayList<>();
        ResearchAgentCommandDispatcher dispatcher = new ResearchAgentCommandDispatcher(
                jdbcTemplate, (topic, messageKey, payloadJson) -> publishedKeys.add(messageKey));

        var response = dispatcher.dispatchReadyForRun(runId, 10);

        assertThat(response.dispatchedCount()).isEqualTo(1);
        assertThat(publishedKeys).containsExactly(agentTaskId);
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_outbox where research_agent_task_id = ?", String.class, agentTaskId)).isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_outbox where research_agent_task_id = ?", String.class, anotherAgentTaskId)).isEqualTo("READY");
    }

    @Test
    void shouldLeaveOutboxReadyWhenPublisherFails() {
        outboxService.enqueue(agentTaskId);
        ResearchAgentCommandDispatcher dispatcher = new ResearchAgentCommandDispatcher(
                jdbcTemplate, (topic, messageKey, payloadJson) -> { throw new IllegalStateException("injected publish failure"); });

        assertThatThrownBy(() -> dispatcher.dispatchReadyForRun(runId, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected publish failure");
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_outbox where research_agent_task_id = ?", String.class, agentTaskId)).isEqualTo("READY");
    }

    @Test
    void shouldNotMarkNewRetryDeliverySentAfterPublishingStaleDelivery() {
        outboxService.enqueue(agentTaskId);
        ResearchAgentCommandDispatcher dispatcher = new ResearchAgentCommandDispatcher(
                jdbcTemplate, (topic, messageKey, payloadJson) -> outboxService.enqueueRetry(agentTaskId));

        var response = dispatcher.dispatchReadyForRun(runId, 10);

        assertThat(response.dispatchedCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_outbox where research_agent_task_id = ?", String.class, agentTaskId)).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject("select delivery_no from research_agent_outbox where research_agent_task_id = ?", Integer.class, agentTaskId)).isEqualTo(2);
    }

    @Test
    void shouldNotPublishWhenRunLeavesIncrementalModeOrBecomesTerminal() {
        outboxService.enqueue(agentTaskId);
        java.util.List<String> published = new java.util.ArrayList<>();
        ResearchAgentCommandDispatcher dispatcher = new ResearchAgentCommandDispatcher(
                jdbcTemplate, (topic, messageKey, payloadJson) -> published.add(messageKey));

        jdbcTemplate.update("update research_run set agent_execution_mode = 'SEQUENTIAL_V1' where id = ?", runId);
        assertThat(dispatcher.dispatchReadyForRun(runId, 10).dispatchedCount()).isZero();

        jdbcTemplate.update("update research_run set agent_execution_mode = 'INCREMENTAL_V1', status = 'FAILED' where id = ?", runId);
        assertThat(dispatcher.dispatchReadyForRun(runId, 10).dispatchedCount()).isZero();
        assertThat(published).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_outbox where research_agent_task_id = ?", String.class, agentTaskId)).isEqualTo("READY");
    }

    @Test
    void shouldHonorRetryBackoffBeforePublishingReadyDelivery() {
        outboxService.enqueue(agentTaskId);
        jdbcTemplate.update("update research_agent_task set status = 'RETRY_WAIT', next_attempt_at = ? where id = ?",
                Timestamp.from(Instant.now().plusSeconds(60)), agentTaskId);
        java.util.List<String> published = new java.util.ArrayList<>();
        ResearchAgentCommandDispatcher dispatcher = new ResearchAgentCommandDispatcher(
                jdbcTemplate, (topic, messageKey, payloadJson) -> published.add(messageKey));

        assertThat(dispatcher.dispatchReadyForRun(runId, 10).dispatchedCount()).isZero();
        assertThat(published).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select status from research_agent_outbox where research_agent_task_id = ?", String.class, agentTaskId)).isEqualTo("READY");

        jdbcTemplate.update("update research_agent_task set next_attempt_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), agentTaskId);
        assertThat(dispatcher.dispatchReadyForRun(runId, 10).dispatchedCount()).isEqualTo(1);
        assertThat(published).containsExactly(agentTaskId);
    }

    private String createRunAndAgentTask(String suffix) {
        String anotherRunId = Ids.newId();
        String parentTaskId = Ids.newId();
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, anotherRunId);
        jdbcTemplate.update("insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode) values (?, ?, ?, 'q', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')", anotherRunId, workspaceId, parentTaskId);
        taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                anotherRunId, "outbox-agent-task-" + suffix, "outbox-agent-idem-" + suffix, 1,
                "DEEP_CELL", "entity-1", "branch-main", 0, 1,
                List.of("entity-1:method"), Map.of("llm_calls", 1)));
        return anotherRunId;
    }
}
