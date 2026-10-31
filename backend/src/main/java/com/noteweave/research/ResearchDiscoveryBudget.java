package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 将已消耗预算和未结算预留合并计数，避免并发调度超额。调用方持有 Run 锁。 */
final class ResearchDiscoveryBudget {
    static final Map<String, Long> LIMITS = Map.of("llm_calls", 4096L, "search_calls", 256L);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    ResearchDiscoveryBudget(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    boolean allows(String runId, Map<String, Long> requested, int copies) {
        return allows(runId, requested, copies, "ANY");
    }
    boolean allows(String runId, Map<String, Long> requested, int copies, String role) {
        if (copies < 0 || requested.values().stream().anyMatch(value -> value == null || value < 0)) return false;
        List<String> stored = jdbc.queryForList("select limits_json from research_discovery_loop where research_run_id = ?", String.class, runId);
        if (stored.isEmpty()) return true;
        JsonNode limits = read(stored.get(0));
        if (limits.isMissingNode()) limits = mapper.valueToTree(LIMITS);
        java.math.BigInteger[] used = {java.math.BigInteger.ZERO, java.math.BigInteger.ZERO};
        // 使用当前读，避免 MySQL REPEATABLE_READ 中较早建立的快照漏掉已提交预留。
        jdbc.query("select reserved_json, consumed_json, state from research_budget_reservation where research_run_id = ? for update", rs -> {
            JsonNode counters = read(rs.getString("RESERVED".equals(rs.getString(3)) ? 1 : 2));
            used[0] = used[0].add(java.math.BigInteger.valueOf(counters.path("llm_calls").asLong()));
            used[1] = used[1].add(java.math.BigInteger.valueOf(counters.path("search_calls").asLong()));
        }, runId);
        // 保留请求的完整数值，极大计数也不能通过整数溢出绕过上限。
        var slots = java.math.BigInteger.valueOf(copies);
        return used[0].add(java.math.BigInteger.valueOf(requested.getOrDefault("llm_calls", 0L)).multiply(slots))
                    .compareTo(java.math.BigInteger.valueOf(limits.path("llm_calls").asLong())) <= 0
                && used[1].add(java.math.BigInteger.valueOf(requested.getOrDefault("search_calls", 0L)).multiply(slots))
                    .compareTo(java.math.BigInteger.valueOf(limits.path("search_calls").asLong())) <= 0;
    }
    JsonNode read(String raw) {
        try { JsonNode value = mapper.readTree(raw); return value.isTextual() ? mapper.readTree(value.asText()) : value; }
        catch (Exception exception) { throw new IllegalStateException("宽搜索预算 JSON 不符合约定", exception); }
    }
}
