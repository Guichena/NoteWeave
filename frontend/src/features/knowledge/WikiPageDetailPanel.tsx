import type { WikiWorkbenchProps } from "./buildWikiWorkbenchProps";
import { useEffect, useState } from "react";
import { History, PencilLine } from "lucide-react";
import { MarkdownSurface } from "../../shared/ui/MarkdownSurface";
import { formatWikiKind } from "./wikiUtils";

type WikiPageDetailPanelProps = Pick<WikiWorkbenchProps, "isBusy" | "workspace"> & {
  selection: WikiWorkbenchProps["selection"];
  draft: Pick<WikiWorkbenchProps["draft"], "wikiAppendDraft" | "setWikiAppendDraft" | "wikiRenameTitle" | "setWikiRenameTitle">;
  actions: Pick<
    WikiWorkbenchProps["actions"],
    | "loadWikiVersion"
    | "restoreLatestWikiVersion"
    | "openWikiPageById"
    | "autoFixWiki"
    | "renameSelectedWikiPage"
    | "deleteSelectedWikiPage"
    | "appendWikiVersion"
  >;
  helpers: Pick<
    WikiWorkbenchProps["helpers"],
    "formatDateTime" | "buildKnowledgeCitationLabel" | "getWikiIssuePrimaryAction"
  >;
  pages?: Array<{ item_id: string; title: string }>;
  onRepairLink?: (targetTitle: string, sourceTitle: string) => void;
};

export function WikiPageDetailPanel({
  isBusy,
  workspace,
  selection,
  draft,
  actions,
  helpers,
  pages = [],
  onRepairLink
}: WikiPageDetailPanelProps) {
  const props = { isBusy, workspace, ...selection, ...draft, ...actions, ...helpers };
  const page = props.selectedWikiPage;
  const [editing, setEditing] = useState(false);
  useEffect(() => {
    setEditing(false);
  }, [page?.item_id]);
  if (!page) {
    return null;
  }
  const latestVersion = props.selectedWikiDetail?.latest_version_no ?? page.latest_version_no;

  const viewingHistory = Boolean(
    props.selectedWikiVersionDetail && props.selectedWikiVersionDetail.version_no !== latestVersion
  );
  const pageContent = (props.selectedWikiVersionDetail?.version_no === latestVersion
    ? props.selectedWikiDetail?.content
    : props.selectedWikiVersionDetail?.content) || props.selectedWikiDetail?.content || page.summary || "这个页面暂时还没有正文。";
  const resolveLinkTarget = (title: string) => {
    const normalized = title.trim().toLocaleLowerCase();
    const fromLinks = props.selectedWikiDetail?.outgoing_links?.find(
      (link) => link.target_title.trim().toLocaleLowerCase() === normalized && link.target_item_id
    );
    if (fromLinks?.target_item_id) return fromLinks.target_item_id;
    return pages.find((entry) => entry.title.trim().toLocaleLowerCase() === normalized)?.item_id ?? "";
  };
  const citations = viewingHistory
    ? props.selectedWikiVersionDetail?.citations ?? []
    : props.selectedWikiDetail?.citations ?? [];

  return (
    <article className="wiki-page wiki-page-detail">
      <header className="wiki-doc-header">
        <p className="wiki-doc-kicker">
          <span className="wiki-kind-tag">{formatWikiKind(props.selectedWikiDetail?.page_kind || page.page_kind)}</span>
          <span>v{viewingHistory ? props.selectedWikiVersionDetail?.version_no : latestVersion}</span>
          <span>更新于 {props.formatDateTime(props.selectedWikiDetail?.updated_at ?? page.updated_at)}</span>
        </p>
        <h2>{page.title}</h2>
        <div className="wiki-doc-toolbar">
          <button
            type="button"
            className={editing ? "secondary-button active" : "secondary-button"}
            aria-pressed={editing}
            onClick={() => setEditing((current) => !current)}
          >
            <PencilLine size={14} aria-hidden="true" />编辑
          </button>
          {props.selectedWikiVersions.length > 1 ? (
            <details className="wiki-version-menu">
              <summary className="secondary-button">
                <History size={14} aria-hidden="true" />{props.selectedWikiVersions.length} 个版本
              </summary>
              <div className="wiki-version-list">
                {props.selectedWikiVersions.map((version) => {
                  const active = (props.selectedWikiVersionDetail?.version_no ?? latestVersion) === version.version_no;
                  return (
                    <button
                      key={version.version_id}
                      type="button"
                      className={active ? "wiki-version-row active" : "wiki-version-row"}
                      disabled={props.isBusy}
                      onClick={() => void props.loadWikiVersion(page.item_id, version.version_no)}
                    >
                      <span className="version-pill">v{version.version_no}</span>
                      <span className="wiki-version-summary">{version.summary || "无摘要"}</span>
                      <small>{props.formatDateTime(version.created_at)} · 引用 {version.citation_count}</small>
                    </button>
                  );
                })}
              </div>
            </details>
          ) : null}
        </div>
        {viewingHistory && props.selectedWikiDetail ? (
          <div className="wiki-history-banner">
            <span>正在查看历史版本 v{props.selectedWikiVersionDetail?.version_no}</span>
            <button type="button" className="secondary-button" disabled={props.isBusy} onClick={props.restoreLatestWikiVersion}>
              返回最新版本
            </button>
          </div>
        ) : null}
      </header>

      {editing ? (
        <section className="wiki-edit-panel" aria-label="编辑页面">
          <label className="input-block">
            <span>页面标题</span>
            <div className="wiki-edit-inline">
              <input value={props.wikiRenameTitle} onChange={(event) => props.setWikiRenameTitle(event.target.value)} placeholder={page.title} />
              <button type="button" className="secondary-button" onClick={props.renameSelectedWikiPage} disabled={props.isBusy}>重命名</button>
            </div>
          </label>
          <label className="input-block">
            <span>追加为新版本</span>
            <textarea
              value={props.wikiAppendDraft}
              onChange={(event) => props.setWikiAppendDraft(event.target.value)}
              placeholder="粘贴或编辑新的页面正文，提交后会生成新版本，旧版本仍可回看。"
              rows={7}
            />
          </label>
          <div className="wiki-edit-actions">
            <button type="button" className="danger-button" onClick={props.deleteSelectedWikiPage} disabled={props.isBusy}>删除页面</button>
            <button type="button" className="primary-action" onClick={props.appendWikiVersion} disabled={props.isBusy}>追加 Wiki 版本</button>
          </div>
        </section>
      ) : null}

      <MarkdownSurface
        content={pageContent}
        className="wiki-doc-body"
        resolveWikiLink={(title) => Boolean(resolveLinkTarget(title))}
        onWikiLinkClick={(title) => {
          const targetId = resolveLinkTarget(title);
          if (targetId) {
            if (targetId !== page.item_id) void props.openWikiPageById(targetId);
          } else {
            onRepairLink?.(title, page.title);
          }
        }}
      />

      {citations.length ? (
        <section className="wiki-doc-section">
          <h3>来源引用</h3>
          <ol className="wiki-citation-list">
            {citations.map((citation) => (
              <li key={citation.citation_id}>
                <strong>{props.buildKnowledgeCitationLabel(citation)}</strong>
                {citation.quote_text ? <q>{citation.quote_text}</q> : null}
              </li>
            ))}
          </ol>
        </section>
      ) : null}

      {props.selectedWikiIssues.length ? (
        <section className="wiki-doc-section">
          <h3>这个页面需要处理</h3>
          <ul className="wiki-issue-list">
            {props.selectedWikiIssues.map((issue, index) => {
              const issueAction = props.getWikiIssuePrimaryAction(issue);
              return (
                <li className="wiki-issue-row" key={`page-issue-${issue.issue_type}-${index}`}>
                  <span className={`wiki-issue-dot${issue.auto_fixable ? " is-auto" : ""}`} aria-hidden="true" />
                  <div>
                    <strong>{issue.title || issue.issue_type}</strong>
                    <span>{issue.message}</span>
                  </div>
                  <div className="wiki-issue-actions">
                    {issue.auto_fixable ? (
                      <button type="button" className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={() => void props.autoFixWiki()}>
                        自动补缺
                      </button>
                    ) : null}
                    {issueAction ? (
                      <button type="button" className="secondary-button" disabled={props.isBusy} onClick={issueAction.run}>
                        {issueAction.label}
                      </button>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      {props.selectedWikiLog.length ? (
        <details className="wiki-doc-section wiki-doc-disclosure">
          <summary>变更记录 <small>{props.selectedWikiLog.length}</small></summary>
          <ol className="wiki-log-list">
            {props.selectedWikiLog.slice(0, 8).map((entry) => (
              <li key={`page-log-${entry.id}`}>
                <span>{entry.message}</span>
                <time>{props.formatDateTime(entry.created_at)}</time>
              </li>
            ))}
          </ol>
        </details>
      ) : null}
    </article>
  );
}
