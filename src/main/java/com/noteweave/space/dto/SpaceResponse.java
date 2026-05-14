package com.noteweave.space.dto;

import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SpaceResponse {
    private Long id;
    private String name;
    private String description;
    private SpaceType type;
    private Long ownerId;
    private SpaceStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
