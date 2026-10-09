import { ChevronDown, FileInput } from "lucide-react";
import type { ArtifactRailProps } from "./ArtifactRailProps";

type NoteSourceDraftToolProps = Pick<
  ArtifactRailProps,
  | "isBusy"
  | "workspace"
  | "sourceDraftTitle"
  | "setSourceDraftTitle"
  | "sourceDraftContent"
  | "setSourceDraftContent"
  | "sourceDraftRewriteMode"
  | "rewriteNoteSourceDraft"
  | "saveNoteAnswerAsSource"
>;

/** 把最新的精读回答整理成中性草稿，确认后存入资料库。只在有精读回答时出现。 */
export function NoteSourceDraftTool(props: NoteSourceDraftToolProps) {
  const disabled = props.isBusy || !props.workspace;
  return (
    <details className="artifact-note-tool">
      <summary>
        <FileInput size={16} aria-hidden="true" />
        <span>
          <strong>把精读回答存为资料</strong>
          <small>先生成中性草稿，确认后进入资料库</small>
        </span>
        <ChevronDown size={16} aria-hidden="true" />
      </summary>
      <div className="artifact-note-tool-body">
        <label className="rail-field">
          <span>资料标题</span>
          <input
            value={props.sourceDraftTitle}
            onChange={(event) => props.setSourceDraftTitle(event.target.value)}
            placeholder="例如：阶段研究整理"
          />
        </label>
        <label className="rail-field">
          <span>正文</span>
          <textarea
            value={props.sourceDraftContent}
            onChange={(event) => props.setSourceDraftContent(event.target.value)}
            rows={7}
            placeholder="生成中性草稿后可以在这里调整"
          />
        </label>
        {props.sourceDraftRewriteMode ? (
          <p className="phase-note">草稿来源：{props.sourceDraftRewriteMode}</p>
        ) : null}
        <div className="artifact-note-actions">
          <button type="button" className="secondary-button" disabled={disabled}
            onClick={() => void props.rewriteNoteSourceDraft()}>
            生成中性草稿
          </button>
          <button type="button" className="primary-action" disabled={disabled}
            onClick={() => void props.saveNoteAnswerAsSource()}>
            保存为资料
          </button>
        </div>
      </div>
    </details>
  );
}
