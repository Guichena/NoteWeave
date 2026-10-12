import { useCallback, useEffect, useState } from "react";
import { researchApi, type ResearchApi } from "./api";
import type { ResearchPlan, ResearchPlanRevision } from "./model";

const POLL_INTERVAL_MS = 3000;
const STATUS_LABELS: Record<string, string> = {
  PLANNING: "模型规划中",
  REVIEW: "等待确认",
  READY: "已开始研究"
};

type ResearchPlanPanelProps = {
  workspaceId: string;
  researchRunId: string;
  api?: Pick<ResearchApi, "getPlan" | "approvePlan" | "revisePlan">;
  onStarted?: () => void;
};

/** 研究表格：模型规划出的实体（行）与研究维度（列）。确认阶段可以修改后再开始研究。 */
export function ResearchPlanPanel({ workspaceId, researchRunId, api = researchApi, onStarted }: ResearchPlanPanelProps) {
  const [plan, setPlan] = useState<ResearchPlan | null>(null);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState(false);
  const [entityText, setEntityText] = useState("");
  const [dimensionText, setDimensionText] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setPlan(await api.getPlan(workspaceId, researchRunId));
      setError("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "研究表格加载失败");
    }
  }, [api, workspaceId, researchRunId]);

  useEffect(() => {
    void load();
  }, [load]);

  const waiting = plan?.plan_status === "PLANNING" || plan?.plan_status === "REVIEW";
  useEffect(() => {
    if (!waiting || editing) return undefined;
    const timer = window.setInterval(() => void load(), POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [waiting, editing, load]);

  function startEditing(current: ResearchPlan) {
    setEntityText(current.rows.map((row) => row.label ?? row.entity_key).join("\n"));
    setDimensionText(current.columns
      .map((column) => (column.description ? `${column.label} | ${column.description}` : column.label))
      .join("\n"));
    setEditing(true);
  }

  async function run(action: () => Promise<ResearchPlan>, started: boolean) {
    setBusy(true);
    try {
      setPlan(await action());
      setEditing(false);
      setError("");
      if (started) onStarted?.();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "操作失败");
    } finally {
      setBusy(false);
    }
  }

  if (!plan) {
    return error ? <div className="task-card research-plan-panel"><small role="alert">{error}</small></div> : null;
  }

  return (
    <section className="task-card research-plan-panel" aria-label="研究表格">
      <div className="research-plan-header">
        <strong>研究表格</strong>
        <span className={`research-plan-status status-${plan.plan_status.toLowerCase()}`}>
          {STATUS_LABELS[plan.plan_status] ?? plan.plan_status}
        </span>
      </div>
      {plan.brief.objective ? <p className="research-plan-objective">{plan.brief.objective}</p> : null}
      <small>
        {plan.rows.length} 个研究对象 · {plan.columns.length} 个维度
        {plan.effort_tier ? ` · 投入等级 ${plan.effort_tier}` : ""}
        {plan.planner === "DETERMINISTIC" ? " · 规则生成" : ""}
      </small>
      {plan.plan_status === "PLANNING" ? <small>正在根据问题规划研究对象和维度，完成后开始检索。</small> : null}

      {editing ? (
        <div className="research-plan-editor">
          <label>
            研究对象（每行一个）
            <textarea value={entityText} rows={4} onChange={(event) => setEntityText(event.target.value)} />
          </label>
          <label>
            研究维度（每行一个，可用 | 分隔名称和说明）
            <textarea value={dimensionText} rows={6} onChange={(event) => setDimensionText(event.target.value)} />
          </label>
          <div className="research-plan-actions">
            <button type="button" className="secondary-button" disabled={busy} onClick={() => setEditing(false)}>取消</button>
            <button
              type="button"
              className="primary-button"
              disabled={busy}
              onClick={() => void run(() => api.revisePlan(workspaceId, researchRunId,
                parseRevision(entityText, dimensionText)), false)}
            >
              保存修改
            </button>
          </div>
        </div>
      ) : (
        <>
          <ul className="research-plan-list" aria-label="研究对象">
            {plan.rows.map((row) => <li key={row.entity_key}>{row.label ?? row.entity_key}</li>)}
          </ul>
          <ul className="research-plan-list research-plan-columns" aria-label="研究维度">
            {plan.columns.map((column) => (
              <li key={column.column_key}>
                <span>{column.label}{column.high_risk ? " · 高风险" : ""}</span>
                {column.description ? <small>{column.description}</small> : null}
              </li>
            ))}
          </ul>
          {plan.authoritative_domains.length > 0 ? (
            <small>优先参考：{plan.authoritative_domains.join("、")}</small>
          ) : null}
          {plan.plan_status === "REVIEW" ? (
            <div className="research-plan-actions">
              <button type="button" className="secondary-button" disabled={busy} onClick={() => startEditing(plan)}>修改</button>
              <button
                type="button"
                className="primary-button"
                disabled={busy}
                onClick={() => void run(() => api.approvePlan(workspaceId, researchRunId), true)}
              >
                开始研究
              </button>
            </div>
          ) : null}
        </>
      )}
      {error ? <small role="alert">{error}</small> : null}
    </section>
  );
}

/** 把编辑框的文本转成接口需要的结构；空行忽略，维度行用第一个 | 分隔名称和说明。 */
export function parseRevision(entityText: string, dimensionText: string): ResearchPlanRevision {
  const lines = (value: string) => value.split("\n").map((line) => line.trim()).filter(Boolean);
  return {
    entities: lines(entityText).map((label) => ({ label })),
    dimensions: lines(dimensionText).map((line) => {
      const separator = line.indexOf("|");
      if (separator < 0) return { label: line };
      const description = line.slice(separator + 1).trim();
      return description
        ? { label: line.slice(0, separator).trim(), description }
        : { label: line.slice(0, separator).trim() };
    })
  };
}
