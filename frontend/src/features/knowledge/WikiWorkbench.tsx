import { useEffect, useState } from "react";
import type { WikiWorkbenchProps } from "./buildWikiWorkbenchProps";
import { WikiIndexPanel } from "./WikiIndexPanel";
import { WikiOverviewPanel } from "./WikiOverviewPanel";
import { WikiPageDetailPanel } from "./WikiPageDetailPanel";
import { WikiRelationsPanel } from "./WikiRelationsPanel";
import { ChevronRight, RefreshCw, TriangleAlert, Wrench } from "lucide-react";
import { WikiMaintenancePanel, type MaintenanceTab } from "./WikiMaintenancePanel";
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
  const [manageOpen, setManageOpen] = useState(false);
  const [manageTab, setManageTab] = useState<MaintenanceTab>("issues");

  useEffect(() => {
    if (!manageOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setManageOpen(false);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [manageOpen]);

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

  const manageProps = {
    ...props,
    prepareWikiLinkRepair: (targetTitle: string, sourceTitle: string) => {
      props.prepareWikiLinkRepair(targetTitle, sourceTitle);
      setManageTab("draft");
      setManageOpen(true);
    }
  };

  return (
    <section className={`wiki-workbench${manageOpen ? " is-manage-open" : ""}`}>
      <WikiIndexPanel
        isBusy={props.isBusy}
        wikiHome={wikiHome}
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

      <div className="wiki-main">
        <header className="wiki-main-header">
          <nav className="wiki-breadcrumb" aria-label="知识库位置">
            {props.selectedWikiPage ? (
              <>
                <button type="button" className="wiki-breadcrumb-link" disabled={props.isBusy} onClick={() => void props.openWikiIndex()}>
                  知识库
                </button>
                <ChevronRight size={14} aria-hidden="true" />
                <span title={props.selectedWikiPage.title}>{props.selectedWikiPage.title}</span>
              </>
            ) : <span>知识库总览</span>}
          </nav>
          <button
            type="button"
            className="secondary-button wiki-manage-trigger"
            aria-expanded={manageOpen}
            onClick={() => {
              setManageTab("issues");
              setManageOpen(true);
            }}
          >
            <Wrench size={14} aria-hidden="true" />
            管理
            {wikiIssues.length > 0 ? <span className="wiki-manage-badge">{wikiIssues.length}</span> : null}
          </button>
        </header>

        {props.selectedWikiPage ? (
          <div className="wiki-page-layout">
            <WikiPageDetailPanel
              isBusy={isBusy}
              workspace={workspace}
              selection={selection}
              draft={draft}
              actions={actions}
              helpers={helpers}
              pages={wikiHome.pages}
              onRepairLink={manageProps.prepareWikiLinkRepair}
            />
            <WikiRelationsPanel {...manageProps} />
          </div>
        ) : (
          <article className="wiki-page wiki-overview-page">
            <WikiOverviewPanel
              {...manageProps}
              onOpenManage={() => {
                setManageTab("issues");
                setManageOpen(true);
              }}
            />
          </article>
        )}
      </div>

      {manageOpen ? (
        <>
          <button
            type="button"
            className="wiki-manage-backdrop"
            aria-label="关闭知识库管理"
            onClick={() => setManageOpen(false)}
          />
          <WikiMaintenancePanel
            {...manageProps}
            key={manageTab}
            initialTab={manageTab}
            onClose={() => setManageOpen(false)}
          />
        </>
      ) : null}
    </section>
  );
}
