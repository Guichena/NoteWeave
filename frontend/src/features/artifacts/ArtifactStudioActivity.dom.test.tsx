// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import type { ComponentProps } from "react";
import { describe, expect, it, vi } from "vitest";
import { buildArtifactHistoryVersionKey } from "./artifactHistory";
import { buildArtifactSidebarState } from "./artifactSidebar";
import { ArtifactStudioActivity } from "./ArtifactStudioActivity";
import type { ArtifactVersionDetail } from "./model";

type ArtifactStudioActivityProps = ComponentProps<typeof ArtifactStudioActivity>;

function artifactVersion(versionNo: number): ArtifactVersionDetail {
  return {
    version_id: `version-${versionNo}`,
    artifact_job_id: "job-1",
    skill_key: "report_draft",
    version_no: versionNo,
    title: versionNo === 2 ? "最新研究报告" : "历史研究报告",
    content_markdown: `# 研究报告 v${versionNo}`,
    trace_summary: "",
    citations: [],
    runtime_trace: null,
    files: [],
    created_at: `2026-08-0${versionNo}T00:00:00Z`
  };
}

function activityProps(
  selectedArtifactHistoryVersion: ArtifactVersionDetail | null = null
): ArtifactStudioActivityProps {
  const latestArtifactVersion = artifactVersion(2);

  return {
    artifactSidebarState: buildArtifactSidebarState({
      artifactJobs: [{
        artifact_job_id: latestArtifactVersion.artifact_job_id,
        skill_key: latestArtifactVersion.skill_key,
        status: "COMPLETED",
        task_status: "SUCCEEDED",
        progress_phase: "completed",
        progress_message: "产物已生成",
        result_title: latestArtifactVersion.title,
        latest_version_no: latestArtifactVersion.version_no,
        created_at: latestArtifactVersion.created_at,
        updated_at: latestArtifactVersion.created_at
      }],
      latestArtifactVersion,
      selectedArtifactHistoryVersion,
      selectedArtifactHistoryKey: selectedArtifactHistoryVersion
        ? buildArtifactHistoryVersionKey(
            selectedArtifactHistoryVersion.artifact_job_id,
            selectedArtifactHistoryVersion.version_no
          )
        : "",
      workspaceReady: false,
      sourcesCount: 0,
      resolveArtifactSkillTitle: () => "结构化报告",
      formatRelativeTime: () => "刚刚"
    }),
    latestArtifactVersion,
    artifactJobsLoading: false,
    isBusy: false,
    formatRelativeTime: () => "刚刚",
    saveArtifactVersionAsSource: vi.fn(),
    artifactSavedSourceByVersionId: {},
    artifactVersionSaveKey: (version) => `${version.artifact_job_id}-v${version.version_no}`,
    writeArtifactVersionToKnowledge: vi.fn(),
    artifactWritebackByVersionId: {},
    regenerateArtifactVersion: vi.fn(),
    compareArtifactWithPreviousVersion: vi.fn(),
    rollbackArtifactVersion: vi.fn(),
    downloadArtifactVersionPdf: vi.fn(),
    downloadArtifactVersionFile: vi.fn(),
    resolveArtifactSkillTitle: () => "结构化报告",
    summarizeRunStatus: (status) => status,
    openArtifactHistoryVersion: vi.fn(),
    artifactHistoryLoadingKey: "",
    artifactStudioSkills: []
  };
}

describe("ArtifactStudioActivity", () => {
  it("offers each READY version file and excludes degraded files", () => {
    const props = activityProps();
    props.latestArtifactVersion!.files = [
      { file_id: "pptx-1", file_format: "PPTX", file_name: "slides.pptx", media_type: "application/vnd.openxmlformats-officedocument.presentationml.presentation", storage_backend: "local", bucket_name: "", object_key: "", size_bytes: 1024, checksum_sha256: "a".repeat(64), status: "READY", error_message: "", created_at: "2026-08-02T00:00:00Z" },
      { file_id: "preview-1", file_format: "PNG", file_name: "slide-01.png", media_type: "image/png", storage_backend: "local", bucket_name: "", object_key: "", size_bytes: 512, checksum_sha256: "b".repeat(64), status: "DEGRADED", error_message: "", created_at: "2026-08-02T00:00:00Z" }
    ];
    render(<ArtifactStudioActivity {...props} />);
    fireEvent.click(screen.getByRole("button", { name: "下载 PPTX" }));
    expect(props.downloadArtifactVersionFile).toHaveBeenCalledWith(
      expect.objectContaining({ version_no: 2 }), expect.objectContaining({ file_id: "pptx-1" }));
    expect(screen.queryByRole("button", { name: /slide-01.png/ })).toBeNull();
  });
  it("renders the latest version once when the history viewer defaults to that version", () => {
    const { container } = render(<ArtifactStudioActivity {...activityProps()} />);

    expect(container.querySelectorAll(".artifact-version-card")).toHaveLength(1);
    expect(container.querySelector(".artifact-history-detail-card")).toBeNull();
  });

  it("keeps a separately selected historical version visible beside the latest version", () => {
    const { container } = render(
      <ArtifactStudioActivity {...activityProps(artifactVersion(1))} />
    );

    expect(container.querySelectorAll(".artifact-version-card")).toHaveLength(1);
    expect(container.querySelectorAll(".artifact-history-detail-card")).toHaveLength(1);
  });
});
