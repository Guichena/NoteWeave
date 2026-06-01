package com.noteweave.team.wiki.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiGraphResponse {
    private Long spaceId;
    private Long rootPageId;
    private Integer depth;
    private Integer nodeCount;
    private Integer edgeCount;
    private WikiGraphSummaryResponse summary;
    private List<WikiGraphNodeResponse> nodes;
    private List<WikiGraphEdgeResponse> edges;
    private List<WikiGraphUnresolvedLinkResponse> unresolvedLinks;
}
