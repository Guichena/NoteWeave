package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.knowledge.KnowledgePageHit;
import org.junit.jupiter.api.Test;

/**
 * F6-04 W07：页面证据正文只有一处构造，Judge、生成、清单和成本计费共用同一份文本与包装开销。
 */
class WikiPageEvidenceTest {

    @Test
    void excerptCombinesSummaryAndContentInAStableOrder() {
        KnowledgePageHit page = page("Wiki Page", "页面摘要", "页面正文");

        String excerpt = WikiPageEvidence.excerpt(page);

        assertThat(excerpt).isEqualTo("页面摘要\n\n页面正文");
        assertThat(WikiPageEvidence.excerpt(page)).isEqualTo(excerpt);
        assertThat(WikiPageEvidence.cost(page))
                .isEqualTo(excerpt.length() + "Wiki Page".length() + 32);
    }

    @Test
    void contentThatAlreadyContainsSummaryIsNotDuplicated() {
        KnowledgePageHit page = page("Wiki Page", "摘要", "摘要以及更多正文");

        assertThat(WikiPageEvidence.excerpt(page)).isEqualTo("摘要以及更多正文");
    }

    @Test
    void longExcerptIsTruncatedWithAnExplicitMarker() {
        String content = "字".repeat(WikiPageEvidence.MAX_EXCERPT_CHARS + 100);
        KnowledgePageHit page = page("Long", "", content);

        String excerpt = WikiPageEvidence.excerpt(page);

        assertThat(excerpt).hasSize(WikiPageEvidence.MAX_EXCERPT_CHARS + 1).endsWith("…");
        assertThat(WikiPageEvidence.cost(page)).isEqualTo(excerpt.length() + "Long".length() + 32);
    }

    private KnowledgePageHit page(String title, String summary, String content) {
        return new KnowledgePageHit("item", "version", 1, title, content, summary, 8);
    }
}
