// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ArtifactRail } from "./ArtifactRail";
import { ArtifactReader, stripLeadingTitle } from "./ArtifactReader";
import type { ArtifactOutputItem } from "./artifactOutputs";
import type { ArtifactFileMetadata, ArtifactJobSummary, ArtifactVersionDetail } from "./model";

function file(overrides: Partial<ArtifactFileMetadata>): ArtifactFileMetadata {
  return {
    file_id: "file", file_format: "PDF", file_name: "report.pdf", media_type: "application/pdf",
    storage_backend: "MINIO", bucket_name: "artifacts", object_key: "report.pdf", size_bytes: 10,
    checksum_sha256: "", status: "READY", error_message: "", created_at: "2026-09-01T00:00:00Z",
    ...overrides
  };
}

function version(versionNo: number, overrides: Partial<ArtifactVersionDetail> = {}): ArtifactVersionDetail {
  return {
    version_id: `v${versionNo}`,
    artifact_job_id: "job-report",
    skill_key: "report_draft",
    version_no: versionNo,
    title: "检索链路报告",
    content_markdown: `# 检索链路报告\n\n## 结论\n\n第 ${versionNo} 版正文`,
    trace_summary: "",
    citations: [],
    runtime_trace: {
      verification: { status: "PASS" },
      evidence_coverage: { section_count: 4, covered_section_count: 3, sections_missing_evidence: ["小结"] },
      approval_trace: { capability_decisions: [{ capability_name: "EXTRACT_TRANSCRIPT", server_id: "bilibili", tool_name: "get_subtitle" }] }
    },
    files: [],
    created_at: "2026-09-01T00:00:00Z",
    ...overrides
  };
}

const item: ArtifactOutputItem = {
  key: "artifact-job-job-report",
  artifactJobId: "job-report",
  skillKey: "report_draft",
  title: "检索链路报告",
  typeLabel: "结构化报告",
  versionNo: 3,
  state: "ready",
  statusLabel: "",
  phaseIndex: -1,
  detail: "",
  technicalDetail: "",
  updatedAt: "2026-09-01T00:00:00Z"
};

function readerProps(overrides: Record<string, unknown> = {}) {
  return {
    workspaceId: "workspace",
    item,
    versionNo: 3,
    version: version(3),
    loading: false,
    isBusy: false,
    formatRelativeTime: () => "刚刚",
    saveArtifactVersionAsSource: vi.fn(),
    artifactSavedSourceByVersionId: {},
    artifactVersionSaveKey: (ref: { artifact_job_id: string; version_no: number }) => `${ref.artifact_job_id}:${ref.version_no}`,
    writeArtifactVersionToKnowledge: vi.fn(),
    artifactWritebackByVersionId: {},
    regenerateArtifactVersion: vi.fn(),
    compareArtifactWithPreviousVersion: vi.fn(),
    rollbackArtifactVersion: vi.fn(async () => undefined),
    downloadArtifactVersionPdf: vi.fn(),
    downloadArtifactVersionFile: vi.fn(),
    onBack: vi.fn(),
    onSelectVersion: vi.fn(),
    onRolledBack: vi.fn(),
    api: {
      listVersions: vi.fn(async () => [3, 1, 2].map((no) => ({
        version_id: `v${no}`, artifact_job_id: "job-report", skill_key: "report_draft",
        version_no: no, title: "检索链路报告", created_at: "2026-09-01T00:00:00Z"
      }))),
      downloadFile: vi.fn()
    },
    ...overrides
  };
}

describe("ArtifactReader", () => {
  afterEach(() => cleanup());

  it("renders the content once under the header and summarizes how it was generated", async () => {
    render(<ArtifactReader {...readerProps()} />);

    expect(screen.getByRole("heading", { name: "检索链路报告" })).toBeTruthy();
    // 正文开头与标题相同的一级标题不再重复
    expect(document.querySelector(".artifact-reader-markdown h1")).toBeNull();
    expect(screen.getByText("第 3 版正文")).toBeTruthy();
    expect(screen.getByText("校验通过")).toBeTruthy();
    expect(screen.getByText("来源覆盖 3/4")).toBeTruthy();
    expect(screen.getByText("MCP 工具 1")).toBeTruthy();
    expect(screen.getByText("4 个章节中 3 个有来源支撑，缺少：小结")).toBeTruthy();
    expect(screen.getByTitle("MCP · bilibili / get_subtitle").textContent).toContain("提取字幕");
  });

  it("switches between versions listed newest first", async () => {
    const props = readerProps();
    render(<ArtifactReader {...props} />);

    const select = screen.getByLabelText("选择版本") as HTMLSelectElement;
    await waitFor(() => expect(select.options).toHaveLength(3));
    expect([...select.options].map((option) => option.textContent)).toEqual(["v3 · 最新", "v2", "v1"]);
    fireEvent.change(select, { target: { value: "1" } });
    expect(props.onSelectVersion).toHaveBeenCalledWith(1);
  });

  it("shows the comparison with the previous version inline", async () => {
    const props = readerProps({
      compareArtifactWithPreviousVersion: vi.fn(async () => ({
        from_version_no: 2, to_version_no: 3, title_changed: false,
        added_lines: 9, removed_lines: 1, unchanged_lines: 20, summary: ""
      }))
    });
    render(<ArtifactReader {...props} />);

    fireEvent.click(screen.getByRole("menuitem", { name: "与上一版比较" }));
    expect(await screen.findByText(/与 v2 相比：新增 9 行，删除 1 行/)).toBeTruthy();
    expect(props.compareArtifactWithPreviousVersion).toHaveBeenCalledWith({ artifact_job_id: "job-report", version_no: 3 });
  });

  it("restores an older version as a new version and returns to the latest", async () => {
    const props = readerProps({ versionNo: 1, version: version(1) });
    render(<ArtifactReader {...props} />);

    expect(screen.getByRole("menuitem", { name: "与上一版比较" })).toHaveProperty("disabled", true);
    fireEvent.click(screen.getByRole("menuitem", { name: "恢复为此版本" }));
    await waitFor(() => expect(props.onRolledBack).toHaveBeenCalledOnce());
    expect(props.rollbackArtifactVersion).toHaveBeenCalledWith({ artifact_job_id: "job-report", version_no: 1 });
  });

  it("offers downloads only for ready files and hides restore on the latest version", () => {
    const props = readerProps({
      version: version(3, {
        files: [
          file({ file_id: "pdf", file_format: "PDF" }),
          file({ file_id: "md", file_format: "MARKDOWN", file_name: "report.md" }),
          file({ file_id: "broken", file_format: "PPTX", status: "DEGRADED" })
        ]
      })
    });
    render(<ArtifactReader {...props} />);

    expect(screen.getByRole("button", { name: "下载 PDF" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "下载 Markdown" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "下载 PPTX" })).toBeNull();
    expect(screen.queryByRole("menuitem", { name: "恢复为此版本" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "下载 Markdown" }));
    expect(props.downloadArtifactVersionFile).toHaveBeenCalledWith(
      { artifact_job_id: "job-report", version_no: 3 },
      expect.objectContaining({ file_id: "md" })
    );
  });

  it("keeps the leading heading when it differs from the title", () => {
    expect(stripLeadingTitle("# 其他标题\n\n正文", "检索链路报告")).toBe("# 其他标题\n\n正文");
    expect(stripLeadingTitle("# 检索链路报告\r\n\r\n正文", "检索链路报告")).toBe("正文");
  });
});

describe("ArtifactRail outputs", () => {
  afterEach(() => cleanup());

  const readyJob: ArtifactJobSummary = {
    artifact_job_id: "job-report", workspace_id: "workspace", task_id: "task", skill_key: "report_draft",
    status: "COMPLETED", task_status: "COMPLETED", progress_phase: "EXPORTING", progress_message: "",
    result_title: "检索链路报告", wait_context: null, latest_version_no: 3,
    created_at: "2026-09-01T00:00:00Z", updated_at: "2026-09-01T00:00:00Z"
  };

  function railProps(overrides: Record<string, unknown> = {}) {
    return {
      ...readerProps(),
      artifactComposerOpen: false,
      artifactStudioSkills: [],
      artifactSkillsLoading: false,
      artifactJobs: [
        readyJob,
        { ...readyJob, artifact_job_id: "job-quiz", skill_key: "quiz_pack", status: "RUNNING", task_status: "RUNNING",
          progress_phase: "COMPOSING", result_title: "", latest_version_no: 0 }
      ],
      artifactJobsLoading: false,
      latestArtifactVersion: null,
      selectedArtifactHistoryVersion: null,
      artifactHistoryLoadingKey: "",
      openArtifactHistoryVersion: vi.fn(),
      resolveArtifactSkillTitle: (key: string) => ({ report_draft: "结构化报告", quiz_pack: "测验题集" })[key] ?? key,
      workspace: { workspace_id: "workspace", name: "研究工作台", status: "ACTIVE" },
      sourceCount: 2,
      lastNoteAssistantMessageId: "",
      ...overrides
    } as any;
  }

  it("shows the running phase and opens a ready output in the reader", async () => {
    const props = railProps();
    const { rerender } = render(<ArtifactRail {...props} />);

    const running = document.querySelector('[data-run-key="artifact-job-job-quiz"]');
    expect(running?.getAttribute("data-state")).toBe("running");
    expect(running?.textContent).toContain("分段生成");
    expect(screen.getByRole("progressbar", { name: "分段生成" }).getAttribute("aria-valuenow")).toBe("2");
    expect(screen.queryByRole("button", { name: "打开 测验题集" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "打开 检索链路报告" }));
    await waitFor(() => expect(props.openArtifactHistoryVersion).toHaveBeenCalledWith(
      expect.objectContaining({ key: "artifact-version-job-report-v3", artifactJobId: "job-report", versionNo: 3 })
    ));
    expect(screen.getByText("正在加载 v3")).toBeTruthy();

    rerender(<ArtifactRail {...railProps({ selectedArtifactHistoryVersion: version(3) })} />);
    expect(await screen.findByText("第 3 版正文")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "返回产物列表" }));
    expect(screen.getByRole("heading", { name: "我的产物" })).toBeTruthy();
  });

  it("offers the note-to-source tool only after a close-reading answer", () => {
    const { rerender } = render(<ArtifactRail {...railProps()} />);
    expect(screen.queryByText("把精读回答存为资料")).toBeNull();
    rerender(<ArtifactRail {...railProps({ lastNoteAssistantMessageId: "message-1" })} />);
    expect(screen.getByText("把精读回答存为资料")).toBeTruthy();
  });
});
