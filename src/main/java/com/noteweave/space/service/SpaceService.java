package com.noteweave.space.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.SpacePermissionService;
import com.noteweave.space.dto.AddMemberRequest;
import com.noteweave.space.dto.CreateSpaceRequest;
import com.noteweave.space.dto.MemberResponse;
import com.noteweave.space.dto.SpaceResponse;
import com.noteweave.space.dto.UpdateMemberRoleRequest;
import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceMember;
import com.noteweave.space.model.SpaceMemberStatus;
import com.noteweave.space.model.SpaceRole;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import com.noteweave.space.repository.SpaceMemberRepository;
import com.noteweave.space.repository.SpaceRepository;
import com.noteweave.user.model.User;
import com.noteweave.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SpaceService {

    private final SpaceRepository spaceRepository;
    private final SpaceMemberRepository spaceMemberRepository;
    private final UserRepository userRepository;
    private final SpacePermissionService spacePermissionService;

    @Transactional
    public SpaceResponse createTeamSpace(Long userId, CreateSpaceRequest request) {
        Space space = new Space();
        space.setName(request.getName().trim());
        space.setDescription(request.getDescription());
        space.setType(SpaceType.TEAM);
        space.setOwnerId(userId);
        space.setStatus(SpaceStatus.ACTIVE);
        space = spaceRepository.save(space);

        SpaceMember member = new SpaceMember();
        member.setSpaceId(space.getId());
        member.setUserId(userId);
        member.setRole(SpaceRole.OWNER);
        member.setStatus(SpaceMemberStatus.ACTIVE);
        member.setJoinedAt(LocalDateTime.now());
        spaceMemberRepository.save(member);

        return toSpaceResponse(space);
    }

    public List<SpaceResponse> listMySpaces(Long userId) {
        List<SpaceMember> memberships =
                spaceMemberRepository.findByUserIdAndStatus(userId, SpaceMemberStatus.ACTIVE);
        if (memberships.isEmpty()) {
            return List.of();
        }
        Set<Long> spaceIds = memberships.stream().map(SpaceMember::getSpaceId).collect(Collectors.toSet());
        List<Space> spaces = spaceRepository.findByIdInAndStatus(spaceIds, SpaceStatus.ACTIVE);
        return spaces.stream()
                .sorted(Comparator.comparing(Space::getCreatedAt).reversed())
                .map(this::toSpaceResponse)
                .toList();
    }

    public SpaceResponse getSpace(Long userId, Long spaceId) {
        spacePermissionService.requireViewSpace(userId, spaceId);
        Space space = getRequiredSpace(spaceId);
        return toSpaceResponse(space);
    }

    @Transactional
    public MemberResponse addMember(Long operatorId, Long spaceId, AddMemberRequest request) {
        spacePermissionService.requireManageSpace(operatorId, spaceId);
        Space space = getRequiredSpace(spaceId);
        requireTeamSpace(space);

        User targetUser = userRepository.findByEmail(request.getEmail().trim().toLowerCase())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND, "User with email not found"));

        SpaceMember member = spaceMemberRepository.findBySpaceIdAndUserId(spaceId, targetUser.getId())
                .orElseGet(() -> {
                    SpaceMember created = new SpaceMember();
                    created.setSpaceId(spaceId);
                    created.setUserId(targetUser.getId());
                    return created;
                });

        if (member.getId() != null && member.getStatus() == SpaceMemberStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CONFLICT, "Member already exists");
        }

        member.setStatus(SpaceMemberStatus.ACTIVE);
        member.setRole(request.getRole());
        member.setJoinedAt(LocalDateTime.now());
        member.setRemovedAt(null);
        member.setRemovedBy(null);
        SpaceMember saved = spaceMemberRepository.save(member);
        return toMemberResponse(saved, targetUser);
    }

    public List<MemberResponse> listMembers(Long operatorId, Long spaceId) {
        spacePermissionService.requireViewSpace(operatorId, spaceId);
        List<SpaceMember> members =
                spaceMemberRepository.findBySpaceIdAndStatus(spaceId, SpaceMemberStatus.ACTIVE);
        if (members.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = members.stream().map(SpaceMember::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<MemberResponse> responses = new ArrayList<>();
        for (SpaceMember member : members) {
            User user = userMap.get(member.getUserId());
            if (user != null) {
                responses.add(toMemberResponse(member, user));
            }
        }
        responses.sort(Comparator.comparing(MemberResponse::getCreatedAt));
        return responses;
    }

    @Transactional
    public MemberResponse updateMemberRole(Long operatorId, Long spaceId, Long memberId, UpdateMemberRoleRequest request) {
        spacePermissionService.requireManageSpace(operatorId, spaceId);
        Space space = getRequiredSpace(spaceId);
        requireTeamSpace(space);

        SpaceMember member = spaceMemberRepository.findByIdAndSpaceIdAndStatus(memberId, spaceId, SpaceMemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.getUserId().equals(space.getOwnerId()) && request.getRole() != SpaceRole.OWNER) {
            throw new BusinessException(ErrorCode.OWNER_CANNOT_BE_REMOVED, "Owner role cannot be changed");
        }

        member.setRole(request.getRole());
        SpaceMember saved = spaceMemberRepository.save(member);
        User user = userRepository.findById(saved.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        return toMemberResponse(saved, user);
    }

    @Transactional
    public void removeMember(Long operatorId, Long spaceId, Long memberId) {
        spacePermissionService.requireManageSpace(operatorId, spaceId);
        Space space = getRequiredSpace(spaceId);
        requireTeamSpace(space);

        SpaceMember member = spaceMemberRepository.findByIdAndSpaceIdAndStatus(memberId, spaceId, SpaceMemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.getUserId().equals(space.getOwnerId()) || member.getRole() == SpaceRole.OWNER) {
            throw new BusinessException(ErrorCode.OWNER_CANNOT_BE_REMOVED);
        }
        member.setStatus(SpaceMemberStatus.REMOVED);
        member.setRemovedAt(LocalDateTime.now());
        member.setRemovedBy(operatorId);
        spaceMemberRepository.save(member);
    }

    private Space getRequiredSpace(Long spaceId) {
        return spaceRepository.findByIdAndStatus(spaceId, SpaceStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
    }

    private void requireTeamSpace(Space space) {
        if (space.getType() != SpaceType.TEAM) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "Personal space does not support member management");
        }
    }

    private SpaceResponse toSpaceResponse(Space space) {
        return SpaceResponse.builder()
                .id(space.getId())
                .name(space.getName())
                .description(space.getDescription())
                .type(space.getType())
                .ownerId(space.getOwnerId())
                .status(space.getStatus())
                .createdAt(space.getCreatedAt())
                .updatedAt(space.getUpdatedAt())
                .build();
    }

    private MemberResponse toMemberResponse(SpaceMember member, User user) {
        return MemberResponse.builder()
                .id(member.getId())
                .userId(member.getUserId())
                .username(user.getUsername())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .role(member.getRole())
                .status(member.getStatus())
                .createdAt(member.getCreatedAt())
                .updatedAt(member.getUpdatedAt())
                .build();
    }
}
