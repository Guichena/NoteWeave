package com.noteweave.artifact.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DistillArtifactRequest {

    @NotBlank
    private String cardType;

    private Long proposalId;

    private Boolean confirm = Boolean.FALSE;
}
