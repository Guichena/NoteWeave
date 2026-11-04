import { type Message } from "./messageTypes";

/**
 * 正文出现前按后台准备阶段给出进度提示；正文开始流出后统一显示正在生成。
 * 没有阶段事件时（实时连接断开或旧版本后端），按运行状态给出大致提示。
 */
export function answerProgressLabel(message: Pick<Message, "content" | "answerStatus" | "answerStage" | "answerEvidenceCount">) {
  if (message.content) return "正在生成回答";
  const count = message.answerEvidenceCount;
  const found = typeof count === "number"
    ? (count > 0 ? `检索到 ${count} 条资料` : "没有检索到相关资料")
    : "";
  switch (message.answerStage) {
    case "understanding":
      return "正在理解问题";
    case "retrieving":
      return "正在检索资料";
    case "retrieved":
      return found ? `${found}，正在整理` : "正在整理资料";
    case "writing":
      return found ? `${found}，正在撰写` : "正在撰写回答";
    default:
      return message.answerStatus === "PREPARING" ? "正在理解问题" : "正在生成回答";
  }
}
