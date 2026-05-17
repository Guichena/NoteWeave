package com.noteweave.personal.methodology.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.personal.methodology.dto.CreateMethodologyCardRequest;
import com.noteweave.personal.methodology.dto.MethodologyCardResponse;
import com.noteweave.personal.methodology.dto.UpdateMethodologyCardRequest;
import com.noteweave.personal.methodology.service.MethodologyCardService;
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
@RequestMapping("/api/v1/personal")
@RequiredArgsConstructor
public class MethodologyCardController {

    private final MethodologyCardService methodologyCardService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/research-projects/{projectId}/methodology-cards")
    public ApiResponse<List<MethodologyCardResponse>> list(@PathVariable Long projectId) {
        return ApiResponse.success(methodologyCardService.list(currentUserProvider.getCurrentUserId(), projectId));
    }

    @GetMapping("/methodology-cards/{cardId}")
    public ApiResponse<MethodologyCardResponse> get(@PathVariable Long cardId) {
        return ApiResponse.success(methodologyCardService.get(currentUserProvider.getCurrentUserId(), cardId));
    }

    @PostMapping("/research-projects/{projectId}/methodology-cards")
    public ApiResponse<MethodologyCardResponse> create(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateMethodologyCardRequest request
    ) {
        return ApiResponse.success(methodologyCardService.create(currentUserProvider.getCurrentUserId(), projectId, request));
    }

    @PutMapping("/methodology-cards/{cardId}")
    public ApiResponse<MethodologyCardResponse> update(
            @PathVariable Long cardId,
            @Valid @RequestBody UpdateMethodologyCardRequest request
    ) {
        return ApiResponse.success(methodologyCardService.update(currentUserProvider.getCurrentUserId(), cardId, request));
    }

    @DeleteMapping("/methodology-cards/{cardId}")
    public ApiResponse<Void> archive(@PathVariable Long cardId) {
        methodologyCardService.archive(currentUserProvider.getCurrentUserId(), cardId);
        return ApiResponse.success(null);
    }
}
