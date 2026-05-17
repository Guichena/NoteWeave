package com.noteweave.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreatePromptVersionRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String scene;

    @NotBlank
    private String content;

    private String variablesJson;

    private String status;

    @NotNull
    private Integer version;
}
