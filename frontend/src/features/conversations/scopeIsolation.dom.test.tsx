// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { answersApi } from "../answers/api";
import { sourcesApi } from "../sources/api";
import { useChatSourceActions } from "./useChatSourceActions";
import { useChatSessionController, type UseChatSessionControllerInput } from "./useChatSessionController";

vi.mock("../../shared/event-stream", () => ({
  ConversationStreamClient: class { start() {} stop() {} }
}));
vi.mock("../../shared/event-stream/streamEvent", () => ({ streamEvents: vi.fn(async () => undefined) }));

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("资料与 Note 草稿作用域", () => {
  it("旧工作台资料列表晚返回时不会覆盖新工作台或刷新旧 Wiki", async () => {
    const response = deferred<Awaited<ReturnType<typeof sourcesApi.list>>>();
    vi.spyOn(sourcesApi, "uploadText").mockResolvedValue({ task_id: "" } as Awaited<ReturnType<typeof sourcesApi.uploadText>>);
    vi.spyOn(sourcesApi, "list").mockReturnValue(response.promise);
    const first = sourceProps("A");
    const { result, rerender } = renderHook(props => useChatSourceActions(props), { initialProps: first });
    let uploading!: Promise<void>;
    act(() => { uploading = result.current.uploadSource(); });
    await waitFor(() => expect(sourcesApi.list).toHaveBeenCalledWith("A"));
    rerender({ ...first, workspace: workspace("B"), conversation: conversation("B") });
    await act(async () => { response.resolve([]); await uploading; });
    expect(first.setSources).not.toHaveBeenCalled();
    expect(first.refreshWikiHome).not.toHaveBeenCalled();
  });

  it("A 切到 B 再切回 A 也不会接受首次上传的旧回调", async () => {
    const response = deferred<Awaited<ReturnType<typeof sourcesApi.uploadText>>>();
    vi.spyOn(sourcesApi, "uploadText").mockReturnValue(response.promise);
    const list = vi.spyOn(sourcesApi, "list").mockResolvedValue([]);
    const first = sourceProps("A");
    const { result, rerender } = renderHook(props => useChatSourceActions(props), { initialProps: first });
    let uploading!: Promise<void>;
    act(() => { uploading = result.current.uploadSource(); });
    rerender({ ...first, workspace: workspace("B") });
    rerender(first);
    await act(async () => { response.resolve({ task_id: "" } as Awaited<ReturnType<typeof sourcesApi.uploadText>>); await uploading; });
    expect(list).not.toHaveBeenCalled();
    expect(first.setSources).not.toHaveBeenCalled();
  });

  it("切换工作台立即隐藏旧 Note 草稿，旧保存回调也失效", async () => {
    prepareAnswer();
    const save = vi.spyOn(sourcesApi, "saveAnswerAsSource");
    const first = chatProps("A");
    const { result, rerender } = renderHook(props => useChatSessionController(props), { initialProps: first });
    await act(async () => { await result.current.sendConversationPrompt("整理笔记", "提问", { mode: "note" }); });
    expect(result.current.lastNoteAssistantMessageId).toBe("message-A");
    expect(result.current.sourceDraftContent).toBe("A 的入库正文");
    const oldSave = result.current.saveNoteAnswerAsSource;
    rerender({ ...first, workspace: workspace("B"), conversation: conversation("B") });
    expect(result.current.lastNoteAssistantMessageId).toBe("");
    expect(result.current.sourceDraftContent).toBe("");
    await act(async () => { await oldSave(); await result.current.saveNoteAnswerAsSource(); });
    expect(save).not.toHaveBeenCalled();
  });

  it("旧会话草稿晚返回不会写入新会话", async () => {
    prepareAnswer();
    const draft = deferred<Awaited<ReturnType<typeof sourcesApi.buildNoteSourceDraft>>>();
    vi.mocked(sourcesApi.buildNoteSourceDraft).mockReturnValue(draft.promise);
    const first = chatProps("A");
    const { result, rerender } = renderHook(props => useChatSessionController(props), { initialProps: first });
    let sending!: Promise<void>;
    act(() => { sending = result.current.sendConversationPrompt("整理笔记", "提问", { mode: "note" }); });
    await waitFor(() => expect(sourcesApi.buildNoteSourceDraft).toHaveBeenCalled());
    rerender({ ...first, conversation: conversation("B") });
    await act(async () => { draft.resolve({ message_id: "message-A", source_content: "原回答", title: "旧标题", content: "旧正文", rewrite_mode: "raw" }); await sending; });
    expect(result.current.sourceDraftContent).toBe("");
    expect(result.current.lastNoteAssistantMessageId).toBe("");
  });
});

function prepareAnswer() {
  vi.spyOn(answersApi, "send").mockResolvedValue({ answer_run_id: "run-A", assistant_message_id: "message-A", answer_stream_url: "/stream" } as Awaited<ReturnType<typeof answersApi.send>>);
  vi.spyOn(answersApi, "getRun").mockResolvedValue({ id: "run-A", status: "COMPLETED", content: "A 的回答" });
  vi.spyOn(sourcesApi, "buildNoteSourceDraft").mockResolvedValue({ message_id: "message-A", source_content: "A 的回答", title: "A 的标题", content: "A 的入库正文", rewrite_mode: "raw" });
}

function chatProps(key: string): UseChatSessionControllerInput {
  return { workspace: workspace(key), conversation: conversation(key), statusActive: true,
    run: async (_label, action) => { await action(); }, setStatus: vi.fn(), sources: [], setSources: vi.fn(),
    messages: [], setMessages: vi.fn(), refreshWikiHome: vi.fn(async () => undefined), refreshWikiFromServer: vi.fn(async () => undefined),
    wikiEnabled: true, wikiGraphMode: "global", wikiGraphKindFilters: [],
    loadExecution: vi.fn(async () => ({ task: { task_status: "COMPLETED" } })), setLatestTaskId: vi.fn(), selectedWikiItemId: "" };
}

function sourceProps(key: string): Parameters<typeof useChatSourceActions>[0] {
  return { ...chatProps(key), sourceText: "资料", setSourceText: vi.fn(), appendSystemMessage: vi.fn(),
    lastNoteAssistantMessageId: "", lastNoteAnswerContent: "", sourceDraftTitle: "草稿", sourceDraftContent: "",
    sourceDraftRewriteMode: "", setSourceDraftTitle: vi.fn(), setSourceDraftContent: vi.fn(), setSourceDraftRewriteMode: vi.fn() };
}

function workspace(key: string) { return { workspace_id: key, name: key, status: "ACTIVE" }; }
function conversation(key: string) { return { conversation_id: key, title: key }; }
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
