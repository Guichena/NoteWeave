package com.noteweave.artifact.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateArtifactRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String content;

    private String changeNote;
}
