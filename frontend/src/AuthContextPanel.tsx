import { FileText, Network, NotebookPen, Search, Sparkles } from "lucide-react";

export function AuthContextPanel() {
  return (
          <aside className="auth-context-panel">
            <div className="auth-context-topbar">
              <div className="auth-brand-lockup">
                <span className="auth-brand-symbol" aria-hidden="true">
                  <NotebookPen size={20} strokeWidth={1.8} />
                </span>
                <span className="auth-brand-title">NoteWeave</span>
              </div>
              <nav className="auth-context-nav" aria-label="产品能力">
                <span>Research</span>
                <span>Evidence</span>
                <span>Wiki</span>
              </nav>
            </div>
            <div className="auth-context-body">
              <p className="auth-context-kicker">A notebook for evidence</p>
              <h2 className="auth-context-heading">把分散线索编织成知识</h2>
              <p className="auth-context-lead">从来源、对话到可追踪的知识页面，NoteWeave 让每个结论都保留来处。</p>
              <div className="auth-weave-stage" aria-hidden="true">
                <span className="auth-weave-thread thread-source" />
                <span className="auth-weave-thread thread-note" />
                <span className="auth-weave-thread thread-wiki" />
                <span className="auth-weave-pulse pulse-one" />
                <span className="auth-weave-pulse pulse-two" />
                <div className="auth-weave-node node-source"><FileText size={16} /><span>Sources</span></div>
                <div className="auth-weave-node node-search"><Search size={16} /><span>Recall</span></div>
                <div className="auth-weave-core"><Sparkles size={22} /><span>Evidence</span></div>
                <div className="auth-weave-node node-note"><NotebookPen size={16} /><span>Notes</span></div>
                <div className="auth-weave-node node-wiki"><Network size={16} /><span>Wiki</span></div>
              </div>
              <div className="auth-product-strip" aria-label="NoteWeave 能力概览">
                <span><strong>01</strong><small>采集来源</small></span>
                <span><strong>02</strong><small>核验证据</small></span>
                <span><strong>03</strong><small>沉淀知识</small></span>
              </div>
            </div>
            <div className="auth-context-footer">
              <span>Research workspace</span>
              <span>Sources to insight</span>
            </div>
          </aside>
  );
}
