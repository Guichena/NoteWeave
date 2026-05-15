package com.noteweave.team.kb.dto;

import com.noteweave.team.kb.model.KnowledgeBaseStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KnowledgeBaseResponse {
    private Long id;
    private Long spaceId;
    private String name;
    private String description;
    private KnowledgeBaseStatus status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
