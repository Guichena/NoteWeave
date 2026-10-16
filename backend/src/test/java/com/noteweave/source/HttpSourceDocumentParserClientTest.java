package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noteweave.common.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpSourceDocumentParserClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temporary;
    private final SourceDocumentParserPort.Request request = new SourceDocumentParserPort.Request(
            "workspace-1", "source-1", "snapshot-1", "资料.pdf", "application/pdf", "a".repeat(64), 1000);

    @Test
    void binarySubmissionIncludesIdentityBudgetAndTheInternalToken() throws Exception {
        Path original = temporary.resolve("original.pdf");
        byte[] bytes = new byte[] {37, 80, 68, 70, 10};
        Files.write(original, bytes);
        var actualRequest = new SourceDocumentParserPort.Request("workspace-1", "source-1", "snapshot-1",
                "资料.pdf", "application/pdf", SourceDocumentTextExtractor.sha256(original), 1000);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<byte[]> sent = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/internal/parse", exchange -> {
            sent.set(exchange.getRequestBody().readAllBytes());
            token.set(exchange.getRequestHeaders().getFirst("X-NoteWeave-Internal-Token"));
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] response = mapper.writeValueAsBytes(valid(actualRequest));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var properties = new SourceDocumentParserProperties(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), 5, 1, 1000, "private-test-token");
            var result = new HttpSourceDocumentParserClient(properties, mapper).parse(actualRequest, original);
            assertThat(sent.get()).containsExactly(bytes);
            assertThat(token.get()).isEqualTo("private-test-token");
            assertThat(query.get()).contains("workspace_id=workspace-1", "source_id=source-1",
                    "snapshot_id=snapshot-1", "sha256=" + actualRequest.sha256(), "max_chars=1000");
            assertThat(result.parser()).isEqualTo("DOCLING");
            assertThat(result.elements()).singleElement().satisfies(element -> {
                assertThat(element.elementType()).isEqualTo(DocumentElement.ElementType.TEXT);
                assertThat(element.bbox().coordOrigin()).isEqualTo(DocumentElement.CoordOrigin.TOPLEFT);
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void strictResponseRejectsUnknownFieldsMissingFieldsAndTypeCoercion() throws Exception {
        ObjectNode unknown = valid(request);
        unknown.put("extra", "不得忽略");
        rejects(unknown);
        ObjectNode missing = valid(request);
        missing.remove("truncated");
        rejects(missing);
        ObjectNode coercion = valid(request);
        coercion.put("schema_version", "1");
        rejects(coercion);
        ObjectNode unknownElement = valid(request);
        ((ObjectNode) unknownElement.get("elements").get(0)).put("element_type", "UNKNOWN");
        rejects(unknownElement);
    }

    @Test
    void responseMustMatchAllIdentitiesMimeTypeAndTheOriginalHash() throws Exception {
        for (String field : List.of("workspace_id", "source_id", "snapshot_id", "sha256", "mime_type", "parser")) {
            ObjectNode invalid = valid(request);
            invalid.put(field, "其他身份");
            rejects(invalid);
        }
    }

    @Test
    void responseRejectsEmptyTextInvalidPagesAndCoordinateOrigin() throws Exception {
        ObjectNode empty = valid(request);
        empty.put("markdown", " ");
        rejects(empty);
        ObjectNode page = valid(request);
        ((ObjectNode) page.get("elements").get(0)).put("page", 2);
        rejects(page);
        ObjectNode coordinates = valid(request);
        ((ObjectNode) coordinates.get("elements").get(0).get("bbox")).put("coord_origin", "SIDEWAYS");
        rejects(coordinates);
        ObjectNode backwards = valid(request);
        ((ObjectNode) backwards.get("elements").get(0).get("bbox")).put("bottom", 1);
        rejects(backwards);
    }

    @Test
    void unknownPageCountPreservesRealPositiveProvenanceAndRejectsInvalidPages() throws Exception {
        ObjectNode unknown = valid(request);
        unknown.put("page_count", 0);
        ((ObjectNode) unknown.get("elements").get(0)).put("page", 7);
        byte[] response = mapper.writeValueAsBytes(unknown);
        var parsed = client().readResponse(new ByteArrayInputStream(response), 10000, request);
        assertThat(parsed.pageCount()).isZero();
        assertThat(parsed.elements()).singleElement().satisfies(element -> assertThat(element.page()).isEqualTo(7));

        ((ObjectNode) unknown.get("elements").get(0)).put("page", 0);
        rejects(unknown);
        ((ObjectNode) unknown.get("elements").get(0)).put("page", -1);
        rejects(unknown);
        ((ObjectNode) unknown.get("elements").get(0)).put("page", "7");
        rejects(unknown);
    }

    @Test
    void moreThanOneHundredThousandElementsAreRejectedBeforeTheyReachChunking() throws Exception {
        ObjectNode response = valid(request);
        var elements = response.putArray("elements");
        ObjectNode picture = mapper.createObjectNode().put("element_type", "PICTURE").put("text", "");
        picture.putNull("page").putNull("bbox").putNull("heading_level");
        for (int index = 0; index <= 100_000; index++) elements.add(picture);
        byte[] bytes = mapper.writeValueAsBytes(response);
        assertThatThrownBy(() -> client().readResponse(new ByteArrayInputStream(bytes), 20L * 1024 * 1024, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("SOURCE_PARSER_RESPONSE_INVALID");
                    assertThat(exception.getMessage()).contains("100000");
                });
    }

    @Test
    void boundedReaderRejectsOversizeDuplicateKeysAndTrailingDocuments() throws Exception {
        byte[] json = mapper.writeValueAsBytes(valid(request));
        var client = client();
        assertThatThrownBy(() -> client.readResponse(new ByteArrayInputStream(json), 32, request))
                .isInstanceOf(BusinessException.class);
        String duplicate = new String(json, StandardCharsets.UTF_8).replace("\"schema_version\":1",
                "\"schema_version\":1,\"schema_version\":1");
        assertThatThrownBy(() -> client.readResponse(new ByteArrayInputStream(duplicate.getBytes(StandardCharsets.UTF_8)),
                10000, request)).isInstanceOf(BusinessException.class);
        byte[] trailing = (new String(json, StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client.readResponse(new ByteArrayInputStream(trailing), 10000, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void optionalOcrFieldsPreserveZeroConfidenceAndRealPageAuditsWithoutInventingLegacyValues() throws Exception {
        var legacy = read(valid(request));
        assertThat(legacy.pageSources()).isEmpty();
        assertThat(legacy.elements().get(0).textOrigin()).isNull();
        assertThat(legacy.elements().get(0).ocrConfidence()).isNull();

        ObjectNode response = valid(request);
        response.put("page_count", 2);
        ObjectNode element = (ObjectNode) response.get("elements").get(0);
        element.put("text_origin", "OCR").put("ocr_confidence", 0.0);
        response.putArray("page_sources").add(pageSource(1, "OCR", 0.0)).add(pageSource(2, null, null));
        var parsed = read(response);
        assertThat(parsed.elements().get(0).textOrigin()).isEqualTo(DocumentElement.TextOrigin.OCR);
        assertThat(parsed.elements().get(0).ocrConfidence()).isZero();
        assertThat(parsed.pageSources()).hasSize(2);
        assertThat(parsed.pageSources().get(0).ocrConfidence()).isZero();
        assertThat(parsed.pageSources().get(1).textOrigin()).isNull();
        assertThat(parsed.pageSources().get(1).nativeCharCount()).isNull();
        assertThat(parsed.pageSources().get(1).hasImages()).isNull();
    }

    @Test
    void illegalOcrSourcesConfidenceTypesAndConfidenceWithoutOcrAreRejected() throws Exception {
        for (String source : List.of("MIXED", "ocr", "")) {
            ObjectNode response = valid(request);
            ((ObjectNode) response.get("elements").get(0)).put("text_origin", source);
            rejects(response);
        }
        for (double value : new double[]{-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            ObjectNode response = valid(request);
            ((ObjectNode) response.get("elements").get(0)).put("text_origin", "OCR").put("ocr_confidence", value);
            rejects(response);
        }
        ObjectNode wrongType = valid(request);
        ObjectNode element = (ObjectNode) wrongType.get("elements").get(0);
        element.put("text_origin", "OCR").put("ocr_confidence", "0.5");
        rejects(wrongType);
        element.put("ocr_confidence", true);
        rejects(wrongType);
        element.put("text_origin", "TEXT_LAYER").put("ocr_confidence", 0.0);
        rejects(wrongType);
        element.remove("text_origin");
        rejects(wrongType);
        element.putNull("text_origin");
        rejects(wrongType);
        element.putNull("ocr_confidence").put("unrecognized_ocr_field", 0.5);
        rejects(wrongType);
    }

    @Test
    void pageAuditsRejectDuplicatesImpossiblePageNumbersAndCoercionButAllowUnknownTotal() throws Exception {
        ObjectNode response = valid(request);
        response.putArray("page_sources").add(pageSource(1, "OCR", 0.8)).add(pageSource(1, "OCR", 0.8));
        rejects(response);
        for (int page : new int[]{0, -1, 2}) {
            response.putArray("page_sources").add(pageSource(page, "OCR", 0.8));
            rejects(response);
        }
        response.put("page_count", 0);
        response.putArray("page_sources").add(pageSource(7, "OCR", 0.8));
        assertThat(read(response).pageSources().get(0).page()).isEqualTo(7);
        ObjectNode audit = (ObjectNode) response.get("page_sources").get(0);
        audit.put("page", "7");
        rejects(response);
        audit.put("page", 7).put("native_char_count", -1);
        rejects(response);
        audit.put("native_char_count", 1.5);
        rejects(response);
        audit.put("native_char_count", "0");
        rejects(response);
        audit.putNull("native_char_count").put("has_images", "true");
        rejects(response);
        audit.putNull("has_images").put("ocr_attempted", "false");
        rejects(response);
        audit.put("ocr_attempted", true).put("extra", "拒绝未知字段");
        rejects(response);
        audit.remove("extra");
        audit.remove("native_char_count");
        rejects(response);
        response.putNull("page_sources");
        rejects(response);
    }

    private SourceDocumentParserPort.ParsedDocument read(ObjectNode response) throws Exception {
        return client().readResponse(new ByteArrayInputStream(mapper.writeValueAsBytes(response)), 10000, request);
    }

    private ObjectNode pageSource(int page, String origin, Double confidence) {
        ObjectNode node = mapper.createObjectNode().put("page", page);
        if (origin == null) node.putNull("text_origin"); else node.put("text_origin", origin);
        if (confidence == null) node.putNull("ocr_confidence"); else node.put("ocr_confidence", confidence);
        node.putNull("native_char_count").putNull("has_images").put("ocr_attempted", origin != null && origin.equals("OCR"));
        return node;
    }

    private HttpSourceDocumentParserClient client() {
        return new HttpSourceDocumentParserClient(new SourceDocumentParserProperties(
                true, "http://127.0.0.1:18103", 5, 1, 1000, ""), mapper);
    }

    private void rejects(ObjectNode response) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(response);
        assertThatThrownBy(() -> client().readResponse(new ByteArrayInputStream(bytes), 10000, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SOURCE_PARSER_RESPONSE_INVALID"));
    }

    private ObjectNode valid(SourceDocumentParserPort.Request identity) {
        return mapper.valueToTree(Map.ofEntries(
                Map.entry("schema_version", 1), Map.entry("workspace_id", identity.workspaceId()),
                Map.entry("source_id", identity.sourceId()), Map.entry("snapshot_id", identity.snapshotId()),
                Map.entry("sha256", identity.sha256()), Map.entry("mime_type", identity.mimeType()),
                Map.entry("markdown", "正文保留连续原文。"), Map.entry("page_count", 1),
                Map.entry("parser", "DOCLING"), Map.entry("truncated", false), Map.entry("warnings", List.of()),
                Map.entry("elements", List.of(Map.of("element_type", "TEXT", "text", "正文保留连续原文。", "page", 1,
                        "bbox", Map.of("left", 10, "top", 20, "right", 100, "bottom", 80, "coord_origin", "TOPLEFT"),
                        "heading_level", 1)))));
    }
}
