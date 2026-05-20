package com.noteweave.graph.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KnowledgeGraphPathResponse {
    private Long spaceId;
    private String sourceNodeId;
    private String targetNodeId;
    private Integer length;
    private List<KnowledgeGraphNodeResponse> nodes;
    private List<KnowledgeGraphEdgeResponse> edges;
}
