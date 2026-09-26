import type { WikiWorkbenchProps } from "./buildWikiWorkbenchProps";
import { MarkdownSurface } from "../../shared/ui/MarkdownSurface";

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
};

export function WikiPageDetailPanel({
  isBusy,
  workspace,
  selection,
  draft,
  actions,
  helpers
}: WikiPageDetailPanelProps) {
  const props = { isBusy, workspace, ...selection, ...draft, ...actions, ...helpers };
  const page = props.selectedWikiPage;
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

  return (
    <article className="wiki-page wiki-page-detail">
      <header className="wiki-doc-header">
        <p className="section-label">知识页 · {props.selectedWikiDetail?.page_kind || page.page_kind || "TOPIC"}</p>
        <h2>{page.title}</h2>
        <p className="wiki-doc-meta">
          <span className="version-pill">{viewingHistory ? `历史版本 v${props.selectedWikiVersionDetail?.version_no}` : `最新版本 v${latestVersion}`}</span>
          <span>更新于 {props.formatDateTime(props.selectedWikiDetail?.updated_at ?? page.updated_at)}</span>
          <span>
            {props.selectedWikiVersionDetail?.source_message_id
              ? `来自聊天消息 ${props.selectedWikiVersionDetail.source_message_id}`
              : "来自工作台资料或人工维护"}
          </span>
        </p>
        {viewingHistory && props.selectedWikiDetail ? (
          <div className="wiki-history-banner">
            <span>正在查看历史版本 v{props.selectedWikiVersionDetail?.version_no}</span>
            <button className="secondary-button" disabled={props.isBusy} onClick={props.restoreLatestWikiVersion}>
              返回最新版本
            </button>
          </div>
        ) : null}
      </header>

      <MarkdownSurface content={pageContent} className="wiki-doc-body" />

      {props.selectedWikiDetail?.citations.length ? (
        <section className="wiki-doc-section">
          <h3>来源引用</h3>
          <ol className="wiki-citation-list">
            {props.selectedWikiDetail.citations.map((citation) => (
              <li key={citation.citation_id}>
                <strong>{props.buildKnowledgeCitationLabel(citation)}</strong>
                {citation.quote_text ? <q>{citation.quote_text}</q> : null}
              </li>
            ))}
          </ol>
        </section>
      ) : null}

      {viewingHistory && props.selectedWikiVersionDetail ? (
        <section className="wiki-doc-section">
          <h3>历史版本引用</h3>
          {props.selectedWikiVersionDetail.citations.length === 0 ? <p className="phase-note">该历史版本没有绑定引用。</p> : (
            <ol className="wiki-citation-list">
              {props.selectedWikiVersionDetail.citations.map((citation) => (
                <li key={`history-${citation.citation_id}`}>
                  <strong>{props.buildKnowledgeCitationLabel(citation)}</strong>
                  {citation.quote_text ? <q>{citation.quote_text}</q> : null}
                </li>
              ))}
            </ol>
          )}
        </section>
      ) : null}

      <section className="wiki-doc-section wiki-doc-links">
        <div>
          <h3>页面出链</h3>
          {props.selectedWikiDetail?.outgoing_links?.length ? (
            <div className="wiki-link-chips">
              {props.selectedWikiDetail.outgoing_links.map((link, index) => (
                <button
                  key={`${link.source_item_id}-${link.target_title}-${index}-inline`}
                  className={link.target_item_id ? "wiki-link-chip" : "wiki-link-chip is-unresolved"}
                  disabled={props.isBusy || !link.target_item_id}
                  onClick={() => link.target_item_id ? void props.openWikiPageById(link.target_item_id) : undefined}
                >
                  {link.target_title}
                </button>
              ))}
            </div>
          ) : <p className="phase-note">当前页面暂无显式出链</p>}
        </div>
        <div>
          <h3>反向链接</h3>
          {props.selectedWikiDetail?.backlinks?.length ? (
            <div className="wiki-link-chips">
              {props.selectedWikiDetail.backlinks.map((link, index) => (
                <button
                  key={`${link.source_item_id}-${link.target_title}-${index}-back-inline`}
                  className="wiki-link-chip"
                  disabled={props.isBusy || !link.source_item_id}
                  onClick={() => link.source_item_id ? void props.openWikiPageById(link.source_item_id) : undefined}
                >
                  {link.target_title}
                </button>
              ))}
            </div>
          ) : <p className="phase-note">当前页面暂无反向链接</p>}
        </div>
      </section>

      <section className="wiki-doc-section">
        <h3>版本历史</h3>
        {props.selectedWikiVersions.length === 0 ? <p className="phase-note">当前页面还没有版本记录。</p> : (
          <div className="wiki-version-list">
            {props.selectedWikiVersions.map((version) => {
              const active = (props.selectedWikiVersionDetail?.version_no ?? latestVersion) === version.version_no;
              return (
                <button
                  key={version.version_id}
                  className={active ? "wiki-version-row active" : "wiki-version-row"}
                  disabled={props.isBusy}
                  onClick={() => void props.loadWikiVersion(page.item_id, version.version_no)}
                >
                  <span className="version-pill">v{version.version_no}</span>
                  <span className="wiki-version-summary">{version.summary || "无摘要"}</span>
                  <small>引用 {version.citation_count} · {version.source_message_id ? "聊天来源" : "工作台维护"} · {props.formatDateTime(version.created_at)}</small>
                </button>
              );
            })}
          </div>
        )}
      </section>

      <section className="wiki-doc-section">
        <h3>页面健康问题</h3>
        {props.selectedWikiIssues.length ? props.selectedWikiIssues.map((issue, index) => {
          const issueAction = props.getWikiIssuePrimaryAction(issue);
          return (
            <div className="link-card" key={`page-issue-${issue.issue_type}-${index}`}>
              <strong>{issue.issue_type} / {issue.severity}</strong>
              <span>{issue.message}</span>
              <span>{issue.auto_fixable ? "可自动修复" : "需人工确认"} · {issue.suggested_action}</span>
              <div className="wiki-inline-actions">
                {issue.auto_fixable ? (
                  <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={() => void props.autoFixWiki()}>
                    自动补缺
                  </button>
                ) : null}
                {issueAction ? (
                  <button className="secondary-button" disabled={props.isBusy} onClick={issueAction.run}>
                    {issueAction.label}
                  </button>
                ) : null}
                {issue.item_id && issue.item_id !== props.selectedWikiItemId ? (
                  <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(issue.item_id!)}>
                    打开相关页面
                  </button>
                ) : null}
              </div>
            </div>
          );
        }) : <p className="phase-note">当前页面没有单独的维护提醒。</p>}
      </section>

      <section className="wiki-doc-section">
        <h3>页面最近变更</h3>
        {props.selectedWikiLog.length ? (
          <ol className="wiki-log-list">
            {props.selectedWikiLog.slice(0, 5).map((entry) => (
              <li key={`page-log-${entry.id}`}>
                <strong>{entry.event_type}</strong>
                <span>{entry.message}</span>
                <time>{props.formatDateTime(entry.created_at)}</time>
              </li>
            ))}
          </ol>
        ) : <p className="phase-note">当前页面还没有可展示的变更日志。</p>}
      </section>

      <details className="wiki-doc-section wiki-edit-drawer">
        <summary>
          <strong>编辑页面</strong>
          <span>重命名、追加新版本或删除</span>
        </summary>
        <p className="phase-note">编辑正文会生成新版本；重命名会刷新页面关系并同步改写引用页；删除采用软删除，引用它的页面关系会回退为未解析。</p>
        <label className="input-block">
          <span>重命名页面</span>
          <input value={props.wikiRenameTitle} onChange={(event) => props.setWikiRenameTitle(event.target.value)} />
        </label>
        <div className="maintenance-actions">
          <button className="secondary-button" onClick={props.renameSelectedWikiPage} disabled={props.isBusy}>重命名</button>
          <button className="danger-button" onClick={props.deleteSelectedWikiPage} disabled={props.isBusy}>删除页面</button>
        </div>
        <label className="input-block">
          <span>追加为新版本</span>
          <textarea
            value={props.wikiAppendDraft}
            onChange={(event) => props.setWikiAppendDraft(event.target.value)}
            placeholder="粘贴或编辑新的 Wiki 页面正文，提交后 latest_version_no 会递增。"
            rows={6}
          />
        </label>
        <div className="maintenance-actions">
          <button className="primary-action" onClick={props.appendWikiVersion} disabled={props.isBusy}>追加 Wiki 版本</button>
        </div>
      </details>
    </article>
  );
}
