export type {
  WaitApprovalRequest,
  WaitBlockedOperation,
  WaitContext,
  WaitProviderDeliveryAttempt,
  WaitProviderJob,
  WaitReason
} from "./features/executions/model";

export {
  buildWaitContextDetailLines,
  buildWaitContextNarrative,
  buildWaitContextSignalChips
} from "./waitContextPresentation";

export type {
  WaitContextDetailLine,
  WaitContextSignalChip,
  WaitSignalTone
} from "./waitContextPresentation";

export type RunTone = "active" | "waiting" | "stable" | "danger";

export function resolveRunStatus(taskStatus?: string, domainStatus?: string): string {
  const normalizedTaskStatus = normalizeStatus(taskStatus);
  const normalizedDomainStatus = normalizeStatus(domainStatus);
  if (normalizedTaskStatus === "WAITING" && normalizedDomainStatus.startsWith("WAITING_FOR_")) {
    return normalizedDomainStatus;
  }
  if (normalizedTaskStatus) {
    return normalizedTaskStatus;
  }
  if (normalizedDomainStatus) {
    return normalizedDomainStatus;
  }
  return "UNKNOWN";
}

export function summarizeRunStatus(status?: string): string {
  const normalizedStatus = normalizeStatus(status);
  return statusSummaryMap[normalizedStatus] ?? (normalizedStatus || "未知状态");
}

export function resolveRunTone(status?: string): RunTone {
  const normalizedStatus = normalizeStatus(status);
  if (normalizedStatus === "FAILED") {
    return "danger";
  }
  if (normalizedStatus === "RUNNING") {
    return "active";
  }
  if (
    normalizedStatus === "WAITING"
    || normalizedStatus === "PENDING"
    || normalizedStatus === "QUEUED"
    || normalizedStatus === "OFF"
    || normalizedStatus.startsWith("WAITING_FOR_")
  ) {
    return "waiting";
  }
  return "stable";
}

function normalizeStatus(status?: string): string {
  return (status || "").trim().toUpperCase();
}

const statusSummaryMap: Record<string, string> = {
  WAITING_FOR_PROVIDER: "等待 Provider",
  WAITING_FOR_APPROVAL: "等待审批",
  WAITING_FOR_CAPABILITY: "等待能力",
  WAITING: "等待中",
  RUNNING: "运行中",
  QUEUED: "排队中",
  PENDING: "等待启动",
  COMPLETED: "已完成",
  READY: "就绪",
  FAILED: "失败",
  OFF: "未开启",
  UNKNOWN: "未知状态"
};
