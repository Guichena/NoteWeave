import { useState } from "react";
import { routes, type AnswerMode } from "./routes";

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "";

type Workspace = {
  workspace_id: string;
  name: string;
  status: string;
};

type Conversation = {
  conversation_id: string;
  title: string;
};

type Message = {
  role: "user" | "assistant" | "system";
  content: string;
};

type TaskStatus = {
  task_id: string;
  task_type: string;
  task_status: string;
  progress_phase: string;
  progress_message: string;
  result_ref: string;
  error_message: string;
};

type WikiPage = {
  item_id: string;
  item_type: string;
  title: string;
  latest_version_id: string;
  latest_version_no: number;
  summary: string;
};

type WikiLink = {
  source_item_id: string;
  target_item_id: string | null;
  target_title: string;
  relation_type: string;
  relation_status: string;
  mention_count: number;
};

type WikiHome = {
  workspace_id: string;
  wiki_url: string;
  pages: WikiPage[];
  links: WikiLink[];
};

type WikiSettings = {
  workspace_id: string;
  wiki_enabled: boolean;
};

type WikiRebuild = {
  workspace_id: string;
  source_count: number;
  task_count: number;
  task_ids: string[];
};

type WikiStats = {
  page_count: number;
  link_count: number;
  resolved_link_count: number;
  unresolved_link_count: number;
  citation_count: number;
  issue_count: number;
};

type WikiIssue = {
  issue_type: string;
  severity: string;
  title: string;
  message: string;
  suggested_action: string;
};

type WikiLogEntry = {
  id: string;
  event_type: string;
  message: string;
  created_at: string;
};

type WikiGraph = {
  nodes: Array<{ item_id: string; title: string; page_kind: string; version_no: number }>;
  edges: Array<{ source_item_id: string; target_item_id: string | null; target_title: string; relation_status: string }>;
};

type KnowledgeCitation = {
  citation_id: string;
  source_id: string;
  title: string;
  quote_text: string;
  page_no: number | null;
  location_info: string;
};

type KnowledgeItemDetail = WikiPage & {
  content: string;
  citations: KnowledgeCitation[];
};

type ApiResponse<T> = {
  success: boolean;
  code: string;
  message: string;
  data: T;
};

export function App() {
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [mode, setMode] = useState<AnswerMode>("qa");
  const [sourceText, setSourceText] = useState("NoteWeave 阶段3包含问答 RAG、Marginalia 式资料级检索 Note 链路和 WebKonra / WeKnora 式全量 Wiki 检索链路。");
  const [question, setQuestion] = useState("阶段3里 Note 和 Wiki 有什么区别？");
  const [messages, setMessages] = useState<Message[]>([
    {
      role: "assistant",
      content: "先创建工作台并上传一段资料，然后就可以在同一个聊天框里切换问答、Note、Wiki 三种链路。"
    }
  ]);
  const [view, setView] = useState<"chat" | "wiki">("chat");
  const [wikiUrl, setWikiUrl] = useState("");
  const [wikiHome, setWikiHome] = useState<WikiHome | null>(null);
  const [wikiEnabled, setWikiEnabled] = useState(false);
  const [wikiStats, setWikiStats] = useState<WikiStats | null>(null);
  const [wikiIssues, setWikiIssues] = useState<WikiIssue[]>([]);
  const [wikiLog, setWikiLog] = useState<WikiLogEntry[]>([]);
  const [wikiGraph, setWikiGraph] = useState<WikiGraph | null>(null);
  const [selectedWikiItemId, setSelectedWikiItemId] = useState("");
  const [selectedWikiDetail, setSelectedWikiDetail] = useState<KnowledgeItemDetail | null>(null);
  const [lastAssistantMessageId, setLastAssistantMessageId] = useState("");
  const [lastAssistantAnswer, setLastAssistantAnswer] = useState("");
  const [noteTitle, setNoteTitle] = useState("阶段整理笔记");
  const [wikiTitle, setWikiTitle] = useState("阶段知识页");
  const [wikiDraft, setWikiDraft] = useState("");
  const [wikiAppendDraft, setWikiAppendDraft] = useState("");
  const [wikiSearch, setWikiSearch] = useState("");
  const [wikiRenameTitle, setWikiRenameTitle] = useState("");
  const [latestTask, setLatestTask] = useState<TaskStatus | null>(null);
  const [taskEvents, setTaskEvents] = useState<string[]>([]);
  const [isBusy, setIsBusy] = useState(false);
  const [status, setStatus] = useState("准备就绪");

  async function createWorkspace() {
    await run("创建工作台", async () => {
      const created = await post<Workspace>("/api/v2/workspaces", {
        name: "NoteWeave v2 研究工作台",
        description: "阶段1/2/3前端联调工作台"
      });
      setWorkspace(created);
      const wikiSettings = await get<WikiSettings>(`/api/v2/workspaces/${created.workspace_id}/wiki-settings`);
      setWikiEnabled(wikiSettings.wiki_enabled);
      const createdConversation = await post<Conversation>(`/api/v2/workspaces/${created.workspace_id}/conversations`, {
        title: "阶段1/2/3联调会话",
        conversation_type: "WORKSPACE_CHAT"
      });
      setConversation(createdConversation);
      setMessages((current) => [
        ...current,
        { role: "system", content: `已创建工作台 ${created.name}，并创建默认会话。` }
      ]);
    });
  }

  async function uploadSource() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("上传资料并解析", async () => {
      const bytes = new TextEncoder().encode(sourceText);
      const upload = await post<{ upload_id: string }>(`/api/v2/workspaces/${workspace.workspace_id}/uploads`, {
        file_name: "frontend-source.md",
        file_size: bytes.byteLength,
        mime_type: "text/markdown",
        chunk_size: bytes.byteLength,
        total_chunks: 1
      });
      await request(`/api/v2/uploads/${upload.upload_id}/chunks/0`, {
        method: "PUT",
        headers: { "Content-Type": "application/octet-stream" },
        body: bytes
      });
      const completed = await post<{ source_id: string; task_id: string; parse_status: string; index_status: string }>(
        `/api/v2/uploads/${upload.upload_id}/complete`,
        {}
      );
      const task = await get<TaskStatus>(`/api/v2/tasks/${completed.task_id}`);
      const eventStream = await requestText(`/api/v2/tasks/${completed.task_id}/events`);
      const events = parseEventStream(eventStream).map((event) => `${event.event}: ${event.data}`);
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      setLatestTask(task);
      setTaskEvents(events);
      setWikiHome(wiki);
      setWikiUrl(wiki.wiki_url);
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: `资料已上传并解析：source=${completed.source_id}，parse=${completed.parse_status}，index=${completed.index_status}，task=${task.task_status}。${wikiEnabled ? "Wiki 构建已开启，本次资料变更已进入 Wiki ingest 队列。" : "Wiki 构建未开启，本次只进入问答/Note 检索索引。"}`
        }
      ]);
    });
  }

  async function toggleWikiEnabled() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run(wikiEnabled ? "关闭 Wiki 构建" : "开启 Wiki 构建", async () => {
      const next = !wikiEnabled;
      const settings = await put<WikiSettings>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-settings`, {
        wiki_enabled: next
      });
      setWikiEnabled(settings.wiki_enabled);
      if (settings.wiki_enabled) {
        const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
        await applyWikiHome(wiki, selectedWikiItemId);
      }
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: settings.wiki_enabled
            ? "已开启工作台级 Wiki 构建。已有资料会自动回补，后续资料上传或更新会进入 Wiki ingest 队列。"
            : "已关闭工作台级 Wiki 构建。资料上传只进入普通检索索引。"
        }
      ]);
    });
  }

  async function sendMessage() {
    if (!conversation) {
      setStatus("请先创建工作台和会话");
      return;
    }
    const trimmed = question.trim();
    if (!trimmed) {
      return;
    }
    await run(`${currentRoute().label} 提问`, async () => {
      setMessages((current) => [...current, { role: "user", content: trimmed }]);
      const sent = await post<{ assistant_message_id: string; assistant_request_id: string; stream_url: string }>(
        `/api/v2/conversations/${conversation.conversation_id}/messages`,
        {
          content: trimmed,
          answer_mode: mode.toUpperCase(),
          client_request_id: `${mode}-${Date.now()}`
        }
      );
      const stream = await requestText(sent.stream_url);
      const parsed = parseChatStream(stream);
      const citationBlock = parsed.citations.length
        ? `\n\n引用来源：\n${parsed.citations.map((citation, index) => `${index + 1}. ${citation}`).join("\n")}`
        : "";
      const answer = `${parsed.answer || "后端已完成回答，但没有返回 delta 内容。"}${citationBlock}`;
      setLastAssistantMessageId(sent.assistant_message_id);
      setLastAssistantAnswer(answer);
      setWikiDraft((current) => current || answer);
      setMessages((current) => [...current, { role: "assistant", content: answer }]);
    });
  }

  async function openWikiHome() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("读取默认 Wiki 工作台入口", async () => {
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, selectedWikiItemId || wiki.pages[0]?.item_id || "");
      setView("wiki");
      window.history.pushState({}, "", wiki.wiki_url);
      setMessages((current) => [
        ...current,
        { role: "system", content: `默认 Wiki 工作台：${wiki.wiki_url}，页面数 ${wiki.pages.length}，链接数 ${wiki.links.length}` }
      ]);
    });
  }

  async function saveLatestAnswerAsNote() {
    if (!lastAssistantMessageId) {
      setStatus("请先完成一次聊天回答");
      return;
    }
    await run("保存最新回答为 Note", async () => {
      const saved = await post<WikiPage>(`/api/v2/messages/${lastAssistantMessageId}/save-as-note`, {
        title: noteTitle
      });
      setMessages((current) => [
        ...current,
        { role: "system", content: `已保存 Note：${saved.title}（v${saved.latest_version_no}）` }
      ]);
    });
  }

  async function createWikiPage() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    const content = wikiDraft.trim();
    if (!content) {
      setStatus("请先填写 Wiki 页面正文。Wiki 是研究工作台级知识网络，不默认绑定最近聊天回答。");
      return;
    }
    await run("创建 Wiki 页面", async () => {
      const created = await post<WikiPage>(`/api/v2/workspaces/${workspace.workspace_id}/knowledge-items`, {
        item_type: "WIKI",
        title: wikiTitle,
        content,
        source_message_id: null
      });
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, created.item_id);
      setView("wiki");
      window.history.pushState({}, "", wiki.wiki_url);
    });
  }

  async function appendWikiVersion() {
    if (!selectedWikiPage) {
      setStatus("请先选择一个 Wiki 页面");
      return;
    }
    const content = wikiAppendDraft.trim();
    if (!content) {
      setStatus("请先填写新的 Wiki 版本正文");
      return;
    }
    await run(`追加 Wiki 页面《${selectedWikiPage.title}》版本`, async () => {
      await post<WikiPage>(`/api/v2/knowledge-items/${selectedWikiPage.item_id}/versions`, {
        content,
        source_message_id: null
      });
      const wiki = workspace ? await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`) : wikiHome;
      if (wiki) {
        await applyWikiHome(wiki, selectedWikiPage.item_id);
      }
      setWikiAppendDraft("");
    });
  }

  async function renameSelectedWikiPage() {
    if (!selectedWikiPage || !workspace) {
      setStatus("请先选择一个 Wiki 页面");
      return;
    }
    const title = wikiRenameTitle.trim();
    if (!title) {
      setStatus("请先填写新的 Wiki 标题");
      return;
    }
    await run(`重命名 Wiki 页面《${selectedWikiPage.title}》`, async () => {
      const renamed = await patch<WikiPage>(`/api/v2/knowledge-items/${selectedWikiPage.item_id}/title`, { title });
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, renamed.item_id);
    });
  }

  async function deleteSelectedWikiPage() {
    if (!selectedWikiPage || !workspace) {
      setStatus("请先选择一个 Wiki 页面");
      return;
    }
    await run(`删除 Wiki 页面《${selectedWikiPage.title}》`, async () => {
      await del(`/api/v2/knowledge-items/${selectedWikiPage.item_id}`);
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, "");
    });
  }

  async function rebuildWikiLinks() {
    if (!workspace) {
      return;
    }
    await run("重建 Wiki 链接", async () => {
      await post<WikiStats>(`/api/v2/workspaces/${workspace.workspace_id}/wiki/rebuild-links`, {});
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, selectedWikiItemId);
    });
  }

  async function rebuildWorkspaceWiki() {
    if (!workspace) {
      return;
    }
    await run("重建工作台 Wiki", async () => {
      const rebuilt = await post<WikiRebuild>(`/api/v2/workspaces/${workspace.workspace_id}/wiki/rebuild`, {});
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, selectedWikiItemId);
      setMessages((current) => [
        ...current,
        { role: "system", content: `已按当前工作台资料重建 Wiki：资料 ${rebuilt.source_count} 个，任务 ${rebuilt.task_count} 个。` }
      ]);
    });
  }

  async function autoFixWiki() {
    if (!workspace) {
      return;
    }
    await run("自动修复 Wiki", async () => {
      await post<{ created_pages: number }>(`/api/v2/workspaces/${workspace.workspace_id}/wiki/auto-fix`, {});
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, selectedWikiItemId);
    });
  }

  async function run(label: string, action: () => Promise<void>) {
    try {
      setIsBusy(true);
      setStatus(`${label}中...`);
      await action();
      setStatus(`${label}完成`);
    } catch (error) {
      setStatus(error instanceof Error ? error.message : `${label}失败`);
    } finally {
      setIsBusy(false);
    }
  }

  function currentRoute() {
    return routes.find((route) => route.key === mode) ?? routes[0];
  }

  function backToChat() {
    setView("chat");
    window.history.pushState({}, "", "/");
  }

  async function selectWikiPage(page: WikiPage) {
    setSelectedWikiItemId(page.item_id);
    await run(`打开 Wiki 页面《${page.title}》`, async () => {
      setSelectedWikiDetail(await get<KnowledgeItemDetail>(`/api/v2/knowledge-items/${page.item_id}`));
      setWikiAppendDraft("");
      setWikiRenameTitle(page.title);
    });
  }

  const selectedWikiPage = wikiHome?.pages.find((page) => page.item_id === selectedWikiItemId) ?? wikiHome?.pages[0];
  const visibleWikiPages = wikiHome?.pages.filter((page) => {
    const keyword = wikiSearch.trim().toLowerCase();
    return !keyword || `${page.title}\n${page.summary}`.toLowerCase().includes(keyword);
  }) ?? [];

  async function applyWikiHome(wiki: WikiHome, preferredItemId: string) {
    setWikiUrl(wiki.wiki_url);
    setWikiHome(wiki);
    setWikiStats(await get<WikiStats>(`/api/v2/workspaces/${wiki.workspace_id}/wiki-stats`));
    setWikiIssues(await get<WikiIssue[]>(`/api/v2/workspaces/${wiki.workspace_id}/wiki-issues`));
    setWikiLog(await get<WikiLogEntry[]>(`/api/v2/workspaces/${wiki.workspace_id}/wiki-log`));
    setWikiGraph(await get<WikiGraph>(`/api/v2/workspaces/${wiki.workspace_id}/wiki-graph`));
    const selected = wiki.pages.find((page) => page.item_id === preferredItemId) ?? wiki.pages[0];
    setSelectedWikiItemId(selected?.item_id ?? "");
    setSelectedWikiDetail(selected ? await get<KnowledgeItemDetail>(`/api/v2/knowledge-items/${selected.item_id}`) : null);
    setWikiRenameTitle(selected?.title ?? "");
  }

  return (
    <main className="shell">
      <section className="hero">
        <p className="eyebrow">NoteWeave v2</p>
        <h1>研究工作台的三链路完整闭环</h1>
        <p className="lede">
          在同一个工作台里完成创建、上传资料、三模式聊天和默认 Wiki 工作台入口。问答 RAG 负责快速证据问答，
          Note 参考 Marginalia 做资料级候选与原文窗口检索，Wiki 参考 WebKonra / WeKnora 做全量 Wiki 页面网络检索。
        </p>
      </section>

      <section className="workspace-card">
        <div>
          <p className="section-label">研究工作台</p>
          <h2>{workspace ? workspace.name : "尚未创建工作台"}</h2>
          <p>{conversation ? `当前会话：${conversation.title}` : "先创建工作台，系统会自动创建一个默认会话。"}</p>
          <p className="status-line">{status}</p>
        </div>
        <div className="action-group">
          {view === "wiki" && (
            <button className="secondary-button" onClick={backToChat}>
              返回聊天
            </button>
          )}
          <button onClick={createWorkspace} disabled={isBusy}>
            创建工作台
          </button>
          <button className="research-button" disabled title="阶段5接入 Deep Research 智能体">
            Deep Research（阶段5）
          </button>
        </div>
      </section>

      {view === "wiki" && wikiHome ? (
        <section className="wiki-workbench">
          <aside className="wiki-index">
            <p className="section-label">Wiki Index</p>
            <h2>默认 Wiki 工作台</h2>
            <p className="wiki-url">{wikiHome.wiki_url}</p>
            <input
              value={wikiSearch}
              onChange={(event) => setWikiSearch(event.target.value)}
              placeholder="搜索页面标题或摘要"
            />
            <div className="wiki-page-list">
              {wikiHome.pages.length === 0 && <p className="empty-state">还没有 Wiki 页面。可以先在 Wiki 模式回答后，将稳定内容创建为 Wiki 页面。</p>}
              {wikiHome.pages.length > 0 && visibleWikiPages.length === 0 && <p className="empty-state">没有匹配的 Wiki 页面。</p>}
              {visibleWikiPages.map((page) => (
                <button
                  key={page.item_id}
                  className={page.item_id === selectedWikiPage?.item_id ? "active" : ""}
                  disabled={isBusy}
                  onClick={() => void selectWikiPage(page)}
                >
                  <span>{page.title}</span>
                  <small>v{page.latest_version_no}</small>
                </button>
              ))}
            </div>
          </aside>

          <article className="wiki-page">
            <p className="section-label">Wiki Page</p>
            {selectedWikiPage ? (
              <>
                <h2>{selectedWikiPage.title}</h2>
                <p className="version-pill">当前版本 v{selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no}</p>
                <div className="wiki-summary">
                  {selectedWikiDetail?.content || selectedWikiPage.summary || "这个页面暂时还没有正文。"}
                </div>
                {selectedWikiDetail?.citations.length ? (
                  <div className="wiki-citations">
                    <strong>来源引用</strong>
                    {selectedWikiDetail.citations.map((citation, index) => (
                      <span key={citation.citation_id}>
                        {index + 1}. {citation.title}：{citation.quote_text}
                      </span>
                    ))}
                  </div>
                ) : null}
                <div className="wiki-maintenance">
                  <strong>页面维护动作</strong>
                  <span>编辑正文会生成新版本；重命名会刷新页面关系；删除采用软删除并保留日志。</span>
                </div>
                <label className="input-block">
                  <span>重命名页面</span>
                  <input value={wikiRenameTitle} onChange={(event) => setWikiRenameTitle(event.target.value)} />
                </label>
                <div className="maintenance-actions">
                  <button onClick={renameSelectedWikiPage} disabled={isBusy}>
                    重命名
                  </button>
                  <button className="danger-button" onClick={deleteSelectedWikiPage} disabled={isBusy}>
                    删除页面
                  </button>
                </div>
                <label className="input-block">
                  <span>追加为新版本</span>
                  <textarea
                    value={wikiAppendDraft}
                    onChange={(event) => setWikiAppendDraft(event.target.value)}
                    placeholder="粘贴或编辑新的 Wiki 页面正文，提交后 latest_version_no 会递增。"
                    rows={6}
                  />
                </label>
                <button onClick={appendWikiVersion} disabled={isBusy}>
                  追加 Wiki 版本
                </button>
              </>
            ) : (
              <div className="empty-state">当前工作台暂无 Wiki 页面。</div>
            )}
          </article>

          <aside className="wiki-links">
            <p className="section-label">页面关系</p>
            {wikiStats && (
              <div className="wiki-maintenance">
                <strong>Wiki 健康度</strong>
                <span>页面 {wikiStats.page_count} · 链接 {wikiStats.link_count} · 已解析 {wikiStats.resolved_link_count} · 断链 {wikiStats.unresolved_link_count} · 引用 {wikiStats.citation_count} · 问题 {wikiStats.issue_count}</span>
              </div>
            )}
            <div className="wiki-maintenance">
              <strong>维护动作</strong>
              <button onClick={rebuildWikiLinks} disabled={isBusy || !workspace}>重建链接</button>
              <button onClick={autoFixWiki} disabled={isBusy || !workspace}>Auto Fix</button>
            </div>
            {wikiHome.links.length === 0 && <p className="empty-state">暂无页面链接。</p>}
            {wikiHome.links.map((link, index) => (
              <div className="link-card" key={`${link.source_item_id}-${link.target_title}-${index}`}>
                <strong>{link.target_title}</strong>
                <span>{link.relation_type} · {link.relation_status} · {link.mention_count} 次提及</span>
              </div>
            ))}
            <p className="section-label">Wiki Graph</p>
            <div className="wiki-maintenance">
              <span>节点 {wikiGraph?.nodes.length ?? 0} · 边 {wikiGraph?.edges.length ?? 0}</span>
            </div>
            <p className="section-label">Lint Issues</p>
            {wikiIssues.length === 0 && <p className="empty-state">暂无健康问题。</p>}
            {wikiIssues.slice(0, 5).map((issue, index) => (
              <div className="link-card" key={`${issue.issue_type}-${issue.title}-${index}`}>
                <strong>{issue.issue_type} · {issue.severity}</strong>
                <span>{issue.message}</span>
              </div>
            ))}
            <p className="section-label">Wiki Log</p>
            {wikiLog.slice(0, 4).map((entry) => (
              <div className="link-card" key={entry.id}>
                <strong>{entry.event_type}</strong>
                <span>{entry.message}</span>
              </div>
            ))}
          </aside>
        </section>
      ) : (
        <section className="layout">
        <div className="chat-panel">
          <div className="mode-tabs">
            {routes.map((route) => (
              <button
                key={route.key}
                className={route.key === mode ? "active" : ""}
                title={route.description}
                onClick={() => setMode(route.key)}
              >
                {route.label}
              </button>
            ))}
          </div>

          <div className="mode-explainer">
            {routes.map((route) => (
              <article key={route.key} className={route.key === mode ? "selected" : ""}>
                <strong>{route.label}</strong>
                <span>{route.description}</span>
              </article>
            ))}
          </div>

          <label className="input-block">
            <span>上传资料内容</span>
            <textarea value={sourceText} onChange={(event) => setSourceText(event.target.value)} rows={4} />
          </label>
          <button onClick={uploadSource} disabled={isBusy || !workspace}>
            上传并解析资料
          </button>
          {latestTask && (
            <div className="task-card">
              <strong>资料处理任务</strong>
              <span>{latestTask.task_type} · {latestTask.task_status} · {latestTask.progress_phase}</span>
              <span>{latestTask.progress_message}</span>
              {taskEvents.map((event, index) => (
                <small key={`${event}-${index}`}>{event}</small>
              ))}
            </div>
          )}

          <div className="conversation">
            {messages.map((message, index) => (
              <div key={`${message.role}-${index}`} className={`bubble ${message.role}`}>
                {message.content}
              </div>
            ))}
          </div>

          <label className="input-block">
            <span>{currentRoute().label} 问题</span>
            <textarea value={question} onChange={(event) => setQuestion(event.target.value)} rows={3} />
          </label>
          <div className="composer-actions">
            <button onClick={sendMessage} disabled={isBusy || !conversation}>
              发送到 {currentRoute().label}
            </button>
          </div>
        </div>

        <aside className="artifact-rail">
          <p className="section-label">工作台级 Wiki</p>
          <button onClick={openWikiHome} disabled={isBusy || !workspace}>
            进入默认 Wiki 工作台
          </button>
          <button onClick={toggleWikiEnabled} disabled={isBusy || !workspace}>
            {wikiEnabled ? "关闭 Wiki 构建" : "开启 Wiki 构建"}
          </button>
          <button onClick={rebuildWorkspaceWiki} disabled={isBusy || !workspace || !wikiEnabled}>
            按当前资料重建 Wiki
          </button>
          <p className="phase-note">
            参考 WeKnora：Wiki 是工作台级索引策略，开启后已有资料会回补，后续资料变化进入异步 Wiki ingest 队列。
          </p>
          {wikiUrl && <p className="wiki-url">{wikiUrl}</p>}
          <button onClick={openWikiHome} disabled={isBusy || !workspace}>
            查看 Wiki Index / 页面关系
          </button>
          <hr />
          <p className="section-label">知识沉淀</p>
          <label className="rail-field">
            <span>Note 标题</span>
            <input value={noteTitle} onChange={(event) => setNoteTitle(event.target.value)} />
          </label>
          <button onClick={saveLatestAnswerAsNote} disabled={isBusy || !lastAssistantMessageId}>
            保存最新回答为 Note
          </button>
          <p className="section-label">Wiki 自动构建</p>
          <p className="phase-note">手动补充只用于编辑修正；主流程是开启 Wiki 构建后由资料变更触发 ingest。</p>
          <label className="rail-field">
            <span>Wiki 标题</span>
            <input value={wikiTitle} onChange={(event) => setWikiTitle(event.target.value)} />
          </label>
          <label className="rail-field">
            <span>Wiki 正文</span>
            <textarea
              value={wikiDraft}
              onChange={(event) => setWikiDraft(event.target.value)}
              placeholder="可选：手动补充或修正工作台级 Wiki 页面正文。资料上传后的 Wiki 页面会自动生成。"
              rows={5}
            />
          </label>
          <button onClick={createWikiPage} disabled={isBusy || !workspace}>
            手动补充 Wiki 页面
          </button>
          <hr />
          <p className="section-label">右侧产物栏</p>
          <p className="phase-note">阶段4接入 Artifact Agent 后启用。</p>
          <button disabled title="阶段4接入 Artifact Agent">生成报告</button>
          <button disabled title="阶段4接入 Artifact Agent">生成 FAQ</button>
          <button disabled title="阶段4接入 Artifact Agent">生成测验</button>
          <button disabled title="阶段4接入 Artifact Agent">生成学习指南</button>
          <button disabled title="阶段4接入 Artifact Agent">导出 Markdown / PDF</button>
        </aside>
      </section>
      )}
    </main>
  );
}

async function get<T>(path: string): Promise<T> {
  const response = await request(path);
  return (await response.json() as ApiResponse<T>).data;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const response = await request(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  return (await response.json() as ApiResponse<T>).data;
}

async function put<T>(path: string, body: unknown): Promise<T> {
  const response = await request(path, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  return (await response.json() as ApiResponse<T>).data;
}

async function patch<T>(path: string, body: unknown): Promise<T> {
  const response = await request(path, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  return (await response.json() as ApiResponse<T>).data;
}

async function del(path: string): Promise<void> {
  await request(path, { method: "DELETE" });
}

async function request(path: string, init?: RequestInit) {
  const url = path.startsWith("http") ? path : `${API_BASE}${path}`;
  const response = await fetch(url, init);
  if (!response.ok) {
    const payload = await response.text();
    throw new Error(payload || `请求失败：${response.status}`);
  }
  return response;
}

async function requestText(path: string) {
  const response = await request(path);
  return response.text();
}

function parseChatStream(stream: string) {
  const answer = parseSse(stream, "chat.delta");
  const citations = parseAllSse(stream, "chat.citation");
  return { answer, citations };
}

function parseSse(stream: string, eventName: string) {
  return parseAllSse(stream, eventName)[0] ?? "";
}

function parseAllSse(stream: string, eventName: string) {
  return parseEventStream(stream)
    .filter((event) => event.event === eventName)
    .map((event) => event.data);
}

function parseEventStream(stream: string) {
  return stream.split("\n\n")
    .map((eventBlock) => {
      const eventLine = eventBlock.split("\n").find((line) => line.startsWith("event: "));
      const dataLine = eventBlock.split("\n").find((line) => line.startsWith("data: "));
      return {
        event: eventLine?.replace("event: ", "") ?? "",
        data: dataLine?.replace("data: ", "").replaceAll("\\n", "\n") ?? ""
      };
    })
    .filter((event) => event.event);
}
