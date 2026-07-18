import { describe, expect, it } from "vitest";
import type { ArtifactHistoryItem } from "./artifactHistory";
import { buildArtifactHistoryViewerState, type ArtifactHistoryViewerVersion } from "./artifactHistoryViewer";

describe("artifactHistoryViewer", () => {
  const historyItems: ArtifactHistoryItem[] = [
    {
      key: "artifact-version-job-2-v5",
      artifactJobId: "job-2",
      skillKey: "resume_highlight",
      versionNo: 5,
      title: "最新简历亮点",
      detail: "done",
      updatedAt: "2026-07-08T10:00:00Z",
      status: "SUCCEEDED"
    },
    {
      key: "artifact-version-job-1-v3",
      artifactJobId: "job-1",
      skillKey: "study_guide",
      versionNo: 3,
      title: "历史学习指南",
      detail: "done",
      updatedAt: "2026-07-07T10:00:00Z",
      status: "SUCCEEDED"
    }
  ];

  it("should default the history viewer to the latest artifact version", () => {
    const latestVersion: ArtifactHistoryViewerVersion = {
      artifact_job_id: "job-2",
      skill_key: "resume_highlight",
      version_no: 5,
      title: "最新简历亮点",
      content_markdown: "  latest output   with verifier summary  ",
      created_at: "2026-07-08T09:00:00Z",
      runtime_trace: {
        verification: {
          status: "PASS"
        }
      }
    };

    const viewer = buildArtifactHistoryViewerState({
      historyItems,
      latestVersion
    });

    expect(viewer.activeKey).toBe("artifact-version-job-2-v5");
    expect(viewer.activeVersion?.artifact_job_id).toBe("job-2");
    expect(viewer.isLatestVersion).toBe(true);
    expect(viewer.scopeLabel).toBe("最新版本");
    expect(viewer.detailToggleLabel).toBe("查看最新版本审计详情");
    expect(viewer.preview).toBe("latest output with verifier summary");
    expect(viewer.runtimeSummary).toEqual([
      {
        label: "Verifier",
        value: "PASS"
      }
    ]);
    expect(viewer.detailSections[0]?.title).toBe("Verifier");
  });

  it("should switch the viewer to the selected historical version when it differs from the latest one", () => {
    const latestVersion: ArtifactHistoryViewerVersion = {
      artifact_job_id: "job-2",
      skill_key: "resume_highlight",
      version_no: 5,
      title: "最新简历亮点",
      content_markdown: "latest output",
      created_at: "2026-07-08T09:00:00Z",
      runtime_trace: {
        verification: {
          status: "PASS"
        }
      }
    };
    const selectedVersion: ArtifactHistoryViewerVersion = {
      artifact_job_id: "job-1",
      skill_key: "study_guide",
      version_no: 3,
      title: "历史学习指南",
      content_markdown: " historical output with callback trace ",
      created_at: "2026-07-07T09:00:00Z",
      runtime_trace: {
        acquisition_callback_trace: {
          receipt: {
            provider_job_status: "SUCCEEDED"
          }
        }
      }
    };

    const viewer = buildArtifactHistoryViewerState({
      historyItems,
      latestVersion,
      selectedVersion,
      selectedKey: "artifact-version-job-1-v3"
    });

    expect(viewer.activeKey).toBe("artifact-version-job-1-v3");
    expect(viewer.activeVersion?.artifact_job_id).toBe("job-1");
    expect(viewer.isLatestVersion).toBe(false);
    expect(viewer.scopeLabel).toBe("历史版本");
    expect(viewer.detailToggleLabel).toBe("查看历史版本审计详情");
    expect(viewer.preview).toBe("historical output with callback trace");
    expect(viewer.runtimeSummary).toEqual([
      {
        label: "Callback",
        value: "SUCCEEDED"
      }
    ]);
    expect(viewer.detailSections[0]?.title).toBe("Callback");
  });

  it("should derive a stable active key from the selected version when no explicit selection key exists", () => {
    const selectedVersion: ArtifactHistoryViewerVersion = {
      artifact_job_id: "job-1",
      skill_key: "study_guide",
      version_no: 3,
      title: "历史学习指南",
      content_markdown: "history output",
      created_at: "2026-07-07T09:00:00Z"
    };

    const viewer = buildArtifactHistoryViewerState({
      historyItems,
      selectedVersion
    });

    expect(viewer.activeKey).toBe("artifact-version-job-1-v3");
    expect(viewer.scopeLabel).toBe("历史版本");
  });
});
