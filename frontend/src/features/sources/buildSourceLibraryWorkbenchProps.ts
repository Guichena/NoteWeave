import type { SourceLibraryWorkbenchProps } from "./SourceLibraryWorkbench";
import type { SourceAsset } from "./model";
import type { Workspace } from "../workspace/model";
import type { ChatSessionController } from "../conversations/useChatSessionController";
import type { ExecutionEvent, ExecutionTask } from "../executions/model";
import type { SignalChip } from "../research/model";
import type { WaitContextDetailLine } from "../../runStatus";
import {
  buildGenericTaskRuntimeSnapshot,
  buildSourceOriginBadge,
  buildTaskEventNarrative
} from "../research/presentation";

type BuildSourceLibraryWorkbenchPropsInput = {
  chat: ChatSessionController;
  sources: SourceAsset[];
  workspace: Workspace | null;
  conversationCount: number;
  uploadBusy: boolean;
  latestTask: ExecutionTask | null;
  taskEvents: ExecutionEvent[];
  taskPresentation: {
    waitSignals: SignalChip[];
    waitDetails: WaitContextDetailLine[];
    progressEvent: ExecutionEvent | null;
  };
};

export function buildSourceLibraryWorkbenchProps(
  input: BuildSourceLibraryWorkbenchPropsInput
): SourceLibraryWorkbenchProps {
  const { chat, sources, workspace, conversationCount, uploadBusy, latestTask, taskEvents, taskPresentation } = input;
  return {
    sources,
    sourceText: chat.sourceText,
    setSourceText: chat.setSourceText,
    uploadSource: () => void chat.uploadSource(),
    uploadSourceFile: chat.uploadSourceFile,
    workspace,
    conversationCount,
    latestTask,
    latestWorkspaceTaskWaitSignals: taskPresentation.waitSignals,
    latestWorkspaceTaskWaitDetails: taskPresentation.waitDetails,
    latestWorkspaceProgressEvent: taskPresentation.progressEvent,
    taskEvents,
    deleteSource: (source) => void chat.deleteSource(source),
    buildSourceOriginBadge,
    buildGenericTaskRuntimeSnapshot,
    buildTaskEventNarrative,
    sourcesBusy: uploadBusy
  };
}
