import type { WikiWorkbenchProps } from "./buildWikiWorkbenchProps";

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

  return (
    <article className="wiki-page">
      <p className="section-label">Wiki Page</p>
      <h2>{page.title}</h2>
      <div className="wiki-maintenance">
        <strong>页面类型</strong>
        <span>{props.selectedWikiDetail?.page_kind || page.page_kind || "TOPIC"}</span>
      </div>
      <div className="wiki-maintenance">
        <strong>当前查看</strong>
        <span>
          {props.selectedWikiVersionDetail && props.selectedWikiVersionDetail.version_no !== latestVersion
            ? `历史版本 v${props.selectedWikiVersionDetail.version_no}`
            : `最新版本 v${latestVersion}`}
        </span>
      </div>
      <div className="wiki-maintenance">
        <strong>版本来源</strong>
        <span>
          {props.selectedWikiVersionDetail?.source_message_id
            ? `来自聊天消息 ${props.selectedWikiVersionDetail.source_message_id}`
            : "来自工作台级资料 ingest 或人工维护"}
          {" · "}
          {props.formatDateTime(props.selectedWikiVersionDetail?.created_at)}
        </span>
      </div>
      <div className="wiki-maintenance">
        <strong>页面更新时间</strong>
        <span>{props.formatDateTime(props.selectedWikiDetail?.updated_at ?? page.updated_at)}</span>
      </div>
      <p className="version-pill">当前版本 v{latestVersion}</p>
      <div className="wiki-summary">
        {(props.selectedWikiVersionDetail?.version_no === latestVersion
          ? props.selectedWikiDetail?.content
          : props.selectedWikiVersionDetail?.content) || props.selectedWikiDetail?.content || page.summary || "这个页面暂时还没有正文。"}
      </div>
      <div className="wiki-citations">
        <strong>版本历史</strong>
        {props.selectedWikiVersions.length === 0 && <span>当前页面还没有版本记录。</span>}
        {props.selectedWikiVersions.map((version) => (
          <button
            key={version.version_id}
            className="secondary-button"
            disabled={props.isBusy}
            onClick={() => void props.loadWikiVersion(page.item_id, version.version_no)}
          >
            v{version.version_no} · 引用 {version.citation_count} · {version.source_message_id ? "聊天来源" : "工作台维护"} · {version.summary || "无摘要"}
          </button>
        ))}
        {props.selectedWikiVersionDetail && props.selectedWikiDetail && props.selectedWikiVersionDetail.version_no !== props.selectedWikiDetail.latest_version_no ? (
          <button className="secondary-button" disabled={props.isBusy} onClick={props.restoreLatestWikiVersion}>
            返回最新版本
          </button>
        ) : null}
      </div>
      {props.selectedWikiVersionDetail && props.selectedWikiVersionDetail.version_no !== latestVersion ? (
        <div className="wiki-citations">
          <strong>历史版本引用</strong>
          {props.selectedWikiVersionDetail.citations.length === 0 && <span>该历史版本没有绑定引用。</span>}
          {props.selectedWikiVersionDetail.citations.map((citation, index) => (
            <span key={`history-${citation.citation_id}`}>
              {index + 1}. {props.buildKnowledgeCitationLabel(citation)}：{citation.quote_text}
            </span>
          ))}
        </div>
      ) : null}
      {props.selectedWikiDetail?.citations.length ? (
        <div className="wiki-citations">
          <strong>来源引用</strong>
          {props.selectedWikiDetail.citations.map((citation, index) => (
            <span key={citation.citation_id}>
              {index + 1}. {props.buildKnowledgeCitationLabel(citation)}：{citation.quote_text}
            </span>
          ))}
        </div>
      ) : null}
      <div className="wiki-maintenance">
        <strong>页面出链</strong>
        {props.selectedWikiDetail?.outgoing_links?.length ? (
          <span>
            {props.selectedWikiDetail.outgoing_links.map((link, index) => (
              <button
                key={`${link.source_item_id}-${link.target_title}-${index}-inline`}
                className="inline-action"
                disabled={props.isBusy || !link.target_item_id}
                onClick={() => link.target_item_id ? void props.openWikiPageById(link.target_item_id) : undefined}
              >
                {link.target_title}
              </button>
            ))}
          </span>
        ) : "当前页面暂无显式出链"}
      </div>
      <div className="wiki-maintenance">
        <strong>反向链接</strong>
        {props.selectedWikiDetail?.backlinks?.length ? (
          <span>
            {props.selectedWikiDetail.backlinks.map((link, index) => (
              <button
                key={`${link.source_item_id}-${link.target_title}-${index}-back-inline`}
                className="inline-action"
                disabled={props.isBusy || !link.source_item_id}
                onClick={() => link.source_item_id ? void props.openWikiPageById(link.source_item_id) : undefined}
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
        {props.selectedWikiIssues.length ? props.selectedWikiIssues.map((issue, index) => {
          const issueAction = props.getWikiIssuePrimaryAction(issue);
          return (
            <div className="link-card" key={`page-issue-${issue.issue_type}-${index}`}>
              <strong>{issue.issue_type} / {issue.severity}</strong>
              <span>{issue.message}</span>
              <span>{issue.auto_fixable ? "可自动修复" : "需人工确认"}</span>
              <span>{issue.suggested_action}</span>
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
        }) : <span>当前页面没有单独的维护提醒。</span>}
      </div>
      <div className="wiki-citations">
        <strong>页面最近变更</strong>
        {props.selectedWikiLog.length ? props.selectedWikiLog.slice(0, 5).map((entry) => (
          <div className="link-card" key={`page-log-${entry.id}`}>
            <strong>{entry.event_type}</strong>
            <span>{entry.message}</span>
            <span>{props.formatDateTime(entry.created_at)}</span>
          </div>
        )) : <span>当前页面还没有可展示的变更日志。</span>}
      </div>
      <label className="input-block">
        <span>重命名页面</span>
        <input value={props.wikiRenameTitle} onChange={(event) => props.setWikiRenameTitle(event.target.value)} />
      </label>
      <div className="maintenance-actions">
        <button onClick={props.renameSelectedWikiPage} disabled={props.isBusy}>重命名</button>
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
      <button onClick={props.appendWikiVersion} disabled={props.isBusy}>追加 Wiki 版本</button>
    </article>
  );
}
