package com.noteweave.memory;

import org.springframework.stereotype.Service;

@Service
public class MemoryShadowRecallService {

    private final MemoryCompilerService memoryCompilerService;
    private final MemoryRuntime memoryRuntime;

    public MemoryShadowRecallService(
            MemoryCompilerService memoryCompilerService,
            MemoryRuntime memoryRuntime
    ) {
        this.memoryCompilerService = memoryCompilerService;
        this.memoryRuntime = memoryRuntime;
    }

    public MemoryShadowRecallResponse recall(String workspaceId) {
        return new MemoryShadowRecallResponse(
                true,
                memoryCompilerService.compileChatControlPack(workspaceId, "QA"),
                memoryRuntime.recall(new MemoryRuntimeQuery(workspaceId))
        );
    }
}
