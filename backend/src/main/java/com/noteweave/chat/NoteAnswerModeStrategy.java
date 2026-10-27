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
import com.noteweave.retrieval.NoteRetrievalStrategyProfile;
import com.noteweave.retrieval.QueryRewrite;
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
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("workspace_id", context.workspaceId());
        filters.put("source_status", "READY");
        QueryRewrite.copyInto(filters, context.attributes());
        NoteRetrievalStrategyProfile.copyInto(filters);
        return new RetrievalPlan(
                PLAN_VERSION,
                AnswerMode.NOTE,
                List.of(new RetrievalPlan.Step(
                        NoteEvidenceRetriever.CHANNEL,
                        8,
                        1.0,
                        filters
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
        // 窗口按证据顺序排列：引用卡片按证据顺序保存，回答里的 [n] 才能对应第 n 张卡片
        java.util.Map<String, ReadingWindow> windowsById = new java.util.LinkedHashMap<>();
        snapshot.windows().stream()
                .filter(window -> selectedEvidenceIds.contains(windowEvidenceId(window)))
                .forEach(window -> windowsById.putIfAbsent(windowEvidenceId(window), window));
        snapshot = new NoteRetrievalSnapshot(
                snapshot.recallPlan(),
                snapshot.metadataEntries(),
                bundle.evidence().stream().map(EvidenceBundle.Evidence::evidenceId)
                        .map(windowsById::get).filter(java.util.Objects::nonNull).toList()
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

    /**
     * Note 回答的生成提示：用户请求、写作要求和编号的原文窗口。
     *
     * <p>原来的草稿把候选打分、覆盖率、窗口定位等检索细节整段交给模型润色，原文窗口只给 120 到 220 个字，
     * 模型写不出真正的笔记。现在由模型在直接回答一节写出完整内容，资料定位只列资料名，
     * 摘录证据给出足够长的原文；检索细节仍记录在回答的检索轨迹里，不再进入提示。</p>
     */
    private String render(AnswerContext context, NoteRetrievalSnapshot snapshot) {
        var recallPlan = snapshot.recallPlan();
        List<ReadingWindow> windows = snapshot.windows();
        String request = context.attributes().getOrDefault(ATTRIBUTE_REQUEST_CONTENT, context.query());
        StringBuilder builder = new StringBuilder();
        builder.append("用户请求：").append(request).append("\n");
        if (!context.query().equals(request)) {
            builder.append("结合上文理解后的完整请求：").append(context.query()).append("\n");
        }
        builder.append("\n写作要求：\n")
                .append("- 在直接回答一节写出完整的回答。用户要整理笔记、总结或提纲时，写成结构清晰的笔记：用三级标题（###）分主题，")
                .append("每个主题下列出要点和必要的解释；用户提问时先用一两句话直接回答，再展开。\n")
                .append("- 只使用摘录证据一节中的原文，不补写原文里没有的事实；与请求无关的内容不要写进来。\n")
                .append("- 依据原文的句子末尾用方括号标注摘录编号，例如 [2] 或 [1][3]，不要编造编号。\n")
                .append("- 资料定位和摘录证据两节保持原样，不要改写。\n")
                .append("- 不要描述你如何检索或组织这次回答。\n\n");
        if (contextApplied(context)) {
            ConversationWindow.append(builder, context);
        }
        builder.append("## 直接回答\n");
        builder.append("（在这里写回答）\n\n");
        builder.append("## 资料定位\n");
        Set<String> listed = new LinkedHashSet<>();
        for (CandidateSource candidate : recallPlan.candidateSources()) {
            if (!listed.add(formatCandidateSourceTitle(candidate))) continue;
            builder.append("- 《").append(formatCandidateSourceTitle(candidate)).append("》");
            if (!candidate.summary().isBlank()) builder.append("：").append(trim(candidate.summary(), 120));
            builder.append("\n");
        }
        for (NoteJournalHit hit : recallPlan.journalHits()) {
            builder.append("- 历史笔记《").append(hit.title()).append("》");
            String freshnessStatus = hit.freshnessStatus();
            if ("source-deleted".equals(freshnessStatus)) {
                // 来源已删除时笔记仍可作背景参考，只是不能再引用原文
                builder.append("（引用的来源已删除，笔记内容仅供参考，原文不可引用）");
            } else if ("source-unavailable".equals(freshnessStatus)) {
                // 解析中或解析失败属于暂不可用，与已删除区分开
                builder.append("（引用的来源暂不可用，笔记内容仅供参考）");
            } else if ("stale-source-updated".equals(freshnessStatus)) {
                builder.append("（来源已更新，以当前原文为准）");
            } else if ("source-compliance-revoked".equals(freshnessStatus)) {
                builder.append("（引用的来源因合规要求已撤销，不可引用）");
            }
            builder.append("\n");
        }
        builder.append("\n## 摘录证据\n");
        for (int index = 0; index < windows.size(); index++) {
            ReadingWindow window = windows.get(index);
            builder.append("[").append(index + 1).append("] 《").append(formatReadingWindowTitle(window));
            if (window.heading() != null && !window.heading().isBlank()) {
                builder.append(" / ").append(window.heading());
            }
            builder.append("》：").append(trim(window.content(), 1_200)).append("\n");
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
        boolean hasStaleJournal = journalHits.stream()
                .anyMatch(hit -> "stale-source-updated".equals(hit.freshnessStatus()));
        boolean hasUnavailableJournal = journalHits.stream()
                .anyMatch(hit -> "source-unavailable".equals(hit.freshnessStatus()));
        boolean hasDeletedJournal = journalHits.stream()
                .anyMatch(hit -> "source-deleted".equals(hit.freshnessStatus()));
        if (hasDeletedJournal) {
            builder.append("历史整理里有笔记引用的来源已删除，这些笔记只作背景参考，不要引用原文。");
        }
        if (hasUnavailableJournal) {
            builder.append("历史整理里有笔记引用的来源暂不可用，这些笔记只作背景参考。");
        }
        if (hasStaleJournal) {
            builder.append("历史整理里存在来源已更新的笔记，本轮已经优先按当前可读原文窗口重新核对。");
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
