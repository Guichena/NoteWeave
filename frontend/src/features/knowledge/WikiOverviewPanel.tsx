import { ArrowRight, Sparkles, TriangleAlert, Wrench } from "lucide-react";
import { WikiGraphPanel } from "./WikiGraphPanel";
import { formatWikiKind } from "./wikiUtils";
import type { WikiRelationsPanelProps } from "./wikiRelationsPanel.contract";

type WikiOverviewPanelProps = WikiRelationsPanelProps & {
  onOpenManage: () => void;
};

/** 知识总览：几个关键数字 + 知识图谱 + 最近更新 + 需要处理的事项。 */
export function WikiOverviewPanel(props: WikiOverviewPanelProps) {
  const index = props.wikiIndex;
  const pages = props.wikiHome?.pages ?? [];
  const issues = props.wikiIssues ?? [];
  const recentUpdates = (index?.recent_updates?.length ? index.recent_updates : pages).slice(0, 6);
  const advice = props.wikiAdviceAction;

  return (
    <div className="wiki-overview">
      <header className="wiki-overview-hero">
        <p className="wiki-overview-kicker">{props.workspace?.name || "当前工作台"} · 知识库</p>
        <h2>知识总览</h2>
        <p className="wiki-overview-lead">
          资料解析后会自动沉淀为知识页，页面之间通过链接相互引用。从左侧目录打开任意页面阅读，或在图谱里探索它们的关系。
        </p>
      </header>

      <dl className="wiki-stat-row" aria-label="知识库概况">
        <div>
          <dt>知识页面</dt>
          <dd>{index?.page_count ?? pages.length}</dd>
        </div>
        <div>
          <dt>页面关系</dt>
          <dd>{index?.link_count ?? 0}</dd>
        </div>
        <div>
          <dt>来源引用</dt>
          <dd>{index?.citation_count ?? 0}</dd>
        </div>
        <div className={issues.length > 0 ? "is-attention" : undefined}>
          <dt>待处理</dt>
          <dd>{issues.length}</dd>
        </div>
      </dl>

      {advice ? (
        <div className="wiki-callout">
          <Sparkles size={17} aria-hidden="true" />
          <p>{props.wikiRebuildAdvice?.message || "知识库有可执行的构建建议。"}</p>
          <button type="button" className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={advice.run}>
            {advice.label}
          </button>
        </div>
      ) : null}

      <section className="wiki-overview-section wiki-overview-graph">
        <WikiGraphPanel {...props} variant="wide" />
      </section>

      <section className="wiki-overview-section">
        <div className="wiki-section-heading">
          <h3>最近更新</h3>
        </div>
        {recentUpdates.length > 0 ? (
          <ul className="wiki-recent-list">
            {recentUpdates.map((page) => (
              <li key={`recent-${page.item_id}`}>
                <button
                  type="button"
                  className="wiki-recent-row"
                  disabled={props.isBusy}
                  onClick={() => void props.selectWikiPage(page, "ego")}
                >
                  <span className="wiki-recent-title">{page.title}</span>
                  <span className="wiki-recent-summary">{page.summary || `${page.citation_count} 条引用 · ${page.backlink_count} 个反链`}</span>
                  <span className="wiki-recent-meta">
                    <span className="wiki-kind-tag">{formatWikiKind(page.page_kind)}</span>
                    <time>{props.formatDateTime(page.updated_at)}</time>
                  </span>
                  <ArrowRight className="wiki-recent-arrow" size={15} aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="phase-note">当前还没有已沉淀的 Wiki 页面。</p>
        )}
      </section>

      {issues.length > 0 ? (
        <section className="wiki-overview-section">
          <div className="wiki-section-heading">
            <h3>需要处理</h3>
            <button type="button" className="ghost-button" onClick={props.onOpenManage}>
              <Wrench size={14} aria-hidden="true" />全部 {issues.length} 项
            </button>
          </div>
          <ul className="wiki-issue-list">
            {issues.slice(0, 3).map((issue, index) => {
              const issueAction = props.getWikiIssuePrimaryAction(issue);
              return (
                <li key={`overview-issue-${issue.issue_type}-${index}`} className="wiki-issue-row">
                  <TriangleAlert size={15} aria-hidden="true" />
                  <div>
                    <strong>{issue.title || issue.issue_type}</strong>
                    <span>{issue.message}</span>
                  </div>
                  <div className="wiki-issue-actions">
                    {issue.auto_fixable ? (
                      <button type="button" className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={() => void props.autoFixWiki()}>
                        自动补缺
                      </button>
                    ) : issueAction ? (
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
    </div>
  );
}
