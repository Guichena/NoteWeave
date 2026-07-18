import { useCallback, useEffect, useReducer, useRef } from "react";
import { researchApi, type ResearchApi } from "./api";
import {
  type ResearchCheckpoint,
  type ResearchRunDetail,
  type ResearchRunSummary
} from "./model";
import {
  createResearchServerState,
  LatestResearchRequestGate,
  reduceResearchServerState
} from "./state";

export type UseResearchStateOptions = {
  workspaceId: string;
};

export function useResearchState(options: UseResearchStateOptions, api: ResearchApi = researchApi) {
  const [state, dispatch] = useReducer(
    reduceResearchServerState<ResearchRunSummary, ResearchRunDetail, ResearchCheckpoint>,
    options.workspaceId,
    createResearchServerState<ResearchRunSummary, ResearchRunDetail, ResearchCheckpoint>
  );
  const gateRef = useRef(new LatestResearchRequestGate());
  const gate = gateRef.current;

  useEffect(() => {
    gate.setWorkspace(options.workspaceId);
    dispatch({ type: "workspace", workspaceId: options.workspaceId });
  }, [gate, options.workspaceId]);

  useEffect(() => () => gate.cancelAll(), [gate]);

  const visibleState = state.workspaceId === options.workspaceId
    ? state
    : createResearchServerState<ResearchRunSummary, ResearchRunDetail, ResearchCheckpoint>(options.workspaceId);

  const clear = useCallback(() => {
    gate.cancelAll();
    dispatch({ type: "clear" });
  }, [gate]);

  const prepareRun = useCallback((researchRunId: string) => {
    gate.cancel("run");
    gate.cancel("checkpoint");
    gate.cancel("comparison");
    dispatch({ type: "prepare-run", researchRunId });
  }, [gate]);

  const loadHistory = useCallback(async () => {
    const workspaceId = options.workspaceId;
    if (!workspaceId) {
      return [] as ResearchRunSummary[];
    }
    const lease = gate.begin("history", workspaceId);
    try {
      const runs = await api.listRuns(workspaceId, { signal: lease.signal });
      if (lease.isCurrent()) {
        dispatch({ type: "runs", runs });
      }
      return runs;
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId]);

  const loadRunDetail = useCallback(async (researchRunId: string, checkpointNo?: number | null) => {
    const workspaceId = options.workspaceId;
    if (!workspaceId) {
      return null;
    }
    const priorState = visibleState;
    const targetCheckpointNo = checkpointNo ?? (
      researchRunId === priorState.currentResearchRunId
        ? priorState.selectedResearchCheckpointNo
        : null
    );
    const lease = gate.begin("run", workspaceId);
    try {
      const detail = await api.getRun(workspaceId, researchRunId, { signal: lease.signal });
      const request = { signal: lease.signal };
      const [resumeSourceCheckpoint, selectedCheckpoint] = await Promise.all([
        detail.resumed_from_research_run_id && detail.resumed_from_checkpoint_no != null
          ? api.getCheckpoint(
            workspaceId,
            detail.resumed_from_research_run_id,
            detail.resumed_from_checkpoint_no,
            request
          )
          : Promise.resolve(null),
        targetCheckpointNo != null
          ? api.getCheckpoint(workspaceId, researchRunId, targetCheckpointNo, request)
          : Promise.resolve(null)
      ]);
      if (lease.isCurrent()) {
        dispatch({
          type: "run-snapshot",
          snapshot: {
            detail,
            selectedCheckpointNo: targetCheckpointNo,
            selectedCheckpoint,
            resumeSourceCheckpoint,
            clearComparison: researchRunId !== priorState.currentResearchRunId
          }
        });
      }
      return detail;
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId, visibleState]);

  const selectCheckpoint = useCallback(async (
    researchRunId: string,
    checkpointNo: number,
    comparisonNo: number | null
  ) => {
    const workspaceId = options.workspaceId;
    if (!workspaceId) {
      return null;
    }
    const lease = gate.begin("checkpoint", workspaceId);
    try {
      const request = { signal: lease.signal };
      const [checkpoint, comparison] = await Promise.all([
        api.getCheckpoint(workspaceId, researchRunId, checkpointNo, request),
        comparisonNo != null
          ? api.getCheckpoint(workspaceId, researchRunId, comparisonNo, request)
          : Promise.resolve(null)
      ]);
      if (lease.isCurrent()) {
        dispatch({
          type: "checkpoint-selection",
          checkpointNo,
          checkpoint,
          comparisonNo,
          comparison
        });
      }
      return checkpoint;
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId]);

  const selectComparison = useCallback(async (researchRunId: string, checkpointNo: number | null) => {
    if (checkpointNo == null) {
      gate.cancel("comparison");
      dispatch({ type: "checkpoint-comparison", checkpointNo: null, checkpoint: null });
      return null;
    }
    const workspaceId = options.workspaceId;
    if (!workspaceId) {
      return null;
    }
    const lease = gate.begin("comparison", workspaceId);
    try {
      const checkpoint = await api.getCheckpoint(workspaceId, researchRunId, checkpointNo, {
        signal: lease.signal
      });
      if (lease.isCurrent()) {
        dispatch({ type: "checkpoint-comparison", checkpointNo, checkpoint });
      }
      return checkpoint;
    } finally {
      lease.complete();
    }
  }, [api, gate, options.workspaceId]);

  return {
    ...visibleState,
    clear,
    prepareRun,
    loadHistory,
    loadRunDetail,
    selectCheckpoint,
    selectComparison
  };
}
