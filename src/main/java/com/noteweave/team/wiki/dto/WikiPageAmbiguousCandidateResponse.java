package com.noteweave.team.wiki.dto;

import com.noteweave.team.wiki.model.WikiIndexStatus;
import com.noteweave.team.wiki.model.WikiPageStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiPageAmbiguousCandidateResponse {
    private Long pageId;
    private String title;
    private WikiPageStatus status;
    private WikiIndexStatus indexStatus;
}
