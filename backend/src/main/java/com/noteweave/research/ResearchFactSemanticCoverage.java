package com.noteweave.research;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只使用服务端接受的独立语义审计记录，不将尚未判断的事实对当作不同事实。 */
final class ResearchFactSemanticCoverage {
    private final JdbcTemplate jdbc;
    ResearchFactSemanticCoverage(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Map<String, String> relations(String run, String cell) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query("""
                select left_cluster_key, right_cluster_key, equivalence_verdict, verdict from research_semantic_check
                where research_run_id = ? and cell_key = ? and verdict in ('NOT_CONTRADICTORY','CONTRADICTORY')
                  and equivalence_verdict in ('EQUIVALENT','NOT_EQUIVALENT')
                  and left_cluster_key like 'fact:%' and right_cluster_key like 'fact:%'
                order by semantic_revision, checked_at, id
                """, rs -> { result.put(pair(rs.getString(1), rs.getString(2)), "CONTRADICTORY".equals(rs.getString(4)) ? "CONTRADICTORY" : rs.getString(3)); }, run, cell);
        return result;
    }

    List<Map<String, String>> pendingPairs(List<String> ids, Map<String, String> known, int limit) {
        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) for (int j = 0; j < i; j++)
            if (!List.of("EQUIVALENT", "NOT_EQUIVALENT").contains(known.getOrDefault(pair(ids.get(j), ids.get(i)), "UNKNOWN")) && result.size() < limit)
                result.add(Map.of("left_cluster_key", ids.get(j), "right_cluster_key", ids.get(i)));
        return List.copyOf(result);
    }

    boolean allChecked(List<String> ids, Map<String, String> known) { return pendingPairs(ids, known, 1).isEmpty(); }

    Map<String, String> groups(Collection<String> ids, Map<String, String> known) {
        Map<String, String> groups = new LinkedHashMap<>();
        Map<String, List<String>> members = new LinkedHashMap<>();
        ids.stream().sorted().forEach(id -> {
            String group = members.entrySet().stream().filter(entry -> entry.getValue().stream()
                    .allMatch(other -> "EQUIVALENT".equals(known.get(pair(id, other))))).map(Map.Entry::getKey).findFirst().orElse(id);
            groups.put(id, group); members.computeIfAbsent(group, ignored -> new ArrayList<>()).add(id);
        });
        return groups;
    }

    int certifiedCount(Collection<String> ids, Map<String, String> known) {
        Map<String, String> groups = groups(ids, known);
        Map<String, List<String>> members = new LinkedHashMap<>();
        groups.forEach((id, group) -> members.computeIfAbsent(group, ignored -> new ArrayList<>()).add(id));
        // 相互矛盾的等价关系不能用于数量判定。
        for (var group : members.values()) for (String a : group) for (String b : group)
            if ("NOT_EQUIVALENT".equals(known.get(pair(a, b)))) return 0;
        List<List<String>> distinct = new ArrayList<>();
        for (var group : members.values()) {
            boolean certified = distinct.stream().allMatch(other -> group.stream().allMatch(a -> other.stream()
                    .allMatch(b -> "NOT_EQUIVALENT".equals(known.get(pair(a, b))))));
            if (certified) distinct.add(group);
        }
        return distinct.size();
    }

    static String pair(String left, String right) { return left.compareTo(right) < 0 ? left + "|" + right : right + "|" + left; }
}
