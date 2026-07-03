import { routes, type AnswerMode } from "./routes";

export function App() {
  return (
    <main className="shell">
      <section className="hero">
        <p className="eyebrow">NoteWeave v2</p>
        <h1>研究工作台的第一条闭环已经准备开跑</h1>
        <p className="lede">
          当前已进入阶段3：统一聊天框支持问答 RAG、Note 和 Wiki 三条回答链路。Note 负责整理和摘录，
          Wiki 负责稳定页面优先的知识回答，Deep Research 和右侧产物栏继续作为独立任务入口。
        </p>
      </section>

      <section className="workspace-card">
        <div>
          <p className="section-label">研究工作台</p>
          <h2>默认围绕当前工作台资料回答</h2>
          <p>上传资料后，问答链路读取资料切片；Note 链路先定位候选资料再打开原文窗口；Wiki 链路优先读取已沉淀页面。</p>
        </div>
        <button className="research-button">Deep Research</button>
      </section>

      <section className="layout">
        <div className="chat-panel">
          <div className="mode-tabs">
            {routes.map((route) => (
              <ModeTab key={route.key} mode={route.key} label={route.label} description={route.description} />
            ))}
          </div>
          <div className="mode-explainer">
            {routes.map((route) => (
              <article key={route.key}>
                <strong>{route.label}</strong>
                <span>{route.description}</span>
              </article>
            ))}
          </div>
          <div className="conversation">
            <div className="bubble user">阶段3里 Note 和 Wiki 有什么区别？</div>
            <div className="bubble assistant">
              Note 会像研究助理一样定位候选资料、打开原文窗口并生成摘录卡片；Wiki 会优先读取已经沉淀的页面，并提供进入 Wiki 工作台的入口。
            </div>
          </div>
          <div className="composer">输入问题后，将通过 `/api/v2/conversations/:id/messages` 发送，并由 `answer_mode` 决定背后链路。</div>
        </div>

        <aside className="artifact-rail">
          <p className="section-label">Wiki 工作台</p>
          <button>进入默认 Wiki 工作台</button>
          <button>查看 Wiki Index</button>
          <button>维护页面链接</button>
          <hr />
          <p className="section-label">右侧产物栏</p>
          <button>生成报告</button>
          <button>生成 FAQ</button>
          <button>生成测验</button>
          <button>生成学习指南</button>
          <button>导出 Markdown / PDF</button>
        </aside>
      </section>
    </main>
  );
}

function ModeTab({ mode, label, description }: { mode: AnswerMode; label: string; description: string }) {
  return (
    <button className={mode === "qa" ? "active" : ""} title={description}>
      {label}
    </button>
  );
}
