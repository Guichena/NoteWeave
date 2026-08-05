package com.noteweave.retrieval.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import com.noteweave.retrieval.index.RetrievalProjectionWriter.NoteSourceDocument;
import com.noteweave.retrieval.index.RetrievalProjectionWriter.QaChunkDocument;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "noteweave.elasticsearch.enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchRetrievalProjectionWriter implements RetrievalProjectionWriter {
    private final ElasticsearchClient client;

    public ElasticsearchRetrievalProjectionWriter(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void writeQaChunk(String targetIndex, QaChunkDocument document) {
        verifyVector(document.contentEmbedding(), document.embeddingDimensions());
        index(targetIndex, document.chunkId(), qaDocument(document));
    }

    @Override
    public void writeNoteSource(String targetIndex, NoteSourceDocument document) {
        verifyVector(document.sourceEmbedding(), document.embeddingDimensions());
        index(targetIndex, document.sourceId(), noteDocument(document));
    }

    @Override
    public void markSnapshotNotCurrent(String targetIndex, String sourceSnapshotId) {
        updateSnapshotCurrentFlag(targetIndex, sourceSnapshotId, false, false);
    }

    @Override
    public void markSnapshotCurrent(String targetIndex, String sourceSnapshotId) {
        updateSnapshotCurrentFlag(targetIndex, sourceSnapshotId, true, true);
    }

    @Override
    public void deleteSource(String targetIndex, String sourceId) {
        try {
            client.deleteByQuery(delete -> delete
                    .index(targetIndex)
                    .allowNoIndices(true)
                    .ignoreUnavailable(true)
                    .query(query -> query.term(term -> term
                            .field("source_id")
                            .value(sourceId)))
                    .refresh(true));
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to delete retrieval projections for source " + sourceId, ex);
        }
    }

    private void updateSnapshotCurrentFlag(
            String targetIndex,
            String sourceSnapshotId,
            boolean current,
            boolean requireMatch
    ) {
        try {
            client.indices().refresh(refresh -> refresh.index(targetIndex));
            UpdateByQueryResponse response = client.updateByQuery(update -> update
                    .index(targetIndex)
                    .query(query -> query.term(term -> term
                            .field("source_snapshot_id")
                            .value(sourceSnapshotId)))
                    .script(script -> script
                            .lang("painless")
                            .source("ctx._source.is_current_snapshot = params.current")
                            .params("current", co.elastic.clients.json.JsonData.of(current)))
                    .refresh(true));
            if (requireMatch && response.updated() == 0) {
                throw new IllegalStateException(
                        "No retrieval projection found for snapshot activation " + sourceSnapshotId);
            }
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Failed to update retrieval snapshot activation " + sourceSnapshotId, ex);
        }
    }

    Map<String, Object> qaDocument(QaChunkDocument document) {
        Map<String, Object> map = commonDocument(
                document.workspaceId(), document.sourceId(), document.sourceSnapshotId(),
                document.title(), document.sourceType(), document.embeddingTextHash(),
                document.embeddingModel(), document.embeddingDimensions(),
                document.embeddingVersion(), document.projectionVersion(), document.currentSnapshot());
        map.put("chunk_id", document.chunkId());
        map.put("chunk_no", document.chunkNo());
        map.put("heading", text(document.heading()));
        map.put("content", text(document.content()));
        map.put("content_embedding", document.contentEmbedding());
        return map;
    }

    Map<String, Object> noteDocument(NoteSourceDocument document) {
        Map<String, Object> map = commonDocument(
                document.workspaceId(), document.sourceId(), document.sourceSnapshotId(),
                document.title(), document.sourceType(), document.embeddingTextHash(),
                document.embeddingModel(), document.embeddingDimensions(),
                document.embeddingVersion(), document.projectionVersion(), document.currentSnapshot());
        map.put("summary", text(document.summary()));
        map.put("tags", document.tags());
        map.put("metadata_text", text(document.metadataText()));
        map.put("section_descriptions", document.sectionDescriptions());
        map.put("catalog_ids", document.catalogIds());
        map.put("chunk_count", document.chunkCount());
        map.put("window_count", document.windowCount());
        map.put("source_embedding", document.sourceEmbedding());
        return map;
    }

    private Map<String, Object> commonDocument(
            String workspaceId,
            String sourceId,
            String sourceSnapshotId,
            String title,
            String sourceType,
            String embeddingTextHash,
            String embeddingModel,
            int embeddingDimensions,
            String embeddingVersion,
            String projectionVersion,
            boolean currentSnapshot
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workspace_id", workspaceId);
        map.put("source_id", sourceId);
        map.put("source_snapshot_id", sourceSnapshotId);
        map.put("title", text(title));
        map.put("source_type", text(sourceType));
        map.put("embedding_text_hash", embeddingTextHash);
        map.put("embedding_model", embeddingModel);
        map.put("embedding_dimensions", embeddingDimensions);
        map.put("embedding_version", embeddingVersion);
        map.put("projection_version", projectionVersion);
        map.put("is_current_snapshot", currentSnapshot);
        return map;
    }

    private void index(String targetIndex, String id, Map<String, Object> document) {
        try {
            client.index(index -> index.index(targetIndex).id(id).document(document));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write retrieval projection " + id, ex);
        }
    }

    private void verifyVector(java.util.List<Float> vector, int dimensions) {
        if (dimensions <= 0 || vector == null || vector.size() != dimensions) {
            throw new IllegalArgumentException(
                    "Projection vector dimensions do not match configured dimensions");
        }
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
