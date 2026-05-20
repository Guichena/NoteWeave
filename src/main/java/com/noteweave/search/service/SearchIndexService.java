package com.noteweave.search.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.search.document.EsDocumentChunk;
import com.noteweave.team.document.model.DocumentChunk;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexService {

    private static final String INDEX_SUFFIX = "document-chunk";
    private static final String VECTOR_ALIAS_SUFFIX = "document-chunk-vector";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUri;
    private final String indexName;
    private final String indexPrefix;
    private final String authorizationHeader;

    public SearchIndexService(
            ObjectMapper objectMapper,
            @Value("${noteweave.elasticsearch.uris:http://localhost:19200}") String uris,
            @Value("${noteweave.elasticsearch.index-prefix:noteweave-dev-}") String indexPrefix,
            @Value("${noteweave.elasticsearch.username:}") String username,
            @Value("${noteweave.elasticsearch.password:}") String password
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.baseUri = normalizeBaseUri(uris);
        this.indexPrefix = indexPrefix;
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
                          "embedding": {
                            "type": "dense_vector",
                            "dims": 8,
                            "index": true,
                            "similarity": "cosine"
                          },
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
            if (chunk.getEmbedding() != null && !chunk.getEmbedding().isEmpty()) {
                body.put("embedding", chunk.getEmbedding());
            }
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

    public List<SearchChunkHit> searchChunkHitsByVector(Long spaceId, List<Long> knowledgeBaseIds, float[] vector, int limit) {
        if (spaceId == null || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || vector == null || vector.length == 0) {
            return List.of();
        }
        String vectorIndex = documentChunkVectorAliasName();
        try {
            HttpResponse<String> exists = send("HEAD", "/" + vectorIndex, null);
            if (exists.statusCode() >= 300) {
                return List.of();
            }
        } catch (Exception ex) {
            return List.of();
        }
        String knowledgeBaseTerms = knowledgeBaseIds.stream()
                .distinct()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        String query = """
                {
                  "size": %d,
                  "knn": {
                    "field": "embedding",
                    "query_vector": %s,
                    "k": %d,
                    "num_candidates": %d,
                    "filter": {
                      "bool": {
                        "filter": [
                          {"term": {"spaceId": %d}},
                          {"terms": {"knowledgeBaseId": [%s]}},
                          {"term": {"lifecycleStatus": "ACTIVE"}},
                          {"term": {"documentStatus": "INDEXED"}}
                        ]
                      }
                    }
                  }
                }
                """.formatted(
                Math.max(1, Math.min(limit, 50)),
                vectorJson(vector),
                Math.max(1, Math.min(limit, 50)),
                Math.max(10, Math.min(limit * 4, 200)),
                spaceId,
                knowledgeBaseTerms
        );
        try {
            HttpResponse<String> response = send("POST", "/" + vectorIndex + "/_search", query);
            if (response.statusCode() >= 300) {
                return List.of();
            }
            return parseChunkHits(response.body());
        } catch (Exception ex) {
            return List.of();
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

    public JsonNode clusterHealth() {
        try {
            HttpResponse<String> response = send("GET", "/_cluster/health", null);
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to read cluster health");
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to read cluster health");
        }
    }

    public boolean aliasExists(String aliasName) {
        try {
            HttpResponse<String> response = send("HEAD", "/" + aliasName, null);
            return response.statusCode() == 200;
        } catch (Exception ex) {
            return false;
        }
    }

    public List<IndexedChunkDocument> listIndexedChunkDocuments(int size) {
        ensureDocumentChunkIndex();
        String query = """
                {
                  "size": %d,
                  "_source": ["chunkId", "documentId", "sourceType"],
                  "query": {"match_all": {}}
                }
                """.formatted(Math.max(1, Math.min(size, 2000)));
        try {
            HttpResponse<String> response = send("POST", "/" + indexName + "/_search", query);
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.ES_QUERY_FAILED, "failed to list ES chunks");
            }
            JsonNode hits = objectMapper.readTree(response.body()).path("hits").path("hits");
            List<IndexedChunkDocument> documents = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                documents.add(new IndexedChunkDocument(
                        hit.path("_id").asText(),
                        longOrNull(source, "chunkId"),
                        longOrNull(source, "documentId"),
                        source.path("sourceType").asText(null)
                ));
            }
            return documents;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_QUERY_FAILED, "failed to list ES chunks");
        }
    }

    public void deleteChunkDocumentByEsDocId(String esDocId) {
        deleteDocumentById(indexName, esDocId);
        if (aliasExists(documentChunkVectorAliasName())) {
            try {
                deleteDocumentById(documentChunkVectorAliasName(), esDocId);
            } catch (BusinessException ignored) {
                // The vector alias is best-effort during orphan cleanup.
            }
        }
    }

    public String documentChunkIndexName() {
        return indexName;
    }

    public String documentChunkVectorAliasName() {
        return indexPrefix + VECTOR_ALIAS_SUFFIX;
    }

    public String documentChunkVectorIndexName(String model, int dimension) {
        return indexPrefix + VECTOR_ALIAS_SUFFIX + "-" + model + "-" + dimension;
    }

    public void ensureVectorIndex(String indexName, int dimension) {
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
                          "embedding": {
                            "type": "dense_vector",
                            "dims": %d,
                            "index": true,
                            "similarity": "cosine"
                          },
                          "contentHash": {"type": "keyword"},
                          "sourceType": {"type": "keyword"},
                          "createdBy": {"type": "long"},
                          "lifecycleStatus": {"type": "keyword"},
                          "createdAt": {"type": "date", "format": "strict_date_optional_time||epoch_millis"}
                        }
                      }
                    }
                    """.formatted(dimension);
            HttpResponse<String> created = send("PUT", "/" + indexName, mapping);
            if (created.statusCode() >= 300 && created.statusCode() != 400) {
                throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to create vector ES index");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to ensure vector ES index");
        }
    }

    public void switchVectorAlias(String aliasName, String indexName) {
        try {
            String body = """
                    {
                      "actions": [
                        {"remove": {"index": "*", "alias": %s, "ignore_unavailable": true}},
                        {"add": {"index": %s, "alias": %s}}
                      ]
                    }
                    """.formatted(quote(aliasName), quote(indexName), quote(aliasName));
            send("POST", "/_aliases", body);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to switch vector alias");
        }
    }

    public void bulkIndexChunkEmbeddings(String vectorIndexName, List<DocumentChunk> chunks, List<float[]> vectors) {
        if (chunks == null || chunks.isEmpty() || vectors == null || vectors.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunks.size() && i < vectors.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            float[] vector = vectors.get(i);
            Map<String, Object> body = new HashMap<>();
            body.put("spaceId", chunk.getSpaceId());
            body.put("knowledgeBaseId", chunk.getKnowledgeBaseId());
            body.put("documentId", chunk.getDocumentId());
            body.put("documentStatus", "INDEXED");
            body.put("indexVersion", chunk.getIndexVersion());
            body.put("activeIndexVersion", chunk.getIndexVersion());
            body.put("chunkId", chunk.getId());
            body.put("chunkIndex", chunk.getChunkIndex());
            body.put("title", "");
            body.put("content", chunk.getContent());
            body.put("embedding", vectorToList(vector));
            body.put("contentHash", chunk.getContentHash());
            body.put("sourceType", "FILE");
            body.put("createdBy", 0L);
            body.put("lifecycleStatus", "ACTIVE");
            body.put("createdAt", chunk.getCreatedAt() == null ? java.time.Instant.now().toString() : chunk.getCreatedAt().toString());
            try {
                send("PUT", "/" + vectorIndexName + "/_doc/" + encode(chunk.getEsDocId()), objectMapper.writeValueAsString(body));
            } catch (Exception ex) {
                throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to index chunk embedding");
            }
        }
        try {
            send("POST", "/" + vectorIndexName + "/_refresh", "");
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to refresh vector index");
        }
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
        String first = (uris == null || uris.isBlank()) ? "http://localhost:19200" : uris.split(",")[0].trim();
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

    private String vectorJson(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private List<Float> vectorToList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private List<SearchChunkHit> parseChunkHits(String responseBody) throws Exception {
        JsonNode hits = objectMapper.readTree(responseBody).path("hits").path("hits");
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

    private void deleteDocumentById(String indexOrAlias, String esDocId) {
        if (esDocId == null || esDocId.isBlank()) {
            return;
        }
        try {
            HttpResponse<String> response = send("DELETE", "/" + indexOrAlias + "/_doc/" + encode(esDocId), null);
            if (response.statusCode() >= 300 && response.statusCode() != 404) {
                throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to delete indexed chunk");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to delete indexed chunk");
        }
    }

    public record IndexedChunkDocument(String esDocId, Long chunkId, Long documentId, String sourceType) {
    }
}
