import { describe, expect, it } from "vitest";
import { buildArtifactSidebarState, type ArtifactSidebarResearchRunSummary, type ArtifactSidebarWikiState, type ArtifactSidebarWorkspaceTask } from "./artifactSidebar";
import type { ArtifactHistoryJobSummary } from "./artifactHistory";
import type { ArtifactHistoryViewerVersion } from "./artifactHistoryViewer";

describe("artifactSidebar", () => {
  const formatRelativeTime = (value: string): string => `ago(${value})`;
  const resolveArtifactSkillTitle = (skillKey: string): string => ({
    resume_highlight: "简历亮点描述"
  }[skillKey] || skillKey);

  it("should build stable sidebar state from artifact, research, workspace, and wiki sources", () => {
    const artifactJobs: ArtifactHistoryJobSummary[] = [
      {
        artifact_job_id: "job-1",
        skill_key: "resume_highlight",
        status: "RUNNING",
        task_status: "WAITING",
        progress_phase: "WRITING",
        progress_message: "waiting on subtitle callback",
        result_title: "简历亮点草稿",
        wait_context: {
          status: "WAITING_FOR_PROVIDER",
          provider_job: {
            dispatch_count: 2,
            previous_failed_delivery_count: 1,
            provider_status: "AVAILABLE",
            health_status: "HEALTHY",
            provider_job_status: "PENDING_UPSTREAM"
          }
        },
        latest_version_no: 2,
        created_at: "2026-07-08T09:00:00Z",
        updated_at: "2026-07-08T10:00:00Z"
      }
    ];
    const latestArtifactVersion = {
      artifact_job_id: "job-1",
      skill_key: "resume_highlight",
      version_no: 2,
      title: "简历亮点描述",
      content_markdown: " latest artifact output ",
      created_at: "2026-07-08T10:05:00Z",
      runtime_trace: {
        verification: {
          status: "PASS"
        }
      }
    };
    const selectedArtifactHistoryVersion: ArtifactHistoryViewerVersion = {
      artifact_job_id: "job-1",
      skill_key: "resume_highlight",
      version_no: 2,
      title: "简历亮点描述",
      content_markdown: " history artifact output ",
      created_at: "2026-07-08T10:05:00Z",
      runtime_trace: {
        verification: {
          status: "PASS_WITH_REPAIR"
        }
      }
    };
    const currentResearchRunSummary: ArtifactSidebarResearchRunSummary = {
      research_run_id: "research-1",
      question: "How should we redesign the artifact runtime contract?",
      status: "RUNNING",
      final_report_title: "",
      source_scope_count: 4,
      updated_at: "2026-07-08T10:10:00Z",
      wait_context: null
    };
    const latestTask: ArtifactSidebarWorkspaceTask = {
      task_id: "task-1",
      task_type: "INGEST_SOURCE",
      task_status: "RUNNING",
      progress_phase: "INDEXING",
      progress_message: "indexing source",
      wait_context: null
    };
    const wikiState: ArtifactSidebarWikiState = {
      enabled: true,
      pageCount: 7,
      pendingTaskCount: 2,
      updatedAt: "2026-07-08T10:11:00Z"
    };

    const sidebar = buildArtifactSidebarState({
      artifactJobs,
      latestArtifactVersion,
      selectedArtifactHistoryVersion,
      selectedArtifactHistoryKey: "artifact-version-job-1-v2",
      currentResearchRunSummary,
      latestTask,
      workspaceReady: true,
      wikiState,
      sourcesCount: 12,
      resolveArtifactSkillTitle,
      formatRelativeTime
    });

    expect(sidebar.runs).toHaveLength(4);
    expect(sidebar.runs[0]).toMatchObject({
      key: "artifact-job-job-1",
      title: "简历亮点描述",
      status: "等待中",
      detail: "简历亮点草稿",
      tone: "waiting",
      group: "active"
    });
    expect(sidebar.runs[0].meta).toContain("Skill resume_highlight · ago(2026-07-08T10:00:00Z)");
    expect(sidebar.runs[0].waitSignals.map((chip) => chip.label)).toEqual(["Delivery", "Retry", "Provider", "State"]);
    expect(sidebar.runs[0].waitDetails).toEqual([]);
    expect(sidebar.runs[1]).toMatchObject({
      key: "research-research-1",
      title: "Deep Research 报告",
      status: "运行中",
      tone: "active",
      group: "active"
    });
    expect(sidebar.runs[2]).toMatchObject({
      key: "workspace-task-task-1",
      title: "INGEST_SOURCE",
      detail: "indexing source"
    });
    expect(sidebar.runs[3]).toMatchObject({
      key: "workspace-wiki",
      title: "工作台 Wiki 构建",
      status: "运行中",
      detail: "页面 7 · 待处理 2",
      group: "active"
    });
    expect(sidebar.runs[3].waitDetails).toEqual([]);

    expect(sidebar.historyItems).toEqual([
      {
        key: "artifact-version-job-1-v2",
        artifactJobId: "job-1",
        skillKey: "resume_highlight",
        versionNo: 2,
        title: "简历亮点草稿",
        detail: "waiting on subtitle callback",
        updatedAt: "2026-07-08T10:00:00Z",
        status: "WAITING"
      }
    ]);
    expect(sidebar.latestAuditView.preview).toBe("latest artifact output");
    expect(sidebar.latestAuditView.detailToggleLabel).toBe("查看最新版本审计详情");
    expect(sidebar.historyViewer.activeKey).toBe("artifact-version-job-1-v2");
    expect(sidebar.historyViewer.detailToggleLabel).toBe("查看最新版本审计详情");
  });

  it("classifies terminal and inactive runs outside the active group", () => {
    const failedJob: ArtifactHistoryJobSummary = {
      artifact_job_id: "failed-job",
      skill_key: "resume_highlight",
      status: "FAILED",
      task_status: "FAILED",
      progress_phase: "VALIDATION",
      progress_message: "source scope must not be empty",
      result_title: "",
      latest_version_no: 0,
      created_at: "2026-07-08T09:00:00Z",
      updated_at: "2026-07-08T10:00:00Z"
    };

    const sidebar = buildArtifactSidebarState({
      artifactJobs: [failedJob],
      latestArtifactVersion: null,
      selectedArtifactHistoryVersion: null,
      selectedArtifactHistoryKey: "",
      currentResearchRunSummary: null,
      latestTask: null,
      workspaceReady: true,
      wikiState: { enabled: false, pageCount: 0, pendingTaskCount: 0, updatedAt: "" },
      sourcesCount: 0,
      resolveArtifactSkillTitle,
      formatRelativeTime
    });

    expect(sidebar.runs[0]).toMatchObject({ tone: "danger", group: "recent", status: "失败" });
    expect(sidebar.runs[1]).toMatchObject({ tone: "waiting", group: "inactive", status: "未开启" });
  });

  it("should keep sidebar stable when no workspace data is ready", () => {
    const sidebar = buildArtifactSidebarState({
      artifactJobs: [],
      latestArtifactVersion: null,
      selectedArtifactHistoryVersion: null,
      selectedArtifactHistoryKey: "",
      currentResearchRunSummary: null,
      latestTask: null,
      workspaceReady: false,
      wikiState: null,
      sourcesCount: 0,
      resolveArtifactSkillTitle,
      formatRelativeTime
    });

    expect(sidebar.runs).toEqual([]);
    expect(sidebar.historyItems).toEqual([]);
    expect(sidebar.latestAuditView.version).toBeNull();
    expect(sidebar.historyViewer.activeVersion).toBeNull();
  });

  it("should surface wait detail lines from provider delivery attempts in running cards", () => {
    const artifactJobs: ArtifactHistoryJobSummary[] = [
      {
        artifact_job_id: "job-1",
        skill_key: "resume_highlight",
        status: "RUNNING",
        task_status: "WAITING",
        progress_phase: "WAITING_FOR_PROVIDER",
        progress_message: "waiting on subtitle callback",
        result_title: "简历亮点草稿",
        wait_context: {
          status: "WAITING_FOR_PROVIDER",
          provider_job: {
            request_id: "fetch-artifact-task-bili-1",
            provider_job_id: "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-1",
            provider_receipt_id: "provider-receipt-fetch-artifact-task-bili-1",
            provider_delivery_attempts: [
              {
                delivery_id: "acq-delivery-fetch-artifact-task-bili-1-1",
                dispatch_count: 1,
                ack_status: "FAILED",
                error_code: "PROVIDER_TIMEOUT"
              },
              {
                delivery_id: "acq-delivery-fetch-artifact-task-bili-1-2",
                dispatch_count: 2,
                ack_status: "PENDING"
              }
            ]
          }
        },
        latest_version_no: 0,
        created_at: "2026-07-08T09:00:00Z",
        updated_at: "2026-07-08T10:00:00Z"
      }
    ];

    const sidebar = buildArtifactSidebarState({
      artifactJobs,
      latestArtifactVersion: null,
      selectedArtifactHistoryVersion: null,
      selectedArtifactHistoryKey: "",
      currentResearchRunSummary: null,
      latestTask: null,
      workspaceReady: false,
      wikiState: null,
      sourcesCount: 0,
      resolveArtifactSkillTitle,
      formatRelativeTime
    });

    expect(sidebar.runs[0].waitDetails).toEqual([
      { label: "Request", value: "fetch-artifact-task-bili-1" },
      { label: "Provider Job", value: "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-1" },
      { label: "Receipt", value: "provider-receipt-fetch-artifact-task-bili-1" },
      { label: "Latest Attempt", value: "PENDING / #2 / delivery=acq-delivery-fetch-artifact-task-bili-1-2" },
      { label: "Previous Attempt", value: "FAILED / #1 / error=PROVIDER_TIMEOUT" }
    ]);
  });
});
