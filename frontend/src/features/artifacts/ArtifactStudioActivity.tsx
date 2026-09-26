import {
  CircleCheck,
  Clock3,
  FileClock,
  LoaderCircle,
  Search,
  TriangleAlert
} from "lucide-react";
import { type ArtifactRailProps } from "./ArtifactRailProps";
import { ArtifactVersionActions } from "./ArtifactVersionActions";
import { type ArtifactSidebarRunCard } from "./artifactSidebar";

type ArtifactStudioActivityProps = Pick<
  ArtifactRailProps,
  | "artifactSidebarState"
  | "latestArtifactVersion"
  | "artifactJobsLoading"
  | "isBusy"
  | "formatRelativeTime"
  | "saveArtifactVersionAsSource"
  | "artifactSavedSourceByVersionId"
  | "artifactVersionSaveKey"
  | "writeArtifactVersionToKnowledge"
  | "artifactWritebackByVersionId"
  | "regenerateArtifactVersion"
  | "compareArtifactWithPreviousVersion"
  | "rollbackArtifactVersion"
  | "downloadArtifactVersionPdf"
  | "resolveArtifactSkillTitle"
  | "summarizeRunStatus"
  | "openArtifactHistoryVersion"
  | "artifactHistoryLoadingKey"
> & {
  artifactStudioSkills: ArtifactRailProps["artifactStudioSkills"];
};

export function ArtifactStudioActivity(props: ArtifactStudioActivityProps) {
  const {
    artifactSidebarState,
    latestArtifactVersion,
    artifactJobsLoading,
    isBusy,
    formatRelativeTime,
    resolveArtifactSkillTitle,
    summarizeRunStatus,
    openArtifactHistoryVersion,
    artifactHistoryLoadingKey
  } = props;
  const activeRuns = artifactSidebarState.runs.filter((run) => run.group === "active");
  const recentRuns = artifactSidebarState.runs.filter((run) => run.group === "recent");
  const otherHistoryItems = artifactSidebarState.historyItems.filter((item) => (
    !latestArtifactVersion
    || item.artifactJobId !== latestArtifactVersion.artifact_job_id
    || item.versionNo !== latestArtifactVersion.version_no
  ));
  const activeHistoryVersion = artifactSidebarState.historyViewer.activeVersion;
  const historyDetailVersion = activeHistoryVersion && (
    !latestArtifactVersion
    || activeHistoryVersion.artifact_job_id !== latestArtifactVersion.artifact_job_id
    || activeHistoryVersion.version_no !== latestArtifactVersion.version_no
  )
    ? activeHistoryVersion
    : null;
  const hasRecords = Boolean(latestArtifactVersion) || artifactSidebarState.historyItems.length > 0;

  return (
    <>
      <section className="artifact-run-section" aria-labelledby="artifact-active-heading">
        <div className="artifact-section-heading">
          <h3 className="section-label" id="artifact-active-heading">正在运行</h3>
          {activeRuns.length > 0 ? <span>{activeRuns.length}</span> : null}
        </div>
        {activeRuns.length > 0 ? activeRuns.map((run) => (
          <ArtifactRunCard key={run.key} run={run} />
        )) : artifactJobsLoading ? (
          <ArtifactPendingState label="正在同步运行状态" />
        ) : (
          <p className="artifact-quiet-state">当前没有运行中的任务。</p>
        )}
      </section>

      {recentRuns.length > 0 ? (
        <section className="artifact-run-section" aria-labelledby="artifact-recent-heading">
          <div className="artifact-section-heading">
            <h3 className="section-label" id="artifact-recent-heading">最近运行</h3>
            <span>{recentRuns.length}</span>
          </div>
          {recentRuns.map((run) => (
            <ArtifactRunCard key={run.key} run={run} />
          ))}
        </section>
      ) : null}

      <section className="artifact-history-section" aria-labelledby="artifact-record-heading">
        <div className="artifact-section-heading">
          <h3 className="section-label" id="artifact-record-heading">产物记录</h3>
          {hasRecords ? <span>{artifactSidebarState.historyItems.length}</span> : null}
        </div>
        {artifactJobsLoading && !hasRecords ? (
          <ArtifactPendingState label="正在加载产物记录" />
        ) : latestArtifactVersion ? (
          <div className="artifact-run-card artifact-version-card tone-stable">
            <div className="artifact-run-row">
              <span className="artifact-run-icon" aria-hidden="true"><FileClock size={17} /></span>
              <div>
                <small className="artifact-record-kicker">最新版本</small>
                <strong>{latestArtifactVersion.title || resolveArtifactSkillTitle(latestArtifactVersion.skill_key)}</strong>
                <ArtifactVersionActions
                  version={latestArtifactVersion}
                  auditView={artifactSidebarState.latestAuditView}
                  isBusy={isBusy}
                  formatRelativeTime={formatRelativeTime}
                  saveArtifactVersionAsSource={props.saveArtifactVersionAsSource}
                  artifactSavedSourceByVersionId={props.artifactSavedSourceByVersionId}
                  artifactVersionSaveKey={props.artifactVersionSaveKey}
                  writeArtifactVersionToKnowledge={props.writeArtifactVersionToKnowledge}
                  artifactWritebackByVersionId={props.artifactWritebackByVersionId}
                  regenerateArtifactVersion={props.regenerateArtifactVersion}
                  compareArtifactWithPreviousVersion={props.compareArtifactWithPreviousVersion}
                  rollbackArtifactVersion={props.rollbackArtifactVersion}
                  downloadArtifactVersionPdf={props.downloadArtifactVersionPdf}
                />
              </div>
            </div>
          </div>
        ) : (
          <p className="artifact-quiet-state">完成一次生成后，版本会保存在这里。</p>
        )}

        {otherHistoryItems.map((item) => (
          <div
            key={item.key}
            className={`artifact-history-item${artifactSidebarState.historyViewer.activeKey === item.key ? " active" : ""}`}
          >
            <div className="artifact-history-icon" aria-hidden="true"><FileClock size={16} /></div>
            <div className="artifact-history-copy">
              <strong>{item.title || resolveArtifactSkillTitle(item.skillKey, props.artifactStudioSkills)}</strong>
              <small>
                v{item.versionNo} · {formatRelativeTime(item.updatedAt)} · {summarizeRunStatus(item.status)}
              </small>
            </div>
            <button
              className="artifact-history-more secondary-button"
              onClick={() => void openArtifactHistoryVersion(item)}
              disabled={isBusy || artifactHistoryLoadingKey === item.key}
              aria-label={`审计产物版本 ${item.title || item.versionNo}`}
            >
              {artifactHistoryLoadingKey === item.key
                ? <LoaderCircle className="is-spinning" size={16} aria-hidden="true" />
                : <Search size={16} aria-hidden="true" />}
              <span>{artifactHistoryLoadingKey === item.key ? "加载中" : "审计"}</span>
            </button>
          </div>
        ))}

        {historyDetailVersion ? (
          <div className="artifact-run-card tone-stable artifact-history-detail-card">
            <div className="artifact-run-row">
              <span className="artifact-run-icon" aria-hidden="true"><FileClock size={17} /></span>
              <div>
                <strong>
                  {historyDetailVersion.title
                    || resolveArtifactSkillTitle(historyDetailVersion.skill_key, props.artifactStudioSkills)}
                </strong>
                <ArtifactVersionActions
                  version={historyDetailVersion}
                  auditView={artifactSidebarState.historyViewer}
                  detailsOpen
                  isBusy={isBusy}
                  formatRelativeTime={formatRelativeTime}
                  saveArtifactVersionAsSource={props.saveArtifactVersionAsSource}
                  artifactSavedSourceByVersionId={props.artifactSavedSourceByVersionId}
                  artifactVersionSaveKey={props.artifactVersionSaveKey}
                  writeArtifactVersionToKnowledge={props.writeArtifactVersionToKnowledge}
                  artifactWritebackByVersionId={props.artifactWritebackByVersionId}
                  regenerateArtifactVersion={props.regenerateArtifactVersion}
                  compareArtifactWithPreviousVersion={props.compareArtifactWithPreviousVersion}
                  rollbackArtifactVersion={props.rollbackArtifactVersion}
                  downloadArtifactVersionPdf={props.downloadArtifactVersionPdf}
                />
              </div>
            </div>
          </div>
        ) : null}
      </section>
    </>
  );
}

function ArtifactRunCard({ run }: { run: ArtifactSidebarRunCard }) {
  const RunIcon = run.tone === "danger"
    ? TriangleAlert
    : run.tone === "stable"
      ? CircleCheck
      : Clock3;
  const hasRuntimeDetails = run.waitSignals.length > 0 || run.waitDetails.length > 0;

  return (
    <div className={`artifact-run-card tone-${run.tone}`} data-run-key={run.key}>
      <div className="artifact-run-row">
        <span className="artifact-run-icon" aria-hidden="true"><RunIcon size={17} /></span>
        <div className="artifact-run-copy">
          <span className="artifact-run-title-row">
            <strong>{run.title}</strong>
            <small className="artifact-status-label">{run.status}</small>
          </span>
          <span className="artifact-run-detail">{run.detail}</span>
          <small>{run.meta}</small>
          {hasRuntimeDetails ? (
            <details className="artifact-run-details">
              <summary>运行详情</summary>
              {run.waitSignals.length > 0 ? (
                <div className="signal-chip-row artifact-wait-signal-row">
                  {run.waitSignals.map((chip) => (
                    <span key={`${run.key}-${chip.label}-${chip.value}`} className={`signal-chip tone-${chip.tone}`}>
                      {chip.label}: {chip.value}
                    </span>
                  ))}
                </div>
              ) : null}
              {run.waitDetails.length > 0 ? (
                <div className="artifact-runtime-trace">
                  {run.waitDetails.map((line) => (
                    <small key={`${run.key}-${line.label}-${line.value}`} className="artifact-runtime-trace-line">
                      <strong>{line.label}</strong> · {line.value}
                    </small>
                  ))}
                </div>
              ) : null}
            </details>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function ArtifactPendingState({ label }: { label: string }) {
  return (
    <div className="artifact-pending-state" role="status">
      <LoaderCircle className="is-spinning" size={16} aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}
