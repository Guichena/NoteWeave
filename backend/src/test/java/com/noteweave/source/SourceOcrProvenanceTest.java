package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.source.DocumentElement.ElementType;
import com.noteweave.source.DocumentElement.TextOrigin;
import com.noteweave.source.SourceDocumentParserPort.PageSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceOcrProvenanceTest {
    @TempDir Path temporary;
    private final DocumentChunker chunker = new DocumentChunker(64, 0);

    @Test
    void indivisibleTableKeepsItsRealOcrSourceConfidenceAndCompleteText() {
        String table = "| 列一 | 列二 |\n" + "| 真实资料 | 真实数据 |\n".repeat(100);
        var document = document(List.of(new DocumentElement(ElementType.TABLE, table, 2, null, null, TextOrigin.OCR, 0.92)));
        var chunks = chunker.chunk(document);
        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).isEqualTo(table);
            assertThat(chunk.textOrigin()).isEqualTo(TextOrigin.OCR);
            assertThat(chunk.ocrConfidence()).isCloseTo(0.92, within(1e-12));
            assertThat(chunk.structureMetadata().elements().get(0).textOrigin()).isEqualTo(TextOrigin.OCR);
            assertThat(chunk.structureMetadata().elements().get(0).ocrConfidence()).isEqualTo(0.92);
            assertThat(chunker.windows(chunk)).containsExactly(table);
        });
    }

    @Test
    void combinesOnlyRealOcrScoresUsingEachEmittedTextsCharacterWeight() {
        var chunks = chunker.chunk(document(List.of(
                new DocumentElement(ElementType.TEXT, "甲乙丙丁", 1, null, null, TextOrigin.OCR, 0.6),
                new DocumentElement(ElementType.TEXT, "一二三四五六七八九十十一", 1, null, null, TextOrigin.OCR, 0.9))));
        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.textOrigin()).isEqualTo(TextOrigin.OCR);
            assertThat(chunk.ocrConfidence()).isCloseTo((4 * 0.6 + 12 * 0.9) / 16, within(1e-12));
        });
    }

    @Test
    void mixedOrPartlyUnknownSourcesStayUnknownAndMissingOcrScoresAreNotInvented() {
        var ocr = new DocumentElement(ElementType.TEXT, "识别原文", 1, null, null, TextOrigin.OCR, 0.0);
        var nativeText = new DocumentElement(ElementType.TEXT, "文字层原文", 1, null, null, TextOrigin.TEXT_LAYER, null);
        var unknown = new DocumentElement(ElementType.TEXT, "历史未知原文", 1, null, null);
        for (DocumentElement other : List.of(nativeText, unknown)) {
            assertThat(chunker.chunk(document(List.of(ocr, other)))).singleElement().satisfies(chunk -> {
                assertThat(chunk.textOrigin()).isNull();
                assertThat(chunk.ocrConfidence()).isNull();
            });
        }
        var noScore = new DocumentElement(ElementType.TEXT, "没有实际分数", 1, null, null, TextOrigin.OCR, null);
        assertThat(chunker.chunk(document(List.of(ocr, noScore)))).singleElement().satisfies(chunk -> {
            assertThat(chunk.textOrigin()).isEqualTo(TextOrigin.OCR);
            assertThat(chunk.ocrConfidence()).isNull();
        });
    }

    @Test
    void nativeElementsAndLongSplitOcrElementsRetainTruthfulNullableFields() {
        var nativeText = new DocumentElement(ElementType.TEXT, "实际文字层正文", 1, null, null, TextOrigin.TEXT_LAYER, null);
        assertThat(chunker.chunk(document(List.of(nativeText)))).singleElement().satisfies(chunk -> {
            assertThat(chunk.textOrigin()).isEqualTo(TextOrigin.TEXT_LAYER);
            assertThat(chunk.ocrConfidence()).isNull();
        });
        var longOcr = new DocumentElement(ElementType.TEXT, "识别到的真实正文。".repeat(100), 1, null, null, TextOrigin.OCR, 0.75);
        assertThat(chunker.chunk(document(List.of(longOcr)))).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.textOrigin()).isEqualTo(TextOrigin.OCR);
            assertThat(chunk.ocrConfidence()).isEqualTo(0.75);
        });
    }

    @Test
    void pdfBoxExtractionMarksOnlyItsRealTextLayerWhileLegacyAndPlainTextRemainUnknown() throws Exception {
        Path file = temporary.resolve("native.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream output = new PDPageContentStream(pdf, page)) {
                output.beginText();
                output.setFont(PDType1Font.HELVETICA, 12);
                output.newLineAtOffset(50, 700);
                output.showText("This is actual text from the PDF text layer.");
                output.endText();
            }
            pdf.save(file.toFile());
        }
        var extracted = new SourceDocumentTextExtractor().extract("native.pdf", "application/pdf", file);
        assertThat(extracted.pageSources()).singleElement().satisfies(page -> {
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.textOrigin()).isEqualTo(TextOrigin.TEXT_LAYER);
            assertThat(page.nativeCharCount()).isGreaterThan(0);
            assertThat(page.hasImages()).isNull();
            assertThat(page.ocrAttempted()).isFalse();
            assertThat(page.ocrConfidence()).isNull();
        });
        assertThat(chunker.chunk(extracted)).allSatisfy(chunk -> assertThat(chunk.textOrigin()).isEqualTo(TextOrigin.TEXT_LAYER));
        var legacy = new SourceDocumentTextExtractor.ExtractedDocument(extracted.text(), "application/pdf", 1);
        assertThat(chunker.chunk(legacy)).allSatisfy(chunk -> assertThat(chunk.textOrigin()).isNull());
        Path text = temporary.resolve("plain.txt");
        Files.writeString(text, "纯文本来源没有 OCR 判定");
        assertThat(chunker.chunk(new SourceDocumentTextExtractor().extract("plain.txt", "text/plain", text)))
                .allSatisfy(chunk -> assertThat(chunk.textOrigin()).isNull());
    }

    @Test
    void extractorPassesAndSerializesOnlyActualParserPageAudits() throws Exception {
        Path file = temporary.resolve("uploaded.pdf");
        Files.writeString(file, "本测试的真实上传字节");
        var audit = new PageSource(2, TextOrigin.OCR, 0.5, 0, true, true);
        SourceDocumentParserPort parser = (request, original) -> new SourceDocumentParserPort.ParsedDocument(1,
                request.workspaceId(), request.sourceId(), request.snapshotId(), request.sha256(), request.mimeType(),
                "真实识别原文", 2, List.of(new DocumentElement(ElementType.TEXT, "真实识别原文", 2, null, null,
                TextOrigin.OCR, 0.5)), "DOCLING", false, List.of(), List.of(audit));
        var settings = new SourceDocumentParserProperties(true, "http://127.0.0.1:18103", 5, 1, 1000, "");
        var extracted = new SourceDocumentTextExtractor(parser, settings).extract("uploaded.pdf", "application/pdf", file);
        assertThat(extracted.pageSources()).containsExactly(audit);
        var mapper = new ObjectMapper();
        var stored = mapper.readTree(mapper.writeValueAsBytes(extracted));
        assertThat(stored.path("page_sources").size()).isEqualTo(1);
        assertThat(stored.path("page_sources").get(0).path("page").intValue()).isEqualTo(2);
        assertThat(mapper.readValue(mapper.writeValueAsBytes(extracted), SourceDocumentTextExtractor.ExtractedDocument.class)
                .pageSources()).containsExactly(audit);
        assertThat(new SourceDocumentTextExtractor.ExtractedDocument("旧正文", "text/plain", 0).pageSources()).isEmpty();
    }

    @Test
    void sourceRecordsRejectNonFiniteScoresAndScoresWithoutAnActualOcrSource() {
        for (Double score : List.of(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1)) {
            assertThatThrownBy(() -> new DocumentElement(ElementType.TEXT, "正文", 1, null, null, TextOrigin.OCR, score))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new DocumentElement(ElementType.TEXT, "正文", 1, null, null, null, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageSource(0, null, null, null, null, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageSource(1, TextOrigin.TEXT_LAYER, 1.0, 1, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SourceDocumentTextExtractor.ExtractedDocument document(List<DocumentElement> elements) {
        return new SourceDocumentTextExtractor.ExtractedDocument("实际正文", "application/pdf", 2,
                elements, "DOCLING", false, List.of(), null);
    }
}
