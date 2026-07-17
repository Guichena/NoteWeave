package com.noteweave.research;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Database-backed MA6 health guard for new initial waves only. */
@Service
public class ResearchAgentRolloutGuard {
    private final JdbcTemplate jdbcTemplate;
    private final ResearchAgentRolloutPolicy policy;
    private final boolean enabled;
    private final int windowMinutes;

    public ResearchAgentRolloutGuard(
            JdbcTemplate jdbcTemplate,
            @Value("${noteweave.research.agent.rollout-guard-enabled:true}") boolean enabled,
            @Value("${noteweave.research.agent.rollout-window-minutes:15}") int windowMinutes,
            @Value("${noteweave.research.agent.rollout-minimum-task-sample:10}") int minimumTaskSample,
            @Value("${noteweave.research.agent.rollout-max-terminal-failure-rate:0.20}") double maxTerminalFailureRate,
            @Value("${noteweave.research.agent.rollout-max-delivery-failure-rate:0.20}") double maxDeliveryFailureRate,
            @Value("${noteweave.research.agent.rollout-max-rejected-merge-rate:0.30}") double maxRejectedMergeRate) {
        if (windowMinutes < 1 || windowMinutes > 1440) {
            throw new IllegalArgumentException("rollout observation window must be between 1 and 1440 minutes");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.windowMinutes = windowMinutes;
        this.policy = new ResearchAgentRolloutPolicy(
                minimumTaskSample,
                maxTerminalFailureRate,
                maxDeliveryFailureRate,
                maxRejectedMergeRate);
    }

    public ResearchAgentRolloutPolicy.Decision evaluate() {
        if (!enabled) {
            return new ResearchAgentRolloutPolicy.Decision(
                    ResearchAgentRolloutPolicy.Action.CONTINUE,
                    List.of("ROLLOUT_GUARD_DISABLED"),
                    Map.of());
        }
        int offset = -windowMinutes;
        long taskCount = count("""
                select count(*) from research_agent_task
                where created_at >= timestampadd(minute, ?, current_timestamp)
                """, offset);
        long terminalFailures = count("""
                select count(*) from research_agent_task
                where created_at >= timestampadd(minute, ?, current_timestamp)
                  and status = 'FAILED'
                """, offset);
        long deliveryFailures = count("""
                select count(*) from research_agent_delivery_failure
                where created_at >= timestampadd(minute, ?, current_timestamp)
                """, offset);
        long rejectedMerges = count("""
                select count(*) from research_cell_merge
                where merged_at >= timestampadd(minute, ?, current_timestamp)
                  and decision = 'REJECTED'
                """, offset);
        long acceptedMerges = count("""
                select count(*) from research_cell_merge
                where merged_at >= timestampadd(minute, ?, current_timestamp)
                  and decision in ('ACCEPTED', 'IDEMPOTENT_REPLAY')
                """, offset);
        return policy.evaluate(new ResearchAgentRolloutPolicy.HealthSnapshot(
                taskCount, terminalFailures, deliveryFailures, rejectedMerges, acceptedMerges));
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
