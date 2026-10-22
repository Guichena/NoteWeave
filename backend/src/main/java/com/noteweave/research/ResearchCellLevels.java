package com.noteweave.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 单元格按依据强弱分级：已核实、有出处、相关材料、未找到。
 *
 * <p>核验门槛不变，只是没通过核验的单元格不再一律当作空白：合并时被拒的候选如果带着原文，
 * 按候选被拒的原因和证据关系判断它是有出处的结论还是只与研究对象有关的材料。
 * 有出处：证据资格被拒（原文存在、关系为支持，只是数字或版本与引用片段对不上），或弱支持证据的支持分大于 0
 * （例如问题要求官方来源、只找到二手来源）；相关材料：弱支持证据的支持分为 0，原文与研究对象有关但没有直接回答这一列。</p>
 */
@Component
class ResearchCellLevels {

    static final String VERIFIED = "VERIFIED";
    static final String SOURCED = "SOURCED";
    static final String RELATED = "RELATED";
    static final String NOT_FOUND = "NOT_FOUND";

    /** 每条线索最多带的原文条数。 */
    static final int MAX_QUOTES = 3;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    ResearchCellLevels(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    record Quote(String evidenceKey, String quote, String sourceTitle, String sourceUrl) { }

    /** 一个没通过核验的单元格里最有用的一条线索。 */
    record Lead(String cellKey, String level, String value, List<Quote> quotes) { }

    /** 每个单元格最好的一条线索，有出处优先于相关材料，同级取最近的一次；没有线索的单元格不出现在结果里。 */
    Map<String, Lead> leads(String runId) {
        List<String[]> rows = jdbcTemplate.query("""
                select a.cell_key, a.candidate_value, a.evidence_ids_json, m.reason_code, task.execution_context_json
                from research_agent_candidate a
                join research_cell_merge m on m.candidate_id = a.id
                left join research_agent_task task on task.id = a.task_id
                left join research_cell c on c.research_run_id = a.research_run_id and c.cell_key = a.cell_key
                where a.research_run_id = ? and m.decision = 'REJECTED'
                  and (c.cell_status is null or c.cell_status <> 'VERIFIED')
                  and m.reason_code in ('EVIDENCE_QUALIFICATION_REJECTED', 'NOT_ENOUGH_INFO', 'REQUIREMENT_COVERAGE_INCOMPLETE')
                order by m.merged_at desc, m.id desc
                """, (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)},
                runId);
        Map<String, Lead> best = new LinkedHashMap<>();
        for (String[] row : rows) {
            if (row[1] == null || row[1].isBlank()) continue;
            Lead current = best.get(row[0]);
            if (current != null && SOURCED.equals(current.level())) continue;
            List<EvidenceRow> evidence = evidence(runId, keys(row[2]));
            var scope = ResearchSourceScopePolicy.scope(ResearchSourceScopePolicy.contract(row[4], objectMapper), row[0]);
            if (evidence.stream().anyMatch(item -> ResearchSourceScopePolicy.reasons(scope, item.url(), item.title(), item.quote()).stream()
                    .anyMatch(reason -> Set.of("SOURCE_VERSION_MISMATCH", "SOURCE_DEVELOPMENT_BRANCH_MISMATCH").contains(reason)))) continue;
            List<Quote> quotes = evidence.stream()
                    .filter(item -> item.quote() != null && !item.quote().isBlank())
                    .limit(MAX_QUOTES)
                    .map(item -> new Quote(item.key(), item.quote(), item.title(), item.url()))
                    .toList();
            if (quotes.isEmpty()) continue;
            String level = level(row[3], evidence);
            if (level == null || (current != null && !SOURCED.equals(level))) continue;
            best.put(row[0], new Lead(row[0], level, row[1].strip(), quotes));
        }
        return best;
    }

    /** 候选被拒的原因与证据关系对应的级别；无法判断时返回 null。 */
    static String level(String reasonCode, List<EvidenceRow> evidence) {
        if ("EVIDENCE_QUALIFICATION_REJECTED".equals(reasonCode)
                || ResearchRequirementCoverageService.INCOMPLETE.equals(reasonCode)) return SOURCED;
        if (!"NOT_ENOUGH_INFO".equals(reasonCode) || evidence.isEmpty()
                || !evidence.stream().allMatch(item -> "WEAK_SUPPORT".equals(item.relation()))) {
            return null;
        }
        return evidence.stream().anyMatch(item -> item.supportPpm() > 0) ? SOURCED : RELATED;
    }

    /** 单元格级别：已核实看单元格状态，其余看线索，没有线索的是未找到。 */
    static String levelOf(String cellStatus, Lead lead) {
        if (VERIFIED.equals(cellStatus)) return VERIFIED;
        return lead == null ? NOT_FOUND : lead.level();
    }

    record EvidenceRow(String key, String relation, int supportPpm, String quote, String title, String url) { }

    private List<EvidenceRow> evidence(String runId, List<String> keys) {
        List<EvidenceRow> rows = new ArrayList<>();
        for (String key : keys.stream().distinct().toList()) {
            rows.addAll(jdbcTemplate.query("""
                    select relation_type, coalesce(support_score_ppm, 0), quote_text, source_title, source_url
                    from source_evidence where research_run_id = ? and evidence_key = ?
                    """, (rs, rowNum) -> new EvidenceRow(key, rs.getString(1), rs.getInt(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)), runId, key));
        }
        return rows;
    }

    private List<String> keys(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).toList();
        } catch (Exception exception) {
            return List.of();
        }
    }
}
