package com.noteweave.worker;

public interface ArtifactWorkerControlClient {

    ArtifactWorkerExecutionResponse resumeTask(String taskId, ArtifactWorkerResumeRequest request);

    ArtifactAcquisitionAckResponse acknowledgeAcquisition(ArtifactAcquisitionAckRequest request);
}
