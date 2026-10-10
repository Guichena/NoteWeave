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

    ResearchAgentHonestReportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

    private String render(String question, String runId,
                          ResearchAgentRunCompletionGate.CompletionDecision decision) {
        Map<String, String> labels = ResearchAgentIncrementalFinalizationService.matrixLabels(jdbcTemplate, runId);
        StringBuilder report = new StringBuilder("# 研究报告\n\n")
                .append(question == null ? "" : question).append("\n\n")
                .append("**结果：** `").append(decision.terminalState().name()).append("`\n\n");
        if (!decision.promotableClaims().isEmpty()) {
            report.append("## 已验证结论\n\n");
            for (ResearchAgentRunCompletionGate.PromotableClaim claim : decision.promotableClaims()) {
                report.append("### ").append(markdown(claim.value())).append("\n\n")
                        .append("- 研究项：`").append(markdown(heading(claim.cellKey(), labels))).append("`\n");
                String candidateId = acceptedCandidateId(runId, claim.cellKey());
                if (!candidateId.isBlank()) report.append("- 候选：`").append(candidateId).append("`\n");
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
        appendVerifiedFacts(report, runId, decision, labels);
        if (!decision.unresolvedCells().isEmpty() || !decision.limitations().isEmpty()) {
            report.append("## 局限与未解决项\n\n");
            for (ResearchAgentRunCompletionGate.UnresolvedCell cell : decision.unresolvedCells()) {
                report.append("- **").append(markdown(heading(cell.cellKey(), labels))).append("**：")
                        .append(reasonText(cell.reasonCode())).append("（`")
                        .append(markdown(cell.reasonCode())).append("`，`")
                        .append(markdown(cell.status())).append("`）\n");
            }
            for (String limitation : decision.limitations()) {
                report.append("- ").append(markdown(limitation)).append("\n");
            }
            report.append('\n');
        }
        if (!decision.reasonCodes().isEmpty()) {
            report.append("## 完成判定原因\n\n");
            decision.reasonCodes().forEach(reason -> report.append("- ").append(markdown(reason)).append("\n"));
        }
        return report.toString();
    }

    /**
     * Cells that passed verification but were not promoted (the Run as a whole lacks evidence)
     * are still evidence-backed facts; list them so a partial result is not silently dropped.
     */
    private void appendVerifiedFacts(StringBuilder report, String runId,
                                     ResearchAgentRunCompletionGate.CompletionDecision decision,
                                     Map<String, String> labels) {
        java.util.Set<String> promoted = new java.util.HashSet<>();
        decision.promotableClaims().forEach(claim -> promoted.add(claim.cellKey()));
        List<String[]> cells = jdbcTemplate.query("""
                select cell_key, candidate_value, evidence_refs_json from research_cell
                where research_run_id = ? and cell_status = 'VERIFIED' order by cell_key
                """, (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, runId);
        cells.removeIf(cell -> promoted.contains(cell[0]) || cell[1] == null || cell[1].isBlank());
        if (cells.isEmpty()) return;
        report.append("## 已核验的部分事实\n\n")
                .append("以下研究项已有原文证据支撑，但整体证据不足以形成完整结论，仅供参考。\n\n");
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

    private String heading(String cellKey, Map<String, String> labels) {
        return ResearchAgentIncrementalFinalizationService.cellHeading(cellKey, labels);
    }

    private static String reasonText(String reasonCode) {
        if (reasonCode == null) return "未解决";
        return switch (reasonCode) {
            case "REQUIRED_CELL_UNRESOLVED" -> "必需研究项未找到足够证据";
            case "OPTIONAL_CELL_UNRESOLVED" -> "可选研究项未找到足够证据";
            case "CONFLICTED_CELL", "EVIDENCE_CONFLICT" -> "不同来源的证据相互冲突";
            case "CONFLICT_REPAIR_EXHAUSTED" -> "证据冲突且补充检索后仍未解决";
            default -> "未解决";
        };
    }

    private String acceptedCandidateId(String runId, String cellKey) {
        List<String> rows = jdbcTemplate.query("""
                select candidate_id from research_cell_merge
                where research_run_id = ? and cell_key = ? and decision = 'ACCEPTED'
                order by merged_at desc, id desc limit 1
                """, (rs, rowNum) -> rs.getString(1), runId, cellKey);
        return rows.isEmpty() ? "" : rows.get(0);
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
