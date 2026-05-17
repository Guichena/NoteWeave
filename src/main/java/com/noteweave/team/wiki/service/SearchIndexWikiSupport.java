package com.noteweave.team.wiki.service;

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
public class SearchIndexWikiSupport {

    private static final String WIKI_INDEX_SUFFIX = "wiki-page";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUri;
    private final String indexName;
    private final String authorizationHeader;

    public SearchIndexWikiSupport(
            ObjectMapper objectMapper,
            @Value("${noteweave.elasticsearch.uris:http://localhost:9200}") String uris,
            @Value("${noteweave.elasticsearch.index-prefix:noteweave-dev-}") String indexPrefix,
            @Value("${noteweave.elasticsearch.username:}") String username,
            @Value("${noteweave.elasticsearch.password:}") String password
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.baseUri = normalizeBaseUri(uris);
        this.indexName = indexPrefix + WIKI_INDEX_SUFFIX;
        this.authorizationHeader = buildAuthorizationHeader(username, password);
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
                          "wikiPageId": {"type": "long"},
                          "publishedVersionId": {"type": "long"},
                          "title": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                          "content": {"type": "text"},
                          "status": {"type": "keyword"},
                          "indexStatus": {"type": "keyword"},
                          "sourceArtifactId": {"type": "long"},
                          "updatedAt": {"type": "date", "format": "strict_date_optional_time||epoch_millis"}
                        }
                      }
                    }
                    """;
            HttpResponse<String> created = send("PUT", "/" + indexName, mapping);
            if (created.statusCode() >= 300 && created.statusCode() != 400) {
                throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to create wiki ES index");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_INDEX_NOT_AVAILABLE, "failed to ensure wiki ES index");
        }
    }

    public void deleteByWikiPageId(Long wikiPageId) {
        if (wikiPageId == null) {
            return;
        }
        try {
            if (!indexExists()) {
                return;
            }
            String query = """
                    {
                      "query": {
                        "term": {"wikiPageId": %d}
                      }
                    }
                    """.formatted(wikiPageId);
            HttpResponse<String> response = send("POST", "/" + indexName + "/_delete_by_query?refresh=true&conflicts=proceed", query);
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.WIKI_INDEX_FAILED, "failed to delete wiki page from index");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.WIKI_INDEX_FAILED, "failed to delete wiki page from index");
        }
    }

    public void index(WikiIndexDocument document) {
        ensureIndex();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("spaceId", document.spaceId());
            body.put("wikiPageId", document.wikiPageId());
            body.put("publishedVersionId", document.publishedVersionId());
            body.put("title", document.title());
            body.put("content", document.content());
            body.put("status", document.status());
            body.put("indexStatus", document.indexStatus());
            body.put("sourceArtifactId", document.sourceArtifactId());
            body.put("updatedAt", document.updatedAt());
            HttpResponse<String> response = send("PUT", "/" + indexName + "/_doc/" + encode(document.esDocId()), objectMapper.writeValueAsString(body));
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.WIKI_INDEX_FAILED, "failed to index wiki page");
            }
            refresh();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.WIKI_INDEX_FAILED, "failed to index wiki page");
        }
    }

    public List<WikiSearchHit> search(Long spaceId, String keyword, int limit) {
        if (spaceId == null || keyword == null || keyword.isBlank()) {
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
                            "fields": ["title^2", "content"]
                          }
                        }
                      ],
                      "filter": [
                        {"term": {"spaceId": %d}},
                        {"term": {"status": "PUBLISHED"}},
                        {"term": {"indexStatus": "INDEXED"}}
                      ]
                    }
                  }
                }
                """.formatted(Math.max(1, Math.min(limit, 50)), quote(keyword), spaceId);
        try {
            HttpResponse<String> response = send("POST", "/" + indexName + "/_search", query);
            if (response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.ES_QUERY_FAILED, "failed to search wiki pages");
            }
            JsonNode hits = objectMapper.readTree(response.body()).path("hits").path("hits");
            List<WikiSearchHit> items = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                items.add(new WikiSearchHit(
                        source.path("wikiPageId").asLong(),
                        source.path("publishedVersionId").asLong(),
                        source.path("title").asText(""),
                        source.path("content").asText(""),
                        hit.path("_score").isNumber() ? hit.path("_score").asDouble() : 1.0d
                ));
            }
            return items;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.ES_QUERY_FAILED, "failed to search wiki pages");
        }
    }

    private void refresh() {
        try {
            send("POST", "/" + indexName + "/_refresh", "");
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.WIKI_INDEX_FAILED, "failed to refresh wiki index");
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

    public record WikiIndexDocument(
            String esDocId,
            Long spaceId,
            Long wikiPageId,
            Long publishedVersionId,
            String title,
            String content,
            String status,
            String indexStatus,
            Long sourceArtifactId,
            String updatedAt
    ) {
    }

    public record WikiSearchHit(
            Long wikiPageId,
            Long publishedVersionId,
            String title,
            String content,
            Double score
    ) {
    }
}
