import { describe, expect, it } from "vitest";
import {
  createResearchServerState,
  LatestResearchRequestGate,
  reduceResearchServerState
} from "./state";

type Run = { research_run_id: string };
type Detail = Run & {
  resumed_from_research_run_id: string;
  resumed_from_checkpoint_no: number | null;
};

describe("Research server state", () => {
  it("clears every server projection when workspace changes", () => {
    const initial = createResearchServerState<Run, Detail, string>("workspace-a");
    const populated = reduceResearchServerState(initial, {
      type: "run-snapshot",
      snapshot: {
        detail: {
          research_run_id: "run-a",
          resumed_from_research_run_id: "",
          resumed_from_checkpoint_no: null
        },
        selectedCheckpointNo: 2,
        selectedCheckpoint: "checkpoint-2",
        resumeSourceCheckpoint: null,
        clearComparison: true
      }
    });
    const changed = reduceResearchServerState(populated, { type: "workspace", workspaceId: "workspace-b" });

    expect(changed).toEqual(createResearchServerState<Run, Detail, string>("workspace-b"));
  });

  it("resets checkpoint projections before a new run is loaded", () => {
    const state = reduceResearchServerState(
      createResearchServerState<Run, Detail, string>("workspace"),
      {
        type: "checkpoint-selection",
        checkpointNo: 4,
        checkpoint: "checkpoint-4",
        comparisonNo: 3,
        comparison: "checkpoint-3"
      }
    );
    const next = reduceResearchServerState(state, { type: "prepare-run", researchRunId: "run-next" });

    expect(next.currentResearchRunId).toBe("run-next");
    expect(next.selectedResearchCheckpoint).toBeNull();
    expect(next.compareResearchCheckpoint).toBeNull();
  });

  it("aborts stale same-channel requests and all requests on workspace replacement", () => {
    const gate = new LatestResearchRequestGate();
    gate.setWorkspace("workspace-a");
    const first = gate.begin("run", "workspace-a");
    const second = gate.begin("run", "workspace-a");

    expect(first.signal.aborted).toBe(true);
    expect(second.isCurrent()).toBe(true);

    gate.setWorkspace("workspace-b");
    expect(second.signal.aborted).toBe(true);
  });
});
