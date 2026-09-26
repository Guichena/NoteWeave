import type { WikiRelationsPanelProps } from "./wikiRelationsPanel.contract";
import { WikiGraphPanel } from "./WikiGraphPanel";
import { X } from "lucide-react";

export function WikiRelationsPanel(props: WikiRelationsPanelProps) {
  const wikiIssues = props.wikiIssues ?? [];
  const wikiIssueTypes = props.wikiIssueTypes ?? [];
  const wikiIssueSeverities = props.wikiIssueSeverities ?? [];
  return (
<aside className="wiki-links" id="wiki-relations-panel" aria-label="Wiki 关系面板">
  <p className="section-label">{props.selectedWikiPage ? "页面关系" : "工作台关系"}</p>
  <button
    type="button"
    className="wiki-relations-close secondary-button"
    aria-label="关闭关系面板"
    title="关闭关系面板"
    onClick={props.onCloseRelations}
  >
    <X size={17} aria-hidden="true" />
  </button>
  {props.wikiStats && (
    <div className="wiki-health-overview">
      <div className="wiki-health-metrics" aria-label="Wiki 健康摘要">
        <span><strong>{props.wikiStats.page_count}</strong><small>页面</small></span>
        <span><strong>{props.wikiStats.unresolved_link_count}</strong><small>断链</small></span>
        <span><strong>{props.wikiStats.issue_count}</strong><small>问题</small></span>
      </div>
      <p>
        {props.wikiStats.wiki_enabled ? "构建已开启" : "构建未开启"}
        {" · "}链接 {props.wikiStats.link_count}
        {" · "}引用 {props.wikiStats.citation_count}
        {" · "}待处理 {props.wikiStats.pending_task_count}
      </p>
      {props.wikiStats.auto_fixable_issue_count > 0 || props.wikiStats.manual_review_issue_count > 0 ? (
        <small>自动补缺 {props.wikiStats.auto_fixable_issue_count} · 人工确认 {props.wikiStats.manual_review_issue_count}</small>
      ) : null}
    </div>
  )}
  <WikiGraphPanel {...props} />
  {props.selectedWikiPage ? <section className="wiki-direct-relations" aria-labelledby="wiki-direct-relations-heading">
    <p className="section-label" id="wiki-direct-relations-heading">直接关系</p>
    {props.selectedWikiDetail?.outgoing_links?.length === 0 && props.selectedWikiDetail?.backlinks?.length === 0 && (
      <p className="empty-state">当前页面暂无直接关系。</p>
    )}
    {props.selectedWikiDetail?.outgoing_links?.map((link, index) => (
      <div className="link-card" key={`${link.source_item_id}-${link.target_title}-${index}`}>
      <strong>{link.target_title}</strong>
      <span>出链 · {props.formatWikiRelationType(link.relation_type)} · {link.relation_status} · {link.mention_count} 次提及</span>
      <div className="wiki-inline-actions">
        {link.target_item_id ? (
          <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(link.target_item_id!)}>
            打开页面
          </button>
        ) : (
          <button
            className="secondary-button"
            disabled={props.isBusy || !props.selectedWikiPage}
            onClick={() => props.selectedWikiPage ? props.prepareWikiLinkRepair(link.target_title, props.selectedWikiPage.title) : undefined}
          >
            预填补缺页
          </button>
        )}
      </div>
      </div>
    ))}
    {props.selectedWikiDetail?.backlinks?.map((link, index) => (
      <div className="link-card" key={`backlink-${link.source_item_id}-${link.target_title}-${index}`}>
      <strong>{link.target_title}</strong>
      <span>反链 · {props.formatWikiRelationType(link.relation_type)} · {link.relation_status} · {link.mention_count} 次提及</span>
      {link.source_item_id ? (
        <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(link.source_item_id)}>
          打开来源页
        </button>
      ) : null}
      </div>
    ))}
  </section> : null}
  <details className="maintenance-drawer">
    <summary>
      <strong>维护工具</strong>
      <span>重建链接、自动补缺、维护问题、日志与手动补页</span>
    </summary>
    <div className="wiki-maintenance">
      <strong>维护动作</strong>
      <button onClick={props.rebuildWikiLinks} disabled={props.isBusy || !props.workspace}>重建链接</button>
      <button onClick={props.autoFixWiki} disabled={props.isBusy || !props.workspace}>自动补缺</button>
    </div>
    {props.wikiRebuildAdvice ? (
      <div className="wiki-maintenance">
        <strong>构建建议</strong>
        <span>{props.wikiRebuildAdvice.message}</span>
        {props.wikiAdviceAction ? (
          <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={props.wikiAdviceAction.run}>
            {props.wikiAdviceAction.label}
          </button>
        ) : null}
      </div>
    ) : null}
    {props.reviewRequiredIssues.length ? (
      <div className="wiki-citations">
        <strong>人工确认队列</strong>
        {props.reviewRequiredIssues.slice(0, 4).map((issue, index) => {
          const issueAction = props.getWikiIssuePrimaryAction(issue);
          return (
            <div className="link-card" key={`manual-issue-${issue.issue_type}-${index}`}>
              <strong>{issue.issue_type} / {issue.severity}</strong>
              <span>{issue.message}</span>
              <span>{issue.suggested_action}</span>
              <div className="wiki-inline-actions">
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
                <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.focusWikiIssue(issue)}>
                  打开问题列表
                </button>
              </div>
            </div>
          );
        })}
      </div>
    ) : null}
    <div className="wiki-maintenance">
      <strong>任务队列</strong>
      <span>
        {props.wikiStats?.wiki_enabled ? "工作台级 Wiki 已开启" : "工作台级 Wiki 未开启"} ·
        待处理 {props.wikiStats?.pending_task_count ?? 0} ·
        最近任务 {(props.wikiStats?.recent_tasks?.length ?? 0)}
      </span>
    </div>
    {props.wikiStats?.recent_tasks?.length ? (
      <div className="wiki-citations">
        <strong>最近任务详情</strong>
        {props.wikiStats.recent_tasks.slice(0, 4).map((task) => props.renderWikiTaskCard(task, `recent-task-${task.task_id}`))}
      </div>
    ) : null}
    {props.wikiStats?.recent_updates?.length ? (
      <div className="wiki-citations">
        <strong>最近更新</strong>
        {props.wikiStats.recent_updates.slice(0, 4).map((page) => props.renderWikiRecentUpdateCard(page, `recent-update-${page.item_id}`))}
      </div>
    ) : null}
    <div className="wiki-citations">
      <strong>手动补页 / 修正文案</strong>
      <span>这里用于补缺页面或修正文案。若标题已存在，系统会直接追加新版本，而不是重复创建同名页面。</span>
      <label className="rail-field">
        <span>页面标题</span>
        <input value={props.wikiTitle} onChange={(event) => props.setWikiTitle(event.target.value)} />
      </label>
      <label className="rail-field">
        <span>页面正文</span>
        <textarea
          value={props.wikiDraft}
          onChange={(event) => props.setWikiDraft(event.target.value)}
          placeholder="可以从断链关系或人工确认项预填草稿，也可以手动输入正文。"
          rows={8}
        />
      </label>
      <div className="wiki-inline-actions">
        <button disabled={props.isBusy || !props.workspace} onClick={props.createWikiPage}>
          提交补页 / 修正文案
        </button>
        <button className="secondary-button" disabled={props.isBusy} onClick={props.clearWikiRepairDraft}>
          清空草稿
        </button>
      </div>
      <span>如果当前是在修已有页面正文，优先使用中间区域的“追加 Wiki 版本”。</span>
    </div>
    <p className="section-label">维护问题列表</p>
    <div className="wiki-maintenance">
      <strong>问题过滤</strong>
      <div className="wiki-inline-pills">
        <button
          className={props.wikiIssuePageFilter === "ALL" ? "active filter-pill" : "filter-pill"}
          onClick={() => props.setWikiIssuePageFilter("ALL")}
          disabled={props.isBusy}
        >
          全工作台
        </button>
        <button
          className={props.wikiIssuePageFilter === "CURRENT" ? "active filter-pill" : "filter-pill"}
          onClick={() => props.setWikiIssuePageFilter("CURRENT")}
          disabled={props.isBusy || !props.selectedWikiItemId}
        >
          当前页面 {props.selectedWikiIssues.length}
        </button>
      </div>
      <div className="wiki-inline-pills">
        <button
          className={props.wikiIssueScopeFilter === "ALL" ? "active filter-pill" : "filter-pill"}
          onClick={() => props.setWikiIssueScopeFilter("ALL")}
          disabled={props.isBusy}
        >
          全部 {wikiIssues.length}
        </button>
        <button
          className={props.wikiIssueScopeFilter === "AUTO" ? "active filter-pill" : "filter-pill"}
          onClick={() => props.setWikiIssueScopeFilter("AUTO")}
          disabled={props.isBusy}
        >
          自动 {props.autoFixableIssues.length}
        </button>
        <button
          className={props.wikiIssueScopeFilter === "MANUAL" ? "active filter-pill" : "filter-pill"}
          onClick={() => props.setWikiIssueScopeFilter("MANUAL")}
          disabled={props.isBusy}
        >
          人工 {props.reviewRequiredIssues.length}
        </button>
      </div>
      <div className="wiki-inline-pills">
        <button
          className={props.wikiIssueTypeFilter === "ALL" ? "active filter-pill" : "filter-pill"}
          onClick={() => props.setWikiIssueTypeFilter("ALL")}
          disabled={props.isBusy}
        >
          全部类型
        </button>
        {wikiIssueTypes.map((issueType) => (
          <button
            key={`issue-type-${issueType}`}
            className={props.wikiIssueTypeFilter === issueType ? "active filter-pill" : "filter-pill"}
            onClick={() => props.setWikiIssueTypeFilter(issueType)}
            disabled={props.isBusy}
          >
            {issueType}
          </button>
        ))}
      </div>
      <div className="wiki-inline-pills">
        <button
          className={props.wikiIssueSeverityFilter === "ALL" ? "active filter-pill" : "filter-pill"}
          onClick={() => props.setWikiIssueSeverityFilter("ALL")}
          disabled={props.isBusy}
        >
          全部级别
        </button>
        {wikiIssueSeverities.map((severity) => (
          <button
            key={`issue-severity-${severity}`}
            className={props.wikiIssueSeverityFilter === severity ? "active filter-pill" : "filter-pill"}
            onClick={() => props.setWikiIssueSeverityFilter(severity)}
            disabled={props.isBusy}
          >
            {severity}
          </button>
        ))}
      </div>
    </div>
    {props.filteredWikiIssues.length === 0 && <p className="empty-state">当前过滤条件下暂无维护问题。</p>}
    {props.filteredWikiIssues.slice(0, 8).map((issue, index) => {
      const issueAction = props.getWikiIssuePrimaryAction(issue);
      return (
        <div className="link-card" key={`${issue.issue_type}-${issue.title}-${index}`}>
          <strong>{issue.issue_type} · {issue.severity}</strong>
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
              <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(issue.item_id)}>
                打开相关页面
              </button>
            ) : null}
          </div>
        </div>
      );
    })}
    <p className="section-label">变更记录</p>
    {props.wikiLog.slice(0, 4).map((entry) => (
      <div className="link-card" key={entry.id}>
        <strong>{entry.event_type}</strong>
        <span>{entry.message}</span>
        <span>{props.formatDateTime(entry.created_at)}</span>
        <div className="wiki-inline-actions">
          {entry.item_id ? (
            <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(entry.item_id!)}>
              打开相关页面
            </button>
          ) : (
            <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={() => void props.openWikiIndex()}>
              返回工作台总览
            </button>
          )}
        </div>
      </div>
    ))}
  </details>
</aside>
  );
}
