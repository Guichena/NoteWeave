package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the guarded transition into or out of the durable agent execution mode. */
@Service
public class ResearchAgentExecutionModeService {
    private final JdbcTemplate jdbcTemplate;

    public ResearchAgentExecutionModeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void setMode(String researchRunId, String mode) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase();
        if (!List.of("SEQUENTIAL_V1", "SEQUENTIAL_V2", "LOCAL_PARALLEL", "INCREMENTAL_V1")
                .contains(normalized)) {
            throw new BusinessException(
                    "RESEARCH_AGENT_EXECUTION_MODE_INVALID", "Unsupported research agent execution mode");
        }
        Map<String, String> current = jdbcTemplate.query("""
                select agent_execution_mode, status from research_run where id = ? for update
                """, rs -> rs.next() ? Map.of("mode", rs.getString(1), "status", rs.getString(2)) : null,
                researchRunId);
        if (current == null || List.of("COMPLETED", "FAILED", "CANCELLED").contains(current.get("status"))) {
            throw new BusinessException(
                    "RESEARCH_AGENT_EXECUTION_MODE_NOT_SWITCHABLE", "Research run is missing or terminal");
        }
        if ("INCREMENTAL_V1".equals(current.get("mode")) && !"INCREMENTAL_V1".equals(normalized)) {
            Integer activeTasks = jdbcTemplate.queryForObject("""
                    select count(*) from research_agent_task
                    where research_run_id = ?
                    and status in ('PENDING', 'CLAIMED', 'RUNNING', 'RETRY_WAIT', 'EXPIRED')
                    """, Integer.class, researchRunId);
            if (activeTasks != null && activeTasks > 0) {
                throw new BusinessException("RESEARCH_AGENT_EXECUTION_MODE_ACTIVE_TASKS",
                        "Cancel or finish active research agent tasks before leaving INCREMENTAL_V1");
            }
            Integer incrementalHistory = jdbcTemplate.queryForObject(
                    "select count(*) from research_agent_task where research_run_id = ?",
                    Integer.class, researchRunId);
            Integer checkpoints = jdbcTemplate.queryForObject(
                    "select count(*) from research_agent_checkpoint where research_run_id = ?",
                    Integer.class, researchRunId);
            Integer advancements = jdbcTemplate.queryForObject(
                    "select count(*) from research_agent_run_advancement where research_run_id = ?",
                    Integer.class, researchRunId);
            if ((incrementalHistory != null && incrementalHistory > 0)
                    || (checkpoints != null && checkpoints > 0)
                    || (advancements != null && advancements > 0)) {
                throw new BusinessException("RESEARCH_AGENT_EXECUTION_MODE_INCREMENTAL_HISTORY",
                        "Incremental execution history cannot be handed back to a legacy mode");
            }
        }
        jdbcTemplate.update("""
                update research_run set agent_execution_mode = ?, updated_at = current_timestamp where id = ?
                """, normalized, researchRunId);
    }
}
