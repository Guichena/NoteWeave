package com.noteweave.chat.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AnswerFeedbackResponse {
    private Long id;
    private Long messageId;
    private String rating;
    private String reason;
    private String comment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
