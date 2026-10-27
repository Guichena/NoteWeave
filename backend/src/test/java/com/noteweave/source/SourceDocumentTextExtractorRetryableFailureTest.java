package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * PARSE-01：解析服务暂时不可用时，只有能在本地兜底的格式才降级；
 * Office 这类没有兜底的格式必须保留可重试标记，不能变成终态的「需要解析服务」。
 */
class SourceDocumentTextExtractorRetryableFailureTest {

    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final SourceDocumentParserPort rateLimited = (request, original) -> {
        throw SourceParserUnavailableException.rateLimited(Duration.ofSeconds(5));
    };

    private final SourceDocumentTextExtractor extractor = new SourceDocumentTextExtractor(
            rateLimited,
            new SourceDocumentParserProperties(true, "http://127.0.0.1:1", 1, 1, 1000, ""));

    @Test
    void officeWithoutLocalFallbackKeepsTheRetryableFailure() {
        assertThatThrownBy(() -> extractor.extract("讲义.docx", DOCX, new byte[] {1, 2, 3}))
                .isInstanceOf(SourceParserUnavailableException.class)
                .satisfies(thrown -> {
                    SourceParserUnavailableException failure = (SourceParserUnavailableException) thrown;
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure.code()).isEqualTo("SOURCE_PARSER_RATE_LIMITED");
                });
    }

    @Test
    void textWithLocalFallbackStillDegradesWhenTheParserIsRateLimited() {
        SourceDocumentTextExtractor.ExtractedDocument document =
                extractor.extract("笔记.md", "text/markdown", "# 标题\n\n正文".getBytes(StandardCharsets.UTF_8));

        assertThat(document.text()).contains("标题").contains("正文");
        assertThat(document.degradation()).isEqualTo("SOURCE_PARSER_RATE_LIMITED");
    }

    @Test
    void terminalParserFailureStillDegradesToTheLocalError() {
        SourceDocumentTextExtractor rejected = new SourceDocumentTextExtractor(
                (request, original) -> {
                    throw SourceParserUnavailableException.rejected(415);
                },
                new SourceDocumentParserProperties(true, "http://127.0.0.1:1", 1, 1, 1000, ""));

        assertThatThrownBy(() -> rejected.extract("讲义.docx", DOCX, new byte[] {1, 2, 3}))
                .isInstanceOf(com.noteweave.common.BusinessException.class)
                .hasMessageContaining("SOURCE_PARSER_REJECTED");
    }
}
