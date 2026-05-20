package com.noteweave.team.wiki.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiPageNeighborResponse {
    private Long pageId;
    private String title;
    private Integer outgoingMentionCount;
    private Integer incomingMentionCount;
    private boolean bidirectional;
}
