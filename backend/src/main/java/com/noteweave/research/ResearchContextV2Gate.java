package com.noteweave.research;

import com.noteweave.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Blocks task work when a conversation Research Run's frozen Context is unavailable. */
@Service
class ResearchContextV2Gate {
    private final JdbcTemplate jdbc;
    private final ResearchBriefCompiler briefs;

    ResearchContextV2Gate(JdbcTemplate jdbc, ResearchBriefCompiler briefs) {
        this.jdbc = jdbc;
        this.briefs = briefs;
    }

    void requireReadable(String runId) {
        RunContext context = jdbc.query("""
                select context_snapshot_id, execution_question from research_run where id = ?
                """, rs -> rs.next() ? new RunContext(rs.getString(1), rs.getString(2)) : null, runId);
        if (context == null) {
            throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research Run does not exist",
                    HttpStatus.NOT_FOUND);
        }
        if (context.snapshotId() == null) return;
        if (context.executionQuestion() == null) {
            throw new BusinessException("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH",
                    "Frozen Research Context is unavailable", HttpStatus.CONFLICT);
        }
        briefs.compile(runId, context.executionQuestion());
    }

    private record RunContext(String snapshotId, String executionQuestion) {}
}
