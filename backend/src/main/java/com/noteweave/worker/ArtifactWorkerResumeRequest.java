package com.noteweave.worker;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record ArtifactWorkerResumeRequest(
        String requestId,
        @JsonIgnore String deliveryToken
) {
}
