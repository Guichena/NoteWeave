package com.noteweave.memory;

import jakarta.validation.constraints.NotBlank;

public record CreateMemoryObservationRequest(
        @NotBlank String observationId,
        @NotBlank String scope,
        @NotBlank String slotKey,
        @NotBlank String displayText
) {
}
