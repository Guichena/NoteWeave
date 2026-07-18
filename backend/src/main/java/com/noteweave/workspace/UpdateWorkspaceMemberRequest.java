package com.noteweave.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateWorkspaceMemberRequest(
        @NotBlank @Pattern(regexp = "EDITOR|VIEWER") String role,
        @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED") String status
) {
}
