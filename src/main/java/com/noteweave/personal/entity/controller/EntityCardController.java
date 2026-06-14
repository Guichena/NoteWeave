package com.noteweave.personal.entity.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.personal.entity.dto.CreateEntityCardRequest;
import com.noteweave.personal.entity.dto.EntityCardResponse;
import com.noteweave.personal.entity.dto.LinkEntityRelationRequest;
import com.noteweave.personal.entity.dto.UpdateEntityCardRequest;
import com.noteweave.personal.entity.service.EntityCardService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/personal")
@RequiredArgsConstructor
public class EntityCardController {

    private final EntityCardService entityCardService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/entity-cards")
    public ApiResponse<EntityCardResponse> create(@Valid @RequestBody CreateEntityCardRequest request) {
        return ApiResponse.success(entityCardService.create(currentUserProvider.getCurrentUserId(), request));
    }

    @GetMapping("/research-projects/{projectId}/entity-cards")
    public ApiResponse<List<EntityCardResponse>> list(@PathVariable Long projectId) {
        return ApiResponse.success(entityCardService.list(currentUserProvider.getCurrentUserId(), projectId));
    }

    @GetMapping("/entity-cards/{entityCardId}")
    public ApiResponse<EntityCardResponse> get(@PathVariable Long entityCardId) {
        return ApiResponse.success(entityCardService.get(currentUserProvider.getCurrentUserId(), entityCardId));
    }

    @PutMapping("/entity-cards/{entityCardId}")
    public ApiResponse<EntityCardResponse> update(
            @PathVariable Long entityCardId,
            @Valid @RequestBody UpdateEntityCardRequest request
    ) {
        return ApiResponse.success(entityCardService.update(currentUserProvider.getCurrentUserId(), entityCardId, request));
    }

    @PostMapping("/concept-cards/{conceptCardId}/entity-links")
    public ApiResponse<Void> linkConcept(
            @PathVariable Long conceptCardId,
            @Valid @RequestBody LinkEntityRelationRequest request
    ) {
        entityCardService.linkConcept(currentUserProvider.getCurrentUserId(), conceptCardId, request);
        return ApiResponse.success(null);
    }

    @PostMapping("/claims/{claimId}/entity-links")
    public ApiResponse<Void> linkClaim(
            @PathVariable Long claimId,
            @Valid @RequestBody LinkEntityRelationRequest request
    ) {
        entityCardService.linkClaim(currentUserProvider.getCurrentUserId(), claimId, request);
        return ApiResponse.success(null);
    }
}
