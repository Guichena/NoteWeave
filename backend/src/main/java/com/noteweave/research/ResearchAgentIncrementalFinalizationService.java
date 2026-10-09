package com.noteweave.research;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.conversation.ConversationResearchProjectionService;
import com.noteweave.task.TaskService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MA4J internal-only finalizer for a citation-gated incremental ledger. */
@Service
public class ResearchAgentIncrementalFinalizationService {
    private final JdbcTemplate jdbcTemplate;
    private final ResearchAgentIncrementalFinalizationFaultInjector faultInjector;
    private final ResearchCollectionService researchCollectionService;
    private final ConversationResearchProjectionService conversationResearchProjectionService;
    private final TaskService taskService;
    private final ResearchContextV2Gate contextGate;

    public ResearchAgentIncrementalFinalizationService(JdbcTemplate jdbcTemplate,
                                                         ResearchAgentIncrementalFinalizationFaultInjector faultInjector,
                                                         ResearchCollectionService researchCollectionService,
                                                         ConversationResearchProjectionService conversationResearchProjectionService,
                                                         TaskService taskService,
                                                         ResearchContextV2Gate contextGate) {
        this.jdbcTemplate = jdbcTemplate; this.faultInjector = faultInjector;
        this.researchCollectionService = researchCollectionService;
        this.conversationResearchProjectionService = conversationResearchProjectionService;
        this.taskService = taskService;
        this.contextGate = contextGate;
    }

    @Transactional
    public FinalizationReceipt finalizeIncrementalRun(String runId) {
        RunRow run = jdbcTemplate.query("""
                select id, workspace_id, task_id, question, status, agent_execution_mode,
                       final_report_title, final_report_markdown, conversation_id, answer_message_id
                from research_run where id = ? for update
                """, rs -> rs.next() ? new RunRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10)) : null, runId);
        if (run == null || !"INCREMENTAL_V1".equals(run.mode())) throw new BusinessException("RESEARCH_AGENT_FINALIZATION_GATE_REJECTED", "Incremental finalization requires an incremental run");
        contextGate.requireReadable(runId);
        if ("COMPLETED".equals(run.status())) {
            Artifact artifact = requireArtifact(run.id());
            if (!artifact.digest().equals(sha256(run.markdown()))) {
                throw new BusinessException("RESEARCH_AGENT_FINALIZATION_INTEGRITY_ERROR", "Final report and artifact digest do not match");
            }
            persistEvidenceManifest(run, run.markdown(), loadVerifiedCells(run.id()));
            researchCollectionService.materialize(run.id());
            projectConversationReport(run, run.title());
            return new FinalizationReceipt(run.id(), artifact.id(), artifact.digest(), run.title(), run.markdown(), true);
        }
        if (!"RUNNING".equals(run.status())) throw new BusinessException("RESEARCH_AGENT_FINALIZATION_GATE_REJECTED", "Run is not finalizable");
        // FAILED is a historical task outcome, not necessarily an unresolved ledger outcome.
        // A later COUNTERFACTUAL/repair task can verify the same cell while the original failed
        // task intentionally remains immutable for auditability.  The canonical cell ledger below
        // is the authority for whether that repair converged; counting every historical FAILED
        // task here caused fully verified runs to spin forever at finalization.
        Integer active = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task where research_run_id = ?
                and status in ('PENDING','CLAIMED','RUNNING','RETRY_WAIT','EXPIRED')
                """, Integer.class, runId);
        Integer unverified = jdbcTemplate.queryForObject("""
                select count(*) from research_cell where research_run_id = ?
                and (cell_status <> 'VERIFIED' or evidence_refs_json is null or evidence_refs_json = '[]')
                """, Integer.class, runId);
        Integer verified = jdbcTemplate.queryForObject("select count(*) from research_cell where research_run_id = ? and cell_status = 'VERIFIED'", Integer.class, runId);
        if ((active != null && active > 0) || unverified == null || unverified > 0 || verified == null || verified == 0) {
            throw new BusinessException("RESEARCH_AGENT_FINALIZATION_GATE_REJECTED", "Canonical ledger is not citation-gated finalizable");
        }
        List<Cell> cells = loadVerifiedCells(runId);
        cells.sort((left, right) -> ResearchAgentBinaryOrder.UTF8.compare(left.key(), right.key()));
        SynthesisCandidate synthesis = loadValidatedSynthesis(runId);
        String markdown = synthesis == null ? render(run.question(), cells, matrixLabels(jdbcTemplate, runId)) : synthesis.markdown();
        markdown = appendCitationAudit(runId, markdown, cells);
        String title = ResearchDisplayLabels.reportTitle(run.question());
        String artifactId = Ids.newId();
        String reportDigest = sha256(markdown);
        jdbcTemplate.update("""
                insert into research_agent_report_artifact(
                    id, research_run_id, generation_key, report_digest,
                    report_title, report_markdown, verified_cell_count
                ) values (?, ?, 'canonical-ledger:v1', ?, ?, ?, ?)
                """, artifactId, runId, reportDigest, title, markdown, cells.size());
        jdbcTemplate.update("update research_run set status = 'COMPLETED', final_report_title = ?, final_report_markdown = ?, updated_at = current_timestamp where id = ? and status = 'RUNNING'", title, markdown, runId);
        if (synthesis != null) {
            jdbcTemplate.update("""
                    update research_agent_synthesis_candidate set status = 'PROMOTED'
                    where id = ? and status = 'VALIDATED'
                    """, synthesis.id());
        }
        persistEvidenceManifest(run, markdown, cells);
        researchCollectionService.materialize(run.id());
        projectConversationReport(run, title);
        faultInjector.checkpoint(ResearchAgentIncrementalFinalizationFaultInjector.Stage.AFTER_RUN_REPORT_WRITE);
        taskService.completeTask(run.taskId(), "RESEARCH_REPORTED", title, runId);
        jdbcTemplate.update("insert into research_trace(id, research_run_id, trace_type, trace_message, payload_json) values (?, ?, 'INCREMENTAL_FINALIZED', ?, ?)", Ids.newId(), runId, title, "{\"cell_count\":" + cells.size() + "}");
        return new FinalizationReceipt(run.id(), artifactId, reportDigest, title, markdown, false);
    }

    private Artifact requireArtifact(String runId) {
        Artifact artifact = jdbcTemplate.query("""
                select id, report_digest from research_agent_report_artifact where research_run_id = ?
                """, rs -> rs.next() ? new Artifact(rs.getString(1), rs.getString(2)) : null, runId);
        if (artifact == null) {
            throw new BusinessException("RESEARCH_AGENT_FINALIZATION_INTEGRITY_ERROR", "Completed incremental run has no report artifact");
        }
        return artifact;
    }

    private SynthesisCandidate loadValidatedSynthesis(String runId) {
        return jdbcTemplate.query("""
                select id, markdown from research_agent_synthesis_candidate
                where research_run_id = ? and status = 'VALIDATED'
                order by created_at desc, id desc limit 1
                """, rs -> rs.next() ? new SynthesisCandidate(rs.getString(1), rs.getString(2)) : null, runId);
    }

    private void projectConversationReport(RunRow run, String title) {
        conversationResearchProjectionService.projectCompletedReport(
                run.workspaceId(), run.conversationId(), run.answerMessageId(), run.id(), title);
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

    private void persistEvidenceManifest(RunRow run, String reportMarkdown, List<Cell> cells) {
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from research_evidence_manifest where research_run_id = ?", Integer.class, run.id());
        if (existing != null && existing > 0) return;

        String manifestId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_evidence_manifest(id, workspace_id, research_run_id, report_content_hash)
                values (?, ?, ?, ?)
                """, manifestId, run.workspaceId(), run.id(), contentHash(reportMarkdown));
        int rank = 0;
        for (Cell cell : cells) {
            List<Evidence> evidence = jdbcTemplate.query("""
                    select source_evidence.evidence_key, source_evidence.source_id, source_evidence.source_title,
                           source_evidence.quote_text, source_evidence.claim_text, source_evidence.snapshot_key,
                           source_evidence.window_id, source_evidence.source_url
                    from research_cell_evidence cell_evidence
                    join source_evidence on source_evidence.id = cell_evidence.source_evidence_id
                    where cell_evidence.research_cell_id = ?
                    order by source_evidence.evidence_key asc
                    """, (rs, rowNum) -> new Evidence(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), cell.id());
            for (Evidence item : evidence) {
                String excerpt = nonBlank(item.quote(), item.claim());
                if (excerpt.isBlank()) continue;
                rank++;
                jdbcTemplate.update("""
                        insert into research_evidence_manifest_item(
                            id, manifest_id, rank_no, evidence_id, source_id, source_snapshot_id,
                            passage_id, title, excerpt, content_hash, location_info
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Ids.newId(), manifestId, rank, item.key(), item.sourceId(), item.snapshotKey(),
                        item.windowId(), item.title(), excerpt, contentHash(excerpt), cell.key());
            }
        }
    }

    private List<Cell> loadVerifiedCells(String runId) {
        return jdbcTemplate.query("""
                select id, cell_key, candidate_value, evidence_refs_json from research_cell
                where research_run_id = ? and cell_status = 'VERIFIED'
                """, (rs, rowNum) -> new Cell(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), runId);
    }

    private String contentHash(String value) {
        return sha256(value == null ? "" : value).substring("sha256:".length());
    }

    private String nonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }

    private String render(String question, List<Cell> cells, java.util.Map<String, String> labels) {
        StringBuilder result = new StringBuilder("# 研究报告\n\n").append(question).append("\n\n| 研究项 | 已验证结论 | 证据 |\n|---|---|---|\n");
        for (Cell cell : cells) result.append('|').append(markdownCell(cellHeading(cell.key(), labels)))
                .append('|').append(markdownCell(cell.value()))
                .append('|').append(markdownCell(cell.evidence())).append("|\n");
        return result.toString();
    }

    private String appendCitationAudit(String runId, String markdown, List<Cell> cells) {
        StringBuilder result = new StringBuilder(markdown == null ? "" : markdown.stripTrailing());
        result.append("\n\n## Citation audit\n\n");
        java.util.Map<String, String> labels = matrixLabels(jdbcTemplate, runId);
        for (Cell cell : cells) {
            List<Evidence> evidence = jdbcTemplate.query("""
                    select se.evidence_key, se.source_id, se.source_title, se.quote_text,
                           se.claim_text, se.snapshot_key, se.window_id, se.source_url
                    from research_cell_evidence ce
                    join source_evidence se on se.id = ce.source_evidence_id
                    where ce.research_cell_id = ? order by se.evidence_key
                    """, (rs, rowNum) -> new Evidence(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), cell.id());
            for (Evidence item : evidence) {
                result.append("### ").append(markdownCell(cellHeading(cell.key(), labels))).append("\n\n")
                        .append("- Evidence: `").append(markdownCell(item.key())).append("`\n")
                        .append("- Exact quote: “").append(markdownCell(nonBlank(item.quote(), item.claim()))).append("”\n")
                        .append("- Snapshot: `").append(markdownCell(item.snapshotKey())).append("`\n")
                        .append("- URL: ").append(markdownCell(item.url())).append("\n\n");
            }
        }
        return result.toString();
    }

    /** 研究矩阵里行和列的显示名，键为行键或列键。 */
    static java.util.Map<String, String> matrixLabels(JdbcTemplate jdbcTemplate, String runId) {
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        if (runId == null || runId.isBlank()) return labels;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String json : jdbcTemplate.queryForList(
                "select plan_json from research_matrix_plan where research_run_id = ?", String.class, runId)) {
            try {
                com.fasterxml.jackson.databind.JsonNode plan = mapper.readTree(json);
                if (plan.isTextual()) plan = mapper.readTree(plan.asText());
                for (String group : List.of("rows", "columns")) {
                    plan.path(group).forEach(node -> {
                        String key = node.path("key").asText("");
                        String label = node.path("label").asText("");
                        if (!key.isBlank()) labels.putIfAbsent(key, ResearchDisplayLabels.of(key, label));
                    });
                }
            } catch (Exception ignored) {
                // 规划记录损坏时退回显示单元格键
            }
        }
        return labels;
    }

    /** 单元格键形如 entity-xxx:answer，审计小节用 研究对象 · 字段 作为标题，缺少显示名时保留原键。 */
    static String cellHeading(String cellKey, java.util.Map<String, String> labels) {
        int separator = cellKey == null ? -1 : cellKey.lastIndexOf(':');
        if (separator <= 0) return cellKey == null ? "" : cellKey;
        String row = labels.get(cellKey.substring(0, separator));
        String column = labels.get(cellKey.substring(separator + 1));
        if (row == null || column == null) return cellKey;
        return row + " · " + column;
    }

    private String markdownCell(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r\n", "<br>")
                .replace("\r", "<br>")
                .replace("\n", "<br>");
    }
    public record FinalizationReceipt(String runId, String artifactId, String reportDigest,
                                      String reportTitle, String reportMarkdown, boolean idempotentReplay) { }
    private record RunRow(
            String id,
            String workspaceId,
            String taskId,
            String question,
            String status,
            String mode,
            String title,
            String markdown,
            String conversationId,
            String answerMessageId
    ) { }
    private record Cell(String id, String key, String value, String evidence) { }
    private record Evidence(String key, String sourceId, String title, String quote, String claim,
                            String snapshotKey, String windowId, String url) { }
    private record SynthesisCandidate(String id, String markdown) { }
    private record Artifact(String id, String digest) { }
}
