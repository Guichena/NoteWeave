package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/worker/research-tasks")
public class ResearchWorkerInputController {

    private final ResearchRunService researchRunService;

    public ResearchWorkerInputController(ResearchRunService researchRunService) {
        this.researchRunService = researchRunService;
    }

    @GetMapping("/{taskId}/input")
    ApiResponse<ResearchWorkerInputResponse> getTaskInput(@PathVariable String taskId) {
        return ApiResponse.success(researchRunService.getWorkerInput(taskId));
    }
}
