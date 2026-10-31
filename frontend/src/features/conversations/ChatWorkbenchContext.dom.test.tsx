// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ChatWorkbench } from "./ChatWorkbench";
import { researchReportRefs } from "./useChatSessionController";

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
  it("offers a stop action only while an answer is generating", () => {
    const onCancelAnswer = vi.fn();
    const { rerender } = render(<ChatWorkbench {...buildProps({
      chatBusy: true,
      onCancelAnswer,
      messages: [{ role: "assistant", content: "", answerStatus: "GENERATING", answerRunId: "run-1" }]
    })} />);

    fireEvent.click(screen.getByRole("button", { name: "停止生成回答" }));
    expect(onCancelAnswer).toHaveBeenCalledWith("run-1");

    // 回答已结束：停止入口消失，避免对已完成的运行发取消请求
    rerender(<ChatWorkbench {...buildProps({
      chatBusy: false,
      onCancelAnswer,
      messages: [{ role: "assistant", content: "结论", answerStatus: "COMPLETED", answerRunId: "run-1" }]
    })} />);
    expect(screen.queryByRole("button", { name: "停止生成回答" })).toBeNull();
  });
});

describe("ChatWorkbench deep research", () => {
  it("深度研究开关打开后这一轮发起研究，不需要已建索引的资料", () => {
    const setDeepResearch = vi.fn();
    const { rerender } = render(<ChatWorkbench {...buildProps({ sources: [], question: "对比 Kafka 与 Pulsar", setDeepResearch })} />);

    const toggle = screen.getByRole("button", { name: "深度研究" });
    expect(toggle.getAttribute("aria-pressed")).toBe("false");
    fireEvent.click(toggle);
    expect(setDeepResearch).toHaveBeenCalledWith(true);

    rerender(<ChatWorkbench {...buildProps({ sources: [], question: "对比 Kafka 与 Pulsar", setDeepResearch, deepResearch: true })} />);
    const send = screen.getByRole("button", { name: "开始深度研究" }) as HTMLButtonElement;
    expect(send.disabled).toBe(false);
    expect(screen.getByText(/会带上这段对话的上文/)).toBeTruthy();
  });

  it("研究轮次在对话里显示研究进度与报告，查看完整报告跳到研究页", async () => {
    const onOpenResearchRun = vi.fn();
    const loadResearchRun = vi.fn(async () => ({
      research_run_id: "run-9",
      question: "对比 Kafka 与 Pulsar",
      status: "COMPLETED",
      completion_terminal_state: "COMPLETED",
      final_report_title: "Kafka 与 Pulsar 的顺序保证",
      final_report_markdown: ["# 报告", "", "Kafka 只在分区内有序 [evidence:e1]。", "", "## 直接回答", "", "细节 [evidence:e1]。"].join("\n"),
      saved_report_source: null,
      closed_loop_state: { rows: [], cells: [], source_evidence: [], loop_rounds: [] },
      research_process_summary: { search_read_timeline: { rounds: [] } }
    }));
    render(<ChatWorkbench {...buildProps({
      messages: [
        { role: "user", content: "对比 Kafka 与 Pulsar" },
        { role: "assistant", content: "", researchRunId: "run-9", answerStatus: "GENERATING" }
      ],
      loadResearchRun,
      onOpenResearchRun
    })} />);

    const report = await screen.findByRole("article", { name: "研究报告：Kafka 与 Pulsar 的顺序保证" });
    expect(loadResearchRun).toHaveBeenCalledWith("run-9");
    // 问答页的研究消息只保留跳到完整报告的入口
    expect(screen.queryByRole("button", { name: "导出报告" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /查看完整报告/ }));
    expect(onOpenResearchRun).toHaveBeenCalledWith("run-9");
    expect(report).toBeTruthy();
  });

  it("对话里有研究报告时，问答可以依据报告作答，不需要工作台资料", () => {
    const setReportGrounding = vi.fn();
    const props = { sources: [], question: "重平衡时顺序会怎样？", reportRefs: ["run-9"], setReportGrounding };
    const { rerender } = render(<ChatWorkbench {...buildProps({ ...props, reportGrounding: true })} />);

    const toggle = screen.getByRole("button", { name: "依据研究报告" });
    expect(toggle.getAttribute("aria-pressed")).toBe("true");
    expect((screen.getByRole("button", { name: "发送到 问答" }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.getByText(/引用报告里核对过的网页原文/)).toBeTruthy();

    fireEvent.click(toggle);
    expect(setReportGrounding).toHaveBeenCalledWith(false);

    // 关掉之后回到普通问答：没有可检索资料时不能发送
    rerender(<ChatWorkbench {...buildProps({ ...props, reportGrounding: false })} />);
    expect((screen.getByRole("button", { name: "等待可检索资料" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("其他回答模式和没有研究的对话不显示研究报告开关", () => {
    const { rerender } = render(<ChatWorkbench {...buildProps({ mode: "note", reportRefs: ["run-9"],
      reportGrounding: true, setReportGrounding: vi.fn() })} />);
    expect(screen.queryByRole("button", { name: "依据研究报告" })).toBeNull();

    rerender(<ChatWorkbench {...buildProps({ reportRefs: [], reportGrounding: true, setReportGrounding: vi.fn() })} />);
    expect(screen.queryByRole("button", { name: "依据研究报告" })).toBeNull();
  });
});

describe("researchReportRefs", () => {
  it("取对话里最近的研究编号，去重且最多 3 个", () => {
    expect(researchReportRefs([
      { role: "assistant", content: "", researchRunId: "r1" },
      { role: "user", content: "追问" },
      { role: "assistant", content: "", researchRunId: "r2" },
      { role: "assistant", content: "", researchRunId: "r3" },
      { role: "assistant", content: "", researchRunId: "r2" },
      { role: "assistant", content: "", researchRunId: "r4" }
    ])).toEqual(["r4", "r2", "r3"]);
    expect(researchReportRefs([{ role: "assistant", content: "普通回答" }])).toEqual([]);
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
