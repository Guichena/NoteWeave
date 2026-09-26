import {
  BookOpenText,
  Brain,
  ChevronDown,
  FileInput,
  FlaskConical,
  Network,
  Wrench
} from "lucide-react";
import type { ArtifactRailProps } from "./ArtifactRailProps";

type ArtifactUtilityPanelProps = Pick<
  ArtifactRailProps,
  | "isBusy"
  | "workspace"
  | "openWikiHome"
  | "openResearchWorkbench"
  | "openMemoryWorkbench"
  | "toggleWikiEnabled"
  | "wikiEnabled"
  | "sourceDraftTitle"
  | "setSourceDraftTitle"
  | "sourceDraftContent"
  | "setSourceDraftContent"
  | "sourceDraftRewriteMode"
  | "rewriteNoteSourceDraft"
  | "saveNoteAnswerAsSource"
  | "lastNoteAssistantMessageId"
  | "wikiRebuildAdvice"
>;

export function ArtifactUtilityPanel(props: ArtifactUtilityPanelProps) {
  const {
    isBusy,
    workspace,
    openWikiHome,
    openResearchWorkbench,
    openMemoryWorkbench,
    toggleWikiEnabled,
    wikiEnabled,
    sourceDraftTitle,
    setSourceDraftTitle,
    sourceDraftContent,
    setSourceDraftContent,
    sourceDraftRewriteMode,
    rewriteNoteSourceDraft,
    saveNoteAnswerAsSource,
    lastNoteAssistantMessageId,
    wikiRebuildAdvice
  } = props;

  return (
    <details className="artifact-utility-section">
      <summary className="artifact-utility-summary">
        <span className="artifact-utility-heading">
          <Wrench size={17} aria-hidden="true" />
          <span>
            <strong>更多工具</strong>
            <small>研究、Wiki、记忆与笔记入库</small>
          </span>
        </span>
        <ChevronDown className="artifact-utility-chevron" size={17} aria-hidden="true" />
      </summary>

      <div className="artifact-utility-body">
        <div className="artifact-utility-actions">
          <button type="button" className="secondary-button" onClick={openWikiHome} disabled={isBusy || !workspace}>
            <BookOpenText size={16} aria-hidden="true" />
            <span>打开 Wiki 工作台</span>
          </button>
          <button type="button" className="secondary-button" onClick={openResearchWorkbench} disabled={isBusy || !workspace}>
            <FlaskConical size={16} aria-hidden="true" />
            <span>打开 Deep Research</span>
          </button>
          <button type="button" className="secondary-button" onClick={openMemoryWorkbench} disabled={isBusy || !workspace}>
            <Brain size={16} aria-hidden="true" />
            <span>打开 Memory 审核</span>
          </button>
          <button type="button" className="secondary-button" onClick={toggleWikiEnabled} disabled={isBusy || !workspace}>
            <Network size={16} aria-hidden="true" />
            <span>{wikiEnabled ? "关闭 Wiki 构建" : "开启 Wiki 构建"}</span>
          </button>
        </div>

        <details className="artifact-note-tool">
          <summary>
            <FileInput size={16} aria-hidden="true" />
            <span>Note 确认入库</span>
            <ChevronDown size={16} aria-hidden="true" />
          </summary>
          <div className="artifact-note-tool-body">
            <p className="phase-note">
              Note 模式的最新回答会先生成中性 Markdown 草稿，确认后才进入当前工作台资料库。
            </p>
            <label className="rail-field">
              <span>资料标题</span>
              <input
                value={sourceDraftTitle}
                onChange={(event) => setSourceDraftTitle(event.target.value)}
                placeholder="例如：阶段研究整理"
              />
            </label>
            <label className="rail-field">
              <span>中性 Markdown 正文</span>
              <textarea
                value={sourceDraftContent}
                onChange={(event) => setSourceDraftContent(event.target.value)}
                rows={7}
                placeholder="Note 回答完成后会生成中性草稿，也可在这里调整"
              />
            </label>
            {sourceDraftRewriteMode ? (
              <p className="phase-note">当前草稿来源：{sourceDraftRewriteMode}</p>
            ) : null}
            <div className="artifact-note-actions">
              <button
                type="button"
                className="secondary-button"
                onClick={() => void rewriteNoteSourceDraft()}
                disabled={isBusy || !workspace || !lastNoteAssistantMessageId}
              >
                生成中性草稿
              </button>
              <button
                type="button"
                className="primary-action"
                onClick={() => void saveNoteAnswerAsSource()}
                disabled={isBusy || !workspace || !lastNoteAssistantMessageId}
              >
                保存最新回答为 Note
              </button>
            </div>
          </div>
        </details>
        {wikiRebuildAdvice ? <p className="phase-note">{wikiRebuildAdvice.message}</p> : null}
      </div>
    </details>
  );
}
