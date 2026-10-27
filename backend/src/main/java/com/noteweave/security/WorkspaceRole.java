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
            WorkspacePermission.EXECUTION_OPERATE,
            // SEC-05：记忆是工作台共享知识的一部分，编辑者负责日常整理与审核；
            // 此前 MEMORY_REVIEW 只给了 OWNER，编辑者连读记忆条目都会被拦。
            WorkspacePermission.MEMORY_REVIEW
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
