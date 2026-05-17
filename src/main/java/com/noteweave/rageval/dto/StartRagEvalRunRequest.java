package com.noteweave.rageval.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StartRagEvalRunRequest {

    @NotBlank
    private String name;
}
