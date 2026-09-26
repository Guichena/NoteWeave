package com.noteweave.research;

import com.noteweave.common.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
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
        String title = "Research report: " + question.substring(0, Math.min(240, question.length()));
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
        StringBuilder report = new StringBuilder("# Research report\n\n")
                .append(question == null ? "" : question).append("\n\n")
                .append("**Outcome:** `").append(decision.terminalState().name()).append("`\n\n");
        if (!decision.promotableClaims().isEmpty()) {
            report.append("## Verified findings\n\n");
            for (ResearchAgentRunCompletionGate.PromotableClaim claim : decision.promotableClaims()) {
                report.append("### ").append(markdown(claim.value())).append("\n\n")
                        .append("- Cell: `").append(markdown(claim.cellKey())).append("`\n");
                String candidateId = acceptedCandidateId(runId, claim.cellKey());
                if (!candidateId.isBlank()) report.append("- Candidate: `").append(candidateId).append("`\n");
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
        if (!decision.unresolvedCells().isEmpty() || !decision.limitations().isEmpty()) {
            report.append("## Limitations\n\n");
            for (ResearchAgentRunCompletionGate.UnresolvedCell cell : decision.unresolvedCells()) {
                report.append("- `").append(markdown(cell.cellKey())).append("`: ")
                        .append(markdown(cell.reasonCode())).append(" (`")
                        .append(markdown(cell.status())).append("`)\n");
            }
            for (String limitation : decision.limitations()) {
                report.append("- ").append(markdown(limitation)).append("\n");
            }
            report.append('\n');
        }
        if (!decision.reasonCodes().isEmpty()) {
            report.append("## Completion reasons\n\n");
            decision.reasonCodes().forEach(reason -> report.append("- ").append(markdown(reason)).append("\n"));
        }
        return report.toString();
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
