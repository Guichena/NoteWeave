package com.noteweave.context;

import com.noteweave.conversation.TokenCounter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性渲染器：把视图渲染成提示词文本。
 *
 * <p>渲染器不截字、不挑选、不改写正文。超过预算的选择必须在视图构造阶段完成并记录原因，
 * 渲染阶段偷偷截断会让快照、计数与模型实际看到的内容对不上。同一份视图重复渲染得到
 * 完全相同的文本、摘要与 token 估算。</p>
 *
 * <p>每条材料都带形态标记：原文、消息摘要、话题摘要、句柄、占位互不混淆，
 * 摘要不会被渲染成已经恢复的原文。</p>
 */
public final class ContextRenderer {

    /** 渲染实现版本，改动格式必须递增。 */
    public static final String RENDERER_VERSION = "context-renderer-v1";

    /** 分段标题前缀。 */
    public static final String SECTION_PREFIX = "## ";

    public RenderedContext render(ContextView view) {
        if (view == null) {
            throw new IllegalArgumentException("context view is required");
        }
        List<String> sections = new ArrayList<>();
        Map<String, Integer> sectionTokens = new LinkedHashMap<>();
        for (ContextLayer layer : view.contract().ordering()) {
            String section = renderSection(view, layer);
            if (section.isEmpty()) {
                continue;
            }
            sections.add(section);
            int tokens = 0;
            for (ContextView.Item item : view.itemsOf(layer)) {
                tokens += item.tokens();
            }
            sectionTokens.put(layer.wire(), tokens);
        }
        String text = sections.isEmpty() ? "" : String.join("\n\n", sections) + "\n";
        return new RenderedContext(RenderedContext.SCHEMA_VERSION, RENDERER_VERSION, text,
                sha256(text), estimate(text), estimatorVersion(), sectionTokens);
    }

    /** 渲染单层，没有条目时返回空串。 */
    public String renderSection(ContextView view, ContextLayer layer) {
        if (view == null || layer == null) {
            throw new IllegalArgumentException("context view and layer are required");
        }
        view.contract().requireAllows(layer);
        List<ContextView.Item> items = view.itemsOf(layer);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(SECTION_PREFIX).append(layer.label());
        for (ContextView.Item item : items) {
            builder.append('\n').append("- ").append(line(item));
        }
        return builder.toString();
    }

    /** 单条材料的渲染行，形态标记与序号范围都在这里固定。 */
    public String line(ContextView.Item item) {
        String prefix = switch (item.form()) {
            case RAW -> "原文" + range(item);
            case MESSAGE_SUMMARY -> "消息摘要" + range(item);
            case TOPIC_SUMMARY -> "话题摘要" + range(item);
            case HANDLE -> "可回忆句柄" + range(item);
            case PLACEHOLDER -> "占位" + range(item);
            case STRUCTURED -> "";
        };
        return prefix.isEmpty() ? item.text() : prefix + "：" + item.text();
    }

    /** 与视图一致的估算口径，供调用方核对预算。 */
    public static int estimate(String text) {
        return TokenCounter.estimate(text, null).count();
    }

    /** 当前估算方式版本，写进契约与渲染结果。 */
    public static String estimatorVersion() {
        var estimate = TokenCounter.estimate("sample", null);
        return estimate.method() + "/" + estimate.tokenizerVersion();
    }

    private static String range(ContextView.Item item) {
        if (item.startSeq() == 0 || item.endSeq() == 0) {
            return "";
        }
        if (item.startSeq() == item.endSeq()) {
            return "(第" + item.startSeq() + "条)";
        }
        return "(第" + item.startSeq() + "-" + item.endSeq() + "条)";
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
