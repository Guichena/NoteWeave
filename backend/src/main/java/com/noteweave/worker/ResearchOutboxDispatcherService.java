package com.noteweave.worker;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ResearchOutboxDispatcherService {

    private static final String RESEARCH_TOPIC = "noteweave.research.run";

    private final JdbcTemplate jdbcTemplate;
    private final ResearchWorkerRunClient researchWorkerRunClient;

    public ResearchOutboxDispatcherService(
            JdbcTemplate jdbcTemplate,
            ResearchWorkerRunClient researchWorkerRunClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.researchWorkerRunClient = researchWorkerRunClient;
    }

    public ResearchOutboxDispatchResponse dispatchReadyResearchRuns(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<OutboxRow> rows = jdbcTemplate.query("""
                select id, task_id
                from task_outbox
                where topic = ? and status = 'READY'
                order by created_at asc, id asc
                limit ?
                """, (rs, rowNum) -> new OutboxRow(
                rs.getString("id"),
                rs.getString("task_id")
        ), RESEARCH_TOPIC, safeLimit);

        int dispatched = 0;
        for (OutboxRow row : rows) {
            researchWorkerRunClient.runTask(row.taskId());
            jdbcTemplate.update("""
                    update task_outbox
                    set status = 'SENT', sent_at = current_timestamp
                    where id = ? and status = 'READY'
                    """, row.outboxId());
            dispatched++;
        }
        return new ResearchOutboxDispatchResponse(dispatched);
    }

    private record OutboxRow(String outboxId, String taskId) {
    }
}
