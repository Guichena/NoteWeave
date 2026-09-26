// @vitest-environment jsdom

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { researchApi } from "./api";
import { useResearchReportActions } from "./useResearchReportActions";

describe("useResearchReportActions", () => {
  it("refreshes workspace sources after saving a Research report", async () => {
    const saveReportAsSource = vi.spyOn(researchApi, "saveReportAsSource").mockResolvedValue({
      source_id: "source-report",
      source_type: "GENERATED_RESEARCH_REPORT",
      status: "READY",
      parse_status: "PARSED",
      index_status: "DISABLED",
      generated_by: "research_agent",
      generated_ref_id: "run-1",
      title: "Research report"
    } as never);
    const sourceApi = {
      list: vi.fn(async () => [{ source_id: "source-report", title: "Research report" }])
    };
    const replaceSources = vi.fn();
    const loadResearchRunDetail = vi.fn(async () => null);
    const loadResearchRunHistory = vi.fn(async () => []);
    const appendSystemMessage = vi.fn();
    const run = vi.fn(async (_label: string, action: () => Promise<void>) => action());

    const { result } = renderHook(() => useResearchReportActions({
      workspace: { workspace_id: "workspace-1", name: "QA", status: "ACTIVE" },
      currentResearchRunId: "run-1",
      currentResearchRun: null,
      currentResearchRunSummary: null,
      currentRunSummaryRecoveryTargets: null,
      run,
      setStatus: vi.fn(),
      appendSystemMessage,
      replaceSources,
      loadResearchRunDetail,
      loadResearchRunHistory,
      sourceApi: sourceApi as never
    }));

    await act(async () => {
      await result.current.saveResearchReportAsSource();
    });

    expect(saveReportAsSource).toHaveBeenCalledWith("workspace-1", "run-1");
    expect(sourceApi.list).toHaveBeenCalledWith("workspace-1");
    expect(replaceSources).toHaveBeenCalledWith([
      { source_id: "source-report", title: "Research report" }
    ]);
    expect(loadResearchRunDetail).toHaveBeenCalledWith("run-1");
    expect(loadResearchRunHistory).toHaveBeenCalledWith("run-1");
    expect(appendSystemMessage).toHaveBeenCalledWith(expect.stringContaining("已写回资料库"));

    saveReportAsSource.mockRestore();
  });
});
