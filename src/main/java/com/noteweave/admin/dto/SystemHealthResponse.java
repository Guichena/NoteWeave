package com.noteweave.admin.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SystemHealthResponse {
    private List<ComponentHealthResponse> components;
    private LocalDateTime checkedAt;
}
