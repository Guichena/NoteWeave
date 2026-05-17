package com.noteweave.rageval.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpsertRagEvalCaseRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String queryText;

    private String expectedAnswer;
    private String expectedSourceJson;
    private String tagsJson;
    private Boolean enabled;
}
