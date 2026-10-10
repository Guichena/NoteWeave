import { answersApi } from "../answers/api";
import type { SourceAsset } from "../sources/model";
import type { Workspace } from "../workspace/model";
import type { Conversation } from "./model";
import type { ChatSessionController } from "./useChatSessionController";
import type { ChatWorkbenchProps } from "./ChatWorkbench";

type BuildChatWorkbenchPropsInput = {
  chat: ChatSessionController;
  sources: SourceAsset[];
  workspace: Workspace | null;
  conversation: Conversation | null;
  conversationCount: number;
  chatBusy: boolean;
  artifactComposerOpen: boolean;
  setArtifactComposerOpen: (open: boolean) => void;
  artifactRailProps: import("../artifacts/ArtifactRailProps").ArtifactRailProps;
  onOpenSourceLibrary: () => void;
  onOpenWikiPage?: (title: string) => void;
  uploadBusy?: boolean;
};

export function buildChatWorkbenchProps(input: BuildChatWorkbenchPropsInput): ChatWorkbenchProps {
  const {
    chat,
    sources,
    workspace,
    conversation,
    conversationCount,
    chatBusy,
    artifactComposerOpen,
    setArtifactComposerOpen,
    artifactRailProps,
    onOpenSourceLibrary,
    onOpenWikiPage,
    uploadBusy = false
  } = input;

  return {
    mode: chat.mode,
    setMode: chat.setMode,
    sources,
    chatBusy,
    workspace,
    messages: chat.messages,
    question: chat.question,
    setQuestion: chat.setQuestion,
    currentRouteLabel: chat.currentRoute().label,
    selectedQaSourceIds: chat.selectedQaSourceIds,
    setSelectedQaSourceIds: chat.setSelectedQaSourceIds,
    toggleQaScope: chat.toggleQaScope,
    sendMessage: () => void chat.sendMessage(),
    conversation,
    conversationCount,
    setArtifactComposerOpen,
    artifactComposerOpen,
    artifactRailProps,
    onOpenSourceLibrary,
    uploadSourceFile: chat.uploadSourceFile,
    uploadBusy,
    onOpenWikiPage,
    loadAnswerEvidence: workspace
      ? (answerRunId) => answersApi.getEvidence(workspace.workspace_id, answerRunId).then((manifest) => manifest.evidence)
      : undefined
  };
}
