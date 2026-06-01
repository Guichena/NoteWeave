package com.noteweave.team.wiki.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiGraphSummaryResponse {
    private Integer totalPageCount;
    private Integer publishedPageCount;
    private Integer draftPageCount;
    private Integer indexedPageCount;
    private Integer resolvedEdgeCount;
    private Integer unresolvedLinkCount;
    private Integer missingLinkCount;
    private Integer ambiguousLinkCount;
    private Integer orphanPageCount;
    private Integer leafPageCount;
    private Integer evidenceBackedPageCount;
    private Integer sourceBackedPageCount;
}
