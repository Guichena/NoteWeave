import { apiClient, type ApiClient } from "../../shared/api";
import { type Conversation, type CreateConversationInput } from "./model";

export class ConversationsApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  create(workspaceId: string, input: CreateConversationInput) {
    return this.client.post<Conversation>(
      `/api/v2/workspaces/${workspaceId}/conversations`,
      input
    );
  }
}

export const conversationsApi = new ConversationsApi();
