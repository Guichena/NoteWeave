package com.noteweave.knowledge;

import java.util.List;

public record WikiGraphResponse(
        String workspaceId,
        List<WikiGraphNode> nodes,
        List<WikiGraphEdge> edges,
        WikiGraphMetaResponse meta
) {
}
