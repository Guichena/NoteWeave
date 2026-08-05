import {
  buildArtifactHistoryItems,
  type ArtifactHistoryItem,
  type ArtifactHistoryJobSummary
} from "./artifactHistory";
import {
  buildArtifactHistoryViewerState,
  type ArtifactHistoryViewerState,
  type ArtifactHistoryViewerVersion
} from "./artifactHistoryViewer";
import {
  buildArtifactVersionAuditView,
  type ArtifactVersionAuditView,
  type ArtifactVersionAuditViewVersion
} from "./artifactVersionAudit";
import {
  buildWaitContextDetailLines,
  buildWaitContextNarrative,
  buildWaitContextSignalChips,
  resolveRunStatus,
  resolveRunTone,
  summarizeRunStatus,
  type RunTone,
  type WaitContext,
  type WaitContextDetailLine,
  type WaitContextSignalChip
} from "../../runStatus";

export type ArtifactSidebarRunCard = {
  key: string;
  title: string;
  status: string;
  detail: string;
  meta: string;
  waitSignals: WaitContextSignalChip[];
  waitDetails: WaitContextDetailLine[];
  tone: RunTone;
};

export type ArtifactSidebarResearchRunSummary = {
  research_run_id: string;
  question: string;
  status: string;
  final_report_title: string;
  source_scope_count: number;
  wait_context?: WaitContext | null;
  updated_at: string;
};

export type ArtifactSidebarWorkspaceTask = {
  task_id: string;
  task_type: string;
  task_status: string;
  progress_phase: string;
  progress_message: string;
  wait_context?: WaitContext | null;
};

export type ArtifactSidebarWikiState = {
  enabled: boolean;
  pageCount: number;
  pendingTaskCount: number;
  updatedAt: string;
};

export type ArtifactSidebarLatestVersion = ArtifactVersionAuditViewVersion & {
  artifact_job_id: string;
};

export type ArtifactSidebarState = {
  runs: ArtifactSidebarRunCard[];
  historyItems: ArtifactHistoryItem[];
  latestAuditView: ArtifactVersionAuditView;
  historyViewer: ArtifactHistoryViewerState;
};

type BuildArtifactSidebarStateParams = {
  artifactJobs: ArtifactHistoryJobSummary[];
  latestArtifactVersion?: ArtifactSidebarLatestVersion | null;
  selectedArtifactHistoryVersion?: ArtifactHistoryViewerVersion | null;
  selectedArtifactHistoryKey?: string;
  currentResearchRunSummary?: ArtifactSidebarResearchRunSummary | null;
  latestTask?: ArtifactSidebarWorkspaceTask | null;
  workspaceReady: boolean;
  wikiState?: ArtifactSidebarWikiState | null;
  sourcesCount: number;
  resolveArtifactSkillTitle: (skillKey: string) => string;
  formatRelativeTime: (value: string) => string;
};

export function buildArtifactSidebarState(
  params: BuildArtifactSidebarStateParams
): ArtifactSidebarState {
  const historyItems = buildArtifactHistoryItems(params.artifactJobs);
  const latestArtifactJob = params.artifactJobs[0] ?? null;
  const latestAuditView = buildArtifactVersionAuditView({
    version: params.latestArtifactVersion ?? null,
    scopeLabel: "最新版本",
    detailToggleLabel: "查看最新版本审计详情"
  });
  const historyViewer = buildArtifactHistoryViewerState({
    historyItems,
    latestVersion: params.latestArtifactVersion ?? null,
    selectedVersion: params.selectedArtifactHistoryVersion ?? null,
    selectedKey: params.selectedArtifactHistoryKey
  });

  const runs = [
    latestArtifactJob
      ? buildArtifactRunCard({
          key: `artifact-job-${latestArtifactJob.artifact_job_id}`,
          title: params.resolveArtifactSkillTitle(latestArtifactJob.skill_key),
          status: resolveRunStatus(latestArtifactJob.task_status, latestArtifactJob.status),
          detail: latestArtifactJob.result_title || latestArtifactJob.progress_message || latestArtifactJob.progress_phase,
          baseMeta: `Skill ${latestArtifactJob.skill_key} · ${params.formatRelativeTime(latestArtifactJob.updated_at)}`,
          waitContext: latestArtifactJob.wait_context
        })
      : null,
    params.currentResearchRunSummary
      ? buildArtifactRunCard({
          key: `research-${params.currentResearchRunSummary.research_run_id}`,
          title: "Deep Research 报告",
          status: params.currentResearchRunSummary.status,
          detail: params.currentResearchRunSummary.final_report_title
            || summarizeText(params.currentResearchRunSummary.question, 36),
          baseMeta: `基于 ${params.currentResearchRunSummary.source_scope_count} 个资料 · ${params.formatRelativeTime(params.currentResearchRunSummary.updated_at)}`,
          waitContext: params.currentResearchRunSummary.wait_context
        })
      : null,
    params.latestTask
      ? buildArtifactRunCard({
          key: `workspace-task-${params.latestTask.task_id}`,
          title: params.latestTask.task_type,
          status: params.latestTask.task_status,
          detail: params.latestTask.progress_message || params.latestTask.progress_phase,
          baseMeta: `工作台资料处理中 · ${params.sourcesCount} 条资料`,
          waitContext: params.latestTask.wait_context
        })
      : null,
    params.workspaceReady
      ? buildArtifactWikiRunCard(params.wikiState ?? null, params.formatRelativeTime)
      : null
  ].filter((run): run is ArtifactSidebarRunCard => Boolean(run));

  return {
    runs,
    historyItems,
    latestAuditView,
    historyViewer
  };
}

type BuildArtifactRunCardParams = {
  key: string;
  title: string;
  status: string;
  detail: string;
  baseMeta: string;
  waitContext?: WaitContext | null;
};

function buildArtifactRunCard(params: BuildArtifactRunCardParams): ArtifactSidebarRunCard {
  return {
    key: params.key,
    title: params.title,
    status: summarizeRunStatus(params.status),
    detail: params.detail,
    meta: formatRunMeta(params.baseMeta, params.waitContext),
    waitSignals: buildWaitContextSignalChips(params.waitContext),
    waitDetails: buildWaitContextDetailLines(params.waitContext),
    tone: resolveRunTone(params.status)
  };
}

function buildArtifactWikiRunCard(
  wikiState: ArtifactSidebarWikiState | null,
  formatRelativeTime: (value: string) => string
): ArtifactSidebarRunCard {
  const enabled = wikiState?.enabled === true;
  const status = enabled
    ? ((wikiState?.pendingTaskCount ?? 0) > 0 ? "RUNNING" : "READY")
    : "OFF";

  return {
    key: "workspace-wiki",
    title: "工作台 Wiki 构建",
    status: summarizeRunStatus(status),
    detail: enabled
      ? `页面 ${wikiState?.pageCount ?? 0} · 待处理 ${wikiState?.pendingTaskCount ?? 0}`
      : "当前未开启自动 Wiki 构建",
    meta: enabled
      ? `工作台级索引与异步 ingest · ${formatRelativeTime(wikiState?.updatedAt ?? "")}`
      : "可在更多工具中开启",
    waitSignals: [],
    waitDetails: [],
    tone: resolveRunTone(status)
  };
}

function formatRunMeta(baseMeta: string, waitContext?: WaitContext | null): string {
  const waitNarrative = buildWaitContextNarrative(waitContext);
  return waitNarrative ? `${waitNarrative} · ${baseMeta}` : baseMeta;
}

function summarizeText(value: string, maxLength: number): string {
  const normalized = value.replace(/\s+/g, " ").trim();
  if (normalized.length <= maxLength) {
    return normalized;
  }
  return `${normalized.slice(0, Math.max(0, maxLength - 1)).trimEnd()}…`;
}
