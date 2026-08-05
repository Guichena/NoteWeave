import { memo } from "react";

type WikiIndexPanelProps = {
  isBusy: boolean;
  wikiHome: { pages: Array<{ item_id: string; title: string; page_kind?: string; latest_version_no: number; outgoing_count: number; backlink_count: number; citation_count: number; unresolved_count: number }> };
  wikiIndex?: {
    pending_task_count?: number;
    auto_fixable_issue_count?: number;
    manual_review_issue_count?: number;
  } | null;
  wikiUrl?: string;
  wikiSearch: string;
  setWikiSearch: (value: string) => void;
  wikiKindFilter: string;
  setWikiKindFilter: (value: string) => void;
  availableWikiKinds: string[];
  visibleWikiPages: Array<{ item_id: string }>;
  groupedWikiPages: Record<string, Array<{
    item_id: string;
    title: string;
    page_kind?: string;
    latest_version_no: number;
    outgoing_count: number;
    backlink_count: number;
    citation_count: number;
    unresolved_count: number;
  }>>;
  selectedWikiItemId?: string | null;
  selectedWikiPage?: { item_id: string } | null;
  wikiRebuildAdvice?: { message?: string } | null;
  openWikiIndex: () => void | Promise<void>;
  selectWikiPage: (page: any) => void | Promise<void>;
};

export const WikiIndexPanel = memo(function WikiIndexPanel(props: WikiIndexPanelProps) {
  const pending = props.wikiIndex?.pending_task_count ?? 0;
  const autoFix = props.wikiIndex?.auto_fixable_issue_count ?? 0;
  const manual = props.wikiIndex?.manual_review_issue_count ?? 0;

  return (
    <aside className="wiki-index">
      <div className="wiki-index-header">
        <p className="section-label">Wiki Index</p>
        <h2 className="wiki-index-title">知识网络</h2>
        {props.wikiUrl ? <p className="wiki-url" title={props.wikiUrl}>{props.wikiUrl}</p> : null}
      </div>
      <div className="wiki-inline-pills">
        <span className={`status-chip${pending > 0 ? " tone-waiting" : ""}`}>待处理 {pending}</span>
        <span className={`status-chip${autoFix > 0 ? " tone-active" : ""}`}>可自动修复 {autoFix}</span>
        <span className={`status-chip${manual > 0 ? " tone-warn" : ""}`}>人工确认 {manual}</span>
      </div>
      <button
        className={!props.selectedWikiItemId ? "active nav-button" : "nav-button"}
        disabled={props.isBusy}
        onClick={() => void props.openWikiIndex()}
      >
        <span>工作台总览</span>
        <small>自动构建 · 页面分布 · 维护提醒</small>
      </button>
      <label className="wiki-search-field">
        <span className="sr-only">搜索 Wiki 页面</span>
        <input
          value={props.wikiSearch}
          onChange={(event) => props.setWikiSearch(event.target.value)}
          placeholder="搜索页面标题或摘要"
          aria-label="搜索 Wiki 页面"
        />
      </label>
      <div className="wiki-kind-filter" role="group" aria-label="页面类型筛选">
        <button
          type="button"
          className={props.wikiKindFilter === "ALL" ? "active filter-pill" : "filter-pill"}
          disabled={props.isBusy}
          onClick={() => props.setWikiKindFilter("ALL")}
        >
          全部
        </button>
        {props.availableWikiKinds.map((kind) => (
          <button
            type="button"
            key={kind}
            className={props.wikiKindFilter === kind ? "active filter-pill" : "filter-pill"}
            disabled={props.isBusy}
            onClick={() => props.setWikiKindFilter(kind)}
          >
            {kind}
          </button>
        ))}
      </div>
      <div className="wiki-page-list">
        {props.wikiHome.pages.length === 0 && (
          <div className="empty-panel">
            <strong>还没有 Wiki 页面</strong>
            <p>
              {props.wikiRebuildAdvice?.message
                ?? "在产物栏开启 Wiki 构建，或从资料上传后自动生成知识页。"}
            </p>
          </div>
        )}
        {props.wikiHome.pages.length > 0 && props.visibleWikiPages.length === 0 && (
          <div className="empty-panel">
            <strong>没有匹配页面</strong>
            <p>试试清空搜索词，或切换页面类型筛选。</p>
          </div>
        )}
        {Object.entries(props.groupedWikiPages).map(([kind, pages]) => (
          <div key={`group-${kind}`} className="wiki-page-group">
            <div className="wiki-page-group-title">
              <strong>{kind}</strong>
              <small>{pages.length} 页</small>
            </div>
            {pages.map((page) => (
              <button
                type="button"
                key={page.item_id}
                className={page.item_id === props.selectedWikiPage?.item_id ? "active wiki-page-card" : "wiki-page-card"}
                disabled={props.isBusy}
                onClick={() => void props.selectWikiPage(page)}
              >
                <span className="wiki-page-card-title">{page.title}</span>
                <small>{page.page_kind || "TOPIC"} · v{page.latest_version_no}</small>
                <small className="wiki-page-card-meta">
                  出链 {page.outgoing_count} · 反链 {page.backlink_count} · 引用 {page.citation_count}
                  {page.unresolved_count > 0 ? (
                    <span className="wiki-break-hint"> · 断链 {page.unresolved_count}</span>
                  ) : null}
                </small>
              </button>
            ))}
          </div>
        ))}
      </div>
    </aside>
  );
});
