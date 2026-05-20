package com.noteweave.team.wiki.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiPageRelationSummaryResponse {
    private int outgoingResolvedCount;
    private int incomingResolvedCount;
    private int unresolvedOutgoingCount;
    private int neighborCount;
}
