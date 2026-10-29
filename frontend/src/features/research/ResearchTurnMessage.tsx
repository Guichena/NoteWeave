import { useEffect, useState } from "react";
import type { ResearchRunDetail } from "./model";
import { resolveRunState, ResearchRunTurn } from "./ResearchChatView";

const POLL_MS = 5000;
const RETRY_MS = 15000;

type ResearchTurnMessageProps = {
  researchRunId: string;
  loadRun: (researchRunId: string) => Promise<ResearchRunDetail>;
  onOpenReport: (researchRunId: string) => void;
};

/**
 * 问答页里用深度研究发起的一轮：按研究编号读取运行详情，研究进行中每 5 秒刷新一次，
 * 完成后显示报告消息；查看完整报告跳到研究页的完整报告。
 */
export function ResearchTurnMessage({ researchRunId, loadRun, onOpenReport }: ResearchTurnMessageProps) {
  const [run, setRun] = useState<ResearchRunDetail | null>(null);
  const [failed, setFailed] = useState(false);

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
  }, [researchRunId, loadRun]);

  if (!run) {
    return (
      <p className="research-chat-progress">
        <span className="chat-typing-dots" aria-hidden="true"><i /><i /><i /></span>
        {failed ? "暂时读不到研究进度，稍后自动重试" : "正在启动深度研究"}
      </p>
    );
  }
  return <ResearchRunTurn run={run} onOpenReport={() => onOpenReport(researchRunId)} />;
}
