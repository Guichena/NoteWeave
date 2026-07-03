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
  const [sourceText, setSourceText] = useState("NoteWeave 阶段3包含问答 RAG、Marginalia 式 Note 链路和 WeKnora 式 Wiki 链路。");
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
  const [selectedWikiItemId, setSelectedWikiItemId] = useState("");
  const [isBusy, setIsBusy] = useState(false);
  const [status, setStatus] = useState("准备就绪");

  async function createWorkspace() {
    await run("创建工作台", async () => {
      const created = await post<Workspace>("/api/v2/workspaces", {
        name: "NoteWeave v2 研究工作台",
        description: "阶段1/2/3前端联调工作台"
      });
      setWorkspace(created);
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
      setMessages((current) => [
        ...current,
        {
          role: "system",
          content: `资料已上传并解析：source=${completed.source_id}，parse=${completed.parse_status}，index=${completed.index_status}`
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
      const sent = await post<{ assistant_request_id: string; stream_url: string }>(
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
      setWikiUrl(wiki.wiki_url);
      setWikiHome(wiki);
      setSelectedWikiItemId(wiki.pages[0]?.item_id ?? "");
      setView("wiki");
      window.history.pushState({}, "", wiki.wiki_url);
      setMessages((current) => [
        ...current,
        { role: "system", content: `默认 Wiki 工作台：${wiki.wiki_url}，页面数 ${wiki.pages.length}，链接数 ${wiki.links.length}` }
      ]);
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

  const selectedWikiPage = wikiHome?.pages.find((page) => page.item_id === selectedWikiItemId) ?? wikiHome?.pages[0];

  return (
    <main className="shell">
      <section className="hero">
        <p className="eyebrow">NoteWeave v2</p>
        <h1>研究工作台的阶段1/2/3最小闭环</h1>
        <p className="lede">
          在同一个工作台里完成创建、上传资料、三模式聊天和默认 Wiki 工作台入口。问答 RAG 负责快速证据问答，
          Note 参考 Marginalia 做结构化阅读漏斗，Wiki 参考 WeKnora 做 Wiki-first 页面回答。
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
          <button className="research-button" disabled>
            Deep Research
          </button>
        </div>
      </section>

      {view === "wiki" && wikiHome ? (
        <section className="wiki-workbench">
          <aside className="wiki-index">
            <p className="section-label">Wiki Index</p>
            <h2>默认 Wiki 工作台</h2>
            <p className="wiki-url">{wikiHome.wiki_url}</p>
            <div className="wiki-page-list">
              {wikiHome.pages.length === 0 && <p className="empty-state">还没有 Wiki 页面。可以先在 Wiki 模式回答后，将稳定内容创建为 Wiki 页面。</p>}
              {wikiHome.pages.map((page) => (
                <button
                  key={page.item_id}
                  className={page.item_id === selectedWikiPage?.item_id ? "active" : ""}
                  onClick={() => setSelectedWikiItemId(page.item_id)}
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
                <p className="version-pill">当前版本 v{selectedWikiPage.latest_version_no}</p>
                <div className="wiki-summary">
                  {selectedWikiPage.summary || "这个页面暂时还没有摘要。"}
                </div>
                <div className="wiki-maintenance">
                  <strong>页面维护动作</strong>
                  <span>版本追加、来源引用和页面链接由后端接口底座承接；当前工作台视图聚焦页面浏览、版本识别和链接检查。</span>
                </div>
              </>
            ) : (
              <div className="empty-state">当前工作台暂无 Wiki 页面。</div>
            )}
          </article>

          <aside className="wiki-links">
            <p className="section-label">页面关系</p>
            {wikiHome.links.length === 0 && <p className="empty-state">暂无页面链接。</p>}
            {wikiHome.links.map((link, index) => (
              <div className="link-card" key={`${link.source_item_id}-${link.target_title}-${index}`}>
                <strong>{link.target_title}</strong>
                <span>{link.relation_type} · {link.relation_status} · {link.mention_count} 次提及</span>
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
          <p className="section-label">Wiki 工作台</p>
          <button onClick={openWikiHome} disabled={isBusy || !workspace}>
            进入默认 Wiki 工作台
          </button>
          {wikiUrl && <p className="wiki-url">{wikiUrl}</p>}
          <button onClick={openWikiHome} disabled={isBusy || !workspace}>查看 Wiki Index</button>
          <button disabled>维护页面链接</button>
          <hr />
          <p className="section-label">右侧产物栏</p>
          <button disabled>生成报告</button>
          <button disabled>生成 FAQ</button>
          <button disabled>生成测验</button>
          <button disabled>生成学习指南</button>
          <button disabled>导出 Markdown / PDF</button>
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
  const events = stream.split("\n\n");
  const values: string[] = [];
  for (const event of events) {
    if (event.includes(`event: ${eventName}`)) {
      const dataLine = event.split("\n").find((line) => line.startsWith("data: "));
      if (dataLine) {
        values.push(dataLine.replace("data: ", "").replaceAll("\\n", "\n"));
      }
    }
  }
  return values;
}
