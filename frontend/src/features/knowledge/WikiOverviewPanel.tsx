import type { WikiWorkbenchProps } from "./buildWikiWorkbenchProps";
import { formatSourceIndexStatus, formatSourceProcessingStatus } from "../sources/model";
import { BookOpenText, Link2, LibraryBig } from "lucide-react";

type WikiOverviewPanelProps =
  Pick<WikiWorkbenchProps, "isBusy" | "workspace">
  & Pick<WikiWorkbenchProps["data"], "wikiIndex" | "wikiRebuildAdvice">
  & Pick<WikiWorkbenchProps["derived"], "wikiAdviceAction">
  & Pick<WikiWorkbenchProps["helpers"],
    | "formatDateTime"
    | "renderWikiRecentUpdateCard"
    | "renderWikiTaskCard"
    | "getWikiIssuePrimaryAction"
  >
  & Pick<WikiWorkbenchProps["actions"], "getRecentSourceAction" | "openWikiPageById" | "autoFixWiki">;

export function WikiOverviewPanel(props: WikiOverviewPanelProps) {
  return (
    <>
      <div className="wiki-overview-hero">
        <div className="wiki-overview-heading">
          <span className="wiki-overview-icon" aria-hidden="true"><BookOpenText size={20} /></span>
          <div>
            <span className="wiki-overview-kicker">Workspace knowledge</span>
            <h2>工作台总览</h2>
          </div>
        </div>
        <div className="wiki-summary">
          Wiki 汇总当前工作台中由资料与人工维护沉淀的知识页面。

          {"\n\n"}先在总览核对构建状态和维护提醒，再进入具体页面查看版本、引用与关系。
        </div>
        <div className="wiki-overview-metrics" aria-label="Wiki 内容概况">
          <span><LibraryBig size={15} aria-hidden="true" /><strong>{props.wikiIndex?.ready_source_count ?? 0}</strong><small>已解析资料</small></span>
          <span><BookOpenText size={15} aria-hidden="true" /><strong>{props.wikiIndex?.page_count ?? 0}</strong><small>知识页面</small></span>
          <span><Link2 size={15} aria-hidden="true" /><strong>{props.wikiIndex?.link_count ?? 0}</strong><small>页面关系</small></span>
        </div>
      </div>
      <div className="wiki-maintenance">
        <strong>构建状态</strong>
        <span>
          {props.wikiIndex?.wiki_enabled ? "工作台级 Wiki 构建已开启" : "工作台级 Wiki 构建未开启"} ·
          已解析资料 {props.wikiIndex?.ready_source_count ?? 0} ·
          页面 {props.wikiIndex?.page_count ?? 0} ·
          待处理任务 {props.wikiIndex?.pending_task_count ?? 0}
        </span>
      </div>
      <div className="wiki-maintenance">
        <strong>页面构成</strong>
        <span>
          资料驱动页面 {props.wikiIndex?.source_backed_page_count ?? 0} ·
          人工维护页面 {props.wikiIndex?.manual_page_count ?? 0} ·
          链接 {props.wikiIndex?.link_count ?? 0} ·
          断链 {props.wikiIndex?.unresolved_link_count ?? 0}
        </span>
      </div>
      <div className="wiki-maintenance">
        <strong>构建建议</strong>
        <span>{props.wikiRebuildAdvice?.message ?? "当前工作台会在这里显示 Wiki 的自动构建建议。"}</span>
        {props.wikiAdviceAction ? (
          <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={props.wikiAdviceAction.run}>
            {props.wikiAdviceAction.label}
          </button>
        ) : null}
      </div>
      <div className="wiki-citations">
        <strong>页面类型分布</strong>
        <span>{props.wikiIndex ? Object.entries(props.wikiIndex.pages_by_kind).map(([kind, count]) => `${kind}:${count}`).join(" / ") || "暂无页面" : "暂无页面"}</span>
      </div>
      <div className="wiki-citations">
        <strong>最近更新页面</strong>
        {props.wikiIndex?.recent_updates?.length ? props.wikiIndex.recent_updates.map((page) => props.renderWikiRecentUpdateCard(page, `index-page-${page.item_id}`)) : <span>当前还没有已沉淀的 Wiki 页面。</span>}
      </div>
      <div className="wiki-citations">
        <strong>最近资料变化</strong>
        {props.wikiIndex?.recent_sources?.length ? props.wikiIndex.recent_sources.map((source) => {
          const sourceAction = props.getRecentSourceAction(source);
          return (
            <div className="link-card" key={`index-source-${source.source_id}`}>
              <strong>{source.title}</strong>
              <span className="wiki-source-status-line">
                <span className="wiki-source-state is-readable">{formatSourceProcessingStatus(source.status)}</span>
                <span className={`wiki-source-state${source.index_status.trim().toUpperCase() === "INDEXED" ? " is-indexed" : " is-unindexed"}`}>
                  {formatSourceIndexStatus(source.index_status)}
                </span>
                <time>{props.formatDateTime(source.updated_at)}</time>
              </span>
              {source.related_pages.length ? (
                <div className="wiki-inline-actions">
                  {source.related_pages.map((page) => (
                    <button
                      key={`source-page-${source.source_id}-${page.item_id}`}
                      className="secondary-button"
                      disabled={props.isBusy}
                      onClick={() => void props.openWikiPageById(page.item_id)}
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
                      <button className="secondary-button" disabled={props.isBusy} onClick={sourceAction.run}>
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
            待处理任务 {props.wikiIndex?.pending_task_count ?? 0} ·
            自动补缺 {props.wikiIndex?.auto_fixable_issue_count ?? 0} ·
            人工确认 {props.wikiIndex?.manual_review_issue_count ?? 0}
          </span>
        </summary>
        <div className="wiki-citations">
          <strong>最近 Wiki 任务</strong>
          {props.wikiIndex?.recent_tasks?.length ? props.wikiIndex.recent_tasks.map((task) => props.renderWikiTaskCard(task, `index-task-${task.task_id}`)) : <span>当前还没有 Wiki 相关任务记录。</span>}
        </div>
        <div className="wiki-citations">
          <strong>优先维护项</strong>
          {props.wikiIndex?.top_issues?.length ? props.wikiIndex.top_issues.map((issue, index) => {
            const issueAction = props.getWikiIssuePrimaryAction(issue);
            return (
              <div className="link-card" key={`index-issue-${issue.issue_type}-${index}`}>
                <strong>{issue.issue_type} / {issue.severity}</strong>
                <span>{issue.message}</span>
                <span>{issue.auto_fixable ? "可自动补缺" : "需人工确认"}</span>
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
                  {issue.item_id ? (
                    <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(issue.item_id!)}>
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
  );
}
