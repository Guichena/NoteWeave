package com.noteweave.team.wiki.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiPageVersionResponse {
    private Long id;
    private Long wikiPageId;
    private int versionNo;
    private String title;
    private String content;
    private String changeNote;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
