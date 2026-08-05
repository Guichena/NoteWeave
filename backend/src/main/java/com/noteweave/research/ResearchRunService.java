package com.noteweave.research;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stable Research run facade. Command, query, and artifact behavior live behind
 * dedicated module interfaces so callers do not need to coordinate them.
 */
@Service
public class ResearchRunService {

    private final ResearchRunCommandService commandService;
    private final ResearchRunQueryService queryService;
    private final ResearchArtifactService artifactService;

    public ResearchRunService(
            ResearchRunCommandService commandService,
            ResearchRunQueryService queryService,
            ResearchArtifactService artifactService
    ) {
        this.commandService = commandService;
        this.queryService = queryService;
        this.artifactService = artifactService;
    }

    @Transactional
    public ResearchRunResponse createRun(String workspaceId, CreateResearchRunRequest request) {
        return commandService.createRun(workspaceId, request);
    }

    public List<ResearchRunSummaryResponse> listRuns(String workspaceId) {
        return queryService.listRuns(workspaceId);
    }

    public ResearchRunDetailResponse getRunDetail(String workspaceId, String researchRunId) {
        return queryService.getRunDetail(workspaceId, researchRunId);
    }

    public List<ResearchCheckpointSummaryResponse> listCheckpoints(String workspaceId, String researchRunId) {
        return queryService.listCheckpoints(workspaceId, researchRunId);
    }

    public ResearchCheckpointResponse getCheckpoint(String workspaceId, String researchRunId, int checkpointNo) {
        return queryService.getCheckpoint(workspaceId, researchRunId, checkpointNo);
    }

    @Transactional
    public ResearchRunResponse resumeFromCheckpoint(String workspaceId, String researchRunId, int checkpointNo) {
        return commandService.resumeFromCheckpoint(workspaceId, researchRunId, checkpointNo);
    }

    @Transactional
    public SaveResearchReportSourceResponse saveReportAsSource(String workspaceId, String researchRunId) {
        return artifactService.saveReportAsSource(workspaceId, researchRunId);
    }

    public ResearchEvidenceManifestResponse evidenceManifest(String workspaceId, String researchRunId) {
        return artifactService.evidenceManifest(workspaceId, researchRunId);
    }

    @Transactional
    public void setAgentExecutionMode(String researchRunId, String mode) {
        commandService.setAgentExecutionMode(researchRunId, mode);
    }
}
