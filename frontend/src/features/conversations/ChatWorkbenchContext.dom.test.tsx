// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ChatWorkbench } from "./ChatWorkbench";

const scrollHeightDescriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "scrollHeight");

afterEach(() => {
  cleanup();
  if (scrollHeightDescriptor) {
    Object.defineProperty(HTMLElement.prototype, "scrollHeight", scrollHeightDescriptor);
  } else {
    Reflect.deleteProperty(HTMLElement.prototype, "scrollHeight");
  }
});

describe("ChatWorkbench notebook surface", () => {
  it("renders the conversation with a switchable sources / studio panel", async () => {
    render(<ChatWorkbench {...buildProps()} />);

    expect(screen.getByRole("heading", { name: "政策研究会话" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "今天想从资料里弄清楚什么？" })).toBeTruthy();
    expect(screen.queryByRole("dialog", { name: "产物工作台" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "打开来源" }));
    expect(screen.getByRole("complementary", { name: "来源与产物" })).toBeTruthy();
    expect(screen.getByRole("region", { name: "来源" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "打开产物" }));
    await waitFor(() => expect(document.querySelector(".studio-pane .artifact-rail")).toBeTruthy());
    expect(screen.queryByRole("region", { name: "来源" })).toBeNull();
  });

  it("keeps an empty welcome state at the top and scrolls only after messages exist", () => {
    Object.defineProperty(HTMLElement.prototype, "scrollHeight", {
      configurable: true,
      get: () => 420
    });
    const { rerender } = render(<ChatWorkbench {...buildProps()} />);
    const conversation = document.querySelector<HTMLElement>(".conversation");
    expect(conversation?.scrollTop).toBe(0);

    rerender(<ChatWorkbench {...buildProps({ messages: [{ role: "user", content: "测试问题" }] })} />);
    expect(conversation?.scrollTop).toBe(420);
  });

  it("routes an empty workspace to the library instead of offering unusable prompts", () => {
    const onOpenSourceLibrary = vi.fn();
    render(<ChatWorkbench {...buildProps({ sources: [], onOpenSourceLibrary })} />);

    expect(screen.queryByRole("button", { name: "这份资料的核心结论是什么？" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "添加资料" }));
    expect(onOpenSourceLibrary).toHaveBeenCalledOnce();
  });

  it("keeps readable-only sources out of the QA retrieval scope", () => {
    render(<ChatWorkbench {...buildProps({
      sources: [{ source_id: "source-1", title: "本地资料.md", status: "READY", index_status: "DISABLED" }],
      question: "这份资料讲了什么？"
    })} />);
    fireEvent.click(screen.getByRole("button", { name: "打开来源" }));

    expect(screen.getByText("1 份仅可阅读 · 无检索索引")).toBeTruthy();
    expect(screen.getByRole("button", { name: "等待可检索资料" }).hasAttribute("disabled")).toBe(true);
    expect(screen.queryByRole("checkbox", { name: "在问答中使用 本地资料.md" })).toBeNull();
  });

  it("maps source checkboxes to the QA scope", () => {
    const setSelectedQaSourceIds = vi.fn();
    render(<ChatWorkbench {...buildProps({
      sources: [
        { source_id: "a", title: "A.pdf", status: "READY", index_status: "INDEXED" },
        { source_id: "b", title: "B.pdf", status: "READY", index_status: "INDEXED" }
      ],
      setSelectedQaSourceIds
    })} />);
    fireEvent.click(screen.getByRole("button", { name: "打开来源" }));

    const checkboxA = screen.getByRole("checkbox", { name: "在问答中使用 A.pdf" }) as HTMLInputElement;
    expect(checkboxA.checked).toBe(true);
    fireEvent.click(checkboxA);
    expect(setSelectedQaSourceIds).toHaveBeenCalledWith(["b"]);
  });

  it("selects the next answer mode from the composer", () => {
    const setMode = vi.fn();
    render(<ChatWorkbench {...buildProps({ setMode })} />);

    const group = screen.getByRole("radiogroup", { name: "回答模式" });
    expect(screen.getByRole("radio", { name: /问答/ }).getAttribute("aria-checked")).toBe("true");
    expect(screen.getByText(/问答：关键词与语义混合检索/)).toBeTruthy();

    fireEvent.click(screen.getByRole("radio", { name: /Wiki/ }));
    expect(setMode).toHaveBeenCalledWith("wiki");

    setMode.mockClear();
    fireEvent.keyDown(group, { key: "ArrowRight" });
    expect(setMode).toHaveBeenCalledWith("note");
  });

  it("opens the Studio on its overview and toggles the panel from the header", async () => {
    const setArtifactComposerOpen = vi.fn();
    render(<ChatWorkbench {...buildProps({ setArtifactComposerOpen })} />);

    const studio = screen.getByRole("button", { name: "打开产物" });
    fireEvent.click(studio);
    expect(setArtifactComposerOpen).toHaveBeenCalledWith(false);
    expect(document.querySelector(".chat-page")?.getAttribute("data-panel-tab")).toBe("studio");
    await waitFor(() => expect(screen.getByRole("button", { name: "关闭产物工作台" })).toBeTruthy());

    fireEvent.click(studio);
    expect(document.querySelector(".chat-page")?.hasAttribute("data-panel-tab")).toBe(false);
  });
});

function buildProps(overrides: Record<string, unknown> = {}) {
  return {
    mode: "qa",
    setMode: vi.fn(),
    sources: [{ source_id: "source-1", title: "架构说明.pdf", status: "READY", index_status: "INDEXED" }],
    sourceText: "",
    setSourceText: vi.fn(),
    uploadSource: vi.fn(),
    uploadSourceFile: vi.fn(async () => undefined),
    chatBusy: false,
    uploadBusy: false,
    workspace: { workspace_id: "workspace-1", name: "企业研究工作台", status: "ACTIVE" },
    latestTask: null,
    latestWorkspaceTaskWaitSignals: [],
    latestWorkspaceTaskWaitDetails: [],
    latestWorkspaceProgressEvent: null,
    taskEvents: [],
    deleteSource: vi.fn(),
    buildSourceOriginBadge: vi.fn(() => "workspace"),
    buildGenericTaskRuntimeSnapshot: vi.fn(() => ""),
    buildTaskEventNarrative: vi.fn(() => ""),
    messages: [],
    question: "",
    setQuestion: vi.fn(),
    currentRouteLabel: "问答",
    selectedQaSourceIds: [],
    setSelectedQaSourceIds: vi.fn(),
    toggleQaScope: vi.fn(),
    sendMessage: vi.fn(),
    conversation: { conversation_id: "conversation-1", title: "政策研究会话" },
    conversationCount: 2,
    setArtifactComposerOpen: vi.fn(),
    artifactComposerOpen: false,
    artifactRailProps: {
      artifactComposerOpen: false,
      isBusy: false,
      setArtifactComposerOpen: vi.fn(),
      renderArtifactField: vi.fn(),
      artifactCustomInstruction: "",
      setArtifactCustomInstruction: vi.fn(),
      appendArtifactHint: vi.fn(),
      workspace: { workspace_id: "workspace-1", name: "企业研究工作台", status: "ACTIVE" },
      prepareArtifactPrompt: vi.fn(() => ""),
      isArtifactFormReady: vi.fn(() => true),
      artifactFormValues: {},
      launchArtifactPrompt: vi.fn(),
      artifactStudioSkills: [],
      artifactSkillsLoading: false,
      artifactJobsLoading: false,
      sourceCount: 1,
      setSelectedArtifactSkillKey: vi.fn(),
      setArtifactFormValues: vi.fn(),
      artifactJobs: [],
      selectedArtifactHistoryVersion: null,
      latestArtifactVersion: null,
      formatRelativeTime: vi.fn(() => "刚刚"),
      saveArtifactVersionAsSource: vi.fn(),
      artifactSavedSourceByVersionId: {},
      artifactVersionSaveKey: vi.fn(() => ""),
      writeArtifactVersionToKnowledge: vi.fn(),
      artifactWritebackByVersionId: {},
      regenerateArtifactVersion: vi.fn(),
      compareArtifactWithPreviousVersion: vi.fn(),
      rollbackArtifactVersion: vi.fn(),
      downloadArtifactVersionPdf: vi.fn(),
      resolveArtifactSkillTitle: vi.fn(() => "产物"),
      openArtifactHistoryVersion: vi.fn(),
      artifactHistoryLoadingKey: "",
      sourceDraftTitle: "",
      setSourceDraftTitle: vi.fn(),
      sourceDraftContent: "",
      setSourceDraftContent: vi.fn(),
      sourceDraftRewriteMode: "",
      rewriteNoteSourceDraft: vi.fn(),
      saveNoteAnswerAsSource: vi.fn(),
      lastNoteAssistantMessageId: "",
      wikiRebuildAdvice: null
    },
    onOpenSourceLibrary: vi.fn(),
    ...overrides
  } as any;
}
