package com.noteweave.research;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Finds only runs whose durable execution mode authorizes automatic coordination. */
@Service
public class ResearchAgentCoordinatorRunScanner {
    private final JdbcTemplate jdbcTemplate;

    public ResearchAgentCoordinatorRunScanner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> findEligibleRunIds(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query("""
                select id from research_run
                where status = 'RUNNING' and agent_execution_mode = 'INCREMENTAL_V1'
                order by updated_at, id limit ?
                """, (rs, rowNum) -> rs.getString(1), safeLimit);
    }
}
