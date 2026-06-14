package com.noteweave.personal.question.dto;

import com.noteweave.personal.question.model.ResearchQuestionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateResearchQuestionRequest {

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 64)
    private String questionType;

    @NotNull
    private ResearchQuestionStatus status;

    @Size(max = 2048)
    private String currentHypothesis;

    private String currentAnswer;

    @Size(max = 1024)
    private String nextStep;

    @Size(max = 1024)
    private String scopeNote;
}
