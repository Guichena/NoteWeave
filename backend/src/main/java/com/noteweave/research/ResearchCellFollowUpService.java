package com.noteweave.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * F4-03：研究单元格追问的定位线索。
 *
 * <p>对象与维度名只用于选择线索：命中已定位单元格时优先返回其结论与通过资格检查的证据，
 * 但未核实单元格即使有相关网页也不能被解释成已核实；问题只有指代、无法定位对象时返回空，
 * 不以猜测补齐。</p>
 */
@Service
public class ResearchCellFollowUpService {

    public static final String DERIVATION_VERSION = "research-cell-followup-v1";
    private static final int MAX_MATCHES = 8;
    private static final int MAX_CONCLUSION_CHARS = 400;

    private final JdbcTemplate jdbcTemplate;

    public ResearchCellFollowUpService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Match> match(String workspaceId, String conversationId, String question, int cutoffSeq) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<CellRow> cells = jdbcTemplate.query("""
                select r.id as research_run_id, c.id as cell_id, c.cell_key, c.candidate_value,
                       c.cell_status, c.evidence_refs_json, rrow.row_key, rrow.source_title,
                       col.column_key, col.label
                from research_run r
                join conversation_message m on m.id = r.answer_message_id
                join research_cell c on c.research_run_id = r.id
                join research_row rrow on rrow.id = c.research_row_id
                left join research_column col
                  on col.research_run_id = c.research_run_id
                 and col.column_key = c.column_key
                 and col.plan_revision = (
                     select max(current.plan_revision) from research_column current
                     where current.research_run_id = c.research_run_id
                       and current.column_key = c.column_key)
                where r.workspace_id = ? and m.conversation_id = ?
                  and m.context_status = 'CURRENT' and m.message_seq <= ?
                order by r.created_at desc, c.cell_key asc
                """, (rs, rowNum) -> new CellRow(
                rs.getString("research_run_id"), rs.getString("cell_id"), rs.getString("cell_key"),
                rs.getString("candidate_value"), rs.getString("cell_status"),
                rs.getString("evidence_refs_json"), rs.getString("row_key"),
                rs.getString("source_title"), rs.getString("column_key"), rs.getString("label")),
                workspaceId, conversationId, Math.max(0, cutoffSeq));
        if (cells.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        boolean objectMatchedAnywhere = false;
        for (CellRow cell : cells) {
            boolean objectMatched = containsAny(normalized, cell.sourceTitle(), cell.rowKey());
            boolean dimensionMatched = containsAny(normalized, cell.label(), cell.columnKey());
            objectMatchedAnywhere |= objectMatched;
            if (!objectMatched && !dimensionMatched) {
                continue;
            }
            scored.add(new Scored(cell, objectMatched, dimensionMatched,
                    (objectMatched ? 2 : 0) + (dimensionMatched ? 1 : 0)));
        }
        if (!objectMatchedAnywhere) {
            // 只有指代、没有可定位对象：不猜测命中。
            return List.of();
        }
        int topScore = scored.stream().mapToInt(Scored::score).max().orElse(0);
        long topCount = scored.stream().filter(value -> value.score() == topScore).count();
        boolean ambiguous = topCount > 1;
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing((Scored value) -> "VERIFIED".equals(status(value.cell())) ? 0 : 1))
                .limit(MAX_MATCHES)
                .map(value -> new Match(
                        value.cell().researchRunId(),
                        value.cell().cellId(),
                        value.cell().cellKey(),
                        display(value.cell().sourceTitle(), value.cell().rowKey()),
                        display(value.cell().label(), value.cell().columnKey()),
                        mapStatus(value.cell().cellStatus()),
                        conclusion(value.cell()),
                        "VERIFIED".equals(mapStatus(value.cell().cellStatus()))
                                ? evidenceIds(value.cell().evidenceRefsJson()) : List.of(),
                        value.objectMatched(),
                        value.dimensionMatched(),
                        ambiguous && value.score() == topScore,
                        DERIVATION_VERSION))
                .toList();
    }

    private static String status(CellRow cell) {
        return cell.cellStatus() == null ? "" : cell.cellStatus().toUpperCase(Locale.ROOT);
    }

    private static boolean containsAny(String question, String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized.length() >= 2 && question.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String display(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? (fallback == null ? "" : fallback) : preferred;
    }

    private static String conclusion(CellRow cell) {
        String value = cell.candidateValue() == null ? "" : cell.candidateValue().strip();
        if (value.length() > MAX_CONCLUSION_CHARS) {
            value = value.substring(0, MAX_CONCLUSION_CHARS) + "…";
        }
        return value;
    }

    private static List<String> evidenceIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
            return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String mapStatus(String status) {
        String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "VERIFIED" -> "VERIFIED";
            case "PARTIAL" -> "PARTIAL";
            case "PENDING", "RUNNING" -> "PENDING";
            case "FAILED", "ERROR" -> "FAILED";
            default -> "UNVERIFIED";
        };
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》…]+", "");
    }

    /**
     * 回答层边界：未核实且没有歧义的单元格必须明确说不能当已核实事实。
     * 已核实单元格和无法定位的歧义不写进这段，避免把已核实结论再说成不足。
     */
    public static String unverifiedBoundary(List<Match> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        List<Match> unverified = matches.stream()
                .filter(match -> !match.ambiguous() && !"VERIFIED".equals(match.status()))
                .toList();
        if (unverified.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("这些研究单元格尚未核实，不能当作已核实事实：");
        for (Match match : unverified) {
            builder.append("\n- ")
                    .append(match.objectName() == null || match.objectName().isBlank() ? "未命名对象" : match.objectName())
                    .append(" / ")
                    .append(match.dimensionName() == null || match.dimensionName().isBlank() ? "未命名维度" : match.dimensionName())
                    .append("（").append(match.status()).append("）");
        }
        return builder.toString();
    }

    private record CellRow(String researchRunId, String cellId, String cellKey, String candidateValue,
                           String cellStatus, String evidenceRefsJson, String rowKey,
                           String sourceTitle, String columnKey, String label) {
    }

    private record Scored(CellRow cell, boolean objectMatched, boolean dimensionMatched, int score) {
    }

    /**
     * 单元格定位结果。
     *
     * @param verified 只有 VERIFIED 单元格才为 true，且此时才可能携带证据
     * @param ambiguous 同一最高分有多个候选，调用方应展示歧义而不是替用户选择
     */
    public record Match(
            String researchRunId,
            String cellId,
            String cellKey,
            String objectName,
            String dimensionName,
            String status,
            String conclusion,
            List<String> evidenceIds,
            boolean objectMatched,
            boolean dimensionMatched,
            boolean ambiguous,
            String derivationVersion
    ) {
        public Match {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            if (!"VERIFIED".equals(status) && !evidenceIds.isEmpty()) {
                throw new IllegalArgumentException("only verified cells may carry verified evidence");
            }
        }
    }
}
