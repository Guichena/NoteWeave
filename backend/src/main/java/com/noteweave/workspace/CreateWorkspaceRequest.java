package com.noteweave.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 1000) String description
) {
}
