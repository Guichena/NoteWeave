import { ArrowLeft, Layers3, LibraryBig, LoaderCircle, X } from "lucide-react";
import { ArtifactStudioActivity } from "./ArtifactStudioActivity";
import { ArtifactStudioGrid } from "./ArtifactStudioGrid";
import { ArtifactUtilityPanel } from "./ArtifactUtilityPanel";
import { VideoLearningPanel } from "./VideoLearningPanel";
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

  return (
    <aside className="artifact-rail" aria-label="产物工作台">
      {artifactComposerOpen && selectedArtifactSkill ? (
        <div className="artifact-composer-view">
          <header className="artifact-composer-header">
            <button
              type="button"
              className="artifact-icon-button secondary-button"
              disabled={isBusy}
              onClick={() => setArtifactComposerOpen(false)}
              aria-label="返回 Studio"
              title="返回 Studio"
            >
              <ArrowLeft size={18} aria-hidden="true" />
            </button>
            <div>
              <p className="section-label">创建产物</p>
              <h2>{selectedArtifactSkill.title}</h2>
            </div>
            {onCloseArtifactRail ? (
              <button
                type="button"
                className="artifact-icon-button secondary-button"
                onClick={onCloseArtifactRail}
                aria-label="关闭产物工作台"
                title="关闭产物工作台"
              >
                <X size={18} aria-hidden="true" />
              </button>
            ) : null}
          </header>

          <div className="artifact-source-context">
            <LibraryBig size={17} aria-hidden="true" />
            <span>
              <small>来源范围</small>
              <strong>{workspace?.name || "未选择工作台"} · {sourceCount} 份资料</strong>
            </span>
          </div>

          <div className="artifact-form-fields">
            {selectedArtifactSkill.inputFields.map((field) => renderArtifactField(field))}

            <label className="rail-field">
              <span>补充生成要求</span>
              <textarea
                value={artifactCustomInstruction}
                onChange={(event) => setArtifactCustomInstruction(event.target.value)}
                rows={6}
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
                  {hint}
                </button>
              ))}
            </div>
          ) : null}

          <div className="artifact-panel-actions">
            <button
              type="button"
              className="secondary-button"
              disabled={isBusy || !workspace}
              onClick={() => prepareArtifactPrompt(selectedArtifactSkill)}
            >
              填入对话
            </button>
            <button
              type="button"
              disabled={isBusy || !workspace || !isArtifactFormReady(selectedArtifactSkill, artifactFormValues)}
              onClick={() => void launchArtifactPrompt(selectedArtifactSkill)}
            >
              生成产物
            </button>
          </div>
          {artifactComposerError ? (
            <div className="artifact-composer-error" role="alert">
              <strong>未能创建产物</strong>
              <span>{artifactComposerError}</span>
            </div>
          ) : null}
        </div>
      ) : (
        <>
          <header className="artifact-studio-header">
            <span className="artifact-studio-mark" aria-hidden="true"><Layers3 size={19} /></span>
            <div>
              <p className="section-label">Workspace Studio</p>
              <h2>产物工作台</h2>
              <p>{sourceCount} 份资料 · {artifactStudioSkills.length} 种产物</p>
            </div>
            {onCloseArtifactRail ? (
              <button
                type="button"
                className="artifact-icon-button secondary-button"
                onClick={onCloseArtifactRail}
                aria-label="关闭产物工作台"
                title="关闭产物工作台"
              >
                <X size={18} aria-hidden="true" />
              </button>
            ) : null}
          </header>

          {artifactSkillsLoading ? (
            <div className="artifact-pending-state artifact-catalog-pending" role="status">
              <LoaderCircle className="is-spinning" size={17} aria-hidden="true" />
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
            <p className="artifact-quiet-state">Skill 目录当前不可用，无法创建产物任务。</p>
          ) : null}

          {workspace ? (
            <VideoLearningPanel workspaceId={workspace.workspace_id}
              onJobsChanged={props.refreshArtifactJobs} />
          ) : null}

          <ArtifactStudioActivity {...props} />
          <ArtifactUtilityPanel {...props} />
        </>
      )}
    </aside>
  );
}
