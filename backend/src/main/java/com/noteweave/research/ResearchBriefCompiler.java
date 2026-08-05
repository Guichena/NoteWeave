package com.noteweave.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Compiles the immutable planning brief consumed by coordinator task snapshots. */
@Service
public class ResearchBriefCompiler {
    private static final int MAX_PLANNING_QUERY_CHARS = 12_000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchBriefCompiler(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public CompiledBrief compile(String runId, String currentQuestion) {
        SnapshotLink link = jdbcTemplate.query("""
                select id, workspace_id, conversation_id, snapshot_json
                from run_input_snapshot
                where execution_kind = 'RESEARCH' and research_run_id = ? and replay_availability = 'FULL'
                """, rs -> rs.next() ? new SnapshotLink(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) : null, runId);
        if (link == null) {
            return attachResumeContext(runId, standalone(currentQuestion));
        }

        FrozenProjection projection = loadFrozenProjection(link);
        List<Map<String, Object>> messages = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        if (!projection.summaryText().isBlank()) {
            context.append("Conversation summary:\n").append(projection.summaryText().trim()).append("\n\n");
        }
        for (FrozenMessage message : projection.messages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("message_id", message.messageId());
            item.put("message_seq", message.messageSeq());
            item.put("role", message.role());
            item.put("content", message.content());
            messages.add(Map.copyOf(item));
            context.append(message.role()).append(": ").append(message.content()).append('\n');
        }
        String planningQuery = bounded("Use the frozen conversation context to interpret the research request.\n\n"
                + context + "\nCurrent research request:\n" + currentQuestion.trim());
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("entry_point", "CONVERSATION");
        brief.put("context_snapshot_id", link.snapshotId());
        brief.put("current_question", currentQuestion.trim());
        brief.put("conversation_summary", projection.summaryText());
        brief.put("recent_messages", List.copyOf(messages));
        return attachResumeContext(runId, new CompiledBrief(planningQuery, Map.copyOf(brief)));
    }

    private FrozenProjection loadFrozenProjection(SnapshotLink link) {
        JsonNode snapshot;
        try {
            snapshot = objectMapper.readTree(link.snapshotJson());
        } catch (Exception ex) {
            throw snapshotMismatch("Research input snapshot JSON is invalid", ex);
        }
        String summaryText = loadFrozenSummary(link, snapshot.path("segment_summary_refs"));
        List<FrozenMessage> messages = new ArrayList<>();
        for (JsonNode ref : snapshot.path("recent_message_refs")) {
            String messageId = ref.path("message_id").asText();
            List<FrozenMessage> rows = jdbcTemplate.query("""
                    select id, message_seq, role, content, content_hash
                    from conversation_message
                    where workspace_id = ? and conversation_id = ? and id = ?
                    """, (rs, rowNum) -> new FrozenMessage(
                    rs.getString("id"), rs.getInt("message_seq"), rs.getString("role"),
                    rs.getString("content"), rs.getString("content_hash")
            ), link.workspaceId(), link.conversationId(), messageId);
            if (rows.size() != 1) {
                throw snapshotMismatch("Frozen research message is unavailable: " + messageId, null);
            }
            FrozenMessage message = rows.get(0);
            if (message.messageSeq() != ref.path("message_seq").asInt()
                    || !Objects.equals(message.role(), ref.path("role").asText())
                    || !Objects.equals(message.contentHash(), nullableText(ref, "content_hash"))) {
                throw snapshotMismatch("Frozen research message no longer matches its snapshot: " + messageId, null);
            }
            messages.add(message);
        }
        return new FrozenProjection(summaryText, List.copyOf(messages));
    }

    private String loadFrozenSummary(SnapshotLink link, JsonNode summaryRefs) {
        if (!summaryRefs.isArray() || summaryRefs.isEmpty()) {
            return "";
        }
        JsonNode ref = summaryRefs.get(0);
        String revisionId = ref.path("summary_revision_id").asText();
        List<FrozenSummary> rows = jdbcTemplate.query("""
                select revision.summary_text, revision.content_hash
                from segment_summary_revision revision
                join conversation_segment segment on segment.id = revision.segment_id
                where revision.id = ? and segment.id = ?
                  and segment.workspace_id = ? and segment.conversation_id = ?
                  and revision.status = 'READY'
                """, (rs, rowNum) -> new FrozenSummary(rs.getString(1), rs.getString(2)),
                revisionId, ref.path("segment_id").asText(), link.workspaceId(), link.conversationId());
        if (rows.size() != 1 || !Objects.equals(rows.get(0).contentHash(), nullableText(ref, "content_hash"))) {
            throw snapshotMismatch("Frozen research summary no longer matches its snapshot: " + revisionId, null);
        }
        return rows.get(0).summaryText() == null ? "" : rows.get(0).summaryText();
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private BusinessException snapshotMismatch(String message, Exception cause) {
        BusinessException exception = new BusinessException(
                "RESEARCH_CONTEXT_SNAPSHOT_MISMATCH", message, HttpStatus.CONFLICT);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private CompiledBrief standalone(String question) {
        return new CompiledBrief(question.trim(), Map.of(
                "entry_point", "STANDALONE",
                "current_question", question.trim(),
                "recent_messages", List.of()));
    }

    private String bounded(String value) {
        return value.length() <= MAX_PLANNING_QUERY_CHARS ? value : value.substring(value.length() - MAX_PLANNING_QUERY_CHARS);
    }

    private CompiledBrief attachResumeContext(String runId, CompiledBrief base) {
        ResumeContext resume = jdbcTemplate.query("""
                select rr.resumed_from_research_run_id, rr.resumed_from_checkpoint_no,
                       rec.snapshot_type, rec.active_branch_key, rec.final_loop_decision, rec.summary_json
                from research_run rr
                join research_execution_checkpoint rec
                  on rec.research_run_id = rr.resumed_from_research_run_id
                 and rec.checkpoint_no = rr.resumed_from_checkpoint_no
                where rr.id = ?
                """, rs -> rs.next() ? new ResumeContext(
                rs.getString("resumed_from_research_run_id"),
                rs.getInt("resumed_from_checkpoint_no"),
                rs.getString("snapshot_type"),
                rs.getString("active_branch_key"),
                rs.getString("final_loop_decision"),
                readMap(rs.getString("summary_json"))
        ) : null, runId);
        if (resume == null) {
            return base;
        }

        Map<String, Object> resumeCheckpoint = new LinkedHashMap<>();
        resumeCheckpoint.put("source_research_run_id", text(resume.sourceRunId()));
        resumeCheckpoint.put("checkpoint_no", resume.checkpointNo());
        resumeCheckpoint.put("snapshot_type", text(resume.snapshotType()));
        resumeCheckpoint.put("active_branch_key", text(resume.activeBranchKey()));
        resumeCheckpoint.put("final_loop_decision", text(resume.finalLoopDecision()));
        resumeCheckpoint.put("summary", resume.summary());
        Map<String, Object> brief = new LinkedHashMap<>(base.researchBrief());
        brief.put("entry_point", "CHECKPOINT_RESUME");
        brief.put("resume_checkpoint", Map.copyOf(resumeCheckpoint));
        String checkpointJson;
        try {
            checkpointJson = objectMapper.writeValueAsString(resumeCheckpoint);
        } catch (Exception ex) {
            checkpointJson = resumeCheckpoint.toString();
        }
        String query = bounded("Continue the research from the persisted checkpoint. Preserve verified findings, "
                + "re-open conflicted or incomplete cells, and do not restart the investigation from scratch.\n"
                + "Checkpoint context:\n" + checkpointJson + "\n\nCurrent research request:\n"
                + base.planningQuery());
        return new CompiledBrief(query, Map.copyOf(brief));
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ex) {
            return Map.of("raw_summary", json);
        }
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    public record CompiledBrief(String planningQuery, Map<String, Object> researchBrief) {
        public Map<String, Object> queryPolicy() {
            return Map.of("query", planningQuery, "research_brief", researchBrief);
        }
    }

    private record SnapshotLink(String snapshotId, String workspaceId, String conversationId, String snapshotJson) { }

    private record FrozenProjection(String summaryText, List<FrozenMessage> messages) { }

    private record FrozenMessage(
            String messageId,
            int messageSeq,
            String role,
            String content,
            String contentHash
    ) { }

    private record FrozenSummary(String summaryText, String contentHash) { }

    private record ResumeContext(
            String sourceRunId,
            int checkpointNo,
            String snapshotType,
            String activeBranchKey,
            String finalLoopDecision,
            Map<String, Object> summary
    ) { }
}
