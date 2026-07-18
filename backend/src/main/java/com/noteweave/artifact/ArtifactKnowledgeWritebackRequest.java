package com.noteweave.artifact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ArtifactKnowledgeWritebackRequest(
        @NotBlank @Pattern(regexp = "NOTE|WIKI") String itemType,
        @Size(max = 300) String title
) {
}
