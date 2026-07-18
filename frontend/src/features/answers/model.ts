export type SendAnswerInput = {
  content: string;
  answer_mode: string;
  client_request_id: string;
  source_scope_source_ids?: string[];
};

export type SendAnswerResponse = {
  assistant_message_id: string;
  assistant_request_id: string;
  stream_url: string;
  answer_run_id: string;
  answer_stream_url: string;
  retrieval_degraded: boolean;
  retrieval_degradation_reasons: string[];
};

export type AnswerRunSnapshot = {
  id: string;
  status: string;
  content: string;
  error_code?: string;
  error_message?: string;
};

export type AnswerRunState = {
  runId: string;
  content: string;
  citations: string[];
  status: string;
  error: string;
  lastRunSequence: number;
};
