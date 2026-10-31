import { answersApi } from "../answers/api";
import { researchApi } from "../research/api";
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
  /** 对话里的研究消息点查看完整报告时，跳到研究页并打开这次研究的完整报告 */
  onOpenResearchRun?: (researchRunId: string) => void;
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
    onOpenResearchRun,
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
      ? (answerRunId) => answersApi.getEvidence(workspace.workspace_id, answerRunId)
      : undefined,
    onAnswerFeedback: workspace ? chat.sendAnswerFeedback : undefined,
    deepResearch: chat.deepResearch,
    setDeepResearch: chat.setDeepResearch,
    reportRefs: chat.reportRefs,
    reportGrounding: chat.reportGrounding,
    setReportGrounding: chat.setReportGrounding,
    loadResearchRun: workspace
      ? (researchRunId) => researchApi.getRun(workspace.workspace_id, researchRunId)
      : undefined,
    onOpenResearchRun,
    onRegenerateAnswer: chatBusy ? undefined : (answerRunId) => void chat.regenerateAnswer(answerRunId),
    onCancelAnswer: chatBusy ? (answerRunId) => void chat.cancelAnswer(answerRunId) : undefined
  };
}
