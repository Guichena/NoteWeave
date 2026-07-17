package com.noteweave.research;

import com.noteweave.common.BusinessException;
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
        if (!"INCREMENTAL_V1".equals(normalized)) {
            throw new BusinessException(
                    "RESEARCH_AGENT_EXECUTION_MODE_INVALID", "Unsupported research agent execution mode");
        }
        Map<String, String> current = jdbcTemplate.query("""
                select agent_execution_mode, status from research_run where id = ? for update
                """, rs -> rs.next() ? Map.of("mode", rs.getString(1), "status", rs.getString(2)) : null,
                researchRunId);
        if (current == null || java.util.Set.of("COMPLETED", "FAILED", "CANCELLED").contains(current.get("status"))) {
            throw new BusinessException(
                    "RESEARCH_AGENT_EXECUTION_MODE_NOT_SWITCHABLE", "Research run is missing or terminal");
        }
        jdbcTemplate.update("""
                update research_run set agent_execution_mode = ?, updated_at = current_timestamp where id = ?
                """, normalized, researchRunId);
    }
}
