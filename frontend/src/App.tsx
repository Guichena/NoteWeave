import { useEffect, useState } from "react";
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
  answerMode?: AnswerMode;
  citations?: string[];
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

type SourceAsset = {
  source_id: string;
  title: string;
  source_type: string;
  status: string;
  parse_status: string;
  index_status: string;
  updated_at: string;
};

type WikiPage = {
  item_id: string;
  item_type: string;
  page_kind: string;
  title: string;
  latest_version_id: string;
  latest_version_no: number;
  summary: string;
  updated_at: string;
  outgoing_count: number;
  backlink_count: number;
  citation_count: number;
  unresolved_count: number;
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
  auto_fixable_issue_count: number;
  manual_review_issue_count: number;
  pages_by_kind: Record<string, number>;
  recent_updates: WikiPage[];
  recent_tasks: WikiTaskSummary[];
  pending_task_count: number;
  wiki_enabled: boolean;
};

type WikiIssue = {
  item_id: string;
  issue_type: string;
  severity: string;
  title: string;
  message: string;
  suggested_action: string;
  auto_fixable: boolean;
  action_code: string;
};

type WikiLogEntry = {
  id: string;
  item_id: string | null;
  event_type: string;
  message: string;
  created_at: string;
};

type WikiGraph = {
  nodes: Array<{
    item_id: string;
    title: string;
    page_kind: string;
    version_no: number;
    degree: number;
    outgoing_count: number;
    backlink_count: number;
    citation_count: number;
    unresolved_count: number;
  }>;
  edges: Array<{
    source_item_id: string;
    source_title: string;
    target_item_id: string | null;
    target_title: string;
    relation_type: string;
    relation_status: string;
    mention_count: number;
  }>;
  meta: {
    mode: "overview" | "ego";
    center_item_id: string;
    depth: number;
    total_nodes: number;
    returned_nodes: number;
    truncated: boolean;
  };
};

type WikiRebuildAdvice = {
  should_enable_wiki: boolean;
  ready_source_count: number;
  active_wiki_page_count: number;
  message: string;
  recommended_action: string;
  recommended_issue_type: string;
  focus_item_id: string;
  focus_title: string;
};

type WikiIndexSource = {
  source_id: string;
  title: string;
  status: string;
  index_status: string;
  related_pages: WikiTaskRelatedPage[];
  recommended_action: string;
  focus_item_id: string;
  focus_title: string;
  updated_at: string;
};

type WikiTaskRelatedPage = {
  item_id: string;
  title: string;
  page_kind: string;
};

type WikiTaskSummary = {
  task_id: string;
  task_type: string;
  task_status: string;
  progress_phase: string;
  progress_message: string;
  target_type: string;
  target_id: string;
  target_title: string;
  related_pages: WikiTaskRelatedPage[];
  updated_at: string;
};

type WikiIndex = {
  workspace_id: string;
  wiki_enabled: boolean;
  ready_source_count: number;
  page_count: number;
  source_backed_page_count: number;
  manual_page_count: number;
  link_count: number;
  resolved_link_count: number;
  unresolved_link_count: number;
  citation_count: number;
  issue_count: number;
  auto_fixable_issue_count: number;
  manual_review_issue_count: number;
  pending_task_count: number;
  pages_by_kind: Record<string, number>;
  recent_updates: WikiPage[];
  recent_tasks: WikiStats["recent_tasks"];
  recent_sources: WikiIndexSource[];
  top_issues: WikiIssue[];
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
  source_message_id: string | null;
  citations: KnowledgeCitation[];
  outgoing_links: WikiLink[];
  backlinks: WikiLink[];
  version_created_at: string;
};

type KnowledgeVersionDetail = {
  version_id: string;
  item_id: string;
  version_no: number;
  content: string;
  summary: string;
  source_message_id: string | null;
  citations: KnowledgeCitation[];
  created_at: string;
};

type KnowledgeVersionSummary = {
  version_id: string;
  version_no: number;
  summary: string;
  source_message_id: string | null;
  citation_count: number;
  created_at: string;
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
  const [sourceText, setSourceText] = useState("NoteWeave 支持在同一个研究工作台里使用问答 RAG、Marginalia 式 Note 检索链路和 WebKonra / WeKnora 式 Wiki 检索链路。");
  const [question, setQuestion] = useState("Note 和 Wiki 两种检索方式有什么区别？");
  const [messages, setMessages] = useState<Message[]>([
    {
      role: "assistant",
      content: "先创建工作台并上传一段资料，然后就可以在同一个聊天框里切换问答、Note、Wiki 三种链路。"
    }
  ]);
  const [view, setView] = useState<"chat" | "wiki">("chat");
  const [wikiUrl, setWikiUrl] = useState("");
  const [wikiHome, setWikiHome] = useState<WikiHome | null>(null);
  const [wikiIndex, setWikiIndex] = useState<WikiIndex | null>(null);
  const [wikiEnabled, setWikiEnabled] = useState(false);
  const [wikiStats, setWikiStats] = useState<WikiStats | null>(null);
  const [wikiIssues, setWikiIssues] = useState<WikiIssue[]>([]);
  const [wikiLog, setWikiLog] = useState<WikiLogEntry[]>([]);
  const [wikiGraph, setWikiGraph] = useState<WikiGraph | null>(null);
  const [wikiRebuildAdvice, setWikiRebuildAdvice] = useState<WikiRebuildAdvice | null>(null);
  const [selectedWikiItemId, setSelectedWikiItemId] = useState("");
  const [selectedWikiDetail, setSelectedWikiDetail] = useState<KnowledgeItemDetail | null>(null);
  const [selectedWikiVersions, setSelectedWikiVersions] = useState<KnowledgeVersionSummary[]>([]);
  const [selectedWikiVersionDetail, setSelectedWikiVersionDetail] = useState<KnowledgeVersionDetail | null>(null);
  const [selectedWikiLog, setSelectedWikiLog] = useState<WikiLogEntry[]>([]);
  const [lastAssistantMessageId, setLastAssistantMessageId] = useState("");
  const [noteTitle, setNoteTitle] = useState("工作台整理笔记");
  const [wikiTitle, setWikiTitle] = useState("工作台知识页");
  const [wikiDraft, setWikiDraft] = useState("");
  const [wikiAppendDraft, setWikiAppendDraft] = useState("");
  const [wikiSearch, setWikiSearch] = useState("");
  const [wikiSearchResults, setWikiSearchResults] = useState<WikiPage[] | null>(null);
  const [wikiRenameTitle, setWikiRenameTitle] = useState("");
  const [wikiGraphMode, setWikiGraphMode] = useState<"overview" | "ego">("overview");
  const [wikiKindFilter, setWikiKindFilter] = useState<string>("ALL");
  const [wikiGraphKindFilters, setWikiGraphKindFilters] = useState<string[]>([]);
  const [wikiGraphSearch, setWikiGraphSearch] = useState("");
  const [wikiIssueTypeFilter, setWikiIssueTypeFilter] = useState<string>("ALL");
  const [wikiIssueScopeFilter, setWikiIssueScopeFilter] = useState<"ALL" | "AUTO" | "MANUAL">("ALL");
  const [wikiIssueSeverityFilter, setWikiIssueSeverityFilter] = useState<string>("ALL");
  const [wikiIssuePageFilter, setWikiIssuePageFilter] = useState<"ALL" | "CURRENT">("ALL");
  const [filteredWikiIssues, setFilteredWikiIssues] = useState<WikiIssue[]>([]);
  const [sources, setSources] = useState<SourceAsset[]>([]);
  const [latestTask, setLatestTask] = useState<TaskStatus | null>(null);
  const [taskEvents, setTaskEvents] = useState<string[]>([]);
  const [isBusy, setIsBusy] = useState(false);
  const [status, setStatus] = useState("准备就绪");

  useEffect(() => {
    if (!workspace || !wikiHome) {
      setWikiSearchResults(null);
      return;
    }
    const keyword = wikiSearch.trim();
    if (!keyword) {
      setWikiSearchResults(null);
      return;
    }
    const timeoutId = window.setTimeout(() => {
      void get<WikiPage[]>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-search?q=${encodeURIComponent(keyword)}`)
        .then((pages) => setWikiSearchResults(pages))
        .catch(() => setWikiSearchResults([]));
    }, 200);
    return () => window.clearTimeout(timeoutId);
  }, [workspace, wikiHome, wikiSearch]);

  useEffect(() => {
    if (!workspace || !wikiHome) {
      setFilteredWikiIssues([]);
      return;
    }
    const timeoutId = window.setTimeout(() => {
      const query = buildWikiIssueQuery({
        issueType: wikiIssueTypeFilter,
        severity: wikiIssueSeverityFilter,
        scope: wikiIssueScopeFilter,
        page: wikiIssuePageFilter,
        selectedItemId: selectedWikiItemId
      });
      void get<WikiIssue[]>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-issues?${query.toString()}`)
        .then((issues) => setFilteredWikiIssues(issues))
        .catch(() => setFilteredWikiIssues([]));
    }, 120);
    return () => window.clearTimeout(timeoutId);
  }, [
    workspace,
    wikiHome,
    wikiIssueTypeFilter,
    wikiIssueSeverityFilter,
    wikiIssueScopeFilter,
    wikiIssuePageFilter,
    selectedWikiItemId
  ]);

  useEffect(() => {
    if (!workspace || !wikiHome || !selectedWikiItemId) {
      setSelectedWikiLog([]);
      return;
    }
    const timeoutId = window.setTimeout(() => {
      void get<WikiLogEntry[]>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-log?item_id=${encodeURIComponent(selectedWikiItemId)}`)
        .then((entries) => setSelectedWikiLog(entries))
        .catch(() => setSelectedWikiLog([]));
    }, 120);
    return () => window.clearTimeout(timeoutId);
  }, [workspace, wikiHome, selectedWikiItemId]);

  async function createWorkspace() {
    await run("创建工作台", async () => {
      const created = await post<Workspace>("/api/v2/workspaces", {
        name: "NoteWeave 研究工作台",
        description: "用于上传资料、持续对话和维护工作台级 Wiki 的研究空间"
      });
      setWorkspace(created);
      setSources([]);
      const wikiSettings = await get<WikiSettings>(`/api/v2/workspaces/${created.workspace_id}/wiki-settings`);
      setWikiEnabled(wikiSettings.wiki_enabled);
      const createdConversation = await post<Conversation>(`/api/v2/workspaces/${created.workspace_id}/conversations`, {
        title: "默认研究会话",
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
      const nextSources = await get<SourceAsset[]>(`/api/v2/workspaces/${workspace.workspace_id}/sources`);
      setLatestTask(task);
      setTaskEvents(events);
      setWikiHome(wiki);
      setWikiUrl(wiki.wiki_url);
      setSources(nextSources);
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: `资料已上传并解析：source=${completed.source_id}，parse=${completed.parse_status}，index=${completed.index_status}，task=${task.task_status}。${wikiEnabled ? "Wiki 构建已开启，本次资料变更已进入 Wiki ingest 队列。" : "Wiki 构建未开启，本次只进入问答/Note 检索索引。"}`
        }
      ]);
    });
  }

  async function deleteSource(source: SourceAsset) {
    if (!workspace) {
      return;
    }
    await run(`删除资料《${source.title}》`, async () => {
      const deleted = await delJson<{ source_id: string; status: string; wiki_retract_task_id: string }>(
        `/api/v2/workspaces/${workspace.workspace_id}/sources/${source.source_id}`
      );
      const nextSources = await get<SourceAsset[]>(`/api/v2/workspaces/${workspace.workspace_id}/sources`);
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      setSources(nextSources);
      await applyWikiHome(wiki, selectedWikiItemId);
      if (deleted.wiki_retract_task_id) {
        const task = await get<TaskStatus>(`/api/v2/tasks/${deleted.wiki_retract_task_id}`);
        setLatestTask(task);
        const eventStream = await requestText(`/api/v2/tasks/${deleted.wiki_retract_task_id}/events`);
        setTaskEvents(parseEventStream(eventStream).map((event) => `${event.event}: ${event.data}`));
      }
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: deleted.wiki_retract_task_id
            ? `资料已删除：${source.title}。相关自动生成页面已同步清理。`
            : `资料已删除：${source.title}。当前未开启 Wiki 构建，因此没有关联页面需要同步处理。`
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
      setWikiRebuildAdvice(await get<WikiRebuildAdvice>(`/api/v2/workspaces/${workspace.workspace_id}/wiki/rebuild-advice`));
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
      let sent: { assistant_message_id: string; assistant_request_id: string; stream_url: string };
      try {
        sent = await post<{ assistant_message_id: string; assistant_request_id: string; stream_url: string }>(
          `/api/v2/conversations/${conversation.conversation_id}/messages`,
          {
            content: trimmed,
            answer_mode: mode.toUpperCase(),
            client_request_id: `${mode}-${Date.now()}`
          }
        );
      } catch (error) {
        setMessages((current) => {
          const rollbackIndex = [...current]
            .map((message, index) => ({ message, index }))
            .reverse()
            .find(({ message }) => message.role === "user" && message.content === trimmed)?.index;
          if (rollbackIndex === undefined) {
            return current;
          }
          return current.filter((_, index) => index !== rollbackIndex);
        });
        throw error;
      }
      const stream = await requestText(sent.stream_url);
      const parsed = parseChatStream(stream);
      const answer = parsed.answer || "后端已完成回答，但没有返回 delta 内容。";
      setLastAssistantMessageId(sent.assistant_message_id);
      setMessages((current) => [...current, { role: "assistant", content: answer, answerMode: mode, citations: parsed.citations }]);
    });
  }

  async function openWikiHome() {
    if (!workspace) {
      setStatus("请先创建工作台");
      return;
    }
    await run("读取默认 Wiki 工作台入口", async () => {
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, selectedWikiItemId);
      setWikiRebuildAdvice(await get<WikiRebuildAdvice>(`/api/v2/workspaces/${workspace.workspace_id}/wiki/rebuild-advice`));
      setView("wiki");
      window.history.pushState({}, "", wiki.wiki_url);
      setMessages((current) => [
        ...current,
        { role: "system", content: `默认 Wiki 工作台：${wiki.wiki_url}，页面数 ${wiki.pages.length}，链接数 ${wiki.links.length}` }
      ]);
    });
  }

  async function openWikiIndex() {
    if (!workspace || !wikiHome) {
      return;
    }
    setWikiGraphMode("overview");
    setSelectedWikiItemId("");
    setSelectedWikiDetail(null);
    setSelectedWikiVersions([]);
    setSelectedWikiVersionDetail(null);
    setWikiRenameTitle("");
    setWikiGraph(await loadWikiGraph(workspace.workspace_id, { mode: "overview" }));
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
      setStatus("请先填写要补充的页面正文。主流程是工作台级自动 Wiki 构建，手动补充只用于缺口修正。");
      return;
    }
    await run("手动补缺 / 修正 Wiki 页面", async () => {
      const created = await post<WikiPage>(`/api/v2/workspaces/${workspace.workspace_id}/knowledge-items`, {
        item_type: "WIKI",
        title: wikiTitle,
        content,
        source_message_id: null
      });
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, created.item_id);
      setWikiRebuildAdvice(await get<WikiRebuildAdvice>(`/api/v2/workspaces/${workspace.workspace_id}/wiki/rebuild-advice`));
      setWikiDraft("");
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: created.latest_version_no > 1
            ? `已将《${created.title}》追加为 v${created.latest_version_no}，用于手动修正现有 Wiki 页面。`
            : `已创建补缺页《${created.title}》。`
        }
      ]);
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
      setWikiRebuildAdvice(await get<WikiRebuildAdvice>(`/api/v2/workspaces/${workspace.workspace_id}/wiki/rebuild-advice`));
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
      const fixed = await post<{ created_pages: number; remaining_issues: number }>(`/api/v2/workspaces/${workspace.workspace_id}/wiki/auto-fix`, {});
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, selectedWikiItemId);
      setWikiRebuildAdvice(await get<WikiRebuildAdvice>(`/api/v2/workspaces/${workspace.workspace_id}/wiki/rebuild-advice`));
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: `自动补缺已创建 ${fixed.created_pages} 个补缺页面，当前仍有 ${fixed.remaining_issues} 个问题待进一步处理。`
        }
      ]);
    });
  }

  function clearWikiRepairDraft() {
    setWikiTitle("工作台知识页");
    setWikiDraft("");
    setStatus("已清空手动补页草稿");
  }

  function composeMissingWikiPageDraft(targetTitle: string, sourceTitle?: string) {
    const relationLine = sourceTitle
      ? `- 当前缺口来源页：[[${sourceTitle}]]`
      : "- 当前缺口来源页：待补充";
    return `# ${targetTitle}

## 页面定位

该页面用于补齐当前工作台 Wiki 网络中的缺失页面，避免知识关系在这里中断。

## 关联关系

${relationLine}
- 与其他相关页面的关系：待补充

## 待补充内容

- 核心定义或主题说明
- 关键事实与结论
- 需要补充的来源依据
`;
  }

  function composeWikiAppendDraft(issueType: string, pageTitle: string) {
    if (issueType === "MISSING_SOURCE") {
      return `## 来源补充

- 待补充资料来源：
- 关键证据摘录：
- 引用定位：

## 页面修正说明

为《${pageTitle}》补齐来源依据，并让关键结论可以继续回溯到资料证据。`;
    }
    if (issueType === "ORPHAN_PAGE") {
      return `## 页面关系补充

- 建议补充的上游页面：[[ ]]
- 建议补充的下游页面：[[ ]]
- 本页在工作台中的定位：

## 页面修正说明

把《${pageTitle}》重新接回当前 Wiki 网络，避免它继续孤立在页面关系之外。`;
    }
    return `## 页面修正

请根据当前维护提醒补充《${pageTitle}》的正文、来源或页面关系。`;
  }

  function prefillWikiRepairDraft(title: string, draft: string) {
    setWikiTitle(title);
    setWikiDraft(draft);
    setStatus(`已为《${title}》预填手动补页草稿`);
  }

  async function prepareWikiIssueRepair(issue: WikiIssue) {
    if (issue.issue_type === "BROKEN_LINK") {
      const sourceTitle = wikiHome?.pages.find((page) => page.item_id === issue.item_id)?.title;
      prefillWikiRepairDraft(issue.title, composeMissingWikiPageDraft(issue.title, sourceTitle));
      return;
    }
    if (!issue.item_id) {
      return;
    }
    await openWikiPageById(issue.item_id);
    const pageTitle = wikiHome?.pages.find((page) => page.item_id === issue.item_id)?.title ?? issue.title;
    setWikiAppendDraft(composeWikiAppendDraft(issue.issue_type, pageTitle));
    setStatus(`已定位到《${pageTitle}》，并预填页面修正草稿`);
  }

  function prepareWikiLinkRepair(targetTitle: string, sourceTitle: string) {
    prefillWikiRepairDraft(targetTitle, composeMissingWikiPageDraft(targetTitle, sourceTitle));
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

  async function loadWikiVersion(itemId: string, versionNo: number) {
    setSelectedWikiVersionDetail(await get<KnowledgeVersionDetail>(`/api/v2/knowledge-items/${itemId}/versions/${versionNo}`));
  }

  function buildWikiIssueQuery(filters: {
    issueType: string;
    severity: string;
    scope: "ALL" | "AUTO" | "MANUAL";
    page: "ALL" | "CURRENT";
    selectedItemId: string;
  }) {
    const query = new URLSearchParams();
    if (filters.issueType !== "ALL") {
      query.set("issue_type", filters.issueType);
    }
    if (filters.severity !== "ALL") {
      query.set("severity", filters.severity);
    }
    if (filters.scope === "AUTO") {
      query.set("auto_fixable", "true");
    }
    if (filters.scope === "MANUAL") {
      query.set("auto_fixable", "false");
    }
    if (filters.page === "CURRENT" && filters.selectedItemId) {
      query.set("item_id", filters.selectedItemId);
    }
    return query;
  }

  function buildWikiGraphQuery(
    mode: "overview" | "ego",
    selectedItemId?: string,
    graphKinds: string[] = wikiGraphKindFilters
  ) {
    const query = new URLSearchParams();
    if (mode === "ego" && selectedItemId) {
      query.set("mode", "ego");
      query.set("center", selectedItemId);
      query.set("depth", "1");
      query.set("limit", "12");
    } else {
      query.set("mode", "overview");
      query.set("limit", "24");
    }
    graphKinds.forEach((kind) => query.append("kinds", kind));
    return query;
  }

  async function loadWikiGraph(
    workspaceId: string,
    options?: {
      mode?: "overview" | "ego";
      selectedItemId?: string;
      graphKinds?: string[];
    }
  ) {
    const nextMode = options?.mode ?? wikiGraphMode;
    const nextSelectedItemId = options?.selectedItemId;
    const nextKinds = options?.graphKinds ?? wikiGraphKindFilters;
    const query = buildWikiGraphQuery(nextMode, nextSelectedItemId, nextKinds);
    return get<WikiGraph>(`/api/v2/workspaces/${workspaceId}/wiki-graph?${query.toString()}`);
  }

  async function selectWikiPage(page: WikiPage, nextGraphMode?: "overview" | "ego") {
    setSelectedWikiItemId(page.item_id);
    await run(`打开 Wiki 页面《${page.title}》`, async () => {
      const detail = await get<KnowledgeItemDetail>(`/api/v2/knowledge-items/${page.item_id}`);
      setSelectedWikiDetail(detail);
      setSelectedWikiVersions(await get<KnowledgeVersionSummary[]>(`/api/v2/knowledge-items/${page.item_id}/versions`));
      if (workspace) {
        const graphMode = nextGraphMode ?? wikiGraphMode;
        setWikiGraphMode(graphMode);
        setWikiGraph(await loadWikiGraph(workspace.workspace_id, { mode: graphMode, selectedItemId: page.item_id }));
      }
      setSelectedWikiVersionDetail({
        version_id: detail.latest_version_id,
        item_id: detail.item_id,
        version_no: detail.latest_version_no,
        content: detail.content,
        summary: detail.summary,
        source_message_id: detail.source_message_id,
        citations: detail.citations,
        created_at: detail.version_created_at
      });
      setWikiAppendDraft("");
      setWikiRenameTitle(page.title);
    });
  }

  function getWikiKindRank(kind: string) {
    const normalized = (kind || "TOPIC").toUpperCase();
    switch (normalized) {
      case "OVERVIEW":
        return 0;
      case "TOPIC":
        return 1;
      case "CONCEPT":
        return 2;
      case "COMPARISON":
        return 3;
      default:
        return 9;
    }
  }

  function getSeverityRank(severity: string) {
    switch ((severity || "").toUpperCase()) {
      case "HIGH":
        return 0;
      case "MEDIUM":
        return 1;
      case "LOW":
        return 2;
      default:
        return 9;
    }
  }

  function formatDateTime(value: string | null | undefined) {
    if (!value) {
      return "时间未知";
    }
    return new Date(value).toLocaleString("zh-CN");
  }

  function formatWikiRelationType(relationType: string) {
    switch ((relationType || "").toUpperCase()) {
      case "AUTO_LINK":
        return "自动互链";
      case "HYBRID_LINK":
        return "混合互链";
      case "WIKI_LINK":
        return "显式链接";
      default:
        return relationType || "页面关系";
    }
  }

  const selectedWikiPage = wikiHome?.pages.find((page) => page.item_id === selectedWikiItemId) ?? null;
  const selectedWikiIssues = selectedWikiItemId
    ? wikiIssues.filter((issue) => issue.item_id === selectedWikiItemId)
    : [];
  const baseWikiPages = wikiSearchResults ?? wikiHome?.pages ?? [];
  const availableWikiKinds = Array.from(new Set((wikiHome?.pages ?? []).map((page) => page.page_kind || "TOPIC")))
    .sort((left, right) => {
      const rankDiff = getWikiKindRank(left) - getWikiKindRank(right);
      return rankDiff !== 0 ? rankDiff : left.localeCompare(right);
    });
  const graphSearchHits = wikiGraphSearch.trim()
    ? (wikiHome?.pages ?? [])
        .filter((page) => {
          const keyword = wikiGraphSearch.trim().toLowerCase();
          return page.title.toLowerCase().includes(keyword)
            || page.summary.toLowerCase().includes(keyword)
            || (page.page_kind || "TOPIC").toLowerCase().includes(keyword);
        })
        .slice(0, 6)
    : [];
  const graphFilterLabel = wikiGraphKindFilters.length === 0 ? "全部页面类型" : wikiGraphKindFilters.join(" / ");
  const autoFixableIssues = wikiIssues.filter((issue) => issue.auto_fixable);
  const reviewRequiredIssues = wikiIssues.filter((issue) => !issue.auto_fixable);
  const wikiIssueTypes = Array.from(new Set(wikiIssues.map((issue) => issue.issue_type))).sort();
  const wikiIssueSeverities = Array.from(new Set(wikiIssues.map((issue) => issue.severity)))
    .sort((left, right) => {
      const rankDiff = getSeverityRank(left) - getSeverityRank(right);
      return rankDiff !== 0 ? rankDiff : left.localeCompare(right);
    });
  const visibleWikiPages = baseWikiPages.filter((page) => wikiKindFilter === "ALL" || (page.page_kind || "TOPIC") === wikiKindFilter);
  const groupedWikiPages = Object.fromEntries(
    Object.entries(visibleWikiPages.reduce<Record<string, WikiPage[]>>((groups, page) => {
      const kind = page.page_kind || "TOPIC";
      groups[kind] = groups[kind] ?? [];
      groups[kind].push(page);
      return groups;
    }, {})).sort(([left], [right]) => {
      const rankDiff = getWikiKindRank(left) - getWikiKindRank(right);
      return rankDiff !== 0 ? rankDiff : left.localeCompare(right);
    })
  );

  async function applyWikiHome(wiki: WikiHome, preferredItemId: string) {
    setWikiUrl(wiki.wiki_url);
    setWikiHome(wiki);
    setWikiIndex(await get<WikiIndex>(`/api/v2/workspaces/${wiki.workspace_id}/wiki-index`));
    setWikiStats(await get<WikiStats>(`/api/v2/workspaces/${wiki.workspace_id}/wiki-stats`));
    const issues = await get<WikiIssue[]>(`/api/v2/workspaces/${wiki.workspace_id}/wiki-issues`);
    setWikiIssues(issues);
    setFilteredWikiIssues(issues);
    setWikiLog(await get<WikiLogEntry[]>(`/api/v2/workspaces/${wiki.workspace_id}/wiki-log`));
    setWikiRebuildAdvice(await get<WikiRebuildAdvice>(`/api/v2/workspaces/${wiki.workspace_id}/wiki/rebuild-advice`));
    const selected = preferredItemId ? (wiki.pages.find((page) => page.item_id === preferredItemId) ?? null) : null;
    setSelectedWikiItemId(selected?.item_id ?? "");
    setWikiGraph(await loadWikiGraph(wiki.workspace_id, { selectedItemId: selected?.item_id }));
    const detail = selected ? await get<KnowledgeItemDetail>(`/api/v2/knowledge-items/${selected.item_id}`) : null;
    setSelectedWikiDetail(detail);
    setSelectedWikiVersions(selected ? await get<KnowledgeVersionSummary[]>(`/api/v2/knowledge-items/${selected.item_id}/versions`) : []);
      setSelectedWikiVersionDetail(detail
      ? {
          version_id: detail.latest_version_id,
          item_id: detail.item_id,
          version_no: detail.latest_version_no,
          content: detail.content,
          summary: detail.summary,
          source_message_id: detail.source_message_id,
          citations: detail.citations,
          created_at: detail.version_created_at
        }
      : null);
    setWikiRenameTitle(selected?.title ?? "");
  }

  function restoreLatestWikiVersion() {
    if (!selectedWikiDetail) {
      return;
    }
    setSelectedWikiVersionDetail({
      version_id: selectedWikiDetail.latest_version_id,
      item_id: selectedWikiDetail.item_id,
      version_no: selectedWikiDetail.latest_version_no,
      content: selectedWikiDetail.content,
      summary: selectedWikiDetail.summary,
      source_message_id: selectedWikiDetail.source_message_id,
      citations: selectedWikiDetail.citations,
      created_at: selectedWikiDetail.version_created_at
    });
  }

  async function switchWikiGraphMode(nextMode: "overview" | "ego") {
    if (!workspace) {
      return;
    }
    setWikiGraphMode(nextMode);
    setWikiGraph(await loadWikiGraph(workspace.workspace_id, { mode: nextMode, selectedItemId: selectedWikiItemId }));
  }

  async function toggleWikiGraphKind(kind: string) {
    if (!workspace) {
      return;
    }
    const nextKinds = wikiGraphKindFilters.includes(kind)
      ? wikiGraphKindFilters.filter((entry) => entry !== kind)
      : [...wikiGraphKindFilters, kind].sort((left, right) => getWikiKindRank(left) - getWikiKindRank(right));
    setWikiGraphKindFilters(nextKinds);
    setWikiGraph(await loadWikiGraph(workspace.workspace_id, {
      mode: wikiGraphMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: nextKinds
    }));
  }

  async function resetWikiGraphKinds() {
    if (!workspace) {
      return;
    }
    setWikiGraphKindFilters([]);
    setWikiGraph(await loadWikiGraph(workspace.workspace_id, {
      mode: wikiGraphMode,
      selectedItemId: selectedWikiItemId,
      graphKinds: []
    }));
  }

  async function openWikiGraphPage(itemId: string) {
    const page = wikiHome?.pages.find((entry) => entry.item_id === itemId);
    if (!page) {
      return;
    }
    await selectWikiPage(page, "ego");
  }

  async function openWikiPageById(itemId: string) {
    const page = wikiHome?.pages.find((entry) => entry.item_id === itemId);
    if (!page) {
      return;
    }
    await selectWikiPage(page);
  }

  async function focusWikiIssue(issue: WikiIssue) {
    setWikiIssueScopeFilter(issue.auto_fixable ? "AUTO" : "MANUAL");
    setWikiIssueTypeFilter(issue.issue_type || "ALL");
    setWikiIssueSeverityFilter(issue.severity || "ALL");
    if (issue.item_id) {
      await openWikiPageById(issue.item_id);
      setWikiIssuePageFilter("CURRENT");
      return;
    }
    setWikiIssuePageFilter("ALL");
  }

  async function focusWikiIssueType(issueType: string) {
    if (!workspace) {
      return;
    }
    if (!wikiHome) {
      const wiki = await get<WikiHome>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-home`);
      await applyWikiHome(wiki, "");
      setView("wiki");
      window.history.pushState({}, "", wiki.wiki_url);
    } else {
      setView("wiki");
      await openWikiIndex();
    }
    const matchedIssues = await get<WikiIssue[]>(`/api/v2/workspaces/${workspace.workspace_id}/wiki-issues?issue_type=${encodeURIComponent(issueType)}`);
    const firstIssue = matchedIssues[0] ?? null;
    setWikiIssueScopeFilter(firstIssue?.auto_fixable ? "AUTO" : issueType === "BROKEN_LINK" ? "AUTO" : "MANUAL");
    setWikiIssueTypeFilter(issueType || "ALL");
    setWikiIssueSeverityFilter("ALL");
    if (!firstIssue) {
      setWikiIssuePageFilter("ALL");
      setStatus(`已定位到 ${issueType} 维护视图`);
      return;
    }
    await prepareWikiIssueRepair(firstIssue);
    if (firstIssue.item_id) {
      setWikiIssuePageFilter("CURRENT");
    } else {
      setWikiIssuePageFilter("ALL");
    }
    setStatus(`已定位到 ${issueType} 的首个相关对象`);
  }

  async function focusWikiAdviceTarget() {
    if (!wikiRebuildAdvice) {
      return;
    }
    const issueType = wikiRebuildAdvice.recommended_issue_type || "MISSING_SOURCE";
    const focusItemId = wikiRebuildAdvice.focus_item_id || "";
    const focusTitle = wikiRebuildAdvice.focus_title || "";
    setWikiIssueTypeFilter(issueType || "ALL");
    setWikiIssueSeverityFilter("ALL");
    if (issueType === "BROKEN_LINK" && focusTitle) {
      const sourceTitle = focusItemId
        ? wikiHome?.pages.find((page) => page.item_id === focusItemId)?.title ?? ""
        : "";
      if (focusItemId) {
        await openWikiPageById(focusItemId);
        setWikiIssuePageFilter("CURRENT");
      } else {
        setWikiIssuePageFilter("ALL");
      }
      setWikiIssueScopeFilter("AUTO");
      prefillWikiRepairDraft(focusTitle, composeMissingWikiPageDraft(focusTitle, sourceTitle));
      setStatus(`已根据建议预填《${focusTitle}》的补缺草稿`);
      return;
    }
    if (focusItemId) {
      await openWikiPageById(focusItemId);
      setWikiIssuePageFilter("CURRENT");
      setWikiIssueScopeFilter("MANUAL");
      setStatus(`已定位到《${focusTitle || focusItemId}》的修正视图`);
      return;
    }
    await focusWikiIssueType(issueType);
  }

  function getWikiAdviceAction() {
    if (!wikiRebuildAdvice) {
      return null;
    }
    switch (wikiRebuildAdvice.recommended_action) {
      case "ENABLE_WIKI":
        return {
          label: "按建议开启 Wiki 构建",
          run: () => void toggleWikiEnabled()
        };
      case "REBUILD_WIKI":
        return {
          label: "按建议重建 Wiki",
          run: () => void rebuildWorkspaceWiki()
        };
      case "AUTO_FIX_WIKI":
        return {
          label: "按建议执行自动补缺",
          run: () => void autoFixWiki()
        };
      case "FOCUS_MANUAL_REPAIR":
        return {
          label: "按建议进入人工修正",
          run: () => void focusWikiAdviceTarget()
        };
      case "OPEN_WIKI_HOME":
        return {
          label: view === "wiki" ? "查看工作台总览" : "进入 Wiki 工作台",
          run: () => void (view === "wiki" ? openWikiIndex() : openWikiHome())
        };
      default:
        return null;
    }
  }

  function getWikiIssuePrimaryAction(issue: WikiIssue) {
    switch (issue.action_code || issue.issue_type) {
      case "REBUILD_WIKI":
        if (!workspace) {
          return null;
        }
        return {
          label: "按资料重建 Wiki",
          run: () => void rebuildWorkspaceWiki()
        };
      case "PREFILL_MISSING_PAGE":
      case "BROKEN_LINK":
        return {
          label: "预填补缺页",
          run: () => void prepareWikiIssueRepair(issue)
        };
      case "FOCUS_MANUAL_REPAIR":
      case "PLACEHOLDER_CONTENT":
      case "MISSING_SOURCE":
      case "ORPHAN_PAGE":
        return {
          label: "进入修正模式",
          run: () => void prepareWikiIssueRepair(issue)
        };
      default:
        return null;
    }
  }

  const wikiAdviceAction = getWikiAdviceAction();

  function renderWikiTaskCard(task: WikiTaskSummary, key: string) {
    return (
      <div className="link-card" key={key}>
        <strong>{task.task_type} · {task.task_status}</strong>
        <span>{task.progress_phase} · {task.progress_message}</span>
        <span>{task.target_type || "WORKSPACE"} · {task.target_title || task.target_id || "当前工作台"} · {formatDateTime(task.updated_at)}</span>
        {task.related_pages.length ? (
          <div className="wiki-inline-actions">
            {task.related_pages.map((page) => (
              <button
                key={`${task.task_id}-${page.item_id}`}
                className="secondary-button"
                disabled={isBusy}
                onClick={() => void openWikiPageById(page.item_id)}
              >
                打开 {page.title}
              </button>
            ))}
          </div>
        ) : null}
      </div>
    );
  }

  function renderWikiRecentUpdateCard(page: WikiPage, key: string) {
    return (
      <div className="link-card" key={key}>
        <strong>{page.title}</strong>
        <span>
          {page.page_kind || "TOPIC"} · v{page.latest_version_no} · 出链 {page.outgoing_count} · 反链 {page.backlink_count} · 引用 {page.citation_count}
          {page.unresolved_count > 0 ? ` · 断链 ${page.unresolved_count}` : ""}
        </span>
        <span>{formatDateTime(page.updated_at)}</span>
        <div className="wiki-inline-actions">
          <button className="secondary-button" disabled={isBusy} onClick={() => void selectWikiPage(page)}>
            打开页面
          </button>
          <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiGraphPage(page.item_id)}>
            查看子图
          </button>
        </div>
      </div>
    );
  }

  function getRecentSourceAction(source: WikiIndexSource) {
    switch (source.recommended_action) {
      case "OPEN_WIKI_PAGE":
        if (!source.focus_item_id) {
          return null;
        }
        return {
          label: `打开 ${source.focus_title || "关联页面"}`,
          run: () => void openWikiPageById(source.focus_item_id)
        };
      case "ENABLE_WIKI":
        if (!workspace) {
          return null;
        }
        return {
          label: "开启 Wiki 构建",
          run: () => void toggleWikiEnabled()
        };
      case "REBUILD_WIKI":
        if (!workspace) {
          return null;
        }
        return {
          label: "按当前资料重建 Wiki",
          run: () => void rebuildWorkspaceWiki()
        };
      default:
        return null;
    }
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
        </div>
      </section>

      {view === "wiki" && wikiHome ? (
        <section className="wiki-workbench">
          <aside className="wiki-index">
            <p className="section-label">Wiki Index</p>
            <h2>默认 Wiki 工作台</h2>
            <p className="wiki-url">{wikiHome.wiki_url}</p>
            <div className="wiki-inline-pills">
              <span className="status-chip">待处理任务 {wikiIndex?.pending_task_count ?? 0}</span>
              <span className="status-chip">可自动修复 {wikiIndex?.auto_fixable_issue_count ?? 0}</span>
              <span className="status-chip">人工确认 {wikiIndex?.manual_review_issue_count ?? 0}</span>
            </div>
            <button
              className={!selectedWikiItemId ? "active nav-button" : "nav-button"}
              disabled={isBusy}
              onClick={() => void openWikiIndex()}
            >
              <span>工作台总览</span>
              <small>自动构建 / 页面分布 / 维护提醒</small>
            </button>
            <input
              value={wikiSearch}
              onChange={(event) => setWikiSearch(event.target.value)}
              placeholder="搜索页面标题或摘要"
            />
            <div className="wiki-kind-filter">
              <button
                className={wikiKindFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                disabled={isBusy}
                onClick={() => setWikiKindFilter("ALL")}
              >
                全部
              </button>
              {availableWikiKinds.map((kind) => (
                <button
                  key={kind}
                  className={wikiKindFilter === kind ? "active filter-pill" : "filter-pill"}
                  disabled={isBusy}
                  onClick={() => setWikiKindFilter(kind)}
                >
                  {kind}
                </button>
              ))}
            </div>
            <div className="wiki-page-list">
              {wikiHome.pages.length === 0 && (
                <p className="empty-state">
                  {wikiRebuildAdvice?.message ?? "还没有 Wiki 页面。建议先开启工作台级 Wiki 构建，让现有资料进入自动页面生成。"}
                </p>
              )}
              {wikiHome.pages.length > 0 && visibleWikiPages.length === 0 && <p className="empty-state">没有匹配的 Wiki 页面。</p>}
              {Object.entries(groupedWikiPages).map(([kind, pages]) => (
                <div key={`group-${kind}`} className="wiki-page-group">
                  <div className="wiki-page-group-title">
                    <strong>{kind}</strong>
                    <small>{pages.length} 页</small>
                  </div>
                  {pages.map((page) => (
                    <button
                      key={page.item_id}
                      className={page.item_id === selectedWikiPage?.item_id ? "active wiki-page-card" : "wiki-page-card"}
                      disabled={isBusy}
                      onClick={() => void selectWikiPage(page)}
                    >
                      <span>{page.title}</span>
                      <small>{page.page_kind || "TOPIC"} · v{page.latest_version_no}</small>
                      <small>
                        出链 {page.outgoing_count} · 反链 {page.backlink_count} · 引用 {page.citation_count}
                        {page.unresolved_count > 0 ? ` · 断链 ${page.unresolved_count}` : ""}
                      </small>
                    </button>
                  ))}
                </div>
              ))}
            </div>
          </aside>

          <article className="wiki-page">
            <p className="section-label">{selectedWikiPage ? "Wiki Page" : "Wiki Index"}</p>
            {selectedWikiPage ? (
              <>
                <h2>{selectedWikiPage.title}</h2>
                <div className="wiki-maintenance">
                  <strong>页面类型</strong>
                  <span>{selectedWikiDetail?.page_kind || selectedWikiPage.page_kind || "TOPIC"}</span>
                </div>
                <div className="wiki-maintenance">
                  <strong>当前查看</strong>
                  <span>
                    {selectedWikiVersionDetail && selectedWikiVersionDetail.version_no !== (selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no)
                      ? `历史版本 v${selectedWikiVersionDetail.version_no}`
                      : `最新版本 v${selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no}`}
                  </span>
                </div>
                <div className="wiki-maintenance">
                  <strong>版本来源</strong>
                  <span>
                    {selectedWikiVersionDetail?.source_message_id
                      ? `来自聊天消息 ${selectedWikiVersionDetail.source_message_id}`
                      : "来自工作台级资料 ingest 或人工维护"}
                    {" · "}
                    {formatDateTime(selectedWikiVersionDetail?.created_at)}
                  </span>
                </div>
                <div className="wiki-maintenance">
                  <strong>页面更新时间</strong>
                  <span>{formatDateTime(selectedWikiDetail?.updated_at ?? selectedWikiPage.updated_at)}</span>
                </div>
                <p className="version-pill">当前版本 v{selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no}</p>
                <div className="wiki-summary">
                  {(selectedWikiVersionDetail?.version_no === (selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no)
                    ? selectedWikiDetail?.content
                    : selectedWikiVersionDetail?.content) || selectedWikiDetail?.content || selectedWikiPage.summary || "这个页面暂时还没有正文。"}
                </div>
                <div className="wiki-citations">
                  <strong>版本历史</strong>
                  {selectedWikiVersions.length === 0 && <span>当前页面还没有版本记录。</span>}
                  {selectedWikiVersions.map((version) => (
                    <button
                      key={version.version_id}
                      className="secondary-button"
                      disabled={isBusy}
                      onClick={() => void loadWikiVersion(selectedWikiPage.item_id, version.version_no)}
                    >
                      v{version.version_no} · 引用 {version.citation_count} · {version.source_message_id ? "聊天来源" : "工作台维护"} · {version.summary || "无摘要"}
                    </button>
                  ))}
                  {selectedWikiVersionDetail && selectedWikiDetail && selectedWikiVersionDetail.version_no !== selectedWikiDetail.latest_version_no ? (
                    <button className="secondary-button" disabled={isBusy} onClick={restoreLatestWikiVersion}>
                      返回最新版本
                    </button>
                  ) : null}
                </div>
                {selectedWikiVersionDetail && selectedWikiVersionDetail.version_no !== (selectedWikiDetail?.latest_version_no ?? selectedWikiPage.latest_version_no) ? (
                  <div className="wiki-citations">
                    <strong>历史版本引用</strong>
                    {selectedWikiVersionDetail.citations.length === 0 && <span>该历史版本没有绑定引用。</span>}
                    {selectedWikiVersionDetail.citations.map((citation, index) => (
                      <span key={`history-${citation.citation_id}`}>
                        {index + 1}. {citation.title}：{citation.quote_text}
                      </span>
                    ))}
                  </div>
                ) : null}
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
                  <strong>页面出链</strong>
                  {selectedWikiDetail?.outgoing_links?.length ? (
                    <span>
                      {selectedWikiDetail.outgoing_links.map((link, index) => (
                        <button
                          key={`${link.source_item_id}-${link.target_title}-${index}-inline`}
                          className="inline-action"
                          disabled={isBusy || !link.target_item_id}
                          onClick={() => link.target_item_id ? void openWikiPageById(link.target_item_id) : undefined}
                        >
                          {link.target_title}
                        </button>
                      ))}
                    </span>
                  ) : "当前页面暂无显式出链"}
                </div>
                <div className="wiki-maintenance">
                  <strong>反向链接</strong>
                  {selectedWikiDetail?.backlinks?.length ? (
                    <span>
                      {selectedWikiDetail.backlinks.map((link, index) => (
                        <button
                          key={`${link.source_item_id}-${link.target_title}-${index}-back-inline`}
                          className="inline-action"
                          disabled={isBusy || !link.source_item_id}
                          onClick={() => link.source_item_id ? void openWikiPageById(link.source_item_id) : undefined}
                        >
                          {link.target_title}
                        </button>
                      ))}
                    </span>
                  ) : "当前页面暂无反向链接"}
                </div>
                <div className="wiki-maintenance">
                  <strong>页面维护动作</strong>
                  <span>编辑正文会生成新版本；重命名会刷新页面关系并同步改写引用页；删除采用软删除，引用它的页面关系会回退为未解析。</span>
                </div>
                <div className="wiki-citations">
                  <strong>页面健康问题</strong>
                  {selectedWikiIssues.length ? selectedWikiIssues.map((issue, index) => (
                    (() => {
                      const issueAction = getWikiIssuePrimaryAction(issue);
                      return (
                        <div className="link-card" key={`page-issue-${issue.issue_type}-${index}`}>
                          <strong>{issue.issue_type} / {issue.severity}</strong>
                          <span>{issue.message}</span>
                          <span>{issue.auto_fixable ? "可自动修复" : "需人工确认"}</span>
                          <span>{issue.suggested_action}</span>
                          <div className="wiki-inline-actions">
                            {issue.auto_fixable ? (
                              <button className="secondary-button" disabled={isBusy || !workspace} onClick={() => void autoFixWiki()}>
                                自动补缺
                              </button>
                            ) : null}
                            {issueAction ? (
                              <button className="secondary-button" disabled={isBusy} onClick={issueAction.run}>
                                {issueAction.label}
                              </button>
                            ) : null}
                            {issue.item_id && issue.item_id !== selectedWikiItemId ? (
                              <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(issue.item_id!)}>
                                打开相关页面
                              </button>
                            ) : null}
                          </div>
                        </div>
                      );
                    })()
                  )) : <span>当前页面没有单独的维护提醒。</span>}
                </div>
                <div className="wiki-citations">
                  <strong>页面最近变更</strong>
                  {selectedWikiLog.length ? selectedWikiLog.slice(0, 5).map((entry) => (
                    <div className="link-card" key={`page-log-${entry.id}`}>
                      <strong>{entry.event_type}</strong>
                      <span>{entry.message}</span>
                      <span>{formatDateTime(entry.created_at)}</span>
                    </div>
                  )) : <span>当前页面还没有可展示的变更日志。</span>}
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
              <>
                <h2>工作台总览</h2>
                <div className="wiki-summary">
                  当前 Wiki 工作台默认先展示工作台级总览，再按需进入具体页面。

                  {"\n\n"}这和 WeKnora 的浏览器逻辑保持一致：Wiki 首先绑定研究工作台与资料变化，
                  页面只是这套工作台级知识网络里的阅读与维护对象，而不是聊天临时草稿。
                </div>
                <div className="wiki-maintenance">
                  <strong>构建状态</strong>
                  <span>
                    {wikiIndex?.wiki_enabled ? "工作台级 Wiki 构建已开启" : "工作台级 Wiki 构建未开启"} ·
                    READY 资料 {wikiIndex?.ready_source_count ?? 0} ·
                    页面 {wikiIndex?.page_count ?? 0} ·
                    待处理任务 {wikiIndex?.pending_task_count ?? 0}
                  </span>
                </div>
                <div className="wiki-maintenance">
                  <strong>页面构成</strong>
                  <span>
                    资料驱动页面 {wikiIndex?.source_backed_page_count ?? 0} ·
                    人工维护页面 {wikiIndex?.manual_page_count ?? 0} ·
                    链接 {wikiIndex?.link_count ?? 0} ·
                    断链 {wikiIndex?.unresolved_link_count ?? 0}
                  </span>
                </div>
                <div className="wiki-maintenance">
                  <strong>构建建议</strong>
                  <span>{wikiRebuildAdvice?.message ?? "当前工作台会在这里显示 Wiki 的自动构建建议。"}</span>
                  {wikiAdviceAction ? (
                    <button className="secondary-button" disabled={isBusy || !workspace} onClick={wikiAdviceAction.run}>
                      {wikiAdviceAction.label}
                    </button>
                  ) : null}
                </div>
                <div className="wiki-citations">
                  <strong>页面类型分布</strong>
                  <span>{wikiIndex ? Object.entries(wikiIndex.pages_by_kind).map(([kind, count]) => `${kind}:${count}`).join(" / ") || "暂无页面" : "暂无页面"}</span>
                </div>
                <div className="wiki-citations">
                  <strong>最近更新页面</strong>
                  {wikiIndex?.recent_updates?.length ? wikiIndex.recent_updates.map((page) => renderWikiRecentUpdateCard(page, `index-page-${page.item_id}`)) : <span>当前还没有已沉淀的 Wiki 页面。</span>}
                </div>
                <div className="wiki-citations">
                  <strong>最近资料变化</strong>
                  {wikiIndex?.recent_sources?.length ? wikiIndex.recent_sources.map((source) => {
                    const sourceAction = getRecentSourceAction(source);
                    return (
                      <div className="link-card" key={`index-source-${source.source_id}`}>
                        <strong>{source.title}</strong>
                        <span>{source.status} · {source.index_status} · {formatDateTime(source.updated_at)}</span>
                        {source.related_pages.length ? (
                          <div className="wiki-inline-actions">
                            {source.related_pages.map((page) => (
                              <button
                                key={`source-page-${source.source_id}-${page.item_id}`}
                                className="secondary-button"
                                disabled={isBusy}
                                onClick={() => void openWikiPageById(page.item_id)}
                              >
                                打开 {page.title}
                              </button>
                            ))}
                          </div>
                        ) : (
                          <>
                            <span>当前资料尚未关联到可打开的 Wiki 页面。</span>
                            {sourceAction ? (
                              <div className="wiki-inline-actions">
                                <button className="secondary-button" disabled={isBusy} onClick={sourceAction.run}>
                                  {sourceAction.label}
                                </button>
                              </div>
                            ) : null}
                          </>
                        )}
                      </div>
                    );
                  }) : <span>当前工作台还没有资料。</span>}
                </div>
                <details className="maintenance-drawer">
                  <summary>
                    <strong>维护提醒</strong>
                    <span>
                      待处理任务 {wikiIndex?.pending_task_count ?? 0} ·
                      自动补缺 {wikiIndex?.auto_fixable_issue_count ?? 0} ·
                      人工确认 {wikiIndex?.manual_review_issue_count ?? 0}
                    </span>
                  </summary>
                  <div className="wiki-citations">
                    <strong>最近 Wiki 任务</strong>
                    {wikiIndex?.recent_tasks?.length ? wikiIndex.recent_tasks.map((task) => renderWikiTaskCard(task, `index-task-${task.task_id}`)) : <span>当前还没有 Wiki 相关任务记录。</span>}
                  </div>
                  <div className="wiki-citations">
                    <strong>优先维护项</strong>
                    {wikiIndex?.top_issues?.length ? wikiIndex.top_issues.map((issue, index) => {
                      const issueAction = getWikiIssuePrimaryAction(issue);
                      return (
                        <div className="link-card" key={`index-issue-${issue.issue_type}-${index}`}>
                          <strong>{issue.issue_type} / {issue.severity}</strong>
                          <span>{issue.message}</span>
                          <span>{issue.auto_fixable ? "可自动补缺" : "需人工确认"}</span>
                          <span>{issue.suggested_action}</span>
                          <div className="wiki-inline-actions">
                            {issue.auto_fixable ? (
                              <button className="secondary-button" disabled={isBusy || !workspace} onClick={() => void autoFixWiki()}>
                                自动补缺
                              </button>
                            ) : null}
                            {issueAction ? (
                              <button className="secondary-button" disabled={isBusy} onClick={issueAction.run}>
                                {issueAction.label}
                              </button>
                            ) : null}
                            {issue.item_id ? (
                              <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(issue.item_id!)}>
                                打开相关页面
                              </button>
                            ) : null}
                          </div>
                        </div>
                      );
                    }) : <span>当前没有需要优先处理的维护项。</span>}
                  </div>
                </details>
              </>
            )}
          </article>

          <aside className="wiki-links">
            <p className="section-label">{selectedWikiPage ? "页面关系" : "工作台关系"}</p>
            {wikiStats && (
              <>
                <div className="wiki-maintenance">
                  <strong>Wiki 健康度</strong>
                  <span>页面 {wikiStats.page_count} · 链接 {wikiStats.link_count} · 已解析 {wikiStats.resolved_link_count} · 断链 {wikiStats.unresolved_link_count} · 引用 {wikiStats.citation_count} · 问题 {wikiStats.issue_count}</span>
                </div>
                <div className="wiki-maintenance">
                  <strong>维护提醒</strong>
                  <span>自动补缺 {wikiStats.auto_fixable_issue_count} · 人工确认 {wikiStats.manual_review_issue_count}</span>
                </div>
                <div className="wiki-maintenance">
                  <strong>工作台状态</strong>
                  <span>{wikiStats.wiki_enabled ? "Wiki 构建已开启" : "Wiki 构建未开启"} · 待处理任务 {wikiStats.pending_task_count}</span>
                </div>
                <div className="wiki-maintenance">
                  <strong>页面类型分布</strong>
                  <span>{Object.entries(wikiStats.pages_by_kind).map(([kind, count]) => `${kind}:${count}`).join(" / ") || "暂无页面"}</span>
                </div>
              </>
            )}
            {selectedWikiPage && selectedWikiDetail?.outgoing_links?.length === 0 && selectedWikiDetail?.backlinks?.length === 0 && (
              <p className="empty-state">当前页面暂无直接关系。</p>
            )}
            {selectedWikiDetail?.outgoing_links?.map((link, index) => (
              <div className="link-card" key={`${link.source_item_id}-${link.target_title}-${index}`}>
                <strong>{link.target_title}</strong>
                <span>出链 · {formatWikiRelationType(link.relation_type)} · {link.relation_status} · {link.mention_count} 次提及</span>
                <div className="wiki-inline-actions">
                  {link.target_item_id ? (
                    <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(link.target_item_id!)}>
                      打开页面
                    </button>
                  ) : (
                    <button
                      className="secondary-button"
                      disabled={isBusy || !selectedWikiPage}
                      onClick={() => selectedWikiPage ? prepareWikiLinkRepair(link.target_title, selectedWikiPage.title) : undefined}
                    >
                      预填补缺页
                    </button>
                  )}
                </div>
              </div>
            ))}
            {selectedWikiDetail?.backlinks?.map((link, index) => (
              <div className="link-card" key={`backlink-${link.source_item_id}-${link.target_title}-${index}`}>
                <strong>{link.target_title}</strong>
                <span>反链 · {formatWikiRelationType(link.relation_type)} · {link.relation_status} · {link.mention_count} 次提及</span>
                {link.source_item_id ? (
                  <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(link.source_item_id)}>
                    打开来源页
                  </button>
                ) : null}
              </div>
            ))}
            <p className="section-label">Wiki Graph</p>
            <div className="wiki-maintenance">
              <span>
                {wikiGraph?.meta.mode === "ego" ? "当前页面局部子图" : "工作台总览图"} ·
                节点 {wikiGraph?.nodes.length ?? 0} / {wikiGraph?.meta.total_nodes ?? 0} ·
                边 {wikiGraph?.edges.length ?? 0}
                {wikiGraph?.meta.truncated ? " · 已截断" : ""}
              </span>
            </div>
            <div className="wiki-maintenance">
              <strong>图谱视角</strong>
              <button className={wikiGraphMode === "overview" ? "active" : ""} onClick={() => void switchWikiGraphMode("overview")} disabled={isBusy || !workspace}>
                总览图
              </button>
              <button className={wikiGraphMode === "ego" ? "active" : ""} onClick={() => void switchWikiGraphMode("ego")} disabled={isBusy || !workspace || !selectedWikiItemId}>
                当前页面子图
              </button>
            </div>
            <div className="wiki-maintenance">
              <strong>图谱类型过滤</strong>
              <span>{graphFilterLabel}</span>
              <div className="wiki-inline-pills">
                <button
                  className={wikiGraphKindFilters.length === 0 ? "active filter-pill" : "filter-pill"}
                  disabled={isBusy || !workspace}
                  onClick={() => void resetWikiGraphKinds()}
                >
                  全部
                </button>
                {availableWikiKinds.map((kind) => (
                  <button
                    key={`graph-kind-${kind}`}
                    className={wikiGraphKindFilters.includes(kind) ? "active filter-pill" : "filter-pill"}
                    disabled={isBusy || !workspace}
                    onClick={() => void toggleWikiGraphKind(kind)}
                  >
                    {kind}
                  </button>
                ))}
              </div>
            </div>
            <div className="wiki-maintenance">
              <strong>图谱搜索</strong>
              <input
                value={wikiGraphSearch}
                onChange={(event) => setWikiGraphSearch(event.target.value)}
                placeholder="搜索页面标题、摘要或页面类型"
              />
              {graphSearchHits.length ? (
                <div className="wiki-search-hit-list">
                  {graphSearchHits.map((page) => (
                    <div className="link-card" key={`graph-search-${page.item_id}`}>
                      <strong>{page.title}</strong>
                      <span>{page.page_kind || "TOPIC"} · v{page.latest_version_no}</span>
                      <div className="wiki-inline-actions">
                        <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(page.item_id)}>
                          打开页面
                        </button>
                        <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiGraphPage(page.item_id)}>
                          查看子图
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              ) : wikiGraphSearch.trim() ? <span>没有匹配的图谱页面。</span> : null}
            </div>
            {wikiGraph?.nodes.slice(0, 6).map((node) => (
              <div className="link-card" key={`graph-node-${node.item_id}`}>
                <strong>{node.title}</strong>
                <span>
                  {node.page_kind} · degree {node.degree} · 出链 {node.outgoing_count} · 反链 {node.backlink_count} · 引用 {node.citation_count}
                  {node.unresolved_count > 0 ? ` · 断链 ${node.unresolved_count}` : ""}
                </span>
                <div className="wiki-inline-actions">
                  <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(node.item_id)}>
                    打开页面
                  </button>
                  <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiGraphPage(node.item_id)}>
                    查看子图
                  </button>
                </div>
              </div>
            ))}
            {wikiGraph?.edges.slice(0, 6).map((edge, index) => (
              <div className="link-card" key={`graph-edge-${edge.source_item_id}-${edge.target_title}-${index}`}>
                <strong>{edge.source_title} {"->"} {edge.target_title}</strong>
                <span>{edge.relation_status} · {edge.mention_count} 次提及 · {formatWikiRelationType(edge.relation_type)}</span>
                <div className="wiki-inline-actions">
                  <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(edge.source_item_id)}>
                    打开来源页
                  </button>
                  {!edge.target_item_id ? (
                    <button className="secondary-button" disabled={isBusy} onClick={() => prepareWikiLinkRepair(edge.target_title, edge.source_title)}>
                      预填补缺页
                    </button>
                  ) : null}
                  {edge.target_item_id ? (
                    <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(edge.target_item_id!)}>
                      打开目标页
                    </button>
                  ) : null}
                  {edge.target_item_id ? (
                    <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiGraphPage(edge.target_item_id!)}>
                      查看目标子图
                    </button>
                  ) : null}
                </div>
              </div>
            ))}
            <details className="maintenance-drawer">
              <summary>
                <strong>维护工具</strong>
                <span>重建链接、自动补缺、维护问题、日志与手动补页</span>
              </summary>
              <div className="wiki-maintenance">
                <strong>维护动作</strong>
                <button onClick={rebuildWikiLinks} disabled={isBusy || !workspace}>重建链接</button>
                <button onClick={autoFixWiki} disabled={isBusy || !workspace}>自动补缺</button>
              </div>
              {wikiRebuildAdvice ? (
                <div className="wiki-maintenance">
                  <strong>构建建议</strong>
                  <span>{wikiRebuildAdvice.message}</span>
                  {wikiAdviceAction ? (
                    <button className="secondary-button" disabled={isBusy || !workspace} onClick={wikiAdviceAction.run}>
                      {wikiAdviceAction.label}
                    </button>
                  ) : null}
                </div>
              ) : null}
              {reviewRequiredIssues.length ? (
                <div className="wiki-citations">
                  <strong>人工确认队列</strong>
                  {reviewRequiredIssues.slice(0, 4).map((issue, index) => {
                    const issueAction = getWikiIssuePrimaryAction(issue);
                    return (
                      <div className="link-card" key={`manual-issue-${issue.issue_type}-${index}`}>
                        <strong>{issue.issue_type} / {issue.severity}</strong>
                        <span>{issue.message}</span>
                        <span>{issue.suggested_action}</span>
                        <div className="wiki-inline-actions">
                          {issueAction ? (
                            <button className="secondary-button" disabled={isBusy} onClick={issueAction.run}>
                              {issueAction.label}
                            </button>
                          ) : null}
                          {issue.item_id ? (
                            <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(issue.item_id!)}>
                              打开相关页面
                            </button>
                          ) : null}
                          <button className="secondary-button" disabled={isBusy} onClick={() => void focusWikiIssue(issue)}>
                            打开问题列表
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : null}
              <div className="wiki-maintenance">
                <strong>任务队列</strong>
                <span>
                  {wikiStats?.wiki_enabled ? "工作台级 Wiki 已开启" : "工作台级 Wiki 未开启"} ·
                  待处理 {wikiStats?.pending_task_count ?? 0} ·
                  最近任务 {(wikiStats?.recent_tasks?.length ?? 0)}
                </span>
              </div>
              {wikiStats?.recent_tasks?.length ? (
                <div className="wiki-citations">
                  <strong>最近任务详情</strong>
                  {wikiStats.recent_tasks.slice(0, 4).map((task) => renderWikiTaskCard(task, `recent-task-${task.task_id}`))}
                </div>
              ) : null}
              {wikiStats?.recent_updates?.length ? (
                <div className="wiki-citations">
                  <strong>最近更新</strong>
                  {wikiStats.recent_updates.slice(0, 4).map((page) => renderWikiRecentUpdateCard(page, `recent-update-${page.item_id}`))}
                </div>
              ) : null}
              <div className="wiki-citations">
                <strong>手动补页 / 修正文案</strong>
                <span>这里用于补缺页面或修正文案。若标题已存在，系统会直接追加新版本，而不是重复创建同名页面。</span>
                <label className="rail-field">
                  <span>页面标题</span>
                  <input value={wikiTitle} onChange={(event) => setWikiTitle(event.target.value)} />
                </label>
                <label className="rail-field">
                  <span>页面正文</span>
                  <textarea
                    value={wikiDraft}
                    onChange={(event) => setWikiDraft(event.target.value)}
                    placeholder="可以从断链关系或人工确认项预填草稿，也可以手动输入正文。"
                    rows={8}
                  />
                </label>
                <div className="wiki-inline-actions">
                  <button disabled={isBusy || !workspace} onClick={createWikiPage}>
                    提交补页 / 修正文案
                  </button>
                  <button className="secondary-button" disabled={isBusy} onClick={clearWikiRepairDraft}>
                    清空草稿
                  </button>
                </div>
                <span>如果当前是在修已有页面正文，优先使用中间区域的“追加 Wiki 版本”。</span>
              </div>
              <p className="section-label">维护问题列表</p>
              <div className="wiki-maintenance">
                <strong>问题过滤</strong>
                <div className="wiki-inline-pills">
                  <button
                    className={wikiIssuePageFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssuePageFilter("ALL")}
                    disabled={isBusy}
                  >
                    全工作台
                  </button>
                  <button
                    className={wikiIssuePageFilter === "CURRENT" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssuePageFilter("CURRENT")}
                    disabled={isBusy || !selectedWikiItemId}
                  >
                    当前页面 {selectedWikiIssues.length}
                  </button>
                </div>
                <div className="wiki-inline-pills">
                  <button
                    className={wikiIssueScopeFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueScopeFilter("ALL")}
                    disabled={isBusy}
                  >
                    全部 {wikiIssues.length}
                  </button>
                  <button
                    className={wikiIssueScopeFilter === "AUTO" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueScopeFilter("AUTO")}
                    disabled={isBusy}
                  >
                    自动 {autoFixableIssues.length}
                  </button>
                  <button
                    className={wikiIssueScopeFilter === "MANUAL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueScopeFilter("MANUAL")}
                    disabled={isBusy}
                  >
                    人工 {reviewRequiredIssues.length}
                  </button>
                </div>
                <div className="wiki-inline-pills">
                  <button
                    className={wikiIssueTypeFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueTypeFilter("ALL")}
                    disabled={isBusy}
                  >
                    全部类型
                  </button>
                  {wikiIssueTypes.map((issueType) => (
                    <button
                      key={`issue-type-${issueType}`}
                      className={wikiIssueTypeFilter === issueType ? "active filter-pill" : "filter-pill"}
                      onClick={() => setWikiIssueTypeFilter(issueType)}
                      disabled={isBusy}
                    >
                      {issueType}
                    </button>
                  ))}
                </div>
                <div className="wiki-inline-pills">
                  <button
                    className={wikiIssueSeverityFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                    onClick={() => setWikiIssueSeverityFilter("ALL")}
                    disabled={isBusy}
                  >
                    全部级别
                  </button>
                  {wikiIssueSeverities.map((severity) => (
                    <button
                      key={`issue-severity-${severity}`}
                      className={wikiIssueSeverityFilter === severity ? "active filter-pill" : "filter-pill"}
                      onClick={() => setWikiIssueSeverityFilter(severity)}
                      disabled={isBusy}
                    >
                      {severity}
                    </button>
                  ))}
                </div>
              </div>
              {filteredWikiIssues.length === 0 && <p className="empty-state">当前过滤条件下暂无维护问题。</p>}
              {filteredWikiIssues.slice(0, 8).map((issue, index) => {
                const issueAction = getWikiIssuePrimaryAction(issue);
                return (
                  <div className="link-card" key={`${issue.issue_type}-${issue.title}-${index}`}>
                    <strong>{issue.issue_type} · {issue.severity}</strong>
                    <span>{issue.message}</span>
                    <span>{issue.auto_fixable ? "可自动补缺" : "需人工确认"}</span>
                    <span>{issue.suggested_action}</span>
                    <div className="wiki-inline-actions">
                      {issue.auto_fixable ? (
                        <button className="secondary-button" disabled={isBusy || !workspace} onClick={() => void autoFixWiki()}>
                          自动补缺
                        </button>
                      ) : null}
                      {issueAction ? (
                        <button className="secondary-button" disabled={isBusy} onClick={issueAction.run}>
                          {issueAction.label}
                        </button>
                      ) : null}
                      {issue.item_id ? (
                        <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(issue.item_id)}>
                          打开相关页面
                        </button>
                      ) : null}
                    </div>
                  </div>
                );
              })}
              <p className="section-label">Wiki Log</p>
              {wikiLog.slice(0, 4).map((entry) => (
                <div className="link-card" key={entry.id}>
                  <strong>{entry.event_type}</strong>
                  <span>{entry.message}</span>
                  <span>{formatDateTime(entry.created_at)}</span>
                  <div className="wiki-inline-actions">
                    {entry.item_id ? (
                      <button className="secondary-button" disabled={isBusy} onClick={() => void openWikiPageById(entry.item_id!)}>
                        打开相关页面
                      </button>
                    ) : (
                      <button className="secondary-button" disabled={isBusy || !workspace} onClick={() => void openWikiIndex()}>
                        返回工作台总览
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </details>
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
          {sources.length > 0 && (
            <div className="task-card">
              <strong>工作台资料</strong>
              {sources.map((source) => (
                <span key={source.source_id}>
                  {source.title} · {source.status} · {source.index_status}
                  <button className="inline-action" onClick={() => void deleteSource(source)} disabled={isBusy}>
                    删除资料
                  </button>
                </span>
              ))}
            </div>
          )}

          <div className="conversation">
            {messages.map((message, index) => (
              <div key={`${message.role}-${index}`} className={`bubble ${message.role}`}>
                <MessageBubble message={message} />
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
            打开 Wiki 工作台
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
          {wikiRebuildAdvice ? <p className="phase-note">{wikiRebuildAdvice.message}</p> : null}
          {wikiUrl && <p className="wiki-url">{wikiUrl}</p>}
          <p className="phase-note">如果需要补页、修正文案或查看维护问题，统一进入 Wiki 工作台里的维护工具区处理。</p>
          <hr />
          <p className="section-label">知识沉淀</p>
          <label className="rail-field">
            <span>Note 标题</span>
            <input value={noteTitle} onChange={(event) => setNoteTitle(event.target.value)} />
          </label>
          <button onClick={saveLatestAnswerAsNote} disabled={isBusy || !lastAssistantMessageId}>
            保存最新回答为 Note
          </button>
          <hr />
          <p className="section-label">当前范围</p>
          <p className="phase-note">当前原型已接入三条聊天链路和工作台级 Wiki。Deep Research 与产物生成还不在这个页面展示，避免和当前可用能力混淆。</p>
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

async function delJson<T>(path: string): Promise<T> {
  const response = await request(path, { method: "DELETE" });
  return (await response.json() as ApiResponse<T>).data;
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

type MessageSection = {
  title: string;
  content: string;
};

type MessageCard = {
  title: string;
  preview: string;
  content: string;
};

type MessagePresentation = {
  leadTitle: string | null;
  body: string;
  cards: MessageCard[];
};

function MessageBubble({ message }: { message: Message }) {
  if (message.role !== "assistant" || !message.answerMode) {
    return <div className="bubble-body">{message.content}</div>;
  }
  const presentation = buildAssistantPresentation(message);
  return (
    <>
      {presentation.leadTitle ? <div className="bubble-label">{presentation.leadTitle}</div> : null}
      <div className="bubble-body">{presentation.body}</div>
      {presentation.cards.length > 0 ? (
        <div className="bubble-cards">
          {presentation.cards.map((card, index) => (
            <details className="message-card" key={`${card.title}-${index}`}>
              <summary>
                <strong>{card.title}</strong>
                <span>{card.preview}</span>
              </summary>
              <div className="message-card-body">{card.content}</div>
            </details>
          ))}
        </div>
      ) : null}
    </>
  );
}

function buildAssistantPresentation(message: Message): MessagePresentation {
  const sections = splitMarkdownSections(message.content);
  const [leadSection, ...restSections] = sections;
  const citationSectionTitles = new Set(["引用来源", "来源引用", "来源回链"]);
  const citationSection = restSections.find((section) => citationSectionTitles.has(section.title));
  const cards = message.answerMode === "note"
    ? buildNoteMessageCards(restSections, message.citations ?? [])
    : buildDefaultMessageCards(restSections, citationSectionTitles, citationSection, message.citations ?? []);
  if (!leadSection) {
    return {
      leadTitle: null,
      body: message.content.trim(),
      cards
    };
  }
  return {
    leadTitle: normalizeLeadTitle(leadSection.title),
    body: leadSection.content || leadSection.title,
    cards
  };
}

function buildDefaultMessageCards(
  restSections: MessageSection[],
  citationSectionTitles: Set<string>,
  citationSection: MessageSection | undefined,
  citations: string[]
) {
  const cards = restSections
    .filter((section) => section.content)
    .filter((section) => !(citations.length && citationSectionTitles.has(section.title)))
    .map((section) => buildMessageCard(section.title, section.content));
  if (citationSection?.content || citations.length) {
    cards.push(buildMessageCard(
      citationSection?.title || "来源引用",
      buildCitationCardContent(citationSection?.content ?? "", citations),
      citations.length
    ));
  }
  return cards;
}

function buildNoteMessageCards(restSections: MessageSection[], citations: string[]) {
  const byTitle = new Map(restSections.map((section) => [section.title.trim(), section]));
  const directLocation = byTitle.get("资料定位");
  const directReading = byTitle.get("深读窗口");
  const directEvidence = byTitle.get("摘录证据");
  const directCitation = byTitle.get("引用来源") ?? byTitle.get("来源引用") ?? byTitle.get("来源回链");
  if (directLocation || directReading || directEvidence) {
    const cards: MessageCard[] = [];
    if (directLocation?.content) {
      cards.push(buildMessageCard("资料定位", directLocation.content));
    }
    if (directReading?.content) {
      cards.push(buildMessageCard("深读窗口", directReading.content));
    }
    if (directEvidence?.content) {
      cards.push(buildMessageCard("摘录证据", directEvidence.content));
    }
    if (directCitation?.content || citations.length) {
      cards.push(buildMessageCard(
        "来源引用",
        buildCitationCardContent(directCitation?.content ?? "", citations),
        citations.length
      ));
    }
    return cards;
  }
  const cards: MessageCard[] = [];
  pushMergedNoteCard(cards, "资料定位", [
    byTitle.get("检索说明"),
    byTitle.get("Journal 信号"),
    byTitle.get("候选资料"),
    byTitle.get("关系扩展"),
    byTitle.get("验证批次")
  ]);
  pushMergedNoteCard(cards, "深读窗口", [
    byTitle.get("条目元数据"),
    byTitle.get("原文读取计划")
  ]);
  pushMergedNoteCard(cards, "摘录证据", [
    byTitle.get("关键观点"),
    byTitle.get("摘录证据")
  ]);
  const citationSection = byTitle.get("引用来源") ?? byTitle.get("来源引用") ?? byTitle.get("来源回链");
  if (citationSection?.content || citations.length) {
    cards.push(buildMessageCard(
      "来源引用",
      buildCitationCardContent(citationSection?.content ?? "", citations),
      citations.length
    ));
  }
  return cards;
}

function pushMergedNoteCard(cards: MessageCard[], title: string, sections: Array<MessageSection | undefined>) {
  const content = mergeCardSections(sections);
  if (!content) {
    return;
  }
  cards.push(buildMessageCard(title, content));
}

function mergeCardSections(sections: Array<MessageSection | undefined>) {
  const filled = sections.filter((section): section is MessageSection => Boolean(section?.content?.trim()));
  if (filled.length === 0) {
    return "";
  }
  if (filled.length === 1) {
    return filled[0].content.trim();
  }
  return filled
    .map((section) => `【${normalizeCardTitle(section.title)}】\n${section.content.trim()}`)
    .join("\n\n");
}

function splitMarkdownSections(content: string): MessageSection[] {
  const normalized = content.replaceAll("\r\n", "\n").trim();
  const matches = Array.from(normalized.matchAll(/^##\s+(.+)$/gm));
  if (matches.length === 0) {
    return normalized ? [{ title: "", content: normalized }] : [];
  }
  return matches.map((match, index) => {
    const title = (match[1] ?? "").trim();
    const start = (match.index ?? 0) + match[0].length;
    const end = index + 1 < matches.length ? (matches[index + 1].index ?? normalized.length) : normalized.length;
    return {
      title,
      content: normalized.slice(start, end).trim()
    };
  });
}

function normalizeLeadTitle(title: string) {
  const trimmed = title.trim();
  if (!trimmed || trimmed === "直接回答") {
    return null;
  }
  return trimmed;
}

function buildMessageCard(title: string, content: string, citationCount?: number): MessageCard {
  const normalizedTitle = normalizeCardTitle(title);
  return {
    title: normalizedTitle,
    preview: summarizeCard(normalizedTitle, content, citationCount),
    content
  };
}

function normalizeCardTitle(title: string) {
  switch (title.trim()) {
    case "引用来源":
    case "来源回链":
    case "来源引用":
      return "来源引用";
    case "原文读取计划":
      return "原文窗口";
    case "条目元数据":
      return "资料元信息";
    default:
      return title.trim();
  }
}

function buildCitationCardContent(sectionContent: string, citations: string[]) {
  const citationLines = citations.map((citation, index) => `${index + 1}. ${citation}`);
  if (!sectionContent.trim()) {
    return citationLines.join("\n");
  }
  if (countBulletLines(sectionContent) > 0) {
    return sectionContent.trim();
  }
  if (citationLines.length === 0) {
    return sectionContent.trim();
  }
  return `${sectionContent.trim()}\n\n${citationLines.join("\n")}`;
}

function summarizeCard(title: string, content: string, citationCount?: number) {
  const bulletCount = countBulletLines(content);
  if (title === "来源引用") {
    return `${citationCount ?? bulletCount ?? 0} 条来源`;
  }
  if (title === "资料定位") {
    const count = countSectionBullets(content, "候选资料") || bulletCount;
    return `${count} 份候选资料`;
  }
  if (title === "深读窗口") {
    const count = countSectionBullets(content, "原文窗口") || bulletCount;
    return `${count} 个阅读窗口`;
  }
  if (title === "候选资料") {
    return `${bulletCount} 份候选资料`;
  }
  if (title === "关系扩展") {
    return `${bulletCount} 条扩展关系`;
  }
  if (title === "验证批次") {
    return `${bulletCount} 项验证摘要`;
  }
  if (title === "资料元信息") {
    return `${bulletCount} 份资料元信息`;
  }
  if (title === "原文窗口") {
    return `${bulletCount} 个阅读窗口`;
  }
  if (title === "摘录证据" || title === "关键依据" || title === "关键观点") {
    return `${bulletCount} 条证据`;
  }
  if (title === "Journal 信号") {
    return `${bulletCount} 条历史整理信号`;
  }
  if (title === "检索说明") {
    return "检索方式与展示策略";
  }
  if (title === "关键页面关系" || title === "反向引用关系" || title === "页面关系") {
    return `${bulletCount} 条页面关系`;
  }
  const firstLine = content.split("\n").map((line) => line.trim()).find(Boolean);
  return firstLine ? trimText(firstLine, 32) : "点击展开";
}

function countBulletLines(content: string) {
  return content.split("\n").filter((line) => line.trim().startsWith("- ")).length;
}

function countSectionBullets(content: string, sectionTitle: string) {
  const escapedTitle = sectionTitle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const pattern = new RegExp(`【${escapedTitle}】([\\s\\S]*?)(?=\\n\\n【|$)`);
  const match = content.match(pattern);
  return match ? countBulletLines(match[1]) : 0;
}

function trimText(value: string, limit: number) {
  return value.length <= limit ? value : `${value.slice(0, Math.max(0, limit - 1))}…`;
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

