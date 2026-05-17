package com.noteweave.team.wiki.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateWikiDraftRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String content;

    private Long sourceArtifactId;
}
