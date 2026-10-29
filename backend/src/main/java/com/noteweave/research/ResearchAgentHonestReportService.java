package com.noteweave.research;

import com.noteweave.common.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Renders business-terminal reports directly from the CompletionGate decision and canonical evidence. */
@Service
class ResearchAgentHonestReportService {
    private final JdbcTemplate jdbcTemplate;
    private final ResearchCellLevels cellLevels;

    ResearchAgentHonestReportService(JdbcTemplate jdbcTemplate, ResearchCellLevels cellLevels) {
        this.jdbcTemplate = jdbcTemplate;
        this.cellLevels = cellLevels;
    }

    String renderAndPersist(String runId, ResearchAgentRunCompletionGate.CompletionDecision decision) {
        String question = jdbcTemplate.queryForObject(
                "select question from research_run where id = ?", String.class, runId);
        String markdown = render(question, runId, decision).strip();
        if (markdown.isBlank()) return "";

        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from research_agent_report_artifact where research_run_id = ?", Integer.class, runId);
        if (existing != null && existing > 0) return markdown;
        String title = ResearchDisplayLabels.reportTitle(question);
        jdbcTemplate.update("""
                insert into research_agent_report_artifact(
                    id, research_run_id, generation_key, report_digest,
                    report_title, report_markdown, verified_cell_count)
                values (?, ?, 'completion-gate:v1', ?, ?, ?, ?)
                """, Ids.newId(), runId, sha256(markdown), title, markdown,
                decision.promotableClaims().size());
        jdbcTemplate.update("""
                update research_run set final_report_title = ?, final_report_markdown = ?, updated_at = current_timestamp
                where id = ?
                """, title, markdown, runId);
        return markdown;
    }

    /** 交给综合撰写的局限说明：每个未解决的单元格一行，写明研究项和原因。 */
    List<String> limitationLines(String runId, ResearchAgentRunCompletionGate.CompletionDecision decision) {
        Map<String, String> labels = ResearchAgentIncrementalFinalizationService.matrixLabels(jdbcTemplate, runId);
        List<String> lines = new java.util.ArrayList<>();
        for (ResearchAgentRunCompletionGate.UnresolvedCell cell : decision.unresolvedCells()) {
            lines.add(heading(cell.cellKey(), labels) + "：" + reasonText(cell.reasonCode()));
        }
        lines.addAll(readableLimitations(decision.limitations()));
        return lines;
    }

    /**
     * 整次研究层面的局限原因码换成读者看得懂的说明；逐个单元格的原因已经单独列出，
     * 只是重复说明的原因码和不认识的内部原因码直接略去，不在报告里显示代码。
     */
    static List<String> readableLimitations(List<String> codes) {
        List<String> lines = new java.util.ArrayList<>();
        for (String code : codes == null ? List.<String>of() : codes) {
            String text = switch (code == null ? "" : code.strip()) {
                case "REQUIRED_CELLS_PARTIALLY_RESOLVED" -> "部分必答研究项未能核验，结论只覆盖已核验的部分";
                case "INSUFFICIENT_EVIDENCE" -> "没有找到足够的可核验证据";
                case "BUDGET_EXHAUSTED" -> "研究预算已用完，部分研究项未能继续补查";
                case "VERIFIER_DISAGREEMENT" -> "独立核验对部分结论的判断不一致";
                case "EVIDENCE_CONFLICT", "CONFLICTED_CELL" -> "部分研究项的不同来源相互矛盾";
                case "CONFLICT_REPAIR_EXHAUSTED" -> "证据冲突经补充检索后仍未解决";
                case "UNRESOLVED_CELLS_REMAIN", "" -> "";
                default -> code.strip().matches("[A-Z0-9_]+") ? "" : code.strip();
            };
            if (!text.isEmpty() && !lines.contains(text)) lines.add(text);
        }
        return lines;
    }

    private String render(String question, String runId,
                          ResearchAgentRunCompletionGate.CompletionDecision decision) {
        Map<String, String> labels = ResearchAgentIncrementalFinalizationService.matrixLabels(jdbcTemplate, runId);
        Synthesis synthesis = validatedSynthesis(runId);
        StringBuilder report;
        if (synthesis != null) {
            // 综合撰写的正文在前，后面保留结论汇总、逐条引用核对和局限说明
            // 成文里的局限一节只摘了部分条目，去掉后统一用下面完整的局限与未解决项
            // 核验情况放在摘要下面，读者先看到有多少结论已核实、多少仍缺，和主流研究报告开头的研究概况一致
            report = new StringBuilder(afterLead(withoutSection(synthesis.markdown(), "## 局限", "## Limitations").strip(),
                    verificationSummary(runId, decision))).append("\n\n");
            jdbcTemplate.update("update research_agent_synthesis_candidate set status = 'PROMOTED' where id = ? and status = 'VALIDATED'",
                    synthesis.id());
        } else {
            report = new StringBuilder("# 研究报告\n\n")
                    .append(question == null ? "" : question).append("\n\n")
                    .append(verificationSummary(runId, decision)).append("\n\n");
        }
        // 成文已经带对比表时不再追加一张结论汇总表，避免同一组结论出现两张表
        if (synthesis == null || !synthesis.markdown().contains("## 对比表")) appendSummaryTable(report, runId);
        if (!decision.promotableClaims().isEmpty()) {
            report.append(synthesis != null ? "## 引用核对\n\n" : "## 已验证结论\n\n");
            for (ResearchAgentRunCompletionGate.PromotableClaim claim : decision.promotableClaims()) {
                report.append("### ").append(markdown(claim.value())).append("\n\n")
                        .append("- 研究项：`").append(markdown(heading(claim.cellKey(), labels))).append("`\n");
                // 候选编号只用于排查，记录在 research_cell_merge 里，不写进给读者的报告
                for (String evidenceKey : claim.evidenceKeys()) {
                    Evidence evidence = evidence(runId, evidenceKey);
                    if (evidence == null) continue;
                    report.append("- Evidence: `").append(markdown(evidenceKey)).append("`\n")
                            .append("  - Exact quote: “").append(markdown(evidence.quote())).append("”\n")
                            .append("  - Snapshot: `").append(markdown(evidence.snapshotKey())).append("`\n")
                            .append("  - URL: ").append(markdown(evidence.url())).append("\n");
                }
                report.append('\n');
            }
        }
        appendVerifiedFacts(report, runId, decision, labels, synthesis != null);
        Map<String, String> withLeads = appendTentativeFindings(report, runId, decision, labels);
        if (!decision.unresolvedCells().isEmpty() || !decision.limitations().isEmpty()) {
            report.append("## 局限与未解决项\n\n");
            for (ResearchAgentRunCompletionGate.UnresolvedCell cell : decision.unresolvedCells()) {
                report.append("- **").append(markdown(heading(cell.cellKey(), labels))).append("**：")
                        .append(leadText(withLeads.get(cell.cellKey()), cell.reasonCode())).append("\n");
            }
            for (String limitation : readableLimitations(decision.limitations())) {
                report.append("- ").append(markdown(limitation)).append("\n");
            }
            report.append('\n');
        }
        // 完成状态与原因码已经写入 research_run 和完成判定记录，报告正文只给读者看得懂的内容
        return ResearchReportCitations.consolidate(jdbcTemplate, runId,
                ResearchRepairExecutionService.appendCounterAudit(jdbcTemplate, runId, report.toString()));
    }

    /**
     * 未通过核验的单元格里保留下来的线索，按 {@link ResearchCellLevels} 分为有出处和相关材料两类。
     * 参考主流研究报告的做法，关键结论仍只用已核验的内容，这些线索单独成节并注明未经核实，
     * 附上原文引用，由读者自行判断。返回列出了线索的单元格及其级别。
     */
    private Map<String, String> appendTentativeFindings(StringBuilder report, String runId,
                                                        ResearchAgentRunCompletionGate.CompletionDecision decision,
                                                        Map<String, String> labels) {
        Map<String, ResearchCellLevels.Lead> leads = cellLevels.leads(runId);
        Map<String, String> listed = new java.util.LinkedHashMap<>();
        StringBuilder sourced = new StringBuilder();
        StringBuilder related = new StringBuilder();
        for (ResearchAgentRunCompletionGate.UnresolvedCell cell : decision.unresolvedCells()) {
            ResearchCellLevels.Lead lead = leads.get(cell.cellKey());
            if (lead == null) continue;
            listed.put(cell.cellKey(), lead.level());
            // 成文正文已经引用并注明未经核实的线索不再重复列出
            if (lead.quotes().stream().anyMatch(quote -> report.indexOf("[" + quote.evidenceKey() + "]") >= 0)) continue;
            String citations = lead.quotes().stream().map(quote -> "[" + quote.evidenceKey() + "]")
                    .collect(java.util.stream.Collectors.joining());
            StringBuilder target = ResearchCellLevels.SOURCED.equals(lead.level()) ? sourced : related;
            target.append("- **").append(markdown(heading(cell.cellKey(), labels))).append("**：")
                    .append(markdown(lead.value())).append(' ').append(citations).append('\n');
        }
        if (sourced.isEmpty() && related.isEmpty()) return listed;
        report.append("## 待核实的线索\n\n");
        if (!sourced.isEmpty()) {
            report.append("以下内容有原文出处，但只找到第三方资料，或数字、版本与官方文档对不上，")
                    .append("暂未核实。可作参考，请以引用的原文为准。\n\n")
                    .append(sourced).append('\n');
        }
        if (!related.isEmpty()) {
            report.append("### 相关材料\n\n")
                    .append("以下原文与研究对象有关，但没有直接回答对应的研究项，可作为进一步查阅的起点。\n\n")
                    .append(related).append('\n');
        }
        return listed;
    }

    static String leadText(String level, String reasonCode) {
        if (ResearchCellLevels.SOURCED.equals(level)) return "只找到第三方资料或版本不一致的文档，正文中已标注待核实";
        if (ResearchCellLevels.RELATED.equals(level)) return "只找到相关背景材料，没有直接回答这一项";
        return reasonText(reasonCode);
    }

    /** 把一段文字插到标题和摘要之后、第一个二级标题之前；没有二级标题时追加到末尾。 */
    static String afterLead(String markdown, String text) {
        int section = markdown.indexOf("\n## ");
        if (section < 0) return markdown.stripTrailing() + "\n\n" + text;
        return markdown.substring(0, section).stripTrailing() + "\n\n" + text + "\n" + markdown.substring(section);
    }

    /** 去掉以这些二级标题开头的小节，到下一个二级标题为止。 */
    static String withoutSection(String markdown, String... headings) {
        StringBuilder kept = new StringBuilder();
        boolean skipping = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("## ")) {
                skipping = java.util.Arrays.stream(headings).anyMatch(line::startsWith);
            }
            if (!skipping) kept.append(line).append('\n');
        }
        return kept.toString();
    }

    /**
     * 研究对象 × 研究维度的汇总表，放在逐项证据之前，让读者先看到对比结果。只填已核验的值，
     * 其余按状态标为未找到证据、证据冲突或待核验；没有规划记录或只有一个单元格时不生成。
     */
    private void appendSummaryTable(StringBuilder report, String runId) {
        MatrixAxes axes = matrixAxes(runId);
        if (axes.rows().isEmpty() || axes.columns().isEmpty()
                || axes.rows().size() * axes.columns().size() < 2) return;
        Map<String, String[]> cells = new java.util.HashMap<>();
        jdbcTemplate.query("select cell_key, cell_status, candidate_value from research_cell where research_run_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        cells.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)}),
                runId);
        if (cells.isEmpty()) return;
        report.append("## 结论汇总\n\n| 研究对象 |");
        axes.columns().forEach(column -> report.append(' ').append(tableCell(column[1])).append(" |"));
        report.append("\n|---|");
        axes.columns().forEach(column -> report.append("---|"));
        report.append('\n');
        for (String[] row : axes.rows()) {
            report.append("| ").append(tableCell(row[1])).append(" |");
            for (String[] column : axes.columns()) {
                report.append(' ').append(summaryValue(cells.get(row[0] + ":" + column[0]))).append(" |");
            }
            report.append('\n');
        }
        report.append('\n');
    }

    static String summaryValue(String[] cell) {
        // 规划时标为不适用的组合不建单元格
        if (cell == null) return "不适用";
        String status = cell[0] == null ? "" : cell[0];
        String value = cell[1] == null ? "" : cell[1].strip();
        if ("VERIFIED".equals(status) && !value.isBlank()) {
            return tableCell(value.length() <= 80 ? value : value.substring(0, 80) + "…");
        }
        if (status.startsWith("CONFLICT")) return "证据冲突";
        if ("GAP".equals(status) || "EMPTY".equals(status) || status.isBlank()) return "未找到证据";
        return "待核验";
    }

    private static String tableCell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replaceAll("\\s+", " ").strip();
    }

    /**
     * 按规划记录中的顺序取行与列，元素为 {键, 显示名}；限制说明类的列不进入汇总表。
     * 局部重规划记录不含行列，取最近一条带行列的矩阵规划。
     */
    private MatrixAxes matrixAxes(String runId) {
        List<String> plans = jdbcTemplate.queryForList("""
                select plan_json from research_matrix_plan where research_run_id = ?
                order by created_at desc, id desc
                """, String.class, runId);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String json : plans) {
            List<String[]> rows = new java.util.ArrayList<>();
            List<String[]> columns = new java.util.ArrayList<>();
            try {
                com.fasterxml.jackson.databind.JsonNode plan = mapper.readTree(json);
                if (plan.isTextual()) plan = mapper.readTree(plan.asText());
                plan.path("rows").forEach(node -> addAxis(rows, node));
                plan.path("columns").forEach(node -> {
                    if (!ResearchAgentRunCompletionGate.LIMITATION_COLUMN_KEYS.contains(node.path("key").asText(""))) {
                        addAxis(columns, node);
                    }
                });
            } catch (Exception ignored) {
                // 规划记录损坏时跳过，继续看更早的记录；都不可用时不生成汇总表
                continue;
            }
            if (!rows.isEmpty() && !columns.isEmpty()) return new MatrixAxes(rows, columns);
        }
        return new MatrixAxes(List.of(), List.of());
    }

    private static void addAxis(List<String[]> axis, com.fasterxml.jackson.databind.JsonNode node) {
        String key = node.path("key").asText("");
        if (!key.isBlank()) axis.add(new String[] {key, ResearchDisplayLabels.of(key, node.path("label").asText(""))});
    }

    private record MatrixAxes(List<String[]> rows, List<String[]> columns) { }

    /**
     * Cells that passed verification but were not promoted (the Run as a whole lacks evidence)
     * are still evidence-backed facts; list them so a partial result is not silently dropped.
     */
    private void appendVerifiedFacts(StringBuilder report, String runId,
                                     ResearchAgentRunCompletionGate.CompletionDecision decision,
                                     Map<String, String> labels, boolean synthesized) {
        java.util.Set<String> promoted = new java.util.HashSet<>();
        decision.promotableClaims().forEach(claim -> promoted.add(claim.cellKey()));
        List<String[]> cells = jdbcTemplate.query("""
                select cell_key, candidate_value, evidence_refs_json from research_formal_eligible_cell
                where research_run_id = ? and cell_status = 'VERIFIED' order by cell_key
                """, (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, runId);
        cells.removeIf(cell -> promoted.contains(cell[0]) || cell[1] == null || cell[1].isBlank());
        if (cells.isEmpty()) return;
        if (synthesized) {
            // 正文已经基于这些单元格成文，这里只保留逐条原文供核对
            report.append(decision.promotableClaims().isEmpty() ? "## 引用核对\n\n" : "");
        } else {
            report.append("## 已核验的部分事实\n\n")
                    .append("以下研究项已有原文证据支撑，但整体证据不足以形成完整结论，仅供参考。\n\n");
        }
        for (String[] cell : cells) {
            report.append("### ").append(markdown(heading(cell[0], labels))).append("\n\n")
                    .append(markdown(cell[1])).append("\n\n");
            for (String evidenceKey : evidenceKeys(cell[2])) {
                Evidence evidence = evidence(runId, evidenceKey);
                if (evidence == null) continue;
                report.append("- Evidence: `").append(markdown(evidenceKey)).append("`\n")
                        .append("  - Exact quote: “").append(markdown(evidence.quote())).append("”\n")
                        .append("  - Snapshot: `").append(markdown(evidence.snapshotKey())).append("`\n")
                        .append("  - URL: ").append(markdown(evidence.url())).append("\n");
            }
            report.append('\n');
        }
    }

    private List<String> evidenceKeys(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node.isTextual()) node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(node.asText());
            List<String> keys = new java.util.ArrayList<>();
            node.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) keys.add(item.asText()); });
            return keys;
        } catch (Exception exception) {
            return List.of();
        }
    }

    /**
     * 报告开头的核验说明：用中文写清楚几项已核验、几项没有找到证据或证据冲突，
     * 不再直接写内部完成状态代码。完成状态仍记录在研究记录里，报告末尾的判定原因保留代码供排查。
     */
    String verificationSummary(String runId, ResearchAgentRunCompletionGate.CompletionDecision decision) {
        Integer verified = jdbcTemplate.queryForObject("""
                select count(*) from research_formal_eligible_cell where research_run_id = ? and cell_status = 'VERIFIED'
                """, Integer.class, runId);
        long conflicted = decision.unresolvedCells().stream()
                .filter(cell -> cell.status() != null && cell.status().startsWith("CONFLICT")).count();
        long missing = decision.unresolvedCells().size() - conflicted;
        StringBuilder text = new StringBuilder("**核验情况：** 已核验 ").append(verified == null ? 0 : verified).append(" 项");
        if (missing > 0) text.append("，未找到足够证据 ").append(missing).append(" 项");
        if (conflicted > 0) text.append("，来源说法冲突 ").append(conflicted).append(" 项");
        return text.append("。").toString();
    }

    private Synthesis validatedSynthesis(String runId) {
        return jdbcTemplate.query("""
                select id, markdown from research_agent_synthesis_candidate
                where research_run_id = ? and status = 'VALIDATED'
                order by created_at desc, id desc limit 1
                """, rs -> rs.next() ? new Synthesis(rs.getString(1), rs.getString(2)) : null, runId);
    }

    private record Synthesis(String id, String markdown) { }

    private String heading(String cellKey, Map<String, String> labels) {
        return ResearchAgentIncrementalFinalizationService.cellHeading(cellKey, labels);
    }

    static String reasonText(String reasonCode) {
        if (reasonCode == null) return "未解决";
        return switch (reasonCode) {
            case "REQUIRED_CELL_UNRESOLVED" -> "必需研究项未找到足够证据";
            case "OPTIONAL_CELL_UNRESOLVED" -> "可选研究项未找到足够证据";
            case "CONFLICTED_CELL", "EVIDENCE_CONFLICT" -> "不同来源的证据相互冲突";
            case "CONFLICT_REPAIR_EXHAUSTED" -> "证据冲突且补充检索后仍未解决";
            default -> "未解决";
        };
    }

    private Evidence evidence(String runId, String evidenceKey) {
        return jdbcTemplate.query("""
                select quote_text, snapshot_key, source_url from source_evidence
                where research_run_id = ? and evidence_key = ?
                """, rs -> rs.next() ? new Evidence(rs.getString(1), rs.getString(2), rs.getString(3)) : null,
                runId, evidenceKey);
    }

    private String markdown(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("`", "\\`")
                .replace("\r", " ").replace("\n", " ").strip();
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder digest = new StringBuilder("sha256:");
            for (byte item : bytes) digest.append(String.format("%02x", item));
            return digest.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Evidence(String quote, String snapshotKey, String url) { }
}
