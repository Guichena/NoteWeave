package com.noteweave.memory;

import java.util.List;

/** 记忆自己的向量索引。检索索引的写入器不在这个接口后面。 */
public interface MemoryVectorIndex {
    boolean available();

    /** 写入失败时返回 false，不把异常抛回晋升事务。 */
    boolean upsert(Document document);

    boolean delete(String workspaceId, String memoryItemId);

    SearchResult search(String workspaceId, String taskNeighborhood, List<Float> vector, int limit);

    record Document(
            String workspaceId,
            String memoryItemId,
            String slotKey,
            String displayText,
            String taskNeighborhood,
            List<Float> embedding
    ) {
    }

    record Hit(String itemId, String text, String slot, double score) {
    }

    record SearchResult(boolean available, List<Hit> hits) {
        public SearchResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }
}
