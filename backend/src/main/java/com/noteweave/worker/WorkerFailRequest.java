package com.noteweave.worker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkerFailRequest(
        @NotBlank @Size(max = 64) String phase,
        @NotBlank @Size(max = 64) String errorCode,
        @NotBlank @Size(max = 1000) String errorMessage,
        boolean retryable
) {
}
