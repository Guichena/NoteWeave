package com.noteweave.worker;

import com.noteweave.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/worker/research-outbox")
public class ResearchOutboxDispatcherController {

    private final ResearchOutboxDispatcherService researchOutboxDispatcherService;

    public ResearchOutboxDispatcherController(ResearchOutboxDispatcherService researchOutboxDispatcherService) {
        this.researchOutboxDispatcherService = researchOutboxDispatcherService;
    }

    @PostMapping("/dispatch")
    ApiResponse<ResearchOutboxDispatchResponse> dispatch(
            @RequestParam(defaultValue = "5") int limit
    ) {
        return ApiResponse.success(researchOutboxDispatcherService.dispatchReadyResearchRuns(limit));
    }
}
