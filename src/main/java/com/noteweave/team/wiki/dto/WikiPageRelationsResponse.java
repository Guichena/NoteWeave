package com.noteweave.team.wiki.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiPageRelationsResponse {
    private Long pageId;
    private String pageTitle;
    private WikiPageRelationSummaryResponse summary;
    private List<WikiPageRelationLinkResponse> outgoingLinks;
    private List<WikiPageRelationLinkResponse> incomingLinks;
    private List<WikiPageUnresolvedLinkDetailResponse> unresolvedLinks;
    private List<WikiPageNeighborResponse> neighborPages;
}
