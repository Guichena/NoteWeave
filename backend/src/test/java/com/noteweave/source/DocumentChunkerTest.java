package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker(120, 30);

    @Test
    void markdownChunksCarryTheirHeadingPathAndNeverSplitASectionMidSentence() {
        String markdown = """
                # 缓存一致性方案

                ## 1 推荐方案

                写请求先更新数据库，成功后删除缓存。读请求在缓存未命中时回源数据库并回填缓存。

                ## 2 补偿手段

                删除失败时把删除操作写入消息队列重试。同时订阅数据库 binlog，由独立消费者再删一次缓存兜底。

                ### 2.1 延迟双删

                在更新数据库前后各删一次缓存，第二次删除延迟 500 毫秒到 1 秒。
                """;

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(markdown, "text/markdown");

        assertThat(chunks).extracting(DocumentChunker.DocumentChunk::headingPath)
                .contains("缓存一致性方案 > 2 补偿手段");
        DocumentChunker.DocumentChunk compensation = chunks.stream()
                .filter(chunk -> chunk.content().contains("binlog")).findFirst().orElseThrow();
        assertThat(compensation.headingPath()).startsWith("缓存一致性方案 > 2 补偿手段");
        assertThat(compensation.location(1)).startsWith("chunk:1 · 缓存一致性方案 > 2 补偿手段");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content()).doesNotEndWith("#"));
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.pageStart()).isNull());
    }

    @Test
    void headingsAreCarriedForwardInsteadOfEndingAChunk() {
        String paragraph = "检索链路由两路召回组成，BM25 擅长精确匹配术语和编号，向量召回擅长语义相近的问题。".repeat(2);
        String markdown = "## 第一节\n\n" + paragraph + "\n\n## 第二节\n\n" + paragraph;

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(markdown, "text/markdown");

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().strip()).doesNotEndWith("## 第二节"));
        assertThat(chunks.get(chunks.size() - 1).content()).startsWith("## 第二节");
        assertThat(chunks.get(chunks.size() - 1).headingPath()).isEqualTo("第二节");
    }

    @Test
    void tinySectionsMergeWithTheFollowingContent() {
        String markdown = "# 总览\n\n一句话介绍。\n\n## 细节\n\n正文第二部分。";

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(markdown, "text/markdown");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isEqualTo("总览");
        assertThat(chunks.get(0).content()).contains("一句话介绍").contains("正文第二部分");
    }

    @Test
    void longParagraphsSplitOnSentencesWithSentenceLevelOverlap() {
        StringBuilder text = new StringBuilder();
        for (int index = 1; index <= 12; index++) {
            text.append("第").append(index).append("句说明混合检索中的一个细节，并给出对应的工程取舍。");
        }

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(text.toString(), "text/plain");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.tokens()).isLessThanOrEqualTo(120);
            assertThat(chunk.content()).endsWith("。");
            assertThat(chunk.content()).startsWith("第");
        });
        String lastSentenceOfFirst = chunks.get(0).content()
                .substring(chunks.get(0).content().lastIndexOf("第"));
        assertThat(chunks.get(1).content()).startsWith(lastSentenceOfFirst);
    }

    @Test
    void pdfPagesAndNumberedHeadingsBecomePageRangesAndPaths() {
        String pdfText = String.join("\f",
                "第一章 检索链路\n混合检索由 BM25 与向量召回组成。",
                "1.1 融合方式\nRRF 按名次融合两路结果，k 通常取 60。",
                "1.2 重排\n融合后的候选交给重排模型精排。");

        List<DocumentChunker.DocumentChunk> chunks = new DocumentChunker(600, 80).chunk(pdfText, "application/pdf");

        assertThat(chunks).hasSize(1);
        DocumentChunker.DocumentChunk chunk = chunks.get(0);
        assertThat(chunk.pageStart()).isEqualTo(1);
        assertThat(chunk.pageEnd()).isEqualTo(3);
        assertThat(chunk.headingPath()).isEqualTo("第一章 检索链路");
        assertThat(chunk.location(0)).isEqualTo("chunk:0 · 第 1-3 页 · 第一章 检索链路");
    }

    @Test
    void chunksSpanningSiblingSectionsListTheSectionNames() {
        String text = """
                缓存一致性方案

                一、推荐方案
                写请求先更新数据库，成功后删除缓存。

                二、补偿手段
                删除失败时写入消息队列重试。
                """;

        List<DocumentChunker.DocumentChunk> chunks = new DocumentChunker(600, 80).chunk(text, "text/plain");

        assertThat(chunks).singleElement()
                .satisfies(chunk -> assertThat(chunk.headingPath()).isEqualTo("一、推荐方案 / 二、补偿手段"));
    }

    @Test
    void consecutiveNumberedShortLinesAreAListNotHeadings() {
        String text = String.join("\n",
                "检索步骤",
                "1. 问题向量化",
                "2. 两路召回",
                "3. 融合排序",
                "以上步骤依次执行。");

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(text, "text/plain");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).headingPath()).isNotEqualTo("1. 问题向量化").isNotEqualTo("2. 两路召回");
    }

    @Test
    void transcriptsChunkOnLinesAndKeepTimeRanges() {
        StringBuilder transcript = new StringBuilder();
        for (int minute = 0; minute < 10; minute++) {
            transcript.append(String.format("[%02d:00] 第 %d 分钟讨论产物生成模块的纪要结构与行动项安排。%n", minute, minute));
        }

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(transcript.toString(), "audio/wav");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).timeStart()).isEqualTo("00:00");
        assertThat(chunks.get(0).location(0)).contains(" · 00:00-");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content()).startsWith("["));
    }

    @Test
    void csvChunksRepeatTheHeaderRow() {
        StringBuilder csv = new StringBuilder("engine,hybrid_search,chinese_tokenizer,notes\n");
        for (int row = 0; row < 30; row++) {
            csv.append("engine-").append(row).append(",yes,ik plugin,row ").append(row).append(" describes deployment\n");
        }

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(csv.toString(), "text/csv");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content()).startsWith("engine,hybrid_search,chinese_tokenizer,notes\n"));
    }

    @Test
    void codeBlocksStayWholeWhenTheyFit() {
        String markdown = "## 示例\n\n说明文字。\n\n```java\nint a = 1;\n\nint b = 2;\n```\n\n结尾。";

        List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(markdown, "text/markdown");

        assertThat(chunks.get(0).content()).contains("```java\nint a = 1;\n\nint b = 2;\n```");
    }

    @Test
    void readWindowsBreakOnSentencesAndOverlapByOneSentence() {
        StringBuilder text = new StringBuilder();
        for (int index = 1; index <= 30; index++) {
            text.append("窗口第").append(index).append("句描述连续阅读时需要保留的上下文内容。");
        }

        List<String> windows = new DocumentChunker(600, 80).windows(text.toString());

        assertThat(windows).hasSizeGreaterThan(1);
        assertThat(windows).allSatisfy(window -> {
            assertThat(window).endsWith("。");
            assertThat(DocumentChunker.tokens(window)).isLessThanOrEqualTo(DocumentChunker.WINDOW_MAX_TOKENS);
        });
        String lastOfFirst = windows.get(0).substring(windows.get(0).lastIndexOf("窗口第"));
        assertThat(windows.get(1)).startsWith(lastOfFirst);
    }

    @Test
    void blankInputYieldsOneEmptyChunk() {
        assertThat(chunker.chunk("  \n\f ", "text/plain"))
                .singleElement()
                .satisfies(chunk -> assertThat(chunk.content()).isEmpty());
    }
}
