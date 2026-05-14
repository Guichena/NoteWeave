package com.noteweave.permission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceMember;
import com.noteweave.space.model.SpaceMemberStatus;
import com.noteweave.space.model.SpaceRole;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import com.noteweave.space.repository.SpaceMemberRepository;
import com.noteweave.space.repository.SpaceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpacePermissionServiceTest {

    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private SpaceMemberRepository spaceMemberRepository;

    private SpacePermissionService spacePermissionService;

    @BeforeEach
    void setUp() {
        spacePermissionService = new SpacePermissionService(spaceRepository, spaceMemberRepository);
    }

    @Test
    void teamOwnerEditorViewerPermissions_ShouldMatchMatrix() {
        Long spaceId = 1L;
        Space teamSpace = buildSpace(spaceId, SpaceType.TEAM, 10L);
        when(spaceRepository.findByIdAndStatus(spaceId, SpaceStatus.ACTIVE)).thenReturn(Optional.of(teamSpace));

        when(spaceMemberRepository.findBySpaceIdAndUserIdAndStatus(spaceId, 10L, SpaceMemberStatus.ACTIVE))
                .thenReturn(Optional.of(buildMember(spaceId, 10L, SpaceRole.OWNER)));
        when(spaceMemberRepository.findBySpaceIdAndUserIdAndStatus(spaceId, 20L, SpaceMemberStatus.ACTIVE))
                .thenReturn(Optional.of(buildMember(spaceId, 20L, SpaceRole.EDITOR)));
        when(spaceMemberRepository.findBySpaceIdAndUserIdAndStatus(spaceId, 30L, SpaceMemberStatus.ACTIVE))
                .thenReturn(Optional.of(buildMember(spaceId, 30L, SpaceRole.VIEWER)));
        when(spaceMemberRepository.existsBySpaceIdAndUserIdAndStatus(spaceId, 40L, SpaceMemberStatus.ACTIVE)).thenReturn(false);
        when(spaceMemberRepository.findBySpaceIdAndUserIdAndStatus(spaceId, 40L, SpaceMemberStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(spacePermissionService.canManageSpace(10L, spaceId)).isTrue();
        assertThat(spacePermissionService.canManageSpace(20L, spaceId)).isFalse();
        assertThat(spacePermissionService.canManageSpace(30L, spaceId)).isFalse();

        assertThat(spacePermissionService.canUploadDocument(10L, spaceId)).isTrue();
        assertThat(spacePermissionService.canUploadDocument(20L, spaceId)).isTrue();
        assertThat(spacePermissionService.canUploadDocument(30L, spaceId)).isFalse();

        assertThat(spacePermissionService.canAskQuestion(10L, spaceId)).isTrue();
        assertThat(spacePermissionService.canAskQuestion(20L, spaceId)).isTrue();
        assertThat(spacePermissionService.canAskQuestion(30L, spaceId)).isTrue();
        assertThat(spacePermissionService.canAskQuestion(40L, spaceId)).isFalse();

        assertThat(spacePermissionService.canViewSpace(40L, spaceId)).isFalse();
    }

    @Test
    void personalSpace_ShouldOnlyAllowOwner() {
        Long spaceId = 2L;
        Space personalSpace = buildSpace(spaceId, SpaceType.PERSONAL, 77L);
        when(spaceRepository.findByIdAndStatus(spaceId, SpaceStatus.ACTIVE)).thenReturn(Optional.of(personalSpace));

        assertThat(spacePermissionService.canViewSpace(77L, spaceId)).isTrue();
        assertThat(spacePermissionService.canManageSpace(77L, spaceId)).isTrue();
        assertThat(spacePermissionService.canUploadDocument(77L, spaceId)).isTrue();
        assertThat(spacePermissionService.canAskQuestion(77L, spaceId)).isTrue();

        assertThat(spacePermissionService.canViewSpace(88L, spaceId)).isFalse();
        assertThat(spacePermissionService.canManageSpace(88L, spaceId)).isFalse();
        assertThat(spacePermissionService.canUploadDocument(88L, spaceId)).isFalse();
        assertThat(spacePermissionService.canAskQuestion(88L, spaceId)).isFalse();
    }

    private Space buildSpace(Long spaceId, SpaceType type, Long ownerId) {
        Space space = new Space();
        space.setId(spaceId);
        space.setType(type);
        space.setOwnerId(ownerId);
        space.setStatus(SpaceStatus.ACTIVE);
        space.setName("space");
        return space;
    }

    private SpaceMember buildMember(Long spaceId, Long userId, SpaceRole role) {
        SpaceMember member = new SpaceMember();
        member.setSpaceId(spaceId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(SpaceMemberStatus.ACTIVE);
        return member;
    }
}
