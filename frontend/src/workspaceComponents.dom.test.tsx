// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ArtifactRail } from "./features/artifacts/ArtifactRail";
import { ResearchDetailWorkbench } from "./features/research/ResearchDetailWorkbench";
import { WorkspaceSettingsPanel } from "./features/workspace/WorkspaceSettingsPanel";

vi.mock("./features/memory/useMemoryItems", () => ({
  useMemoryItems: () => ({
    items: [],
    loading: false,
    error: "",
    pendingRevisionId: "",
    refresh: vi.fn(),
    addPreference: vi.fn(),
    decide: vi.fn()
  }),
  errorMessage: (error: unknown) => String(error)
}));

import { MemoryReviewWorkbench } from "./features/memory/MemoryReviewWorkbench";

afterEach(cleanup);

describe("workspace component wiring", () => {
  it("updates retrieval settings and adds a workspace member", async () => {
    const updateRetrievalSettings = vi.fn(async (_workspaceId: string, enabled: boolean) => ({
      workspace_id: "workspace",
      retrieval_strategy_v2_enabled: enabled
    }));
    const putMember = vi.fn(async (_workspaceId: string, userId: string, input: { role: string; status: string }) => ({
      user_id: userId,
      display_name: "New Member",
      role: input.role,
      status: input.status,
      updated_at: "2026-07-20T00:00:00Z"
    }));
    render(<WorkspaceSettingsPanel
      workspaceId="workspace"
      onClose={vi.fn()}
      showMembers
      api={{
        getRetrievalSettings: vi.fn(async () => ({
          workspace_id: "workspace",
          retrieval_strategy_v2_enabled: false
        })),
        updateRetrievalSettings,
        listMembers: vi.fn(async () => [{
          user_id: "owner",
          display_name: "Owner",
          role: "OWNER",
          status: "ACTIVE",
          updated_at: "2026-07-20T00:00:00Z"
        }]),
        putMember,
        removeMember: vi.fn()
      } as any}
    />);

    const retrievalToggle = await screen.findByRole("checkbox", { name: "已停用" });
    expect(document.querySelector(".workspace-toggle-track")).toBeTruthy();
    expect(document.querySelector(".workspace-select-control")).toBeTruthy();
    fireEvent.click(retrievalToggle);
    await waitFor(() => expect(updateRetrievalSettings).toHaveBeenCalledWith("workspace", true));

    fireEvent.change(screen.getByLabelText("用户 ID"), { target: { value: "member-1" } });
    fireEvent.change(screen.getByLabelText("角色"), { target: { value: "EDITOR" } });
    fireEvent.click(screen.getByRole("button", { name: "添加成员" }));
    expect(putMember).toHaveBeenCalledWith("workspace", "member-1", {
      role: "EDITOR",
      status: "ACTIVE"
    });
  });

  it("mounts the Artifact Studio composer", () => {
    const skill = {
      key: "study_guide",
      title: "学习指南",
      summary: "生成学习指南",
      tone: "blue",
      inputFields: [],
      defaultInputHints: []
    };
    render(<ArtifactRail {...({
      artifactComposerOpen: true,
      isBusy: false,
      selectedArtifactSkill: skill,
      artifactStudioSkills: [skill],
      artifactFormValues: {},
      artifactJobs: [],
      latestArtifactVersion: null,
      artifactHistoryLoadingKey: "",
      artifactSavedSourceByVersionId: {},
      artifactWritebackByVersionId: {},
      artifactCustomInstruction: "",
      workspace: { workspace_id: "workspace" },
      renderArtifactField: () => null,
      setArtifactComposerOpen: vi.fn(),
      setArtifactCustomInstruction: vi.fn(),
      appendArtifactHint: vi.fn(),
      prepareArtifactPrompt: vi.fn(),
      isArtifactFormReady: () => true,
      launchArtifactPrompt: vi.fn()
    } as any)} />);

    expect(screen.getByRole("heading", { name: "学习指南" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "生成产物" })).toBeTruthy();
  });

  it("opens the Artifact composer only after an explicit skill choice", () => {
    const setArtifactComposerOpen = vi.fn();
    const setSelectedArtifactSkillKey = vi.fn();
    const setArtifactFormValues = vi.fn();
    const skill = {
      key: "study_guide",
      title: "学习指南",
      summary: "生成学习指南",
      artifactType: "Study Guide",
      sourceHint: "当前工作台资料",
      runtimeHint: "异步生成",
      badges: [],
      tone: "gold",
      inputFields: [],
      defaultInputHints: []
    };

    render(<ArtifactRail {...buildArtifactOverviewProps({
      selectedArtifactSkill: skill,
      artifactStudioSkills: [skill],
      setArtifactComposerOpen,
      setSelectedArtifactSkillKey,
      setArtifactFormValues
    })} />);

    expect(screen.getByRole("heading", { name: "产物" })).toBeTruthy();
    expect(screen.queryByText("创建产物")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /学习指南/ }));
    expect(setSelectedArtifactSkillKey).toHaveBeenCalledWith("study_guide");
    expect(setArtifactFormValues).toHaveBeenCalledOnce();
    expect(setArtifactComposerOpen).toHaveBeenCalledWith(true);
  });

  it("shows a failed Artifact job with its reason instead of an openable version", () => {
    render(<ArtifactRail {...buildArtifactOverviewProps({
      artifactJobs: [{
        artifact_job_id: "failed-job",
        workspace_id: "workspace",
        task_id: "task-1",
        skill_key: "resume_highlight",
        status: "FAILED",
        task_status: "FAILED",
        progress_phase: "RESOLVING",
        progress_message: "source scope must not be empty",
        result_title: "",
        latest_version_no: 0,
        created_at: "2026-07-20T00:00:00Z",
        updated_at: "2026-07-20T00:00:00Z"
      }],
      resolveArtifactSkillTitle: () => "简历亮点描述"
    })} />);

    const row = document.querySelector('[data-run-key="artifact-job-failed-job"]');
    expect(row?.getAttribute("data-state")).toBe("failed");
    expect(row?.textContent).toContain("简历亮点描述");
    expect(row?.textContent).toContain("生成失败：source scope must not be empty");
    expect(screen.queryByRole("button", { name: "打开 简历亮点描述" })).toBeNull();
  });

  it("shows pending copy before an empty Artifact catalog or history", () => {
    render(<ArtifactRail {...buildArtifactOverviewProps({
      artifactSkillsLoading: true,
      artifactJobsLoading: true
    })} />);

    expect(screen.getByText("正在加载产物类型")).toBeTruthy();
    expect(screen.getByText("正在加载产物")).toBeTruthy();
    expect(screen.queryByText("产物目录暂不可用，无法创建产物任务。")).toBeNull();
    expect(screen.queryByText(/完成的产物和各个版本会保存在这里/)).toBeNull();
  });

  it("navigates the Research detail audit and checkpoint workflows", () => {
    const onSelectCheckpoint = vi.fn();
    const onResume = vi.fn();
    const onOpenWorkbench = vi.fn();
    const onSetSourceScope = vi.fn();
    const onFocusSource = vi.fn();
    const checkpoint = {
      checkpoint_no: 1,
      snapshot_type: "ROUND_END",
      object_key: "workspace/research/run/checkpoint-1.json",
      payload_sha256: "sha-1",
      content_size: 128,
      active_branch_id: "main",
      final_loop_decision: "CONTINUE",
      summary: {},
      local_verifier_status: "PASS",
      global_verifier_decision: "CONTINUE",
      verified_row_count: 2,
      conflicted_row_count: 0,
      counterfactual_summary: null,
      payload: {
        verified_findings: [{ evidence_id: "ev-1", source_title: "研究资料", claim_text: "已验证结论" }],
        evidence_cards: [{ evidence_id: "ev-1", source_title: "研究资料", claim_text: "证据摘要" }]
      },
      created_at: "2026-07-20T00:00:00Z"
    };
    render(<ResearchDetailWorkbench {...({
      run: {
        research_run_id: "run-1",
        workspace_id: "workspace",
        task_id: "task-1",
        question: "Research 能否形成可恢复闭环？",
        profile_key: "default",
        research_intent: {},
        resumed_from_research_run_id: "",
        resumed_from_checkpoint_no: null,
        status: "COMPLETED",
        final_report_title: "Research 闭环报告",
        final_report_markdown: "# Research 闭环报告",
        report_structure: null,
        counterfactual_summary: null,
        research_process_summary: {
          source_scope_count: 1,
          search_read_timeline: {
            loop_round_count: 1,
            total_search_hit_count: 3,
            total_read_window_count: 2,
            total_evidence_card_count: 1,
            all_search_queries: ["Research closed loop"],
            final_loop_decision: "STOP_AND_WRITE",
            final_loop_reason: "evidence ready",
            terminal_disposition: "REPORT_READY",
            handoff_required: false,
            abandon_reason: "",
            rounds: [{ round_no: 1, search_hit_count: 3, read_window_count: 2, evidence_card_count: 1, search_queries: ["Research closed loop"], evidence_ids: ["ev-1"], branch_decision: "KEEP", global_decision: "STOP_AND_WRITE" }]
          },
          source_evidence_summary: { source_basis: "WORKSPACE", primary_quality: "HIGH", quality_mix_label: "HIGH", read_strategy_mix_label: "FULL", fetch_foundation_label: "SEARCH", orchestration_foundation_label: "AGENT", verified_finding_count: 1, citation_count: 1 },
          audit_summary: { local_verifier_status: "PASS", global_verifier_decision: "STOP_AND_WRITE", final_loop_decision: "STOP_AND_WRITE", has_counterfactual_recheck: false, counterfactual_branch_count: 0, checkpoint_count: 1, blocked_row_count: 0, conflicted_row_count: 0, guardrailed_row_count: 0, recovery_target_count: 0 }
        },
        trace_summary: "闭环已完成",
        source_scope: [{ source_id: "source-1", title: "研究资料" }],
        control_pack: {},
        saved_report_source: {
          source_id: "source-1",
          title: "Research 闭环报告",
          source_type: "MARKDOWN",
          status: "READY",
          parse_status: "PARSED",
          index_status: "INDEXED",
          generated_by: "RESEARCH",
          generated_ref_id: "run-1"
        },
        closed_loop_state: {
          active_branch_id: "main",
          local_verifier_status: "PASS",
          global_verifier_decision: "STOP_AND_WRITE",
          final_loop_decision: "STOP_AND_WRITE",
          loop_rounds_count: 1,
          ledger_row_count: 2,
          branch_count: 1,
          verifier_decision_count: 1,
          harness_summary: {},
          checkpoint_candidate: {},
          counterfactual_summary: null,
          state_ledger: { verified_row_count: 2, conflicted_row_count: 0 },
          local_verifier: {},
          global_verifier: {},
          branches: [],
          rows: [],
          cells: [],
          verifier_decisions: [],
          checkpoints: [checkpoint],
          source_evidence: [],
          cell_evidence: [],
          branch_decisions: [],
          loop_rounds: [{ round_no: 1, branch_id: "main" }],
          loop_decision_payload: {}
        },
        traces: [],
        created_at: "2026-07-20T00:00:00Z",
        updated_at: "2026-07-20T00:00:00Z"
      },
      selectedCheckpointNo: 1,
      selectedCheckpoint: checkpoint,
      comparisonCheckpointNo: null,
      comparisonCheckpoint: null,
      isBusy: false,
      onClose: vi.fn(),
      onRefresh: vi.fn(),
      onSaveAsSource: vi.fn(),
      onOpenWorkbench,
      onSetSourceScope,
      onFocusSource,
      onSelectCheckpoint,
      onSelectComparison: vi.fn(),
      onResume
    } as any)} />);

    fireEvent.click(screen.getByRole("button", { name: "打开研究工作台" }));
    fireEvent.click(screen.getByRole("button", { name: "移出当前 source scope" }));
    fireEvent.click(screen.getByRole("button", { name: "定位到 source scope" }));
    expect(onOpenWorkbench).toHaveBeenCalledOnce();
    expect(onSetSourceScope).toHaveBeenCalledWith("source-1", true);
    expect(onFocusSource).toHaveBeenCalledWith("source-1");

    fireEvent.click(screen.getByRole("button", { name: "审计" }));
    expect(screen.getByText("Formal Audit Summary")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Checkpoint" }));
    expect(screen.getByText("Checkpoint Provenance")).toBeTruthy();
    expect(screen.getByText("Snapshot Evidence Ledger")).toBeTruthy();
    expect(screen.getByText("Checkpoint To Current Diff")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "回放" }));
    fireEvent.click(screen.getByRole("button", { name: "从此恢复" }));

    expect(onSelectCheckpoint).toHaveBeenCalledWith(1, null);
    expect(onResume).toHaveBeenCalledWith(1);
  });

  it("mounts the Memory workbench empty state", () => {
    render(<MemoryReviewWorkbench workspaceId="workspace" />);

    expect(screen.getByRole("heading", { name: "记忆" })).toBeTruthy();
    expect(screen.getByRole("form", { name: "添加偏好" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "生效中" })).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "待确认" })).toBeNull();
  });
});

function buildArtifactOverviewProps(overrides: Record<string, unknown> = {}) {
  return {
    artifactComposerOpen: false,
    isBusy: false,
    selectedArtifactSkill: undefined,
    artifactStudioSkills: [],
    artifactSkillsLoading: false,
    artifactJobsLoading: false,
    sourceCount: 2,
    artifactFormValues: {},
    artifactJobs: [],
    selectedArtifactHistoryVersion: null,
    latestArtifactVersion: null,
    artifactHistoryLoadingKey: "",
    artifactSavedSourceByVersionId: {},
    artifactWritebackByVersionId: {},
    artifactCustomInstruction: "",
    workspace: { workspace_id: "workspace", name: "研究工作台", status: "ACTIVE" },
    renderArtifactField: () => null,
    setArtifactComposerOpen: vi.fn(),
    setSelectedArtifactSkillKey: vi.fn(),
    setArtifactFormValues: vi.fn(),
    setArtifactCustomInstruction: vi.fn(),
    appendArtifactHint: vi.fn(),
    prepareArtifactPrompt: vi.fn(),
    isArtifactFormReady: () => true,
    launchArtifactPrompt: vi.fn(),
    formatRelativeTime: (value: string) => value,
    resolveArtifactSkillTitle: (key: string) => key,
    openArtifactHistoryVersion: vi.fn(),
    saveArtifactVersionAsSource: vi.fn(),
    artifactVersionSaveKey: vi.fn(),
    writeArtifactVersionToKnowledge: vi.fn(),
    regenerateArtifactVersion: vi.fn(),
    compareArtifactWithPreviousVersion: vi.fn(),
    rollbackArtifactVersion: vi.fn(),
    downloadArtifactVersionPdf: vi.fn(),
    sourceDraftTitle: "",
    setSourceDraftTitle: vi.fn(),
    sourceDraftContent: "",
    setSourceDraftContent: vi.fn(),
    sourceDraftRewriteMode: "",
    rewriteNoteSourceDraft: vi.fn(),
    saveNoteAnswerAsSource: vi.fn(),
    lastNoteAssistantMessageId: "",
    ...overrides
  } as any;
}
