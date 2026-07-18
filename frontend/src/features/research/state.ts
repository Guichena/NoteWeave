export type ResearchRunIdentity = {
  research_run_id: string;
};

export type ResearchRunDetailIdentity = ResearchRunIdentity & {
  resumed_from_research_run_id: string;
  resumed_from_checkpoint_no: number | null;
};

export type ResearchServerState<
  Run extends ResearchRunIdentity,
  Detail extends ResearchRunDetailIdentity,
  Checkpoint
> = {
  workspaceId: string;
  revision: number;
  researchRuns: Run[];
  currentResearchRunId: string;
  currentResearchRun: Detail | null;
  selectedResearchCheckpointNo: number | null;
  selectedResearchCheckpoint: Checkpoint | null;
  compareResearchCheckpointNo: number | null;
  compareResearchCheckpoint: Checkpoint | null;
  resumeSourceCheckpoint: Checkpoint | null;
};

export type ResearchRunSnapshot<
  Detail extends ResearchRunDetailIdentity,
  Checkpoint
> = {
  detail: Detail;
  selectedCheckpointNo: number | null;
  selectedCheckpoint: Checkpoint | null;
  resumeSourceCheckpoint: Checkpoint | null;
  clearComparison: boolean;
};

export type ResearchStateAction<
  Run extends ResearchRunIdentity,
  Detail extends ResearchRunDetailIdentity,
  Checkpoint
> =
  | { type: "workspace"; workspaceId: string }
  | { type: "runs"; runs: Run[] }
  | { type: "prepare-run"; researchRunId: string }
  | { type: "run-snapshot"; snapshot: ResearchRunSnapshot<Detail, Checkpoint> }
  | {
    type: "checkpoint-selection";
    checkpointNo: number;
    checkpoint: Checkpoint;
    comparisonNo: number | null;
    comparison: Checkpoint | null;
  }
  | { type: "checkpoint-comparison"; checkpointNo: number | null; checkpoint: Checkpoint | null }
  | { type: "clear" };

export function createResearchServerState<
  Run extends ResearchRunIdentity,
  Detail extends ResearchRunDetailIdentity,
  Checkpoint
>(workspaceId = ""): ResearchServerState<Run, Detail, Checkpoint> {
  return {
    workspaceId,
    revision: 0,
    researchRuns: [],
    currentResearchRunId: "",
    currentResearchRun: null,
    selectedResearchCheckpointNo: null,
    selectedResearchCheckpoint: null,
    compareResearchCheckpointNo: null,
    compareResearchCheckpoint: null,
    resumeSourceCheckpoint: null
  };
}

export function reduceResearchServerState<
  Run extends ResearchRunIdentity,
  Detail extends ResearchRunDetailIdentity,
  Checkpoint
>(
  state: ResearchServerState<Run, Detail, Checkpoint>,
  action: ResearchStateAction<Run, Detail, Checkpoint>
): ResearchServerState<Run, Detail, Checkpoint> {
  switch (action.type) {
    case "workspace":
      return action.workspaceId === state.workspaceId
        ? state
        : createResearchServerState<Run, Detail, Checkpoint>(action.workspaceId);
    case "runs":
      return { ...state, revision: state.revision + 1, researchRuns: action.runs };
    case "prepare-run":
      return {
        ...state,
        revision: state.revision + 1,
        currentResearchRunId: action.researchRunId,
        currentResearchRun: null,
        selectedResearchCheckpointNo: null,
        selectedResearchCheckpoint: null,
        compareResearchCheckpointNo: null,
        compareResearchCheckpoint: null,
        resumeSourceCheckpoint: null
      };
    case "run-snapshot":
      return {
        ...state,
        revision: state.revision + 1,
        currentResearchRunId: action.snapshot.detail.research_run_id,
        currentResearchRun: action.snapshot.detail,
        selectedResearchCheckpointNo: action.snapshot.selectedCheckpointNo,
        selectedResearchCheckpoint: action.snapshot.selectedCheckpoint,
        compareResearchCheckpointNo: action.snapshot.clearComparison ? null : state.compareResearchCheckpointNo,
        compareResearchCheckpoint: action.snapshot.clearComparison ? null : state.compareResearchCheckpoint,
        resumeSourceCheckpoint: action.snapshot.resumeSourceCheckpoint
      };
    case "checkpoint-selection":
      return {
        ...state,
        revision: state.revision + 1,
        selectedResearchCheckpointNo: action.checkpointNo,
        selectedResearchCheckpoint: action.checkpoint,
        compareResearchCheckpointNo: action.comparisonNo,
        compareResearchCheckpoint: action.comparison
      };
    case "checkpoint-comparison":
      return {
        ...state,
        revision: state.revision + 1,
        compareResearchCheckpointNo: action.checkpointNo,
        compareResearchCheckpoint: action.checkpoint
      };
    case "clear":
      return createResearchServerState<Run, Detail, Checkpoint>(state.workspaceId);
  }
}

type RequestLease = {
  signal: AbortSignal;
  isCurrent: () => boolean;
  complete: () => void;
};

export class LatestResearchRequestGate {
  private workspaceId = "";
  private readonly controllers = new Map<string, AbortController>();

  setWorkspace(workspaceId: string) {
    if (workspaceId === this.workspaceId) {
      return;
    }
    this.workspaceId = workspaceId;
    this.cancelAll();
  }

  begin(channel: string, workspaceId = this.workspaceId): RequestLease {
    this.cancel(channel);
    const controller = new AbortController();
    this.controllers.set(channel, controller);
    return {
      signal: controller.signal,
      isCurrent: () => (
        !controller.signal.aborted
        && workspaceId === this.workspaceId
        && this.controllers.get(channel) === controller
      ),
      complete: () => {
        if (this.controllers.get(channel) === controller) {
          this.controllers.delete(channel);
        }
      }
    };
  }

  cancel(channel: string) {
    this.controllers.get(channel)?.abort();
    this.controllers.delete(channel);
  }

  cancelAll() {
    this.controllers.forEach((controller) => controller.abort());
    this.controllers.clear();
  }
}
