package com.noteweave.chat;

import com.noteweave.chat.QaPassageRetriever.RetrievedChunk;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.retrieval.qa.ScopedPassageSearch;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 给已经写好的独立问句做范围内混合检索。
 * 调用方负责查询改写，这里不再拼对话上下文。
 */
@Component
public class HybridScopedPassageSearch implements ScopedPassageSearch {
    private final QaHybridRetriever retriever;

    public HybridScopedPassageSearch(QaHybridRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public Result search(String workspaceId, Set<String> sourceIds, String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return new Result(List.of(), false, List.of());
        }
        try {
            QaHybridRetriever.HybridResult result = retriever.retrieve(
                    workspaceId, query, sourceIds == null ? Set.of() : sourceIds);
            List<Passage> passages = result.chunks().stream()
                    .limit(limit)
                    .map(this::passage)
                    .toList();
            return new Result(passages, result.degraded(), result.degradationReasons());
        } catch (RetrievalProviderException ex) {
            return new Result(List.of(), true, List.of(text(ex.errorCode())));
        }
    }

    private Passage passage(RetrievedChunk chunk) {
        return new Passage(
                chunk.chunkId(),
                chunk.sourceId(),
                chunk.sourceSnapshotId(),
                chunk.chunkNo(),
                chunk.title(),
                chunk.locationInfo(),
                chunk.content(),
                chunk.rerankScore());
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "RETRIEVAL_PROVIDER_UNAVAILABLE" : value;
    }
}
