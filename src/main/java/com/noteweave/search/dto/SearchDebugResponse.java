package com.noteweave.search.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SearchDebugResponse {
    private List<SearchHitResponse> items;
}
