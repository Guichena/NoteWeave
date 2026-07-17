package com.noteweave.research;

import com.noteweave.worker.ResearchOutboxPublisher;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Publishes MA4 agent command outbox rows with at-least-once delivery semantics. */
@Service
public class ResearchAgentCommandDispatcher {

    private final JdbcTemplate jdbcTemplate;
    private final ResearchOutboxPublisher publisher;

    public ResearchAgentCommandDispatcher(JdbcTemplate jdbcTemplate, ResearchOutboxPublisher publisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.publisher = publisher;
    }

    public DispatchResponse dispatchReady(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<OutboxRow> rows = jdbcTemplate.query("""
                select rao.id, rao.delivery_no, rao.topic, rao.message_key, rao.payload_json
                from research_agent_outbox rao
                join research_run rr on rr.id = rao.research_run_id
                join research_agent_task rat on rat.id = rao.research_agent_task_id
                where rao.status = 'READY'
                  and rr.agent_execution_mode = 'INCREMENTAL_V1'
                  and rr.status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                  and (rat.status in ('PENDING', 'EXPIRED')
                    or (rat.status = 'RETRY_WAIT' and (rat.next_attempt_at is null or rat.next_attempt_at <= current_timestamp)))
                order by rao.created_at, rao.id limit ?
                """, (rs, rowNum) -> new OutboxRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5)), safeLimit);
        return publish(rows);
    }

    /** Canary-safe dispatch boundary: never publishes READY rows owned by another run. */
    public DispatchResponse dispatchReadyForRun(String runId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<OutboxRow> rows = jdbcTemplate.query("""
                select rao.id, rao.delivery_no, rao.topic, rao.message_key, rao.payload_json
                from research_agent_outbox rao
                join research_run rr on rr.id = rao.research_run_id
                join research_agent_task rat on rat.id = rao.research_agent_task_id
                where rao.research_run_id = ? and rao.status = 'READY'
                  and rr.agent_execution_mode = 'INCREMENTAL_V1'
                  and rr.status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                  and (rat.status in ('PENDING', 'EXPIRED')
                    or (rat.status = 'RETRY_WAIT' and (rat.next_attempt_at is null or rat.next_attempt_at <= current_timestamp)))
                order by rao.created_at, rao.id limit ?
                """, (rs, rowNum) -> new OutboxRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                runId, safeLimit);
        return publish(rows);
    }

    private DispatchResponse publish(List<OutboxRow> rows) {
        int dispatched = 0;
        for (OutboxRow row : rows) {
            publisher.publish(row.topic(), row.messageKey(), row.payloadJson());
            int marked = jdbcTemplate.update("""
                    update research_agent_outbox
                    set status = 'SENT', sent_at = current_timestamp, updated_at = current_timestamp
                    where id = ? and delivery_no = ? and status = 'READY'
                    """, row.id(), row.deliveryNo());
            dispatched += marked;
        }
        return new DispatchResponse(dispatched);
    }

    public record DispatchResponse(int dispatchedCount) { }
    private record OutboxRow(String id, int deliveryNo, String topic, String messageKey, String payloadJson) { }
}
