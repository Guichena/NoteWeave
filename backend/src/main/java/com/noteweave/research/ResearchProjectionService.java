package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.context.ResearchProjection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * F3-03：从研究运行、单元格与需求派生 P0/P1/P2 投影。
 *
 * <p>只使用已证实单元格的值与证据；冲突、冻结和失败单元格按未核实处理，不把规划过程、
 * 失败候选或完整报告注入日常对话视图。历史报告缺结构时只从报告里确定性命中执行摘要，
 * 找不到就明确标为上游缺失。</p>
 */
@Service
public class ResearchProjectionService {

    public static final String PROJECTION_VERSION = "research-projection-v1";
    private static final int MAX_CELLS = 30;
    private static final int MAX_CONCLUSION_CHARS = 800;
    private static final int MAX_SUMMARY_CHARS = 2_000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchProjectionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ResearchProjection build(String runId, String level) {
        RunRow run = jdbcTemplate.query("""
                select id, workspace_id, question, status, final_report_title, final_report_markdown,
                       trace_summary, answer_message_id
                from research_run where id = ?
                """, rs -> rs.next() ? new RunRow(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("question"),
                rs.getString("status"), rs.getString("final_report_title"),
                rs.getString("final_report_markdown"), rs.getString("trace_summary"),
                rs.getString("answer_message_id")) : null, runId);
        if (run == null) {
            throw new IllegalArgumentException("research run not found: " + runId);
        }
        List<CellRow> cellRows = jdbcTemplate.query("""
                select c.id, c.cell_key, c.candidate_value, c.cell_status, c.evidence_refs_json,
                       r.row_key, r.source_title, col.column_key, col.label
                from research_cell c
                join research_row r on r.id = c.research_row_id
                left join research_column col
                  on col.research_run_id = c.research_run_id
                 and col.column_key = c.column_key
                 and col.plan_revision = (
                     select max(current.plan_revision) from research_column current
                     where current.research_run_id = c.research_run_id
                       and current.column_key = c.column_key)
                where c.research_run_id = ?
                order by c.cell_key asc, c.id asc
                """, (rs, rowNum) -> new CellRow(
                rs.getString("id"), rs.getString("cell_key"), rs.getString("candidate_value"),
                rs.getString("cell_status"), rs.getString("evidence_refs_json"),
                rs.getString("row_key"), rs.getString("source_title"),
                rs.getString("column_key"), rs.getString("label")), runId);
        Map<String, RequirementAccumulator> requirements = new LinkedHashMap<>();
        List<ResearchProjection.Cell> cells = new ArrayList<>();
        for (CellRow row : cellRows) {
            String dimensionKey = blank(row.columnKey()) ? row.cellKey() : row.columnKey();
            RequirementAccumulator accumulator = requirements.computeIfAbsent(
                    dimensionKey, ignored -> new RequirementAccumulator(dimensionKey,
                            blank(row.label()) ? dimensionKey : row.label()));
            String status = mapCellStatus(row.cellStatus());
            accumulator.observe(status);
            if (cells.size() < MAX_CELLS) {
                String objectName = blank(row.sourceTitle()) ? row.rowKey() : row.sourceTitle();
                List<String> evidenceIds = "VERIFIED".equals(status)
                        ? evidenceIds(row.evidenceRefsJson()) : List.of();
                cells.add(new ResearchProjection.Cell(
                        row.cellId(),
                        blank(objectName) ? row.cellKey() : objectName,
                        blank(row.label()) ? dimensionKey : row.label(),
                        status,
                        conclusion(row, status),
                        evidenceIds));
            }
        }
        List<ResearchProjection.Requirement> requirementList = requirements.values().stream()
                .map(RequirementAccumulator::toRequirement)
                .toList();
        String title = blank(run.title()) ? run.question() : run.title();
        String summary = executiveSummary(run.reportMarkdown(), run.traceSummary());
        int pending = (int) cells.stream()
                .filter(cell -> "PENDING".equals(cell.status()))
                .count();
        ResearchProjection projection = new ResearchProjection(
                ResearchProjection.SCHEMA_VERSION,
                run.id(),
                run.workspaceId(),
                "",
                blank(run.answerMessageId()) ? "" : run.answerMessageId(),
                mapRunStatus(run.status()),
                ResearchProjection.LEVEL_P2,
                title == null ? "" : title.trim(),
                summary,
                requirementList,
                List.copyOf(cells),
                List.of(),
                List.of(),
                pending,
                PROJECTION_VERSION,
                0,
                cellRows.size() > MAX_CELLS,
                List.of());
        return projection.asLevel(level);
    }

    public String toJson(ResearchProjection projection) {
        try {
            return objectMapper.writeValueAsString(projection);
        } catch (Exception ex) {
            throw new IllegalStateException("research projection JSON failed", ex);
        }
    }

    private String conclusion(CellRow row, String status) {
        String value = blank(row.candidateValue()) ? "" : row.candidateValue().strip();
        if (value.length() > MAX_CONCLUSION_CHARS) {
            value = value.substring(0, MAX_CONCLUSION_CHARS) + "…";
        }
        if (!"VERIFIED".equals(status) && value.isBlank()) {
            return switch (status) {
                case "PARTIAL" -> "部分核实，缺少通过资格检查的完整证据。";
                case "UNVERIFIED" -> "未核实：结论存在冲突或未通过审核。";
                case "FAILED" -> "研究失败，未形成可用结论。";
                default -> "研究尚未完成。";
            };
        }
        return value;
    }

    private List<String> evidenceIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String executiveSummary(String reportMarkdown, String traceSummary) {
        String report = reportMarkdown == null ? "" : reportMarkdown;
        String[] lines = report.split("\\R", -1);
        StringBuilder summary = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (!inside) {
                if (trimmed.matches("(?i)##\\s*(执行摘要|executive summary).*")) {
                    inside = true;
                }
                continue;
            }
            if (trimmed.startsWith("## ")) {
                break;
            }
            if (!trimmed.isEmpty()) {
                if (summary.length() > 0) {
                    summary.append('\n');
                }
                summary.append(trimmed);
            }
        }
        String value = summary.length() > 0 ? summary.toString() : (traceSummary == null ? "" : traceSummary);
        value = value.strip();
        return value.length() <= MAX_SUMMARY_CHARS ? value : value.substring(0, MAX_SUMMARY_CHARS) + "…";
    }

    private String mapRunStatus(String status) {
        String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (normalized.contains("COMPLETED")) return "COMPLETED";
        if (normalized.contains("CANCEL")) return "CANCELLED";
        if (normalized.contains("FAIL") || normalized.contains("ABANDON") || normalized.contains("HANDOFF")) {
            return "FAILED";
        }
        if (normalized.contains("RUNNING") || normalized.contains("PROCESS")) return "RUNNING";
        return "PENDING";
    }

    private String mapCellStatus(String status) {
        String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "VERIFIED", "COMPLETED_VERIFIED" -> "VERIFIED";
            case "PARTIAL" -> "PARTIAL";
            case "PENDING", "RUNNING" -> "PENDING";
            case "FAILED", "ERROR" -> "FAILED";
            default -> "UNVERIFIED";
        };
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record RunRow(String id, String workspaceId, String question, String status,
                          String title, String reportMarkdown, String traceSummary, String answerMessageId) {
    }

    private record CellRow(String cellId, String cellKey, String candidateValue, String cellStatus,
                           String evidenceRefsJson, String rowKey, String sourceTitle,
                           String columnKey, String label) {
    }

    private static final class RequirementAccumulator {
        private final String code;
        private final String label;
        private int total;
        private int verified;
        private int partial;

        private RequirementAccumulator(String code, String label) {
            this.code = code;
            this.label = label;
        }

        private void observe(String status) {
            total++;
            if ("VERIFIED".equals(status)) {
                verified++;
            } else if ("PARTIAL".equals(status)) {
                partial++;
            }
        }

        private ResearchProjection.Requirement toRequirement() {
            String coverage;
            if (verified == total && total > 0) {
                coverage = "COVERED";
            } else if (verified + partial > 0) {
                coverage = "PARTIAL";
            } else if (total == 0) {
                coverage = "MISSING";
            } else {
                coverage = "UNVERIFIED";
            }
            String note = total == 0 ? "没有对应单元格"
                    : verified + "/" + total + " 个单元格已核实";
            return new ResearchProjection.Requirement(code, label, coverage, note);
        }
    }
}
