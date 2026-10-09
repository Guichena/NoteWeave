import { useState } from "react";
import { Link2, Power, Sparkles, WandSparkles, X } from "lucide-react";
import type { WikiRelationsPanelProps } from "./wikiRelationsPanel.contract";

export type MaintenanceTab = "issues" | "draft" | "activity";

type WikiMaintenancePanelProps = WikiRelationsPanelProps & {
  initialTab?: MaintenanceTab;
  onClose: () => void;
};

/**
 * 知识库管理抽屉：把重建链接、自动补缺、问题队列、手动补页、任务与日志
 * 这些“维护型”功能集中在一处，平时不占用阅读界面。
 */
export function WikiMaintenancePanel(props: WikiMaintenancePanelProps) {
  const [tab, setTab] = useState<MaintenanceTab>(props.initialTab ?? "issues");
  const issues = props.wikiIssues ?? [];
  const visibleIssues = props.filteredWikiIssues ?? issues;
  const recentTasks = props.wikiStats?.recent_tasks ?? [];

  return (
    <aside className="wiki-manage-drawer" role="dialog" aria-label="知识库管理">
      <header className="pane-header">
        <h2 className="pane-title">知识库管理</h2>
        <div className="pane-header-actions">
          <button type="button" className="icon-button" aria-label="关闭知识库管理" onClick={props.onClose}>
            <X size={17} aria-hidden="true" />
          </button>
        </div>
      </header>

      <div className="pane-body wiki-manage-body">
        <div className="wiki-manage-actions">
          <button type="button" className="wiki-manage-action" onClick={props.rebuildWikiLinks} disabled={props.isBusy || !props.workspace}>
            <Link2 size={17} aria-hidden="true" />
            <span><strong>重建链接</strong><small>重新解析页面之间的引用关系</small></span>
          </button>
          <button type="button" className="wiki-manage-action" onClick={props.autoFixWiki} disabled={props.isBusy || !props.workspace}>
            <WandSparkles size={17} aria-hidden="true" />
            <span><strong>自动补缺</strong><small>{props.autoFixableIssues.length} 个问题可自动处理</small></span>
          </button>
          <button type="button" className="wiki-manage-action" onClick={() => void props.toggleWikiEnabled()} disabled={props.isBusy || !props.workspace}>
            <Power size={17} aria-hidden="true" />
            <span>
              <strong>{props.wikiEnabled ? "关闭 Wiki 构建" : "开启 Wiki 构建"}</strong>
              <small>{props.wikiEnabled ? "资料更新后会自动整理进知识库" : "开启后已有资料会自动整理进知识库"}</small>
            </span>
          </button>
          {props.wikiAdviceAction ? (
            <button type="button" className="wiki-manage-action" onClick={props.wikiAdviceAction.run} disabled={props.isBusy || !props.workspace}>
              <Sparkles size={17} aria-hidden="true" />
              <span><strong>{props.wikiAdviceAction.label}</strong><small>{props.wikiRebuildAdvice?.message || "构建建议"}</small></span>
            </button>
          ) : null}
        </div>

        <div className="wiki-manage-tabs" role="tablist" aria-label="管理内容">
          {([
            ["issues", `问题 ${issues.length}`],
            ["draft", "补页 / 修正"],
            ["activity", "任务与日志"]
          ] as Array<[MaintenanceTab, string]>).map(([key, label]) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={tab === key}
              className={tab === key ? "wiki-manage-tab active" : "wiki-manage-tab"}
              onClick={() => setTab(key)}
            >
              {label}
            </button>
          ))}
        </div>

        {tab === "issues" ? (
          <div className="wiki-manage-section">
            <div className="wiki-kind-filter" role="group" aria-label="问题范围">
              {([
                ["ALL", `全部 ${issues.length}`],
                ["AUTO", `可自动 ${props.autoFixableIssues.length}`],
                ["MANUAL", `需人工 ${props.reviewRequiredIssues.length}`]
              ] as const).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  className={props.wikiIssueScopeFilter === value ? "wiki-kind-chip active" : "wiki-kind-chip"}
                  onClick={() => props.setWikiIssueScopeFilter(value)}
                  disabled={props.isBusy}
                >
                  {label}
                </button>
              ))}
              {props.selectedWikiItemId ? (
                <button
                  type="button"
                  className={props.wikiIssuePageFilter === "CURRENT" ? "wiki-kind-chip active" : "wiki-kind-chip"}
                  onClick={() => props.setWikiIssuePageFilter(props.wikiIssuePageFilter === "CURRENT" ? "ALL" : "CURRENT")}
                  disabled={props.isBusy}
                >
                  仅当前页面
                </button>
              ) : null}
            </div>
            {visibleIssues.length === 0 ? <p className="phase-note">没有需要处理的问题。</p> : (
              <ul className="wiki-issue-list">
                {visibleIssues.slice(0, 20).map((issue, index) => {
                  const issueAction = props.getWikiIssuePrimaryAction(issue);
                  return (
                    <li key={`${issue.issue_type}-${issue.title}-${index}`} className="wiki-issue-row">
                      <span className={`wiki-issue-dot${issue.auto_fixable ? " is-auto" : ""}`} aria-hidden="true" />
                      <div>
                        <strong>{issue.title || issue.issue_type}</strong>
                        <span>{issue.message}</span>
                        <small>{issue.auto_fixable ? "可自动补缺" : "需人工确认"} · {issue.suggested_action}</small>
                      </div>
                      <div className="wiki-issue-actions">
                        {issue.auto_fixable ? (
                          <button type="button" className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={() => void props.autoFixWiki()}>
                            自动补缺
                          </button>
                        ) : null}
                        {issueAction ? (
                          <button type="button" className="secondary-button" disabled={props.isBusy} onClick={() => {
                            issueAction.run();
                            if (issueAction.label.includes("补")) setTab("draft");
                          }}>
                            {issueAction.label}
                          </button>
                        ) : null}
                        {issue.item_id && issue.item_id !== props.selectedWikiItemId ? (
                          <button type="button" className="ghost-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(issue.item_id)}>
                            打开页面
                          </button>
                        ) : null}
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        ) : null}

        {tab === "draft" ? (
          <form
            className="wiki-manage-section"
            onSubmit={(event) => {
              event.preventDefault();
              props.createWikiPage();
            }}
          >
            <p className="phase-note">
              用于补建缺失页面或修正文案。若标题已存在，会追加为该页面的新版本，不会重复创建。
            </p>
            <label className="rail-field">
              <span>页面标题</span>
              <input value={props.wikiTitle} onChange={(event) => props.setWikiTitle(event.target.value)} />
            </label>
            <label className="rail-field">
              <span>页面正文</span>
              <textarea
                value={props.wikiDraft}
                onChange={(event) => props.setWikiDraft(event.target.value)}
                placeholder="可以从断链或问题一键预填，也可以直接输入。"
                rows={9}
              />
            </label>
            <div className="wiki-inline-actions">
              <button type="submit" className="primary-action" disabled={props.isBusy || !props.workspace}>
                提交补页 / 修正文案
              </button>
              <button type="button" className="ghost-button" disabled={props.isBusy} onClick={props.clearWikiRepairDraft}>
                清空草稿
              </button>
            </div>
          </form>
        ) : null}

        {tab === "activity" ? (
          <div className="wiki-manage-section">
            <p className="phase-note">
              {props.wikiStats?.wiki_enabled ? "工作台级 Wiki 构建已开启" : "工作台级 Wiki 构建未开启"}
              {" · "}待处理任务 {props.wikiStats?.pending_task_count ?? 0}
            </p>
            {recentTasks.length > 0 ? (
              <div className="wiki-manage-cards">
                {recentTasks.slice(0, 4).map((task) => props.renderWikiTaskCard(task, `recent-task-${task.task_id}`))}
              </div>
            ) : null}
            <h3 className="wiki-manage-subtitle">变更记录</h3>
            {props.wikiLog.length === 0 ? <p className="phase-note">还没有变更记录。</p> : (
              <ol className="wiki-log-list">
                {props.wikiLog.slice(0, 12).map((entry) => (
                  <li key={entry.id}>
                    <span>{entry.message}</span>
                    <time>{props.formatDateTime(entry.created_at)}</time>
                    {entry.item_id ? (
                      <button type="button" className="inline-action" disabled={props.isBusy} onClick={() => void props.openWikiPageById(entry.item_id!)}>
                        打开
                      </button>
                    ) : null}
                  </li>
                ))}
              </ol>
            )}
          </div>
        ) : null}
      </div>
    </aside>
  );
}
