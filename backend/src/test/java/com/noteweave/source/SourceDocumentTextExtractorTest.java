package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceDocumentTextExtractorTest {

    private final SourceDocumentTextExtractor extractor = new SourceDocumentTextExtractor();
    @TempDir Path temporary;

    @Test
    void shouldExtractTextAndPageCountFromPdf() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(72, 720);
                stream.showText("Evidence notebook");
                stream.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }

        SourceDocumentTextExtractor.ExtractedDocument extracted = extractor.extract(
                "evidence.pdf", "application/pdf", pdf);

        assertThat(extracted.text()).contains("Evidence notebook");
        assertThat(extracted.pageCount()).isEqualTo(1);
        assertThat(extracted.parser()).isEqualTo("PDFBOX");
        assertThat(extracted.degradation()).isEqualTo("SOURCE_PARSER_DISABLED");
    }

    @Test
    void scannedPdfWithoutTextShouldFailExplicitly() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }

        assertThatThrownBy(() -> extractor.extract("scan.pdf", "application/pdf", pdf))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OCR");
    }

    @Test
    void gbkTextFilesShouldDecodeWithoutMojibake() {
        byte[] gbk = "缓存一致性：先更新数据库，再删除缓存。".getBytes(java.nio.charset.Charset.forName("GBK"));
        byte[] utf8WithBom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "带 BOM 的 UTF-8".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(extractor.extract("gbk.txt", "text/plain", gbk).text()).isEqualTo("缓存一致性：先更新数据库，再删除缓存。");
        assertThat(extractor.extract("bom.md", "text/markdown", utf8WithBom).text()).isEqualTo("带 BOM 的 UTF-8");
        assertThat(extractor.extract("utf8.md", "text/markdown",
                "普通 UTF-8".getBytes(java.nio.charset.StandardCharsets.UTF_8)).text()).isEqualTo("普通 UTF-8");
    }

    @Test
    void multiPagePdfShouldKeepPageBreaksAndDropRepeatedHeadersAndPageNumbers() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int number = 1; number <= 3; number++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 12);
                    stream.newLineAtOffset(72, 720);
                    stream.showText("Retrieval design handbook");
                    stream.newLineAtOffset(0, -40);
                    stream.showText("Body of page " + number + " explains the retrieval chain.");
                    stream.newLineAtOffset(0, -600);
                    stream.showText("Page " + number);
                    stream.endText();
                }
            }
            document.save(output);
            pdf = output.toByteArray();
        }

        SourceDocumentTextExtractor.ExtractedDocument extracted = extractor.extract("handbook.pdf", "application/pdf", pdf);

        String[] pages = extracted.text().split("\f", -1);
        assertThat(extracted.pageCount()).isEqualTo(3);
        assertThat(pages).hasSize(3);
        assertThat(pages[1]).contains("Body of page 2");
        assertThat(extracted.text()).doesNotContain("Retrieval design handbook").doesNotContain("Page 1");
    }

    @Test
    void pdfNormalizationShouldMergeLinesBrokenByLayout() {
        String page = String.join("\n",
                "检索链路由两路召回组成：BM25 关键词召回擅长精确匹配术语和编号，",
                "向量召回擅长语义相近但措辞不同的问题。",
                "2.1 融合方式",
                "Reciprocal rank fusion combines ranked lists without score normali-",
                "zation, which keeps both channels comparable.");

        String normalized = SourceDocumentTextExtractor.normalizePdfPages(java.util.List.of(page));

        assertThat(normalized.split("\n")).containsExactly(
                "检索链路由两路召回组成：BM25 关键词召回擅长精确匹配术语和编号，向量召回擅长语义相近但措辞不同的问题。",
                "2.1 融合方式",
                "Reciprocal rank fusion combines ranked lists without score normalization, which keeps both channels comparable.");
    }

    @Test
    void figurePagesWithOnlyShortLinesAreNotMerged() {
        String page = String.join("\n", "10 画面 06 ·05:00", "转发给你的技术团队", "关注我", "图 6: 视频画面·05:00");

        assertThat(SourceDocumentTextExtractor.normalizePdfPages(java.util.List.of(page)).split("\n"))
                .containsExactly("10 画面 06 ·05:00", "转发给你的技术团队", "关注我", "图 6: 视频画面·05:00");
    }

    @Test
    void leadingEmptyPdfPageShouldNotShiftLaterPageNumbers() {
        String normalized = SourceDocumentTextExtractor.normalizePdfPages(java.util.List.of("", "第二页正文。", "第三页正文。"));

        String[] pages = normalized.split("\f", -1);
        assertThat(pages).hasSize(3);
        assertThat(pages[1]).isEqualTo("第二页正文。");
    }

    @Test
    void preferredParserKeepsElementsAndItsDiagnostics() throws Exception {
        Path file = temporary.resolve("report.docx");
        Files.write(file, new byte[] {80, 75, 3, 4});
        DocumentElement table = new DocumentElement(DocumentElement.ElementType.TABLE, "| 项目 | 值 |\n| A | 20 |", 1,
                new DocumentElement.BoundingBox(10, 20, 100, 80, DocumentElement.CoordOrigin.TOPLEFT), null);
        SourceDocumentParserPort port = (request, original) -> new SourceDocumentParserPort.ParsedDocument(1,
                request.workspaceId(), request.sourceId(), request.snapshotId(), request.sha256(), request.mimeType(),
                table.text(), 1, List.of(table), "DOCLING", true, List.of("MAX_CHARS_REACHED"));
        var preferred = new SourceDocumentTextExtractor(port, enabled(1000));

        var document = preferred.extract(request(file, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1000), file);

        assertThat(document.parser()).isEqualTo("DOCLING");
        assertThat(document.elements()).containsExactly(table);
        assertThat(document.truncated()).isTrue();
        assertThat(document.warnings()).containsExactly("MAX_CHARS_REACHED");
        assertThat(document.degradation()).isNull();
    }

    @Test
    void unavailableParserFallsBackToRealTextAndRecordsTheReason() throws Exception {
        Path file = temporary.resolve("notes.txt");
        Files.writeString(file, "实际原文，不能由解析替身生成。", StandardCharsets.UTF_8);
        var preferred = new SourceDocumentTextExtractor((request, original) -> {
            throw new BusinessException("SOURCE_PARSER_UNAVAILABLE", "服务未启动");
        }, enabled(1000));

        var document = preferred.extract(request(file, "text/plain", 1000), file);

        assertThat(document.text()).isEqualTo("实际原文，不能由解析替身生成。");
        assertThat(document.parser()).isEqualTo("PLAIN_TEXT");
        assertThat(document.degradation()).isEqualTo("SOURCE_PARSER_UNAVAILABLE");
    }

    @Test
    void invalidIdentityFallsBackAndOfficeWithoutAParserFailsExplicitly() throws Exception {
        Path file = temporary.resolve("notes.txt");
        Files.writeString(file, "当前资料", StandardCharsets.UTF_8);
        var preferred = new SourceDocumentTextExtractor((request, original) -> new SourceDocumentParserPort.ParsedDocument(
                1, "其他工作区", request.sourceId(), request.snapshotId(), request.sha256(), request.mimeType(),
                "不属于当前资料", 0, List.of(), "DOCLING", false, List.of()), enabled(1000));
        assertThat(preferred.extract(request(file, "text/plain", 1000), file).degradation())
                .isEqualTo("SOURCE_PARSER_RESPONSE_INVALID");

        assertThatThrownBy(() -> preferred.extract(request(file, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1000), file))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SOURCE_DOCUMENT_PARSER_REQUIRED"));
    }

    @Test
    void streamingTextCapsCharactersWithoutBreakingASurrogatePair() throws Exception {
        Path file = temporary.resolve("limit.txt");
        Files.writeString(file, "甲乙😀丙丁", StandardCharsets.UTF_8);
        var limited = new SourceDocumentTextExtractor(null,
                new SourceDocumentParserProperties(false, "http://localhost:18103", 180, 3, 3, ""));

        var document = limited.extract("limit.txt", "text/plain", file);

        assertThat(document.text()).isEqualTo("甲乙");
        assertThat(document.truncated()).isTrue();
        assertThat(document.warnings()).containsExactly("MAX_CHARS_REACHED");
    }

    @Test
    void encodingProbeDoesNotMisclassifyAUtf8CharacterCutAtItsBoundary() throws Exception {
        Path file = temporary.resolve("boundary.txt");
        String content = "a".repeat(65535) + "汉字连续原文";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        assertThat(extractor.extract("boundary.txt", "text/plain", file).text()).isEqualTo(content);
    }

    @Test
    void staleOriginalHashIsRejectedBeforeCallingTheParser() throws Exception {
        Path file = temporary.resolve("changed.txt");
        Files.writeString(file, "原文", StandardCharsets.UTF_8);
        var old = request(file, "text/plain", 1000);
        Files.writeString(file, "新资料内容", StandardCharsets.UTF_8);
        var preferred = new SourceDocumentTextExtractor((ignored, original) -> {
            throw new AssertionError("原件摘要不匹配时不应调用 Worker");
        }, enabled(1000));
        assertThatThrownBy(() -> preferred.extract(old, file)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo("SOURCE_DOCUMENT_HASH_MISMATCH"));
    }

    private static SourceDocumentParserProperties enabled(int maxChars) {
        return new SourceDocumentParserProperties(true, "http://localhost:18103", 180, 3, maxChars, "");
    }

    private static SourceDocumentParserPort.Request request(Path file, String mimeType, int maxChars) {
        return new SourceDocumentParserPort.Request("workspace-1", "source-1", "snapshot-1",
                file.getFileName().toString(), mimeType, SourceDocumentTextExtractor.sha256(file), maxChars);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
