package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationMessageDeletionService {
    private static final String EMPTY_CONTENT_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final JdbcTemplate jdbcTemplate;
    private final ConversationService conversationService;
    private final RunReplayRedactionService replayRedactionService;

    public ConversationMessageDeletionService(JdbcTemplate jdbcTemplate, ConversationService conversationService,
                                              RunReplayRedactionService replayRedactionService) {
        this.jdbcTemplate = jdbcTemplate;
        this.conversationService = conversationService;
        this.replayRedactionService = replayRedactionService;
    }

    @Transactional
    public DeletedConversationMessageResponse delete(String workspaceId, String conversationId, String messageId) {
        conversationService.requireConversation(workspaceId, conversationId);
        int changed = jdbcTemplate.update("""
                update conversation_message set content = '', content_hash = ?, context_status = 'DELETED'
                where id = ? and workspace_id = ? and conversation_id = ? and context_status <> 'DELETED'
                """, EMPTY_CONTENT_SHA256, messageId, workspaceId, conversationId);
        if (changed == 0) {
            Integer exists = jdbcTemplate.queryForObject("""
                    select count(*) from conversation_message where id = ? and workspace_id = ? and conversation_id = ?
                    """, Integer.class, messageId, workspaceId, conversationId);
            if (exists == null || exists == 0) {
                throw new BusinessException("CONVERSATION_MESSAGE_NOT_FOUND", "Conversation message not found", HttpStatus.NOT_FOUND);
            }
        }
        replayRedactionService.redactDeletedConversationMessage(workspaceId, messageId);
        jdbcTemplate.update("""
                update conversation_segment set lock_version = lock_version + 1
                where workspace_id = ? and conversation_id = ?
                  and covered_start_seq <= (select message_seq from conversation_message where id = ?)
                  and covered_end_seq >= (select message_seq from conversation_message where id = ?)
                """, workspaceId, conversationId, messageId, messageId);
        // Summary revisions that cover the deleted message carry content derived from it. Marking them
        // STALE is not enough for privacy: their summary_text still renders on replay, and snapshots that
        // reference them only by revision id are not caught by the message-id redaction above. Blank the
        // derived text and redact snapshots per covering revision so deleted content cannot be replayed.
        List<String> coveringRevisionIds = jdbcTemplate.query("""
                select revision.id from segment_summary_revision revision
                where revision.status in ('BUILDING', 'READY') and revision.segment_id in (
                    select id from conversation_segment where workspace_id = ? and conversation_id = ?
                      and covered_start_seq <= (select message_seq from conversation_message where id = ?)
                      and covered_end_seq >= (select message_seq from conversation_message where id = ?)
                )
                """, (rs, rowNum) -> rs.getString("id"), workspaceId, conversationId, messageId, messageId);
        for (String revisionId : coveringRevisionIds) {
            jdbcTemplate.update("""
                    update segment_summary_revision
                    set status = 'STALE', summary_text = '', content_hash = null
                    where id = ? and status in ('BUILDING', 'READY')
                    """, revisionId);
            replayRedactionService.redactDeletedSummaryRevision(revisionId);
        }
        List<String> coveringV2RevisionIds = jdbcTemplate.query("""
                select id from conversation_topic_summary_revision_v2
                where workspace_id = ? and conversation_id = ?
                  and status in ('BUILDING', 'READY')
                  and start_seq <= (select message_seq from conversation_message where id = ?)
                  and end_seq >= (select message_seq from conversation_message where id = ?)
                """, (rs, rowNum) -> rs.getString("id"), workspaceId, conversationId, messageId, messageId);
        for (String revisionId : coveringV2RevisionIds) {
            jdbcTemplate.update("""
                    update conversation_topic_summary_revision_v2
                    set status = 'STALE', summary_text = '', content_hash = null
                    where id = ? and status in ('BUILDING', 'READY')
                    """, revisionId);
            replayRedactionService.redactDeletedSummaryRevision(revisionId);
        }
        jdbcTemplate.update("""
                update conversation_topic_segment_v2
                set decision_status = 'STALE', source_digest = ?,
                    lock_version = lock_version + 1, updated_at = current_timestamp
                where workspace_id = ? and conversation_id = ?
                  and start_seq <= (select message_seq from conversation_message where id = ?)
                  and end_seq >= (select message_seq from conversation_message where id = ?)
                """, EMPTY_CONTENT_SHA256, workspaceId, conversationId, messageId, messageId);
        jdbcTemplate.update("""
                update conversation_topic_v2 set status = 'STALE', anchor_digest = ?
                where workspace_id = ? and conversation_id = ? and anchor_message_id = ?
                """, EMPTY_CONTENT_SHA256, workspaceId, conversationId, messageId);
        jdbcTemplate.update("""
                update conversation_constraint_v2
                set status = 'REVOKED', constraint_text = '', invalid_after_seq =
                    (select message_seq from conversation_message where id = ?)
                where workspace_id = ? and conversation_id = ? and source_message_id = ?
                """, messageId, workspaceId, conversationId, messageId);
        return new DeletedConversationMessageResponse(messageId, "DELETED");
    }
}
