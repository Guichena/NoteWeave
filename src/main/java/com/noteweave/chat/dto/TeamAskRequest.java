package com.noteweave.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TeamAskRequest {

    @NotBlank
    private String content;
}
