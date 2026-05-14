package com.noteweave.permission.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceMember;
import com.noteweave.space.model.SpaceMemberStatus;
import com.noteweave.space.model.SpaceRole;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import com.noteweave.space.repository.SpaceMemberRepository;
import com.noteweave.space.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SpacePermissionService {

    private final SpaceRepository spaceRepository;
    private final SpaceMemberRepository spaceMemberRepository;

    public boolean canViewSpace(Long userId, Long spaceId) {
        Space space = findActiveSpace(spaceId);
        if (space == null) {
            return false;
        }
        if (space.getType() == SpaceType.PERSONAL) {
            return space.getOwnerId().equals(userId);
        }
        return spaceMemberRepository.existsBySpaceIdAndUserIdAndStatus(spaceId, userId, SpaceMemberStatus.ACTIVE);
    }

    public boolean canManageSpace(Long userId, Long spaceId) {
        Space space = findActiveSpace(spaceId);
        if (space == null) {
            return false;
        }
        if (space.getType() == SpaceType.PERSONAL) {
            return space.getOwnerId().equals(userId);
        }
        return findActiveMember(spaceId, userId)
                .map(member -> member.getRole() == SpaceRole.OWNER)
                .orElse(false);
    }

    public boolean canUploadDocument(Long userId, Long spaceId) {
        Space space = findActiveSpace(spaceId);
        if (space == null) {
            return false;
        }
        if (space.getType() == SpaceType.PERSONAL) {
            return space.getOwnerId().equals(userId);
        }
        return findActiveMember(spaceId, userId)
                .map(member -> member.getRole() == SpaceRole.OWNER || member.getRole() == SpaceRole.EDITOR)
                .orElse(false);
    }

    public boolean canEditWiki(Long userId, Long spaceId) {
        return canUploadDocument(userId, spaceId);
    }

    public boolean canAskQuestion(Long userId, Long spaceId) {
        Space space = findActiveSpace(spaceId);
        if (space == null) {
            return false;
        }
        if (space.getType() == SpaceType.PERSONAL) {
            return space.getOwnerId().equals(userId);
        }
        return findActiveMember(spaceId, userId).isPresent();
    }

    public void requireViewSpace(Long userId, Long spaceId) {
        requireSpaceExists(spaceId);
        if (!canViewSpace(userId, spaceId)) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "No permission to view this space");
        }
    }

    public void requireManageSpace(Long userId, Long spaceId) {
        requireSpaceExists(spaceId);
        if (!canManageSpace(userId, spaceId)) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "No permission to manage this space");
        }
    }

    public void requireUploadDocument(Long userId, Long spaceId) {
        requireSpaceExists(spaceId);
        if (!canUploadDocument(userId, spaceId)) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "No permission to upload document");
        }
    }

    public void requireAskQuestion(Long userId, Long spaceId) {
        requireSpaceExists(spaceId);
        if (!canAskQuestion(userId, spaceId)) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "No permission to ask question");
        }
    }

    private java.util.Optional<SpaceMember> findActiveMember(Long spaceId, Long userId) {
        return spaceMemberRepository.findBySpaceIdAndUserIdAndStatus(spaceId, userId, SpaceMemberStatus.ACTIVE);
    }

    private Space findActiveSpace(Long spaceId) {
        return spaceRepository.findByIdAndStatus(spaceId, SpaceStatus.ACTIVE).orElse(null);
    }

    private void requireSpaceExists(Long spaceId) {
        if (findActiveSpace(spaceId) == null) {
            throw new BusinessException(ErrorCode.SPACE_NOT_FOUND);
        }
    }
}
