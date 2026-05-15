package com.noteweave.team.document.parser;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentParserService {

    private static final Set<String> DIRECT_TEXT_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "text/x-markdown"
    );
    private static final Set<String> TIKA_TYPES = Set.of("application/pdf");

    private final Tika tika = new Tika();
    private final int maxTextLength;

    public DocumentParserService(@Value("${noteweave.document.parsing.max-text-length:2000000}") int maxTextLength) {
        this.maxTextLength = maxTextLength;
        this.tika.setMaxStringLength(maxTextLength);
    }

    public ParseResult parse(InputStream inputStream, String fileName, String contentType) {
        String normalizedType = normalizeContentType(fileName, contentType);
        try {
            byte[] bytes = inputStream.readAllBytes();
            String text;
            Map<String, Object> metadataMap = new LinkedHashMap<>();
            metadataMap.put("fileName", fileName == null ? "" : fileName);
            if (DIRECT_TEXT_TYPES.contains(normalizedType)) {
                text = new String(bytes, StandardCharsets.UTF_8);
            } else if (TIKA_TYPES.contains(normalizedType)) {
                Metadata metadata = new Metadata();
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName == null ? "" : fileName);
                text = tika.parseToString(new ByteArrayInputStream(bytes), metadata);
                metadataMap.put("pageCount", detectPdfPageCount(bytes));
            } else {
                throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE, "Unsupported document type: " + normalizedType);
            }

            if (text.length() > maxTextLength) {
                text = text.substring(0, maxTextLength);
            }
            return new ParseResult(text, normalizedType, text.length(), metadataMap);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_PARSE_FAILED, "Failed to parse document: " + ex.getMessage());
        }
    }

    private String normalizeContentType(String fileName, String contentType) {
        String extensionType = contentTypeFromFileName(fileName);
        String suppliedType = normalizedSuppliedContentType(contentType);
        if (suppliedType == null || "application/octet-stream".equals(suppliedType)) {
            return extensionType == null ? "application/octet-stream" : extensionType;
        }
        if (DIRECT_TEXT_TYPES.contains(suppliedType) || TIKA_TYPES.contains(suppliedType)) {
            return suppliedType;
        }
        return suppliedType;
    }

    private String normalizedSuppliedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        return contentType.split(";")[0].trim().toLowerCase();
    }

    private String contentTypeFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        return null;
    }

    private int detectPdfPageCount(byte[] bytes) {
        try (PDDocument document = PDDocument.load(bytes)) {
            return document.getNumberOfPages();
        } catch (Exception ex) {
            return 0;
        }
    }

}
