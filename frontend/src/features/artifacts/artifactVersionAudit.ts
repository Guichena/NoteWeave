import {
  buildArtifactRuntimeSummary,
  type ArtifactRuntimeSummaryItem,
  type ArtifactRuntimeTrace
} from "./artifactRuntimeTrace";
import {
  buildArtifactRuntimeDetailSections,
  type ArtifactRuntimeDetailSection
} from "./artifactRuntimeTraceDetails";

export type ArtifactVersionAuditViewVersion = {
  skill_key: string;
  version_no: number;
  title: string;
  content_markdown: string;
  runtime_trace?: ArtifactRuntimeTrace | null;
  files?: Array<{
    file_format: string;
    storage_backend: string;
    size_bytes: number;
    status: string;
  }>;
  created_at: string;
};

export type ArtifactVersionAuditView = {
  version: ArtifactVersionAuditViewVersion | null;
  scopeLabel: string;
  detailToggleLabel: string;
  preview: string;
  runtimeSummary: ArtifactRuntimeSummaryItem[];
  detailSections: ArtifactRuntimeDetailSection[];
};

type BuildArtifactVersionAuditViewParams = {
  version?: ArtifactVersionAuditViewVersion | null;
  scopeLabel: string;
  detailToggleLabel: string;
};

export function buildArtifactVersionAuditView(
  params: BuildArtifactVersionAuditViewParams
): ArtifactVersionAuditView {
  const version = params.version ?? null;
  if (!version) {
    return {
      version: null,
      scopeLabel: params.scopeLabel,
      detailToggleLabel: params.detailToggleLabel,
      preview: "",
      runtimeSummary: [],
      detailSections: []
    };
  }

  return {
    version,
    scopeLabel: params.scopeLabel,
    detailToggleLabel: params.detailToggleLabel,
    preview: version.content_markdown.replace(/\s+/g, " ").trim().slice(0, 180),
    runtimeSummary: buildArtifactRuntimeSummary(version.runtime_trace ?? null),
    detailSections: buildArtifactRuntimeDetailSections(version.runtime_trace ?? null)
  };
}
