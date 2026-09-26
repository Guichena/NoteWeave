export type ArtifactRuntimeSummaryItem = {
  label: string;
  value: string;
};

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
