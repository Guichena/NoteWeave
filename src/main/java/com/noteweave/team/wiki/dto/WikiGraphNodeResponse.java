package com.noteweave.team.wiki.dto;

import com.noteweave.team.wiki.model.WikiIndexStatus;
import com.noteweave.team.wiki.model.WikiPageStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiGraphNodeResponse {
    private Long id;
    private Long spaceId;
    private String title;
    private WikiPageStatus status;
    private WikiIndexStatus indexStatus;
    private boolean root;
    private Integer outgoingResolvedCount;
    private Integer incomingResolvedCount;
    private Integer unresolvedOutgoingCount;
    private Integer neighborCount;
    private Integer evidenceCitationCount;
    private Integer linkCount;
    private boolean orphan;
    private boolean leaf;
    private String sourceType;
    private Long sourceId;
    private boolean autoMaintained;
}
