import type {
  ArtifactRuntimeSummaryItem,
  ArtifactRuntimeTrace
} from "./artifactRuntimeTrace.model";

export type * from "./artifactRuntimeTrace.model";

type UnknownRecord = Record<string, unknown>;

export function buildArtifactRuntimeSummary(runtimeTrace?: ArtifactRuntimeTrace | null): ArtifactRuntimeSummaryItem[] {
  if (!runtimeTrace) {
    return [];
  }

  const summary: ArtifactRuntimeSummaryItem[] = [];
  const verificationStatus = readString(runtimeTrace.verification?.status);
  if (verificationStatus) {
    summary.push({ label: "Verifier", value: verificationStatus });
  }

  const generationMode = readString(runtimeTrace.generation_trace?.mode);
  if (generationMode) {
    summary.push({
      label: "Generation",
      value: [
        generationMode,
        readString(runtimeTrace.generation_trace?.model),
        readString(runtimeTrace.generation_trace?.fallback_reason)
      ].filter(Boolean).join(" · ")
    });
  }

  const exportStatus = readString(runtimeTrace.export_trace?.status);
  if (exportStatus) {
    summary.push({
      label: "Export",
      value: [
        exportStatus,
        readString(runtimeTrace.export_trace?.format),
        readString(runtimeTrace.export_trace?.file_name)
      ].filter(Boolean).join(" · ")
    });
  }

  const approvalStatus = readString(runtimeTrace.approval_trace?.status);
  if (approvalStatus) {
    summary.push({
      label: "Approval",
      value: `${approvalStatus} · ok=${runtimeTrace.approval_trace?.satisfied_capabilities?.length ?? 0}`
        + ` · pending=${runtimeTrace.approval_trace?.pending_capabilities?.length ?? 0}`
    });
  }

  const capabilityUnionStatus = readString(runtimeTrace.capability_union_trace?.status);
  if (capabilityUnionStatus) {
    summary.push({
      label: "Policy",
      value: `${capabilityUnionStatus} · blocked=${runtimeTrace.capability_union_trace?.blocked_capabilities?.length ?? 0}`
        + ` · external=${runtimeTrace.capability_union_trace?.external_network_capabilities?.length ?? 0}`
    });
  }

  const evidenceStatus = readString(runtimeTrace.evidence_coverage?.status);
  if (evidenceStatus) {
    const sectionCount = readNumber(runtimeTrace.evidence_coverage?.section_count);
    const coveredSectionCount = readNumber(runtimeTrace.evidence_coverage?.covered_section_count);
    summary.push({
      label: "Evidence",
      value: `${evidenceStatus} · covered=${coveredSectionCount}/${sectionCount}`
        + ` · missing=${runtimeTrace.evidence_coverage?.sections_missing_evidence?.length ?? 0}`
    });
  }

  const writebackStatus = readString(runtimeTrace.writeback_preview?.status);
  if (writebackStatus) {
    const requestedMode = readString(runtimeTrace.writeback_preview?.requested_mode);
    const executionMode = readString(runtimeTrace.writeback_preview?.execution_mode);
    summary.push({
      label: "Writeback",
      value: executionMode
        ? `${requestedMode || writebackStatus} / ${executionMode}`
        : requestedMode || writebackStatus
    });
  }

  const nodeTraces = Array.isArray(runtimeTrace.node_traces) ? runtimeTrace.node_traces : [];
  if (nodeTraces.length > 0) {
    const repairedCount = nodeTraces.filter((trace) => trace?.repaired).length;
    const failedCount = nodeTraces.filter((trace) => {
      const status = readString(trace?.verification_status);
      return status !== "" && status !== "PASS" && status !== "PASS_WITH_REPAIR";
    }).length;
    summary.push({
      label: "Nodes",
      value: `count=${nodeTraces.length} · repaired=${repairedCount} · fail=${failedCount}`
    });
  }

  const contractStatus = readString(runtimeTrace.output_contract_trace?.status);
  if (contractStatus) {
    summary.push({
      label: "Contract",
      value: `${contractStatus} · repair=${runtimeTrace.output_contract_trace?.repaired_checks?.length ?? 0}`
        + ` · fail=${runtimeTrace.output_contract_trace?.failed_checks?.length ?? 0}`
    });
  }

  const lifecycleStatus = readString(runtimeTrace.lifecycle_trace?.status);
  if (lifecycleStatus) {
    const currentPhase = readString(runtimeTrace.lifecycle_trace?.current_phase);
    summary.push({
      label: "Lifecycle",
      value: currentPhase ? `${lifecycleStatus} / ${currentPhase}` : lifecycleStatus
    });
  }

  const callbackStatus = readString(runtimeTrace.acquisition_callback_trace?.receipt?.provider_job_status)
    || readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_job_status)
    || readString(runtimeTrace.acquisition_callback_trace?.receipt?.callback_status)
    || readString(runtimeTrace.acquisition_callback_trace?.status);
  if (callbackStatus) {
    const callbackSummaryParts = [callbackStatus];
    const requestId = readString(runtimeTrace.acquisition_callback_trace?.operation?.request_id);
    if (requestId) {
      callbackSummaryParts.push(requestId);
    }
    const providerState = readCallbackProviderState(runtimeTrace);
    if (providerState) {
      callbackSummaryParts.push(`provider=${providerState}`);
    }
    const deliveryCount = readCallbackDeliveryCount(runtimeTrace);
    if (deliveryCount > 0) {
      callbackSummaryParts.push(`delivery=${deliveryCount}`);
    }
    const retryCount = readCallbackRetryCount(runtimeTrace);
    if (retryCount > 0) {
      callbackSummaryParts.push(`retry=${retryCount}`);
    }
    const providerReceiptId = readCallbackProviderReceiptId(runtimeTrace);
    if (providerReceiptId) {
      callbackSummaryParts.push(`receipt=${providerReceiptId}`);
    }
    const resultLocator = readCallbackResultLocator(runtimeTrace);
    if (resultLocator) {
      callbackSummaryParts.push(`result=${resultLocator}`);
    }
    const callbackError = readCallbackErrorSummary(runtimeTrace);
    if (callbackError) {
      callbackSummaryParts.push(`error=${callbackError}`);
    }
    summary.push({ label: "Callback", value: callbackSummaryParts.join(" / ") });
  }

  return summary;
}

function readString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function readNumber(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function readCallbackDeliveryCount(runtimeTrace: ArtifactRuntimeTrace): number {
  const attempts = readCallbackDeliveryAttempts(runtimeTrace);
  if (attempts.length > 0) {
    return attempts.length;
  }
  return readNumber(runtimeTrace.acquisition_callback_trace?.receipt?.dispatch_count)
    || readNumber(runtimeTrace.acquisition_callback_trace?.operation?.dispatch_count)
    || readNumber(runtimeTrace.acquisition_callback_trace?.operation?.dispatchCount);
}

function readCallbackRetryCount(runtimeTrace: ArtifactRuntimeTrace): number {
  const attempts = readCallbackDeliveryAttempts(runtimeTrace);
  if (attempts.length === 0) {
    const deliveryCount = readCallbackDeliveryCount(runtimeTrace);
    return deliveryCount > 1 ? deliveryCount - 1 : 0;
  }
  return attempts.filter((attempt) => {
    const ackStatus = readString(attempt.ackStatus) || readString(attempt.ack_status);
    return ackStatus === "FAILED";
  }).length;
}

function readCallbackProviderState(runtimeTrace: ArtifactRuntimeTrace): string {
  return [
    readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_status),
    readString(runtimeTrace.acquisition_callback_trace?.operation?.health_status)
  ].filter(Boolean).join("/");
}

function readCallbackProviderReceiptId(runtimeTrace: ArtifactRuntimeTrace): string {
  return readString(runtimeTrace.acquisition_callback_trace?.receipt?.provider_receipt_id)
    || readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_receipt_id);
}

function readCallbackResultLocator(runtimeTrace: ArtifactRuntimeTrace): string {
  return readString(runtimeTrace.acquisition_callback_trace?.receipt?.result_locator);
}

function readCallbackErrorSummary(runtimeTrace: ArtifactRuntimeTrace): string {
  return readString(runtimeTrace.acquisition_callback_trace?.receipt?.error_code)
    || readString(runtimeTrace.acquisition_callback_trace?.receipt?.error_message);
}

function readCallbackDeliveryAttempts(runtimeTrace: ArtifactRuntimeTrace): UnknownRecord[] {
  const attempts = runtimeTrace.acquisition_callback_trace?.operation?.providerDeliveryAttempts
    ?? runtimeTrace.acquisition_callback_trace?.operation?.provider_delivery_attempts;
  return (Array.isArray(attempts) ? attempts : [])
    .filter((attempt): attempt is UnknownRecord => Boolean(attempt) && typeof attempt === "object");
}
