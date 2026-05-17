package com.noteweave.team.wiki.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WikiSearchResponse {
    private List<WikiSearchItemResponse> items;
}
