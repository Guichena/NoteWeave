package com.noteweave.personal.claim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexClaimSupport {

    private static final String CLAIM_INDEX_SUFFIX = "claim";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUri;
    private final String indexName;
    private final String authorizationHeader;
    private final int embeddingDimension;

    public SearchIndexClaimSupport(
            ObjectMapper objectMapper,
            @Value("${noteweave.elasticsearch.uris:http://localhost:19200}") String uris,
            @Value("${noteweave.elasticsearch.index-prefix:noteweave-dev-}") String indexPrefix,
            @Value("${noteweave.elasticsearch.username:}") String username,
            @Value("${noteweave.elasticsearch.password:}") String password,
            @Value("${noteweave.embedding.api.dimension:8}") int embeddingDimension
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.baseUri = normalizeBaseUri(uris);
        this.indexName = indexPrefix + CLAIM_INDEX_SUFFIX;
        this.authorizationHeader = buildAuthorizationHeader(username, password);
        this.embeddingDimension = Math.max(1, embeddingDimension);
    }

    public void ensureIndex() {
        try {
            if (indexExists()) {
                return;
            }
            String mapping = """
                    {
                      "mappings": {
                        "properties": {
                          "spaceId": {"type": "long"},
                          "userId": {"type": "long"},
                          "researchProjectId": {"type": "long"},
                          "researchQuestionId": {"type": "long"},
                          "researchQuestionTitle": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                          "claimId": {"type": "long"},
                          "statement": {"type": "text"},
                          "rationale": {"type": "text"},
                          "claimType": {"type": "keyword"},
                          "stance": {"type": "keyword"},
                          "confidence": {"type": "double"},
                          "cardStatus": {"type": "keyword"},
                          "conceptNames": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                          "embedding": {
                            "type": "dense_vector",
                            "dims": %d,
                            "index": true,
                            "similarity": "cosine"
                          },
                          "updatedAt": {"type": "date", "format": "strict_date_optional_time||epoch_millis"}
                        }
                      }
                    }
                    """.formatted(embeddingDimension);
            HttpResponse<String> created = send("PUT", "/" + indexName, mapping);
            if (created.statusCode() >= 300 && created.statusCode() != 400) {
                throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to create claim ES index");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to ensure claim ES index");
        }
    }

    public void index(ClaimIndexDocument document) {
        ensureIndex();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("spaceId", document.spaceId());
            body.put("userId", document.userId());
            body.put("researchProjectId", document.researchProjectId());
            body.put("researchQuestionId", document.researchQuestionId());
            body.put("researchQuestionTitle", document.researchQuestionTitle());
            body.put("claimId", document.claimId());
            body.put("statement", document.statement());
            body.put("rationale", document.rationale());
            body.put("claimType", document.claimType());
            body.put("stance", document.stance());
            body.put("confidence", document.confidence());
            body.put("cardStatus", document.cardStatus());
            body.put("conceptNames", document.conceptNames());
            if (document.embedding() != null && !document.embedding().isEmpty()) {
                body.put("embedding", document.embedding());
            }
            body.put("updatedAt", document.updatedAt());
            HttpResponse<String> response = send(
                    "PUT",
                    "/" + indexName + "/_doc/" + encode(document.esDocId()),
                    objectMapper.writeValueAsString(body)
            );
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to index claim");
            }
            refresh();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to index claim");
        }
    }

    public void deleteByClaimId(Long claimId) {
        if (claimId == null) {
            return;
        }
        try {
            if (!indexExists()) {
                return;
            }
            HttpResponse<String> response = send("DELETE", "/" + indexName + "/_doc/" + encode(esDocId(claimId)), null);
            if (response.statusCode() >= 300 && response.statusCode() != 404) {
                throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to delete indexed claim");
            }
            refresh();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to delete indexed claim");
        }
    }

    public List<ClaimSearchHit> search(
            Long userId,
            Long spaceId,
            String keyword,
            int limit,
            Long excludeResearchQuestionId
    ) {
        if (userId == null || spaceId == null || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        ensureIndex();
        String query = """
                {
                  "size": %d,
                  "query": {
                    "bool": {
                      "must": [
                        {
                          "multi_match": {
                            "query": %s,
                            "fields": ["statement^3", "rationale^2", "conceptNames", "researchQuestionTitle^1.5"]
                          }
                        }
                      ],
                      "filter": [
                        {"term": {"spaceId": %d}},
                        {"term": {"userId": %d}}
                      ]%s
                    }
                  }
                }
                """.formatted(
                clamp(limit),
                quote(keyword.trim()),
                spaceId,
                userId,
                mustNotQuestionClause(excludeResearchQuestionId)
        );
        try {
            HttpResponse<String> response = send("POST", "/" + indexName + "/_search", query);
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.ES_QUERY_FAILED, "failed to search claims");
            }
            return parseHits(response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_QUERY_FAILED, "failed to search claims");
        }
    }

    public List<ClaimSearchHit> searchByVector(
            Long userId,
            Long spaceId,
            float[] vector,
            int limit,
            Long excludeResearchQuestionId
    ) {
        if (userId == null || spaceId == null || vector == null || vector.length == 0) {
            return List.of();
        }
        try {
            if (!indexExists()) {
                return List.of();
            }
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
                              {"term": {"userId": %d}}
                            ]%s
                          }
                        }
                      }
                    }
                    """.formatted(
                    clamp(limit),
                    vectorJson(vector),
                    clamp(limit),
                    Math.max(10, Math.min(limit * 4, 200)),
                    spaceId,
                    userId,
                    mustNotQuestionClause(excludeResearchQuestionId)
            );
            HttpResponse<String> response = send("POST", "/" + indexName + "/_search", query);
            if (response.statusCode() >= 300) {
                return List.of();
            }
            return parseHits(response.body());
        } catch (Exception ex) {
            return List.of();
        }
    }

    public String esDocId(Long claimId) {
        return "claim-" + claimId;
    }

    private List<ClaimSearchHit> parseHits(String responseBody) throws Exception {
        JsonNode hits = objectMapper.readTree(responseBody).path("hits").path("hits");
        List<ClaimSearchHit> items = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            long claimId = source.path("claimId").asLong(0);
            if (claimId <= 0) {
                continue;
            }
            items.add(new ClaimSearchHit(
                    claimId,
                    source.path("researchQuestionId").asLong(),
                    source.path("researchQuestionTitle").asText(""),
                    source.path("statement").asText(""),
                    source.path("rationale").asText(null),
                    source.path("claimType").asText("HYPOTHESIS"),
                    source.path("stance").asText("UNCERTAIN"),
                    source.path("confidence").isNumber() ? source.path("confidence").asDouble() : 0.5d,
                    readStringList(source.path("conceptNames")),
                    hit.path("_score").isNumber() ? hit.path("_score").asDouble() : 0.0d
            ));
        }
        return items;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && !item.isNull()) {
                    values.add(item.asText(""));
                }
            }
            return values;
        }
        return List.of(node.asText(""));
    }

    private String mustNotQuestionClause(Long excludeResearchQuestionId) {
        if (excludeResearchQuestionId == null) {
            return "";
        }
        return """
                ,
                      "must_not": [
                        {"term": {"researchQuestionId": %d}}
                      ]
                """.formatted(excludeResearchQuestionId);
    }

    private void refresh() {
        try {
            send("POST", "/" + indexName + "/_refresh", "");
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.DOCUMENT_INDEX_FAILED, "failed to refresh claim index");
        }
    }

    private boolean indexExists() throws Exception {
        HttpResponse<String> exists = send("HEAD", "/" + indexName, null);
        return exists.statusCode() == 200;
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

    private int clamp(int limit) {
        return Math.max(1, Math.min(limit, 50));
    }

    public record ClaimIndexDocument(
            String esDocId,
            Long spaceId,
            Long userId,
            Long researchProjectId,
            Long researchQuestionId,
            String researchQuestionTitle,
            Long claimId,
            String statement,
            String rationale,
            String claimType,
            String stance,
            Double confidence,
            String cardStatus,
            List<String> conceptNames,
            List<Float> embedding,
            String updatedAt
    ) {
    }

    public record ClaimSearchHit(
            Long claimId,
            Long researchQuestionId,
            String researchQuestionTitle,
            String statement,
            String rationale,
            String claimType,
            String stance,
            Double confidence,
            List<String> conceptNames,
            Double score
    ) {
    }
}
