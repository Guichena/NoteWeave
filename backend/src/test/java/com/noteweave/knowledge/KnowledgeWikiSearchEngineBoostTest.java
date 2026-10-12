package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeWikiSearchEngineBoostTest {
    private final WikiSearchRow row = new WikiSearchRow(
            "item",
            "缓存一致性",
            "TOPIC",
            "version",
            1,
            "缓存一致性依赖版本号",
            "缓存一致性摘要",
            Instant.parse("2026-01-01T00:00:00Z"),
            0,
            0,
            0,
            0);

    @Test
    void mustTitleAndBodyTermsIncreaseBm25fWithoutChangingCoverage() {
        KnowledgeWikiSearchEngine.Query query = KnowledgeWikiSearchEngine.Query.parse("缓存一致性 版本号");
        double plain = score(query, WikiLexicalBoost.none());

        assertThat(score(query, new WikiLexicalBoost(List.of("缓存一致性"), List.of(), List.of())))
                .isGreaterThan(plain);
        assertThat(score(query, new WikiLexicalBoost(List.of(), List.of("缓存一致性"), List.of())))
                .isGreaterThan(plain);
        assertThat(score(query, new WikiLexicalBoost(List.of(), List.of(), List.of("版本号"))))
                .isGreaterThan(plain);

        KnowledgeWikiSearchEngine.Query unrelated = KnowledgeWikiSearchEngine.Query.parse("完全无关")
                .withExtraTerms(new WikiLexicalBoost(List.of("缓存一致性"), List.of(), List.of()));
        assertThat(KnowledgeWikiSearchEngine.bm25f(List.of(row), unrelated)).doesNotContainKey("item");
    }

    @Test
    void splitAnchorLineStillCoversThePageWhenFollowUpTermsDiluteOneGroup() {
        WikiSearchRow alpha = new WikiSearchRow(
                "alpha",
                "Alpha策略",
                "TOPIC",
                "version",
                1,
                """
                # Alpha策略

                Alpha策略页面说明研究工作台如何围绕单一主题组织资料与回答。

                ## 关联页面

                - 具体执行见 [[Alpha执行]]
                """,
                "Alpha策略页面说明研究工作台如何围绕单一主题组织资料与回答。",
                Instant.parse("2026-01-01T00:00:00Z"),
                1,
                0,
                0,
                0);
        assertThat(KnowledgeWikiSearchEngine.bm25f(
                List.of(alpha),
                KnowledgeWikiSearchEngine.Query.parse("第二点为什么成立 请介绍 Alpha策略")))
                .doesNotContainKey("alpha");
        assertThat(KnowledgeWikiSearchEngine.bm25f(
                List.of(alpha),
                KnowledgeWikiSearchEngine.Query.parse("当前问题：第二点为什么成立\n主题锚点：请介绍 Alpha策略")))
                .containsKey("alpha");
    }

    private double score(KnowledgeWikiSearchEngine.Query query, WikiLexicalBoost boost) {
        return KnowledgeWikiSearchEngine.bm25f(List.of(row), query, boost).get("item");
    }
}
