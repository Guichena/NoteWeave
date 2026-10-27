package com.noteweave.memory;

import java.util.List;

/** 记忆自己的向量索引。检索索引的写入器不在这个接口后面。 */
public interface MemoryVectorIndex {
    boolean available();

    /** 写入失败时返回 false，不把异常抛回晋升事务。 */
    boolean upsert(Document document);

    boolean delete(String workspaceId, String memoryItemId);

    /**
     * 带单调版本的写入（B-07）：version 来自条目级 index_generation，使用 ES external version。
     * 迟到的旧 UPSERT 因版本更低被拒绝，不能覆盖更新的删除。默认实现退回无版本写入，
     * 仅供不参与多实例竞态的测试桩使用。
     */
    default boolean upsert(Document document, long version) {
        return upsert(document);
    }

    /** 带版本的删除：写入持久墓碑文档（deleted=true）而非物理删除，检索过滤墓碑。 */
    default boolean delete(String workspaceId, String memoryItemId, long version) {
        return delete(workspaceId, memoryItemId);
    }

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
