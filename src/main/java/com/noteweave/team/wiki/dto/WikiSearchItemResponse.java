package com.noteweave.team.wiki.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiSearchItemResponse {
    private Long id;
    private String title;
    private String content;
    private Double score;
}
