import { useState } from "react";
import { researchApi, type ResearchApi } from "./api";
import type { ResearchStandaloneMigrationReceipt } from "./model";

type Api = Pick<ResearchApi, "attachConversation" | "migrateStandalone">;

type Props = {
  workspaceId: string;
  researchRunId: string;
  api?: Api;
};

/** 历史独立研究：挂到新对话，不删除、不重跑。 */
export function ResearchStandalonePanel({ workspaceId, researchRunId, api = researchApi }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [receipt, setReceipt] = useState<ResearchStandaloneMigrationReceipt | null>(null);

  async function run(action: () => Promise<ResearchStandaloneMigrationReceipt>) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      setReceipt(await action());
    } catch {
      setError("迁入对话失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  return (
    <details className="task-card research-standalone-panel">
      <summary>历史独立研究</summary>
      <p>没有对话的旧研究可以挂到新对话里查看报告，不会删除原研究，也不会重新执行。</p>
      <button type="button" className="secondary-button" disabled={busy}
              onClick={() => void run(() => api.attachConversation(workspaceId, researchRunId))}>
        把当前研究挂到对话
      </button>
      <button type="button" className="secondary-button" disabled={busy}
              onClick={() => void run(() => api.migrateStandalone(workspaceId))}>
        迁移本工作台全部独立研究
      </button>
      {error ? <p role="alert">{error}</p> : null}
      {receipt ? (
        <p role="status">已迁入 {receipt.migrated} 个，跳过 {receipt.skipped} 个</p>
      ) : null}
    </details>
  );
}
