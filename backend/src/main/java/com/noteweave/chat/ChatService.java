package com.noteweave.chat;

import com.noteweave.chat.RetrievalService.CandidateSource;
import com.noteweave.chat.RetrievalService.NoteJournalHit;
import com.noteweave.chat.RetrievalService.NoteEntryMetadata;
import com.noteweave.chat.RetrievalService.NoteRecallPlan;
import com.noteweave.chat.RetrievalService.ReadingWindow;
import com.noteweave.chat.RetrievalService.RelatedEntryPreview;
import com.noteweave.chat.RetrievalService.RetrievedChunk;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.knowledge.KnowledgeCitationResponse;
import com.noteweave.knowledge.KnowledgeService;
import com.noteweave.knowledge.KnowledgeService.KnowledgePageHit;
import com.noteweave.knowledge.KnowledgeService.WikiPageContext;
import com.noteweave.knowledge.WikiLinkResponse;
import com.noteweave.memory.MemoryCompilerService;
import com.noteweave.memory.MemoryControlPackResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {

    private final JdbcTemplate jdbcTemplate;
    private final RetrievalService retrievalService;
    private final KnowledgeService knowledgeService;
    private final MemoryCompilerService memoryCompilerService;

    public ChatService(
            JdbcTemplate jdbcTemplate,
            RetrievalService retrievalService,
            KnowledgeService knowledgeService,
            MemoryCompilerService memoryCompilerService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.retrievalService = retrievalService;
        this.knowledgeService = knowledgeService;
        this.memoryCompilerService = memoryCompilerService;
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

        AnswerDraft draft = buildAnswerDraft(conversationId, userMessageId, conversation.workspaceId(), request);
        String assistantRequestId = Ids.newId();
        String assistantMessageId = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, answer_mode, content, assistant_request_id)
                values (?, ?, ?, ?, 'ASSISTANT', ?, ?, ?)
                """, assistantMessageId, conversationId, conversation.workspaceId(), nextSeq + 1, request.answerMode(), draft.answer(), assistantRequestId);
        jdbcTemplate.update("update conversation set last_active_at = current_timestamp where id = ?", conversationId);
        persistCitations(conversation.workspaceId(), assistantMessageId, draft.evidence());
        bindExistingCitations(assistantMessageId, draft.existingCitationIds(), draft.evidence().size());
        memoryCompilerService.logPackUsage(
                conversation.workspaceId(),
                request.answerMode(),
                "CONVERSATION_MESSAGE",
                assistantMessageId,
                draft.chatControlPack()
        );
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

    private AnswerDraft buildAnswerDraft(String conversationId, String currentUserMessageId, String workspaceId, SendMessageRequest request) {
        ConversationContext context = buildConversationContext(conversationId, currentUserMessageId, request.content());
        MemoryControlPackResponse chatControlPack = memoryCompilerService.compileChatControlPack(workspaceId, request.answerMode());
        return switch (request.answerMode()) {
            case "NOTE" -> buildNoteAnswer(workspaceId, request, context, chatControlPack);
            case "WIKI" -> buildWikiAnswer(workspaceId, request, context, chatControlPack);
            default -> buildQaAnswer(workspaceId, request, context, chatControlPack);
        };
    }

    private AnswerDraft buildQaAnswer(
            String workspaceId,
            SendMessageRequest request,
            ConversationContext context,
            MemoryControlPackResponse chatControlPack
    ) {
        List<RetrievedChunk> evidence = retrievalService.retrieveForQa(workspaceId, context.retrievalQuestion());
        if (evidence.isEmpty()) {
            return new AnswerDraft("当前工作台资料中暂未检索到足够依据，可先上传相关资料后再提问。", List.of(), List.of(), chatControlPack);
        }
        String questionType = classifyQuestion(context.currentQuestion());
        Set<String> sourceTitles = new LinkedHashSet<>();
        for (RetrievedChunk chunk : evidence) {
            sourceTitles.add(chunk.title());
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## 直接回答\n");
        builder.append("根据当前工作台资料，可以先给出一个低延迟、可引用的资料问答回答。");
        if (context.contextApplied()) {
            builder.append("本轮已结合最近连续对话窗口理解这次追问。");
        }
        if (chatControlPack.hasControls()) {
            builder.append("本轮还应用了工作台级 Chat Control Pack。");
        }
        if ("comparison".equals(questionType)) {
            builder.append("这个问题属于比较类问题，因此证据选择会优先覆盖不同资料来源，避免只引用同一份资料。");
        }
        builder.append("\n\n");
        builder.append("## 证据选择\n");
        builder.append("- 查询意图：").append(questionType).append("\n");
        if (context.contextApplied()) {
            builder.append("- 会话上下文：已纳入最近连续对话窗口\n");
            builder.append("- 连续对话窗口：最近 ").append(context.windowTurnCount()).append(" 轮相关对话\n");
            if (!context.topicAnchor().isBlank()) {
                builder.append("- 主题锚点：").append(context.topicAnchor()).append("\n");
            }
            if (!context.topicSummary().isBlank()) {
                builder.append("- 前序主题摘要：").append(context.topicSummary()).append("\n");
            }
        }
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
        appendChatControlSection(builder, chatControlPack);
        builder.append("## 可继续操作\n");
        builder.append("- 如果需要逐篇深读和摘录卡片，可以切换到 Note 链路。\n");
        builder.append("- 如果问题依赖长期页面网络，可以切换到 Wiki 链路。\n");
        return new AnswerDraft(builder.toString(), evidence, List.of(), chatControlPack);
    }

    private AnswerDraft buildNoteAnswer(
            String workspaceId,
            SendMessageRequest request,
            ConversationContext context,
            MemoryControlPackResponse chatControlPack
    ) {
        NoteRecallPlan recallPlan = retrievalService.findNoteRecallPlan(workspaceId, context.retrievalQuestion());
        List<CandidateSource> candidates = recallPlan.candidateSources();
        List<NoteJournalHit> journalHits = recallPlan.journalHits();
        List<CandidateSource> relationExpansionSources = recallPlan.relationExpansionSources();
        List<CandidateSource> verifySources = recallPlan.verifySources();
        List<NoteEntryMetadata> metadataEntries = retrievalService.readEntriesMetadataForNote(workspaceId, verifySources, context.retrievalQuestion());
        List<ReadingWindow> windows = retrievalService.openSourceWindowsForNote(workspaceId, verifySources, context.retrievalQuestion());
        List<RetrievedChunk> evidence = windows.stream().map(ReadingWindow::toRetrievedChunk).toList();
        if (candidates.isEmpty() || windows.isEmpty()) {
            return new AnswerDraft("当前工作台资料不足，暂时无法通过 Marginalia 式资料级检索形成可靠回答。请先上传或保存更多资料。", List.of(), List.of(), chatControlPack);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## 直接回答\n");
        builder.append(buildNoteSynthesis(context.currentQuestion(), windows, journalHits, context, chatControlPack)).append("\n\n");
        builder.append("## 资料定位\n");
        builder.append("【定位说明】\n");
        builder.append("本轮按 Marginalia 式结构化检索漏斗先定位资料，再决定深读窗口；对外以正常聊天回答为主，资料依据通过可展开卡片展示。\n");
        builder.append("- 当前问题：").append(request.content()).append("\n");
        if (context.contextApplied()) {
            builder.append("- 会话上下文：已纳入最近连续对话窗口，避免把这轮追问当成孤立查询\n");
            builder.append("- 连续对话窗口：最近 ").append(context.windowTurnCount()).append(" 轮相关对话\n");
            if (!context.topicAnchor().isBlank()) {
                builder.append("- 主题锚点：").append(context.topicAnchor()).append("\n");
            }
            if (!context.topicSummary().isBlank()) {
                builder.append("- 前序主题摘要：").append(context.topicSummary()).append("\n");
            }
        }
        builder.append("- 呈现策略：聊天正文只保留直接回答，检索细节以下挂卡片折叠展示。\n");
        if (!journalHits.isEmpty()) {
            builder.append("\n【Journal 信号】\n");
            for (NoteJournalHit hit : journalHits) {
                builder.append("- 历史 Note《").append(hit.title()).append("》：")
                        .append(trim(hit.summary().isBlank() ? hit.content() : hit.summary(), 140))
                        .append("，引用数 ").append(hit.citationCount())
                        .append("，匹配分 ").append(hit.score());
                if (!"fresh".equals(hit.freshnessStatus())) {
                    builder.append("，状态 ").append(hit.freshnessStatus())
                            .append("（").append(hit.freshnessNote()).append("）");
                    if (hit.staleSourceCount() > 0) {
                        builder.append("，stale_sources=").append(hit.staleSourceCount());
                    }
                    if (hit.unavailableSourceCount() > 0) {
                        builder.append("，unavailable_sources=").append(hit.unavailableSourceCount());
                    }
                }
                builder.append("\n");
            }
        }
        builder.append("\n【候选资料】\n");
        for (CandidateSource candidate : candidates) {
            builder.append("- 《").append(candidate.title()).append("》：")
                    .append(candidate.sourceType()).append("，可读片段数 ").append(candidate.chunkCount())
                    .append("，可读窗口数 ").append(candidate.windowCount())
                    .append("，匹配分 ").append(candidate.score())
                    .append("，召回信号：").append(candidate.recallSignals());
            if (candidate.totalQueryTerms() > 0) {
                builder.append("，query_coverage=").append(candidate.coveredQueryTerms()).append("/").append(candidate.totalQueryTerms());
            }
            if (!candidate.coverageTerms().isEmpty()) {
                builder.append("，coverage_terms=").append(String.join("/", candidate.coverageTerms()));
            }
            if (!candidate.matchedFields().isEmpty()) {
                builder.append("，matched_fields=").append(String.join("/", candidate.matchedFields()));
            }
            if (candidate.selectionReason() != null && !candidate.selectionReason().isBlank()) {
                builder.append("，selection_reason=").append(candidate.selectionReason());
            }
            if (!candidate.summary().isBlank()) {
                builder.append("，摘要：").append(trim(candidate.summary(), 120));
            }
            builder.append("\n");
        }
        builder.append("\n【关系扩展】\n");
        if (relationExpansionSources.isEmpty()) {
            builder.append("本轮没有新增关系扩展资料，系统直接进入原文验证批次。\n");
        } else {
            builder.append("系统会把命中资料的标题、摘要、标签、历史 Note 引用和资料窗口可读性作为轻量关系信号，并把相邻资料加入扩展候选：\n");
            for (CandidateSource candidate : relationExpansionSources) {
                builder.append("- 《").append(candidate.title()).append("》：")
                        .append(candidate.sourceType())
                        .append("，召回信号：").append(candidate.recallSignals())
                        .append("，匹配分 ").append(candidate.score()).append("\n");
            }
        }
        builder.append("\n【验证摘要】\n");
        builder.append("- candidate_sources: ").append(candidates.size()).append("\n");
        builder.append("- relation_expansion_sources: ").append(relationExpansionSources.size()).append("\n");
        builder.append("- verify_batch_sources: ").append(verifySources.size()).append("\n");
        builder.append("- candidate_quota_trace: ").append(summarizeCandidateSelection(candidates)).append("\n");
        builder.append("- verify_admission_trace: ").append(summarizeVerifyAdmission(verifySources)).append("\n");
        builder.append("- trace: metadata=").append(recallPlan.trace().metadataScoreSum())
                .append(", journal=").append(recallPlan.trace().noteScoreSum())
                .append(", relation=").append(recallPlan.trace().relationScoreSum())
                .append(", readiness=").append(recallPlan.trace().readinessScoreSum()).append("\n");
        for (CandidateSource candidate : verifySources) {
            builder.append("- verify《").append(candidate.title()).append("》：")
                    .append(candidate.sourceType())
                    .append("，window_count=").append(candidate.windowCount())
                    .append("，召回信号：").append(candidate.recallSignals());
            if (candidate.totalQueryTerms() > 0) {
                builder.append("，query_coverage=").append(candidate.coveredQueryTerms()).append("/").append(candidate.totalQueryTerms());
            }
            if (!candidate.matchedFields().isEmpty()) {
                builder.append("，matched_fields=").append(String.join("/", candidate.matchedFields()));
            }
            if (candidate.verifyAdmissionReason() != null && !candidate.verifyAdmissionReason().isBlank()) {
                builder.append("，verify_admission_reason=").append(candidate.verifyAdmissionReason());
            }
            builder.append("\n");
        }
        builder.append("\n## 深读窗口\n");
        builder.append("【资料元信息】\n");
        for (NoteEntryMetadata entry : metadataEntries) {
            builder.append("- 《").append(entry.title()).append("》：")
                    .append(entry.sourceType())
                    .append("，parse=").append(entry.parseStatus())
                    .append("，index=").append(entry.indexStatus())
                    .append("，chunk=").append(entry.chunkCount())
                    .append("，window=").append(entry.windowCount())
                    .append("，tags=").append(String.join(" / ", entry.tags())).append("\n");
            if (!entry.metadataSignals().isEmpty()) {
                builder.append("  metadata_signals=")
                        .append(String.join(" | ", entry.metadataSignals()))
                        .append("\n");
            }
            if (!entry.windowLocators().isEmpty()) {
                builder.append("  window_locators=").append(summarizeWindowLocators(entry.windowLocators())).append("\n");
                if (entry.hasMoreWindows()) {
                    builder.append("  window_has_more=true\n");
                }
            }
            if (!entry.relatedEntries().isEmpty()) {
                builder.append("  related_entries=").append(summarizeRelatedEntries(entry.relatedEntries())).append("\n");
            }
        }
        builder.append("\n【原文窗口】\n");
        for (int i = 0; i < windows.size(); i++) {
            ReadingWindow window = windows.get(i);
            builder.append("- read ").append(i + 1)
                    .append("：").append(window.title())
                    .append(" / chunk=").append(window.chunkNo())
                    .append(" / window=").append(window.windowNo())
                    .append(" / read_role=").append(window.readRole());
            if (window.readObjective() != null && !window.readObjective().isBlank()) {
                builder.append(" / read_objective=").append(window.readObjective());
            }
            if ("continuation-window".equals(window.readRole())) {
                builder.append(" / anchor_window=").append(window.anchorWindowNo());
            }
            if (window.heading() != null && !window.heading().isBlank()) {
                builder.append(" / heading=").append(window.heading());
            }
            builder.append(" / locator=").append(window.locationInfo())
                    .append(" / score=").append(window.score())
                    .append("\n");
        }
        builder.append("\n## 摘录证据\n");
        builder.append("【摘录证据】\n");
        for (int i = 0; i < windows.size(); i++) {
            ReadingWindow window = windows.get(i);
            builder.append("- 摘录卡 ").append(i + 1).append("：")
                    .append(trim(window.content(), 220))
                    .append("（来源：").append(window.title());
            if (window.heading() != null && !window.heading().isBlank()) {
                builder.append(" / ").append(window.heading());
            }
            builder.append(" / ").append(window.locationInfo());
            if (window.readRole() != null && !window.readRole().isBlank()) {
                builder.append(" / ").append(window.readRole());
            }
            if (window.readObjective() != null && !window.readObjective().isBlank()) {
                builder.append(" / ").append(window.readObjective());
            }
            builder.append("）\n");
        }
        appendChatControlSection(builder, chatControlPack);
        return new AnswerDraft(builder.toString(), evidence, List.of(), chatControlPack);
    }

    private String buildNoteSynthesis(
            String question,
            List<ReadingWindow> windows,
            List<NoteJournalHit> journalHits,
            ConversationContext context,
            MemoryControlPackResponse chatControlPack
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("基于当前候选资料与原文窗口，可以先给出一版可验证回答：");
        if (context.contextApplied()) {
            builder.append("本轮已结合最近连续对话窗口理解这次追问。");
            if (!context.topicAnchor().isBlank()) {
                builder.append("当前主题锚点是“").append(context.topicAnchor()).append("”。");
            }
        }
        if (chatControlPack.hasControls()) {
            builder.append("本轮还应用了工作台级 Chat Control Pack。");
        }
        boolean hasStaleJournal = journalHits.stream().anyMatch(hit -> !"fresh".equals(hit.freshnessStatus()));
        if (hasStaleJournal) {
            builder.append("历史整理里存在已更新或已失效的来源，本轮已经优先按当前可读原文窗口重新核对。");
        }
        builder.append("\n");
        Set<String> seenSources = new LinkedHashSet<>();
        int count = 0;
        for (ReadingWindow window : windows) {
            if (!seenSources.add(window.sourceId())) {
                continue;
            }
            builder.append("- 《").append(window.title()).append("》指出：")
                    .append(trim(window.content(), 120));
            if (window.heading() != null && !window.heading().isBlank()) {
                builder.append("（").append(window.heading()).append("）");
            }
            builder.append("\n");
            count++;
            if (count >= 3) {
                break;
            }
        }
        if (count == 0) {
            builder.append("- 当前没有足够的原文窗口可用于综合回答。\n");
        }
        String questionType = classifyQuestion(question);
        if ("comparison".equals(questionType) && count >= 2) {
            builder.append("这些资料更适合放在同一轮做对比阅读，再继续展开差异与共识。\n");
        } else if ("reasoning".equals(questionType)) {
            builder.append("这类问题更依赖原文上下文，因此后面的原文窗口与摘录证据会比普通问答更重要。\n");
        }
        return builder.toString().trim();
    }

    private String summarizeWindowLocators(List<RetrievalService.WindowLocator> locators) {
        return locators.stream()
                .limit(3)
                .map(this::formatWindowLocator)
                .reduce((left, right) -> left + " | " + right)
                .orElse("none");
    }

    private String formatWindowLocator(RetrievalService.WindowLocator locator) {
        StringBuilder builder = new StringBuilder();
        builder.append("chunk=").append(locator.chunkNo())
                .append(",window=").append(locator.windowNo());
        if (locator.heading() != null && !locator.heading().isBlank()) {
            builder.append(",heading=").append(locator.heading());
        }
        if (locator.locationInfo() != null && !locator.locationInfo().isBlank()) {
            builder.append(",locator=").append(locator.locationInfo());
        }
        if (locator.readRole() != null && !locator.readRole().isBlank()) {
            builder.append(",read_role=").append(locator.readRole());
        }
        if (locator.readObjective() != null && !locator.readObjective().isBlank()) {
            builder.append(",read_objective=").append(locator.readObjective());
        }
        if (locator.anchorWindowNo() != null) {
            builder.append(",anchor_window=").append(locator.anchorWindowNo());
        }
        builder.append(",score=").append(locator.score());
        return builder.toString();
    }

    private String summarizeRelatedEntries(List<RelatedEntryPreview> relatedEntries) {
        return relatedEntries.stream()
                .limit(3)
                .map(related -> "《" + related.title() + "》"
                        + "(shared_tags=" + related.sharedTagCount()
                        + ",co_cited_notes=" + related.coCitedNoteCount()
                        + ",co_cited_turns=" + related.coCitedTurnCount()
                        + ",lexical_overlap=" + related.lexicalOverlapScore()
                        + ",graph_neighbor=" + related.graphNeighborhoodScore()
                        + ",reason=" + related.relationReason()
                        + ",score=" + related.score() + ")")
                .reduce((left, right) -> left + " | " + right)
                .orElse("none");
    }

    private String summarizeCandidateSelection(List<CandidateSource> candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("journal-quota", 0);
        counts.put("metadata-quota", 0);
        counts.put("relation-quota", 0);
        counts.put("top-score-backfill", 0);
        counts.put("relation-expansion", 0);
        for (CandidateSource candidate : candidates) {
            String reason = candidate.selectionReason();
            if (reason == null || reason.isBlank()) {
                continue;
            }
            counts.merge(reason, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private String summarizeVerifyAdmission(List<CandidateSource> verifySources) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CandidateSource candidate : verifySources) {
            String reason = candidate.verifyAdmissionReason();
            if (reason == null || reason.isBlank()) {
                continue;
            }
            counts.merge(reason, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private AnswerDraft buildWikiAnswer(
            String workspaceId,
            SendMessageRequest request,
            ConversationContext context,
            MemoryControlPackResponse chatControlPack
    ) {
        List<WikiPageContext> contexts = knowledgeService.findRelevantWikiPageContexts(workspaceId, context.retrievalQuestion());
        List<String> wikiCitationIds = knowledgeService.citationIdsForWikiPages(
                contexts.stream().map(WikiPageContext::page).toList()
        );
        if (contexts.isEmpty()) {
            StringBuilder fallback = new StringBuilder();
            fallback.append("## 基于全量 Wiki 的回答\n");
            fallback.append("当前 Wiki 知识网络还没有可直接命中的正式页面，因此本次不退回普通资料 RAG 直接作答。\n\n");
            fallback.append("## 建议动作\n");
            fallback.append("- 如果这是一个需要长期维护的主题，先开启工作台级 Wiki 构建，并让现有资料进入 Wiki ingest。\n");
            fallback.append("- 如果要立刻补齐某个概念页，可以在默认 Wiki 工作台中手动补充或修正页面正文。\n");
            fallback.append("- 或先用 Note 链路通过资料级检索读取原文窗口，再把稳定结论沉淀进工作台 Wiki 网络。\n");
            appendChatControlSection(fallback, chatControlPack);
            fallback.append("\n## 默认 Wiki 工作台\n");
            fallback.append("/workspaces/").append(workspaceId).append("/wiki\n");
            return new AnswerDraft(fallback.toString(), List.of(), List.of(), chatControlPack);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## 基于全量 Wiki 的回答\n");
        builder.append("我优先检索当前工作台已经沉淀的 Wiki Index、页面正文、页面链接、反向链接和来源回链，再基于多页面知识网络综合回答，而不是退回普通资料 chunk 问答。\n\n");
        if (context.contextApplied()) {
            builder.append("本轮已结合最近连续对话窗口理解这次追问。");
            if (!context.topicAnchor().isBlank()) {
                builder.append(" 当前主题锚点是“").append(context.topicAnchor()).append("”。");
            }
            builder.append("\n\n");
        }
        if (chatControlPack.hasControls()) {
            builder.append("本轮还应用了工作台级 Chat Control Pack。\n\n");
        }
        builder.append("## 相关 Wiki 页面\n");
        for (WikiPageContext pageContext : contexts) {
            KnowledgePageHit page = pageContext.page();
            builder.append("- 《").append(page.title()).append("》v").append(page.versionNo())
                    .append("：").append(trim(page.summary().isBlank() ? page.content() : page.summary(), 180))
                    .append("，出链 ").append(pageContext.outgoingLinks().size())
                    .append("，反链 ").append(pageContext.backlinks().size())
                    .append("，来源 ").append(pageContext.citations().size())
                    .append("\n");
        }
        builder.append("\n## 综合结论\n");
        builder.append(buildWikiSynthesis(contexts)).append("\n\n");
        builder.append("## 关键页面关系\n");
        appendWikiLinks(builder, collectNetworkLinks(contexts, true), "当前命中的 Wiki 页面之间还没有足够显式的页面关系。");
        builder.append("\n## 反向引用关系\n");
        appendWikiLinks(builder, collectNetworkLinks(contexts, false), "当前命中的 Wiki 页面暂时没有明显反向引用网络。");
        builder.append("\n## 来源回链\n");
        List<KnowledgeCitationResponse> citations = collectNetworkCitations(contexts);
        if (citations.isEmpty()) {
            builder.append("当前命中页面暂时没有绑定来源引用。\n");
        } else {
            for (KnowledgeCitationResponse citation : citations) {
                builder.append("- ").append(citation.title())
                        .append("：").append(trim(citation.quoteText(), 120))
                        .append("（").append(citation.locationInfo()).append("）\n");
            }
        }
        builder.append("\n## 页面关系\n");
        builder.append("相关页面关系、图谱、待处理任务、问题分层和治理动作都可以在默认 Wiki 工作台中继续查看。\n\n");
        if (context.contextApplied()) {
            builder.append("## 会话上下文\n");
            builder.append("- 连续对话窗口：最近 ").append(context.windowTurnCount()).append(" 轮相关对话\n");
            if (!context.topicAnchor().isBlank()) {
                builder.append("- 主题锚点：").append(context.topicAnchor()).append("\n");
            }
            if (!context.topicSummary().isBlank()) {
                builder.append("- 前序主题摘要：").append(context.topicSummary()).append("\n");
            }
            builder.append("\n");
        }
        appendChatControlSection(builder, chatControlPack);
        builder.append("## 默认 Wiki 工作台\n");
        builder.append("/workspaces/").append(workspaceId).append("/wiki\n");
        return new AnswerDraft(builder.toString(), List.of(), wikiCitationIds, chatControlPack);
    }

    private void appendChatControlSection(StringBuilder builder, MemoryControlPackResponse chatControlPack) {
        if (chatControlPack == null || !chatControlPack.hasControls()) {
            return;
        }
        builder.append("## 表达控制\n");
        appendControlLine(builder, "- 风格约束：", chatControlPack.styleConstraints());
        appendControlLine(builder, "- 结构约束：", chatControlPack.structureConstraints());
        appendControlLine(builder, "- 术语口径：", chatControlPack.terminologyPolicy());
        appendControlLine(builder, "- 禁用路径：", chatControlPack.forbiddenPatterns());
        appendControlLine(builder, "- 交互策略：", chatControlPack.interactionPolicy());
        appendControlLine(builder, "- 复核清单：", chatControlPack.reviewChecklist());
        builder.append("\n");
    }

    private void appendControlLine(StringBuilder builder, String prefix, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append(prefix).append(String.join("；", values)).append("\n");
    }

    private void appendWikiLinks(StringBuilder builder, List<WikiLinkResponse> links, String emptyMessage) {
        if (links.isEmpty()) {
            builder.append(emptyMessage).append("\n");
            return;
        }
        for (WikiLinkResponse link : links) {
            builder.append("- ").append(link.targetTitle())
                    .append("：").append(link.relationStatus())
                    .append("，").append(link.mentionCount()).append(" 次提及\n");
        }
    }

    private String buildWikiSynthesis(List<WikiPageContext> contexts) {
        List<String> facts = new ArrayList<>();
        for (WikiPageContext context : contexts.stream().limit(3).toList()) {
            KnowledgePageHit page = context.page();
            String snippet = trim(page.summary().isBlank() ? page.content() : page.summary(), 150);
            facts.add("《" + page.title() + "》指出：" + snippet);
        }
        if (facts.isEmpty()) {
            return "当前没有足够的 Wiki 页面可综合。";
        }
        return String.join("\n", facts);
    }

    private List<WikiLinkResponse> collectNetworkLinks(List<WikiPageContext> contexts, boolean outgoing) {
        Map<String, WikiLinkResponse> dedup = new LinkedHashMap<>();
        for (WikiPageContext context : contexts.stream().limit(3).toList()) {
            List<WikiLinkResponse> links = outgoing ? context.outgoingLinks() : context.backlinks();
            for (WikiLinkResponse link : links) {
                String key = (link.targetItemId() == null ? "" : link.targetItemId()) + "|" + link.targetTitle() + "|" + link.relationStatus();
                dedup.putIfAbsent(key, link);
                if (dedup.size() >= 6) {
                    return new ArrayList<>(dedup.values());
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private List<KnowledgeCitationResponse> collectNetworkCitations(List<WikiPageContext> contexts) {
        Map<String, KnowledgeCitationResponse> dedup = new LinkedHashMap<>();
        for (WikiPageContext context : contexts.stream().limit(3).toList()) {
            for (KnowledgeCitationResponse citation : context.citations()) {
                String key = citation.sourceId() + "|" + citation.title() + "|" + citation.locationInfo();
                dedup.putIfAbsent(key, citation);
                if (dedup.size() >= 6) {
                    return new ArrayList<>(dedup.values());
                }
            }
        }
        return new ArrayList<>(dedup.values());
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

    private ConversationContext buildConversationContext(String conversationId, String currentUserMessageId, String currentQuestion) {
        String trimmedQuestion = currentQuestion == null ? "" : currentQuestion.trim();
        if (!isContextDependentQuestion(trimmedQuestion)) {
            return new ConversationContext(trimmedQuestion, trimmedQuestion, false, 0, "", "", List.of());
        }
        List<ConversationHistoryMessage> history = jdbcTemplate.query("""
                select message_seq, role, content
                from conversation_message
                where conversation_id = ? and id <> ?
                order by message_seq desc
                limit 12
                """, (rs, rowNum) -> new ConversationHistoryMessage(
                rs.getInt("message_seq"),
                rs.getString("role"),
                rs.getString("content")
        ), conversationId, currentUserMessageId);
        List<ConversationTurn> turns = buildConversationTurns(history);
        if (turns.isEmpty()) {
            return new ConversationContext(trimmedQuestion, trimmedQuestion, false, 0, "", "", List.of());
        }
        List<ConversationTurn> workingTurns = buildWorkingTurns(trimmedQuestion, turns);
        if (workingTurns.isEmpty()) {
            return new ConversationContext(trimmedQuestion, trimmedQuestion, false, 0, "", "", List.of());
        }
        String topicAnchor = buildTopicAnchor(trimmedQuestion, workingTurns);
        String topicSummary = buildTopicSummary(topicAnchor, workingTurns, turns);
        StringBuilder retrievalQuestion = new StringBuilder();
        retrievalQuestion.append("当前问题：").append(trimmedQuestion);
        if (!topicAnchor.isBlank()) {
            retrievalQuestion.append("\n主题锚点：").append(topicAnchor);
        }
        retrievalQuestion.append("\n连续对话窗口：");
        for (ConversationTurn turn : workingTurns) {
            retrievalQuestion.append("\n- 用户：").append(trim(turn.userQuestion(), 120));
            if (!turn.assistantAnswerSummary().isBlank()) {
                retrievalQuestion.append("\n  助手摘要：").append(trim(turn.assistantAnswerSummary(), 180));
            }
        }
        if (!topicSummary.isBlank()) {
            retrievalQuestion.append("\n前序主题摘要：").append(topicSummary);
        }
        return new ConversationContext(
                trimmedQuestion,
                retrievalQuestion.toString(),
                true,
                workingTurns.size(),
                topicAnchor,
                topicSummary,
                List.copyOf(workingTurns)
        );
    }

    private List<ConversationTurn> buildConversationTurns(List<ConversationHistoryMessage> history) {
        List<ConversationTurn> turns = new ArrayList<>();
        String pendingUserQuestion = null;
        int pendingUserSeq = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationHistoryMessage message = history.get(i);
            if ("USER".equals(message.role())) {
                pendingUserQuestion = message.content();
                pendingUserSeq = message.messageSeq();
                continue;
            }
            if ("ASSISTANT".equals(message.role()) && pendingUserQuestion != null && !pendingUserQuestion.isBlank()) {
                turns.add(new ConversationTurn(
                        pendingUserQuestion,
                        stripMarkdownForContext(message.content()),
                        pendingUserSeq
                ));
                pendingUserQuestion = null;
                pendingUserSeq = 0;
            }
        }
        return turns;
    }

    private List<ConversationTurn> buildWorkingTurns(String currentQuestion, List<ConversationTurn> turns) {
        List<ConversationTurn> workingTurns = new ArrayList<>();
        String currentAnchorCandidate = normalizeTopicPhrase(currentQuestion);
        for (int i = turns.size() - 1; i >= 0; i--) {
            ConversationTurn turn = turns.get(i);
            if (workingTurns.isEmpty()) {
                workingTurns.add(0, turn);
                continue;
            }
            if (workingTurns.size() >= 3) {
                break;
            }
            if (belongsToCurrentTopic(currentQuestion, currentAnchorCandidate, turn.userQuestion(), workingTurns)) {
                workingTurns.add(0, turn);
                continue;
            }
            break;
        }
        return workingTurns;
    }

    private boolean belongsToCurrentTopic(
            String currentQuestion,
            String currentAnchorCandidate,
            String candidateQuestion,
            List<ConversationTurn> workingTurns
    ) {
        if (candidateQuestion == null || candidateQuestion.isBlank()) {
            return false;
        }
        if (isFollowUpHeavyQuestion(currentQuestion)) {
            if (!currentAnchorCandidate.isBlank() && shareTopicTerms(currentAnchorCandidate, candidateQuestion)) {
                return true;
            }
            for (ConversationTurn workingTurn : workingTurns) {
                if (shareTopicTerms(workingTurn.userQuestion(), candidateQuestion)) {
                    return true;
                }
            }
            return workingTurns.size() == 1;
        }
        return shareTopicTerms(currentQuestion, candidateQuestion);
    }

    private String buildTopicAnchor(String currentQuestion, List<ConversationTurn> workingTurns) {
        String bestAnchor = normalizeTopicPhrase(currentQuestion);
        int bestScore = scoreTopicPhrase(bestAnchor);
        for (int i = workingTurns.size() - 1; i >= 0; i--) {
            String candidate = normalizeTopicPhrase(workingTurns.get(i).userQuestion());
            int score = scoreTopicPhrase(candidate);
            if (score > bestScore) {
                bestAnchor = candidate;
                bestScore = score;
            }
        }
        return bestAnchor;
    }

    private int scoreTopicPhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return 0;
        }
        int score = Math.min(phrase.length(), 60);
        if (containsFollowUpHint(phrase)) {
            score -= 12;
        }
        score += extractTopicTerms(phrase).size() * 8;
        return score;
    }

    private String buildTopicSummary(String topicAnchor, List<ConversationTurn> workingTurns, List<ConversationTurn> allTurns) {
        if (workingTurns.isEmpty() || allTurns.size() <= workingTurns.size()) {
            return "";
        }
        int firstWorkingSeq = workingTurns.get(0).messageSeq();
        List<String> summaries = new ArrayList<>();
        for (ConversationTurn turn : allTurns) {
            if (turn.messageSeq() >= firstWorkingSeq) {
                continue;
            }
            if (!topicAnchor.isBlank() && !shareTopicTerms(topicAnchor, turn.userQuestion())) {
                continue;
            }
            StringBuilder summary = new StringBuilder();
            summary.append("用户问“").append(trim(stripMarkdownForContext(turn.userQuestion()), 36)).append("”");
            if (!turn.assistantAnswerSummary().isBlank()) {
                summary.append("，回答聚焦“").append(trim(turn.assistantAnswerSummary(), 48)).append("”");
            }
            summaries.add(summary.toString());
            if (summaries.size() >= 2) {
                break;
            }
        }
        return String.join("；", summaries);
    }

    private boolean shareTopicTerms(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String normalizedLeft = normalizeTopicPhrase(left);
        String normalizedRight = normalizeTopicPhrase(right);
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) {
            return false;
        }
        if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
            return true;
        }
        Set<String> leftTerms = extractTopicTerms(normalizedLeft);
        Set<String> rightTerms = extractTopicTerms(normalizedRight);
        for (String term : leftTerms) {
            if (rightTerms.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> extractTopicTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        String normalized = text.toLowerCase()
                .replaceAll("[^\\p{IsHan}a-z0-9]+", " ")
                .trim();
        if (normalized.isBlank()) {
            return terms;
        }
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank() || isNoiseToken(token)) {
                continue;
            }
            if (token.length() >= 2 || token.codePointCount(0, token.length()) >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private boolean isNoiseToken(String token) {
        return switch (token) {
            case "继续", "展开", "补充", "这个", "这个呢", "这个问题", "这些", "那个", "它", "刚才", "上面",
                    "前者", "后者", "第二点", "第三点", "继续说", "继续讲", "再说", "接着", "what", "why",
                    "how", "please" -> true;
            default -> false;
        };
    }

    private String normalizeTopicPhrase(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String normalized = stripMarkdownForContext(question)
                .replaceAll("继续|展开|补充|接着|再说|刚才|上面|前者|后者|这个问题|这个呢|这个|这些|那个|它", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return trim(normalized, 80);
    }

    private boolean isFollowUpHeavyQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim().toLowerCase();
        return normalized.length() <= 12 || containsFollowUpHint(normalized);
    }

    private boolean containsFollowUpHint(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim().toLowerCase();
        return normalized.contains("继续")
                || normalized.contains("接着")
                || normalized.contains("展开")
                || normalized.contains("再说")
                || normalized.contains("补充")
                || normalized.contains("上一轮")
                || normalized.contains("上面")
                || normalized.contains("刚才")
                || normalized.contains("前者")
                || normalized.contains("后者")
                || normalized.contains("这个")
                || normalized.contains("这个呢")
                || normalized.contains("这个问题")
                || normalized.contains("这些")
                || normalized.contains("那个")
                || normalized.contains("它")
                || normalized.contains("第二点")
                || normalized.contains("第三点");
    }

    private boolean isContextDependentQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim().toLowerCase();
        if (normalized.length() <= 6) {
            return true;
        }
        return containsFollowUpHint(normalized);
    }

    private String stripMarkdownForContext(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content
                .replace("\r", "")
                .replaceAll("(?m)^##\\s+", "")
                .replaceAll("(?m)^-\\s+", "")
                .replace("`", "")
                .trim();
        return normalized.replace("\n", " ");
    }

    private record ConversationRef(String conversationId, String workspaceId) {
    }

    private record MessageRef(String messageId, String content) {
    }

    private record AnswerDraft(
            String answer,
            List<RetrievedChunk> evidence,
            List<String> existingCitationIds,
            MemoryControlPackResponse chatControlPack
    ) {
    }

    private record ConversationHistoryMessage(int messageSeq, String role, String content) {
    }

    private record ConversationTurn(String userQuestion, String assistantAnswerSummary, int messageSeq) {
    }

    private record ConversationContext(
            String currentQuestion,
            String retrievalQuestion,
            boolean contextApplied,
            int windowTurnCount,
            String topicAnchor,
            String topicSummary,
            List<ConversationTurn> workingTurns
    ) {
    }
}

