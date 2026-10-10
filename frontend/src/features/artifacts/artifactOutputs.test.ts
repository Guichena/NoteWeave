import { describe, expect, it } from "vitest";
import { buildArtifactOutputItems, buildArtifactProcessView } from "./artifactOutputs";
import type { ArtifactJobSummary } from "./model";

function job(overrides: Partial<ArtifactJobSummary>): ArtifactJobSummary {
  return {
    artifact_job_id: "job",
    workspace_id: "workspace",
    task_id: "task",
    skill_key: "report_draft",
    status: "COMPLETED",
    task_status: "COMPLETED",
    progress_phase: "EXPORTING",
    progress_message: "",
    result_title: "",
    wait_context: null,
    latest_version_no: 1,
    created_at: "2026-09-01T00:00:00Z",
    updated_at: "2026-09-01T00:00:00Z",
    ...overrides
  };
}

const titles: Record<string, string> = { report_draft: "结构化报告", quiz_pack: "测验题集", audio_minutes: "音频纪要" };
const resolveTitle = (key: string) => titles[key] ?? key;

describe("buildArtifactOutputItems", () => {
  it("maps job status and phase to a user-facing state", () => {
    const items = buildArtifactOutputItems([
      job({ artifact_job_id: "ready", result_title: "检索链路报告", latest_version_no: 3 }),
      job({ artifact_job_id: "running", skill_key: "quiz_pack", status: "RUNNING", task_status: "RUNNING", progress_phase: "VERIFYING", latest_version_no: 0 }),
      job({
        artifact_job_id: "waiting", skill_key: "audio_minutes", status: "WAITING_FOR_PROVIDER", task_status: "WAITING",
        progress_phase: "WAITING_FOR_PROVIDER", latest_version_no: 0
      }),
      job({ artifact_job_id: "failed", status: "FAILED", task_status: "FAILED", progress_message: "缺少来源", latest_version_no: 0 })
    ], resolveTitle);

    const byId = Object.fromEntries(items.map((item) => [item.artifactJobId, item]));
    expect(byId.ready).toMatchObject({ state: "ready", title: "检索链路报告", typeLabel: "结构化报告", versionNo: 3 });
    expect(byId.running).toMatchObject({ state: "running", statusLabel: "校验输出", phaseIndex: 3, title: "测验题集" });
    expect(byId.waiting).toMatchObject({ state: "waiting", statusLabel: "等待外部服务", phaseIndex: 1 });
    expect(byId.failed).toMatchObject({ state: "failed", statusLabel: "生成失败", detail: "缺少来源" });
  });

  it("lists active jobs first, then the most recently updated", () => {
    const items = buildArtifactOutputItems([
      job({ artifact_job_id: "old", updated_at: "2026-09-01T00:00:00Z" }),
      job({ artifact_job_id: "new", updated_at: "2026-09-03T00:00:00Z" }),
      job({ artifact_job_id: "queued", status: "QUEUED", task_status: "QUEUED", updated_at: "2026-08-01T00:00:00Z" })
    ], resolveTitle);

    expect(items.map((item) => item.artifactJobId)).toEqual(["queued", "new", "old"]);
    expect(items[0]).toMatchObject({ state: "queued", statusLabel: "排队中" });
  });
});

describe("buildArtifactProcessView", () => {
  it("summarizes lifecycle, output contract, evidence coverage and capabilities", () => {
    const view = buildArtifactProcessView({
      verification: { status: "PASS" },
      generation_trace: { model: "Qwen2.5-72B-Instruct", source_count: 4 },
      output_contract_trace: { passed_checks: ["a", "b", "c"], repaired_checks: ["d"], failed_checks: [] },
      evidence_coverage: { section_count: 5, covered_section_count: 4, sections_missing_evidence: ["小结"] },
      lifecycle_trace: {
        status: "FAILED",
        steps: [
          { phase: "RESOLVING", status: "COMPLETED" },
          { phase: "ACQUIRING", status: "COMPLETED" },
          { phase: "COMPOSING", status: "FAILED" }
        ]
      },
      capability_union_trace: {
        capability_decisions: [
          { capability_name: "READ_WORKSPACE_DOC" },
          { capability_name: "EXTRACT_TRANSCRIPT" }
        ]
      },
      approval_trace: {
        capability_decisions: [{ capability_name: "EXTRACT_TRANSCRIPT", server_id: "bilibili", tool_name: "get_subtitle" }]
      }
    });

    expect(view?.steps.map((step) => step.status)).toEqual(["done", "done", "failed", "pending", "pending"]);
    expect(view?.verification).toBe("PASS");
    expect(view?.checks).toEqual({ passed: 3, repaired: 1, failed: 0 });
    expect(view?.coverage).toEqual({ covered: 4, total: 5, missing: ["小结"] });
    expect(view?.model).toBe("Qwen2.5-72B-Instruct");
    expect(view?.sourceCount).toBe(4);
    // 同一能力只保留一条，并优先使用带 MCP 服务器信息的记录
    expect(view?.capabilities).toEqual([
      { key: "READ_WORKSPACE_DOC", label: "读取资料", via: "" },
      { key: "EXTRACT_TRANSCRIPT", label: "提取字幕", via: "MCP · bilibili / get_subtitle" }
    ]);
  });

  it("hides the phase row for versions recorded before lifecycle tracing", () => {
    expect(buildArtifactProcessView(null)).toBeNull();
    expect(buildArtifactProcessView({ verification: { status: "WARN" } })).toMatchObject({
      steps: [],
      verification: "WARN",
      checks: null,
      coverage: null,
      capabilities: []
    });
  });
});
