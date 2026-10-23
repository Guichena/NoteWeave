package com.noteweave.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workspace_id", document.workspaceId());
        body.put("memory_item_id", document.memoryItemId());
        body.put("slot_key", document.slotKey() == null ? "" : document.slotKey());
        body.put("display_text", document.displayText() == null ? "" : document.displayText());
        body.put("task_neighborhood", document.taskNeighborhood() == null ? "" : document.taskNeighborhood());
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
        try {
            client.delete(request -> request.index(indexName)
                    .id(documentId(workspaceId, memoryItemId)));
            return true;
        } catch (IOException ex) {
            log.warn("记忆向量删除失败: {}", ex.toString());
            return false;
        }
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
            if (exists) {
                return true;
            }
            client.indices().create(request -> request
                    .index(indexName)
                    .settings(settings -> settings.numberOfReplicas(String.valueOf(replicas)))
                    .mappings(mapping()));
            return true;
        } catch (Exception ex) {
            log.warn("记忆索引 {} 不可用: {}", indexName, ex.toString());
            return false;
        }
    }

    private TypeMapping mapping() {
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("workspace_id", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("memory_item_id", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("slot_key", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("task_neighborhood", Property.of(property -> property.keyword(keyword -> keyword)));
        properties.put("display_text", Property.of(property -> property.text(text -> text)));
        properties.put("embedding", Property.of(property -> property.denseVector(vector -> vector
                .dims(dimensions)
                .index(true)
                .similarity("cosine"))));
        return new TypeMapping.Builder().properties(properties).build();
    }

    private Query scope(String workspaceId, String taskNeighborhood) {
        return Query.of(query -> query.bool(bool -> {
            bool.filter(filter -> filter.term(term -> term.field("workspace_id").value(workspaceId)));
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
