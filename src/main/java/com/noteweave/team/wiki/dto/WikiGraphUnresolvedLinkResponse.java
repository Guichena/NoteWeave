package com.noteweave.team.wiki.dto;

import com.noteweave.team.wiki.model.WikiPageLinkStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiGraphUnresolvedLinkResponse {
    private Long sourcePageId;
    private String sourcePageTitle;
    private String targetTitle;
    private WikiPageLinkStatus relationStatus;
    private Integer mentionCount;
}
