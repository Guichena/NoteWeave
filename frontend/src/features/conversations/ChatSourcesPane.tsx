import { ArrowRight, FileText, Files, LibraryBig, Link2, MessagesSquare } from "lucide-react";
import { formatSourceIndexStatus, formatSourceProcessingStatus, isSourceReadableOnly, isSourceSearchable, type SourceAsset } from "../sources/model";
import type { Workspace } from "../workspace/model";

export type ChatSourcesPaneProps = {
  sources: SourceAsset[];
  workspace: Workspace | null;
  conversationCount: number;
  onOpenSourceLibrary: () => void;
};

export function ChatSourcesPane({
  sources,
  workspace,
  conversationCount,
  onOpenSourceLibrary
}: ChatSourcesPaneProps) {
  const searchableSourceCount = sources.filter(isSourceSearchable).length;
  const readableOnlySourceCount = sources.filter(isSourceReadableOnly).length;
  const processingSourceCount = sources.length - searchableSourceCount - readableOnlySourceCount;
  const recentSources = sources.slice(0, 4);

  return (
    <aside className="sources-pane source-summary-pane" aria-label="资料库摘要">
      <div className="sources-pane-header">
        <div className="sources-pane-title-row">
          <div className="sources-pane-title-copy">
            <p className="section-label">Workspace library</p>
            <strong>共享资料</strong>
            <small title={workspace?.name || "未选择工作台"}>{workspace?.name || "未选择工作台"}</small>
          </div>
          <span className="source-count-badge"><b>{sources.length}</b><small>份</small></span>
        </div>
      </div>

      <div className="source-drawer-body">
        <div className="source-workspace-note">
          <span className="source-workspace-note-label"><Link2 size={13} aria-hidden="true" />绑定当前工作台</span>
          <strong title={workspace?.name || "未选择工作台"}>{workspace?.name || "未选择工作台"}</strong>
          <small><MessagesSquare size={13} aria-hidden="true" />{conversationCount} 个会话、Research 与 Wiki 共用</small>
        </div>

        <dl className="source-summary-metrics" aria-label="资料状态摘要">
          <div className="is-searchable"><dt>可检索</dt><dd>{searchableSourceCount}</dd></div>
          <div className="is-readable"><dt>仅可阅读</dt><dd>{readableOnlySourceCount}</dd></div>
          <div className="is-processing"><dt>处理中</dt><dd>{processingSourceCount}</dd></div>
        </dl>

        {recentSources.length > 0 ? (
          <div className="source-list source-summary-list">
            <div className="source-list-heading"><strong className="source-list-title">最近资料</strong><span>{Math.min(sources.length, 4)} / {sources.length}</span></div>
            {recentSources.map((source) => (
              <div key={source.source_id} className="source-list-item">
                <span className="source-file-mark" aria-hidden="true"><FileText size={16} /></span>
                <div className="source-list-copy">
                  <strong title={source.title}>{source.title}</strong>
                  <small>{formatSourceProcessingStatus(source.status)} · {formatSourceIndexStatus(source.index_status)}</small>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="empty-state source-empty">
            <span className="empty-state-icon" aria-hidden="true"><Files size={20} /></span>
            <div className="empty-state-copy"><strong>还没有资料</strong><p>添加第一份资料后，工作台内的多个会话就能共同使用。</p></div>
          </div>
        )}

        <button type="button" className="source-summary-open primary-action" onClick={onOpenSourceLibrary} disabled={!workspace}>
          <LibraryBig size={16} aria-hidden="true" />
          打开资料库
          <ArrowRight size={15} aria-hidden="true" />
        </button>
      </div>
    </aside>
  );
}
