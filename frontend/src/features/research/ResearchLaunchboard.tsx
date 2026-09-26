import { ArrowRight, BookOpenCheck, FolderOpen, PenLine } from "lucide-react";

type ResearchRetrievalMode = "WEB_ONLY" | "WEB_PLUS_SEEDS" | "SOURCES_ONLY" | string;

export type ResearchLaunchboardProps = {
  workspaceName: string;
  sourceCount: number;
  readySourceCount: number;
  selectedSourceCount: number;
  selectedSourceTitles: string[];
  researchHistoryCount: number;
  researchRetrievalMode: ResearchRetrievalMode;
  researchQuestion: string;
  onOpenSourceLibrary: () => void | Promise<void>;
};

const retrievalModeLabels: Record<string, string> = {
  WEB_ONLY: "仅网络",
  WEB_PLUS_SEEDS: "网络 + 资料",
  SOURCES_ONLY: "仅资料"
};

function focusResearchQuestion() {
  const questionInput = document.getElementById("research-question-input");
  if (!(questionInput instanceof HTMLTextAreaElement)) return;
  questionInput.focus();
  questionInput.scrollIntoView({ behavior: "smooth", block: "center" });
}

export function ResearchLaunchboard({
  workspaceName,
  sourceCount,
  readySourceCount,
  selectedSourceCount,
  selectedSourceTitles,
  researchHistoryCount,
  researchRetrievalMode,
  researchQuestion,
  onOpenSourceLibrary
}: ResearchLaunchboardProps) {
  const hasQuestion = Boolean(researchQuestion.trim());
  const retrievalModeLabel = retrievalModeLabels[researchRetrievalMode] || researchRetrievalMode || "未设置";
  const sourceNarrative = sourceCount === 0
    ? "先把 PDF 或 Markdown 放进工作台，之后可在 Chat、Research 和 Wiki 中共用。"
    : readySourceCount === 0
      ? `${sourceCount} 份资料正在解析，完成后即可纳入显式研究范围。`
      : selectedSourceCount > 0
        ? `${selectedSourceCount} 份已解析资料进入本次显式范围。`
        : "资料已解析，可作为 Research 输入；启动前可在左侧选择本次范围。";

  return (
    <div className="research-launchboard" aria-label="Research 启动面板">
      <div className="research-launchboard-hero">
        <div className="research-launchboard-mark" aria-hidden="true">
          <BookOpenCheck size={23} />
        </div>
        <div className="research-launchboard-copy">
          <span className="process-lane-badge">工作台研究</span>
          <h2>从一个问题开始，留下可核验的报告</h2>
          <p>
            研究问题、资料范围和检索模式都在左侧明确设置。启动后，搜索、阅读和证据会归属于当前工作台。
          </p>
        </div>
        <div className="research-launchboard-actions">
          <button type="button" className="primary-action" onClick={focusResearchQuestion}>
            <PenLine size={16} aria-hidden="true" />
            {hasQuestion ? "检查研究问题" : "填写研究问题"}
            <ArrowRight size={15} aria-hidden="true" />
          </button>
          <button type="button" className="secondary-button" onClick={() => void onOpenSourceLibrary()}>
            <FolderOpen size={15} aria-hidden="true" />
            打开资料库
          </button>
        </div>
        {selectedSourceTitles.length > 0 ? (
          <div className="research-launchboard-scope" aria-label="当前已选资料">
            <small>当前范围</small>
            <div>
              {selectedSourceTitles.map((title) => <span key={title}>{title}</span>)}
              {selectedSourceCount > selectedSourceTitles.length ? <span>+{selectedSourceCount - selectedSourceTitles.length} 份</span> : null}
            </div>
          </div>
        ) : null}
      </div>

      <dl className="research-launchboard-context">
        <div>
          <dt>当前工作台</dt>
          <dd title={workspaceName}>{workspaceName}</dd>
          <small>资料和研究成果都绑定在这里</small>
        </div>
        <div>
          <dt>资料状态</dt>
          <dd>{readySourceCount} <small>/ {sourceCount} 已解析</small></dd>
          <small>{sourceNarrative}</small>
        </div>
        <div>
          <dt>本次检索</dt>
          <dd>{retrievalModeLabel}</dd>
          <small>{researchRetrievalMode === "SOURCES_ONLY" ? "只读取显式资料范围" : "按当前模式收集并核验证据"}</small>
        </div>
        <div>
          <dt>历史研究</dt>
          <dd>{researchHistoryCount} <small>个 run</small></dd>
          <small>{researchHistoryCount > 0 ? "可从左侧历史列表继续查看" : "完成第一次研究后会留在这里"}</small>
        </div>
      </dl>

      <div className="research-launchboard-flow" aria-label="Research 工作流">
        <div className={hasQuestion ? "is-ready" : "is-current"}>
          <span className="research-flow-index">01</span>
          <strong>定义问题</strong>
          <small>{hasQuestion ? "已填写，仍可从左侧调整" : "从左侧写下要验证的内容"}</small>
        </div>
        <div className={selectedSourceCount > 0 || researchRetrievalMode === "WEB_ONLY" ? "is-ready" : "is-current"}>
          <span className="research-flow-index">02</span>
          <strong>检索与核验</strong>
          <small>{selectedSourceCount > 0 ? `${selectedSourceCount} 份资料进入范围` : retrievalModeLabel}</small>
        </div>
        <div>
          <span className="research-flow-index">03</span>
          <strong>报告与证据</strong>
          <small>完成后可导出或写回资料库</small>
        </div>
      </div>
    </div>
  );
}
