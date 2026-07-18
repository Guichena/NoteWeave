package com.noteweave.task;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record WaitApprovalRequestResponse(
        String requestId,
        String taskId,
        String workspaceId,
        String capabilityName,
        String providerId,
        String serverId,
        String toolName,
        String status
) {
}
