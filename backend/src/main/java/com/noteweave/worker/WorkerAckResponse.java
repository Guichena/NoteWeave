package com.noteweave.worker;

public record WorkerAckResponse(
        String taskId,
        String status,
        String resultRef
) {
}
