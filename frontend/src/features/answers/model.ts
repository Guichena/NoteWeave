import type { CitationProtocol } from "./evidence";

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
  citation_protocol?: CitationProtocol;
  event_sequence?: number;
};

export type AnswerRunState = {
  runId: string;
  content: string;
  citations: string[];
  status: string;
  error: string;
  lastRunSequence: number;
  /** 冻结的引用协议：新协议带允许集合与编号绑定，历史回答为 null。 */
  citationProtocol: CitationProtocol | null;
  /** 后台准备阶段：understanding / retrieving / retrieved / writing，正文开始后不再使用。 */
  stage?: string;
  /** 检索到的资料条数，来自 retrieved 阶段事件。 */
  evidenceCount?: number;
};

/** 回答仍在进行中的状态：后台准备、检索、生成与收尾。 */
export function isAnswerInProgress(status: string | undefined) {
  return status === "PREPARING" || status === "RETRIEVING"
    || status === "GENERATING" || status === "FINALIZING";
}
