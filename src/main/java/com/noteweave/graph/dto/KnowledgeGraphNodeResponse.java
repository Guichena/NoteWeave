package com.noteweave.graph.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(toBuilder = true)
public class KnowledgeGraphNodeResponse {
    private String id;
    private Long refId;
    private Long spaceId;
    private KnowledgeGraphNodeType type;
    private String title;
    private String subtitle;
    private String status;
    private String indexStatus;
    private boolean root;
}
