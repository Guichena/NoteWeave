package com.noteweave.graph.service;

import com.noteweave.graph.dto.KnowledgeGraphEdgeResponse;
import com.noteweave.graph.dto.KnowledgeGraphNodeResponse;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
class KnowledgeGraphData {
    private Long spaceId;
    private Map<String, KnowledgeGraphNodeResponse> nodesById;
    private List<KnowledgeGraphEdgeResponse> edges;
}
