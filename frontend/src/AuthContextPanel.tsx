import { FileText, Network, NotebookPen } from "lucide-react";
import { BrandMark } from "./features/shell/BrandMark";

/** 登录页左侧的品牌说明：一句话讲清楚产品，不做装饰性动画墙。 */
export function AuthContextPanel() {
  return (
    <aside className="auth-context-panel">
      <div className="auth-brand-lockup">
        <BrandMark className="auth-brand-symbol" size={40} />
        <span className="auth-brand-title">NoteWeave</span>
      </div>
      <div className="auth-context-body">
        <h2 className="auth-context-heading">把分散线索<br />编织成知识</h2>
        <p className="auth-context-lead">
          上传资料、围绕来源提问、生成可追溯的笔记与报告——每个结论都保留来处。
        </p>
        <ul className="auth-context-points" aria-label="NoteWeave 能力概览">
          <li><FileText size={16} aria-hidden="true" /><span>来源为本：回答附带可点击的引用</span></li>
          <li><NotebookPen size={16} aria-hidden="true" /><span>一键生成学习指南、报告与思维导图</span></li>
          <li><Network size={16} aria-hidden="true" /><span>沉淀为可链接、可追溯的 Wiki</span></li>
        </ul>
      </div>
    </aside>
  );
}
