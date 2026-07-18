package com.noteweave.knowledge;

import java.util.List;

public interface WikiRetrievalQueryPort {
    List<WikiPageContext> findRelevantWikiPageContexts(String workspaceId, String query);

    List<String> citationIdsForWikiPages(List<KnowledgePageHit> pages);
}
