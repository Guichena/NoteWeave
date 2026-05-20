package com.noteweave.graph.dto;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class KnowledgeGraphFilterRequest {
    private Set<KnowledgeGraphNodeType> nodeTypes = new LinkedHashSet<>();
    private Set<KnowledgeGraphEdgeType> edgeTypes = new LinkedHashSet<>();
    private Boolean onlyPublished = false;
    private Boolean onlyIndexed = false;
}
