export type SendAnswerInput = {
  content: string;
  answer_mode: string;
  client_request_id: string;
  source_scope_source_ids?: string[];
  /** 重新生成时带上原回答的 run ID，后端据此记录用户对原回答不满意。 */
  regenerated_from?: string;
  /** 依据研究报告追问时为 AUTO，通道里带 REPORT，grounding_refs 是允许取证的研究运行。 */
  retrieval_strategy?: "AUTO";
  retrieval_channels?: Array<"WORKSPACE" | "REPORT">;
  grounding_refs?: string[];
};

export type AnswerFeedbackRating = "POSITIVE" | "NEGATIVE";

export type AnswerFeedbackInput = {
  rating: AnswerFeedbackRating;
  reason?: string;
};

export type SendAnswerResponse = {
  assistant_message_id: string;
  assistant_request_id: string;
  stream_url: string;
  answer_run_id: string;
  answer_stream_url: string;
  /** 用深度研究发起的一轮返回研究运行，此时没有回答流 */
  research_run_id?: string | null;
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
