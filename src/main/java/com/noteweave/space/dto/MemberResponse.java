package com.noteweave.space.dto;

import com.noteweave.space.model.SpaceMemberStatus;
import com.noteweave.space.model.SpaceRole;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MemberResponse {
    private Long id;
    private Long userId;
    private String username;
    private String email;
    private String displayName;
    private SpaceRole role;
    private SpaceMemberStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
