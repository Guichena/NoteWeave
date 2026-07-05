package com.noteweave.memory;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/memory")
public class MemoryController {

    private final MemorySignalService memorySignalService;
    private final MemoryPromotionService memoryPromotionService;
    private final MemoryCompilerService memoryCompilerService;

    public MemoryController(
            MemorySignalService memorySignalService,
            MemoryPromotionService memoryPromotionService,
            MemoryCompilerService memoryCompilerService
    ) {
        this.memorySignalService = memorySignalService;
        this.memoryPromotionService = memoryPromotionService;
        this.memoryCompilerService = memoryCompilerService;
    }

    @PostMapping("/signals")
    ApiResponse<MemorySignalResponse> createSignal(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateMemorySignalRequest request
    ) {
        return ApiResponse.success(memorySignalService.createSignal(workspaceId, request));
    }

    @PostMapping("/promotions")
    ApiResponse<MemoryPromotionResponse> promoteSignals(
            @PathVariable String workspaceId,
            @Valid @RequestBody PromoteMemorySignalsRequest request
    ) {
        return ApiResponse.success(memoryPromotionService.promoteSignals(workspaceId, request.signalIds()));
    }

    @GetMapping("/control-pack/chat")
    ApiResponse<MemoryControlPackResponse> getChatControlPack(
            @PathVariable String workspaceId,
            @RequestParam("answer_mode") String answerMode
    ) {
        return ApiResponse.success(memoryCompilerService.compileChatControlPack(workspaceId, answerMode));
    }

    @GetMapping("/control-pack/artifact")
    ApiResponse<MemoryControlPackResponse> getArtifactControlPack(
            @PathVariable String workspaceId,
            @RequestParam("action_key") String actionKey
    ) {
        return ApiResponse.success(memoryCompilerService.compileArtifactControlPack(workspaceId, actionKey));
    }

    @GetMapping("/control-pack/research")
    ApiResponse<MemoryControlPackResponse> getResearchControlPack(
            @PathVariable String workspaceId,
            @RequestParam("profile_key") String profileKey
    ) {
        return ApiResponse.success(memoryCompilerService.compileResearchControlPack(workspaceId, profileKey));
    }
}
