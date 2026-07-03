package com.noteweave.chat;

import com.noteweave.chat.RetrievalService.RetrievedChunk;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {

    private final JdbcTemplate jdbcTemplate;
    private final RetrievalService retrievalService;

    public ChatService(JdbcTemplate jdbcTemplate, RetrievalService retrievalService) {
        this.jdbcTemplate = jdbcTemplate;
        this.retrievalService = retrievalService;
    }

    @Transactional
    public SendMessageResponse sendMessage(String conversationId, SendMessageRequest request) {
        ConversationRef conversation = findConversation(conversationId);
        int nextSeq = nextMessageSeq(conversationId);
        String userMessageId = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, answer_mode, content)
                values (?, ?, ?, ?, 'USER', ?, ?)
                """, userMessageId, conversationId, conversation.workspaceId(), nextSeq, request.answerMode(), request.content());

        List<RetrievedChunk> evidence = "QA".equals(request.answerMode())
                ? retrievalService.retrieveForQa(conversation.workspaceId(), request.content())
                : List.of();
        String assistantRequestId = Ids.newId();
        String assistantMessageId = Ids.newId();
        String answer = buildAnswer(request, evidence);
        jdbcTemplate.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, answer_mode, content, assistant_request_id)
                values (?, ?, ?, ?, 'ASSISTANT', ?, ?, ?)
                """, assistantMessageId, conversationId, conversation.workspaceId(), nextSeq + 1, request.answerMode(), answer, assistantRequestId);
        jdbcTemplate.update("update conversation set last_active_at = current_timestamp where id = ?", conversationId);
        persistCitations(conversation.workspaceId(), assistantMessageId, evidence);
        return new SendMessageResponse(userMessageId, assistantRequestId, "/api/v2/chat/requests/" + assistantRequestId + "/stream");
    }

    public String stream(String assistantRequestId) {
        MessageRef message = findAssistantMessage(assistantRequestId);
        List<CitationItem> citations = citationsForMessage(message.messageId());
        StringBuilder builder = new StringBuilder();
        builder.append("event: chat.delta\n");
        builder.append("data: ").append(escape(message.content())).append("\n\n");
        for (CitationItem citation : citations) {
            builder.append("event: chat.citation\n");
            builder.append("data: ").append(escape(citation.title() + " | " + citation.quoteText())).append("\n\n");
        }
        builder.append("event: chat.completed\n");
        builder.append("data: ").append(message.messageId()).append("\n\n");
        return builder.toString();
    }

    private String buildAnswer(SendMessageRequest request, List<RetrievedChunk> evidence) {
        if (!"QA".equals(request.answerMode())) {
            return "当前阶段只实现 QA 链路，Note/Wiki 链路会在阶段3接入。";
        }
        if (evidence.isEmpty()) {
            return "当前工作台资料中暂未检索到足够依据，建议先上传相关资料后再提问。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("根据当前工作台资料，可以先给出一个基于证据的回答：\n\n");
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunk chunk = evidence.get(i);
            builder.append(i + 1).append(". ");
            builder.append(trim(chunk.content(), 220));
            builder.append("\n");
        }
        builder.append("\n以上内容来自已上传资料的可回溯片段，引用信息会随回答一起返回。");
        return builder.toString();
    }

    private void persistCitations(String workspaceId, String messageId, List<RetrievedChunk> evidence) {
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunk chunk = evidence.get(i);
            String citationId = Ids.newId();
            jdbcTemplate.update("""
                    insert into citation(id, workspace_id, source_id, source_snapshot_id, source_chunk_id, title, quote_text, page_no, location_info)
                    values (?, ?, ?, ?, ?, ?, ?, null, ?)
                    """, citationId, workspaceId, chunk.sourceId(), chunk.sourceSnapshotId(), chunk.chunkId(), chunk.title(),
                    trim(chunk.content(), 360), chunk.locationInfo());
            jdbcTemplate.update("""
                    insert into message_citation(id, message_id, citation_id, sort_order)
                    values (?, ?, ?, ?)
                    """, Ids.newId(), messageId, citationId, i);
        }
    }

    private ConversationRef findConversation(String conversationId) {
        return jdbcTemplate.query("""
                select id, workspace_id from conversation where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("CONVERSATION_NOT_FOUND", "会话不存在");
            }
            return new ConversationRef(rs.getString("id"), rs.getString("workspace_id"));
        }, conversationId);
    }

    private MessageRef findAssistantMessage(String assistantRequestId) {
        return jdbcTemplate.query("""
                select id, content from conversation_message where assistant_request_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("CHAT_REQUEST_NOT_FOUND", "聊天请求不存在");
            }
            return new MessageRef(rs.getString("id"), rs.getString("content"));
        }, assistantRequestId);
    }

    private List<CitationItem> citationsForMessage(String messageId) {
        return jdbcTemplate.query("""
                select c.id, c.source_id, c.title, c.quote_text, c.page_no, c.location_info
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ?
                order by mc.sort_order asc
                """, (rs, rowNum) -> new CitationItem(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("title"),
                rs.getString("quote_text"),
                (Integer) rs.getObject("page_no"),
                rs.getString("location_info")
        ), messageId);
    }

    private int nextMessageSeq(String conversationId) {
        Integer maxSeq = jdbcTemplate.queryForObject("""
                select coalesce(max(message_seq), 0) from conversation_message where conversation_id = ?
                """, Integer.class, conversationId);
        return maxSeq == null ? 1 : maxSeq + 1;
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "...";
    }

    private String escape(String data) {
        return data.replace("\r", "").replace("\n", "\\n");
    }

    private record ConversationRef(String conversationId, String workspaceId) {
    }

    private record MessageRef(String messageId, String content) {
    }
}
