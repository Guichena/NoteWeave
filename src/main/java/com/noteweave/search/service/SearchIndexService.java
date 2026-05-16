package com.noteweave.search.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.search.document.EsDocumentChunk;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexService {

    private static final String INDEX_SUFFIX = "document-chunk";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUri;
    private final String indexName;
    private final String authorizationHeader;

    public SearchIndexService(
            ObjectMapper objectMapper,
            @Value("${noteweave.elasticsearch.uris:http://localhost:9200}") String uris,
            @Value("${noteweave.elasticsearch.index-prefix:noteweave-dev-}") String indexPrefix,
            @Value("${noteweave.elasticsearch.username:}") String username,
            @Value("${noteweave.elasticsearch.password:}") String password
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.baseUri = normalizeBaseUri(uris);
        this.indexName = indexPrefix + INDEX_SUFFIX;
        this.authorizationHeader = buildAuthorizationHeader(username, password);
    }

    public void ensureDocumentChunkIndex() {
        try {
            HttpResponse<String> exists = send("HEAD", "/" + indexName, null);
            if (exists.statusCode() == 200) {
                return;
            }
            String mapping = """
                    {
                      "mappings": {
                        "properties": {
                          "spaceId": {"type": "long"},
                          "knowledgeBaseId": {"type": "long"},
                          "documentId": {"type": "long"},
                          "documentStatus": {"type": "keyword"},
                          "indexVersion": {"type": "integer"},
                          "activeIndexVersion": {"type": "integer"},
                          "chunkId": {"type": "long"},
                          "chunkIndex": {"type": "integer"},
                          "title": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                          "content": {"type": "text"},
                          "contentHash": {"type": "keyword"},
                          "sourceType": {"type": "keyword"},
                          "createdBy": {"type": "long"},
                          "lifecycleStatus": {"type": "keyword"},
                          "createdAt": {"type": "date", "format": "strict_date_optional_time||epoch_millis"}
                        }
                      }
                    }
                    """;
            HttpResponse<String> created = send("PUT", "/" + indexName, mapping);
            if (created.statusCode() >= 300 && created.statusCode() != 400) {
                throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to create ES index");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to ensure ES index: " + ex.getMessage());
        }
    }

    public void bulkIndexChunks(List<EsDocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        ensureDocumentChunkIndex();
        for (EsDocumentChunk chunk : chunks) {
            indexChunk(chunk);
        }
        refresh();
    }

    public void indexChunk(EsDocumentChunk chunk) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("spaceId", chunk.getSpaceId());
            body.put("knowledgeBaseId", chunk.getKnowledgeBaseId());
            body.put("documentId", chunk.getDocumentId());
            body.put("documentStatus", chunk.getDocumentStatus());
            body.put("indexVersion", chunk.getIndexVersion());
            body.put("activeIndexVersion", chunk.getActiveIndexVersion());
            body.put("chunkId", chunk.getChunkId());
            body.put("chunkIndex", chunk.getChunkIndex());
            body.put("title", chunk.getTitle());
            body.put("content", chunk.getContent());
            body.put("contentHash", chunk.getContentHash());
            body.put("sourceType", chunk.getSourceType());
            body.put("createdBy", chunk.getCreatedBy());
            body.put("lifecycleStatus", chunk.getLifecycleStatus());
            body.put("createdAt", chunk.getCreatedAt() == null ? java.time.Instant.now().toString() : chunk.getCreatedAt().toString());
            String path = "/" + indexName + "/_doc/" + encode(chunk.getEsDocId());
            HttpResponse<String> response = send("PUT", path, objectMapper.writeValueAsString(body));
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to index chunk");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to index chunk: " + ex.getMessage());
        }
    }

    public List<SearchChunkHit> searchChunkHits(Long spaceId, List<Long> knowledgeBaseIds, String keyword, int limit) {
        if (spaceId == null || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        ensureDocumentChunkIndex();
        String knowledgeBaseTerms = knowledgeBaseIds.stream()
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        String query = """
                {
                  "size": %d,
                  "query": {
                    "bool": {
                      "must": [
                        {
                          "multi_match": {
                            "query": %s,
                            "fields": ["title^2", "content"]
                          }
                        }
                      ],
                      "filter": [
                        {"term": {"spaceId": %d}},
                        {"terms": {"knowledgeBaseId": [%s]}},
                        {"term": {"lifecycleStatus": "ACTIVE"}},
                        {"term": {"documentStatus": "INDEXED"}},
                        {
                          "script": {
                            "script": {
                              "lang": "painless",
                              "source": "doc['indexVersion'].size() != 0 && doc['activeIndexVersion'].size() != 0 && doc['indexVersion'].value == doc['activeIndexVersion'].value"
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """.formatted(Math.max(1, Math.min(limit, 50)), quote(keyword.trim()), spaceId, knowledgeBaseTerms);
        try {
            HttpResponse<String> response = send("POST", "/" + indexName + "/_search", query);
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.ES_QUERY_FAILED, "failed to query ES");
            }
            JsonNode hits = objectMapper.readTree(response.body()).path("hits").path("hits");
            List<SearchChunkHit> chunkHits = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                long chunkId = source.path("chunkId").asLong(0);
                if (chunkId > 0) {
                    chunkHits.add(new SearchChunkHit(
                            chunkId,
                            longOrNull(source, "documentId"),
                            longOrNull(source, "knowledgeBaseId"),
                            longOrNull(source, "spaceId"),
                            intOrNull(source, "indexVersion"),
                            intOrNull(source, "chunkIndex"),
                            hit.path("_score").isNumber() ? hit.path("_score").asDouble() : null
                    ));
                }
            }
            return chunkHits;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_QUERY_FAILED, "failed to query ES: " + ex.getMessage());
        }
    }

    public List<Long> searchChunkIds(Long spaceId, Long knowledgeBaseId, String keyword, int limit) {
        return searchChunkHits(spaceId, List.of(knowledgeBaseId), keyword, limit).stream()
                .map(SearchChunkHit::chunkId)
                .toList();
    }

    public void synchronizeDocumentChunkState(Long documentId, String documentStatus, int activeIndexVersion, String lifecycleStatus) {
        if (documentId == null) {
            return;
        }
        ensureDocumentChunkIndex();
        String query = """
                {
                  "script": {
                    "source": "ctx._source.documentStatus = params.documentStatus; ctx._source.activeIndexVersion = params.activeIndexVersion; ctx._source.lifecycleStatus = params.lifecycleStatus;",
                    "lang": "painless",
                    "params": {
                      "documentStatus": %s,
                      "activeIndexVersion": %d,
                      "lifecycleStatus": %s
                    }
                  },
                  "query": {
                    "term": {"documentId": %d}
                  }
                }
                """.formatted(quote(documentStatus), activeIndexVersion, quote(lifecycleStatus), documentId);
        try {
            HttpResponse<String> response = send("POST", "/" + indexName + "/_update_by_query?refresh=true&conflicts=proceed", query);
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to synchronize indexed document state");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to synchronize indexed document state");
        }
    }

    public void deleteByDocumentId(Long documentId) {
        ensureDocumentChunkIndex();
        String query = """
                {
                  "query": {
                    "term": {"documentId": %d}
                  }
                }
                """.formatted(documentId);
        try {
            send("POST", "/" + indexName + "/_delete_by_query?refresh=true&conflicts=proceed", query);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to delete indexed document");
        }
    }

    public String documentChunkIndexName() {
        return indexName;
    }

    private void refresh() {
        try {
            send("POST", "/" + indexName + "/_refresh", "");
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to refresh index");
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUri + path))
                .timeout(Duration.ofSeconds(20));
        if (authorizationHeader != null) {
            builder.header("Authorization", authorizationHeader);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String normalizeBaseUri(String uris) {
        String first = (uris == null || uris.isBlank()) ? "http://localhost:9200" : uris.split(",")[0].trim();
        if (!first.startsWith("http://") && !first.startsWith("https://")) {
            first = "http://" + first;
        }
        while (first.endsWith("/")) {
            first = first.substring(0, first.length() - 1);
        }
        return first;
    }

    private String buildAuthorizationHeader(String username, String password) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String token = Base64.getEncoder().encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String quote(String value) {
        try {
            return objectMapper.writeValueAsString(value == null ? "" : value);
        } catch (Exception ex) {
            return "\"\"";
        }
    }

    private Long longOrNull(JsonNode node, String field) {
        long value = node.path(field).asLong(0);
        return value > 0 ? value : null;
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return null;
        }
        return value.asInt();
    }
}
