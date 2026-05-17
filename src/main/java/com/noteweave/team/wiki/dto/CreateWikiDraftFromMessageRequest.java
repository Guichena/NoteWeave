package com.noteweave.team.wiki.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateWikiDraftFromMessageRequest {

    @NotBlank
    private String title;
}
