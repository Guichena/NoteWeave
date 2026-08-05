package com.noteweave.security;

import java.util.EnumSet;
import java.util.Set;

public enum WorkspaceRole {
    OWNER(EnumSet.allOf(WorkspacePermission.class)),
    EDITOR(EnumSet.of(
            WorkspacePermission.WORKSPACE_READ,
            WorkspacePermission.SOURCE_WRITE,
            WorkspacePermission.ANSWER_RUN,
            WorkspacePermission.KNOWLEDGE_WRITE,
            WorkspacePermission.EXECUTION_OPERATE
    )),
    VIEWER(EnumSet.of(WorkspacePermission.WORKSPACE_READ));

    private final Set<WorkspacePermission> permissions;

    WorkspaceRole(Set<WorkspacePermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean grants(WorkspacePermission permission) {
        return permissions.contains(permission);
    }
}
