import type { ResearchWorkbenchViewProps } from "./buildResearchWorkbenchProps";
import { isResearchSourceReady } from "./launch";
import { ResearchRunHistoryPanel } from "./ResearchRunHistoryPanel";
import { Play, RefreshCw } from "lucide-react";

export type ResearchSidebarProps = ResearchWorkbenchViewProps["sidebar"] & {
  isBusy: boolean;
};

export function ResearchSidebar(props: ResearchSidebarProps) {
  const {
    researchDeliverableFormat,
    researchProfile,
    researchDepth,
    researchType,
    researchRetrievalMode,
    researchQuestion,
    setResearchQuestion,
    researchGoal,
    setResearchGoal,
    setResearchProfile,
    setResearchDeliverableFormat,
    researchTimeRange,
    setResearchTimeRange,
    setResearchDepth,
    setResearchType,
    setResearchRetrievalMode,
    researchConstraintsText,
    setResearchConstraintsText,
    startDeepResearch,
    isBusy,
    workspace,
    loadResearchRunHistory,
    sources,
    selectedResearchSourceIds,
    currentSavedReportSource,
    focusedResearchSourceId,
    toggleResearchScope,
    setFocusedResearchSourceId,
    buildSourceOriginBadge,
    researchScopeSources,
    currentSavedReportSourceInScope,
    researchRuns,
  } = props;
  const readySourceCount = sources.filter(isResearchSourceReady).length;
  const readySelectedSourceCount = researchScopeSources.filter(isResearchSourceReady).length;
  const sourceScopeReady = researchRetrievalMode === "WEB_ONLY" || readySelectedSourceCount > 0;
  const canStartResearch = Boolean(workspace && researchQuestion.trim() && sourceScopeReady);
  return (
          <aside className="research-index">
            <div className="research-index-scroll">
            <p className="section-label">Deep Research</p>
            <h2>独立研究工作台</h2>
            <p className="phase-note">
              只读取此处显式填写的问题与勾选资料范围，不会从聊天上下文隐式升级。
            </p>
            <label className="rail-field">
              <span>研究问题</span>
              <textarea
                value={researchQuestion}
                id="research-question-input"
                onChange={(event) => setResearchQuestion(event.target.value)}
                rows={4}
                placeholder="写清楚要验证的问题、范围与成功标准。"
              />
            </label>
            <label className="rail-field">
              <span>研究目标</span>
              <textarea
                value={researchGoal}
                onChange={(event) => setResearchGoal(event.target.value)}
                rows={2}
                placeholder="例如：输出可验证结论、冲突点与后续恢复建议。"
              />
            </label>
            <div className="rail-field">
              <span>资料获取模式</span>
              <div className="research-filter-row" role="group" aria-label="Research retrieval mode">
                {(["WEB_ONLY", "WEB_PLUS_SEEDS", "SOURCES_ONLY"] as const).map((mode) => (
                  <button
                    key={mode}
                    type="button"
                    className={researchRetrievalMode === mode ? "filter-pill active" : "filter-pill"}
                    aria-pressed={researchRetrievalMode === mode}
                    onClick={() => setResearchRetrievalMode(mode)}
                    disabled={isBusy}
                  >
                    {mode === "WEB_ONLY" ? "仅网络" : mode === "WEB_PLUS_SEEDS" ? "网络 + 资料" : "仅资料"}
                  </button>
                ))}
              </div>
              <small>
                {researchRetrievalMode === "WEB_ONLY"
                  ? "默认从网络发现并归档证据；Workspace 负责归属和成果沉淀。"
                  : researchRetrievalMode === "WEB_PLUS_SEEDS"
                    ? "网络研究会结合下方显式选择的资料作为 seeds。"
                    : "封闭资料研究，不调用外部网络搜索。"}
              </small>
            </div>
            <details className="research-advanced-settings">
              <summary>
                <strong>研究设置</strong>
                <span>{researchDepth || "STANDARD"} · {researchType || "AUTO"} · {researchProfile || "default"}</span>
              </summary>
              <div className="research-advanced-fields">
                <label className="rail-field">
                  <span>研究 Profile</span>
                  <input value={researchProfile} onChange={(event) => setResearchProfile(event.target.value)} placeholder="default" />
                </label>
                <label className="rail-field">
                  <span>交付格式</span>
                  <input value={researchDeliverableFormat} onChange={(event) => setResearchDeliverableFormat(event.target.value)} placeholder="Evidence-backed research report" />
                </label>
                <label className="rail-field">
                  <span>时间范围</span>
                  <input value={researchTimeRange} onChange={(event) => setResearchTimeRange(event.target.value)} placeholder="例如：2024-2026 / 当前季度 / 不限" />
                </label>
                <label className="rail-field">
                  <span>研究深度</span>
                  <select value={researchDepth} onChange={(event) => setResearchDepth(event.target.value)}>
                    <option value="QUICK">QUICK</option>
                    <option value="STANDARD">STANDARD</option>
                    <option value="DEEP">DEEP</option>
                  </select>
                </label>
                <label className="rail-field">
                  <span>研究类型</span>
                  <select value={researchType} onChange={(event) => setResearchType(event.target.value)}>
                    <option value="AUTO">AUTO</option>
                    <option value="PAPER_SURVEY">PAPER_SURVEY</option>
                    <option value="GITHUB_REPO_ANALYSIS">GITHUB_REPO_ANALYSIS</option>
                    <option value="PRODUCT_COMPARISON">PRODUCT_COMPARISON</option>
                    <option value="TECH_SOLUTION_COMPARISON">TECH_SOLUTION_COMPARISON</option>
                    <option value="CONCEPT_RESEARCH">CONCEPT_RESEARCH</option>
                  </select>
                </label>
                <label className="rail-field research-constraints-field">
                  <span>显式约束</span>
                  <textarea value={researchConstraintsText} onChange={(event) => setResearchConstraintsText(event.target.value)} rows={3} />
                </label>
              </div>
            </details>
            <div className="wiki-maintenance">
              <strong>显式资料范围</strong>
              {sources.length > 0 ? (
                <>
                  <div className="research-scope">
                    {sources.map((source) => {
                      const sourceReady = isResearchSourceReady(source);
                      return <button
                        key={`research-scope-workbench-${source.source_id}`}
                        id={`research-source-scope-${source.source_id}`}
                        type="button"
                        className={[
                          selectedResearchSourceIds.includes(source.source_id) ? "active" : "",
                          currentSavedReportSource?.source_id === source.source_id ? "generated-source" : "",
                          focusedResearchSourceId === source.source_id ? "scope-focus" : "",
                          sourceReady ? "" : "unavailable"
                        ].filter(Boolean).join(" ")}
                        onClick={() => {
                          toggleResearchScope(source.source_id);
                          setFocusedResearchSourceId(source.source_id);
                        }}
                        disabled={isBusy || !sourceReady}
                        title={sourceReady ? "已解析，可加入或移出 Research source scope" : `资料尚未解析完成：${source.status}`}
                      >
                        {source.title}
                        {!sourceReady ? ` · ${source.status}` : ""}
                        {currentSavedReportSource?.source_id === source.source_id ? " · 当前报告" : ""}
                        {source.generated_by === "research_agent" && currentSavedReportSource?.source_id !== source.source_id
                          ? ` · ${buildSourceOriginBadge(source)}`
                          : ""}
                      </button>
                    })}
                  </div>
                  <span>
                    {researchScopeSources.length > 0
                      ? `当前 source scope：${researchScopeSources.map((source) => source.title).join(" / ")}`
                      : "当前未选择显式 source scope。"}
                  </span>
                  {currentSavedReportSource ? (
                    <small>
                      {currentSavedReportSourceInScope
                        ? "闭环回流：当前报告 source 已纳入显式 source scope。"
                        : "闭环回流：当前报告 source 已写回资料池，但尚未纳入显式 source scope。"}
                    </small>
                  ) : null}
                  {readySourceCount === 0 ? (
                    <small>当前没有已解析资料；未完成条目仅供查看，仍可使用“仅网络”发起研究。</small>
                  ) : null}
                </>
              ) : (
                <span>当前工作台还没有已解析资料。你仍然可以直接发起 Deep Research。</span>
              )}
            </div>
            {researchRuns.length > 0 ? <ResearchRunHistoryPanel {...props} /> : (
              <div className="research-empty-history">
                <strong>还没有历史研究</strong>
                <span>完成第一次研究后，运行记录、恢复入口与审计状态会显示在这里。</span>
              </div>
            )}
            </div>
            <div className="research-launch-dock">
              <span>
                {canStartResearch
                  ? "问题与资料范围已就绪"
                  : researchQuestion.trim()
                    ? "请选择已解析资料，或改用仅网络"
                    : "先填写要验证的研究问题"}
              </span>
              <div className="research-inline-actions research-launch-actions">
                <button
                  className="primary-action"
                  aria-label="启动 Deep Research"
                  onClick={startDeepResearch}
                  disabled={isBusy || !canStartResearch}
                >
                  <Play size={16} fill="currentColor" aria-hidden="true" />启动研究
                </button>
                <button className="secondary-button research-history-refresh" onClick={() => void loadResearchRunHistory()} disabled={isBusy || !workspace}>
                  <RefreshCw size={15} aria-hidden="true" />刷新历史
                </button>
              </div>
            </div>
          </aside>
  );
}
