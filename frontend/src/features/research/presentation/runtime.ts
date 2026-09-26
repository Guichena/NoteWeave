import {
  type ExecutionEvent as StreamEvent,
  type ExecutionTask as TaskStatus
} from "../../executions/model";
import { asRecord, numberFromUnknown } from "../../../shared/util/records";
import { normalizeSignalValue, summarizeText } from "./primitives";

export function labelTaskType(taskType: string) {
  switch (normalizeSignalValue(taskType)) {
    case "RESEARCH_RUN":
      return "Deep Research 任务";
    case "SOURCE_PARSE":
      return "资料处理任务";
    case "WIKI_INGEST":
      return "Wiki ingest 任务";
    case "WIKI_RETRACT":
      return "Wiki retract 任务";
    case "ARTIFACT_JOB":
      return "产物任务";
    default:
      return normalizeSignalValue(taskType) || "任务";
  }
}

export function readTaskEventPhase(event: StreamEvent, task: TaskStatus | null) {
  const payload = asRecord(event.payload);
  return normalizeSignalValue(payload.phase ?? task?.progress_phase);
}

export function readTaskEventMetrics(event: StreamEvent) {
  const payload = asRecord(event.payload);
  return asRecord(payload.metrics);
}

export function formatTaskMetric(metric: string, value: string | number | null | undefined) {
  if (value == null || value === "") {
    return "";
  }
  return `${metric} ${value}`;
}

export function buildResearchTaskMetricSummary(event: StreamEvent, limit = 6) {
  const payload = asRecord(event.payload);
  const metrics = readTaskEventMetrics(event);
  const items = [
    formatTaskMetric("scope", numberFromUnknown(metrics.source_count) || undefined),
    formatTaskMetric("query", numberFromUnknown(metrics.query_count) || undefined),
    formatTaskMetric("hits", numberFromUnknown(metrics.search_hits) || undefined),
    formatTaskMetric("windows", numberFromUnknown(metrics.read_windows) || undefined),
    formatTaskMetric("evidence", numberFromUnknown(metrics.evidence_cards) || undefined),
    formatTaskMetric("rows", numberFromUnknown(metrics.ledger_rows) || undefined),
    formatTaskMetric("cells", numberFromUnknown(metrics.ledger_cells) || undefined),
    formatTaskMetric("branches", numberFromUnknown(metrics.branch_count) || undefined),
    formatTaskMetric("rounds", numberFromUnknown(metrics.loop_rounds) || undefined),
    formatTaskMetric("local", normalizeSignalValue(metrics.local_status) || undefined),
    formatTaskMetric("global", normalizeSignalValue(metrics.global_status) || undefined),
    formatTaskMetric("loop", normalizeSignalValue(metrics.loop_decision) || undefined),
    formatTaskMetric(
      "progress",
      numberFromUnknown(payload.progress_percent) > 0
        ? `${numberFromUnknown(payload.progress_percent)}%`
        : undefined
    )
  ].filter(Boolean);
  if (!items.length) {
    return "";
  }
  return `当前闭环计量：${items.slice(0, limit).join(" · ")}`;
}

export function buildResearchTaskRuntimeSnapshot(event: StreamEvent | null, task: TaskStatus | null) {
  if (!event) {
    return "";
  }
  const phase = readTaskEventPhase(event, task);
  const metricSummary = buildResearchTaskMetricSummary(event, 8);
  if (!metricSummary) {
    return "";
  }
  switch (phase) {
    case "SEARCHING":
      return `Research Harness runtime snapshot：当前处于检索扩展段。${metricSummary}`;
    case "READING":
      return `Research Harness runtime snapshot：当前处于读窗压缩段。${metricSummary}`;
    case "EXTRACTING":
      return `Research Harness runtime snapshot：当前正在把读窗沉淀为 Table-as-State。${metricSummary}`;
    case "VERIFYING":
      return `Research Harness runtime snapshot：当前正在进入 Dual Verifier / 反证分支判断。${metricSummary}`;
    case "WRITING":
      return `Research Harness runtime snapshot：当前正在把闭环结果固化为报告产物。${metricSummary}`;
    default:
      return `Research Harness runtime snapshot：${metricSummary}`;
  }
}

export function buildGenericTaskRuntimeSnapshot(event: StreamEvent | null, task: TaskStatus | null) {
  if (!event || !task) {
    return "";
  }
  const payload = asRecord(event.payload);
  const progressPercent = numberFromUnknown(payload.progress_percent);
  const phase = readTaskEventPhase(event, task);
  const metrics = readTaskEventMetrics(event);
  const base = [
    phase ? `phase ${phase}` : "",
    progressPercent > 0 ? `progress ${progressPercent}%` : "",
    Object.keys(metrics).length ? `metrics ${Object.keys(metrics).length}` : ""
  ].filter(Boolean).join(" · ");
  if (!base) {
    return "";
  }
  return `${labelTaskType(task.task_type)} runtime snapshot：${base}`;
}

export function buildTaskEventNarrative(event: StreamEvent, task: TaskStatus | null) {
  const taskLabel = labelTaskType(task?.task_type || "");
  const phase = readTaskEventPhase(event, task);
  const message = summarizeText(event.message || event.data || task?.progress_message || "", 96);
  switch (event.event) {
    case "task.status":
      switch (normalizeSignalValue(task?.task_type)) {
        case "SOURCE_PARSE":
          return "资料处理链路已接管上传内容，准备解析、切片并建立检索索引。";
        case "WIKI_RETRACT":
          return "工作台已开始清理资料删除带来的 Wiki 回链影响。";
        case "WIKI_INGEST":
          return "工作台已开始按资料更新 Wiki 页面与回链。";
        case "ARTIFACT_JOB":
          return "产物任务已入队，等待版本化与资料回流。";
        default:
          return `${taskLabel}已入队。${message}`;
      }
    case "task.heartbeat": {
      const heartbeatAt = String(asRecord(event.payload).heartbeat_at ?? "").trim();
      return `${taskLabel}保活心跳：${heartbeatAt || "worker running"}`;
    }
    case "task.completed":
      return `${taskLabel}已完成：${message || "当前任务已经处理完成。"}`;
    case "task.failed":
      return `${taskLabel}失败：${message || summarizeText(task?.error_message || "任务执行中断", 96)}。`;
    case "task.progress":
    default:
      switch (normalizeSignalValue(task?.task_type)) {
        case "SOURCE_PARSE":
          if (phase === "PARSING") {
            return "资料正在解析、切片并写入本地检索索引。";
          }
          if (phase === "INDEXED") {
            return "资料已经进入检索索引，可继续用于 QA / Note / Wiki 链路。";
          }
          return `资料处理正在推进：${message || "等待下一个阶段。"}`;
        case "WIKI_RETRACT":
          return `Wiki retract 正在清理受影响页面：${message || "处理中。"}`;
        case "WIKI_INGEST":
          return `Wiki ingest 正在根据资料更新工作台页面：${message || "处理中。"}`;
        case "ARTIFACT_JOB":
          return `产物任务正在推进版本化与资料回流：${message || "处理中。"}`;
        default:
          return `${taskLabel}正在推进：${message || "处理中。"}`;
      }
  }
}
