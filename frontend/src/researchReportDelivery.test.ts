import { describe, it, expect } from "vitest";

import {
  buildResearchReportExportArtifact,
  buildResearchReportExportStatusMessage,
} from "./researchReportDelivery";

describe("researchReportDelivery", () => {
  it("should build a markdown export artifact from final report content", () => {
    const artifact = buildResearchReportExportArtifact({
      final_report_markdown: "# Deep Research Report\n\nBody",
      final_report_title: "Deep Research Report",
      question: "ignored question",
    });

    expect(artifact).toEqual({
      fileName: "deep-research-report.md",
      mimeType: "text/markdown;charset=utf-8",
      content: "# Deep Research Report\n\nBody",
    });
  });

  it("should sanitize file names and fall back to the question when title is empty", () => {
    const artifact = buildResearchReportExportArtifact({
      final_report_markdown: "## Report",
      final_report_title: "  ",
      question: "How should export/save:source? work <today> | now",
    });

    expect(artifact?.fileName).toBe("how-should-export-save-source-work-today-now.md");
  });

  it("should fall back to the default file name when title and question are both unusable", () => {
    const artifact = buildResearchReportExportArtifact({
      final_report_markdown: "## Report",
      final_report_title: "////",
      question: "   ",
    });

    expect(artifact?.fileName).toBe("deep-research-report.md");
  });

  it("should return null when there is no exportable markdown content", () => {
    expect(buildResearchReportExportArtifact({
      final_report_markdown: "",
      final_report_title: "Report",
      question: "Question",
    })).toBeNull();

    expect(buildResearchReportExportArtifact({
      final_report_markdown: "   ",
      final_report_title: "Report",
      question: "Question",
    })).toBeNull();
  });

  it("should build a stable status message after export", () => {
    expect(buildResearchReportExportStatusMessage("deep-research-report.md"))
      .toBe("已导出 Deep Research Markdown：deep-research-report.md");
  });
});
