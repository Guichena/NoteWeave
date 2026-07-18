package com.noteweave.worker;

import java.util.List;

public record ArtifactAcquisitionAckResponse(
        ArtifactAcquisitionOperationResponse operation,
        ArtifactAcquisitionReceiptResponse receipt,
        List<ArtifactWorkerExecutionResponse> resumedTasks
) {
}
