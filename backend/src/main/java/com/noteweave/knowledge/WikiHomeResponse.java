package com.noteweave.knowledge;

import java.util.List;

public record WikiHomeResponse(
        String workspaceId,
        String wikiUrl,
        List<KnowledgeItemResponse> pages,
        List<WikiLinkResponse> links
) {
}
