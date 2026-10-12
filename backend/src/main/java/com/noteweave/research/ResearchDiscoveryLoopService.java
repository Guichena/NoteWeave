package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Json;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 持久化宽搜索与填表的交替状态；协调器事务持有 Run 锁。 */
@Service
class ResearchDiscoveryLoopService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResearchAgentFeatureFlagService flags;
    private final ResearchAgentTaskCoordinatorService coordinator;
    ResearchDiscoveryLoopService(JdbcTemplate jdbc, ObjectMapper mapper, ResearchAgentFeatureFlagService flags,
                                 ResearchAgentTaskCoordinatorService coordinator) {
        this.jdbc = jdbc; this.mapper = mapper; this.flags = flags; this.coordinator = coordinator;
    }
    @org.springframework.transaction.annotation.Transactional
    Receipt advance(String runId, int waveNo) {
        if (!flags.enabledForRun(runId, ResearchAgentFeatureFlagService.WIDE_DISCOVERY)) return new Receipt("DISABLED", null);
        initialize(runId);
        String phase = jdbc.queryForObject("select phase from research_discovery_loop where research_run_id = ?", String.class, runId);
        if ("STOPPED".equals(phase)) return new Receipt("STOPPED", null);
        if (jdbc.queryForObject("select count(*) from research_agent_task where research_run_id = ? and status in ('PENDING','CLAIMED','RUNNING','RETRY_WAIT','EXPIRED')", Integer.class, runId) > 0)
            return new Receipt("DISCOVERY_PENDING", null);
        List<Round> rounds = jdbc.query("select round_no, round_status, new_proposal_count from research_discovery_round where research_run_id = ? order by round_no desc",
                (rs, n) -> new Round(rs.getInt(1), rs.getString(2), rs.getInt(3)), runId);
        if (!rounds.isEmpty() && !"COMPLETED".equals(rounds.get(0).status())) return stop(runId, "DISCOVERY_TASK_FAILED");
        if (!rounds.isEmpty()) {
            jdbc.update("update research_discovery_loop set phase = 'POPULATE_CELLS', updated_at = current_timestamp where research_run_id = ?", runId);
            var filled = coordinator.planAndEnqueueForWave(runId, waveNo + 1);
            if (filled.createdTaskCount() > 0 || filled.idempotentReplayCount() > 0) return new Receipt("POPULATE_CELLS", filled);
            String reason = jdbc.queryForObject("select stop_reason from research_discovery_loop where research_run_id = ?", String.class, runId);
            if ("LOOP_BUDGET_EXHAUSTED".equals(reason)) return stop(runId, reason);
        }
        if (rounds.size() >= 2 && rounds.get(0).novel() == 0 && rounds.get(1).novel() == 0) return stop(runId, "DISCOVERY_SATURATED");
        if (rounds.size() >= 3) return stop(runId, "DISCOVERY_ROUND_LIMIT");
        if (!new ResearchDiscoveryBudget(jdbc, mapper).allows(runId, Map.of("llm_calls", 4L, "search_calls", 9L), 1))
            return stop(runId, "LOOP_BUDGET_EXHAUSTED");
        jdbc.update("update research_discovery_loop set phase = 'EXPAND_ROWS', updated_at = current_timestamp where research_run_id = ?", runId);
        return new Receipt("EXPAND_ROWS", coordinator.planDiscoveryTask(runId, waveNo + 1, rounds.size() + 1));
    }
    @org.springframework.transaction.annotation.Transactional
    void initialize(String runId) {
        if (!enabled(runId)) return;
        jdbc.queryForObject("select id from research_run where id = ? for update", String.class, runId);
        if (jdbc.queryForObject("select count(*) from research_discovery_loop where research_run_id = ?", Integer.class, runId) == 0)
            jdbc.update("insert into research_discovery_loop(research_run_id, phase, limits_json) values (?, 'EXPAND_ROWS', ?)",
                    runId, Json.write(mapper, ResearchDiscoveryBudget.LIMITS));
    }
    boolean enabled(String runId) { return flags.enabledForRun(runId, ResearchAgentFeatureFlagService.WIDE_DISCOVERY); }
    boolean budgetStopped(String runId) {
        return jdbc.queryForObject("select count(*) from research_discovery_loop where research_run_id = ? and stop_reason = 'LOOP_BUDGET_EXHAUSTED'", Integer.class, runId) > 0;
    }
    private Receipt stop(String runId, String reason) {
        jdbc.update("update research_discovery_loop set phase = 'STOPPED', stop_reason = ?, updated_at = current_timestamp where research_run_id = ?", reason, runId);
        return new Receipt("STOPPED", null);
    }
    record Receipt(String outcome, ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization) { }
    private record Round(int number, String status, int novel) { }
}
