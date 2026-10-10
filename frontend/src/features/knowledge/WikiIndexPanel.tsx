import { memo } from "react";
import { LayoutGrid, Search } from "lucide-react";
import { formatWikiKind } from "./wikiUtils";
import type { WikiGraphMode } from "./model";

type WikiIndexPage = {
  item_id: string;
  title: string;
  page_kind?: string;
  latest_version_no: number;
  outgoing_count: number;
  backlink_count: number;
  citation_count: number;
  unresolved_count: number;
};

type WikiIndexPanelProps = {
  isBusy: boolean;
  wikiHome: { pages: WikiIndexPage[] };
  wikiSearch: string;
  setWikiSearch: (value: string) => void;
  wikiKindFilter: string;
  setWikiKindFilter: (value: string) => void;
  availableWikiKinds: string[];
  visibleWikiPages: Array<{ item_id: string }>;
  groupedWikiPages: Record<string, WikiIndexPage[]>;
  selectedWikiItemId?: string | null;
  selectedWikiPage?: { item_id: string } | null;
  wikiRebuildAdvice?: { message?: string } | null;
  openWikiIndex: () => void | Promise<void>;
  selectWikiPage: (page: any, nextGraphMode?: WikiGraphMode) => void | Promise<void>;
};

/** Wiki 左侧目录：只保留找页面需要的东西——搜索、类型筛选、分组列表。 */
export const WikiIndexPanel = memo(function WikiIndexPanel(props: WikiIndexPanelProps) {
  const pageCount = props.wikiHome.pages.length;

  return (
    <aside className="wiki-index" aria-label="知识库目录">
      <div className="wiki-index-header">
        <h2 className="wiki-index-title">知识库</h2>
        <span>{pageCount} 页</span>
      </div>

      <label className="wiki-search-field">
        <Search size={15} aria-hidden="true" />
        <span className="sr-only">搜索 Wiki 页面</span>
        <input
          value={props.wikiSearch}
          onChange={(event) => props.setWikiSearch(event.target.value)}
          placeholder="搜索页面"
          aria-label="搜索 Wiki 页面"
        />
      </label>

      {props.availableWikiKinds.length > 1 ? (
        <div className="wiki-kind-filter" role="group" aria-label="页面类型筛选">
          <button
            type="button"
            className={props.wikiKindFilter === "ALL" ? "wiki-kind-chip active" : "wiki-kind-chip"}
            disabled={props.isBusy}
            onClick={() => props.setWikiKindFilter("ALL")}
          >
            全部
          </button>
          {props.availableWikiKinds.map((kind) => (
            <button
              type="button"
              key={kind}
              className={props.wikiKindFilter === kind ? "wiki-kind-chip active" : "wiki-kind-chip"}
              disabled={props.isBusy}
              onClick={() => props.setWikiKindFilter(kind)}
            >
              {formatWikiKind(kind)}
            </button>
          ))}
        </div>
      ) : null}

      <button
        type="button"
        className={!props.selectedWikiItemId ? "wiki-index-home active" : "wiki-index-home"}
        disabled={props.isBusy}
        onClick={() => void props.openWikiIndex()}
      >
        <LayoutGrid size={15} aria-hidden="true" />
        <span>总览</span>
      </button>

      <div className="wiki-page-list">
        {pageCount === 0 ? (
          <p className="wiki-index-empty">
            {props.wikiRebuildAdvice?.message ?? "还没有 Wiki 页面。"}
          </p>
        ) : null}
        {pageCount > 0 && props.visibleWikiPages.length === 0 ? (
          <p className="wiki-index-empty">没有匹配的页面，试试清空搜索或切换类型。</p>
        ) : null}
        {Object.entries(props.groupedWikiPages).map(([kind, pages]) => (
          <div key={`group-${kind}`} className="wiki-page-group">
            <div className="wiki-page-group-title">
              <span>{formatWikiKind(kind)}</span>
              <small>{pages.length}</small>
            </div>
            {pages.map((page) => {
              const active = page.item_id === props.selectedWikiPage?.item_id;
              return (
                <button
                  type="button"
                  key={page.item_id}
                  className={active ? "wiki-page-card active" : "wiki-page-card"}
                  aria-current={active ? "page" : undefined}
                  disabled={props.isBusy}
                  title={page.title}
                  onClick={() => void props.selectWikiPage(page, "ego")}
                >
                  <span className="wiki-page-card-title">{page.title}</span>
                  {page.unresolved_count > 0 ? (
                    <span className="wiki-page-card-flag" title={`${page.unresolved_count} 个断链`} aria-label={`${page.unresolved_count} 个断链`} />
                  ) : null}
                </button>
              );
            })}
          </div>
        ))}
      </div>
    </aside>
  );
});
