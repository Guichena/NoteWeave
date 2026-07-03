package com.noteweave.chat;

import com.noteweave.chat.RetrievalService.CandidateSource;
import com.noteweave.chat.RetrievalService.NoteJournalHit;
import com.noteweave.chat.RetrievalService.NoteRecallPlan;
import com.noteweave.chat.RetrievalService.ReadingWindow;
import com.noteweave.chat.RetrievalService.RetrievedChunk;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.knowledge.KnowledgeService;
import com.noteweave.knowledge.KnowledgeService.KnowledgePageHit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        String questionType = classifyQuestion(request.content());
        Set<String> sourceTitles = new LinkedHashSet<>();
        for (RetrievedChunk chunk : evidence) {
            sourceTitles.add(chunk.title());
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## 直接回答\n");
        builder.append("根据当前工作台资料，可以先给出一个低延迟、可引用的资料问答回答。");
        if ("comparison".equals(questionType)) {
            builder.append("这个问题属于比较类问题，因此证据选择会优先覆盖不同资料来源，避免只引用同一份资料。");
        }
        builder.append("\n\n");
        builder.append("## 证据选择\n");
        builder.append("- 查询意图：").append(questionType).append("\n");
        builder.append("- 检索边界：当前 workspace 内已解析资料\n");
        builder.append("- 检索策略：关键词召回 + 结构化元数据过滤 + 轻量 rerank + 来源覆盖\n");
        builder.append("- 来源覆盖：").append(sourceTitles.size()).append(" 个资料来源");
        if (!sourceTitles.isEmpty()) {
            builder.append("（").append(String.join("、", sourceTitles)).append("）");
        }
        builder.append("\n\n");
        builder.append("## 关键依据\n");
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunk chunk = evidence.get(i);
            builder.append("- 证据 ").append(i + 1)
                    .append("《").append(chunk.title()).append("》")
                    .append("：").append(trim(chunk.content(), 220))
                    .append("（").append(chunk.locationInfo())
                    .append("，score=").append(chunk.score())
                    .append("，reason=").append(chunk.matchReason()).append("）\n");
        }
        builder.append("\n## 引用来源\n");
        builder.append("本轮回答的事实依据全部来自当前轮选中的资料片段，引用信息会通过 `chat.citation` 事件返回，可回跳到 source / snapshot / chunk。\n\n");
        builder.append("## 可继续操作\n");
        builder.append("- 如果需要逐篇深读和摘录卡片，可以切换到 Note 链路。\n");
        builder.append("- 如果问题依赖长期页面网络，可以切换到 Wiki 链路。\n");
        builder.append("- 如果需要外部网页研究，可以启动 Deep Research。\n");
        return new AnswerDraft(builder.toString(), evidence, List.of());
    }

    private AnswerDraft buildNoteAnswer(String workspaceId, SendMessageRequest request) {
        NoteRecallPlan recallPlan = retrievalService.findNoteRecallPlan(workspaceId, request.content());
        List<CandidateSource> candidates = recallPlan.candidateSources();
        List<NoteJournalHit> journalHits = recallPlan.journalHits();
        List<CandidateSource> relationExpansionSources = recallPlan.relationExpansionSources();
        List<CandidateSource> verifySources = recallPlan.verifySources();
        List<ReadingWindow> windows = retrievalService.openSourceWindowsForNote(workspaceId, verifySources, request.content());
        List<RetrievedChunk> evidence = windows.stream().map(ReadingWindow::toRetrievedChunk).toList();
        if (candidates.isEmpty() || windows.isEmpty()) {
            return new AnswerDraft("当前工作台资料不足，暂时无法通过 Marginalia 式资料级检索形成可靠回答。请先上传或保存更多资料。", List.of(), List.of());
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## 直接回答\n");
        builder.append("我会按 Marginalia 式结构化检索漏斗处理这个问题：先用 metadata / tag / journal 信号定位候选资料，再打开原文窗口读取摘录证据，最后生成带引用回答；结构化笔记只是可选沉淀。\n\n");
        builder.append("问题：").append(request.content()).append("\n\n");
        if (!journalHits.isEmpty()) {
            builder.append("## Journal 信号\n");
            for (NoteJournalHit hit : journalHits) {
                builder.append("- 历史 Note《").append(hit.title()).append("》：")
                        .append(trim(hit.summary().isBlank() ? hit.content() : hit.summary(), 140))
                        .append("，引用数 ").append(hit.citationCount())
                        .append("，匹配分 ").append(hit.score()).append("\n");
            }
            builder.append("\n");
        }
        builder.append("## 候选资料\n");
        for (CandidateSource candidate : candidates) {
            builder.append("- 《").append(candidate.title()).append("》：")
                    .append(candidate.sourceType()).append("，可读片段数 ").append(candidate.chunkCount())
                    .append("，匹配分 ").append(candidate.score())
                    .append("，召回信号：").append(candidate.recallSignals());
            if (!candidate.summary().isBlank()) {
                builder.append("，摘要：").append(trim(candidate.summary(), 120));
            }
            builder.append("\n");
        }
        builder.append("\n## 关系扩展\n");
        if (relationExpansionSources.isEmpty()) {
            builder.append("本轮没有新增关系扩展资料，系统直接进入原文验证批次。\n\n");
        } else {
            builder.append("系统会把命中资料的标题、摘要、标签、历史 Note 引用和资料窗口可读性作为轻量关系信号，并把相邻资料加入扩展候选：\n");
            for (CandidateSource candidate : relationExpansionSources) {
                builder.append("- 《").append(candidate.title()).append("》：")
                        .append(candidate.sourceType())
                        .append("，召回信号：").append(candidate.recallSignals())
                        .append("，匹配分 ").append(candidate.score()).append("\n");
            }
            builder.append("\n");
        }
        builder.append("## 验证批次\n");
        builder.append("- candidate_sources: ").append(candidates.size()).append("\n");
        builder.append("- relation_expansion_sources: ").append(relationExpansionSources.size()).append("\n");
        builder.append("- verify_batch_sources: ").append(verifySources.size()).append("\n");
        builder.append("- trace: metadata=").append(recallPlan.trace().metadataScoreSum())
                .append(", journal=").append(recallPlan.trace().noteScoreSum())
                .append(", relation=").append(recallPlan.trace().relationScoreSum()).append("\n");
        for (CandidateSource candidate : verifySources) {
            builder.append("- verify《").append(candidate.title()).append("》：")
                    .append(candidate.sourceType())
                    .append("，召回信号：").append(candidate.recallSignals()).append("\n");
        }
        builder.append("\n");
        builder.append("## 关键观点\n");
        for (int i = 0; i < windows.size(); i++) {
            ReadingWindow window = windows.get(i);
            builder.append("- 观点 ").append(i + 1).append("：来自《").append(window.title()).append("》的原文窗口，说明：")
                    .append(trim(window.content(), 140))
                    .append("，窗口分 ").append(window.score()).append("\n");
        }
        builder.append("\n## 摘录证据\n");
        for (int i = 0; i < windows.size(); i++) {
            ReadingWindow window = windows.get(i);
            builder.append("- 摘录卡 ").append(i + 1).append("：")
                    .append(trim(window.content(), 220))
                    .append("（来源：").append(window.title()).append(" / ").append(window.locationInfo()).append("）\n");
        }
        builder.append("\n## 可选结构化笔记\n");
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
            StringBuilder fallback = new StringBuilder();
            fallback.append("## 基于全量 Wiki 的回答\n");
            fallback.append("当前 Wiki 知识网络还没有可直接命中的正式页面，因此本次不退回普通资料 RAG 直接作答。\n\n");
            fallback.append("## 建议动作\n");
            fallback.append("- 先在右侧知识沉淀区创建 Wiki 页面。\n");
            fallback.append("- 或先用 Note 链路通过资料级检索读取原文窗口，再把稳定结论写入工作台 Wiki 页面。\n");
            fallback.append("- Wiki 页面创建后，本模式会优先检索 Wiki Index、页面正文、页面链接、反向链接和来源回链回答。\n");
            fallback.append("\n## 默认 Wiki 工作台\n");
            fallback.append("/workspaces/").append(workspaceId).append("/wiki\n");
            return new AnswerDraft(fallback.toString(), List.of(), List.of());
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## 基于全量 Wiki 的回答\n");
        builder.append("我优先检索当前工作台已经沉淀的 Wiki Index、页面正文、页面链接、反向链接和来源回链，再基于正式知识网络给出回答。\n\n");
        builder.append("## 相关 Wiki 页面\n");
        for (KnowledgePageHit page : pages) {
            builder.append("- 《").append(page.title()).append("》v").append(page.versionNo())
                    .append("：").append(trim(page.summary().isBlank() ? page.content() : page.summary(), 180)).append("\n");
        }
        builder.append("\n## 简要结论\n");
        builder.append(trim(pages.get(0).content(), 420)).append("\n\n");
        builder.append("## 页面关系\n");
        builder.append("相关页面关系、反向链接、图谱、统计、日志和 Wiki lint 可在默认 Wiki 工作台中查看。\n\n");
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

    private String classifyQuestion(String content) {
        String value = content == null ? "" : content.toLowerCase();
        if (value.contains("比较") || value.contains("对比") || value.contains("区别") || value.contains("compare")) {
            return "comparison";
        }
        if (value.contains("总结") || value.contains("概括") || value.contains("summary")) {
            return "summary";
        }
        if (value.contains("来源") || value.contains("引用") || value.contains("citation") || value.contains("source")) {
            return "source_lookup";
        }
        if (value.contains("为什么") || value.contains("原因") || value.contains("推理") || value.contains("why")) {
            return "reasoning";
        }
        return "definition";
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
