package com.noteweave.worker;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record WorkerProgressRequest(
        @NotBlank @Size(max = 64) String phase,
        @Min(0) @Max(100) Integer progressPercent,
        @NotBlank @Size(max = 1000) String message,
        Map<String, Object> metrics
) {
}
