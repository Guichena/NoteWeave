import { apiClient, type ApiClient } from "../../shared/api";
import {
  type CreateResearchRunInput,
  type ResearchCheckpoint,
  type ResearchCell,
  type ResearchCollection,
  type ResearchCounterCheck,
  type ResearchDiscoveryProposal,
  type ResearchPlan,
  type ResearchPlanRevision,
  type ResearchReplan,
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

  getCollection(workspaceId: string, researchRunId: string, init?: RequestInit) {
    return this.client.get<ResearchCollection>(
      `${this.runsPath(workspaceId)}/${researchRunId}/collection`,
      init
    );
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

  getPlan(workspaceId: string, researchRunId: string, init?: RequestInit) {
    return this.client.get<ResearchPlan>(`${this.runsPath(workspaceId)}/${researchRunId}/plan`, init);
  }

  approvePlan(workspaceId: string, researchRunId: string) {
    return this.client.post<ResearchPlan>(`${this.runsPath(workspaceId)}/${researchRunId}/plan/approve`, {});
  }

  revisePlan(workspaceId: string, researchRunId: string, revision: ResearchPlanRevision) {
    return this.client.put<ResearchPlan>(`${this.runsPath(workspaceId)}/${researchRunId}/plan`, revision);
  }

  listReplans(workspaceId: string, researchRunId: string, init?: RequestInit) {
    return this.client.get<ResearchReplan[]>(`${this.runsPath(workspaceId)}/${researchRunId}/replans`, init);
  }

  listCounterChecks(workspaceId: string, researchRunId: string, init?: RequestInit) {
    return this.client.get<ResearchCounterCheck[]>(`${this.runsPath(workspaceId)}/${researchRunId}/counter-checks`, init);
  }

  listCells(workspaceId: string, researchRunId: string, init?: RequestInit) {
    return this.client.get<ResearchCell[]>(`${this.runsPath(workspaceId)}/${researchRunId}/cells`, init);
  }

  listDiscoveryProposals(workspaceId: string, researchRunId: string, init?: RequestInit) {
    return this.client.get<ResearchDiscoveryProposal[]>(
      `${this.runsPath(workspaceId)}/${researchRunId}/discovery-proposals`,
      init
    );
  }

  saveReportAsSource(workspaceId: string, researchRunId: string) {
    return this.client.post<SaveResearchReportSource>(
      `${this.runsPath(workspaceId)}/${researchRunId}/save-report-as-source`,
      {}
    );
  }

  /** 报告 Markdown 正式下载：服务端完成依赖校验后返回字节流。 */
  async downloadReportMarkdown(workspaceId: string, researchRunId: string) {
    const blob = await this.client.blob(
      `${this.runsPath(workspaceId)}/${researchRunId}/report/markdown`
    );
    const fileName = `${researchRunId}-report.md`;
    return { blob, fileName };
  }

  private runsPath(workspaceId: string) {
    return `/api/v2/workspaces/${workspaceId}/research-runs`;
  }
}

export const researchApi = new ResearchApi();
