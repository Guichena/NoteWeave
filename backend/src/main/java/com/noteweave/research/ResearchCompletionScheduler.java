package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 可解释的需求收益与成本排序；每个明确需求先获得研究机会，再使用历史净增益。 */
final class ResearchCompletionScheduler {
    static Map<String,Integer> scores(JdbcTemplate jdbc, ObjectMapper mapper, String run) {
        var coverage = new ResearchRequirementCoverageService(jdbc,mapper);
        if (!coverage.fullCompletion(run) || !coverage.enforces(run)) return Map.of();
        Map<String,Integer> scores = new LinkedHashMap<>();
        var progress = mapper.valueToTree(coverage.readModel(run));
        for (var req : progress.path("requirements")) if (!"SATISFIED".equals(req.path("status").asText())) {
            if (coverage.answerPolicy(run) && !req.path("mandatory").asBoolean(true)) continue;
            int weight = "EXPLICIT".equals(req.path("origin").asText()) ? 30000 : 100;
            scores.merge(req.path("cell_key").asText(),weight,Integer::sum);
        }
        for (String cell : jdbc.queryForList("select cell_key from research_cell where research_run_id = ? order by cell_key",String.class,run)) {
            var attempts = jdbc.query("""
                    select gain.new_fact_count,gain.new_requirement_count,metric.llm_calls,metric.read_page_count,gain.resolved_conflict_count
                    from research_cell_gain gain join research_runtime_metric metric on metric.completion_id = gain.completion_id
                    join research_cell cell on cell.research_run_id = gain.research_run_id and cell.cell_key = gain.cell_key
                        and cell.plan_revision = gain.plan_revision and cell.entity_set_version = gain.entity_set_version
                    where gain.research_run_id = ? and gain.cell_key = ? and metric.role in ('DEEP_CELL','COUNTERFACTUAL')
                    order by gain.event_seq desc limit 2
                    """,(rs,n) -> new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)},run,cell);
            int adjustment = attempts.isEmpty() ? 3000 : 0;
            for (long[] attempt : attempts) adjustment += (int)((attempt[0] * 100 + attempt[1] * 500 + attempt[4] * 300) / Math.max(1,attempt[2]+attempt[3]));
            if (attempts.size() == 2 && attempts.stream().allMatch(attempt -> attempt[0]+attempt[1]+attempt[4] == 0)) adjustment -= 200;
            // 已定位相关材料的阅读动作成本更低，但仍必须重新核验材料。
            int[] revision = jdbc.query("select plan_revision,entity_set_version from research_cell where research_run_id = ? and cell_key = ?",rs -> rs.next() ? new int[]{rs.getInt(1),rs.getInt(2)} : null,run,cell);
            if (revision != null && !new ResearchDeferredMaterialService(jdbc,mapper).load(run,cell,revision[0],revision[1]).isEmpty()) adjustment += 150;
            scores.merge(cell,adjustment,Integer::sum);
        }
        return Map.copyOf(scores);
    }
}
