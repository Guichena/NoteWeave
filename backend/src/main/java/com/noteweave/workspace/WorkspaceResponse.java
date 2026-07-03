package com.noteweave.workspace;

import java.time.Instant;

public record WorkspaceResponse(String workspaceId, String name, String status, Instant createdAt) {
}
