import { Fragment } from "react";
import { ArrowLeft, LoaderCircle, Sparkles, X } from "lucide-react";
import { ArtifactStudioActivity } from "./ArtifactStudioActivity";
import { ArtifactStudioGrid } from "./ArtifactStudioGrid";
import { ArtifactUtilityPanel } from "./ArtifactUtilityPanel";
import { buildInitialArtifactFormValues } from "./artifactStudio";
import { type ArtifactRailProps } from "./ArtifactRailProps";

export type { ArtifactRailProps };

export function ArtifactRail(props: ArtifactRailProps) {
  const {
    artifactComposerOpen,
    isBusy,
    setArtifactComposerOpen,
    selectedArtifactSkill,
    renderArtifactField,
    artifactCustomInstruction,
    setArtifactCustomInstruction,
    appendArtifactHint,
    workspace,
    prepareArtifactPrompt,
    isArtifactFormReady,
    artifactFormValues,
    launchArtifactPrompt,
    artifactComposerError = "",
    clearArtifactComposerError = () => undefined,
    artifactStudioSkills,
    artifactSkillsLoading,
    sourceCount,
    setSelectedArtifactSkillKey,
    setArtifactFormValues,
    onCloseArtifactRail
  } = props;
  const instructionExample = selectedArtifactSkill?.summary.replace(/[。；;.!！？?]+$/u, "") || "说明希望重点关注的信息";

  const closeButton = onCloseArtifactRail ? (
    <button
      type="button"
      className="icon-button pane-close"
      onClick={onCloseArtifactRail}
      aria-label="关闭产物工作台"
      title="关闭"
    >
      <X size={17} aria-hidden="true" />
    </button>
  ) : null;

  return (
    <div className="artifact-rail">
      {artifactComposerOpen && selectedArtifactSkill ? (
        <div className="artifact-composer-view">
          <header className="pane-header artifact-composer-header">
            <button
              type="button"
              className="icon-button"
              disabled={isBusy}
              onClick={() => setArtifactComposerOpen(false)}
              aria-label="返回 Studio"
              title="返回"
            >
              <ArrowLeft size={17} aria-hidden="true" />
            </button>
            <h2 className="pane-title">{selectedArtifactSkill.title}</h2>
            <div className="pane-header-actions">{closeButton}</div>
          </header>

          <div className="pane-body artifact-composer-body">
            <p className="artifact-composer-summary">{selectedArtifactSkill.summary}</p>
            <p className="artifact-source-context">
              基于 <strong>{sourceCount}</strong> 个已解析来源 · {workspace?.name || "未选择工作台"}
            </p>

            <div className="artifact-form-fields">
              {selectedArtifactSkill.inputFields.map((field) => <Fragment key={field.key}>{renderArtifactField(field)}</Fragment>)}

              <label className="rail-field">
                <span>补充要求</span>
                <textarea
                  value={artifactCustomInstruction}
                  onChange={(event) => setArtifactCustomInstruction(event.target.value)}
                  rows={5}
                  placeholder={`例如：${instructionExample}。重点关注哪些信息、希望采用什么语气、需要什么结构。`}
                />
              </label>
            </div>

            {selectedArtifactSkill.defaultInputHints.length > 0 ? (
              <div className="artifact-hint-strip" aria-label="生成要求建议">
                {selectedArtifactSkill.defaultInputHints.map((hint) => (
                  <button
                    key={hint}
                    type="button"
                    className="artifact-hint-chip"
                    disabled={isBusy}
                    onClick={() => appendArtifactHint(hint)}
                  >
                    + {hint}
                  </button>
                ))}
              </div>
            ) : null}

            {artifactComposerError ? (
              <div className="artifact-composer-error" role="alert">
                <strong>未能创建产物</strong>
                <span>{artifactComposerError}</span>
              </div>
            ) : null}
          </div>

          <footer className="artifact-panel-actions">
            <button
              type="button"
              className="ghost-button"
              disabled={isBusy || !workspace}
              onClick={() => prepareArtifactPrompt(selectedArtifactSkill)}
              title="把生成要求填入对话输入框，由对话来完成"
            >
              填入对话
            </button>
            <button
              type="button"
              className="primary-action"
              disabled={isBusy || !workspace || !isArtifactFormReady(selectedArtifactSkill, artifactFormValues)}
              onClick={() => void launchArtifactPrompt(selectedArtifactSkill)}
            >
              <Sparkles size={15} aria-hidden="true" />
              生成产物
            </button>
          </footer>
        </div>
      ) : (
        <>
          <header className="pane-header artifact-studio-header">
            <h2 className="pane-title">产物</h2>
            <span className="pane-subtitle">{sourceCount} 个来源</span>
            <div className="pane-header-actions">{closeButton}</div>
          </header>

          <div className="pane-body artifact-studio-body">
            {artifactSkillsLoading ? (
              <div className="artifact-pending-state artifact-catalog-pending" role="status">
                <LoaderCircle className="is-spinning" size={16} aria-hidden="true" />
                <span>正在加载产物类型</span>
              </div>
            ) : (
              <ArtifactStudioGrid
                skills={artifactStudioSkills}
                isBusy={isBusy}
                onSelectSkill={(skill) => {
                  clearArtifactComposerError();
                  setSelectedArtifactSkillKey(skill.key);
                  setArtifactFormValues((current) => buildInitialArtifactFormValues(skill, current));
                  setArtifactComposerOpen(true);
                }}
              />
            )}
            {!artifactSkillsLoading && artifactStudioSkills.length === 0 ? (
              <p className="artifact-quiet-state">产物目录暂不可用，无法创建产物任务。</p>
            ) : null}

            <ArtifactStudioActivity {...props} />
            <ArtifactUtilityPanel {...props} />
          </div>
        </>
      )}
    </div>
  );
}
