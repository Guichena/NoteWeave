package com.noteweave.team.wiki.dto;

import com.noteweave.team.wiki.model.WikiIndexStatus;
import com.noteweave.team.wiki.model.WikiPageStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiPageResponse {
    private Long id;
    private Long spaceId;
    private String title;
    private String content;
    private WikiPageStatus status;
    private Long sourceArtifactId;
    private Long sourceMessageId;
    private Long publishedVersionId;
    private Integer publishedVersionNo;
    private WikiIndexStatus indexStatus;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
