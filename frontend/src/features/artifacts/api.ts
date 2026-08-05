import { type ArtifactSkillSummary } from "./artifactStudio";
import { apiClient, type ApiClient } from "../../shared/api";
import {
  type ArtifactJobCreateResponse,
  type ArtifactJobSummary,
  type ArtifactKnowledgeWritebackInput,
  type ArtifactSavedSourceResponse,
  type ArtifactVersionComparison,
  type ArtifactVersionDetail,
  type CreateArtifactJobInput
} from "./model";

export class ArtifactsApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  listSkills(init?: RequestInit) {
    return this.client.get<ArtifactSkillSummary[]>("/api/v2/skills", init);
  }

  listJobs(workspaceId: string, init?: RequestInit) {
    return this.client.get<ArtifactJobSummary[]>(`/api/v2/workspaces/${workspaceId}/artifact-jobs`, init);
  }

  createJob(workspaceId: string, input: CreateArtifactJobInput) {
    return this.client.post<ArtifactJobCreateResponse>(`/api/v2/workspaces/${workspaceId}/artifact-jobs`, input);
  }

  getVersion(workspaceId: string, artifactJobId: string, versionNo: number, init?: RequestInit) {
    return this.client.get<ArtifactVersionDetail>(this.versionPath(workspaceId, artifactJobId, versionNo), init);
  }

  saveVersionAsSource(workspaceId: string, artifactJobId: string, versionNo: number) {
    return this.client.post<ArtifactSavedSourceResponse>(
      `${this.versionPath(workspaceId, artifactJobId, versionNo)}/save-as-source`,
      {}
    );
  }

  writebackVersion(
    workspaceId: string,
    artifactJobId: string,
    versionNo: number,
    input: ArtifactKnowledgeWritebackInput
  ) {
    return this.client.post(
      `${this.versionPath(workspaceId, artifactJobId, versionNo)}/writeback`,
      input
    );
  }

  regenerateVersion(workspaceId: string, artifactJobId: string, versionNo: number) {
    return this.client.post<ArtifactJobCreateResponse>(
      `${this.versionPath(workspaceId, artifactJobId, versionNo)}/regenerate`,
      {}
    );
  }

  rollbackVersion(workspaceId: string, artifactJobId: string, versionNo: number) {
    return this.client.post<ArtifactVersionDetail>(
      `${this.versionPath(workspaceId, artifactJobId, versionNo)}/rollback`,
      {}
    );
  }

  compareVersions(workspaceId: string, artifactJobId: string, fromVersionNo: number, toVersionNo: number) {
    const query = new URLSearchParams({
      from: String(fromVersionNo),
      to: String(toVersionNo)
    });
    return this.client.get<ArtifactVersionComparison>(
      `/api/v2/workspaces/${workspaceId}/artifact-jobs/${artifactJobId}/versions/compare?${query}`
    );
  }

  exportPdf(workspaceId: string, artifactJobId: string, versionNo: number) {
    return this.client.blob(`${this.versionPath(workspaceId, artifactJobId, versionNo)}/export.pdf`);
  }

  private versionPath(workspaceId: string, artifactJobId: string, versionNo: number) {
    return `/api/v2/workspaces/${workspaceId}/artifact-jobs/${artifactJobId}/versions/${versionNo}`;
  }
}

export const artifactsApi = new ArtifactsApi();
