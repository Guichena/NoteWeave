import type { ArtifactRailProps } from "./ArtifactRailProps";
import type {
  ArtifactVersionAuditView,
  ArtifactVersionAuditViewVersion
} from "./artifactVersionAudit";
import { ArtifactMindMapPreview, isMindMapArtifact } from "./ArtifactMindMapPreview";
import { ArtifactSlidePreview } from "./ArtifactSlidePreview";
import type { ArtifactFileMetadata } from "./model";

type ArtifactVersionActionTarget = ArtifactVersionAuditViewVersion & {
  artifact_job_id: string;
};

function downloadableFile(file: NonNullable<ArtifactVersionAuditViewVersion["files"]>[number]): file is ArtifactFileMetadata {
  return file.status === "READY" && "file_id" in file && "file_name" in file;
}

type ArtifactVersionActionsProps = Pick<
  ArtifactRailProps,
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
  | "downloadArtifactVersionFile"
> & {
  version: ArtifactVersionActionTarget;
  auditView: Pick<
    ArtifactVersionAuditView,
    "preview" | "scopeLabel" | "detailToggleLabel" | "runtimeSummary" | "detailSections"
  >;
  detailsOpen?: boolean;
  workspaceId?: string;
};

export function ArtifactVersionActions({
  version,
  auditView,
  workspaceId = "",
  detailsOpen = false,
  isBusy,
  formatRelativeTime,
  saveArtifactVersionAsSource,
  artifactSavedSourceByVersionId,
  artifactVersionSaveKey,
  writeArtifactVersionToKnowledge,
  artifactWritebackByVersionId,
  regenerateArtifactVersion,
  compareArtifactWithPreviousVersion,
  rollbackArtifactVersion,
  downloadArtifactVersionPdf,
  downloadArtifactVersionFile
}: ArtifactVersionActionsProps) {
  const saveKey = artifactVersionSaveKey(version);
  const canCompare = version.version_no > 1;

  return (
    <>
      <span>{auditView.preview || "已生成产物版本，可继续查看或用于后续沉淀。"}</span>
      <small>
        {auditView.scopeLabel} · Skill {version.skill_key} · v{version.version_no} · {formatRelativeTime(version.created_at)}
      </small>
      {isMindMapArtifact(version.skill_key) ? (
        <ArtifactMindMapPreview
          title={version.title || "工作台知识导图"}
          markdown={version.content_markdown}
        />
      ) : null}
      {version.files && version.files.length > 0 ? (
        <small>
          文件状态 {version.files.map((file) => `${file.file_format} · ${file.status} · ${file.storage_backend} · ${file.size_bytes}B`).join(" / ")}
        </small>
      ) : null}
      <button
        className="secondary-button"
        onClick={() => void saveArtifactVersionAsSource(version)}
        disabled={isBusy}
      >
        {artifactSavedSourceByVersionId[saveKey] ? "已保存为资料" : "保存为资料"}
      </button>
      <button
        className="secondary-button"
        onClick={() => void writeArtifactVersionToKnowledge(version, "NOTE")}
        disabled={isBusy}
      >
        {artifactWritebackByVersionId[saveKey]?.includes("NOTE") ? "已写入 Note" : "写入 Note"}
      </button>
      <button
        className="secondary-button"
        onClick={() => void writeArtifactVersionToKnowledge(version, "WIKI")}
        disabled={isBusy}
      >
        {artifactWritebackByVersionId[saveKey]?.includes("WIKI") ? "已写入 Wiki" : "写入 Wiki"}
      </button>
      <button className="secondary-button" onClick={() => void regenerateArtifactVersion(version)} disabled={isBusy}>
        再生成
      </button>
      <button className="secondary-button" onClick={() => void compareArtifactWithPreviousVersion(version)} disabled={isBusy || !canCompare}>
        与上一版比较
      </button>
      <button className="secondary-button" onClick={() => void rollbackArtifactVersion(version)} disabled={isBusy}>
        追加式回滚
      </button>
      {version.runtime_trace?.export_trace?.status === "COMPILED" ? (
        <button className="secondary-button" onClick={() => void downloadArtifactVersionPdf(version)}>
          下载 PDF
        </button>
      ) : null}
      {version.files?.filter(downloadableFile).map((file) => (
        <button key={file.file_id} className="secondary-button"
          onClick={() => void downloadArtifactVersionFile(version, file)} disabled={isBusy}>
          下载 {file.file_format}{file.file_format === "PNG" ? ` · ${file.file_name}` : ""}
        </button>
      ))}
      {version.skill_key === "video_learning_deck" && workspaceId && version.files ? (
        <ArtifactSlidePreview workspaceId={workspaceId}
          artifactJobId={version.artifact_job_id} versionNo={version.version_no}
          files={version.files.filter(downloadableFile)} />
      ) : null}
      {auditView.runtimeSummary.length > 0 ? (
        <div className="artifact-runtime-trace">
          {auditView.runtimeSummary.map((item) => (
            <small key={`${auditView.scopeLabel}-${item.label}`} className="artifact-runtime-trace-line">
              <strong>{item.label}</strong> · {item.value}
            </small>
          ))}
        </div>
      ) : null}
      {auditView.detailSections.length > 0 ? (
        <details className="artifact-runtime-detail-panel" open={detailsOpen}>
          <summary className="artifact-runtime-detail-toggle">{auditView.detailToggleLabel}</summary>
          <div className="artifact-runtime-detail-grid">
            {auditView.detailSections.map((section) => (
              <section key={`${auditView.scopeLabel}-${section.title}`} className="artifact-runtime-detail-section">
                <strong>{section.title}</strong>
                {section.lines.map((line) => (
                  <small key={`${auditView.scopeLabel}-${section.title}-${line}`}>{line}</small>
                ))}
              </section>
            ))}
          </div>
        </details>
      ) : null}
    </>
  );
}
