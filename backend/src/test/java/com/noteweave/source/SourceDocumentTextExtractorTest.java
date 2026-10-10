package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class SourceDocumentTextExtractorTest {

    private final SourceDocumentTextExtractor extractor = new SourceDocumentTextExtractor();

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

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
