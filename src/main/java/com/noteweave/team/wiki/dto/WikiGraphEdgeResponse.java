package com.noteweave.team.wiki.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiGraphEdgeResponse {
    private Long sourcePageId;
    private String sourcePageTitle;
    private Long targetPageId;
    private String targetPageTitle;
    private Integer mentionCount;
}
