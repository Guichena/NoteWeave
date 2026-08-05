import type { WaitContext } from "../../runStatus";

export type ArtifactHistoryJobSummary = {
  artifact_job_id: string;
  skill_key: string;
  status: string;
  task_status: string;
  progress_phase: string;
  progress_message: string;
  result_title: string;
  wait_context?: WaitContext | null;
  latest_version_no: number;
  created_at: string;
  updated_at: string;
};

export type ArtifactHistoryItem = {
  key: string;
  artifactJobId: string;
  skillKey: string;
  versionNo: number;
  title: string;
  detail: string;
  updatedAt: string;
  status: string;
};

export function buildArtifactHistoryVersionKey(artifactJobId: string, versionNo: number): string {
  return `artifact-version-${artifactJobId}-v${versionNo}`;
}

export function buildArtifactHistoryItems(
  jobs: ArtifactHistoryJobSummary[],
  limit = 6
): ArtifactHistoryItem[] {
  return jobs
    .filter((job) => job.latest_version_no > 0)
    .slice()
    .sort((left, right) => String(right.updated_at).localeCompare(String(left.updated_at)))
    .slice(0, limit)
    .map((job) => ({
      key: buildArtifactHistoryVersionKey(job.artifact_job_id, job.latest_version_no),
      artifactJobId: job.artifact_job_id,
      skillKey: job.skill_key,
      versionNo: job.latest_version_no,
      title: job.result_title.trim(),
      detail: job.progress_message.trim() || job.progress_phase.trim(),
      updatedAt: job.updated_at,
      status: job.task_status || job.status
    }));
}
