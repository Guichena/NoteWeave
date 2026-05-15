package com.noteweave.team.document.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.error.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class DocumentParserServiceTest {

    private final DocumentParserService parser = new DocumentParserService(2_000_000);

    @Test
    void parseTxtShouldReturnUtf8Text() {
        ParseResult result = parser.parse(
                new ByteArrayInputStream("hello NoteWeave\nsecond line".getBytes(StandardCharsets.UTF_8)),
                "notes.txt",
                "text/plain"
        );

        assertThat(result.text()).contains("hello NoteWeave");
        assertThat(result.detectedContentType()).isEqualTo("text/plain");
        assertThat(result.characterCount()).isEqualTo(result.text().length());
    }

    @Test
    void parseMarkdownShouldReturnSourceTextWithoutDroppingHeadings() {
        ParseResult result = parser.parse(
                new ByteArrayInputStream("# Deploy\n\nUse blue green rollout.".getBytes(StandardCharsets.UTF_8)),
                "deploy.md",
                "application/octet-stream"
        );

        assertThat(result.text()).contains("# Deploy");
        assertThat(result.text()).contains("blue green rollout");
        assertThat(result.detectedContentType()).isEqualTo("text/markdown");
    }

    @Test
    void parsePdfShouldReturnExtractedTextAndPageCount() throws Exception {
        byte[] pdf = pdfWithText("Quarterly roadmap checkpoint");

        ParseResult result = parser.parse(
                new ByteArrayInputStream(pdf),
                "roadmap.pdf",
                "application/pdf"
        );

        assertThat(result.text()).contains("Quarterly roadmap checkpoint");
        assertThat(result.detectedContentType()).isEqualTo("application/pdf");
        assertThat(result.metadata()).containsEntry("pageCount", 1);
    }

    @Test
    void parseUnknownOctetStreamShouldBeRejected() {
        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream("fake".getBytes(StandardCharsets.UTF_8)),
                "archive.bin",
                "application/octet-stream"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void parseUnsupportedDocxShouldFailFast() {
        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream("fake".getBytes(StandardCharsets.UTF_8)),
                "legacy.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported");
    }

    private byte[] pdfWithText(String text) throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.newLineAtOffset(72, 720);
                contentStream.showText(text);
                contentStream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
