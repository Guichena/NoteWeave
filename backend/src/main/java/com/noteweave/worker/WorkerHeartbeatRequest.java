package com.noteweave.worker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkerHeartbeatRequest(
        @NotBlank @Size(max = 64) String workerType,
        @NotBlank @Size(max = 120) String workerInstanceId,
        @NotBlank @Size(max = 64) String phase,
        @NotBlank @Size(max = 80) String heartbeatAt
) {
}
