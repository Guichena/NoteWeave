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
