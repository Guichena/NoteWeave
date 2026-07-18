import { apiClient, type ApiClient } from "../../shared/api";
import { type Workspace } from "./model";

export type CreateWorkspaceInput = {
  name: string;
  description: string;
};

export class WorkspaceApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  create(input: CreateWorkspaceInput) {
    return this.client.post<Workspace>("/api/v2/workspaces", input);
  }
}

export const workspaceApi = new WorkspaceApi();
