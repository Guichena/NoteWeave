import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { ArrowLeft, LoaderCircle, Sparkles, X } from "lucide-react";
import { ArtifactOutputList } from "./ArtifactOutputList";
import { ArtifactReader } from "./ArtifactReader";
import { ArtifactStudioGrid } from "./ArtifactStudioGrid";
import { NoteSourceDraftTool } from "./NoteSourceDraftTool";
import { VideoLearningPanel } from "./VideoLearningPanel";
import { buildArtifactHistoryVersionKey } from "./artifactHistory";
import { buildArtifactOutputItems, type ArtifactOutputItem } from "./artifactOutputs";
import { buildInitialArtifactFormValues } from "./artifactStudio";
import { type ArtifactRailProps } from "./ArtifactRailProps";

export type { ArtifactRailProps };

type ReaderTarget = {
  artifactJobId: string;
  /** 为空时跟随最新版本，例如回滚或重新生成之后。 */
  versionNo: number | null;
};

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
  const [readerTarget, setReaderTarget] = useState<ReaderTarget | null>(null);
  const outputItems = useMemo(
    () => buildArtifactOutputItems(props.artifactJobs, props.resolveArtifactSkillTitle),
    [props.artifactJobs, props.resolveArtifactSkillTitle]
  );
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

  if (artifactComposerOpen && selectedArtifactSkill) {
    return (
      <div className="artifact-rail">
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
      </div>
    );
  }

  if (readerTarget && workspace) {
    return (
      <div className="artifact-rail">
        <ArtifactReaderContainer
          {...props}
          workspaceId={workspace.workspace_id}
          target={readerTarget}
          items={outputItems}
          onChangeTarget={setReaderTarget}
          onBack={() => setReaderTarget(null)}
        />
      </div>
    );
  }

  return (
    <div className="artifact-rail">
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
        ) : artifactStudioSkills.length === 0 ? (
          <p className="artifact-quiet-state">产物目录暂不可用，无法创建产物任务。</p>
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

        {workspace ? (
          <VideoLearningPanel
            workspaceId={workspace.workspace_id}
            onJobsChanged={props.refreshArtifactJobs}
            onOpenVersion={(artifactJobId, versionNo) => setReaderTarget({ artifactJobId, versionNo })}
          />
        ) : null}

        <ArtifactOutputList
          items={outputItems}
          loading={props.artifactJobsLoading}
          formatRelativeTime={props.formatRelativeTime}
          onOpen={(item) => setReaderTarget({ artifactJobId: item.artifactJobId, versionNo: null })}
        />

        {props.lastNoteAssistantMessageId ? <NoteSourceDraftTool {...props} /> : null}
      </div>
    </div>
  );
}

/** 解析阅读目标对应的版本详情，缺失时按需加载。 */
function ArtifactReaderContainer(props: ArtifactRailProps & {
  workspaceId: string;
  target: ReaderTarget;
  items: ArtifactOutputItem[];
  onChangeTarget: (target: ReaderTarget) => void;
  onBack: () => void;
}) {
  const { target, items, latestArtifactVersion, selectedArtifactHistoryVersion } = props;
  const listed = items.find((entry) => entry.artifactJobId === target.artifactJobId);
  const versionNo = target.versionNo ?? listed?.versionNo ?? 0;
  const versionKey = buildArtifactHistoryVersionKey(target.artifactJobId, versionNo);
  const version = [latestArtifactVersion, selectedArtifactHistoryVersion].find((candidate) => (
    candidate?.artifact_job_id === target.artifactJobId && candidate.version_no === versionNo
  )) ?? null;
  const item = listed ?? buildPlaceholderItem(target, version, props.resolveArtifactSkillTitle);
  const openVersionRef = useRef(props.openArtifactHistoryVersion);
  openVersionRef.current = props.openArtifactHistoryVersion;

  useEffect(() => {
    if (version || versionNo <= 0) return;
    void openVersionRef.current({
      key: versionKey,
      artifactJobId: item.artifactJobId,
      versionNo,
      skillKey: item.skillKey,
      title: item.title,
      detail: "",
      updatedAt: item.updatedAt,
      status: ""
    });
    // 只在目标版本变化或详情缺失时加载，避免回调引用变化导致重复请求。
  }, [versionKey, version === null]);

  return (
    <ArtifactReader
      {...props}
      item={item}
      versionNo={versionNo}
      version={version}
      loading={props.artifactHistoryLoadingKey === versionKey}
      onClose={props.onCloseArtifactRail}
      onSelectVersion={(next) => props.onChangeTarget({
        artifactJobId: item.artifactJobId,
        versionNo: next === item.versionNo ? null : next
      })}
      onRolledBack={() => props.onChangeTarget({ artifactJobId: item.artifactJobId, versionNo: null })}
    />
  );
}

// 视频学习的子产物可能还没出现在任务列表里，先用目标和版本详情占位。
function buildPlaceholderItem(
  target: ReaderTarget,
  version: ArtifactRailProps["latestArtifactVersion"],
  resolveSkillTitle: (skillKey: string) => string
): ArtifactOutputItem {
  const typeLabel = version ? resolveSkillTitle(version.skill_key) : "";
  return {
    key: `artifact-job-${target.artifactJobId}`,
    artifactJobId: target.artifactJobId,
    skillKey: version?.skill_key ?? "",
    title: version?.title || typeLabel || "产物",
    typeLabel,
    versionNo: target.versionNo ?? version?.version_no ?? 0,
    state: "ready",
    statusLabel: "",
    phaseIndex: -1,
    detail: "",
    technicalDetail: "",
    updatedAt: ""
  };
}
