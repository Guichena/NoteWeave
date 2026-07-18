export type ResearchReportExportInput = {
  final_report_markdown?: string | null;
  final_report_title?: string | null;
  question?: string | null;
};

export type ResearchReportExportArtifact = {
  fileName: string;
  mimeType: string;
  content: string;
};

const RESEARCH_REPORT_MIME_TYPE = "text/markdown;charset=utf-8";

function buildResearchReportExportBaseName(input: ResearchReportExportInput): string {
  const candidateNames = [
    input.final_report_title,
    input.question,
    "deep-research-report",
  ];
  const rawName = candidateNames
    .map((value) => (value || "").trim())
    .find(Boolean) || "deep-research-report";

  const normalized = rawName
    .replace(/[\\/:*?"<>|]+/g, "-")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-+|-+$/g, "")
    .toLowerCase();

  return normalized || "deep-research-report";
}

export function buildResearchReportExportArtifact(
  input: ResearchReportExportInput
): ResearchReportExportArtifact | null {
  const content = (input.final_report_markdown || "").trim();
  if (!content) {
    return null;
  }
  return {
    fileName: `${buildResearchReportExportBaseName(input)}.md`,
    mimeType: RESEARCH_REPORT_MIME_TYPE,
    content,
  };
}

export function buildResearchReportExportStatusMessage(fileName: string): string {
  return `已导出 Deep Research Markdown：${fileName}`;
}
