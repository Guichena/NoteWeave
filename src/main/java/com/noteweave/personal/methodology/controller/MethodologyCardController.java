package com.noteweave.personal.methodology.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.personal.methodology.dto.MethodologyCardResponse;
import com.noteweave.personal.methodology.service.MethodologyCardService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
