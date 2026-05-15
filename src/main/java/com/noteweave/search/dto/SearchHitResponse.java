package com.noteweave.search.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SearchHitResponse {
    private Long chunkId;
    private Long documentId;
    private String documentTitle;
    private Integer chunkIndex;
    private String content;
    private Double score;
}
