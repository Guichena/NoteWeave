import {
  buildSourceOriginBadge,
  buildGenericTaskRuntimeSnapshot,
  buildTaskEventNarrative
} from "../research/presentation";
import type { SourceAsset } from "../sources/model";
import type { Workspace } from "../workspace/model";
import type { Conversation } from "./model";
import type { ChatSessionController } from "./useChatSessionController";
import type { ChatWorkbenchProps } from "./ChatWorkbench";
import type {
  ExecutionEvent as StreamEvent,
  ExecutionTask as TaskStatus
} from "../executions/model";
import type { SignalChip } from "../research/model";
import type { WaitContextDetailLine } from "../../runStatus";

type BuildChatWorkbenchPropsInput = {
  chat: ChatSessionController;
  sources: SourceAsset[];
  workspace: Workspace | null;
  conversation: Conversation | null;
  chatBusy: boolean;
  uploadBusy: boolean;
  latestTask: TaskStatus | null;
  taskEvents: StreamEvent[];
  taskPresentation: {
    waitSignals: SignalChip[];
    waitDetails: WaitContextDetailLine[];
    progressEvent: StreamEvent | null;
  };
  artifactComposerOpen: boolean;
  setArtifactComposerOpen: (open: boolean) => void;
  artifactRailProps: import("../artifacts/ArtifactRailProps").ArtifactRailProps;
};

export function buildChatWorkbenchProps(input: BuildChatWorkbenchPropsInput): ChatWorkbenchProps {
  const {
    chat,
    sources,
    workspace,
    conversation,
    chatBusy,
    uploadBusy,
    latestTask,
    taskEvents,
    taskPresentation,
    artifactComposerOpen,
    setArtifactComposerOpen,
    artifactRailProps
  } = input;

  return {
    mode: chat.mode,
    setMode: chat.setMode,
    sources,
    sourceText: chat.sourceText,
    setSourceText: chat.setSourceText,
    uploadSource: () => void chat.uploadSource(),
    chatBusy,
    uploadBusy,
    workspace,
    latestTask,
    latestWorkspaceTaskWaitSignals: taskPresentation.waitSignals,
    latestWorkspaceTaskWaitDetails: taskPresentation.waitDetails,
    latestWorkspaceProgressEvent: taskPresentation.progressEvent,
    taskEvents,
    deleteSource: (source: SourceAsset) => void chat.deleteSource(source),
    buildSourceOriginBadge,
    buildGenericTaskRuntimeSnapshot,
    buildTaskEventNarrative,
    messages: chat.messages,
    question: chat.question,
    setQuestion: chat.setQuestion,
    currentRouteLabel: chat.currentRoute().label,
    selectedQaSourceIds: chat.selectedQaSourceIds,
    setSelectedQaSourceIds: chat.setSelectedQaSourceIds,
    toggleQaScope: chat.toggleQaScope,
    sendMessage: () => void chat.sendMessage(),
    conversation,
    setArtifactComposerOpen,
    artifactComposerOpen,
    artifactRailProps
  };
}
