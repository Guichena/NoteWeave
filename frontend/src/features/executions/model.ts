export type WaitContext = {
  status?: string;
  provider_job?: WaitProviderJob;
  approval_request?: WaitApprovalRequest;
  wait_reason?: WaitReason;
};

export type WaitProviderJob = {
  provider_id?: string;
  server_id?: string;
  tool_name?: string;
  capability_name?: string;
  operation_key?: string;
  provider_status?: string;
  health_status?: string;
  provider_job_status?: string;
  status?: string;
  request_id?: string;
  provider_job_id?: string;
  provider_receipt_id?: string;
  delivery_id?: string;
  callback_token?: string;
  adapter_callback_token?: string;
  callback_status?: string;
  dispatch_count?: number;
  previous_failed_delivery_count?: number;
  has_previous_failed_delivery?: boolean;
  provider_delivery_attempts?: WaitProviderDeliveryAttempt[];
  providerDeliveryAttempts?: WaitProviderDeliveryAttempt[];
};

export type WaitProviderDeliveryAttempt = {
  delivery_id?: string;
  deliveryId?: string;
  dispatch_count?: number;
  dispatchCount?: number;
  callback_token?: string;
  callbackToken?: string;
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

export type WaitApprovalRequest = {
  request_id?: string;
  task_id?: string;
  workspace_id?: string;
  capability_name?: string;
  provider_id?: string;
  server_id?: string;
  tool_name?: string;
  status?: string;
};

export type WaitBlockedOperation = {
  request_id?: string;
  source_id?: string;
  operation_key?: string;
  capability_name?: string;
  callback_status?: string;
};

export type WaitReason = {
  status?: string;
  provider_id?: string;
  operation_key?: string;
  capability_name?: string;
  unavailable_capabilities?: string[];
  capabilities?: string[];
  blocked_operations?: WaitBlockedOperation[];
};

export type ExecutionTask = {
  task_id: string;
  task_type: string;
  task_status: string;
  progress_phase: string;
  progress_message: string;
  result_ref: string;
  error_message: string;
  target_type: string;
  target_id: string;
  wait_context?: WaitContext | null;
};

export type ExecutionEvent = {
  id: string;
  event: string;
  data: string;
  message: string;
  payload: Record<string, unknown>;
  eventType: string;
  createdAt: string;
};

export type ExecutionSnapshot = {
  task: ExecutionTask;
  events: ExecutionEvent[];
  terminalRefetched: boolean;
};

export type ExecutionRecord = ExecutionSnapshot & {
  loading: boolean;
  error: string;
  lastLoadedAt: number;
};
