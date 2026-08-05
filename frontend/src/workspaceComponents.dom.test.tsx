// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ArtifactRail } from "./features/artifacts/ArtifactRail";
import { ResearchDetailWorkbench } from "./features/research/ResearchDetailWorkbench";
import { ResearchReportPanel } from "./features/research/ResearchReportPanel";
import { ResearchSidebar } from "./features/research/ResearchSidebar";
import { WorkspaceSettingsPanel } from "./features/workspace/WorkspaceSettingsPanel";

vi.mock("./features/memory/useMemoryReview", () => ({
  useMemoryReview: () => ({
    queue: [],
    selectedReviewId: "",
    versions: [],
    selectedItem: null,
    selectedVersion: null,
    queueLoading: false,
    versionsLoading: false,
    mutating: false,
    error: "",
    lastDecision: null,
    refreshQueue: vi.fn(),
    selectReview: vi.fn(),
    decide: vi.fn(),
    appendVersion: vi.fn(),
    selectVersion: vi.fn()
  })
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

    const retrievalToggle = await screen.findByRole("checkbox", { name: "停用" });
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
      artifactSidebarState: {},
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
    expect(screen.getByRole("button", { name: "生成" })).toBeTruthy();
  });

  it("mounts the Research launcher with an empty history", () => {
    render(<ResearchSidebar {...({
      summarizedResearchQuestion: "研究问题",
      summarizedResearchGoal: "研究目标",
      researchDeliverableFormat: "报告",
      researchProfile: "default",
      researchDepth: "STANDARD",
      researchType: "AUTO",
      researchRetrievalMode: "WEB_ONLY",
      researchScopeCount: 0,
      researchQuestion: "研究问题",
      researchGoal: "研究目标",
      researchTimeRange: "",
      researchConstraintsText: "",
      isBusy: false,
      workspace: { workspace_id: "workspace" },
      sources: [],
      selectedResearchSourceIds: [],
      researchScopeSources: [],
      researchRuns: [],
      filteredResearchRuns: [],
      researchHistoryFilter: "ALL",
      setResearchHistoryFilter: vi.fn(),
      researchHistoryFilterLabel: (filter: string) => filter,
      researchTimelineMilestones: [],
      researchTimelinePath: {
        stageLabels: [],
        currentStageLabel: "",
        currentRunStageLabel: "",
        currentRunAlignedWithPath: false,
        narrative: ""
      },
      runContinuityBaselineById: new Map(),
      currentResearchRunSummary: null,
      currentResearchRunId: "",
      currentSavedReportSource: null,
      currentSavedReportSourceInScope: false,
      focusedResearchSourceId: "",
      buildRunSignalChips: () => [],
      buildRunPrimaryTone: () => "neutral",
      readRecoveryTargets: () => null
    } as any)} />);

    expect(screen.getByRole("heading", { name: "独立研究工作台" })).toBeTruthy();
  });

  it("mounts the Research report empty state", () => {
    render(<ResearchReportPanel {...({
      currentResearchRun: null,
      currentResearchCollection: null,
      currentResearchRunSummary: null,
      researchReportStructure: null,
      researchClosedLoopState: null,
      currentResearchSourceEvidenceSummary: null,
      currentRecoveryTargets: null,
      currentRunSummaryRecoveryTargets: null,
      currentIntentCompletionContract: null,
      currentFinalAnswer: {},
      currentExecutiveSummary: [],
      currentKeyTakeaways: [],
      currentUncertaintyAndRisks: [],
      currentVerifiedFindings: [],
      currentEvidenceHighlights: [],
      currentSavedReportSource: null,
      currentSavedReportSourceAsset: null,
      sourceById: new Map(),
      researchTimelinePath: {
        stageLabels: [],
        currentStageLabel: "",
        currentRunStageLabel: "",
        currentRunAlignedWithPath: false,
        narrative: ""
      }
    } as any)} />);

    expect(screen.getByText("Research Run")).toBeTruthy();
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

  it("mounts the Memory review workbench empty state", () => {
    render(<MemoryReviewWorkbench workspaceId="workspace" />);

    expect(screen.getByText("Memory Review")).toBeTruthy();
  });
});
