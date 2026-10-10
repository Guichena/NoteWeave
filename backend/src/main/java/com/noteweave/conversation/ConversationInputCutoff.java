package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** Validates the frozen user-query boundary before the assistant placeholder completes. */
final class ConversationInputCutoff {
    private ConversationInputCutoff() {}

    static void requirePendingAssistant(JdbcTemplate jdbc, String workspaceId,
                                        String conversationId, int cutoffSeq) {
        if (cutoffSeq < 1) throw invalid();
        Integer valid = jdbc.queryForObject("""
                select count(*) from conversation_message q
                join conversation_message placeholder
                  on placeholder.conversation_id = q.conversation_id
                 and placeholder.workspace_id = q.workspace_id
                 and placeholder.message_seq = q.message_seq + 1
                 and placeholder.reply_to_message_id = q.id
                where q.workspace_id = ? and q.conversation_id = ?
                  and q.message_seq = ? and q.role = 'USER'
                  and q.context_status = 'CURRENT'
                  and placeholder.role = 'ASSISTANT'
                  and placeholder.context_status = 'PENDING'
                  and not exists (
                    select 1 from conversation_message later
                    where later.conversation_id = q.conversation_id
                      and later.message_seq > placeholder.message_seq
                  )
                """, Integer.class, workspaceId, conversationId, cutoffSeq);
        if (valid == null || valid != 1) throw invalid();
    }

    private static BusinessException invalid() {
        return new BusinessException("CONTEXT_INPUT_CUTOFF_INVALID",
                "Input cutoff must end at the current user query before one pending assistant placeholder",
                HttpStatus.CONFLICT);
    }
}
