import type { ArtifactRuntimeTrace } from "./artifactRuntimeTrace";

export type ArtifactRuntimeDetailSection = {
  title: string;
  lines: string[];
};

export function buildArtifactRuntimeDetailSections(
  runtimeTrace?: ArtifactRuntimeTrace | null
): ArtifactRuntimeDetailSection[] {
  if (!runtimeTrace) {
    return [];
  }

  const sections: ArtifactRuntimeDetailSection[] = [];

  const generationMode = readString(runtimeTrace.generation_trace?.mode);
  if (generationMode) {
    sections.push({
      title: "Generation",
      lines: compactLines([
        `mode=${generationMode}`,
        readString(runtimeTrace.generation_trace?.provider)
          ? `provider=${readString(runtimeTrace.generation_trace?.provider)}`
          : "",
        readString(runtimeTrace.generation_trace?.model)
          ? `model=${readString(runtimeTrace.generation_trace?.model)}`
          : "",
        `attempted=${Boolean(runtimeTrace.generation_trace?.attempted)}`,
        `applied=${Boolean(runtimeTrace.generation_trace?.applied)}`,
        readString(runtimeTrace.generation_trace?.fallback_reason)
          ? `fallback=${readString(runtimeTrace.generation_trace?.fallback_reason)}`
          : "",
        `sections=${readNumber(runtimeTrace.generation_trace?.generated_section_count)}`,
        `sources=${readNumber(runtimeTrace.generation_trace?.source_count)}`
      ])
    });
  }

  const exportStatus = readString(runtimeTrace.export_trace?.status);
  if (exportStatus) {
    sections.push({
      title: "Export",
      lines: compactLines([
        `status=${exportStatus}`,
        readString(runtimeTrace.export_trace?.format)
          ? `format=${readString(runtimeTrace.export_trace?.format)}`
          : "",
        readString(runtimeTrace.export_trace?.file_name)
          ? `file=${readString(runtimeTrace.export_trace?.file_name)}`
          : "",
        readString(runtimeTrace.export_trace?.execution_mode)
          ? `mode=${readString(runtimeTrace.export_trace?.execution_mode)}`
          : "",
        joinValues("notes", runtimeTrace.export_trace?.notes, " / ")
      ])
    });
  }

  const verificationStatus = readString(runtimeTrace.verification?.status);
  if (verificationStatus) {
    sections.push({
      title: "Verifier",
      lines: compactLines([
        `status=${verificationStatus}`,
        `passed=${runtimeTrace.verification?.passed_checks?.length ?? 0}`
        + ` · repaired=${runtimeTrace.verification?.repaired_checks?.length ?? 0}`
        + ` · failed=${runtimeTrace.verification?.failed_checks?.length ?? 0}`
        + ` · warnings=${runtimeTrace.verification?.warnings?.length ?? 0}`
      ])
    });
  }

  if (verificationStatus) {
    const verifierSection = sections[sections.length - 1];
    if (verifierSection?.title === "Verifier") {
      verifierSection.lines.push(
        ...compactLines([
          joinValues("passed_checks", runtimeTrace.verification?.passed_checks, " / "),
          joinValues("repaired_checks", runtimeTrace.verification?.repaired_checks, " / "),
          joinValues("failed_checks", runtimeTrace.verification?.failed_checks, " / "),
          joinValues("warnings", runtimeTrace.verification?.warnings, " / ")
        ])
      );
    }
  }

  const approvalStatus = readString(runtimeTrace.approval_trace?.status);
  if (approvalStatus) {
    sections.push({
      title: "Approval",
      lines: compactLines([
        `status=${approvalStatus}`,
        joinValues("required", runtimeTrace.approval_trace?.required_capabilities),
        joinValues("pending", runtimeTrace.approval_trace?.pending_capabilities),
        joinValues("satisfied", runtimeTrace.approval_trace?.satisfied_capabilities)
      ])
    });
  }

  const policyStatus = readString(runtimeTrace.capability_union_trace?.status);
  if (policyStatus) {
    sections.push({
      title: "Policy",
      lines: compactLines([
        `status=${policyStatus}`,
        readString(runtimeTrace.capability_union_trace?.skill_scope)
          ? `skill=${readString(runtimeTrace.capability_union_trace?.skill_scope)}`
          : "",
        joinValues("external", runtimeTrace.capability_union_trace?.external_network_capabilities),
        joinValues("blocked", runtimeTrace.capability_union_trace?.blocked_capabilities)
      ])
    });
  }

  const evidenceStatus = readString(runtimeTrace.evidence_coverage?.status);
  if (evidenceStatus) {
    sections.push({
      title: "Evidence",
      lines: compactLines([
        `coverage=${readNumber(runtimeTrace.evidence_coverage?.covered_section_count)}/${readNumber(runtimeTrace.evidence_coverage?.section_count)}`,
        joinValues("sources", runtimeTrace.evidence_coverage?.supporting_source_ids, " / "),
        joinValues("missing", runtimeTrace.evidence_coverage?.sections_missing_evidence, " / ")
      ])
    });
  }

  const writebackStatus = readString(runtimeTrace.writeback_preview?.status);
  if (writebackStatus) {
    sections.push({
      title: "Writeback",
      lines: compactLines([
        `status=${writebackStatus}`,
        readString(runtimeTrace.writeback_preview?.requested_mode)
          ? `mode=${readString(runtimeTrace.writeback_preview?.requested_mode)}`
          : "",
        readString(runtimeTrace.writeback_preview?.execution_mode)
          ? `execution=${readString(runtimeTrace.writeback_preview?.execution_mode)}`
          : "",
        readString(runtimeTrace.writeback_preview?.target_locator_preview)
          ? `target=${readString(runtimeTrace.writeback_preview?.target_locator_preview)}`
          : ""
      ])
    });
  }

  const nodeLines = (Array.isArray(runtimeTrace.node_traces) ? runtimeTrace.node_traces : [])
    .flatMap((trace) => {
      const nodeId = readString(trace?.node_id) || readString(trace?.skill_key);
      const verification = readString(trace?.verification_status);
      if (!nodeId && !verification) {
        return [];
      }
      const repaired = trace?.repaired ? " · repaired" : "";
      return compactLines([
        `${nodeId || "node"}${verification ? ` · ${verification}` : ""}${repaired}`,
        readString(trace?.output_summary) ? `summary=${readString(trace?.output_summary)}` : "",
        joinValues("checks", trace?.verification_checks, " / "),
        joinValues("repairs", trace?.repair_actions, " / ")
      ]);
    });
  if (nodeLines.length > 0) {
    sections.push({
      title: "Nodes",
      lines: nodeLines
    });
  }

  const contractStatus = readString(runtimeTrace.output_contract_trace?.status);
  if (contractStatus) {
    const repairSummary = runtimeTrace.output_contract_trace?.repair_summary;
    const contractChecks = runtimeTrace.output_contract_trace?.contract_checks
      ?? runtimeTrace.output_contract_trace?.action_checks;
    sections.push({
      title: "Contract",
      lines: compactLines([
        `status=${contractStatus}`,
        `repaired=${runtimeTrace.output_contract_trace?.repaired_checks?.length ?? 0}`
        + ` · failed=${runtimeTrace.output_contract_trace?.failed_checks?.length ?? 0}`,
        readNumber(repairSummary?.total_repair_count) > 0
          ? `repair_summary=total=${readNumber(repairSummary?.total_repair_count)}`
            + ` · local=${readNumber(repairSummary?.local_repair_count)}`
            + ` · node=${readNumber(repairSummary?.node_repair_count)}`
          : "",
        joinValues("repair_sections", repairSummary?.affected_sections, " / "),
        joinValues("repair_nodes", repairSummary?.affected_nodes, " / "),
        joinValues("local_repairs", repairSummary?.local_repair_checks, " / "),
        joinValues("node_repairs", repairSummary?.node_repair_actions, " / "),
        joinNumberRecord("repair_categories", repairSummary?.category_counts),
        joinCheckSummaries("outline_checks", runtimeTrace.output_contract_trace?.outline_checks),
        joinCheckSummaries("phrase_checks", runtimeTrace.output_contract_trace?.phrase_checks),
        joinCheckSummaries("contract_checks", contractChecks),
        joinCheckSummaries("evidence_checks", runtimeTrace.output_contract_trace?.evidence_checks),
        joinValues("passed_checks", runtimeTrace.output_contract_trace?.passed_checks, " / "),
        joinValues("repaired_checks", runtimeTrace.output_contract_trace?.repaired_checks, " / "),
        joinValues("failed_checks", runtimeTrace.output_contract_trace?.failed_checks, " / "),
        joinValues("warnings", runtimeTrace.output_contract_trace?.warnings, " / ")
      ])
    });
  }

  const lifecycleStatus = readString(runtimeTrace.lifecycle_trace?.status);
  if (lifecycleStatus) {
    const lifecycleSteps = Array.isArray(runtimeTrace.lifecycle_trace?.steps) ? runtimeTrace.lifecycle_trace?.steps : [];
    const stepPhases = lifecycleSteps
      .map((step) => readString(step?.phase))
      .filter(Boolean)
      .slice(0, 5);
    const latestStep = lifecycleSteps.length > 0 ? lifecycleSteps[lifecycleSteps.length - 1] : null;
    const latestStepDetail = latestStep
      ? compactSegments([
        readString(latestStep.phase),
        readString(latestStep.status),
        readNumber(latestStep.progress_percent) > 0 ? `progress=${readNumber(latestStep.progress_percent)}%` : "",
        readString(latestStep.message)
      ])
      : "";
    const resumeScope = runtimeTrace.lifecycle_trace?.resume_scope;
    const resumeScopeDetail = compactSegments([
      readRecordString(resumeScope, "matched_request_id"),
      readRecordString(resumeScope, "matched_operation_key"),
      readRecordString(resumeScope, "matched_source_id")
    ]);
    sections.push({
      title: "Lifecycle",
      lines: compactLines([
        `status=${lifecycleStatus}`,
        readString(runtimeTrace.lifecycle_trace?.current_phase)
          ? `phase=${readString(runtimeTrace.lifecycle_trace?.current_phase)}`
          : "",
        stepPhases.length > 0 ? `steps=${stepPhases.join(" / ")}` : "",
        latestStepDetail ? `step_detail=${latestStepDetail}` : "",
        resumeScopeDetail ? `resume_scope=${resumeScopeDetail}` : ""
      ])
    });
  }

  const callbackStatus = readString(runtimeTrace.acquisition_callback_trace?.receipt?.provider_job_status)
    || readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_job_status)
    || readString(runtimeTrace.acquisition_callback_trace?.receipt?.callback_status)
    || readString(runtimeTrace.acquisition_callback_trace?.operation?.callback_status)
    || readString(runtimeTrace.acquisition_callback_trace?.status);
  const callbackReceiptStatus = readString(runtimeTrace.acquisition_callback_trace?.receipt?.callback_status)
    || readString(runtimeTrace.acquisition_callback_trace?.operation?.callback_status);
  if (callbackStatus) {
    const deliveryAttemptLines = buildDeliveryAttemptLines(
      runtimeTrace.acquisition_callback_trace?.operation?.providerDeliveryAttempts
      ?? runtimeTrace.acquisition_callback_trace?.operation?.provider_delivery_attempts
    );
    sections.push({
      title: "Callback",
      lines: compactLines([
        `status=${callbackStatus}`,
        readString(runtimeTrace.acquisition_callback_trace?.operation?.request_id)
          ? `request=${readString(runtimeTrace.acquisition_callback_trace?.operation?.request_id)}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.operation?.capability_name)
          ? `capability=${readString(runtimeTrace.acquisition_callback_trace?.operation?.capability_name)}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_id)
          || readString(runtimeTrace.acquisition_callback_trace?.operation?.server_id)
          || readString(runtimeTrace.acquisition_callback_trace?.operation?.tool_name)
          ? `provider=${[
            readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_id),
            readString(runtimeTrace.acquisition_callback_trace?.operation?.server_id),
            readString(runtimeTrace.acquisition_callback_trace?.operation?.tool_name)
          ].filter(Boolean).join(" / ")}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_status)
          || readString(runtimeTrace.acquisition_callback_trace?.operation?.health_status)
          ? `provider_state=${[
            readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_status),
            readString(runtimeTrace.acquisition_callback_trace?.operation?.health_status)
          ].filter(Boolean).join("/")}`
          : "",
        callbackReceiptStatus && callbackReceiptStatus !== callbackStatus
          ? `callback=${callbackReceiptStatus}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.receipt?.request_id)
          || readString(runtimeTrace.acquisition_callback_trace?.receipt?.task_id)
          || readString(runtimeTrace.acquisition_callback_trace?.receipt?.source_id)
          || readString(runtimeTrace.acquisition_callback_trace?.receipt?.operation_key)
          ? `receipt_scope=${[
            readString(runtimeTrace.acquisition_callback_trace?.receipt?.request_id),
            readString(runtimeTrace.acquisition_callback_trace?.receipt?.task_id),
            readString(runtimeTrace.acquisition_callback_trace?.receipt?.source_id),
            readString(runtimeTrace.acquisition_callback_trace?.receipt?.operation_key)
          ].filter(Boolean).join(" / ")}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.receipt?.provider_job_id)
          || readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_job_id)
          ? `provider_job=${readString(runtimeTrace.acquisition_callback_trace?.receipt?.provider_job_id)
            || readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_job_id)}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.receipt?.delivery_id)
          || readString(runtimeTrace.acquisition_callback_trace?.operation?.delivery_id)
          ? `delivery=${readString(runtimeTrace.acquisition_callback_trace?.receipt?.delivery_id)
            || readString(runtimeTrace.acquisition_callback_trace?.operation?.delivery_id)}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.receipt?.provider_receipt_id)
          || readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_receipt_id)
          ? `provider_receipt=${readString(runtimeTrace.acquisition_callback_trace?.receipt?.provider_receipt_id)
            || readString(runtimeTrace.acquisition_callback_trace?.operation?.provider_receipt_id)}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.receipt?.result_locator)
          ? `result=${readString(runtimeTrace.acquisition_callback_trace?.receipt?.result_locator)}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.receipt?.error_code)
          || readString(runtimeTrace.acquisition_callback_trace?.receipt?.error_message)
          ? `error=${[
            readString(runtimeTrace.acquisition_callback_trace?.receipt?.error_code),
            readString(runtimeTrace.acquisition_callback_trace?.receipt?.error_message)
          ].filter(Boolean).join(": ")}`
          : "",
        readString(runtimeTrace.acquisition_callback_trace?.receipt?.completed_at)
          ? `completed_at=${readString(runtimeTrace.acquisition_callback_trace?.receipt?.completed_at)}`
          : "",
        readDispatchCount(runtimeTrace) > 0
          ? `dispatch_count=${readDispatchCount(runtimeTrace)}`
          : "",
        ...deliveryAttemptLines
      ])
    });
  }

  return sections;
}

function readString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function readNumber(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function readRecordString(record: Record<string, unknown> | null | undefined, key: string): string {
  if (!record || typeof record !== "object") {
    return "";
  }
  return readString(record[key]);
}

function readDispatchCount(runtimeTrace: ArtifactRuntimeTrace): number {
  return readNumber(runtimeTrace.acquisition_callback_trace?.receipt?.dispatch_count)
    || readNumber(runtimeTrace.acquisition_callback_trace?.operation?.dispatch_count)
    || readNumber(runtimeTrace.acquisition_callback_trace?.operation?.dispatchCount);
}

function buildDeliveryAttemptLines(attempts: Array<Record<string, unknown> | null | undefined> | undefined): string[] {
  const normalizedAttempts = (Array.isArray(attempts) ? attempts : [])
    .filter((attempt): attempt is Record<string, unknown> => Boolean(attempt));
  if (normalizedAttempts.length === 0) {
    return [];
  }

  const lines = [`attempts=${normalizedAttempts.length}`];
  normalizedAttempts.slice(0, 5).forEach((attempt, index) => {
    const status = readString(attempt["ackStatus"]) || readString(attempt["ack_status"]) || "PENDING";
    const fragments = [
      status,
      readAttemptDispatchCount(attempt) > 0 ? `dispatch=${readAttemptDispatchCount(attempt)}` : "",
      readDualString(attempt, "deliveryId", "delivery_id") ? `delivery=${readDualString(attempt, "deliveryId", "delivery_id")}` : "",
      buildAttemptError(attempt),
      readDualString(attempt, "resultLocator", "result_locator") ? `result=${readDualString(attempt, "resultLocator", "result_locator")}` : "",
      readDualString(attempt, "callbackReceivedAt", "callback_received_at") ? `callback_at=${readDualString(attempt, "callbackReceivedAt", "callback_received_at")}` : ""
    ].filter((fragment) => fragment.trim().length > 0);
    lines.push(`attempt#${index + 1}=${fragments.join(" / ")}`);
  });
  if (normalizedAttempts.length > 5) {
    lines.push(`attempts_truncated=${normalizedAttempts.length - 5}`);
  }
  return lines;
}

function compactSegments(parts: string[]): string {
  return parts.filter(Boolean).join(" / ");
}

function readAttemptDispatchCount(attempt: Record<string, unknown>): number {
  return readNumber(attempt["dispatchCount"]) || readNumber(attempt["dispatch_count"]);
}

function buildAttemptError(attempt: Record<string, unknown>): string {
  const code = readDualString(attempt, "errorCode", "error_code");
  const message = readDualString(attempt, "errorMessage", "error_message");
  const value = [code, message].filter(Boolean).join(": ");
  return value ? `error=${value}` : "";
}

function readDualString(record: Record<string, unknown>, camelKey: string, snakeKey: string): string {
  return readString(record[camelKey]) || readString(record[snakeKey]);
}

function joinValues(label: string, values: string[] | undefined, separator = ", "): string {
  const normalized = (Array.isArray(values) ? values : [])
    .map((value) => readString(value))
    .filter(Boolean);
  return normalized.length > 0 ? `${label}=${normalized.join(separator)}` : "";
}

function joinNumberRecord(label: string, values: Record<string, number> | undefined): string {
  if (!values || typeof values !== "object") {
    return "";
  }
  const normalized = Object.entries(values)
    .filter((entry) => typeof entry[1] === "number" && Number.isFinite(entry[1]))
    .map(([key, value]) => `${readString(key) || key}=${value}`);
  return normalized.length > 0 ? `${label}=${normalized.join(" / ")}` : "";
}

function joinCheckSummaries(
  label: string,
  checks: Array<{ label?: string; status?: string } | null | undefined> | undefined
): string {
  const normalized = (Array.isArray(checks) ? checks : [])
    .flatMap((check) => {
      if (!check || typeof check !== "object") {
        return [];
      }
      const checkLabel = readString(check.label);
      const checkStatus = readString(check.status);
      if (!checkLabel && !checkStatus) {
        return [];
      }
      return [compactSegments([checkStatus, checkLabel])];
    })
    .filter(Boolean);
  return normalized.length > 0 ? `${label}=${normalized.join(" / ")}` : "";
}

function compactLines(lines: string[]): string[] {
  return lines.filter((line) => line.trim().length > 0);
}
