package com.noteweave.admin.dto;

import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminSpaceResponse {
    private Long id;
    private String name;
    private String description;
    private SpaceType type;
    private Long ownerId;
    private String ownerUsername;
    private SpaceStatus status;
    private long knowledgeBaseCount;
    private long documentCount;
    private long artifactCount;
    private long taskCount;
    private long storageBytes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
