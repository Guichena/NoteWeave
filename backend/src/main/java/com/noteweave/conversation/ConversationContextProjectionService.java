package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Selects the immutable conversation projection persisted with a run snapshot. */
@Service
public class ConversationContextProjectionService {

    static final int MAX_UNSUMMARIZED_MESSAGES = 256;

    private final JdbcTemplate jdbcTemplate;

    public ConversationContextProjectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Projection select(String workspaceId, String conversationId, int cutoffSeq) {
        return toProjection(compile(workspaceId, conversationId, cutoffSeq));
    }

    public Projection toProjection(CompilationProjection compilation) {
        return new Projection(
                compilation.summary() == null ? List.of() : List.of(compilation.summary()),
                compilation.rawMessages().stream()
                        .map(message -> new MessageRef(message.messageId(), message.messageSeq(), message.role(), message.contentHash()))
                        .toList()
        );
    }

    public CompilationProjection compile(String workspaceId, String conversationId, int cutoffSeq) {
        SegmentSummaryRef summary = selectReadyPrefixSummary(workspaceId, conversationId, cutoffSeq);
        int rawTailStartSeq = summary == null ? 1 : summary.coveredEndSeq() + 1;
        List<RawMessage> newestFirst = jdbcTemplate.query("""
                select id, message_seq, role, content, content_hash
                from conversation_message
                where workspace_id = ? and conversation_id = ?
                  and context_status = 'CURRENT' and message_seq between ? and ?
                order by message_seq desc
                limit ?
                """, (rs, rowNum) -> new RawMessage(
                rs.getString("id"), rs.getInt("message_seq"), rs.getString("role"),
                rs.getString("content"), rs.getString("content_hash")
        ), workspaceId, conversationId, rawTailStartSeq, cutoffSeq, MAX_UNSUMMARIZED_MESSAGES + 1);
        if (newestFirst.size() > MAX_UNSUMMARIZED_MESSAGES) {
            throw new BusinessException(
                    "CONVERSATION_CONTEXT_SUMMARY_LAGGING",
                    "Conversation summary is too far behind to compile a complete context",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        List<RawMessage> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        String summaryText = summary == null ? "" : jdbcTemplate.queryForObject(
                "select summary_text from segment_summary_revision where id = ?", String.class, summary.summaryRevisionId());
        return new CompilationProjection(summary, summaryText == null ? "" : summaryText, List.copyOf(oldestFirst));
    }

    private SegmentSummaryRef selectReadyPrefixSummary(
            String workspaceId,
            String conversationId,
            int cutoffSeq
    ) {
        List<SegmentSummaryRef> summaries = jdbcTemplate.query("""
                select segment.id, revision.id, segment.covered_start_seq, segment.covered_end_seq, revision.content_hash
                from conversation_segment segment
                join segment_summary_revision revision on revision.segment_id = segment.id
                where segment.workspace_id = ? and segment.conversation_id = ?
                  and segment.covered_start_seq = 1
                  and segment.covered_end_seq < ?
                  and revision.status = 'READY'
                order by segment.covered_end_seq desc, revision.revision_no desc
                limit 1
                """, (rs, rowNum) -> new SegmentSummaryRef(
                rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5)
        ), workspaceId, conversationId, cutoffSeq);
        return summaries.isEmpty() ? null : summaries.get(0);
    }

    public record Projection(List<SegmentSummaryRef> segmentSummaryRefs, List<MessageRef> recentMessageRefs) {
        public Projection {
            segmentSummaryRefs = segmentSummaryRefs == null ? List.of() : List.copyOf(segmentSummaryRefs);
            recentMessageRefs = recentMessageRefs == null ? List.of() : List.copyOf(recentMessageRefs);
        }
    }

    public record SegmentSummaryRef(
            String segmentId,
            String summaryRevisionId,
            int coveredStartSeq,
            int coveredEndSeq,
            String contentHash
    ) { }

    public record MessageRef(String messageId, int messageSeq, String role, String contentHash) { }

    public record RawMessage(String messageId, int messageSeq, String role, String content, String contentHash) { }

    public record CompilationProjection(
            SegmentSummaryRef summary,
            String summaryText,
            List<RawMessage> rawMessages
    ) { }
}
