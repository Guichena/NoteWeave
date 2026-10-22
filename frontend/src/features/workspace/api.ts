import { apiClient, type ApiClient } from "../../shared/api";
import {
  type UpdateWorkspaceMemberInput,
  type Workspace,
  type WorkspaceMember
} from "./model";

export type CreateWorkspaceInput = {
  name: string;
  description: string;
};

export class WorkspaceApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  list() {
    return this.client.get<Workspace[]>("/api/v2/workspaces");
  }

  create(input: CreateWorkspaceInput) {
    return this.client.post<Workspace>("/api/v2/workspaces", input);
  }

  listMembers(workspaceId: string) {
    return this.client.get<WorkspaceMember[]>(`/api/v2/workspaces/${workspaceId}/members`);
  }

  putMember(workspaceId: string, userId: string, input: UpdateWorkspaceMemberInput) {
    return this.client.put<WorkspaceMember>(
      `/api/v2/workspaces/${workspaceId}/members/${encodeURIComponent(userId)}`,
      input
    );
  }

  removeMember(workspaceId: string, userId: string) {
    return this.client.delete(
      `/api/v2/workspaces/${workspaceId}/members/${encodeURIComponent(userId)}`
    );
  }
}

export const workspaceApi = new WorkspaceApi();
