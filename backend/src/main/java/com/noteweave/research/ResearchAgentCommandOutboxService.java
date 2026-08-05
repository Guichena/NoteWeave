package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates sanitized, minimal MA4 Kafka command envelopes for durable agent tasks. */
@Service
public class ResearchAgentCommandOutboxService {

    public static final String AGENT_COMMAND_TOPIC = "noteweave.research.agent.command";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String commandTopic;

    public ResearchAgentCommandOutboxService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${noteweave.kafka.topics.research-agent-command:noteweave.research.agent.command}") String commandTopic) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.commandTopic = commandTopic == null || commandTopic.isBlank() ? AGENT_COMMAND_TOPIC : commandTopic.trim();
    }

    @Transactional
    public CommandReceipt enqueue(String agentTaskId) {
        OutboxRow existing = findByTaskId(agentTaskId);
        if (existing != null) return new CommandReceipt(existing.id(), true);
        return createDelivery(agentTaskId, 1);
    }

    @Transactional
    public CommandReceipt enqueueRetry(String agentTaskId) {
        OutboxRow existing = findByTaskId(agentTaskId);
        if (existing == null) return createDelivery(agentTaskId, 1);
        TaskScope scope = requireReadyScope(agentTaskId);
        int nextDelivery = existing.deliveryNo() + 1;
        jdbcTemplate.update("""
                update research_agent_outbox
                set payload_json = ?, delivery_no = ?, status = 'READY', sent_at = null,
                    attempt_count = 0, lease_owner = null, lease_until = null,
                    next_attempt_at = null, last_error = null, dead_lettered_at = null,
                    updated_at = current_timestamp
                where id = ?
                """, payload(scope, agentTaskId, existing.id(), nextDelivery), nextDelivery, existing.id());
        return new CommandReceipt(existing.id(), false);
    }

    private CommandReceipt createDelivery(String agentTaskId, int deliveryNo) {
        TaskScope scope = requireReadyScope(agentTaskId);
        String outboxId = Ids.newId();
        try {
            jdbcTemplate.update("""
                    insert into research_agent_outbox(
                        id, research_run_id, research_agent_task_id, delivery_no, topic, message_key, payload_json, status
                    ) values (?, ?, ?, ?, ?, ?, ?, 'READY')
                    """, outboxId, scope.runId(), agentTaskId, deliveryNo, commandTopic, agentTaskId,
                    payload(scope, agentTaskId, outboxId, deliveryNo));
            return new CommandReceipt(outboxId, false);
        } catch (DataIntegrityViolationException duplicate) {
            OutboxRow replay = findByTaskId(agentTaskId);
            if (replay != null) return new CommandReceipt(replay.id(), true);
            throw duplicate;
        }
    }

    private TaskScope requireReadyScope(String agentTaskId) {
        TaskScope scope = jdbcTemplate.query("""
                select rat.research_run_id, rr.agent_execution_mode, rat.status
                from research_agent_task rat join research_run rr on rr.id = rat.research_run_id
                where rat.id = ?
                """, rs -> rs.next() ? new TaskScope(rs.getString(1), rs.getString(2), rs.getString(3)) : null, agentTaskId);
        if (scope == null) throw new BusinessException("RESEARCH_AGENT_TASK_NOT_FOUND", "Research agent task does not exist");
        if (!"INCREMENTAL_V1".equals(scope.executionMode())) {
            throw new BusinessException("RESEARCH_AGENT_OUTBOX_MODE_INVALID", "Agent command outbox requires INCREMENTAL_V1 run");
        }
        if (!"PENDING".equals(scope.status()) && !"RETRY_WAIT".equals(scope.status()) && !"EXPIRED".equals(scope.status())) {
            throw new BusinessException("RESEARCH_AGENT_OUTBOX_TASK_NOT_READY", "Agent task is not ready for a command");
        }
        return scope;
    }

    private String payload(TaskScope scope, String agentTaskId, String outboxId, int deliveryNo) {
        String idempotencyKey = "agent-command:" + agentTaskId + ":delivery:" + deliveryNo;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", "research-agent-command.v1");
        payload.put("command_id", outboxId);
        payload.put("research_run_id", scope.runId());
        payload.put("agent_task_id", agentTaskId);
        payload.put("idempotency_key", idempotencyKey);
        payload.put("delivery_attempt", deliveryNo);
        return Json.write(objectMapper, payload);
    }

    private OutboxRow findByTaskId(String taskId) {
        return jdbcTemplate.query("select id, delivery_no from research_agent_outbox where research_agent_task_id = ?",
                rs -> rs.next() ? new OutboxRow(rs.getString(1), rs.getInt(2)) : null, taskId);
    }

    public record CommandReceipt(String outboxId, boolean idempotentReplay) { }
    private record OutboxRow(String id, int deliveryNo) { }
    private record TaskScope(String runId, String executionMode, String status) { }
}
