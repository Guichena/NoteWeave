package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.AnswerModeStrategy;
import com.noteweave.answer.strategy.AnswerPolicy;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.NoteEntryMetadata;
import com.noteweave.chat.NoteRetrievalService.NoteJournalHit;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import com.noteweave.chat.NoteRetrievalService.RelatedEntryPreview;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class NoteAnswerModeStrategy implements AnswerModeStrategy {

    public static final String PLAN_VERSION = "note-marginalia-funnel-v1";
    public static final String ATTRIBUTE_CURRENT_QUESTION = "current_question";
    public static final String ATTRIBUTE_REQUEST_CONTENT = "request_content";
    public static final String ATTRIBUTE_QUESTION_TYPE = "question_type";
    public static final String ATTRIBUTE_CONTEXT_APPLIED = "context_applied";
    public static final String ATTRIBUTE_WINDOW_TURN_COUNT = "window_turn_count";
    public static final String ATTRIBUTE_TOPIC_ANCHOR = "topic_anchor";
    public static final String ATTRIBUTE_TOPIC_SUMMARY = "topic_summary";
    public static final String ATTRIBUTE_HAS_CHAT_CONTROLS = "has_chat_controls";
    public static final String ATTRIBUTE_CHAT_CONTROL_SECTION = "chat_control_section";
    public static final String ATTRIBUTE_TEMPLATE_LABEL = "template_label";

    private final NoteRetrievalSnapshotCodec snapshotCodec;

    public NoteAnswerModeStrategy(NoteRetrievalSnapshotCodec snapshotCodec) {
        this.snapshotCodec = snapshotCodec;
    }

    @Override
    public AnswerMode supports() {
        return AnswerMode.NOTE;
    }

    @Override
    public RetrievalPlan plan(AnswerContext context) {
        return new RetrievalPlan(
                PLAN_VERSION,
                AnswerMode.NOTE,
                List.of(new RetrievalPlan.Step(
                        NoteEvidenceRetriever.CHANNEL,
                        8,
                        1.0,
                        Map.of("workspace_id", context.workspaceId(), "source_status", "READY")
                )),
                new RetrievalPlan.Budget(8, 24_000, 0, 0)
        );
    }

    @Override
    public PromptSpec compose(AnswerContext context, EvidenceBundle bundle) {
        String encodedSnapshot = bundle.metadata().get(NoteRetrievalSnapshotCodec.METADATA_KEY);
        if (encodedSnapshot == null || encodedSnapshot.isBlank()) {
            return insufficientPrompt();
        }
        NoteRetrievalSnapshot snapshot = snapshotCodec.decode(encodedSnapshot);
        Set<String> selectedEvidenceIds = bundle.evidence().stream()
                .map(EvidenceBundle.Evidence::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        snapshot = new NoteRetrievalSnapshot(
                snapshot.recallPlan(),
                snapshot.metadataEntries(),
                snapshot.windows().stream()
                        .filter(window -> selectedEvidenceIds.contains(windowEvidenceId(window)))
                        .toList()
        );
        List<CandidateSource> candidates = snapshot.recallPlan().candidateSources();
        List<ReadingWindow> windows = snapshot.windows();
        if (candidates.isEmpty() || windows.isEmpty()) {
            return insufficientPrompt();
        }
        String answer = render(context, snapshot);
        return new PromptSpec(
                "Answer from the selected Note reading windows and preserve their source citations.",
                answer,
                bundle.evidence().stream().map(EvidenceBundle.Evidence::evidenceId).toList(),
                PLAN_VERSION
        );
    }

    @Override
    public AnswerPolicy policy() {
        return new AnswerPolicy(1, false, true, 2_400);
    }

    private PromptSpec insufficientPrompt() {
        return new PromptSpec(
                "Answer only when Note retrieval has verified source windows.",
                "当前工作台资料不足，暂时无法通过 Marginalia 式资料级检索形成可靠回答。请先上传或保存更多资料。",
                List.of(),
                PLAN_VERSION,
                true
        );
    }

    private String windowEvidenceId(ReadingWindow window) {
        return "note-window:" + window.chunkId() + ":" + window.windowNo();
    }

    private String render(AnswerContext context, NoteRetrievalSnapshot snapshot) {
        var recallPlan = snapshot.recallPlan();
        List<CandidateSource> candidates = recallPlan.candidateSources();
        List<NoteJournalHit> journalHits = recallPlan.journalHits();
        List<CandidateSource> relationExpansionSources = recallPlan.relationExpansionSources();
        List<CandidateSource> verifySources = recallPlan.verifySources();
        List<NoteEntryMetadata> metadataEntries = snapshot.metadataEntries();
        List<ReadingWindow> windows = snapshot.windows();
        StringBuilder builder = new StringBuilder();
        builder.append("## 直接回答\n");
        builder.append(buildNoteSynthesis(context, windows, journalHits)).append("\n\n");
        builder.append("## 资料定位\n");
        builder.append("【定位说明】\n");
        builder.append("本轮按 Marginalia 式结构化检索漏斗先定位资料，再决定深读窗口；对外以正常聊天回答为主，资料依据通过可展开卡片展示。\n");
        builder.append("- 当前问题：").append(context.attributes().getOrDefault(
                ATTRIBUTE_REQUEST_CONTENT, context.query())).append("\n");
        if (contextApplied(context)) {
            builder.append("- 会话上下文：已纳入最近连续对话窗口，避免把这轮追问当成孤立查询\n");
            builder.append("- 连续对话窗口：最近 ")
                    .append(context.attributes().getOrDefault(ATTRIBUTE_WINDOW_TURN_COUNT, "0"))
                    .append(" 轮相关对话\n");
            String topicAnchor = context.attributes().getOrDefault(ATTRIBUTE_TOPIC_ANCHOR, "");
            if (!topicAnchor.isBlank()) {
                builder.append("- 主题锚点：").append(topicAnchor).append("\n");
            }
            String topicSummary = context.attributes().getOrDefault(ATTRIBUTE_TOPIC_SUMMARY, "");
            if (!topicSummary.isBlank()) {
                builder.append("- 前序主题摘要：").append(topicSummary).append("\n");
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
            builder.append("- 《").append(formatCandidateSourceTitle(candidate)).append("》：")
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
                builder.append("- 《").append(formatCandidateSourceTitle(candidate)).append("》：")
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
                .append(", semantic=").append(recallPlan.trace().semanticScoreSum())
                .append(", journal=").append(recallPlan.trace().noteScoreSum())
                .append(", relation=").append(recallPlan.trace().relationScoreSum())
                .append(", readiness=").append(recallPlan.trace().readinessScoreSum()).append("\n");
        for (CandidateSource candidate : verifySources) {
            builder.append("- verify《").append(formatCandidateSourceTitle(candidate)).append("》：")
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
            builder.append("- 《").append(formatNoteEntryTitle(entry)).append("》：")
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
        for (int index = 0; index < windows.size(); index++) {
            ReadingWindow window = windows.get(index);
            builder.append("- read ").append(index + 1)
                    .append("：").append(formatReadingWindowTitle(window))
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
        for (int index = 0; index < windows.size(); index++) {
            ReadingWindow window = windows.get(index);
            builder.append("- 摘录卡 ").append(index + 1).append("：")
                    .append(trim(window.content(), 220))
                    .append("（来源：").append(formatReadingWindowTitle(window));
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
        builder.append(context.attributes().getOrDefault(ATTRIBUTE_CHAT_CONTROL_SECTION, ""));
        return builder.toString();
    }

    private String buildNoteSynthesis(
            AnswerContext context,
            List<ReadingWindow> windows,
            List<NoteJournalHit> journalHits
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("基于当前候选资料与原文窗口，可以先给出一版可验证回答：");
        if (contextApplied(context)) {
            builder.append("本轮已结合最近连续对话窗口理解这次追问。");
            String topicAnchor = context.attributes().getOrDefault(ATTRIBUTE_TOPIC_ANCHOR, "");
            if (!topicAnchor.isBlank()) {
                builder.append("当前主题锚点是“").append(topicAnchor).append("”。");
            }
        }
        if (Boolean.parseBoolean(context.attributes().getOrDefault(ATTRIBUTE_HAS_CHAT_CONTROLS, "false"))) {
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
            builder.append("- 《").append(formatReadingWindowTitle(window)).append("》指出：")
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
        String questionType = context.attributes().getOrDefault(ATTRIBUTE_QUESTION_TYPE, "general");
        if ("comparison".equals(questionType) && count >= 2) {
            builder.append("这些资料更适合放在同一轮做对比阅读，再继续展开差异与共识。\n");
        } else if ("reasoning".equals(questionType)) {
            builder.append("这类问题更依赖原文上下文，因此后面的原文窗口与摘录证据会比普通问答更重要。\n");
        }
        return builder.toString().trim();
    }

    private String summarizeWindowLocators(List<NoteRetrievalService.WindowLocator> locators) {
        return locators.stream().limit(3).map(this::formatWindowLocator)
                .reduce((left, right) -> left + " | " + right).orElse("none");
    }

    private String formatWindowLocator(NoteRetrievalService.WindowLocator locator) {
        StringBuilder builder = new StringBuilder();
        builder.append("chunk=").append(locator.chunkNo()).append(",window=").append(locator.windowNo());
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
        return relatedEntries.stream().limit(3)
                .map(related -> "《" + formatRelatedEntryTitle(related) + "》"
                        + "(shared_tags=" + related.sharedTagCount()
                        + ",co_cited_notes=" + related.coCitedNoteCount()
                        + ",co_cited_turns=" + related.coCitedTurnCount()
                        + ",lexical_overlap=" + related.lexicalOverlapScore()
                        + ",graph_neighbor=" + related.graphNeighborhoodScore()
                        + ",reason=" + related.relationReason()
                        + ",score=" + related.score() + ")")
                .reduce((left, right) -> left + " | " + right).orElse("none");
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
            if (reason != null && !reason.isBlank()) {
                counts.merge(reason, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right).orElse("none");
    }

    private String summarizeVerifyAdmission(List<CandidateSource> verifySources) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CandidateSource candidate : verifySources) {
            String reason = candidate.verifyAdmissionReason();
            if (reason != null && !reason.isBlank()) {
                counts.merge(reason, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right).orElse("none");
    }

    private String formatCandidateSourceTitle(CandidateSource candidate) {
        return formatSourceDisplayTitle(candidate.title(), candidate.generatedBy(), candidate.generatedRefId());
    }

    private String formatReadingWindowTitle(ReadingWindow window) {
        return formatSourceDisplayTitle(window.title(), window.generatedBy(), window.generatedRefId());
    }

    private String formatNoteEntryTitle(NoteEntryMetadata entry) {
        return formatSourceDisplayTitle(entry.title(), entry.generatedBy(), entry.generatedRefId());
    }

    private String formatRelatedEntryTitle(RelatedEntryPreview entry) {
        return formatSourceDisplayTitle(entry.title(), entry.generatedBy(), entry.generatedRefId());
    }

    private String formatSourceDisplayTitle(String title, String generatedBy, String generatedRefId) {
        if ("research_agent".equals(generatedBy)) {
            return title + " · Research Report(" + (
                    generatedRefId == null || generatedRefId.isBlank() ? "unknown run" : generatedRefId) + ")";
        }
        return title;
    }

    private boolean contextApplied(AnswerContext context) {
        return Boolean.parseBoolean(context.attributes().getOrDefault(ATTRIBUTE_CONTEXT_APPLIED, "false"));
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max - 1) + "...";
    }
}
