package com.noteweave.source;

import com.noteweave.common.BusinessException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class SourceDocumentTextExtractor {

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "application/json",
            "text/csv"
    );

    public ExtractedDocument extract(String fileName, String mimeType, byte[] content) {
        String normalizedMime = normalizeMime(mimeType);
        if (TEXT_MIME_TYPES.contains(normalizedMime)) {
            return new ExtractedDocument(
                    new String(content, StandardCharsets.UTF_8),
                    normalizedMime,
                    0
            );
        }
        if ("application/pdf".equals(normalizedMime)) {
            return extractPdf(fileName, content);
        }
        throw new BusinessException(
                "SOURCE_DOCUMENT_TYPE_UNSUPPORTED",
                "不支持解析该资料类型：" + normalizedMime
        );
    }

    private ExtractedDocument extractPdf(String fileName, byte[] content) {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(content))) {
            String text = new PDFTextStripper().getText(document).trim();
            if (text.isBlank()) {
                throw new BusinessException(
                        "SOURCE_PDF_TEXT_EMPTY",
                        "PDF 未提取到可检索文本，请先对扫描件执行 OCR：" + safeName(fileName)
                );
            }
            return new ExtractedDocument(text, "application/pdf", document.getNumberOfPages());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    "SOURCE_PDF_PARSE_FAILED",
                    "PDF 解析失败：" + safeName(fileName)
            );
        }
    }

    private String normalizeMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "text/markdown";
        }
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private String safeName(String fileName) {
        return fileName == null || fileName.isBlank() ? "未命名资料" : fileName;
    }

    public record ExtractedDocument(String text, String mimeType, int pageCount) {
    }
}
