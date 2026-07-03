package com.noteweave.chat;

import com.noteweave.chat.RetrievalService.RetrievedChunk;
import com.noteweave.chat.RetrievalService.CandidateSource;
import com.noteweave.chat.RetrievalService.ReadingWindow;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.knowledge.KnowledgeService;
import com.noteweave.knowledge.KnowledgeService.KnowledgePageHit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {

    private final JdbcTemplate jdbcTemplate;
    private final RetrievalService retrievalService;
    private final KnowledgeService knowledgeService;

    public ChatService(JdbcTemplate jdbcTemplate, RetrievalService retrievalService, KnowledgeService knowledgeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.retrievalService = retrievalService;
        this.knowledgeService = knowledgeService;
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

        AnswerDraft draft = buildAnswerDraft(conversation.workspaceId(), request);
        String assistantRequestId = Ids.newId();
        String assistantMessageId = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, answer_mode, content, assistant_request_id)
                values (?, ?, ?, ?, 'ASSISTANT', ?, ?, ?)
                """, assistantMessageId, conversationId, conversation.workspaceId(), nextSeq + 1, request.answerMode(), draft.answer(), assistantRequestId);
        jdbcTemplate.update("update conversation set last_active_at = current_timestamp where id = ?", conversationId);
        persistCitations(conversation.workspaceId(), assistantMessageId, draft.evidence());
        bindExistingCitations(assistantMessageId, draft.existingCitationIds(), draft.evidence().size());
        return new SendMessageResponse(userMessageId, assistantMessageId, assistantRequestId, "/api/v2/chat/requests/" + assistantRequestId + "/stream");
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

    private AnswerDraft buildAnswerDraft(String workspaceId, SendMessageRequest request) {
        return switch (request.answerMode()) {
            case "NOTE" -> buildNoteAnswer(workspaceId, request);
            case "WIKI" -> buildWikiAnswer(workspaceId, request);
            default -> buildQaAnswer(workspaceId, request);
        };
    }

    private AnswerDraft buildQaAnswer(String workspaceId, SendMessageRequest request) {
        List<RetrievedChunk> evidence = retrievalService.retrieveForQa(workspaceId, request.content());
        if (evidence.isEmpty()) {
            return new AnswerDraft("当前工作台资料中暂未检索到足够依据，建议先上传相关资料后再提问。", List.of(), List.of());
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
        return new AnswerDraft(builder.toString(), evidence, List.of());
    }

    private AnswerDraft buildNoteAnswer(String workspaceId, SendMessageRequest request) {
        List<CandidateSource> candidates = retrievalService.findCandidateSourcesForNote(workspaceId, request.content());
        List<ReadingWindow> windows = retrievalService.openSourceWindowsForNote(workspaceId, candidates);
        List<RetrievedChunk> evidence = windows.stream().map(ReadingWindow::toRetrievedChunk).toList();
        if (candidates.isEmpty() || windows.isEmpty()) {
            return new AnswerDraft("当前工作台资料不足，暂时无法形成结构化笔记。请先上传或保存更多资料。", List.of(), List.of());
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## 直接回答\n");
        builder.append("我先按 Note 链路把问题拆成可整理的资料笔记：").append(request.content()).append("\n\n");
        builder.append("## 关键观点\n");
        for (int i = 0; i < windows.size(); i++) {
            ReadingWindow window = windows.get(i);
            builder.append("- 观点 ").append(i + 1).append("：来自《").append(window.title()).append("》的原文窗口，说明：")
                    .append(trim(window.content(), 140)).append("\n");
        }
        builder.append("\n## 候选资料\n");
        for (CandidateSource candidate : candidates) {
            builder.append("- 《").append(candidate.title()).append("》：")
                    .append(candidate.sourceType()).append("，可读片段数 ").append(candidate.chunkCount())
                    .append("，匹配分 ").append(candidate.score()).append("\n");
        }
        builder.append("\n## 摘录卡片\n");
        for (int i = 0; i < windows.size(); i++) {
            ReadingWindow window = windows.get(i);
            builder.append("- 摘录 ").append(i + 1).append("：")
                    .append(trim(window.content(), 220))
                    .append("（来源：").append(window.title()).append(" / ").append(window.locationInfo()).append("）\n");
        }
        builder.append("\n## 结构化笔记\n");
        builder.append("### 直接结论\n");
        builder.append("这个问题可以先基于上面的候选资料和摘录形成一版可编辑笔记。\n\n");
        builder.append("### 待确认问题\n");
        builder.append("- 是否需要继续打开更多原文窗口？\n");
        builder.append("- 是否要把这份整理保存到工作台 Note？\n");
        return new AnswerDraft(builder.toString(), evidence, List.of());
    }

    private AnswerDraft buildWikiAnswer(String workspaceId, SendMessageRequest request) {
        List<KnowledgePageHit> pages = knowledgeService.findRelevantWikiPages(workspaceId, request.content());
        List<String> wikiCitationIds = knowledgeService.citationIdsForWikiPages(pages);
        if (pages.isEmpty()) {
            List<RetrievedChunk> fallbackEvidence = retrievalService.retrieveForQa(workspaceId, request.content());
            StringBuilder fallback = new StringBuilder();
            fallback.append("## 基于 Wiki 的回答\n");
            fallback.append("当前默认 Wiki 工作台还没有可直接命中的页面，因此本次先回退到工作台资料补充回答。\n\n");
            fallback.append("## 来源补充\n");
            for (RetrievedChunk chunk : fallbackEvidence) {
                fallback.append("- ").append(chunk.title()).append("：").append(trim(chunk.content(), 180)).append("\n");
            }
            fallback.append("\n## 可选操作\n");
            fallback.append("进入 Wiki 工作台：/workspaces/").append(workspaceId).append("/wiki\n");
            fallback.append("可以把稳定内容整理成正式 Wiki 页面，后续 Wiki 模式会优先读取它。");
            return new AnswerDraft(fallback.toString(), fallbackEvidence, List.of());
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## 基于 Wiki 的回答\n");
        builder.append("我优先读取了当前工作台已经沉淀的 Wiki 页面，并基于页面网络给出回答。\n\n");
        builder.append("## 相关 Wiki 页面\n");
        for (KnowledgePageHit page : pages) {
            builder.append("- 《").append(page.title()).append("》v").append(page.versionNo())
                    .append("：").append(trim(page.summary().isBlank() ? page.content() : page.summary(), 180)).append("\n");
        }
        builder.append("\n## 简要结论\n");
        builder.append(trim(pages.get(0).content(), 420)).append("\n\n");
        builder.append("## 默认 Wiki 工作台\n");
        builder.append("/workspaces/").append(workspaceId).append("/wiki\n");
        return new AnswerDraft(builder.toString(), List.of(), wikiCitationIds);
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

    private void bindExistingCitations(String messageId, List<String> citationIds, int sortOffset) {
        for (int i = 0; i < citationIds.size(); i++) {
            jdbcTemplate.update("""
                    insert into message_citation(id, message_id, citation_id, sort_order)
                    values (?, ?, ?, ?)
                    """, Ids.newId(), messageId, citationIds.get(i), sortOffset + i);
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

    private record AnswerDraft(String answer, List<RetrievedChunk> evidence, List<String> existingCitationIds) {
    }
}
