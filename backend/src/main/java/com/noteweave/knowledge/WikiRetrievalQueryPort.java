package com.noteweave.knowledge;

import java.util.List;
import java.util.Map;

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

    /** 读取这批页面触达的已解析链接，含出链和入链。 */
    default List<WikiResolvedLink> loadResolvedWikiLinks(String workspaceId, List<String> itemIds) {
        return List.of();
    }

    /** 这批页面在全工作台已解析链接中的入度。 */
    default Map<String, Integer> loadWikiInDegrees(String workspaceId, List<String> itemIds) {
        return Map.of();
    }

    /** 按页面 id 回填当前 ACTIVE 版本。不可读的页面不返回。 */
    default List<WikiPageContext> loadWikiPagesByIds(String workspaceId, List<String> itemIds) {
        return List.of();
    }
}
