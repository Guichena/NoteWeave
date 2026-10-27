import { type AnswerMode } from "../../routes";
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
      answerError: answer.error
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
      answerError: answer.error
    }
    : message);
}
