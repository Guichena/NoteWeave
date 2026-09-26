// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ArtifactVersionActions } from "./ArtifactVersionActions";

describe("ArtifactVersionActions", () => {
  it("shares version actions and keeps compare disabled for the first version", () => {
    const version = {
      artifact_job_id: "job-1",
      skill_key: "resume_highlights",
      version_no: 1,
      title: "简历亮点",
      content_markdown: "# 简历亮点",
      created_at: "2026-08-07T00:00:00Z",
      runtime_trace: null,
      files: [{ file_format: "md", storage_backend: "local", size_bytes: 128, status: "COMPILED" }]
    };
    const writeArtifactVersionToKnowledge = vi.fn();

    render(
      <ArtifactVersionActions
        version={version}
        auditView={{
          preview: "预览内容",
          scopeLabel: "最新版本",
          detailToggleLabel: "查看最新版本审计详情",
          runtimeSummary: [{ label: "状态", value: "COMPILED" }],
          detailSections: [{ title: "导出", lines: ["Markdown 已生成"] }]
        }}
        isBusy={false}
        formatRelativeTime={() => "刚刚"}
        saveArtifactVersionAsSource={vi.fn()}
        artifactSavedSourceByVersionId={{}}
        artifactVersionSaveKey={() => "job-1-v1"}
        writeArtifactVersionToKnowledge={writeArtifactVersionToKnowledge}
        artifactWritebackByVersionId={{}}
        regenerateArtifactVersion={vi.fn()}
        compareArtifactWithPreviousVersion={vi.fn()}
        rollbackArtifactVersion={vi.fn()}
        downloadArtifactVersionPdf={vi.fn()}
        downloadArtifactVersionFile={vi.fn()}
      />
    );

    expect(screen.getByText("预览内容")).toBeTruthy();
    expect((screen.getByRole("button", { name: "与上一版比较" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "写入 Note" }));
    expect(writeArtifactVersionToKnowledge).toHaveBeenCalledWith(version, "NOTE");
    expect(screen.getByText("查看最新版本审计详情")).toBeTruthy();
  });
});
