package com.noteweave.personal.project.dto;

import com.noteweave.personal.project.model.ResearchProjectCompileStatus;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ResearchProjectResponse {
    private Long id;
    private Long spaceId;
    private Long userId;
    private String title;
    private String description;
    private String researchGoal;
    private ResearchProjectCompileStatus compileStatus;
    private ResearchProjectStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
