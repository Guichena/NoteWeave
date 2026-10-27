import { createAnswerRunState } from "../answers/store";
import type { AnswerRunState } from "../answers/model";

/**
 * 回答没有正文时应该显示的状态。
 *
 * <p>此前调用方无论后端返回什么状态，都会把空内容改写成 COMPLETED 并补一句
 * 「后端已完成回答」，失败与取消被伪装成成功。这里严格按权威状态分支：</p>
 *
 * <ul>
 *   <li>FAILED / CANCELLED：保留原状态，补上可读的失败原因；</li>
 *   <li>GENERATING / FINALIZING：仍是进行中，保留等待态，不写入伪正文；</li>
 *   <li>其余终态（含 COMPLETED）但没有正文：如实标记失败，提供重试入口。</li>
 * </ul>
 */
export function resolveMissingContentState(
  answerRunId: string,
  state?: AnswerRunState
): AnswerRunState {
  if (!state) {
    // 拿不到权威状态时不能默认成完成，也不能让界面一直转圈
    return {
      ...createAnswerRunState(answerRunId),
      status: "FAILED",
      error: "无法确认回答状态，请重试"
    };
  }
  const base = state;
  const status = (base.status || "").toUpperCase();
  if (status === "FAILED" || status === "CANCELLED") {
    return {
      ...base,
      content: "",
      status,
      error: base.error || (status === "CANCELLED" ? "回答已取消" : "回答生成失败")
    };
  }
  if (status === "GENERATING" || status === "FINALIZING") {
    return { ...base, content: "" };
  }
  return {
    ...base,
    content: "",
    status: "FAILED",
    error: base.error || "回答已完成，但没有返回正文"
  };
}
