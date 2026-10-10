import type { SourceLibraryWorkbenchProps } from "./SourceLibraryWorkbench";
import type { SourceAsset, SourceReprocessResult } from "./model";
import type { Workspace } from "../workspace/model";
import type { ChatSessionController } from "../conversations/useChatSessionController";

type BuildSourceLibraryWorkbenchPropsInput = {
  chat: ChatSessionController;
  sources: SourceAsset[];
  workspace: Workspace | null;
  uploadBusy: boolean;
  refreshSources: () => Promise<void>;
  reprocessSource: (sourceId: string) => Promise<SourceReprocessResult>;
};

export function buildSourceLibraryWorkbenchProps(
  input: BuildSourceLibraryWorkbenchPropsInput
): SourceLibraryWorkbenchProps {
  const { chat, sources, workspace, uploadBusy } = input;
  return {
    sources,
    sourceText: chat.sourceText,
    setSourceText: chat.setSourceText,
    uploadSource: () => void chat.uploadSource(),
    uploadSourceFile: chat.uploadSourceFile,
    uploads: chat.uploads,
    dismissUpload: chat.dismissUpload,
    workspace,
    deleteSource: (source) => void chat.deleteSource(source),
    refreshSources: input.refreshSources,
    reprocessSource: input.reprocessSource,
    sourcesBusy: uploadBusy
  };
}
