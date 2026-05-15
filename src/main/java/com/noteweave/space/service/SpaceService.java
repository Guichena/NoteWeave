package com.noteweave.space.service;

import com.noteweave.common.api.PageResponse;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.space.dto.AddMemberRequest;
import com.noteweave.space.dto.CreateSpaceRequest;
import com.noteweave.space.dto.MemberListQuery;
import com.noteweave.space.dto.MemberResponse;
import com.noteweave.space.dto.SpaceListQuery;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SpaceService {

    private static final Sort DEFAULT_SPACE_SORT = Sort.by(Sort.Direction.DESC, "createdAt");
    private static final Sort DEFAULT_MEMBER_SORT = Sort.by(Sort.Direction.ASC, "createdAt");
    private static final Set<String> ALLOWED_SPACE_SORT_FIELDS = Set.of("id", "name", "createdAt", "updatedAt");
    private static final Set<String> ALLOWED_MEMBER_SORT_FIELDS = Set.of("id", "role", "createdAt", "updatedAt", "joinedAt");

    private final SpaceRepository spaceRepository;
    private final SpaceMemberRepository spaceMemberRepository;
    private final UserRepository userRepository;
    private final ResourceAccessService resourceAccessService;

    @Transactional
    public Space createPersonalSpace(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));

        Space existing = spaceRepository
                .findFirstByOwnerIdAndTypeAndStatusOrderByIdAsc(userId, SpaceType.PERSONAL, SpaceStatus.ACTIVE)
                .orElse(null);
        if (existing != null) {
            ensureOwnerMember(existing.getId(), userId);
            return existing;
        }

        Space personalSpace = new Space();
        personalSpace.setName(user.getUsername() + "'s Personal Space");
        personalSpace.setType(SpaceType.PERSONAL);
        personalSpace.setOwnerId(userId);
        personalSpace.setDescription("Personal workspace");
        personalSpace.setStatus(SpaceStatus.ACTIVE);
        personalSpace = spaceRepository.save(personalSpace);

        ensureOwnerMember(personalSpace.getId(), userId);
        return personalSpace;
    }

    @Transactional
    public SpaceResponse createTeamSpace(Long userId, CreateSpaceRequest request) {
        Space space = new Space();
        space.setName(normalizeRequired(request.getName()));
        space.setDescription(normalizeOptional(request.getDescription()));
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

    public PageResponse<SpaceResponse> listMySpaces(Long userId, SpaceListQuery query) {
        Pageable pageable = buildPageable(
                query.getPage(),
                query.getPageSize(),
                query.getSort(),
                DEFAULT_SPACE_SORT,
                ALLOWED_SPACE_SORT_FIELDS
        );
        Page<Space> page = spaceRepository.findVisibleSpaces(
                userId,
                SpaceStatus.ACTIVE,
                SpaceMemberStatus.ACTIVE,
                pageable
        );
        List<SpaceResponse> items = page.getContent().stream()
                .map(this::toSpaceResponse)
                .toList();

        return PageResponse.<SpaceResponse>builder()
                .items(items)
                .page(pageable.getPageNumber() + 1)
                .pageSize(pageable.getPageSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .sort(toSortExpression(pageable.getSort()))
                .filters(Map.of())
                .build();
    }

    public SpaceResponse getSpace(Long userId, Long spaceId) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        Space space = getRequiredSpace(spaceId);
        return toSpaceResponse(space);
    }

    @Transactional
    public MemberResponse addMember(Long operatorId, Long spaceId, AddMemberRequest request) {
        resourceAccessService.requireManageSpace(operatorId, spaceId);
        Space space = getRequiredSpace(spaceId);
        requireTeamSpace(space);

        User targetUser = userRepository.findByEmail(normalizeEmail(request.getEmail()))
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

    public PageResponse<MemberResponse> listMembers(Long operatorId, Long spaceId, MemberListQuery query) {
        resourceAccessService.requireViewSpace(operatorId, spaceId);
        Pageable pageable = buildPageable(
                query.getPage(),
                query.getPageSize(),
                query.getSort(),
                DEFAULT_MEMBER_SORT,
                ALLOWED_MEMBER_SORT_FIELDS
        );
        Page<SpaceMember> page = spaceMemberRepository.findBySpaceIdAndStatus(spaceId, SpaceMemberStatus.ACTIVE, pageable);

        Set<Long> userIds = page.getContent().stream().map(SpaceMember::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<MemberResponse> items = page.getContent().stream()
                .map(member -> {
                    User user = userMap.get(member.getUserId());
                    return user == null ? null : toMemberResponse(member, user);
                })
                .filter(Objects::nonNull)
                .toList();

        return PageResponse.<MemberResponse>builder()
                .items(items)
                .page(pageable.getPageNumber() + 1)
                .pageSize(pageable.getPageSize())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .sort(toSortExpression(pageable.getSort()))
                .filters(Map.of())
                .build();
    }

    @Transactional
    public MemberResponse updateMemberRole(Long operatorId, Long spaceId, Long memberId, UpdateMemberRoleRequest request) {
        resourceAccessService.requireManageSpace(operatorId, spaceId);
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
        resourceAccessService.requireManageSpace(operatorId, spaceId);
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

    private void ensureOwnerMember(Long spaceId, Long userId) {
        SpaceMember ownerMember = spaceMemberRepository.findBySpaceIdAndUserId(spaceId, userId)
                .orElseGet(() -> {
                    SpaceMember member = new SpaceMember();
                    member.setSpaceId(spaceId);
                    member.setUserId(userId);
                    return member;
                });
        if (ownerMember.getId() != null
                && ownerMember.getRole() == SpaceRole.OWNER
                && ownerMember.getStatus() == SpaceMemberStatus.ACTIVE) {
            return;
        }
        ownerMember.setRole(SpaceRole.OWNER);
        ownerMember.setStatus(SpaceMemberStatus.ACTIVE);
        if (ownerMember.getJoinedAt() == null) {
            ownerMember.setJoinedAt(LocalDateTime.now());
        }
        ownerMember.setRemovedAt(null);
        ownerMember.setRemovedBy(null);
        spaceMemberRepository.save(ownerMember);
    }

    private Pageable buildPageable(
            int page,
            int pageSize,
            String sortValue,
            Sort defaultSort,
            Set<String> allowedSortFields
    ) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(pageSize, 1);
        Sort sort = parseSort(sortValue, defaultSort, allowedSortFields);
        return PageRequest.of(safePage - 1, safePageSize, sort);
    }

    private Sort parseSort(String sortValue, Sort defaultSort, Set<String> allowedSortFields) {
        if (sortValue == null || sortValue.isBlank()) {
            return defaultSort;
        }

        String[] parts = sortValue.split(",");
        if (parts.length == 0) {
            return defaultSort;
        }

        String property = parts[0].trim();
        if (!allowedSortFields.contains(property)) {
            return defaultSort;
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())) {
            direction = Sort.Direction.DESC;
        }
        return Sort.by(direction, property);
    }

    private String toSortExpression(Sort sort) {
        Sort.Order order = sort.stream().findFirst()
                .orElse(Sort.Order.by("createdAt").with(Sort.Direction.DESC));
        return order.getProperty() + "," + order.getDirection().name().toLowerCase();
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

    private String normalizeRequired(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeEmail(String value) {
        return normalizeRequired(value).toLowerCase(java.util.Locale.ROOT);
    }
}
