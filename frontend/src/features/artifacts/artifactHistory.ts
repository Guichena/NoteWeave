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
