package com.noteweave.team.wiki.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PublishArtifactToWikiRequest {

    @NotNull
    private Long spaceId;

    @NotBlank
    private String title;

    private Long artifactVersionId;
}
