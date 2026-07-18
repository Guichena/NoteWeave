package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
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
        jdbcTemplate.update("""
                update segment_summary_revision set status = 'STALE'
                where status in ('BUILDING', 'READY') and segment_id in (
                    select id from conversation_segment where workspace_id = ? and conversation_id = ?
                      and covered_start_seq <= (select message_seq from conversation_message where id = ?)
                      and covered_end_seq >= (select message_seq from conversation_message where id = ?)
                )
                """, workspaceId, conversationId, messageId, messageId);
        return new DeletedConversationMessageResponse(messageId, "DELETED");
    }
}
