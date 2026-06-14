package com.noteweave.personal.question.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateResearchQuestionRequest {

    @NotNull
    private Long researchProjectId;

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 64)
    private String questionType;

    @Size(max = 2048)
    private String currentHypothesis;

    @Size(max = 1024)
    private String nextStep;

    @Size(max = 1024)
    private String scopeNote;
}
