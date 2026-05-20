package com.noteweave.graph.dto;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KnowledgeGraphNodeDetailResponse {
    private String id;
    private Long refId;
    private Long spaceId;
    private KnowledgeGraphNodeType type;
    private String title;
    private String subtitle;
    private String status;
    private Map<String, Object> attributes;
    private List<KnowledgeGraphEdgeResponse> adjacentEdges;
}
