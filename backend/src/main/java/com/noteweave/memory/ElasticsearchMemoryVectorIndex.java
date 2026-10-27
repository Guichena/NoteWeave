package com.noteweave.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
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

/**
 * 记忆向量索引 noteweave-memory。
 * 客户端不存在或调用失败时只记录日志并返回不可用，不影响记忆晋升。
 */
@Component
public class ElasticsearchMemoryVectorIndex implements MemoryVectorIndex {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchMemoryVectorIndex.class);

    private final ElasticsearchClient client;
    private final String indexName;
    private final int dimensions;
    private final int replicas;
    /** 映射已校验通过；避免每次写入都拉一遍 mapping。 */
    private volatile boolean mappingVerified;

    @Autowired
    public ElasticsearchMemoryVectorIndex(
            ObjectProvider<ElasticsearchClient> clientProvider,
            @Value("${noteweave.memory.index-name:noteweave-memory}") String indexName,
            @Value("${noteweave.embedding.dimensions:1024}") int dimensions,
            @Value("${noteweave.elasticsearch.replicas:1}") int replicas
    ) {
        this(clientProvider.getIfAvailable(), indexName, dimensions, replicas);
    }

    ElasticsearchMemoryVectorIndex(ElasticsearchClient client, String indexName, int dimensions) {
        this(client, indexName, dimensions, 1);
    }

    ElasticsearchMemoryVectorIndex(ElasticsearchClient client, String indexName, int dimensions, int replicas) {
        this.client = client;
        this.indexName = indexName == null || indexName.isBlank() ? "noteweave-memory" : indexName;
        this.dimensions = dimensions;
        this.replicas = Math.max(0, replicas);
    }

    @Override
    public boolean available() {
        return client != null;
    }

    @Override
    public boolean upsert(Document document) {
        if (client == null || document == null || document.embedding() == null
                || document.embedding().size() != dimensions) {
            return false;
        }
        if (!ensureIndex()) {
            return false;
        }
        Map<String, Object> body = baseBody(document.workspaceId(), document.memoryItemId(),
                document.slotKey(), document.displayText(), document.taskNeighborhood());
        body.put("embedding", document.embedding());
        try {
            client.index(request -> request
                    .index(indexName)
                    .id(documentId(document.workspaceId(), document.memoryItemId()))
                    .document(body));
            return true;
        } catch (IOException ex) {
            log.warn("记忆向量写入失败: {}", ex.toString());
            return false;
        }
    }

    @Override
    public boolean delete(String workspaceId, String memoryItemId) {
        if (client == null) {
            return false;
        }
        if (!ensureIndex()) {
            return false;
        }
        try {
            client.delete(request -> request.index(indexName)
                    .id(documentId(workspaceId, memoryItemId)));
            return true;
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException ex) {
            if (isNotFound(ex)) {
                return true;
            }
            log.warn("记忆向量删除失败: {}", ex.toString());
            return false;
        } catch (IOException ex) {
            log.warn("记忆向量删除失败: {}", ex.toString());
            return false;
        }
    }

    /**
     * 带单调版本的写入（B-07）：external version 让迟到的旧 UPSERT 被版本比较拒绝，
     * 不能覆盖更新的删除或更新。
     */
    @Override
    public boolean upsert(Document document, long version) {
        if (client == null || document == null || document.embedding() == null
                || document.embedding().size() != dimensions || version < 1) {
            return false;
        }
        if (!ensureIndex()) {
            return false;
        }
        Map<String, Object> body = baseBody(document.workspaceId(), document.memoryItemId(),
                document.slotKey(), document.displayText(), document.taskNeighborhood());
        body.put("embedding", document.embedding());
        try {
            client.index(request -> request
                    .index(indexName)
                    .id(documentId(document.workspaceId(), document.memoryItemId()))
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
            throw conflict;
        } catch (IOException ex) {
            if (ex instanceof org.elasticsearch.client.ResponseException response
                    && response.getResponse().getStatusLine().getStatusCode() == 409) {
                // 迟到的旧 UPSERT：external version 更低被 ES 拒绝（409），按写失败返回
                return false;
            }
            log.warn("记忆向量带版本写入失败: {}", ex.toString());
            return false;
        }
    }

    /**
     * 带版本的删除（B-07）：写入持久墓碑文档（deleted=true）而不是物理删除。
     * 墓碑不依赖 TTL：迟到的旧 UPSERT 版本更低被拒绝；墓碑因段合并消失后，
     * 复活写入也只能来自数据库再次授予的更高版本。检索一律过滤墓碑。
     */
    @Override
    public boolean delete(String workspaceId, String memoryItemId, long version) {
        if (client == null || version < 1) {
            return false;
        }
        // 墓碑也是写入：先保证索引按预期映射建好，否则首次删除会用动态映射把索引建坏
        if (!ensureIndex()) {
            return false;
        }
        Map<String, Object> body = baseBody(workspaceId, memoryItemId, "", "", "");
        body.put("deleted", true);
        try {
            client.index(request -> request
                    .index(indexName)
                    .id(documentId(workspaceId, memoryItemId))
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
                log.warn("记忆向量墓碑写入失败，索引已被并发删除: {}", conflict.toString());
                return false;
            }
            throw conflict;
        } catch (IOException ex) {
            if (ex instanceof org.elasticsearch.client.ResponseException response
                    && response.getResponse().getStatusLine().getStatusCode() == 409) {
                // 迟到的旧 UPSERT：external version 更低被 ES 拒绝（409），按写失败返回
                return false;
            }
            log.warn("记忆向量墓碑写入失败: {}", ex.toString());
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

    private Map<String, Object> baseBody(String workspaceId, String memoryItemId,
            String slotKey, String displayText, String taskNeighborhood) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace_id", workspaceId);
        body.put("memory_item_id", memoryItemId);
        body.put("slot_key", slotKey == null ? "" : slotKey);
        body.put("display_text", displayText == null ? "" : displayText);
        body.put("task_neighborhood", taskNeighborhood == null ? "" : taskNeighborhood);
        body.put("deleted", false);
        return body;
    }

    @Override
    public SearchResult search(String workspaceId, String taskNeighborhood, List<Float> vector, int limit) {
        if (client == null || vector == null || vector.size() != dimensions || limit <= 0) {
            return new SearchResult(false, List.of());
        }
        if (!ensureIndex()) {
            return new SearchResult(false, List.of());
        }
        int size = Math.min(limit, 5);
        KnnSearch knn = new KnnSearch.Builder()
                .field("embedding")
                .queryVector(vector)
                .k(size)
                .numCandidates(Math.max(50, size * 5))
                .filter(scope(workspaceId, taskNeighborhood))
                .build();
        try {
            return new SearchResult(true, read(client.search(new SearchRequest.Builder()
                    .index(indexName)
                    .size(size)
                    .knn(knn)
                    .build(), Map.class)));
        } catch (IOException ex) {
            log.warn("记忆向量检索失败: {}", ex.toString());
            return new SearchResult(false, List.of());
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
            log.warn("记忆索引 {} 不可用: {}", indexName, ex.toString());
            return false;
        }
    }

    /** 动态映射建出来的索引没有 dense_vector，向量检索会直接失败，必须校验后再写。 */
    private boolean verifyMapping() {
        if (mappingVerified) {
            return true;
        }
        try {
            var mappings = client.indices().getMapping(request -> request.index(indexName)).result();
            var metadata = mappings == null ? null : mappings.get(indexName);
            String problem = mappingProblem(metadata == null ? null : metadata.mappings(), dimensions);
            if (problem != null) {
                log.error("记忆索引 {} 的映射与预期不符（{}），请删除该索引后由服务重建；写入已停止",
                        indexName, problem);
                return false;
            }
            mappingVerified = true;
            return true;
        } catch (Exception ex) {
            log.warn("记忆索引 {} 映射校验失败: {}", indexName, ex.toString());
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
        Property deleted = properties.get("deleted");
        if (deleted == null || !deleted.isBoolean()) {
            return "deleted 不是 boolean";
        }
        Property embedding = properties.get("embedding");
        if (embedding == null || !embedding.isDenseVector()) {
            return "embedding 不是 dense_vector";
        }
        Integer dims = embedding.denseVector().dims();
        if (dims == null || dims != dimensions) {
            return "embedding 维度 " + dims + " 与配置 " + dimensions + " 不一致";
        }
        return null;
    }

    TypeMapping mapping() {
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("workspace_id", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("memory_item_id", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("slot_key", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("task_neighborhood", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("display_text", Property.of(property -> property.text(text -> text)));
        properties.put("deleted", Property.of(property -> property.boolean_(bool -> bool)));
        properties.put("embedding", Property.of(property -> property.denseVector(vector -> vector
                .dims(dimensions)
                .index(true)
                .similarity("cosine"))));
        return new TypeMapping.Builder().properties(properties).build();
    }

    private Query scope(String workspaceId, String taskNeighborhood) {
        return Query.of(query -> query.bool(bool -> {
            bool.filter(filter -> filter.term(term -> term.field("workspace_id").value(workspaceId)));
            // 墓碑文档不参与检索（B-07）
            bool.mustNot(filter -> filter.term(term -> term.field("deleted").value(true)));
            if (taskNeighborhood != null && !taskNeighborhood.isBlank()) {
                bool.filter(filter -> filter.term(term -> term
                        .field("task_neighborhood").value(taskNeighborhood)));
            }
            return bool;
        }));
    }

    @SuppressWarnings("rawtypes")
    private List<MemoryVectorIndex.Hit> read(SearchResponse<Map> response) {
        List<MemoryVectorIndex.Hit> hits = new ArrayList<>();
        if (response.hits() == null) {
            return hits;
        }
        for (co.elastic.clients.elasticsearch.core.search.Hit<Map> hit : response.hits().hits()) {
            Map source = hit.source();
            if (source == null) {
                continue;
            }
            String itemId = text(source.get("memory_item_id"));
            if (itemId.isBlank()) {
                continue;
            }
            double score = hit.score() == null ? 0 : hit.score();
            hits.add(new MemoryVectorIndex.Hit(
                    itemId, text(source.get("display_text")), text(source.get("slot_key")), score));
        }
        return hits;
    }

    private static String documentId(String workspaceId, String memoryItemId) {
        return workspaceId + ":" + memoryItemId;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
