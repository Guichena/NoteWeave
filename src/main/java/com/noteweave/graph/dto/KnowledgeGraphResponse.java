package com.noteweave.graph.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KnowledgeGraphResponse {
    private Long spaceId;
    private String rootNodeId;
    private Integer nodeCount;
    private Integer edgeCount;
    private List<KnowledgeGraphNodeResponse> nodes;
    private List<KnowledgeGraphEdgeResponse> edges;
}
