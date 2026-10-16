import { useCallback, useEffect, useState } from "react";
import { ApiError } from "../../shared/api/error";

export type RunResource<T> = {
  data: T | null;
  /** 后端还没有提供这个接口（404），面板直接不显示。 */
  unavailable: boolean;
  error: string;
  reload: () => void;
};

/** 读取某个研究 Run 的附属数据；refreshKey 变化时重新读取。 */
export function useRunResource<T>(load: () => Promise<T>, refreshKey: string): RunResource<T> {
  const [data, setData] = useState<T | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [error, setError] = useState("");
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    let active = true;
    load().then(
      (value) => {
        if (!active) return;
        setData(value);
        setUnavailable(false);
        setError("");
      },
      (cause: unknown) => {
        if (!active) return;
        if (cause instanceof ApiError && cause.status === 404) {
          setUnavailable(true);
          return;
        }
        setError(cause instanceof Error ? cause.message : "加载失败");
      }
    );
    return () => {
      active = false;
    };
    // load 由调用方按 workspaceId、researchRunId 生成，refreshKey 与 nonce 决定何时重读
  }, [refreshKey, nonce]);

  const reload = useCallback(() => setNonce((value) => value + 1), []);
  return { data, unavailable, error, reload };
}

/** 置信度以百万分之一存储，展示为百分比。 */
export function formatConfidence(ppm: number | null | undefined) {
  if (ppm === null || ppm === undefined) return "";
  return `${Math.round(ppm / 10000)}%`;
}
