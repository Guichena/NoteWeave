import { describe, expect, it } from "vitest";
import { resolveMissingContentState } from "./emptyAnswer";
import { createAnswerRunState } from "../answers/store";

describe("resolveMissingContentState", () => {
  it("失败的回答保持失败状态并带可读原因", () => {
    const state = {
      ...createAnswerRunState("run-failed"),
      status: "FAILED",
      error: "Answer LLM is not configured"
    };

    const resolved = resolveMissingContentState("run-failed", state);

    expect(resolved.status).toBe("FAILED");
    expect(resolved.content).toBe("");
    expect(resolved.error).toBe("Answer LLM is not configured");
  });

  it("取消的回答保持取消状态", () => {
    const resolved = resolveMissingContentState("run-cancelled", {
      ...createAnswerRunState("run-cancelled"),
      status: "CANCELLED"
    });

    expect(resolved.status).toBe("CANCELLED");
    expect(resolved.error).toBe("回答已取消");
  });

  it("仍在生成的回答保留等待态，不写入伪正文", () => {
    const resolved = resolveMissingContentState("run-generating", {
      ...createAnswerRunState("run-generating"),
      status: "GENERATING"
    });

    expect(resolved.status).toBe("GENERATING");
    expect(resolved.content).toBe("");
  });

  it("已完成但没有正文时如实标记失败，不再显示为完成", () => {
    const resolved = resolveMissingContentState("run-empty", {
      ...createAnswerRunState("run-empty"),
      status: "COMPLETED"
    });

    expect(resolved.status).toBe("FAILED");
    expect(resolved.error).toBe("回答已完成，但没有返回正文");
  });

  it("没有权威状态时按失败处理，不默认成完成", () => {
    const resolved = resolveMissingContentState("run-unknown");

    expect(resolved.runId).toBe("run-unknown");
    expect(resolved.status).toBe("FAILED");
    expect(resolved.error).toBe("无法确认回答状态，请重试");
  });
});
