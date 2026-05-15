package com.noteweave.permission.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.task.model.Task;
import com.noteweave.user.model.UserSystemRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResourceAccessService {

    private final SpacePermissionService spacePermissionService;

    public boolean canViewSpace(Long userId, Long spaceId) {
        return spacePermissionService.canViewSpace(userId, spaceId);
    }

    public boolean canManageSpace(Long userId, Long spaceId) {
        return spacePermissionService.canManageSpace(userId, spaceId);
    }

    public boolean canUploadDocument(Long userId, Long spaceId) {
        return spacePermissionService.canUploadDocument(userId, spaceId);
    }

    public boolean canAskQuestion(Long userId, Long spaceId) {
        return spacePermissionService.canAskQuestion(userId, spaceId);
    }

    public void requireViewSpace(Long userId, Long spaceId) {
        spacePermissionService.requireViewSpace(userId, spaceId);
    }

    public void requireManageSpace(Long userId, Long spaceId) {
        spacePermissionService.requireManageSpace(userId, spaceId);
    }

    public void requireUploadDocument(Long userId, Long spaceId) {
        spacePermissionService.requireUploadDocument(userId, spaceId);
    }

    public void requireAskQuestion(Long userId, Long spaceId) {
        spacePermissionService.requireAskQuestion(userId, spaceId);
    }

    public void requireViewTask(CurrentUser currentUser, Task task) {
        if (currentUser.systemRole() == UserSystemRole.ADMIN) {
            return;
        }
        if (!canViewSpace(currentUser.userId(), task.getSpaceId())) {
            throw new BusinessException(ErrorCode.TASK_ACCESS_DENIED, "No permission to view this task");
        }
    }

    public void requireOperateTask(CurrentUser currentUser, Task task) {
        if (currentUser.systemRole() == UserSystemRole.ADMIN) {
            return;
        }
        if (!canUploadDocument(currentUser.userId(), task.getSpaceId())) {
            throw new BusinessException(ErrorCode.TASK_ACCESS_DENIED, "No permission to operate this task");
        }
    }
}
