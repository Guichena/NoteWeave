package com.noteweave.chat;

import com.noteweave.knowledge.KnowledgePageHit;

/**
 * Wiki 页面证据正文的唯一构造函数（F6-04）。
 *
 * <p>Judge、生成提示、引用清单和成本计费都读这一份正文：摘要和页面正文按固定顺序拼接，
 * 超过上限统一截断并标记省略；预算成本同时计入标题与编号包装开销，
 * 展示层不再另取一份正文或按不同长度二次裁剪。</p>
 */
public final class WikiPageEvidence {

    /** 单页证据正文的字符上限；与证据预算的口径一致，按字符而不是字节描述展示文本。 */
    public static final int MAX_EXCERPT_CHARS = 4_000;

    /** 标题、编号、书名号、标签等包装开销的固定估计。 */
    private static final int WRAPPER_OVERHEAD = 32;

    private WikiPageEvidence() {
    }

    public static String excerpt(KnowledgePageHit page) {
        if (page == null) {
            return "";
        }
        String summary = page.summary() == null ? "" : page.summary().strip();
        String content = page.content() == null ? "" : page.content().strip();
        String body;
        if (content.isBlank()) {
            body = summary;
        } else if (summary.isBlank() || content.contains(summary)) {
            body = content;
        } else {
            body = summary + "\n\n" + content;
        }
        if (body.length() <= MAX_EXCERPT_CHARS) {
            return body;
        }
        return body.substring(0, MAX_EXCERPT_CHARS) + "…";
    }

    /** 证据成本 = 真实正文 + 标题与编号包装开销；预算器按同一口径计费。 */
    public static int cost(KnowledgePageHit page) {
        if (page == null) {
            return 0;
        }
        String title = page.title() == null ? "" : page.title();
        return excerpt(page).length() + title.length() + WRAPPER_OVERHEAD;
    }
}
