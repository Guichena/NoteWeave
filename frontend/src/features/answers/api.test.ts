import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "../../shared/api";
import { AnswersApi } from "./api";

describe("AnswersApi", () => {
  it("owns the conversation message submission contract", async () => {
    const post = vi.fn(async () => ({ answer_run_id: "run" }));
    const api = new AnswersApi({ post } as unknown as ApiClient);
    const input = {
      content: "question",
      answer_mode: "QA",
      client_request_id: "request"
    };

    await api.send("conversation", input);

    expect(post).toHaveBeenCalledWith(
      "/api/v2/conversations/conversation/messages",
      input
    );
  });

  it("owns the terminal AnswerRun reconciliation path", async () => {
    const get = vi.fn(async () => ({
      id: "run",
      status: "COMPLETED",
      content: "canonical"
    }));
    const api = new AnswersApi({ get } as unknown as ApiClient);

    await api.getRun("workspace", "run");

    expect(get).toHaveBeenCalledWith("/api/v2/workspaces/workspace/answer-runs/run");
  });
});
