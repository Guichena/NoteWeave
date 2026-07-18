package com.noteweave.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record WaitReasonResponse(
        String status,
        String providerId,
        String operationKey,
        String capabilityName,
        List<String> unavailableCapabilities,
        List<String> capabilities,
        List<WaitBlockedOperationResponse> blockedOperations
) {
}
