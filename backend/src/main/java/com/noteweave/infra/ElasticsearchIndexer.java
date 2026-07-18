package com.noteweave.infra;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.task.TaskService;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.common.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 写侧投影器。
 * <p>
 * 索引模式：{@code <prefix>_<workspaceId>}，每个工作台一个独立索引。
 * 写入由 {@code noteweave.source.index} Kafka topic 触发；QA 读侧由
 * {@link ElasticsearchChunkSearchAdapter} 独立承接。
 */
@Component
@ConditionalOnProperty(name = "noteweave.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexer.class);

    private final boolean enabled;
    private final ElasticsearchClient client;
    private final NoteWeaveProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TaskService taskService;
    private final MeterRegistry meterRegistry;
    private final SourceCatalogVersionService sourceCatalogVersionService;

    public ElasticsearchIndexer(ElasticsearchClient client, NoteWeaveProperties properties, ObjectMapper objectMapper,
                                JdbcTemplate jdbcTemplate, TaskService taskService) {
        this(client, properties, objectMapper, jdbcTemplate, taskService,
                new SimpleMeterRegistry(), new SourceCatalogVersionService(jdbcTemplate));
    }

    public ElasticsearchIndexer(ElasticsearchClient client, NoteWeaveProperties properties, ObjectMapper objectMapper,
                                JdbcTemplate jdbcTemplate, TaskService taskService, MeterRegistry meterRegistry) {
        this(client, properties, objectMapper, jdbcTemplate, taskService,
                meterRegistry, new SourceCatalogVersionService(jdbcTemplate));
    }

    @Autowired
    public ElasticsearchIndexer(ElasticsearchClient client, NoteWeaveProperties properties, ObjectMapper objectMapper,
                                JdbcTemplate jdbcTemplate, TaskService taskService, MeterRegistry meterRegistry,
                                SourceCatalogVersionService sourceCatalogVersionService) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.taskService = taskService;
        this.meterRegistry = meterRegistry;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
        this.enabled = properties.elasticsearch().enabled();
    }

    @PostConstruct
    void verifyConnection() {
        if (!enabled) {
            log.info("Elasticsearch disabled by noteweave.elasticsearch.enabled=false; QA retrieval will use MySQL fallback.");
            return;
        }
        try {
            var info = client.info();
            log.info("Elasticsearch connected: {} v{}", info.clusterName(), info.version().number());
        } catch (Exception ex) {
            log.warn("Elasticsearch unreachable at startup: {}. Index pipeline will degrade gracefully.", ex.getMessage());
        }
    }

    /**
     * 监听 source.index Kafka 事件，把 chunk 索引到 ES。
     */
    @KafkaListener(topics = "${noteweave.kafka.topics.source-index}",
                   groupId = "${noteweave.elasticsearch.consumer-group:noteweave-es-projector}",
                   containerFactory = "kafkaListenerContainerFactory",
                   autoStartup = "${noteweave.elasticsearch.enabled:true}")
    public void onSourceIndexEvent(ConsumerRecord<String, String> record) {
        String eventId = record.topic() + "-" + record.partition() + "-" + record.offset();
        MDC.put(RequestContext.EVENT_ID, eventId);
        MDC.put(RequestContext.CORRELATION_ID, eventId);
        try {
            onSourceIndexEventInternal(record);
        } finally {
            MDC.clear();
        }
    }

    private void onSourceIndexEventInternal(ConsumerRecord<String, String> record) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(record.value(), new TypeReference<>() {
            });
            String workspaceId = requiredText(payload, "workspaceId");
            String chunkId = requiredText(payload, "chunkId");
            String sourceId = requiredText(payload, "sourceId");
            String sourceSnapshotId = requiredText(payload, "sourceSnapshotId");
            putContext(payload, RequestContext.WORKSPACE_ID, "workspaceId");
            putContext(payload, RequestContext.TASK_ID, "taskId");
            putContext(payload, RequestContext.SOURCE_ID, "sourceId");
            int chunkNo = requiredInt(payload, "chunkNo");
            String title = requiredText(payload, "title");
            String sourceType = requiredText(payload, "sourceType");
            String content = requiredText(payload, "content");
            if (!isProjectionActive(workspaceId, chunkId, sourceId, sourceSnapshotId)) {
                log.info("Skip stale source projection: sourceId={}, snapshotId={}, chunkId={}",
                        sourceId, sourceSnapshotId, chunkId);
                return;
            }
            indexChunk(workspaceId, chunkId, sourceId, sourceSnapshotId, chunkNo, title, sourceType, content);
            acknowledgeProjection(workspaceId, chunkId, sourceId, sourceSnapshotId, payload.get("taskId"));
        } catch (Exception ex) {
            meterRegistry.counter("noteweave.elasticsearch.projection.failure").increment();
            log.error("ES index failed for record key {}: {}", record.key(), ex.getMessage(), ex);
            throw new IllegalStateException("ES source.index projection failed", ex);
        }
    }

    private void putContext(Map<String, Object> payload, String mdcKey, String payloadKey) {
        Object value = payload.get(payloadKey);
        if (value != null && !String.valueOf(value).isBlank()) {
            MDC.put(mdcKey, String.valueOf(value));
        }
    }

    private void acknowledgeProjection(
            String workspaceId,
            String chunkId,
            String sourceId,
            String snapshotId,
            Object taskId
    ) {
        int projected = jdbcTemplate.update("""
                update source_chunk
                set projection_status = 'PROJECTED', projected_at = current_timestamp
                where id = ? and workspace_id = ? and source_id = ? and source_snapshot_id = ?
                  and projection_status <> 'PROJECTED'
                """, chunkId, workspaceId, sourceId, snapshotId);
        if (projected == 0) {
            return;
        }
        Integer remaining = jdbcTemplate.queryForObject("""
                select count(*) from source_chunk where source_snapshot_id = ? and projection_status <> 'PROJECTED'
                """, Integer.class, snapshotId);
        if (remaining != null && remaining == 0) {
            int sourceReady = jdbcTemplate.update("""
                    update source
                    set index_status = 'INDEXED', status = 'READY',
                        updated_by = 'SYSTEM:ELASTICSEARCH', updated_at = current_timestamp
                    where id = ? and status = 'PROCESSING' and index_status = 'INDEXING'
                    """, sourceId);
            int snapshotReady = jdbcTemplate.update("""
                    update source_snapshot set index_status = 'INDEXED'
                    where id = ? and index_status = 'INDEXING'
                    """, snapshotId);
            if (sourceReady == 0 || snapshotReady == 0) {
                return;
            }
            sourceCatalogVersionService.bump(workspaceId);
            if (taskId != null) {
                taskService.completeTask(String.valueOf(taskId), "INDEXED", "资料解析与 Elasticsearch 投影已完成", sourceId);
            }
        }
    }

    private boolean isProjectionActive(String workspaceId, String chunkId, String sourceId, String snapshotId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from source_chunk c
                join source s on s.id = c.source_id and s.workspace_id = c.workspace_id
                join source_snapshot ss on ss.id = c.source_snapshot_id and ss.source_id = s.id
                where c.id = ? and c.workspace_id = ? and c.source_id = ? and c.source_snapshot_id = ?
                  and c.projection_status <> 'PROJECTED'
                  and s.status <> 'DELETED' and ss.index_status <> 'DELETED'
                """, Integer.class, chunkId, workspaceId, sourceId, snapshotId);
        return count != null && count == 1;
    }

    public void indexChunk(String workspaceId, String chunkId, String sourceId, String sourceSnapshotId,
                           int chunkNo, String title, String sourceType, String content) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("workspace_id", workspaceId);
            doc.put("chunk_id", chunkId);
            doc.put("source_id", sourceId);
            doc.put("source_snapshot_id", sourceSnapshotId);
            doc.put("chunk_no", chunkNo);
            doc.put("title", title);
            doc.put("source_type", sourceType);
            doc.put("content", content);
            IndexResponse response = client.index(i -> i
                    .index(indexName(workspaceId))
                    .id(chunkId)
                    .document(doc));
            log.debug("ES indexed chunkId={} result={}", chunkId, response.result());
        } catch (IOException ex) {
            throw new IllegalStateException("ES index chunk failed: " + chunkId, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("ES index chunk failed: " + chunkId, ex);
        }
    }

    public void deleteBySourceId(String workspaceId, String sourceId) {
        if (!enabled) {
            return;
        }
        try {
            client.deleteByQuery(d -> d
                    .index(indexName(workspaceId))
                    .query(q -> q.term(t -> t.field("source_id").value(sourceId)))
                    .refresh(true));
            log.info("ES deleted chunks for sourceId={}", sourceId);
        } catch (Exception ex) {
            log.error("ES delete by source failed: {}", ex.getMessage());
        }
    }

    public String indexName(String workspaceId) {
        return (properties.elasticsearch().indexPrefix() + "_" + workspaceId).toLowerCase();
    }

    private String requiredText(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("source.index missing required field: " + field);
        }
        return String.valueOf(value);
    }

    private int requiredInt(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(requiredText(payload, field));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("source.index invalid integer field: " + field, ex);
        }
    }

    private String readField(String json, String field) {
        if (json == null) {
            return null;
        }
        // 简化：直接用 Jackson 转 Map 读取
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {
            });
            Object value = map.get(field);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("source.index payload parse failed", ex);
        }
    }

}
