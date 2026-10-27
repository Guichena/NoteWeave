package com.noteweave.knowledge;

import java.util.List;

/** noteweave-wiki 索引。客户端不存在或调用失败时返回不可用，检索改走内存 BM25F。 */
interface WikiPageIndex {
    boolean available();

    boolean upsert(Document document);

    boolean delete(String workspaceId, String itemId);

    /** 带单调版本的写入（B-07）：external version 拒绝迟到的旧 UPSERT。 */
    default boolean upsert(Document document, long version) {
        return upsert(document);
    }

    /** 带版本的删除：写入持久墓碑文档而非物理删除，检索过滤墓碑。 */
    default boolean delete(String workspaceId, String itemId, long version) {
        return delete(workspaceId, itemId);
    }

    SearchResult keywordSearch(String workspaceId, String query, WikiLexicalBoost boost, int limit);

    SearchResult vectorSearch(String workspaceId, List<Float> vector, int limit);

    record Document(
            String workspaceId,
            String itemId,
            String versionId,
            int versionNo,
            String pageKind,
            String title,
            String summary,
            String content,
            int outgoingCount,
            int backlinkCount,
            int citationCount,
            int unresolvedCount,
            long updatedAtEpochMilli,
            List<Float> embedding
    ) {
    }

    record Hit(
            String itemId,
            double score,
            int outgoingCount,
            int backlinkCount,
            int citationCount,
            int unresolvedCount
    ) {
    }

    record SearchResult(boolean available, List<Hit> hits) {
        public SearchResult {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }

        static SearchResult unavailable() {
            return new SearchResult(false, List.of());
        }

        static SearchResult ready(List<Hit> hits) {
            return new SearchResult(true, hits);
        }
    }
}
