import {
  buildArtifactHistoryVersionKey,
  type ArtifactHistoryItem
} from "./artifactHistory";
import {
  buildArtifactVersionAuditView,
  type ArtifactVersionAuditViewVersion
} from "./artifactVersionAudit";

export type ArtifactHistoryViewerVersion = ArtifactVersionAuditViewVersion & {
  artifact_job_id: string;
};

export type ArtifactHistoryViewerState = {
  activeKey: string;
  activeVersion: ArtifactHistoryViewerVersion | null;
  isLatestVersion: boolean;
  scopeLabel: string;
  detailToggleLabel: string;
  preview: string;
  runtimeSummary: ReturnType<typeof buildArtifactVersionAuditView>["runtimeSummary"];
  detailSections: ReturnType<typeof buildArtifactVersionAuditView>["detailSections"];
};

type BuildArtifactHistoryViewerStateParams = {
  historyItems: ArtifactHistoryItem[];
  latestVersion?: ArtifactHistoryViewerVersion | null;
  selectedVersion?: ArtifactHistoryViewerVersion | null;
  selectedKey?: string;
};

export function buildArtifactHistoryViewerState(
  params: BuildArtifactHistoryViewerStateParams
): ArtifactHistoryViewerState {
  const activeVersion = params.selectedVersion ?? params.latestVersion ?? null;
  if (!activeVersion) {
    return {
      activeKey: "",
      activeVersion: null,
      isLatestVersion: false,
      scopeLabel: "",
      detailToggleLabel: "",
      preview: "",
      runtimeSummary: [],
      detailSections: []
    };
  }

  const latestVersionKey = params.latestVersion
    ? buildArtifactHistoryVersionKey(params.latestVersion.artifact_job_id, params.latestVersion.version_no)
    : "";
  const activeKey = params.selectedKey?.trim()
    || buildArtifactHistoryVersionKey(activeVersion.artifact_job_id, activeVersion.version_no);
  const isLatestVersion = Boolean(params.latestVersion)
    && params.latestVersion?.artifact_job_id === activeVersion.artifact_job_id
    && params.latestVersion?.version_no === activeVersion.version_no;
  const normalizedActiveKey = isLatestVersion && latestVersionKey
    ? latestVersionKey
    : activeKey;
  const resolvedKey = normalizedActiveKey
    || params.historyItems.find((item) => item.artifactJobId === activeVersion.artifact_job_id && item.versionNo === activeVersion.version_no)?.key
    || "";
  const scopeLabel = isLatestVersion ? "最新版本" : "历史版本";
  const auditView = buildArtifactVersionAuditView({
    version: activeVersion,
    scopeLabel,
    detailToggleLabel: `查看${scopeLabel}审计详情`
  });

  return {
    activeKey: resolvedKey,
    activeVersion,
    isLatestVersion,
    scopeLabel: auditView.scopeLabel,
    detailToggleLabel: auditView.detailToggleLabel,
    preview: auditView.preview,
    runtimeSummary: auditView.runtimeSummary,
    detailSections: auditView.detailSections
  };
}
