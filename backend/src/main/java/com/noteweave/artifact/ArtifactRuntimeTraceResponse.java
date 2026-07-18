package com.noteweave.artifact;

public record ArtifactRuntimeTraceResponse(
        ArtifactVerificationTraceResponse verification,
        ArtifactGenerationTraceResponse generationTrace,
        ArtifactExportTraceResponse exportTrace,
        ArtifactApprovalTraceResponse approvalTrace,
        ArtifactCapabilityUnionTraceResponse capabilityUnionTrace,
        java.util.List<ArtifactNodeTraceResponse> nodeTraces,
        ArtifactEvidenceCoverageTraceResponse evidenceCoverage,
        ArtifactWritebackPreviewTraceResponse writebackPreview,
        ArtifactOutputContractTraceResponse outputContractTrace,
        ArtifactLifecycleTraceResponse lifecycleTrace,
        ArtifactAcquisitionCallbackTraceResponse acquisitionCallbackTrace
) {
}
