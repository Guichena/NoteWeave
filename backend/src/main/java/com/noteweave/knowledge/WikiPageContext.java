package com.noteweave.knowledge;

import java.util.List;

public record WikiPageContext(
        KnowledgePageHit page,
        List<WikiLinkResponse> outgoingLinks,
        List<WikiLinkResponse> backlinks,
        List<KnowledgeCitationResponse> citations
) {
    public WikiPageContext {
        outgoingLinks = outgoingLinks == null ? List.of() : List.copyOf(outgoingLinks);
        backlinks = backlinks == null ? List.of() : List.copyOf(backlinks);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
