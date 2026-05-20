package com.noteweave.graph.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.graph.dto.KnowledgeGraphEdgeType;
import com.noteweave.graph.dto.KnowledgeGraphEdgeView;
import com.noteweave.graph.dto.KnowledgeGraphFilterRequest;
import com.noteweave.graph.dto.KnowledgeGraphNodeDetailResponse;
import com.noteweave.graph.dto.KnowledgeGraphNodeType;
import com.noteweave.graph.dto.KnowledgeGraphPathResponse;
import com.noteweave.graph.dto.KnowledgeGraphResponse;
import com.noteweave.graph.service.KnowledgeGraphService;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphService knowledgeGraphService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/spaces/{spaceId}/knowledge-graph")
    public ApiResponse<KnowledgeGraphResponse> spaceGraph(
            @PathVariable Long spaceId,
            @RequestParam(required = false) Set<KnowledgeGraphNodeType> nodeTypes,
            @RequestParam(required = false) Set<KnowledgeGraphEdgeType> edgeTypes,
            @RequestParam(defaultValue = "false") boolean onlyPublished,
            @RequestParam(defaultValue = "false") boolean onlyIndexed
    ) {
        return ApiResponse.success(knowledgeGraphService.getSpaceGraph(
                currentUserProvider.getCurrentUserId(),
                spaceId,
                filter(nodeTypes, edgeTypes, onlyPublished, onlyIndexed)
        ));
    }

    @GetMapping("/team/wiki-pages/{pageId}/knowledge-graph")
    public ApiResponse<KnowledgeGraphResponse> wikiNeighborhood(
            @PathVariable Long pageId,
            @RequestParam(defaultValue = "1") Integer depth,
            @RequestParam(required = false) Set<KnowledgeGraphNodeType> nodeTypes,
            @RequestParam(required = false) Set<KnowledgeGraphEdgeType> edgeTypes,
            @RequestParam(defaultValue = "false") boolean onlyPublished,
            @RequestParam(defaultValue = "false") boolean onlyIndexed
    ) {
        return ApiResponse.success(knowledgeGraphService.getWikiNeighborhood(
                currentUserProvider.getCurrentUserId(),
                pageId,
                depth,
                filter(nodeTypes, edgeTypes, onlyPublished, onlyIndexed)
        ));
    }

    @GetMapping("/spaces/{spaceId}/knowledge-graph/nodes/{nodeId}")
    public ApiResponse<KnowledgeGraphNodeDetailResponse> nodeDetail(
            @PathVariable Long spaceId,
            @PathVariable String nodeId,
            @RequestParam(required = false) Set<KnowledgeGraphNodeType> nodeTypes,
            @RequestParam(required = false) Set<KnowledgeGraphEdgeType> edgeTypes,
            @RequestParam(defaultValue = "false") boolean onlyPublished,
            @RequestParam(defaultValue = "false") boolean onlyIndexed
    ) {
        return ApiResponse.success(knowledgeGraphService.getNodeDetail(
                currentUserProvider.getCurrentUserId(),
                spaceId,
                nodeId,
                filter(nodeTypes, edgeTypes, onlyPublished, onlyIndexed)
        ));
    }

    @GetMapping("/spaces/{spaceId}/knowledge-graph/neighborhood/{nodeId}")
    public ApiResponse<KnowledgeGraphResponse> neighborhood(
            @PathVariable Long spaceId,
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "1") Integer depth,
            @RequestParam(required = false) Set<KnowledgeGraphNodeType> nodeTypes,
            @RequestParam(required = false) Set<KnowledgeGraphEdgeType> edgeTypes,
            @RequestParam(defaultValue = "false") boolean onlyPublished,
            @RequestParam(defaultValue = "false") boolean onlyIndexed
    ) {
        return ApiResponse.success(knowledgeGraphService.getNeighborhood(
                currentUserProvider.getCurrentUserId(),
                spaceId,
                nodeId,
                depth,
                filter(nodeTypes, edgeTypes, onlyPublished, onlyIndexed)
        ));
    }

    @GetMapping("/spaces/{spaceId}/knowledge-graph/path")
    public ApiResponse<KnowledgeGraphPathResponse> path(
            @PathVariable Long spaceId,
            @RequestParam String sourceNodeId,
            @RequestParam String targetNodeId,
            @RequestParam(defaultValue = "UNDIRECTED") KnowledgeGraphEdgeView edgeView,
            @RequestParam(required = false) Set<KnowledgeGraphNodeType> nodeTypes,
            @RequestParam(required = false) Set<KnowledgeGraphEdgeType> edgeTypes,
            @RequestParam(defaultValue = "false") boolean onlyPublished,
            @RequestParam(defaultValue = "false") boolean onlyIndexed
    ) {
        return ApiResponse.success(knowledgeGraphService.findShortestPath(
                currentUserProvider.getCurrentUserId(),
                spaceId,
                sourceNodeId,
                targetNodeId,
                edgeView,
                filter(nodeTypes, edgeTypes, onlyPublished, onlyIndexed)
        ));
    }

    private KnowledgeGraphFilterRequest filter(
            Set<KnowledgeGraphNodeType> nodeTypes,
            Set<KnowledgeGraphEdgeType> edgeTypes,
            boolean onlyPublished,
            boolean onlyIndexed
    ) {
        KnowledgeGraphFilterRequest filter = new KnowledgeGraphFilterRequest();
        filter.setNodeTypes(nodeTypes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(nodeTypes));
        filter.setEdgeTypes(edgeTypes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(edgeTypes));
        filter.setOnlyPublished(onlyPublished);
        filter.setOnlyIndexed(onlyIndexed);
        return filter;
    }
}
