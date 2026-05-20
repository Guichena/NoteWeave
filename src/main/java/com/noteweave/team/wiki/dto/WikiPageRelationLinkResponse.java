package com.noteweave.team.wiki.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiPageRelationLinkResponse {
    private Long pageId;
    private String title;
    private Integer mentionCount;
}
