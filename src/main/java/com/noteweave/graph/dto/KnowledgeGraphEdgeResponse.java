package com.noteweave.graph.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KnowledgeGraphEdgeResponse {
    private String sourceId;
    private String targetId;
    private KnowledgeGraphEdgeType type;
    private String label;
    private Integer weight;
}
