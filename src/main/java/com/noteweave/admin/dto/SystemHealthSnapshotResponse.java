package com.noteweave.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.noteweave.admin.model.SystemHealthComponent;
import com.noteweave.admin.model.SystemHealthStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SystemHealthSnapshotResponse {
    private Long id;
    private SystemHealthComponent component;
    private SystemHealthStatus status;
    private Long latencyMs;
    private JsonNode detail;
    private LocalDateTime checkedAt;
}
