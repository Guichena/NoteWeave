package com.noteweave.retrieval.projection;

import com.noteweave.retrieval.index.RetrievalEmbeddingVersionSource;
import org.springframework.stereotype.Component;

/**
 * 用投影服务的批次向量版本作为检索侧的当前版本（IDX-02）：
 * 文档里的 {@code embedding_version} 就是它写进去的，查询过滤用同一个值才不会漂移。
 */
@Component
public class ProjectionEmbeddingVersionSource implements RetrievalEmbeddingVersionSource {

    private final SourceRetrievalProjectionService projectionService;

    public ProjectionEmbeddingVersionSource(SourceRetrievalProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @Override
    public String currentEmbeddingVersion() {
        return projectionService.currentBatchEmbeddingVersion();
    }
}
