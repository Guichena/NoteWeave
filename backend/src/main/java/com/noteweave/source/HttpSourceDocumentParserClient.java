package com.noteweave.source;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 向 Docling Worker 流式提交原件，并在反序列化之前校验严格响应契约。 */
@Component
public class HttpSourceDocumentParserClient implements SourceDocumentParserPort {
    private static final int MAX_ELEMENTS = 100_000;
    private static final Set<String> DOCUMENT_FIELDS = Set.of("schema_version", "workspace_id", "source_id",
            "snapshot_id", "sha256", "mime_type", "markdown", "page_count", "elements", "parser",
            "truncated", "warnings");
    private static final Set<String> ELEMENT_FIELDS = Set.of("element_type", "text", "page", "bbox", "heading_level");
    private static final Set<String> TEXT_SOURCE_FIELDS = Set.of("text_origin", "ocr_confidence");
    private static final Set<String> PAGE_SOURCE_FIELDS = Set.of("page", "text_origin", "ocr_confidence",
            "native_char_count", "has_images", "ocr_attempted");
    private static final Set<String> BBOX_FIELDS = Set.of("left", "top", "right", "bottom", "coord_origin");
    private final RestClient worker;
    private final ObjectMapper mapper;
    private final SourceDocumentParserProperties properties;

    public HttpSourceDocumentParserClient(SourceDocumentParserProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper.copy();
        URI base = URI.create(properties.baseUrl());
        if ((!"http".equals(base.getScheme()) && !"https".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("文档解析服务地址必须是有效的 HTTP 或 HTTPS 地址");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(factory);
        if (!properties.internalAuthToken().isBlank()) {
            builder.defaultHeader("X-NoteWeave-Internal-Token", properties.internalAuthToken());
        }
        worker = builder.build();
    }

    @Override
    public ParsedDocument parse(Request request, Path original) {
        if (request.maxChars() < 1 || request.maxChars() > properties.maxChars()
                || request.sha256() == null || !request.sha256().matches("[0-9a-f]{64}")) {
            throw invalid("解析请求的摘要或字符预算无效");
        }
        if (!request.sha256().equals(SourceDocumentTextExtractor.sha256(original))) {
            throw new BusinessException("SOURCE_DOCUMENT_HASH_MISMATCH", "资料原件摘要与解析请求不一致");
        }
        // JSON 字符可以使用 Unicode 转义，另为结构和诊断预留空间，响应仍有绝对上限。
        long limit = Math.min(128L * 1024 * 1024, request.maxChars() * 12L + 4L * 1024 * 1024);
        try {
            return worker.post().uri(builder -> builder.path("/internal/parse")
                            .queryParam("workspace_id", request.workspaceId())
                            .queryParam("source_id", request.sourceId())
                            .queryParam("snapshot_id", request.snapshotId())
                            .queryParam("file_name", request.fileName())
                            .queryParam("mime_type", request.mimeType())
                            .queryParam("sha256", request.sha256())
                            .queryParam("max_chars", request.maxChars()).build())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new FileSystemResource(original))
                    .exchange((sent, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new BusinessException("SOURCE_PARSER_UNAVAILABLE", "文档解析服务未返回成功响应");
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                            throw invalid("文档解析服务响应类型必须是 JSON");
                        }
                        long contentLength = response.getHeaders().getContentLength();
                        if (contentLength > limit) throw invalid("文档解析服务响应超出大小上限");
                        return readResponse(response.getBody(), limit, request);
                    });
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BusinessException("SOURCE_PARSER_UNAVAILABLE", "无法连接文档解析服务或等待响应超时");
        }
    }

    ParsedDocument readResponse(InputStream input, long limit, Request request) {
        try (JsonParser parser = mapper.getFactory().createParser(new BoundedInputStream(input, limit))) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = mapper.readTree(parser);
            if (parser.nextToken() != null) throw invalid("文档解析服务响应包含多份 JSON");
            fields(node, DOCUMENT_FIELDS, Set.of("page_sources"));
            integer(node, "schema_version", 1, 1);
            identity(node, "workspace_id", request.workspaceId());
            identity(node, "source_id", request.sourceId());
            identity(node, "snapshot_id", request.snapshotId());
            identity(node, "sha256", request.sha256());
            identity(node, "mime_type", request.mimeType());
            identity(node, "parser", "DOCLING");
            String markdown = string(node, "markdown");
            if (markdown.isBlank() || markdown.length() > request.maxChars()) {
                throw invalid("文档解析服务返回空正文或超出字符预算");
            }
            int pageCount = integer(node, "page_count", 0, Integer.MAX_VALUE);
            if (!node.get("truncated").isBoolean()) throw invalid("truncated 必须是布尔值");
            JsonNode warnings = node.get("warnings");
            if (!warnings.isArray()) throw invalid("warnings 必须是数组");
            for (JsonNode warning : warnings) if (!warning.isTextual()) throw invalid("warnings 项必须是字符串");
            JsonNode elements = node.get("elements");
            if (!elements.isArray()) throw invalid("elements 必须是数组");
            if (elements.size() > MAX_ELEMENTS) throw invalid("结构元素数量超过 100000 上限");
            long textChars = 0;
            for (JsonNode element : elements) {
                fields(element, ELEMENT_FIELDS, TEXT_SOURCE_FIELDS);
                validateTextSource(element);
                try { DocumentElement.ElementType.valueOf(string(element, "element_type")); }
                catch (IllegalArgumentException exception) { throw invalid("未知的结构元素类型"); }
                textChars += string(element, "text").length();
                if (textChars > request.maxChars()) throw invalid("结构元素正文超出字符预算");
                // 未知总页数不会否定真实 provenance；总页数已知时仍核对页码范围。
                if (!element.get("page").isNull()) integer(element, "page", 1,
                        pageCount == 0 ? Integer.MAX_VALUE : pageCount);
                if (!element.get("heading_level").isNull()) integer(element, "heading_level", 1, 100);
                JsonNode bbox = element.get("bbox");
                if (!bbox.isNull()) {
                    exactFields(bbox, BBOX_FIELDS);
                    for (String coordinate : Set.of("left", "top", "right", "bottom")) {
                        JsonNode value = bbox.get(coordinate);
                        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw invalid("结构坐标必须是有限数值");
                    }
                    String origin = string(bbox, "coord_origin");
                    if (!Set.of("TOPLEFT", "BOTTOMLEFT").contains(origin)) throw invalid("未知的坐标原点");
                    if (bbox.get("right").doubleValue() < bbox.get("left").doubleValue()) throw invalid("结构横坐标顺序无效");
                    double top = bbox.get("top").doubleValue(), bottom = bbox.get("bottom").doubleValue();
                    if (("TOPLEFT".equals(origin) && top > bottom) || ("BOTTOMLEFT".equals(origin) && top < bottom)) {
                        throw invalid("结构纵坐标顺序与坐标原点不一致");
                    }
                }
            }
            JsonNode pageSources = node.get("page_sources");
            if (pageSources != null) {
                if (!pageSources.isArray() || pageSources.size() > MAX_ELEMENTS) {
                    throw invalid("page_sources 必须是有界的页面来源数组");
                }
                Set<Integer> recordedPages = new HashSet<>();
                for (JsonNode pageSource : pageSources) {
                    exactFields(pageSource, PAGE_SOURCE_FIELDS);
                    int page = integer(pageSource, "page", 1, pageCount == 0 ? Integer.MAX_VALUE : pageCount);
                    if (!recordedPages.add(page)) throw invalid("页面来源记录的页码不能重复");
                    validateTextSource(pageSource);
                    if (!pageSource.get("native_char_count").isNull()) {
                        integer(pageSource, "native_char_count", 0, Integer.MAX_VALUE);
                    }
                    if (!pageSource.get("has_images").isNull() && !pageSource.get("has_images").isBoolean()) {
                        throw invalid("has_images 必须是布尔值或未知空值");
                    }
                    if (!pageSource.get("ocr_attempted").isBoolean()) throw invalid("ocr_attempted 必须是布尔值");
                }
            }
            return mapper.treeToValue(node, ParsedDocument.class);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid("文档解析服务响应无效或超出大小上限");
        }
    }

    private static void exactFields(JsonNode node, Set<String> expected) {
        fields(node, expected, Set.of());
    }

    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw invalid("响应对象结构无效");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) throw invalid("响应字段与解析契约不一致");
    }

    private static void validateTextSource(JsonNode node) {
        JsonNode origin = node.get("text_origin");
        if (origin != null && !origin.isNull() && (!origin.isTextual()
                || !Set.of("TEXT_LAYER", "OCR").contains(origin.textValue()))) {
            throw invalid("文字来源必须是 TEXT_LAYER、OCR 或未知空值");
        }
        JsonNode confidence = node.get("ocr_confidence");
        if (confidence != null && !confidence.isNull() && (!confidence.isNumber()
                || !Double.isFinite(confidence.doubleValue()) || confidence.doubleValue() < 0
                || confidence.doubleValue() > 1 || origin == null || !"OCR".equals(origin.textValue()))) {
            throw invalid("识别置信度只能用于 OCR 来源，并且必须是 0 到 1 的有限数值");
        }
    }

    private static String string(JsonNode node, String key) {
        if (!node.get(key).isTextual()) throw invalid(key + " 必须是字符串");
        return node.get(key).textValue();
    }

    private static int integer(JsonNode node, String key, int minimum, int maximum) {
        JsonNode value = node.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum || value.intValue() > maximum) {
            throw invalid(key + " 必须是有效范围内的整数");
        }
        return value.intValue();
    }

    private static void identity(JsonNode node, String key, String expected) {
        if (!string(node, key).equals(expected)) throw invalid("文档解析响应的 " + key + " 与请求不一致");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("SOURCE_PARSER_RESPONSE_INVALID", message);
    }

    static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && --remaining < 0) throw new IOException("响应超出大小上限");
            return value;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            int read = in.read(target, offset, (int) Math.min(length, Math.max(1, remaining + 1)));
            if (read > 0 && (remaining -= read) < 0) throw new IOException("响应超出大小上限");
            return read;
        }
    }
}
