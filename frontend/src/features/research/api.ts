import { apiClient, type ApiClient } from "../../shared/api";
import {
  type CreateResearchRunInput,
  type ResearchCheckpoint,
  type ResearchRunDetail,
  type ResearchRunResponse,
  type ResearchRunSummary,
  type SaveResearchReportSource
} from "./model";

export class ResearchApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  listRuns(workspaceId: string, init?: RequestInit) {
    return this.client.get<ResearchRunSummary[]>(this.runsPath(workspaceId), init);
  }

  createRun(workspaceId: string, input: CreateResearchRunInput) {
    return this.client.post<ResearchRunResponse>(this.runsPath(workspaceId), input);
  }

  getRun(workspaceId: string, researchRunId: string, init?: RequestInit) {
    return this.client.get<ResearchRunDetail>(`${this.runsPath(workspaceId)}/${researchRunId}`, init);
  }

  getCheckpoint(workspaceId: string, researchRunId: string, checkpointNo: number, init?: RequestInit) {
    return this.client.get<ResearchCheckpoint>(
      `${this.runsPath(workspaceId)}/${researchRunId}/checkpoints/${checkpointNo}`,
      init
    );
  }

  resumeFromCheckpoint(workspaceId: string, researchRunId: string, checkpointNo: number) {
    return this.client.post<ResearchRunResponse>(
      `${this.runsPath(workspaceId)}/${researchRunId}/resume-from-checkpoint/${checkpointNo}`,
      {}
    );
  }

  saveReportAsSource(workspaceId: string, researchRunId: string) {
    return this.client.post<SaveResearchReportSource>(
      `${this.runsPath(workspaceId)}/${researchRunId}/save-report-as-source`,
      {}
    );
  }

  private runsPath(workspaceId: string) {
    return `/api/v2/workspaces/${workspaceId}/research-runs`;
  }
}

export const researchApi = new ResearchApi();
