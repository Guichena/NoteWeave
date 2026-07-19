package com.noteweave.research;

import com.noteweave.conversation.ConversationContextProjectionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Compiles the immutable planning brief consumed by coordinator task snapshots. */
@Service
public class ResearchBriefCompiler {
    private static final int MAX_PLANNING_QUERY_CHARS = 12_000;

    private final JdbcTemplate jdbcTemplate;
    private final ConversationContextProjectionService contextProjectionService;

    public ResearchBriefCompiler(
            JdbcTemplate jdbcTemplate,
            ConversationContextProjectionService contextProjectionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.contextProjectionService = contextProjectionService;
    }

    public CompiledBrief compile(String runId, String currentQuestion) {
        SnapshotLink link = jdbcTemplate.query("""
                select id, workspace_id, conversation_id, conversation_cutoff_seq
                from run_input_snapshot
                where execution_kind = 'RESEARCH' and research_run_id = ? and replay_availability = 'FULL'
                """, rs -> rs.next() ? new SnapshotLink(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)) : null, runId);
        if (link == null) {
            return standalone(currentQuestion);
        }

        ConversationContextProjectionService.CompilationProjection projection = contextProjectionService.compile(
                link.workspaceId(), link.conversationId(), link.cutoffSeq());
        List<Map<String, Object>> messages = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        if (projection.summaryText() != null && !projection.summaryText().isBlank()) {
            context.append("Conversation summary:\n").append(projection.summaryText().trim()).append("\n\n");
        }
        for (ConversationContextProjectionService.RawMessage message : projection.rawMessages()) {
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
        brief.put("conversation_summary", projection.summaryText() == null ? "" : projection.summaryText());
        brief.put("recent_messages", List.copyOf(messages));
        return new CompiledBrief(planningQuery, Map.copyOf(brief));
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

    public record CompiledBrief(String planningQuery, Map<String, Object> researchBrief) {
        public Map<String, Object> queryPolicy() {
            return Map.of("query", planningQuery, "research_brief", researchBrief);
        }
    }

    private record SnapshotLink(String snapshotId, String workspaceId, String conversationId, int cutoffSeq) { }
}
