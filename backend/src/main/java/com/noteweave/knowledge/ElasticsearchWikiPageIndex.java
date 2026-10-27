package com.noteweave.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.VersionType;
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
    private final int replicas;
    /** 映射已校验通过；避免每次写入都拉一遍 mapping。 */
    private volatile boolean mappingVerified;

    @Autowired
    ElasticsearchWikiPageIndex(
            ObjectProvider<ElasticsearchClient> clientProvider,
            @Value("${noteweave.knowledge.wiki-index-name:noteweave-wiki}") String indexName,
            @Value("${noteweave.embedding.dimensions:1024}") int dimensions,
            @Value("${noteweave.elasticsearch.replicas:1}") int replicas
    ) {
        this(clientProvider.getIfAvailable(), indexName, dimensions, replicas);
    }

    ElasticsearchWikiPageIndex(ElasticsearchClient client, String indexName, int dimensions) {
        this(client, indexName, dimensions, 1);
    }

    ElasticsearchWikiPageIndex(ElasticsearchClient client, String indexName, int dimensions, int replicas) {
        this.client = client;
        this.indexName = indexName == null || indexName.isBlank() ? "noteweave-wiki" : indexName;
        this.dimensions = dimensions;
        this.replicas = Math.max(0, replicas);
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
        body.put("deleted", false);
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
        if (!ensureIndex()) {
            return false;
        }
        try {
            client.delete(request -> request.index(indexName).id(documentId(workspaceId, itemId)));
            return true;
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException ex) {
            if (isNotFound(ex)) {
                return true;
            }
            log.warn("Wiki 页面索引删除失败: {}", ex.toString());
            return false;
        } catch (IOException ex) {
            log.warn("Wiki 页面索引删除失败: {}", ex.toString());
            return false;
        }
    }

    /** 带单调版本的写入（B-07）：迟到的旧 UPSERT 被版本比较拒绝。 */
    @Override
    public boolean upsert(Document document, long version) {
        if (client == null || document == null || document.itemId() == null
                || document.itemId().isBlank() || version < 1) {
            return false;
        }
        if (!ensureIndex()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace_id", text(document.workspaceId()));
        body.put("item_id", document.itemId());
        body.put("deleted", false);
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
                    .version(version)
                    .versionType(VersionType.External)
                    .document(body));
            return true;
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException conflict) {
            if (String.valueOf(conflict.error().type()).contains("version_conflict")) {
                // 迟到的旧 UPSERT：版本更低被 ES 拒绝，按写失败返回，不覆盖新状态
                return false;
            }
            throw conflict;
        } catch (IOException ex) {
            if (ex instanceof org.elasticsearch.client.ResponseException response
                    && response.getResponse().getStatusLine().getStatusCode() == 409) {
                // 迟到的旧 UPSERT：external version 更低被 ES 拒绝（409），按写失败返回
                return false;
            }
            log.warn("Wiki 页面带版本写入失败: {}", ex.toString());
            return false;
        }
    }

    /**
     * 带版本的删除（B-07）：写入持久墓碑文档（deleted=true）而非物理删除。
     * 迟到的旧 UPSERT 因版本更低被拒绝；检索一律过滤墓碑，不依赖 TTL。
     */
    @Override
    public boolean delete(String workspaceId, String itemId, long version) {
        if (client == null || version < 1) {
            return false;
        }
        // 墓碑也是写入：先保证索引按预期映射建好，否则首次删除会用动态映射把索引建坏
        if (!ensureIndex()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace_id", text(workspaceId));
        body.put("item_id", itemId);
        body.put("deleted", true);
        try {
            client.index(request -> request
                    .index(indexName)
                    .id(documentId(workspaceId, itemId))
                    .version(version)
                    .versionType(VersionType.External)
                    .document(body));
            return true;
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException conflict) {
            if (String.valueOf(conflict.error() == null ? "" : conflict.error().type())
                    .contains("version_conflict")) {
                // 迟到的旧 UPSERT：版本更低被 ES 拒绝，按写失败返回，不覆盖新状态
                return false;
            }
            if (isNotFound(conflict)) {
                log.warn("Wiki 页面墓碑写入失败，索引已被并发删除: {}", conflict.toString());
                return false;
            }
            throw conflict;
        } catch (IOException ex) {
            if (ex instanceof org.elasticsearch.client.ResponseException response
                    && response.getResponse().getStatusLine().getStatusCode() == 409) {
                // 迟到的旧 UPSERT：external version 更低被 ES 拒绝（409），按写失败返回
                return false;
            }
            log.warn("Wiki 页面墓碑写入失败: {}", ex.toString());
            return false;
        }
    }

    /** 索引或文档已不存在：删除语义上已经达成，不当成失败。 */
    private static boolean isNotFound(co.elastic.clients.elasticsearch._types.ElasticsearchException ex) {
        String type = ex.error() == null ? "" : String.valueOf(ex.error().type());
        return ex.status() == 404
                || type.contains("index_not_found")
                || type.contains("document_missing");
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
                .filter(filter -> filter.term(term -> term.field("workspace_id").value(workspaceId)))
                // 墓碑文档不参与检索（B-07）
                .mustNot(filter -> filter.term(term -> term.field("deleted").value(true)));
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
                .filter(filter -> filter.bool(bool -> bool
                        .filter(term -> term.term(t -> t.field("workspace_id").value(workspaceId)))
                        // 墓碑文档不参与向量检索（B-07）
                        .mustNot(term -> term.term(t -> t.field("deleted").value(true)))))
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
            if (!exists) {
                client.indices().create(request -> request
                        .index(indexName)
                        .settings(settings -> settings.numberOfReplicas(String.valueOf(replicas)))
                        .mappings(mapping()));
                mappingVerified = true;
                return true;
            }
            return verifyMapping();
        } catch (Exception ex) {
            log.warn("Wiki 索引 {} 不可用: {}", indexName, ex.toString());
            return false;
        }
    }

    /**
     * IDX-03：既有索引必须校验映射再写入。动态映射建出来的索引会把 {@code page_embedding}
     * 变成普通浮点数组、{@code workspace_id} 变成分词文本、中文标题按标准分词器切分，
     * 之后向量检索直接报错、中文检索也命中不到；这种索引无法通过 putMapping 修正，只能重建。
     */
    private boolean verifyMapping() {
        if (mappingVerified) {
            return true;
        }
        try {
            var mappings = client.indices().getMapping(request -> request.index(indexName)).result();
            var metadata = mappings == null ? null : mappings.get(indexName);
            String problem = mappingProblem(metadata == null ? null : metadata.mappings(), dimensions);
            if (problem != null) {
                log.error("Wiki 索引 {} 的映射与预期不符（{}），请删除该索引后由服务重建；写入已停止",
                        indexName, problem);
                return false;
            }
            mappingVerified = true;
            return true;
        } catch (Exception ex) {
            log.warn("Wiki 索引 {} 映射校验失败: {}", indexName, ex.toString());
            return false;
        }
    }

    /** 返回映射问题描述，null 表示符合预期。 */
    static String mappingProblem(TypeMapping mapping, int dimensions) {
        if (mapping == null || mapping.properties() == null) {
            return "缺少 mapping";
        }
        Map<String, Property> properties = mapping.properties();
        Property workspace = properties.get("workspace_id");
        if (workspace == null || !workspace.isKeyword()) {
            return "workspace_id 不是 keyword";
        }
        for (String field : List.of("title", "summary", "content")) {
            Property property = properties.get(field);
            if (property == null || !property.isText()) {
                return field + " 不是 text";
            }
            String analyzer = property.text().analyzer();
            if (analyzer == null || !analyzer.toLowerCase(java.util.Locale.ROOT).contains("cjk")) {
                return field + " 未使用 cjk 分词器";
            }
        }
        Property deleted = properties.get("deleted");
        if (deleted == null || !deleted.isBoolean()) {
            return "deleted 不是 boolean";
        }
        Property embedding = properties.get("page_embedding");
        if (embedding == null || !embedding.isDenseVector()) {
            return "page_embedding 不是 dense_vector";
        }
        Integer dims = embedding.denseVector().dims();
        if (dims == null || dims != dimensions) {
            return "page_embedding 维度 " + dims + " 与配置 " + dimensions + " 不一致";
        }
        return null;
    }

    TypeMapping mapping() {
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
        properties.put("deleted", Property.of(property -> property.boolean_(bool -> bool)));
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
