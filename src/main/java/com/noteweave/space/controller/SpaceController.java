package com.noteweave.space.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.space.dto.AddMemberRequest;
import com.noteweave.space.dto.CreateSpaceRequest;
import com.noteweave.space.dto.MemberResponse;
import com.noteweave.space.dto.SpaceResponse;
import com.noteweave.space.dto.UpdateMemberRoleRequest;
import com.noteweave.space.service.SpaceService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaceService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping
    public ApiResponse<SpaceResponse> createSpace(@Valid @RequestBody CreateSpaceRequest request) {
        Long userId = currentUserProvider.getCurrentUserId();
        return ApiResponse.success(spaceService.createTeamSpace(userId, request));
    }

    @GetMapping
    public ApiResponse<List<SpaceResponse>> listSpaces() {
        Long userId = currentUserProvider.getCurrentUserId();
        return ApiResponse.success(spaceService.listMySpaces(userId));
    }

    @GetMapping("/{spaceId}")
    public ApiResponse<SpaceResponse> getSpace(@PathVariable Long spaceId) {
        Long userId = currentUserProvider.getCurrentUserId();
        return ApiResponse.success(spaceService.getSpace(userId, spaceId));
    }

    @PostMapping("/{spaceId}/members")
    public ApiResponse<MemberResponse> addMember(@PathVariable Long spaceId, @Valid @RequestBody AddMemberRequest request) {
        Long userId = currentUserProvider.getCurrentUserId();
        return ApiResponse.success(spaceService.addMember(userId, spaceId, request));
    }

    @GetMapping("/{spaceId}/members")
    public ApiResponse<List<MemberResponse>> listMembers(@PathVariable Long spaceId) {
        Long userId = currentUserProvider.getCurrentUserId();
        return ApiResponse.success(spaceService.listMembers(userId, spaceId));
    }

    @PutMapping("/{spaceId}/members/{memberId}/role")
    public ApiResponse<MemberResponse> updateMemberRole(
            @PathVariable Long spaceId,
            @PathVariable Long memberId,
            @Valid @RequestBody UpdateMemberRoleRequest request
    ) {
        Long userId = currentUserProvider.getCurrentUserId();
        return ApiResponse.success(spaceService.updateMemberRole(userId, spaceId, memberId, request));
    }

    @DeleteMapping("/{spaceId}/members/{memberId}")
    public ApiResponse<Void> removeMember(@PathVariable Long spaceId, @PathVariable Long memberId) {
        Long userId = currentUserProvider.getCurrentUserId();
        spaceService.removeMember(userId, spaceId, memberId);
        return ApiResponse.success(null);
    }
}
