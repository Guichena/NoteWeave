export type ArtifactRuntimeSummaryItem = {
  label: string;
  value: string;
};

type UnknownRecord = Record<string, unknown>;

export type ArtifactRuntimeTrace = {
  verification?: ArtifactVerificationTrace | null;
  generation_trace?: ArtifactGenerationTrace | null;
  export_trace?: ArtifactExportTrace | null;
  approval_trace?: ArtifactApprovalTrace | null;
  capability_union_trace?: ArtifactCapabilityUnionTrace | null;
  node_traces?: ArtifactNodeTrace[] | null;
  evidence_coverage?: ArtifactEvidenceCoverageTrace | null;
  writeback_preview?: ArtifactWritebackPreviewTrace | null;
  output_contract_trace?: ArtifactOutputContractTrace | null;
  lifecycle_trace?: ArtifactLifecycleTrace | null;
  acquisition_callback_trace?: ArtifactAcquisitionCallbackTrace | null;
};

export type ArtifactGenerationTrace = {
  mode?: string;
  provider?: string;
  model?: string;
  attempted?: boolean;
  applied?: boolean;
  fallback_reason?: string;
  generated_section_count?: number;
  source_count?: number;
};

export type ArtifactExportTrace = {
  status?: string;
  format?: string;
  file_name?: string;
  download_path?: string;
  execution_mode?: string;
  notes?: string[];
};

export type ArtifactVerificationTrace = {
  status?: string;
  passed_checks?: string[];
  repaired_checks?: string[];
  failed_checks?: string[];
  warnings?: string[];
};

export type ArtifactApprovalTrace = {
  status?: string;
  decision?: string;
  reason_code?: string;
  required_capabilities?: string[];
  pending_capabilities?: string[];
  satisfied_capabilities?: string[];
  approval_request?: Record<string, unknown>;
  capability_decisions?: ArtifactApprovalCapabilityDecision[];
  notes?: string[];
};

export type ArtifactApprovalCapabilityDecision = {
  capability_name?: string;
  provider_id?: string;
  server_id?: string;
  tool_name?: string;
  approval_status?: string;
  provider_status?: string;
  health_status?: string;
  discovery_status?: string;
  selection_reason?: string;
  runtime_status?: string;
};

export type ArtifactCapabilityUnionTrace = {
  status?: string;
  decision?: string;
  reason_code?: string;
  policy_key?: string;
  skill_scope?: string;
  workspace_scope?: string;
  capability_scope?: string[];
  external_network_capabilities?: string[];
  writeback_capabilities?: string[];
  blocked_capabilities?: string[];
  capability_decisions?: ArtifactCapabilityUnionDecision[];
  notes?: string[];
};

export type ArtifactCapabilityUnionDecision = {
  capability_name?: string;
  scope_type?: string;
  server_id?: string;
  tool_name?: string;
  provider_id?: string;
  risk_level?: string;
  approval_mode?: string;
  discovery_status?: string;
  provider_status?: string;
  health_status?: string;
  approval_status?: string;
  selection_reason?: string;
  route_basis?: string;
  runtime_status?: string;
};

export type ArtifactNodeTrace = {
  node_id?: string;
  skill_key?: string;
  output_summary?: string;
  verification_status?: string;
  verification_checks?: string[];
  repair_actions?: string[];
  repaired?: boolean;
};

export type ArtifactEvidenceCoverageTrace = {
  status?: string;
  required_citation_density?: string;
  section_count?: number;
  covered_section_count?: number;
  coverage_ratio?: number;
  supporting_source_ids?: string[];
  sections_missing_evidence?: string[];
  notes?: string[];
};

export type ArtifactWritebackPreviewTrace = {
  status?: string;
  requested_mode?: string;
  allowed_target?: string;
  execution_mode?: string;
  required_capabilities?: string[];
  version_id?: string;
  request_id?: string;
  target_locator_preview?: string;
  notes?: string[];
};

export type ArtifactContractCheckTrace = {
  label?: string;
  status?: string;
  detail?: string;
  metadata?: Record<string, unknown>;
};

export type ArtifactRepairSummaryTrace = {
  total_repair_count?: number;
  local_repair_count?: number;
  node_repair_count?: number;
  affected_sections?: string[];
  affected_nodes?: string[];
  local_repair_checks?: string[];
  node_repair_actions?: string[];
  category_counts?: Record<string, number>;
  notes?: string[];
};

export type ArtifactOutputContractTrace = {
  status?: string;
  outline_checks?: ArtifactContractCheckTrace[];
  phrase_checks?: ArtifactContractCheckTrace[];
  contract_checks?: ArtifactContractCheckTrace[];
  action_checks?: ArtifactContractCheckTrace[];
  evidence_checks?: ArtifactContractCheckTrace[];
  repair_summary?: ArtifactRepairSummaryTrace | null;
  repaired_checks?: string[];
  passed_checks?: string[];
  failed_checks?: string[];
  warnings?: string[];
  notes?: string[];
};

export type ArtifactLifecycleStepTrace = {
  phase?: string;
  status?: string;
  progress_percent?: number;
  message?: string;
  metrics?: Record<string, unknown>;
};

export type ArtifactLifecycleTrace = {
  status?: string;
  current_phase?: string;
  steps?: ArtifactLifecycleStepTrace[];
  notes?: string[];
  resume_scope?: Record<string, unknown>;
};

export type ArtifactAcquisitionCallbackTrace = {
  status?: string;
  receipt?: ArtifactAcquisitionCallbackReceiptTrace | null;
  operation?: ArtifactAcquisitionOperationTrace | null;
};

export type ArtifactAcquisitionCallbackReceiptTrace = {
  receipt_id?: string;
  request_id?: string;
  task_id?: string;
  source_id?: string;
  operation_key?: string;
  delivery_id?: string;
  provider_job_status?: string;
  callback_status?: string;
  provider_receipt_id?: string;
  provider_job_id?: string;
  result_locator?: string;
  completed_at?: string;
  dispatch_count?: number;
  error_code?: string;
  error_message?: string;
};

export type ArtifactAcquisitionOperationTrace = {
  request_id?: string;
  provider_job_id?: string;
  capability_name?: string;
  provider_id?: string;
  server_id?: string;
  tool_name?: string;
  provider_status?: string;
  health_status?: string;
  provider_job_status?: string;
  callback_status?: string;
  delivery_id?: string;
  provider_receipt_id?: string;
  dispatch_count?: number;
  dispatchCount?: number;
  provider_delivery_attempts?: ArtifactAcquisitionDeliveryAttemptTrace[];
  providerDeliveryAttempts?: ArtifactAcquisitionDeliveryAttemptTrace[];
};

export type ArtifactAcquisitionDeliveryAttemptTrace = {
  delivery_id?: string;
  deliveryId?: string;
  dispatch_count?: number;
  dispatchCount?: number;
  dispatched_at?: string;
  dispatchedAt?: string;
  callback_deadline_at?: string;
  callbackDeadlineAt?: string;
  provider_job_id?: string;
  providerJobId?: string;
  provider_receipt_id?: string;
  providerReceiptId?: string;
  input_digest?: string;
  inputDigest?: string;
  ack_status?: string;
  ackStatus?: string;
  callback_received_at?: string;
  callbackReceivedAt?: string;
  result_locator?: string;
  resultLocator?: string;
  error_code?: string;
  errorCode?: string;
  error_message?: string;
  errorMessage?: string;
};

export function buildArtifactRuntimeSummary(runtimeTrace?: ArtifactRuntimeTrace | null): ArtifactRuntimeSummaryItem[] {
  if (!runtimeTrace) {
    return [];
  }

  const summary: ArtifactRuntimeSummaryItem[] = [];

  const verificationStatus = readString(runtimeTrace.verification?.status);
  if (verificationStatus) {
    summary.push({
      label: "Verifier",
      value: verificationStatus
    });
  }

  const generationMode = readString(runtimeTrace.generation_trace?.mode);
  if (generationMode) {
    const model = readString(runtimeTrace.generation_trace?.model);
    const fallbackReason = readString(runtimeTrace.generation_trace?.fallback_reason);
    summary.push({
      label: "Generation",
      value: [generationMode, model, fallbackReason].filter(Boolean).join(" · ")
    });
  }

  const exportStatus = readString(runtimeTrace.export_trace?.status);
  if (exportStatus) {
    const format = readString(runtimeTrace.export_trace?.format);
    const fileName = readString(runtimeTrace.export_trace?.file_name);
    summary.push({
      label: "Export",
      value: [exportStatus, format, fileName].filter(Boolean).join(" · ")
    });
  }

  const approvalStatus = readString(runtimeTrace.approval_trace?.status);
  if (approvalStatus) {
    const satisfied = runtimeTrace.approval_trace?.satisfied_capabilities?.length ?? 0;
    const pending = runtimeTrace.approval_trace?.pending_capabilities?.length ?? 0;
    summary.push({
      label: "Approval",
      value: `${approvalStatus} · ok=${satisfied} · pending=${pending}`
    });
  }

  const capabilityUnionStatus = readString(runtimeTrace.capability_union_trace?.status);
  if (capabilityUnionStatus) {
    const blocked = runtimeTrace.capability_union_trace?.blocked_capabilities?.length ?? 0;
    const external = runtimeTrace.capability_union_trace?.external_network_capabilities?.length ?? 0;
    summary.push({
      label: "Policy",
      value: `${capabilityUnionStatus} · blocked=${blocked} · external=${external}`
    });
  }

  const evidenceStatus = readString(runtimeTrace.evidence_coverage?.status);
  if (evidenceStatus) {
    const sectionCount = readNumber(runtimeTrace.evidence_coverage?.section_count);
    const coveredSectionCount = readNumber(runtimeTrace.evidence_coverage?.covered_section_count);
    const missingEvidenceCount = runtimeTrace.evidence_coverage?.sections_missing_evidence?.length ?? 0;
    summary.push({
      label: "Evidence",
      value: `${evidenceStatus} · covered=${coveredSectionCount}/${sectionCount} · missing=${missingEvidenceCount}`
    });
  }

  const writebackStatus = readString(runtimeTrace.writeback_preview?.status);
  if (writebackStatus) {
    const requestedMode = readString(runtimeTrace.writeback_preview?.requested_mode);
    const executionMode = readString(runtimeTrace.writeback_preview?.execution_mode);
    const writebackPrimary = requestedMode || writebackStatus;
    const writebackSuffix = executionMode || (!requestedMode ? "" : "");
    summary.push({
      label: "Writeback",
      value: writebackSuffix ? `${writebackPrimary} / ${writebackSuffix}` : writebackPrimary
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
    const repairedChecks = runtimeTrace.output_contract_trace?.repaired_checks?.length ?? 0;
    const failedChecks = runtimeTrace.output_contract_trace?.failed_checks?.length ?? 0;
    summary.push({
      label: "Contract",
      value: `${contractStatus} · repair=${repairedChecks} · fail=${failedChecks}`
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
    const requestId = readString(runtimeTrace.acquisition_callback_trace?.operation?.request_id);
    const callbackSummaryParts = [callbackStatus];
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
    summary.push({
      label: "Callback",
      value: callbackSummaryParts.join(" / ")
    });
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
    const ackStatus = readString(attempt["ackStatus"]) || readString(attempt["ack_status"]);
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
