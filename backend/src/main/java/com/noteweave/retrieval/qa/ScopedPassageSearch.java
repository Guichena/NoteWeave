package com.noteweave.retrieval.qa;

import java.util.List;
import java.util.Set;

/**
 * 在给定资料范围内检索片段。
 * query 是一句独立的自然语言查询，调用方不需要再做对话改写。
 */
public interface ScopedPassageSearch {
    Result search(String workspaceId, Set<String> sourceIds, String query, int limit);

    record Passage(
            String chunkId,
            String sourceId,
            String sourceSnapshotId,
            int chunkNo,
            String title,
            String location,
            String content,
            double score
    ) {
    }

    record Result(List<Passage> passages, boolean degraded, List<String> degradationReasons) {
        public Result {
            passages = passages == null ? List.of() : List.copyOf(passages);
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        }
    }
}
