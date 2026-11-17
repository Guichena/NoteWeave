import { useCallback, useEffect, useRef, useState } from "react";
import { memoryApi, type MemoryApi } from "./api";
import type { MemoryItem, MemoryReviewDecision } from "./model";
import { useOperationScope } from "../../shared/useOperationScope";

export type AddPreferenceInput = {
  text: string;
  kind: "PREFERENCE" | "NEGATIVE";
  neighborhood: string;
};

export function useMemoryItems(workspaceId: string, api: MemoryApi = memoryApi) {
  const [items, setItems] = useState<MemoryItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [pendingRevisionId, setPendingRevisionId] = useState("");
  // 切换工作台时丢弃旧请求的结果
  const requestRef = useRef(0);
  const isCurrent = useOperationScope(workspaceId);

  const refresh = useCallback(async () => {
    if (!isCurrent()) return [];
    const requestId = ++requestRef.current;
    if (!workspaceId) {
      setItems([]);
      return [];
    }
    setLoading(true);
    try {
      const next = await api.listItems(workspaceId);
      if (isCurrent() && requestId === requestRef.current) {
        setItems(next);
        setError("");
      }
      return next;
    } catch (failure) {
      if (isCurrent() && requestId === requestRef.current) setError(errorMessage(failure));
      return [];
    } finally {
      if (isCurrent() && requestId === requestRef.current) setLoading(false);
    }
  }, [api, workspaceId, isCurrent]);

  useEffect(() => {
    setItems([]);
    setError("");
    setPendingRevisionId("");
    setLoading(false);
    void refresh();
  }, [refresh]);

  /** 以用户反馈提交一条信号并立即晋升，返回晋升后的记忆，用于展示门控结论。 */
  const addPreference = useCallback(async (input: AddPreferenceInput) => {
    if (!isCurrent()) return null;
    const text = input.text.trim();
    const signal = await api.createSignal(workspaceId, {
      signal_type: input.kind,
      source_type: "USER_FEEDBACK",
      signal_text: text,
      task_neighborhood: input.neighborhood,
      ...(input.kind === "NEGATIVE" ? { forbidden_patterns: [text] } : { style_constraints: [text] })
    });
    if (!isCurrent()) return null;
    const promotion = await api.promoteSignals(workspaceId, [signal.signal_id]);
    if (!isCurrent()) return null;
    const next = await refresh();
    const candidateId = promotion.candidates[0]?.candidate_id ?? "";
    return next.find((item) => item.memory_item_id === candidateId) ?? null;
  }, [api, refresh, workspaceId, isCurrent]);

  const decide = useCallback(async (item: MemoryItem, decision: MemoryReviewDecision) => {
    if (!isCurrent()) return;
    setPendingRevisionId(item.revision_id);
    try {
      const result = await api.decideReview(workspaceId, item.revision_id, { decision });
      if (isCurrent()) await refresh();
      return result;
    } finally {
      if (isCurrent()) setPendingRevisionId("");
    }
  }, [api, refresh, workspaceId, isCurrent]);

  return { items, loading, error, pendingRevisionId, refresh, addPreference, decide };
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "记忆请求失败";
}
