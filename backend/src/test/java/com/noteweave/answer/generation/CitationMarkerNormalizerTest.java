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
    void rewritesUnselectedPageNameAsPlainText() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(CONTRACT);

        assertThat(normalizer.accept("参见[[未选页面]]。")).isEqualTo("参见［［未选页面］］。");
        assertThat(normalizer.unknownPages()).isEqualTo(1);
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
    void buffersPageMarkersThatArriveInSeveralTokens() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(CONTRACT);

        assertThat(normalizer.accept("参见[[路由")).isEqualTo("参见");
        assertThat(normalizer.accept("表]]结束")).isEqualTo("[[路由表]]结束");
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

        assertThat(normalizer.accept("[1] 与 [[路由表]]")).isEqualTo("［1］ 与 ［［路由表］］");
        assertThat(normalizer.outOfRangeNumbers()).isEqualTo(1);
        assertThat(normalizer.unknownPages()).isEqualTo(1);
    }

    @Test
    void withoutContractEveryMarkerIsPassedThrough() {
        CitationMarkerNormalizer normalizer = new CitationMarkerNormalizer(null);

        assertThat(normalizer.accept("[9] 与 [[任意页]]")).isEqualTo("[9] 与 [[任意页]]");
        assertThat(normalizer.outOfRangeNumbers()).isZero();
    }
}
