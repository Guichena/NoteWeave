package com.noteweave.workspace;

import java.time.Instant;

public record WorkspaceMemberResponse(
        String userId,
        String displayName,
        String role,
        String status,
        Instant updatedAt
) {
}
