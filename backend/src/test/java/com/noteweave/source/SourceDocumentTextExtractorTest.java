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
}
