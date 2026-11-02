package com.noteweave.answer.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CitationMarkerNormalizerTest {

    private static final GenerationContract CONTRACT = new GenerationContract(
            GenerationContract.SCHEMA_VERSION, "QA", "qa-v1", "system", false,
            List.of("passage:one", "knowledge-version:two"),
            List.of(new CitationBinding(1, "passage:one", List.of("citation-1")),
                    new CitationBinding(2, "knowledge-version:two", List.of("citation-2"))),
            "bundle", Map.of(), List.of("路由表"));

    @Test
    void keepsMarkersThatAreBoundInThisRun() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(CONTRACT);

        assertThat(normalizer.accept("结论见[1]，另见[[路由表]]。"))
                .isEqualTo("结论见[1]，另见[[路由表]]。");
        assertThat(normalizer.outOfRangeNumbers()).isZero();
        assertThat(normalizer.unknownPages()).isZero();
    }

    @Test
    void rewritesOutOfRangeNumberAsPlainText() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(CONTRACT);

        assertThat(normalizer.accept("结论见[7]。")).isEqualTo("结论见［7］。");
        assertThat(normalizer.outOfRangeNumbers()).isEqualTo(1);
    }

    @Test
    void preservesPageNavigationWithoutTreatingItAsEvidence() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(CONTRACT);

        assertThat(normalizer.accept("参见[[未选页面]]。")).isEqualTo("参见[[未选页面]]。");
        assertThat(normalizer.unknownPages()).isZero();
    }

    @Test
    void buffersMarkersThatArriveInSeveralTokens() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(CONTRACT);

        // 标记之前的正文可以立即发送，只有标记本身要等闭合
        assertThat(normalizer.accept("结论见[")).isEqualTo("结论见");
        assertThat(normalizer.accept("1")).isEmpty();
        assertThat(normalizer.accept("]，下一句。")).isEqualTo("[1]，下一句。");
        assertThat(normalizer.outOfRangeNumbers()).isZero();
    }

    @Test
    void preservesPageNavigationAcrossSeveralTokens() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(CONTRACT);

        // 页面名是普通导航，不再为证据身份解析而暂存；只要求分片拼接正文一致。
        assertThat(normalizer.accept("参见[[路由") + normalizer.accept("表]]结束") + normalizer.flush())
                .isEqualTo("参见[[路由表]]结束");
    }

    @Test
    void flushEmitsUnterminatedMarkerAsPlainText() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(CONTRACT);

        assertThat(normalizer.accept("结论见[1")).isEqualTo("结论见");
        assertThat(normalizer.flush()).isEqualTo("[1");
        assertThat(normalizer.flush()).isEmpty();
    }

    @Test
    void veryLongTailIsNotBufferedForever() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(CONTRACT);
        String longTail = "[" + "x".repeat(CitationMarkerNormalizer.MAX_MARKER_LENGTH + 10);

        assertThat(normalizer.accept(longTail)).isEqualTo(longTail);
    }

    @Test
    void refusalContractRejectsEveryMarker() {
        GenerationContract refusal = new GenerationContract(
                GenerationContract.SCHEMA_VERSION, "QA", "qa-v1", "system", true,
                List.of(), List.of(), "bundle", Map.of(), List.of());
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(refusal);

        assertThat(normalizer.accept("[1] 与 [[路由表]]")).isEqualTo("［1］ 与 [[路由表]]");
        assertThat(normalizer.outOfRangeNumbers()).isEqualTo(1);
        assertThat(normalizer.unknownPages()).isZero();
    }

    @Test
    void withoutContractEveryMarkerIsPassedThrough() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(null);

        assertThat(normalizer.accept("[9] 与 [[任意页]]")).isEqualTo("[9] 与 [[任意页]]");
        assertThat(normalizer.outOfRangeNumbers()).isZero();
    }
    @Test
    void everyPartitionKeepsCodeLinksAndNavigation() {
        for (String text : List.of("[[missing]]", "array[7]", "`array[7]`", "``value`[7]``",
                "```java\narray[7]\n```", "~~~\narray[7]\n~~~", "\\[7]", "[7](https://example.org)",
                "[[" + "x".repeat(70) + "]]", "[7](relative)", "[7][ref]")) {
            for (int cut = 0; cut <= text.length(); cut++) {
                CitationMarkerNormalizer parser = new CitationMarkerNormalizer(CONTRACT);
                String actual = parser.accept(text.substring(0, cut)) + parser.accept(text.substring(cut)) + parser.flush();
                assertThat(actual).as("text=%s cut=%s", text, cut).isEqualTo(text);
                assertThat(parser.outOfRangeNumbers()).isZero();
            }
        }
    }

    @Test
    void everyPartitionAndRandomChunksHaveIdenticalInvalidCounts() {
        for (String text : List.of("依据[7]，接着[1][2][0]。", "[9999]", "[10000]", "[" + "9".repeat(90) + "]", "[7", "[")) {
            CitationMarkerNormalizer full = new CitationMarkerNormalizer(CONTRACT);
            String expected = full.accept(text) + full.flush();
            for (int cut = 0; cut <= text.length(); cut++) {
                CitationMarkerNormalizer parser = new CitationMarkerNormalizer(CONTRACT);
                assertThat(parser.accept(text.substring(0, cut)) + parser.accept(text.substring(cut)) + parser.flush()).isEqualTo(expected);
                assertThat(parser.outOfRangeNumbers()).isEqualTo(full.outOfRangeNumbers());
            }
            java.util.Random random = new java.util.Random(6);
            for (int trial = 0; trial < 30; trial++) {
                CitationMarkerNormalizer parser = new CitationMarkerNormalizer(CONTRACT);
                StringBuilder output = new StringBuilder();
                for (int start = 0; start < text.length();) {
                    int end = Math.min(text.length(), start + 1 + random.nextInt(5));
                    output.append(parser.accept(text.substring(start, end)));
                    start = end;
                }
                output.append(parser.flush());
                assertThat(output.toString()).isEqualTo(expected);
                assertThat(parser.outOfRangeNumbers()).isEqualTo(full.outOfRangeNumbers());
            }
        }
    }

    @Test
    void fenceClosingRequiresAnOtherwiseEmptyLineAndLinkDefinitionsAreLiteral() {
        for (String text : List.of("```\n``` not closed\narray [7]\n```", "[7]: https://example.org")) {
            for (int cut = 0; cut <= text.length(); cut++) {
                CitationMarkerNormalizer parser = new CitationMarkerNormalizer(CONTRACT);
                assertThat(parser.accept(text.substring(0, cut)) + parser.accept(text.substring(cut)) + parser.flush())
                        .as("text=%s cut=%s", text, cut).isEqualTo(text);
                assertThat(parser.outOfRangeNumbers()).isZero();
            }
        }
    }

}
