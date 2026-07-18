package com.noteweave.task;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record WaitBlockedOperationResponse(
        String requestId,
        String sourceId,
        String operationKey,
        String capabilityName,
        String callbackStatus
) {
}
