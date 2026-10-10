import { ArrowUp, SlidersHorizontal } from "lucide-react";
import type { KeyboardEvent } from "react";
import type { ResearchWorkbenchViewProps } from "./buildResearchWorkbenchProps";
import { isResearchSourceReady } from "./launch";

type ResearchComposerProps = ResearchWorkbenchViewProps["sidebar"] & {
  isBusy: boolean;
  onStart: () => void;
};

const MODES = [
  { value: "WEB_ONLY", label: "仅网络", hint: "从网络检索并核验证据" },
  { value: "WEB_PLUS_SEEDS", label: "网络 + 资料", hint: "网络检索，并结合所选资料" },
  { value: "SOURCES_ONLY", label: "仅资料", hint: "只使用所选资料，不联网" }
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

const EXAMPLES = [
  "中文资料问答场景下，Elasticsearch、Milvus 与 pgvector 该如何选型？",
  "长期记忆在 Agent 产品中如何避免污染后续事实？",
  "对比主流开源 RAG 框架的检索与评测能力"
];

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
    <div className="research-composer">
      <div className="research-composer-intro">
        <h2>想研究什么问题？</h2>
        <p>研究会拆成一张研究表，逐项检索和核验，每条结论都能追溯到证据。</p>
      </div>

      <div className="research-composer-box">
        <textarea
          id="research-question-input"
          aria-label="研究问题"
          value={researchQuestion}
          onChange={(event) => setResearchQuestion(event.target.value)}
          onKeyDown={handleKeyDown}
          rows={3}
          placeholder="写清楚要研究的问题、对比对象或判断标准"
        />
        <div className="research-composer-toolbar">
          <div className="research-mode-switch" role="radiogroup" aria-label="资料获取模式">
            {MODES.map((item) => (
              <button
                key={item.value}
                type="button"
                role="radio"
                aria-checked={researchRetrievalMode === item.value}
                className={`research-mode-option${researchRetrievalMode === item.value ? " is-active" : ""}`}
                onClick={() => setResearchRetrievalMode(item.value)}
                disabled={isBusy}
              >
                {item.label}
              </button>
            ))}
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
            className="research-start-button"
            aria-label="启动 Deep Research"
            title="开始研究"
            onClick={onStart}
            disabled={!canStart}
          >
            <ArrowUp size={18} aria-hidden="true" />
          </button>
        </div>
      </div>

      <p className="research-composer-hint">
        {mode.hint}
        {usesSources && readySources.length > 0 ? `，已选 ${readySelectedCount} 份资料` : ""}
      </p>

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

      {!researchQuestion.trim() ? (
        <div className="research-examples" aria-label="示例问题">
          {EXAMPLES.map((example) => (
            <button key={example} type="button" className="research-example" onClick={() => setResearchQuestion(example)}>{example}</button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
