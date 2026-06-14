package com.noteweave.personal.question.dto;

import com.noteweave.personal.question.model.ResearchQuestionStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ResearchQuestionResponse {
    private Long id;
    private Long spaceId;
    private Long userId;
    private Long researchProjectId;
    private String title;
    private String questionType;
    private ResearchQuestionStatus status;
    private String currentHypothesis;
    private String currentAnswer;
    private String nextStep;
    private String scopeNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
