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

describe("ChatWorkbench context surface", () => {
  it("connects the welcome state to the real workspace and QA scope", () => {
    render(<ChatWorkbench {...buildProps()} />);

    expect(screen.getAllByText("1 / 1 份可检索").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("企业研究工作台").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByRole("heading", { name: "政策研究会话" })).toBeTruthy();
    expect(screen.getByText("1 份工作台资料")).toBeTruthy();
    expect(screen.getByText("2 个会话")).toBeTruthy();
    expect(screen.getByText("1 份资料 · 2 个会话共用")).toBeTruthy();
    expect(screen.getByLabelText("工作台资料与当前会话的关系")).toBeTruthy();
    expect(screen.getByText("基于工作台资料回答，展示引用与证据；回答不入库。")).toBeTruthy();
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
    fireEvent.click(screen.getByRole("button", { name: "前往资料库添加资料" }));
    expect(onOpenSourceLibrary).toHaveBeenCalledOnce();
  });

  it("keeps readable-only sources out of the QA retrieval scope", () => {
    render(<ChatWorkbench {...buildProps({
      sources: [{ source_id: "source-1", title: "本地资料.md", status: "READY", index_status: "DISABLED" }],
      question: "这份资料讲了什么？"
    })} />);

    expect(screen.getAllByText("1 份仅可阅读 · 无检索索引").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/1 份资料仅可阅读，建立索引后才能参与 RAG/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "等待可检索资料" }).hasAttribute("disabled")).toBe(true);
    expect(screen.queryByText("指定本次 QA 的资料范围（可选）")).toBeNull();
  });

  it("selects the next answer mode from the composer instead of the session header", () => {
    const setMode = vi.fn();
    render(<ChatWorkbench {...buildProps({ setMode })} />);

    expect(screen.queryByRole("tablist", { name: "回答模式" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "回答模式：问答 RAG" }));
    expect(screen.getByRole("menu", { name: "选择回答模式" })).toBeTruthy();
    fireEvent.click(screen.getByRole("menuitemradio", { name: /Wiki/ }));
    expect(setMode).toHaveBeenCalledWith("wiki");
    expect(screen.queryByRole("menu", { name: "选择回答模式" })).toBeNull();
  });

  it("opens Artifact Studio on its overview instead of forcing the first composer", async () => {
    const setArtifactComposerOpen = vi.fn();
    render(<ChatWorkbench {...buildProps({ setArtifactComposerOpen })} />);

    fireEvent.click(screen.getByRole("button", { name: "打开产物" }));
    expect(setArtifactComposerOpen).toHaveBeenCalledWith(false);
    expect(document.querySelector(".layout")?.classList.contains("layout-with-inspector")).toBe(false);
    expect(screen.getByLabelText("资料库摘要")).toBeTruthy();
    expect(document.querySelector(".artifact-rail")).toBeTruthy();
    expect(screen.getByRole("dialog", { name: "产物工作台" }).getAttribute("aria-modal")).toBe("true");

    await waitFor(() => expect(screen.getByRole("button", { name: "关闭产物工作台" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "关闭产物工作台" }));
    expect(screen.queryByRole("dialog", { name: "产物工作台" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "打开产物" }));
    fireEvent.click(document.querySelector(".artifact-modal-backdrop") as HTMLElement);
    expect(screen.queryByRole("dialog", { name: "产物工作台" })).toBeNull();
  });

  it("keeps Artifact Studio open when a nested dialog handles Escape", async () => {
    render(<ChatWorkbench {...buildProps()} />);

    fireEvent.click(screen.getByRole("button", { name: "打开产物" }));
    await waitFor(() => expect(screen.getByRole("dialog", { name: "产物工作台" })).toBeTruthy());

    const handleNestedDialogEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") event.preventDefault();
    };
    window.addEventListener("keydown", handleNestedDialogEscape, true);
    fireEvent.keyDown(window, { key: "Escape" });
    window.removeEventListener("keydown", handleNestedDialogEscape, true);

    expect(screen.getByRole("dialog", { name: "产物工作台" })).toBeTruthy();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "产物工作台" })).toBeNull();
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
    currentRouteLabel: "问答 RAG",
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
      artifactSidebarState: {
        runs: [],
        historyItems: [],
        latestAuditView: {},
        historyViewer: { activeKey: null, activeVersion: null }
      },
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
      summarizeRunStatus: vi.fn(() => "就绪"),
      openArtifactHistoryVersion: vi.fn(),
      artifactHistoryLoadingKey: "",
      openWikiHome: vi.fn(),
      openResearchWorkbench: vi.fn(),
      openMemoryWorkbench: vi.fn(),
      toggleWikiEnabled: vi.fn(),
      wikiEnabled: false,
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
