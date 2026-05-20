package com.noteweave.team.wiki.dto;

import com.noteweave.team.wiki.model.WikiPageLinkStatus;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiPageUnresolvedLinkDetailResponse {
    private String targetTitle;
    private WikiPageLinkStatus relationStatus;
    private Integer mentionCount;
    private List<WikiPageAmbiguousCandidateResponse> candidatePages;
}
