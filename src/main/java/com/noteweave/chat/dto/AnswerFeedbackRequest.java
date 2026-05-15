package com.noteweave.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AnswerFeedbackRequest {

    @NotBlank
    private String rating;

    private String reason;

    private String comment;
}
