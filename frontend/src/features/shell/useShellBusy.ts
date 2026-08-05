import { useCallback, useState } from "react";
import {
  type BusyMap,
  type BusyScope,
  isAnyBusy,
  isScopeBusy,
  setBusyScope
} from "./busyScope";

export type ShellRun = (
  label: string,
  action: () => Promise<void>,
  scope?: BusyScope
) => Promise<void>;

function isAbortError(error: unknown) {
  return typeof error === "object"
    && error !== null
    && "name" in error
    && error.name === "AbortError";
}

export function useShellBusy(initialStatus = "准备就绪") {
  const [busy, setBusy] = useState<BusyMap>({});
  const [status, setStatus] = useState(initialStatus);

  const run = useCallback<ShellRun>(async (label, action, scope = "shell") => {
    try {
      setBusy((current) => setBusyScope(current, scope, true));
      setStatus(`${label}中...`);
      await action();
      setStatus(`${label}完成`);
    } catch (error) {
      if (isAbortError(error)) {
        setStatus((current) => current === `${label}中...` ? initialStatus : current);
      } else {
        setStatus(error instanceof Error ? error.message : `${label}失败`);
      }
    } finally {
      setBusy((current) => setBusyScope(current, scope, false));
    }
  }, [initialStatus]);

  return {
    busy,
    status,
    setStatus,
    run,
    isBusy: isAnyBusy(busy),
    chatBusy: isScopeBusy(busy, "chat"),
    uploadBusy: isScopeBusy(busy, "upload"),
    artifactBusy: isScopeBusy(busy, "artifact"),
    shellBusy: isScopeBusy(busy, "shell"),
    researchBusy: isScopeBusy(busy, "research"),
    wikiBusy: isScopeBusy(busy, "wiki")
  };
}
