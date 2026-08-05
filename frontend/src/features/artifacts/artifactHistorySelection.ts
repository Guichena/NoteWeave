export type ArtifactVersionRequestCandidate = {
  artifact_job_id: string;
  latest_version_no: number;
  updated_at: string;
};

export type ArtifactVersionRequest = {
  artifactJobId: string;
  versionNo: number;
};

export type ArtifactHistoryVersionDetail = {
  artifact_job_id: string;
  version_no: number;
};

export function resolveLatestArtifactVersionRequest(
  jobs: ArtifactVersionRequestCandidate[]
): ArtifactVersionRequest | null {
  const latestCompletedJob = jobs
    .filter((job) => job.latest_version_no > 0)
    .slice()
    .sort((left, right) => String(right.updated_at).localeCompare(String(left.updated_at)))[0];

  if (!latestCompletedJob) {
    return null;
  }

  return {
    artifactJobId: latestCompletedJob.artifact_job_id,
    versionNo: latestCompletedJob.latest_version_no
  };
}

export function shouldReuseLatestArtifactVersion(
  selectedVersion: ArtifactVersionRequest,
  latestArtifactVersion?: ArtifactHistoryVersionDetail | null
): boolean {
  if (!latestArtifactVersion) {
    return false;
  }
  return latestArtifactVersion.artifact_job_id === selectedVersion.artifactJobId
    && latestArtifactVersion.version_no === selectedVersion.versionNo;
}
