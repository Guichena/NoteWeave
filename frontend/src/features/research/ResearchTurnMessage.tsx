import { useEffect, useState } from "react";
import type { ResearchRunDetail } from "./model";
import { parseResearchProjection, resolveRunState, ResearchRunTurn, type ResearchProjectionView } from "./ResearchChatView";

const POLL_MS = 5000;
const RETRY_MS = 15000;

type ResearchTurnMessageProps = {
  researchRunId: string;
  loadRun: (researchRunId: string) => Promise<ResearchRunDetail>;
  /** 取消进行中的研究；未提供时不显示取消入口 */
  cancelRun?: (researchRunId: string) => Promise<void>;
  onOpenReport: (researchRunId: string) => void;
  /** F3-03：对话消息里的结构化研究投影；缺失时退回按运行详情渲染。 */
  researchProjectionJson?: string | null;
};

/**
 * 问答页里用深度研究发起的一轮：按研究编号读取运行详情，研究进行中每 5 秒刷新一次，
 * 完成后优先用后端结构化投影渲染结论卡；查看完整报告跳到研究页的完整报告。
 * 研究进行中显示已用时间和取消按钮，长时间没有进展时用户可以自己停下来。
 */
export function ResearchTurnMessage({
  researchRunId,
  loadRun,
  cancelRun,
  onOpenReport,
  researchProjectionJson,
}: ResearchTurnMessageProps) {
  const [run, setRun] = useState<ResearchRunDetail | null>(null);
  const [failed, setFailed] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [cancelState, setCancelState] = useState<"idle" | "pending" | "failed">("idle");
  const [now, setNow] = useState(() => Date.now());
  const projection: ResearchProjectionView | null = parseResearchProjection(researchProjectionJson);
  const running = run ? resolveRunState(run) === "running" : false;

  // 研究进行中每秒更新一次已用时间，让用户知道研究仍在推进
  useEffect(() => {
    if (!running) return undefined;
    const ticker = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(ticker);
  }, [running]);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function refresh() {
      try {
        const detail = await loadRun(researchRunId);
        if (cancelled) return;
        setRun(detail);
        setFailed(false);
        if (resolveRunState(detail) === "running") timer = setTimeout(refresh, POLL_MS);
      } catch {
        if (cancelled) return;
        setFailed(true);
        timer = setTimeout(refresh, RETRY_MS);
      }
    }
    void refresh();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [researchRunId, loadRun, refreshKey]);

  async function handleCancel() {
    if (!cancelRun) return;
    setCancelState("pending");
    try {
      await cancelRun(researchRunId);
      setCancelState("idle");
      setRefreshKey((value) => value + 1);
    } catch {
      setCancelState("failed");
    }
  }

  if (!run) {
    return (
      <p className="research-chat-progress">
        <span className="chat-typing-dots" aria-hidden="true"><i /><i /><i /></span>
        {failed ? "暂时读不到研究进度，稍后自动重试" : "正在启动深度研究"}
      </p>
    );
  }
  const turn = <ResearchRunTurn run={run} projection={projection} onOpenReport={() => onOpenReport(researchRunId)} />;
  if (!running) return turn;
  const elapsed = formatElapsed(run.created_at, now);
  return (
    <>
      {turn}
      <div className="research-turn-controls">
        {elapsed ? <span className="research-turn-elapsed">已用时 {elapsed}</span> : null}
        {cancelRun ? (
          <button
            type="button"
            className="secondary-button"
            disabled={cancelState === "pending"}
            onClick={() => void handleCancel()}
          >
            {cancelState === "pending" ? "正在取消" : "取消研究"}
          </button>
        ) : null}
        {cancelState === "failed" ? <span className="research-turn-cancel-error">取消失败，请稍后重试</span> : null}
      </div>
    </>
  );
}

/** 已用时间：不足一分钟显示秒，否则显示分和秒。创建时间无法解析时返回空串。 */
export function formatElapsed(createdAt: string, now: number): string {
  const started = Date.parse(createdAt);
  if (Number.isNaN(started)) return "";
  const seconds = Math.max(0, Math.floor((now - started) / 1000));
  if (seconds < 60) return `${seconds} 秒`;
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}
