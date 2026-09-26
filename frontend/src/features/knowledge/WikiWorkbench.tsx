import { useEffect, useState } from "react";
import type { WikiWorkbenchProps } from "./buildWikiWorkbenchProps";
import { WikiIndexPanel } from "./WikiIndexPanel";
import { WikiOverviewPanel } from "./WikiOverviewPanel";
import { WikiPageDetailPanel } from "./WikiPageDetailPanel";
import { WikiRelationsPanel } from "./WikiRelationsPanel";
import { RefreshCw, TriangleAlert } from "lucide-react";
import { WikiEmptyWorkbench } from "./WikiEmptyWorkbench";

export type { WikiWorkbenchProps };

export function WikiWorkbench({
  isBusy,
  workspace,
  filters,
  draft,
  selection,
  data,
  actions,
  helpers,
  derived
}: WikiWorkbenchProps) {
  const [relationsOpen, setRelationsOpen] = useState(false);

  useEffect(() => {
    if (!relationsOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setRelationsOpen(false);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [relationsOpen]);

  const props = {
    isBusy,
    workspace,
    ...filters,
    ...draft,
    ...selection,
    ...data,
    ...actions,
    ...helpers,
    ...derived,
    wikiIssues: data.wikiIssues,
    wikiIssueTypes: derived.wikiIssueTypes,
    wikiIssueSeverities: derived.wikiIssueSeverities
  };
  const wikiIssues = props.wikiIssues ?? [];
  const wikiIssueTypes = props.wikiIssueTypes ?? [];
  const wikiIssueSeverities = props.wikiIssueSeverities ?? [];

  const wikiHome = props.wikiHome;

  if (!wikiHome) {
    const awaitingWorkspaceRestore = !props.workspace;
    return (
      <section className="wiki-workbench wiki-load-workbench">
        <article className="wiki-page wiki-load-state" role="region" aria-label="Wiki 加载状态">
          {props.isBusy || awaitingWorkspaceRestore ? (
            <div className="view-loading">
              <div className="view-loading-body">
                <RefreshCw className="view-loading-spinner-icon" size={18} aria-hidden="true" />
                <span>正在加载 Wiki 工作台数据...</span>
              </div>
              <div className="view-loading-skeleton">
                <div className="skeleton-line w-70" />
                <div className="skeleton-line w-55" />
                <div className="skeleton-line w-40" />
              </div>
            </div>
          ) : (
            <div className="empty-panel wiki-error-panel" role="alert">
              <span className="empty-state-icon" aria-hidden="true"><TriangleAlert size={21} /></span>
              <div className="empty-state-copy">
                <strong>Wiki 工作台加载失败</strong>
                <p>无法连接知识库服务或获取初始 Wiki 索引。请检查连接后重试。</p>
              </div>
              <div className="empty-state-actions">
                <button
                  type="button"
                  className="secondary-button"
                  onClick={() => void props.refreshWikiFromServer(props.selectedWikiItemId)}
                >
                  重试加载 Wiki
                </button>
              </div>
            </div>
          )}
        </article>
      </section>
    );
  }

  if (wikiHome.pages.length === 0 && (props.wikiIndex?.page_count ?? 0) === 0) {
    return (
      <WikiEmptyWorkbench
        workspaceName={props.workspace?.name || "当前工作台"}
        readySourceCount={props.wikiIndex?.ready_source_count ?? 0}
        wikiEnabled={Boolean(props.wikiIndex?.wiki_enabled)}
        advice={props.wikiRebuildAdvice?.message || "先准备可用资料，再开启工作台级 Wiki 构建。"}
        action={props.wikiAdviceAction}
        wikiTitle={props.wikiTitle}
        setWikiTitle={props.setWikiTitle}
        wikiDraft={props.wikiDraft}
        setWikiDraft={props.setWikiDraft}
        createWikiPage={props.createWikiPage}
        isBusy={props.isBusy}
      />
    );
  }

  return (
      <section className={`wiki-workbench${relationsOpen ? " relations-open" : ""}`}>
        <WikiIndexPanel
          isBusy={props.isBusy}
          wikiHome={wikiHome}
          wikiIndex={props.wikiIndex}
          relationsOpen={relationsOpen}
          onOpenRelations={() => setRelationsOpen(true)}
          wikiSearch={props.wikiSearch}
          setWikiSearch={props.setWikiSearch}
          wikiKindFilter={props.wikiKindFilter}
          setWikiKindFilter={props.setWikiKindFilter}
          availableWikiKinds={props.availableWikiKinds}
          visibleWikiPages={props.visibleWikiPages}
          groupedWikiPages={props.groupedWikiPages}
          selectedWikiItemId={props.selectedWikiItemId}
          selectedWikiPage={props.selectedWikiPage}
          wikiRebuildAdvice={props.wikiRebuildAdvice}
          openWikiIndex={props.openWikiIndex}
          selectWikiPage={props.selectWikiPage}
        />

        {props.selectedWikiPage ? (
          <WikiPageDetailPanel
            isBusy={isBusy}
            workspace={workspace}
            selection={selection}
            draft={draft}
            actions={actions}
            helpers={helpers}
          />
        ) : (
          <article className="wiki-page">
            <p className="section-label">Wiki Index</p>
            <WikiOverviewPanel {...props} />
          </article>
        )}
        {relationsOpen ? (
          <button
            type="button"
            className="wiki-relations-backdrop"
            aria-label="点击背景关闭关系面板"
            onClick={() => setRelationsOpen(false)}
          />
        ) : null}
        <WikiRelationsPanel {...props} onCloseRelations={() => setRelationsOpen(false)} />
      </section>
  );
}
