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
