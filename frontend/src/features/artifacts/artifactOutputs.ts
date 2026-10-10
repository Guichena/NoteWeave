import { buildWaitContextNarrative, resolveRunStatus } from "../../runStatus";
import type { ArtifactRuntimeTrace } from "./artifactRuntimeTrace";
import type { ArtifactStudioSkill } from "./artifactStudio";
import type { ArtifactJobSummary } from "./model";

// 产物生成的五个阶段，与 Artifact Worker 的 PHASE_SEQUENCE 保持一致。
export const ARTIFACT_PHASES = [
  { key: "RESOLVING", label: "解析请求" },
  { key: "ACQUIRING", label: "准备素材" },
  { key: "COMPOSING", label: "分段生成" },
  { key: "VERIFYING", label: "校验输出" },
  { key: "EXPORTING", label: "导出文件" }
] as const;

const WAIT_LABELS: Record<string, string> = {
  WAITING_FOR_PROVIDER: "等待外部服务",
  WAITING_FOR_APPROVAL: "等待授权",
  WAITING_FOR_CAPABILITY: "等待能力就绪"
};

// 能力名称来自技能目录的 capability_allowlist。
const CAPABILITY_LABELS: Record<string, string> = {
  READ_WORKSPACE_DOC: "读取资料",
  READ_WEB_PAGE: "读取网页",
  GENERATE_STRUCTURED_TEXT: "结构化生成",
  VERIFY_OUTPUT: "输出校验",
  EXTRACT_TRANSCRIPT: "提取字幕",
  TRANSCRIBE_AUDIO: "语音转写",
  CAPTURE_VIDEO_FRAMES: "视频截帧",
  ANALYZE_FRAME: "画面理解"
};

export type ArtifactOutputState = "queued" | "running" | "waiting" | "failed" | "cancelled" | "ready";

export type ArtifactOutputItem = {
  key: string;
  artifactJobId: string;
  skillKey: string;
  title: string;
  typeLabel: string;
  versionNo: number;
  state: ArtifactOutputState;
  statusLabel: string;
  /** 当前所处阶段在 ARTIFACT_PHASES 中的位置，未知时为 -1。 */
  phaseIndex: number;
  /** 失败原因或等待原因，面向用户的简短说明。 */
  detail: string;
  /** 等待上下文的技术细节，只放在悬停提示里。 */
  technicalDetail: string;
  updatedAt: string;
};

export function isArtifactOutputActive(item: Pick<ArtifactOutputItem, "state">) {
  return item.state === "queued" || item.state === "running" || item.state === "waiting";
}

export function buildArtifactOutputItems(
  jobs: ArtifactJobSummary[],
  resolveSkillTitle: (skillKey: string) => string
): ArtifactOutputItem[] {
  return jobs
    .map((job) => buildArtifactOutputItem(job, resolveSkillTitle))
    .sort((left, right) => {
      const activeOrder = Number(isArtifactOutputActive(right)) - Number(isArtifactOutputActive(left));
      return activeOrder || String(right.updatedAt).localeCompare(String(left.updatedAt));
    });
}

function buildArtifactOutputItem(
  job: ArtifactJobSummary,
  resolveSkillTitle: (skillKey: string) => string
): ArtifactOutputItem {
  const status = resolveRunStatus(job.task_status, job.status);
  const phase = normalize(job.progress_phase);
  const typeLabel = resolveSkillTitle(job.skill_key);
  const state = resolveOutputState(status, phase);
  const waitLabel = WAIT_LABELS[status] ?? WAIT_LABELS[phase] ?? "等待外部服务";
  const phaseIndex = state === "waiting"
    ? 1
    : ARTIFACT_PHASES.findIndex((item) => item.key === phase);
  const statusLabel = {
    queued: "排队中",
    running: phaseIndex >= 0 ? ARTIFACT_PHASES[phaseIndex].label : "生成中",
    waiting: waitLabel,
    failed: "生成失败",
    cancelled: "已取消",
    ready: ""
  }[state];

  return {
    key: `artifact-job-${job.artifact_job_id}`,
    artifactJobId: job.artifact_job_id,
    skillKey: job.skill_key,
    title: job.result_title.trim() || typeLabel,
    typeLabel,
    versionNo: job.latest_version_no,
    state,
    statusLabel,
    phaseIndex,
    detail: state === "failed" ? job.progress_message.trim() : "",
    technicalDetail: buildWaitContextNarrative(job.wait_context),
    updatedAt: job.updated_at
  };
}

function resolveOutputState(status: string, phase: string): ArtifactOutputState {
  if (status === "FAILED") return "failed";
  if (status === "CANCELLED") return "cancelled";
  if (status === "COMPLETED" || status === "READY" || status === "SUCCEEDED") return "ready";
  if (status.startsWith("WAITING") || phase.startsWith("WAITING_FOR_")) return "waiting";
  if (status === "QUEUED" || status === "PENDING") return "queued";
  return "running";
}

export type ArtifactProcessStepStatus = "done" | "active" | "failed" | "pending";

export type ArtifactProcessView = {
  steps: Array<{ key: string; label: string; status: ArtifactProcessStepStatus }>;
  /** Verifier 结论：PASS / WARN / FAIL。 */
  verification: "PASS" | "WARN" | "FAIL" | "";
  checks: { passed: number; repaired: number; failed: number } | null;
  coverage: { covered: number; total: number; missing: string[] } | null;
  capabilities: Array<{ key: string; label: string; via: string }>;
  model: string;
  sourceCount: number;
};

export function buildArtifactProcessView(trace?: ArtifactRuntimeTrace | null): ArtifactProcessView | null {
  if (!trace) return null;

  // 早期版本没有生命周期记录，此时不展示阶段条。
  const recordedSteps = trace.lifecycle_trace?.steps ?? [];
  const recorded = new Map(recordedSteps.map((step) => [normalize(step.phase), normalize(step.status)]));
  const steps = recordedSteps.length === 0 ? [] : ARTIFACT_PHASES.map((phase) => {
    const stepStatus = recorded.get(phase.key);
    let status: ArtifactProcessStepStatus = "pending";
    if (stepStatus === "COMPLETED") status = "done";
    else if (stepStatus === "FAILED") status = "failed";
    else if (stepStatus) status = "active";
    return { key: phase.key, label: phase.label, status };
  });

  const contract = trace.output_contract_trace;
  const checks = contract
    ? {
        passed: contract.passed_checks?.length ?? 0,
        repaired: contract.repaired_checks?.length ?? contract.repair_summary?.total_repair_count ?? 0,
        failed: contract.failed_checks?.length ?? 0
      }
    : null;

  const coverageTrace = trace.evidence_coverage;
  const coverage = coverageTrace && typeof coverageTrace.section_count === "number" && coverageTrace.section_count > 0
    ? {
        covered: coverageTrace.covered_section_count ?? 0,
        total: coverageTrace.section_count,
        missing: coverageTrace.sections_missing_evidence ?? []
      }
    : null;

  const verification = normalize(trace.verification?.status);

  return {
    steps,
    verification: verification === "PASS" || verification === "WARN" || verification === "FAIL" ? verification : "",
    checks,
    coverage,
    capabilities: buildCapabilityList(trace),
    model: trace.generation_trace?.model?.trim() ?? "",
    sourceCount: trace.generation_trace?.source_count ?? 0
  };
}

function buildCapabilityList(trace: ArtifactRuntimeTrace): ArtifactProcessView["capabilities"] {
  const decisions = [
    ...(trace.capability_union_trace?.capability_decisions ?? []),
    ...(trace.approval_trace?.capability_decisions ?? [])
  ];
  const seen = new Map<string, { key: string; label: string; via: string }>();
  for (const decision of decisions) {
    const name = normalize(decision.capability_name);
    if (!name) continue;
    const server = decision.server_id?.trim() ?? "";
    const tool = decision.tool_name?.trim() ?? "";
    const via = server ? `MCP · ${[server, tool].filter(Boolean).join(" / ")}` : "";
    const existing = seen.get(name);
    if (!existing || (!existing.via && via)) {
      seen.set(name, { key: name, label: CAPABILITY_LABELS[name] ?? name, via });
    }
  }
  return [...seen.values()];
}

export type ArtifactSkillGroup = {
  key: "sources" | "media";
  label: string;
  skills: ArtifactStudioSkill[];
};

// 这些产物需要音视频链接或录音资料，单独归为一组。
const MEDIA_SKILL_KEYS = new Set(["video_summary", "audio_minutes", "course_notes", "bilibili_course_note_pdf"]);

/**
 * 按输入来源分组展示产物类型。视频学习的衍生产物需要先准备好视频素材，
 * 只能从视频学习入口创建，这里不单独展示。
 */
export function groupArtifactSkills(skills: ArtifactStudioSkill[]): ArtifactSkillGroup[] {
  const creatable = skills.filter((skill) => !requiresVideoMaterial(skill));
  const groups: ArtifactSkillGroup[] = [
    { key: "sources", label: "基于资料", skills: creatable.filter((skill) => !isMediaSkill(skill)) },
    { key: "media", label: "音视频", skills: creatable.filter(isMediaSkill) }
  ];
  return groups.filter((group) => group.skills.length > 0);
}

/** 分组以技能目录为准，旧版后端没有分组时按内置的音视频产物判断。 */
function isMediaSkill(skill: ArtifactStudioSkill) {
  return skill.group ? skill.group === "media" : MEDIA_SKILL_KEYS.has(skill.key);
}

function requiresVideoMaterial(skill: ArtifactStudioSkill) {
  if (skill.group === "video_material") return true;
  const required = skill.inputSchema?.required;
  return Array.isArray(required) && required.includes("video_material_bundle_id");
}

function normalize(value?: string | null) {
  return (value ?? "").trim().toUpperCase();
}
