package com.noteweave.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.noteweave.admin.model.AuditAction;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuditLogResponse {
    private Long id;
    private Long operatorId;
    private Long spaceId;
    private AuditAction action;
    private String targetType;
    private Long targetId;
    private String requestId;
    private String ipAddress;
    private String userAgent;
    private JsonNode before;
    private JsonNode after;
    private LocalDateTime createdAt;
}
