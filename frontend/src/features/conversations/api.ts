import { apiClient, type ApiClient } from "../../shared/api";
import {
  type Conversation,
  type ConversationMessage,
  type ConversationSummary,
  type CreateConversationInput,
  type DeletedConversation,
  type PendingMemoryProposal,
  type SessionPreference
} from "./model";

export class ConversationsApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  create(workspaceId: string, input: CreateConversationInput) {
    return this.client.post<Conversation>(
      `/api/v2/workspaces/${workspaceId}/conversations`,
      input
    );
  }

  list(workspaceId: string) {
    return this.client.get<ConversationSummary[]>(
      `/api/v2/workspaces/${workspaceId}/conversations`
    );
  }

  /** 重命名会话，返回更新后的会话摘要。 */
  rename(workspaceId: string, conversationId: string, title: string) {
    return this.client.patch<ConversationSummary>(
      `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}`,
      { title }
    );
  }

  /** 删除会话（后端软删除），进行中的回答会一并停止。 */
  remove(workspaceId: string, conversationId: string) {
    return this.client.deleteJson<DeletedConversation>(
      `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}`
    );
  }

  listMessages(workspaceId: string, conversationId: string, afterSequence = 0, limit = 200) {
    const query = new URLSearchParams({
      after_seq: String(afterSequence),
      limit: String(limit)
    });
    return this.client.get<ConversationMessage[]>(
      `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}/messages?${query}`
    );
  }

  listSessionPreferences(workspaceId: string, conversationId: string) {
    return this.client.get<SessionPreference[]>(
      `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}/preferences/session`
    );
  }

  addSessionPreference(workspaceId: string, conversationId: string, text: string) {
    return this.client.post<SessionPreference>(
      `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}/preferences/session`,
      { text }
    );
  }

  removeSessionPreference(workspaceId: string, conversationId: string, requirementId: string) {
    return this.client.delete(
      `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}/preferences/session/${requirementId}`
    );
  }

  listPendingProposals(workspaceId: string, conversationId: string) {
    return this.client.get<PendingMemoryProposal[]>(
      `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}/preferences/proposals`
    );
  }

  listActiveMemories(workspaceId: string, conversationId: string) {
    return this.client.get<PendingMemoryProposal[]>(
      `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}/preferences/active`
    );
  }

  sessionOnlyRevision(workspaceId: string, conversationId: string, revisionId: string) {
    return this.client.post<SessionPreference>(
      `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}/preferences/session`,
      { revision_id: revisionId }
    );
  }

  reviewMemoryProposal(workspaceId: string, revisionId: string, decision: "ACCEPT" | "REJECT" | "REVOKE") {
    return this.client.post<void>(
      `/api/v2/workspaces/${workspaceId}/memory/revisions/${revisionId}/review`,
      { decision }
    );
  }
}

export const conversationsApi = new ConversationsApi();
