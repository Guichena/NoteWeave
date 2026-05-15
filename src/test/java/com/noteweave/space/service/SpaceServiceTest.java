package com.noteweave.space.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpaceServiceTest {

    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private SpaceMemberRepository spaceMemberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ResourceAccessService resourceAccessService;

    private SpaceService spaceService;

    @BeforeEach
    void setUp() {
        spaceService = new SpaceService(spaceRepository, spaceMemberRepository, userRepository, resourceAccessService);
    }

    @Test
    void createPersonalSpace_ShouldCreateWhenMissing() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(spaceRepository.findFirstByOwnerIdAndTypeAndStatusOrderByIdAsc(1L, SpaceType.PERSONAL, SpaceStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(spaceRepository.save(any(Space.class))).thenAnswer(invocation -> {
            Space space = invocation.getArgument(0);
            space.setId(11L);
            return space;
        });
        when(spaceMemberRepository.findBySpaceIdAndUserId(11L, 1L)).thenReturn(Optional.empty());

        Space created = spaceService.createPersonalSpace(1L);

        assertThat(created.getId()).isEqualTo(11L);
        assertThat(created.getType()).isEqualTo(SpaceType.PERSONAL);
        assertThat(created.getOwnerId()).isEqualTo(1L);

        ArgumentCaptor<SpaceMember> memberCaptor = ArgumentCaptor.forClass(SpaceMember.class);
        verify(spaceMemberRepository).save(memberCaptor.capture());
        SpaceMember savedMember = memberCaptor.getValue();
        assertThat(savedMember.getSpaceId()).isEqualTo(11L);
        assertThat(savedMember.getUserId()).isEqualTo(1L);
        assertThat(savedMember.getRole()).isEqualTo(SpaceRole.OWNER);
        assertThat(savedMember.getStatus()).isEqualTo(SpaceMemberStatus.ACTIVE);
    }

    @Test
    void createPersonalSpace_ShouldReturnExistingAndKeepIdempotent() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");

        Space existing = new Space();
        existing.setId(77L);
        existing.setOwnerId(1L);
        existing.setType(SpaceType.PERSONAL);
        existing.setStatus(SpaceStatus.ACTIVE);

        SpaceMember existingMember = new SpaceMember();
        existingMember.setId(9L);
        existingMember.setSpaceId(77L);
        existingMember.setUserId(1L);
        existingMember.setRole(SpaceRole.VIEWER);
        existingMember.setStatus(SpaceMemberStatus.REMOVED);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(spaceRepository.findFirstByOwnerIdAndTypeAndStatusOrderByIdAsc(1L, SpaceType.PERSONAL, SpaceStatus.ACTIVE))
                .thenReturn(Optional.of(existing));
        when(spaceMemberRepository.findBySpaceIdAndUserId(77L, 1L)).thenReturn(Optional.of(existingMember));

        Space result = spaceService.createPersonalSpace(1L);

        assertThat(result.getId()).isEqualTo(77L);
        verify(spaceRepository, never()).save(any(Space.class));

        ArgumentCaptor<SpaceMember> memberCaptor = ArgumentCaptor.forClass(SpaceMember.class);
        verify(spaceMemberRepository).save(memberCaptor.capture());
        SpaceMember savedMember = memberCaptor.getValue();
        assertThat(savedMember.getId()).isEqualTo(9L);
        assertThat(savedMember.getRole()).isEqualTo(SpaceRole.OWNER);
        assertThat(savedMember.getStatus()).isEqualTo(SpaceMemberStatus.ACTIVE);
    }

    @Test
    void createPersonalSpace_ShouldFailWhenUserMissing() {
        when(userRepository.findById(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spaceService.createPersonalSpace(100L))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getErrorCode() == ErrorCode.NOT_FOUND);
    }
}
