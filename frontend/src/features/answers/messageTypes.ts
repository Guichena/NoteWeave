import { type AnswerMode } from "../../routes";
import { type CitationProtocol } from "./evidence";
import { type AnswerRunState } from "./model";

export type Message = {
  role: "user" | "assistant" | "system";
  content: string;
  answerMode?: AnswerMode;
  citations?: string[];
  answerRunId?: string;
  answerStatus?: string;
  answerError?: string;
  /** RET-01：检索链路降级（例如归属门控丢掉了候选），回答可能不完整。 */
  retrievalDegraded?: boolean;
  retrievalDegradationReasons?: string[];
  /** 深度研究轮次的助手消息：按研究运行展示进度与报告，不走回答流 */
  researchRunId?: string;
  /** F3-03：后端结构化研究投影 JSON，研究结论卡据此渲染。 */
  researchProjectionJson?: string;
  /** 流式先到的引用协议；缺失时由 MessageBubble 补拉证据清单解析。 */
  citationProtocol?: CitationProtocol | null;
  /** 正文出现前的准备阶段与检索到的资料条数，用于进度提示。 */
  answerStage?: string;
  answerEvidenceCount?: number;
  /** 消息已被删除：只显示删除说明，不再提供证据、反馈和重新生成。 */
  deleted?: boolean;
};
export function updateLastAssistantMessage(current: Message[], answer: AnswerRunState) {
  let targetIndex = -1;
  for (let index = current.length - 1; index >= 0; index -= 1) {
    if (current[index].role === "assistant") {
      targetIndex = index;
      break;
    }
  }
  if (targetIndex < 0) {
    return current;
  }
  return current.map((message, index) => index === targetIndex
    ? {
      ...message,
      content: answer.content,
      citations: [...answer.citations],
      answerStatus: answer.status,
      answerError: answer.error,
      citationProtocol: answer.citationProtocol,
      answerStage: answer.stage,
      answerEvidenceCount: answer.evidenceCount
    }
    : message);
}

export function updateAssistantMessageByRun(
  current: Message[],
  answerRunId: string,
  answer: AnswerRunState
) {
  return current.map((message) => message.answerRunId === answerRunId
    ? {
      ...message,
      content: answer.content,
      citations: [...answer.citations],
      answerStatus: answer.status,
      answerError: answer.error,
      citationProtocol: answer.citationProtocol,
      answerStage: answer.stage,
      answerEvidenceCount: answer.evidenceCount
    }
    : message);
}
