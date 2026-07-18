import {
  type WaitContext,
  type WaitProviderDeliveryAttempt,
  type WaitProviderJob
} from "./features/executions/model";

export type {
  WaitApprovalRequest,
  WaitBlockedOperation,
  WaitContext,
  WaitProviderDeliveryAttempt,
  WaitProviderJob,
  WaitReason
} from "./features/executions/model";

export type RunTone = "active" | "waiting" | "stable" | "danger";

export type WaitSignalTone = "conflict" | "neutral" | "stable";

export type WaitContextSignalChip = {
  label: string;
  value: string;
  tone: WaitSignalTone;
};

export type WaitContextDetailLine = {
  label: string;
  value: string;
};

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

export function buildWaitContextNarrative(waitContext?: WaitContext | null): string {
  if (!waitContext) {
    return "";
  }
  const status = normalizeStatus(waitContext.status);
  const providerJob = waitContext.provider_job ?? {};
  const approvalRequest = waitContext.approval_request ?? {};
  const waitReason = waitContext.wait_reason ?? {};
  const providerId = readText(providerJob.provider_id) || readText(waitReason.provider_id);
  const operationKey = readText(providerJob.operation_key) || readText(waitReason.operation_key);
  const capabilityName = readText(providerJob.capability_name) || readText(waitReason.capability_name);
  const approvalRequestId = readText(approvalRequest.request_id);
  const providerRequestId = readText(providerJob.request_id);
  const providerJobId = readText(providerJob.provider_job_id);
  const providerDeliveryId = readText(providerJob.delivery_id);
  const providerStatus = readText(providerJob.provider_status);
  const healthStatus = readText(providerJob.health_status);
  const providerRuntimeStatus = readText(providerJob.provider_job_status) || readText(providerJob.status);
  const callbackStatus = readText(providerJob.callback_status);
  const dispatchCount = resolveWaitProviderDispatchCount(providerJob);
  const previousFailedDeliveryCount = resolveWaitProviderPreviousFailedDeliveryCount(providerJob);
  const hasPreviousFailedDelivery = providerJob.has_previous_failed_delivery === true
    || previousFailedDeliveryCount > 0;

  if (status === "WAITING_FOR_PROVIDER") {
    return appendTraceNarrative(
      compactNarrative("等待 Provider", providerId, operationKey),
      [
        providerRequestId ? `req=${providerRequestId}` : "",
        providerJobId ? `job=${providerJobId}` : "",
        providerDeliveryId ? `delivery=${providerDeliveryId}` : "",
        dispatchCount > 0 ? `attempt=${dispatchCount}` : "",
        hasPreviousFailedDelivery
          ? `failed_before=${previousFailedDeliveryCount > 0 ? previousFailedDeliveryCount : 1}`
          : "",
        providerStatus || healthStatus
          ? `provider=${[providerStatus, healthStatus].filter(Boolean).join("/")}`
          : "",
        providerRuntimeStatus ? `status=${providerRuntimeStatus}` : "",
        !providerRuntimeStatus && callbackStatus ? `callback=${callbackStatus}` : "",
      ]
    );
  }
  if (status === "WAITING_FOR_APPROVAL") {
    return compactNarrative("等待审批", capabilityName, approvalRequestId || operationKey || providerId);
  }
  if (status === "WAITING_FOR_CAPABILITY") {
    return compactNarrative("等待能力", capabilityName || operationKey, providerId);
  }
  return "";
}

export function buildWaitContextSignalChips(waitContext?: WaitContext | null): WaitContextSignalChip[] {
  if (!waitContext) {
    return [];
  }
  const status = normalizeStatus(waitContext.status);

  const providerJob = waitContext.provider_job ?? {};
  const approvalRequest = waitContext.approval_request ?? {};
  const waitReason = waitContext.wait_reason ?? {};

  if (status === "WAITING_FOR_PROVIDER") {
    const dispatchCount = resolveWaitProviderDispatchCount(providerJob);
    const previousFailedDeliveryCount = resolveWaitProviderPreviousFailedDeliveryCount(providerJob);
    const hasPreviousFailedDelivery = providerJob.has_previous_failed_delivery === true
      || previousFailedDeliveryCount > 0;
    const providerStatus = readText(providerJob.provider_status);
    const healthStatus = readText(providerJob.health_status);
    const providerRuntimeStatus = readText(providerJob.provider_job_status) || readText(providerJob.status);

    const chips: WaitContextSignalChip[] = [];
    if (dispatchCount > 0) {
      chips.push({
        label: "Delivery",
        value: `#${dispatchCount}`,
        tone: dispatchCount > 1 ? "neutral" : "stable"
      });
    }
    if (hasPreviousFailedDelivery) {
      chips.push({
        label: "Retry",
        value: previousFailedDeliveryCount > 0 ? `failed ${previousFailedDeliveryCount}` : "failed 1",
        tone: "conflict"
      });
    }
    if (providerStatus || healthStatus) {
      chips.push({
        label: "Provider",
        value: [providerStatus, healthStatus].filter(Boolean).join("/"),
        tone: classifyProviderSignalTone(providerStatus, healthStatus)
      });
    }
    if (providerRuntimeStatus) {
      chips.push({
        label: "State",
        value: providerRuntimeStatus,
        tone: classifyProviderRuntimeTone(providerRuntimeStatus)
      });
    }
    return chips;
  }

  if (status === "WAITING_FOR_APPROVAL") {
    const capabilityName = readText(approvalRequest.capability_name) || readText(providerJob.capability_name);
    const approvalRequestId = readText(approvalRequest.request_id);
    const providerId = readText(approvalRequest.provider_id) || readText(providerJob.provider_id);
    return compactSignalChips([
      capabilityName ? { label: "Capability", value: capabilityName, tone: "neutral" } : null,
      approvalRequestId ? { label: "Approval", value: `#${approvalRequestId}`, tone: "neutral" } : null,
      providerId ? { label: "Provider", value: providerId, tone: "neutral" } : null
    ]);
  }

  if (status === "WAITING_FOR_CAPABILITY") {
    const capabilityName = readText(waitReason.capability_name) || readText(providerJob.capability_name);
    const providerId = readText(waitReason.provider_id) || readText(providerJob.provider_id);
    return compactSignalChips([
      capabilityName ? { label: "Capability", value: capabilityName, tone: "conflict" } : null,
      providerId ? { label: "Provider", value: providerId, tone: "conflict" } : null
    ]);
  }

  return [];
}

export function buildWaitContextDetailLines(waitContext?: WaitContext | null): WaitContextDetailLine[] {
  if (!waitContext) {
    return [];
  }
  const status = normalizeStatus(waitContext.status);
  const providerJob = waitContext.provider_job ?? {};
  const approvalRequest = waitContext.approval_request ?? {};
  const waitReason = waitContext.wait_reason ?? {};

  if (status === "WAITING_FOR_PROVIDER") {
    const attempts = readWaitProviderDeliveryAttempts(providerJob);
    const lines: WaitContextDetailLine[] = compactDetailLines([
      detailLine("Request", readText(providerJob.request_id)),
      detailLine("Provider Job", readText(providerJob.provider_job_id)),
      detailLine(
        "Receipt",
        readText(providerJob.provider_receipt_id)
      ),
      detailLine(
        "Latest Attempt",
        buildLatestAttemptSummary(attempts)
      ),
      detailLine(
        "Previous Attempt",
        buildPreviousAttemptSummary(attempts)
      )
    ]);
    return lines;
  }

  if (status === "WAITING_FOR_APPROVAL") {
    return compactDetailLines([
      detailLine("Approval Request", readText(approvalRequest.request_id)),
      detailLine("Capability", readText(approvalRequest.capability_name) || readText(providerJob.capability_name)),
      detailLine("Provider", readText(approvalRequest.provider_id) || readText(providerJob.provider_id))
    ]);
  }

  if (status === "WAITING_FOR_CAPABILITY") {
    return compactDetailLines([
      detailLine("Capability", readText(waitReason.capability_name) || readText(providerJob.capability_name)),
      detailLine("Provider", readText(waitReason.provider_id) || readText(providerJob.provider_id))
    ]);
  }

  return [];
}

function normalizeStatus(status?: string): string {
  return (status || "").trim().toUpperCase();
}

function readText(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function readNumber(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim().length > 0) {
    const parsed = Number(value.trim());
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

function resolveWaitProviderDispatchCount(providerJob: WaitProviderJob): number {
  const explicitCount = readNumber(providerJob.dispatch_count);
  if (explicitCount > 0) {
    return explicitCount;
  }
  const attempts = readWaitProviderDeliveryAttempts(providerJob);
  if (attempts.length === 0) {
    return 0;
  }
  return attempts.reduce((max, attempt) => Math.max(max, readAttemptDispatchCount(attempt)), 0);
}

function resolveWaitProviderPreviousFailedDeliveryCount(providerJob: WaitProviderJob): number {
  const explicitCount = readNumber(providerJob.previous_failed_delivery_count);
  if (explicitCount > 0) {
    return explicitCount;
  }
  const attempts = readWaitProviderDeliveryAttempts(providerJob);
  return attempts.filter((attempt) => readAttemptAckStatus(attempt) === "FAILED").length;
}

function readWaitProviderDeliveryAttempts(providerJob: WaitProviderJob): WaitProviderDeliveryAttempt[] {
  const attempts = providerJob.providerDeliveryAttempts ?? providerJob.provider_delivery_attempts;
  return Array.isArray(attempts) ? attempts : [];
}

function readAttemptDispatchCount(attempt: WaitProviderDeliveryAttempt): number {
  return readNumber(attempt.dispatchCount) || readNumber(attempt.dispatch_count);
}

function readAttemptAckStatus(attempt: WaitProviderDeliveryAttempt): string {
  return readText(attempt.ackStatus) || readText(attempt.ack_status);
}

function classifyProviderSignalTone(providerStatus: string, healthStatus: string): WaitSignalTone {
  const provider = providerStatus.toUpperCase();
  const health = healthStatus.toUpperCase();
  if (provider === "UNAVAILABLE" || health === "UNHEALTHY" || health === "DEGRADED") {
    return "conflict";
  }
  if (provider === "AVAILABLE" && health === "HEALTHY") {
    return "stable";
  }
  return "neutral";
}

function classifyProviderRuntimeTone(status: string): WaitSignalTone {
  const normalized = status.toUpperCase();
  if (normalized === "FAILED") {
    return "conflict";
  }
  if (normalized === "SUCCEEDED") {
    return "stable";
  }
  return "neutral";
}

function compactSignalChips(chips: Array<WaitContextSignalChip | null>): WaitContextSignalChip[] {
  return chips.filter((chip): chip is WaitContextSignalChip => Boolean(chip));
}

function compactDetailLines(lines: Array<WaitContextDetailLine | null>): WaitContextDetailLine[] {
  return lines.filter((line): line is WaitContextDetailLine => Boolean(line));
}

function detailLine(label: string, value: string): WaitContextDetailLine | null {
  return value ? { label, value } : null;
}

function compactNarrative(prefix: string, primary: string, secondary: string): string {
  const segments = [primary, secondary].filter(Boolean);
  return segments.length > 0
    ? `${prefix} · ${segments.join(" / ")}`
    : prefix;
}

function appendTraceNarrative(base: string, traceSegments: string[]): string {
  const segments = traceSegments.filter(Boolean);
  return segments.length > 0
    ? `${base} · ${segments.join(" · ")}`
    : base;
}

function compactSegments(parts: string[]): string {
  return parts.filter(Boolean).join(" / ");
}

function buildLatestAttemptSummary(attempts: WaitProviderDeliveryAttempt[]): string {
  if (attempts.length === 0) {
    return "";
  }
  const attempt = attempts[attempts.length - 1];
  const status = readAttemptAckStatus(attempt) || "PENDING";
  return compactSegments([
    status,
    readAttemptDispatchCount(attempt) > 0 ? `#${readAttemptDispatchCount(attempt)}` : "",
    readDualAttemptString(attempt, "deliveryId", "delivery_id")
      ? `delivery=${readDualAttemptString(attempt, "deliveryId", "delivery_id")}`
      : ""
  ]);
}

function buildPreviousAttemptSummary(attempts: WaitProviderDeliveryAttempt[]): string {
  if (attempts.length <= 1) {
    return "";
  }
  const attempt = attempts[attempts.length - 2];
  return compactSegments([
    readAttemptAckStatus(attempt) || "PENDING",
    readAttemptDispatchCount(attempt) > 0 ? `#${readAttemptDispatchCount(attempt)}` : "",
    buildAttemptErrorSummary(attempt),
    readDualAttemptString(attempt, "resultLocator", "result_locator")
      ? `result=${readDualAttemptString(attempt, "resultLocator", "result_locator")}`
      : ""
  ]);
}

function buildAttemptErrorSummary(attempt: WaitProviderDeliveryAttempt): string {
  const code = readDualAttemptString(attempt, "errorCode", "error_code");
  const message = readDualAttemptString(attempt, "errorMessage", "error_message");
  const value = [code, message].filter(Boolean).join(": ");
  return value ? `error=${value}` : "";
}

function readDualAttemptString(attempt: WaitProviderDeliveryAttempt, camelKey: keyof WaitProviderDeliveryAttempt, snakeKey: keyof WaitProviderDeliveryAttempt): string {
  return readText(attempt[camelKey]) || readText(attempt[snakeKey]);
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
