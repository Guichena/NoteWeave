package com.noteweave.common.api;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PageResponse<T> {

    private final List<T> items;
    private final int page;
    private final int pageSize;
    private final long total;
    private final int totalPages;
    private final String sort;
    private final Map<String, Object> filters;
}
