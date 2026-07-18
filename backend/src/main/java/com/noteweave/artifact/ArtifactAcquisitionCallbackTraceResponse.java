package com.noteweave.artifact;

public record ArtifactAcquisitionCallbackTraceResponse(
        String status,
        ArtifactAcquisitionCallbackReceiptTraceResponse receipt,
        ArtifactAcquisitionOperationTraceResponse operation
) {
}
