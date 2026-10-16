package com.noteweave.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Wiki 页面索引 noteweave-wiki。 */
@Component
class ElasticsearchWikiPageIndex implements WikiPageIndex {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchWikiPageIndex.class);

    private final ElasticsearchClient client;
    private final String indexName;
    private final int dimensions;

    @Autowired
    ElasticsearchWikiPageIndex(
            ObjectProvider<ElasticsearchClient> clientProvider,
            @Value("${noteweave.knowledge.wiki-index-name:noteweave-wiki}") String indexName,
            @Value("${noteweave.embedding.dimensions:1024}") int dimensions
    ) {
        this(clientProvider.getIfAvailable(), indexName, dimensions);
    }

    ElasticsearchWikiPageIndex(ElasticsearchClient client, String indexName, int dimensions) {
        this.client = client;
        this.indexName = indexName == null || indexName.isBlank() ? "noteweave-wiki" : indexName;
        this.dimensions = dimensions;
    }

    @Override
    public boolean available() {
        return client != null;
    }

    @Override
    public boolean upsert(Document document) {
        if (client == null || document == null || document.itemId() == null || document.itemId().isBlank()) {
            return false;
        }
        if (!ensureIndex()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace_id", text(document.workspaceId()));
        body.put("item_id", document.itemId());
        body.put("version_id", text(document.versionId()));
        body.put("version_no", document.versionNo());
        body.put("page_kind", text(document.pageKind()));
        body.put("title", text(document.title()));
        body.put("summary", text(document.summary()));
        body.put("content", text(document.content()));
        body.put("outgoing_count", document.outgoingCount());
        body.put("backlink_count", document.backlinkCount());
        body.put("citation_count", document.citationCount());
        body.put("unresolved_count", document.unresolvedCount());
        body.put("updated_at", document.updatedAtEpochMilli());
        if (document.embedding() != null && document.embedding().size() == dimensions) {
            body.put("page_embedding", document.embedding());
        }
        try {
            client.index(request -> request
                    .index(indexName)
                    .id(documentId(document.workspaceId(), document.itemId()))
                    .document(body));
            return true;
        } catch (IOException ex) {
            log.warn("Wiki 页面索引写入失败: {}", ex.toString());
            return false;
        }
    }

    @Override
    public boolean delete(String workspaceId, String itemId) {
        if (client == null) {
            return false;
        }
        try {
            client.delete(request -> request.index(indexName).id(documentId(workspaceId, itemId)));
            return true;
        } catch (IOException ex) {
            log.warn("Wiki 页面索引删除失败: {}", ex.toString());
            return false;
        }
    }

    @Override
    public SearchResult keywordSearch(String workspaceId, String query, WikiLexicalBoost boost, int limit) {
        if (client == null || limit <= 0) {
            return SearchResult.unavailable();
        }
        WikiLexicalBoost safeBoost = boost == null ? WikiLexicalBoost.none() : boost;
        boolean hasQuery = query != null && !query.isBlank();
        if (!hasQuery && safeBoost.empty()) {
            return SearchResult.ready(List.of());
        }
        if (!ensureIndex()) {
            return SearchResult.unavailable();
        }
        BoolQuery.Builder bool = new BoolQuery.Builder()
                .filter(filter -> filter.term(term -> term.field("workspace_id").value(workspaceId)));
        if (hasQuery) {
            bool.must(must -> must.multiMatch(multi -> multi
                    .query(query)
                    .fields("title^3", "summary^2", "content")
                    .type(TextQueryType.BestFields)));
        }
        boostTerms(bool, safeBoost.mustTerms(), "title^6", "summary^4", "content^2");
        boostTerms(bool, safeBoost.titleTerms(), "title^6");
        boostTerms(bool, safeBoost.bodyTerms(), "content^2");
        if (!hasQuery) {
            bool.minimumShouldMatch("1");
        }
        SearchRequest request = new SearchRequest.Builder()
                .index(indexName)
                .size(limit)
                .query(candidate -> candidate.bool(bool.build()))
                .build();
        return search(request);
    }

    @Override
    public SearchResult vectorSearch(String workspaceId, List<Float> vector, int limit) {
        if (client == null || limit <= 0) {
            return SearchResult.unavailable();
        }
        if (vector == null || vector.isEmpty()) {
            return SearchResult.ready(List.of());
        }
        if (vector.size() != dimensions) {
            return SearchResult.unavailable();
        }
        if (!ensureIndex()) {
            return SearchResult.unavailable();
        }
        int size = Math.min(limit, WikiHybridRanker.CHANNEL_LIMIT);
        KnnSearch knn = new KnnSearch.Builder()
                .field("page_embedding")
                .queryVector(vector)
                .k(size)
                .numCandidates(Math.max(50, size * 5))
                .filter(filter -> filter.term(term -> term.field("workspace_id").value(workspaceId)))
                .build();
        SearchRequest request = new SearchRequest.Builder()
                .index(indexName)
                .size(size)
                .knn(knn)
                .build();
        return search(request);
    }

    private void boostTerms(BoolQuery.Builder bool, List<String> terms, String... fields) {
        if (terms == null || fields.length == 0) {
            return;
        }
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            bool.should(should -> should.multiMatch(multi -> multi
                    .query(term)
                    .fields(List.of(fields))
                    .type(TextQueryType.BestFields)));
        }
    }

    private boolean ensureIndex() {
        try {
            boolean exists = client.indices().exists(request -> request.index(indexName)).value();
            if (exists) {
                return true;
            }
            client.indices().create(request -> request.index(indexName).mappings(mapping()));
            return true;
        } catch (Exception ex) {
            log.warn("Wiki 索引 {} 不可用: {}", indexName, ex.toString());
            return false;
        }
    }

    private TypeMapping mapping() {
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("workspace_id", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("item_id", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("version_id", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("version_no", Property.of(property -> property.integer(integer -> integer)));
        properties.put("page_kind", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("title", textProperty());
        properties.put("summary", textProperty());
        properties.put("content", textProperty());
        properties.put("outgoing_count", Property.of(property -> property.integer(integer -> integer)));
        properties.put("backlink_count", Property.of(property -> property.integer(integer -> integer)));
        properties.put("citation_count", Property.of(property -> property.integer(integer -> integer)));
        properties.put("unresolved_count", Property.of(property -> property.integer(integer -> integer)));
        properties.put("updated_at", Property.of(property -> property.long_(longProperty -> longProperty)));
        properties.put("page_embedding", Property.of(property -> property.denseVector(vector -> vector
                .dims(dimensions)
                .index(true)
                .similarity("cosine"))));
        return new TypeMapping.Builder().properties(properties).build();
    }

    private static Property textProperty() {
        return Property.of(property -> property.text(text -> text.analyzer("cjk")));
    }

    @SuppressWarnings("rawtypes")
    private SearchResult search(SearchRequest request) {
        try {
            SearchResponse<Map> response = client.search(request, Map.class);
            List<WikiPageIndex.Hit> hits = new ArrayList<>();
            if (response.hits() == null) {
                return SearchResult.ready(hits);
            }
            for (co.elastic.clients.elasticsearch.core.search.Hit<Map> hit : response.hits().hits()) {
                Map source = hit.source();
                if (source == null) {
                    continue;
                }
                String itemId = text(source.get("item_id"));
                if (itemId.isBlank()) {
                    continue;
                }
                hits.add(new WikiPageIndex.Hit(
                        itemId,
                        hit.score() == null ? 0.0d : hit.score(),
                        number(source.get("outgoing_count")),
                        number(source.get("backlink_count")),
                        number(source.get("citation_count")),
                        number(source.get("unresolved_count"))));
            }
            return SearchResult.ready(hits);
        } catch (IOException ex) {
            log.warn("Wiki 索引检索失败: {}", ex.toString());
            return SearchResult.unavailable();
        }
    }

    private static String documentId(String workspaceId, String itemId) {
        return text(workspaceId) + ":" + text(itemId);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
