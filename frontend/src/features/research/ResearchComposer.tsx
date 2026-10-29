import { ArrowUp, BookOpen, Globe, Layers, SlidersHorizontal } from "lucide-react";
import type { KeyboardEvent } from "react";
import type { ResearchWorkbenchViewProps } from "./buildResearchWorkbenchProps";
import { isResearchSourceReady } from "./launch";

type ResearchComposerProps = ResearchWorkbenchViewProps["sidebar"] & {
  isBusy: boolean;
  onStart: () => void;
};

const MODES = [
  { value: "WEB_ONLY", label: "仅网络", hint: "从网络检索并逐项核验证据", icon: Globe },
  { value: "WEB_PLUS_SEEDS", label: "网络 + 资料", hint: "网络检索，并结合所选资料", icon: Layers },
  { value: "SOURCES_ONLY", label: "仅资料", hint: "只使用所选资料，不联网", icon: BookOpen }
] as const;

const DEPTHS = [
  { value: "QUICK", label: "快速" },
  { value: "STANDARD", label: "标准" },
  { value: "DEEP", label: "深入" }
];

const TYPES = [
  { value: "AUTO", label: "自动判断" },
  { value: "TECH_SOLUTION_COMPARISON", label: "技术方案对比" },
  { value: "PRODUCT_COMPARISON", label: "产品对比" },
  { value: "PAPER_SURVEY", label: "论文综述" },
  { value: "GITHUB_REPO_ANALYSIS", label: "开源仓库分析" },
  { value: "CONCEPT_RESEARCH", label: "概念研究" }
];

export const RESEARCH_EXAMPLES = [
  "中文资料问答场景下，Elasticsearch、Milvus 与 pgvector 该如何选型？",
  "长期记忆在 Agent 产品中如何避免污染后续事实？",
  "对比主流开源 RAG 框架的检索与评测能力"
];

/** 研究输入框：与问答页的输入框同一套样式，固定在对话底部；资料模式、研究设置都收在工具栏里。 */
export function ResearchComposer(props: ResearchComposerProps) {
  const {
    isBusy,
    workspace,
    sources,
    researchQuestion,
    setResearchQuestion,
    researchGoal,
    setResearchGoal,
    researchRetrievalMode,
    setResearchRetrievalMode,
    researchTimeRange,
    setResearchTimeRange,
    researchDepth,
    setResearchDepth,
    researchType,
    setResearchType,
    researchConstraintsText,
    setResearchConstraintsText,
    selectedResearchSourceIds,
    toggleResearchScope,
    researchScopeSources,
    onStart
  } = props;

  const readySources = sources.filter(isResearchSourceReady);
  const usesSources = researchRetrievalMode !== "WEB_ONLY";
  const readySelectedCount = researchScopeSources.filter(isResearchSourceReady).length;
  const scopeReady = !usesSources || readySelectedCount > 0;
  const canStart = Boolean(workspace && researchQuestion.trim() && scopeReady) && !isBusy;
  const mode = MODES.find((item) => item.value === researchRetrievalMode) ?? MODES[0];

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      if (canStart) onStart();
    }
  }

  return (
    <div className="composer-dock research-composer-dock">
      {usesSources ? (
        readySources.length > 0 ? (
          <div className="research-scope-picker" role="group" aria-label="研究资料范围">
            {readySources.map((source) => {
              const active = selectedResearchSourceIds.includes(source.source_id);
              return (
                <button
                  key={source.source_id}
                  type="button"
                  aria-pressed={active}
                  className={`research-scope-chip${active ? " is-active" : ""}`}
                  onClick={() => toggleResearchScope(source.source_id)}
                  disabled={isBusy}
                  title={source.title}
                >
                  {source.title}
                </button>
              );
            })}
          </div>
        ) : (
          <p className="research-composer-hint is-warning">工作台还没有解析完成的资料，可以改用「仅网络」模式。</p>
        )
      ) : null}

      <div className={`composer-box${isBusy ? " is-busy" : ""}`}>
        <textarea
          id="research-question-input"
          aria-label="研究问题"
          value={researchQuestion}
          onChange={(event) => setResearchQuestion(event.target.value)}
          onKeyDown={handleKeyDown}
          rows={1}
          placeholder="写清楚要研究的问题、对比对象或判断标准"
        />
        <div className="composer-toolbar">
          <div className="answer-mode-switch" role="radiogroup" aria-label="资料获取模式">
            {MODES.map((item) => {
              const Icon = item.icon;
              const selected = researchRetrievalMode === item.value;
              return (
                <button
                  key={item.value}
                  type="button"
                  role="radio"
                  aria-checked={selected}
                  title={item.hint}
                  className={`answer-mode-segment${selected ? " is-active" : ""}`}
                  onClick={() => setResearchRetrievalMode(item.value)}
                  disabled={isBusy}
                >
                  <Icon size={14} aria-hidden="true" />
                  {item.label}
                </button>
              );
            })}
          </div>
          <details className="research-composer-settings">
            <summary><SlidersHorizontal size={15} aria-hidden="true" />设置</summary>
            <div className="research-settings-panel">
              <label className="rail-field">
                <span>研究目标</span>
                <input value={researchGoal} onChange={(event) => setResearchGoal(event.target.value)} placeholder="例如：给出按场景划分的建议" />
              </label>
              <label className="rail-field">
                <span>时间范围</span>
                <input value={researchTimeRange} onChange={(event) => setResearchTimeRange(event.target.value)} placeholder="例如：2024-2026，默认不限" />
              </label>
              <div className="research-settings-pair">
                <label className="rail-field">
                  <span>研究深度</span>
                  <select value={researchDepth} onChange={(event) => setResearchDepth(event.target.value)}>
                    {DEPTHS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                  </select>
                </label>
                <label className="rail-field">
                  <span>研究类型</span>
                  <select value={researchType} onChange={(event) => setResearchType(event.target.value)}>
                    {TYPES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                  </select>
                </label>
              </div>
              <label className="rail-field">
                <span>约束条件</span>
                <textarea
                  value={researchConstraintsText}
                  onChange={(event) => setResearchConstraintsText(event.target.value)}
                  rows={2}
                  placeholder="每行一条，例如：只采用官方文档"
                />
              </label>
            </div>
          </details>
          <button
            type="button"
            className="composer-send-button"
            aria-label="启动 Deep Research"
            title="开始研究"
            onClick={onStart}
            disabled={!canStart}
          >
            {isBusy ? <span className="composer-send-spinner" aria-hidden="true" /> : <ArrowUp size={18} aria-hidden="true" />}
          </button>
        </div>
      </div>
      <p className="composer-footnote">
        深度研究：{mode.hint}
        {usesSources && readySources.length > 0 ? `，已选 ${readySelectedCount} 份资料` : ""}，每条结论都能追溯到原文
      </p>
    </div>
  );
}
