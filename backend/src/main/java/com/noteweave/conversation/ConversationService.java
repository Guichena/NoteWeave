package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.workspace.WorkspaceService;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceService workspaceService;

    public ConversationService(JdbcTemplate jdbcTemplate, WorkspaceService workspaceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceService = workspaceService;
    }

    @Transactional
    public ConversationResponse createConversation(String workspaceId, CreateConversationRequest request) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        String conversationId = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation(id, workspace_id, title, conversation_type, status)
                values (?, ?, ?, ?, 'ACTIVE')
                """, conversationId, workspaceId, request.title(), request.conversationType());
        return new ConversationResponse(conversationId, request.title(), request.conversationType(), Instant.now());
    }
}
