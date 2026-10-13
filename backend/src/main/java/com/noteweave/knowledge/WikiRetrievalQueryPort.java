package com.noteweave.knowledge;

import java.util.List;

public interface WikiRetrievalQueryPort {
    List<WikiPageContext> findRelevantWikiPageContexts(String workspaceId, String query);

    default List<WikiPageContext> findRelevantWikiPageContexts(
            String workspaceId,
            String query,
            WikiLexicalBoost boost
    ) {
        return findRelevantWikiPageContexts(workspaceId, query);
    }

    List<String> citationIdsForWikiPages(String workspaceId, List<KnowledgePageHit> pages);

    /** 取走最近一次 Wiki 检索的降级原因。没有检索过时返回空列表。 */
    default List<String> consumeWikiDegradationReasons() {
        return List.of();
    }
}
