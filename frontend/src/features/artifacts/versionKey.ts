export function artifactVersionSaveKey(version: { artifact_job_id: string; version_no: number }): string {
  return `${version.artifact_job_id}:${version.version_no}`;
}