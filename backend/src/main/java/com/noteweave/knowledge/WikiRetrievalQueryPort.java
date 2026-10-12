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
}
