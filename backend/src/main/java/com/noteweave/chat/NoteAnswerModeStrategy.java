package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.AnswerModeStrategy;
import com.noteweave.answer.strategy.AnswerPolicy;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
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
            return insufficientPrompt(planVersion(bundle));
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
        List<ReadingWindow> windows = snapshot.windows();
        if (windows.isEmpty()) {
            return insufficientPrompt(planVersion(bundle));
        }
        String answer = render(context, snapshot);
        return new PromptSpec(
                "Answer from the selected Note reading windows and preserve their source citations.",
                answer,
                bundle.evidence().stream().map(EvidenceBundle.Evidence::evidenceId).toList(),
                planVersion(bundle)
        );
    }

    @Override
    public AnswerPolicy policy() {
        return new AnswerPolicy(1, false, true, 2_400);
    }

    private PromptSpec insufficientPrompt(String planVersion) {
        return new PromptSpec(
                "Answer only when Note retrieval has verified source windows.",
                "当前工作台资料不足，暂时无法通过 Marginalia 式资料级检索形成可靠回答。请先上传或保存更多资料。",
                List.of(),
                planVersion,
                true
        );
    }

    /**
     * 版本取证据包里的检索计划版本，保证拒答分支与正常分支的版本一致；
     * 公共校验器按版本比对，不能靠各模式自报固定版本。
     */
    private static String planVersion(EvidenceBundle bundle) {
        String version = bundle == null ? null : bundle.retrievalPlanVersion();
        return version == null || version.isBlank() ? PLAN_VERSION : version;
    }

    private String windowEvidenceId(ReadingWindow window) {
        return "note-window:" + window.chunkId() + ":" + window.windowNo();
    }

    /**
     * Note 回答的生成提示：用户请求、写作要求和编号的最终窗口。
     *
     * <p>提示只使用本次运行最终入选的窗口。日志命中、关系图候选和未入选来源只做召回信号，
     * 它们的标题、摘要与失效状态不再进入提示：失效日志标题不能让模型当成事实来源，
     * 未选来源摘要也不能在正文里冒充已选证据。</p>
     */
    private String render(AnswerContext context, NoteRetrievalSnapshot snapshot) {
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
                .append("- 原文没有写明时间或先后顺序的安排，不要写成某个事件之前或之后发生，直接说明原文未说明时间；同一指标没有在两个时间点都给出时，不要推断它不变或已变化。\n")
                .append("- 资料定位和摘录证据两节保持原样，不要改写。\n")
                .append("- 不要描述你如何检索或组织这次回答。\n\n");
        if (contextApplied(context)) {
            ConversationWindow.append(builder, context);
        }
        builder.append("## 直接回答\n");
        builder.append("（在这里写回答）\n\n");
        builder.append("## 资料定位\n");
        Set<String> listed = new LinkedHashSet<>();
        // 只列本次最终窗口实际使用的来源，未入选来源的标题与摘要不进入提示
        for (ReadingWindow window : windows) {
            String title = formatReadingWindowTitle(window);
            if (listed.add(title)) {
                builder.append("- 《").append(title).append("》\n");
            }
        }
        builder.append("\n## 摘录证据\n");
        for (int index = 0; index < windows.size(); index++) {
            ReadingWindow window = windows.get(index);
            builder.append("[").append(index + 1).append("] 《").append(formatReadingWindowTitle(window));
            if (window.heading() != null && !window.heading().isBlank()) {
                builder.append(" / ").append(window.heading());
            }
            builder.append("》：").append(window.content() == null ? "" : window.content()).append("\n");
        }
        builder.append(context.attributes().getOrDefault(ATTRIBUTE_CHAT_CONTROL_SECTION, ""));
        return builder.toString();
    }

    private String formatReadingWindowTitle(ReadingWindow window) {
        return formatSourceDisplayTitle(window.title(), window.generatedBy(), window.generatedRefId());
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
}
