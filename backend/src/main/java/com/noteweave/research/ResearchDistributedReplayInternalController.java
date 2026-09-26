package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal, authenticated observation surface used only by distributed replay. */
@RestController
@RequestMapping("/internal/research-agent/distributed-replay")
public class ResearchDistributedReplayInternalController {

    private final ResearchDistributedReplayObservationService observationService;

    public ResearchDistributedReplayInternalController(
            ResearchDistributedReplayObservationService observationService
    ) {
        this.observationService = observationService;
    }

    @GetMapping("/runs/{runId}")
    ApiResponse<Map<String, Object>> observe(@PathVariable String runId) {
        return ApiResponse.success(observationService.observe(runId));
    }
}
