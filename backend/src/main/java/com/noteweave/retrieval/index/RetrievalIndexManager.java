package com.noteweave.retrieval.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RetrievalIndexManager {
    static final String TEXT_ANALYZER = "noteweave_cjk_text";
    private final ElasticsearchClient client;

    @Autowired
    public RetrievalIndexManager(ObjectProvider<ElasticsearchClient> clientProvider) {
        this.client = clientProvider.getIfAvailable();
    }

    private RetrievalIndexManager(ElasticsearchClient client) {
        this.client = client;
    }

    public static RetrievalIndexManager withClient(ElasticsearchClient client) {
        return new RetrievalIndexManager(client);
    }

    public void createIndex(
            ProjectionType type,
            String physicalIndex,
            int embeddingDimensions
    ) {
        if (client == null) {
            return;
        }
        if (embeddingDimensions <= 0) {
            throw new IllegalArgumentException("Embedding dimensions must be positive");
        }
        try {
            boolean exists = client.indices().exists(e -> e.index(physicalIndex)).value();
            if (exists) {
                return;
            }
            client.indices().create(c -> c
                    .index(physicalIndex)
                    .settings(settings())
                    .mappings(mapping(type, embeddingDimensions)));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create retrieval index " + physicalIndex, ex);
        }
    }

    public void switchAlias(String alias, String fromIndex, String targetIndex) {
        if (client == null) {
            return;
        }
        try {
            client.indices().updateAliases(update -> {
                if (fromIndex != null && !fromIndex.isBlank()) {
                    update.actions(action -> action.remove(remove -> remove
                            .alias(alias)
                            .index(fromIndex)));
                } else {
                    update.actions(action -> action.remove(remove -> remove
                            .alias(alias)
                            .index("*")
                            .mustExist(false)));
                }
                return update.actions(action -> action.add(add -> add
                        .alias(alias)
                        .index(targetIndex)
                        .isWriteIndex(true)));
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to switch retrieval alias " + alias, ex);
        }
    }

    public void ensureAlias(String alias, String targetIndex) {
        if (client == null) {
            return;
        }
        try {
            if (client.indices().existsAlias(exists -> exists.name(alias)).value()) {
                return;
            }
            client.indices().updateAliases(update -> update.actions(action -> action.add(add -> add
                    .alias(alias)
                    .index(targetIndex)
                    .isWriteIndex(true))));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize retrieval alias " + alias, ex);
        }
    }

    public String resolveWriteIndex(String alias, String defaultIndex) {
        if (client == null) {
            return defaultIndex;
        }
        try {
            if (!client.indices().existsAlias(exists -> exists.name(alias)).value()) {
                return defaultIndex;
            }
            var aliases = client.indices().getAlias(get -> get.name(alias)).result();
            return aliases.entrySet().stream()
                    .filter(entry -> {
                        var metadata = entry.getValue().aliases().get(alias);
                        return metadata != null && Objects.equals(Boolean.TRUE, metadata.isWriteIndex());
                    })
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseGet(() -> aliases.size() == 1
                            ? aliases.keySet().iterator().next()
                            : defaultIndex);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to resolve retrieval alias " + alias, ex);
        }
    }

    public void switchAliases(Map<String, String> targetsByAlias) {
        if (targetsByAlias == null || targetsByAlias.isEmpty()) {
            throw new IllegalArgumentException("At least one retrieval alias target is required");
        }
        if (client == null) {
            return;
        }
        try {
            client.indices().updateAliases(update -> {
                for (Map.Entry<String, String> entry : targetsByAlias.entrySet()) {
                    update.actions(action -> action.remove(remove -> remove
                            .alias(entry.getKey()).index("*").mustExist(false)));
                    update.actions(action -> action.add(add -> add
                            .alias(entry.getKey()).index(entry.getValue()).isWriteIndex(true)));
                }
                return update;
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to atomically switch retrieval aliases", ex);
        }
    }

    /**
     * 文本字段的分析器。默认的 standard 分析器把中文切成单字，BM25 只能按单字匹配，
     * "缓存"和"存储"会因为共享"存"字而相互命中。这里改用 ES 内置的 CJK 二元组：
     * 中日韩文字按相邻两字切分，英文和数字仍按单词切分并转小写，cjk_width 把全角字母数字归一成半角。
     * 内置过滤器不需要额外安装分词插件。
     */
    IndexSettings settings() {
        return IndexSettings.of(settings -> settings.analysis(analysis -> analysis
                .analyzer(TEXT_ANALYZER, analyzer -> analyzer.custom(custom -> custom
                        .tokenizer("standard")
                        .filter("cjk_width", "lowercase", "cjk_bigram")))));
    }

    TypeMapping mapping(ProjectionType type, int dimensions) {
        Map<String, Property> properties = commonProperties(dimensions);
        if (type == ProjectionType.QA_CHUNK) {
            properties.put("chunk_id", keyword());
            properties.put("chunk_no", integer());
            properties.put("heading", text());
            properties.put("content", text());
            properties.put("content_embedding", denseVector(dimensions));
        } else {
            properties.put("summary", text());
            properties.put("tags", keyword());
            properties.put("metadata_text", text());
            properties.put("section_descriptions", text());
            properties.put("catalog_ids", keyword());
            properties.put("chunk_count", integer());
            properties.put("window_count", integer());
            properties.put("source_embedding", denseVector(dimensions));
        }
        return new TypeMapping.Builder().properties(properties).build();
    }

    private Map<String, Property> commonProperties(int dimensions) {
        Map<String, Property> properties = new LinkedHashMap<>();
        properties.put("workspace_id", keyword());
        properties.put("source_id", keyword());
        properties.put("source_snapshot_id", keyword());
        properties.put("title", text());
        properties.put("source_type", keyword());
        properties.put("embedding_text_hash", keyword());
        properties.put("embedding_model", keyword());
        properties.put("embedding_dimensions", integer());
        properties.put("embedding_version", keyword());
        properties.put("projection_version", keyword());
        properties.put("is_current_snapshot", bool());
        return properties;
    }

    private Property denseVector(int dimensions) {
        return Property.of(property -> property.denseVector(vector -> vector
                .dims(dimensions)
                .index(true)
                .similarity("cosine")));
    }

    private Property keyword() {
        return Property.of(property -> property.keyword(keyword -> keyword));
    }

    private Property text() {
        return Property.of(property -> property.text(text -> text.analyzer(TEXT_ANALYZER)));
    }

    private Property integer() {
        return Property.of(property -> property.integer(integer -> integer));
    }

    private Property bool() {
        return Property.of(property -> property.boolean_(bool -> bool));
    }
}
