import { routes, type AnswerMode } from "./routes";

export function App() {
  return (
    <main className="shell">
      <section className="hero">
        <p className="eyebrow">NoteWeave v2</p>
        <h1>研究工作台的第一条闭环已经准备开跑</h1>
        <p className="lede">
          当前前端是阶段1/2的最小壳：工作台资料池、三种聊天链路入口、Deep Research按钮和右侧产物栏都先占位，
          后续可以直接按接口契约接入真实交互。
        </p>
      </section>

      <section className="workspace-card">
        <div>
          <p className="section-label">研究工作台</p>
          <h2>默认围绕当前工作台资料回答</h2>
          <p>上传资料后，问答链路会读取资料切片并返回引用；Note 和 Wiki 在阶段3接入不同检索逻辑。</p>
        </div>
        <button className="research-button">Deep Research</button>
      </section>

      <section className="layout">
        <div className="chat-panel">
          <div className="mode-tabs">
            {routes.map((route) => (
              <ModeTab key={route.key} mode={route.key} label={route.label} />
            ))}
          </div>
          <div className="conversation">
            <div className="bubble user">阶段2实现了什么？</div>
            <div className="bubble assistant">
              根据当前工作台资料，系统已经打通上传、解析切片、问答 RAG 和引用闭环。
            </div>
          </div>
          <div className="composer">输入问题后，将通过 `/api/v2/conversations/:id/messages` 发送。</div>
        </div>

        <aside className="artifact-rail">
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

function ModeTab({ mode, label }: { mode: AnswerMode; label: string }) {
  return <button className={mode === "qa" ? "active" : ""}>{label}</button>;
}
