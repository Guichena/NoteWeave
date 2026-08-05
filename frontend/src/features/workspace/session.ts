import { conversationsApi } from "../conversations/api";
import {
  type ConversationMessage,
  type ConversationSummary
} from "../conversations/model";
import { sourcesApi } from "../sources/api";
import { type SourceAsset } from "../sources/model";
import { workspaceApi } from "./api";
import { type Workspace } from "./model";
import { type AnswerMode } from "../../routes";

export type RestoredMessage = {
  role: "user" | "assistant" | "system";
  content: string;
  answerMode?: AnswerMode;
  answerStatus?: string;
};

export type RestoredWorkspaceSession = {
  workspace: Workspace;
  conversations: ConversationSummary[];
  conversation: ConversationSummary | null;
  messages: RestoredMessage[];
  sources: SourceAsset[];
};

type WorkspaceSessionPort = {
  listWorkspaces(): Promise<Workspace[]>;
  listConversations(workspaceId: string): Promise<ConversationSummary[]>;
  listMessages(
    workspaceId: string,
    conversationId: string,
    afterSequence?: number,
    limit?: number
  ): Promise<ConversationMessage[]>;
  listSources(workspaceId: string): Promise<SourceAsset[]>;
};

const MESSAGE_PAGE_SIZE = 200;

export class WorkspaceSessionLoader {
  private requestSequence = 0;

  constructor(private readonly port: WorkspaceSessionPort) {}

  listAvailableWorkspaces() {
    return this.port.listWorkspaces();
  }

  async restoreWorkspace(
    workspace: Workspace,
    preferredConversationId?: string | null
  ): Promise<RestoredWorkspaceSession | null> {
    const requestSequence = ++this.requestSequence;
    const [conversations, sources] = await Promise.all([
      this.port.listConversations(workspace.workspace_id),
      this.port.listSources(workspace.workspace_id)
    ]);
    const conversation = conversations.find((item) => item.conversation_id === preferredConversationId)
      ?? conversations[0]
      ?? null;
    const messages = conversation
      ? await this.loadAllMessages(workspace.workspace_id, conversation.conversation_id)
      : [];
    return this.isCurrent(requestSequence)
      ? { workspace, conversations, conversation, messages, sources }
      : null;
  }

  async switchConversation(
    workspace: Workspace,
    conversations: ConversationSummary[],
    conversation: ConversationSummary
  ): Promise<RestoredWorkspaceSession | null> {
    const requestSequence = ++this.requestSequence;
    const messages = await this.loadAllMessages(workspace.workspace_id, conversation.conversation_id);
    return this.isCurrent(requestSequence)
      ? { workspace, conversations, conversation, messages, sources: [] }
      : null;
  }

  private async loadAllMessages(workspaceId: string, conversationId: string) {
    const messages: ConversationMessage[] = [];
    let afterSequence = 0;
    while (true) {
      const page = await this.port.listMessages(
        workspaceId,
        conversationId,
        afterSequence,
        MESSAGE_PAGE_SIZE
      );
      messages.push(...page);
      const nextSequence = page.at(-1)?.message_seq ?? afterSequence;
      if (page.length < MESSAGE_PAGE_SIZE || nextSequence <= afterSequence) {
        break;
      }
      afterSequence = nextSequence;
    }
    return messages.map(toRestoredMessage);
  }

  private isCurrent(requestSequence: number) {
    return requestSequence === this.requestSequence;
  }
}

function toRestoredMessage(message: ConversationMessage): RestoredMessage {
  const role = message.role.toLowerCase();
  const restored: RestoredMessage = {
    role: role === "user" || role === "assistant" || role === "system" ? role : "system",
    content: message.content
  };
  const mode = (message.requested_turn_mode || "").toLowerCase();
  if (mode === "qa" || mode === "note" || mode === "wiki") {
    restored.answerMode = mode;
  }
  if (restored.role === "assistant") {
    restored.answerStatus = message.context_status === "FAILED"
      ? "FAILED"
      : message.context_status === "PENDING" ? "GENERATING" : "COMPLETED";
  }
  return restored;
}

export const workspaceSessionLoader = new WorkspaceSessionLoader({
  listWorkspaces: () => workspaceApi.list(),
  listConversations: (workspaceId) => conversationsApi.list(workspaceId),
  listMessages: (workspaceId, conversationId, afterSequence, limit) => (
    conversationsApi.listMessages(workspaceId, conversationId, afterSequence, limit)
  ),
  listSources: (workspaceId) => sourcesApi.list(workspaceId)
});
