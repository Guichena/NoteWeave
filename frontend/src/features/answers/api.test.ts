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

  it("cancels a running answer through the DELETE endpoint", async () => {
    const deleteJson = vi.fn(async () => ({ id: "run", status: "CANCELLED", content: "" }));
    const api = new AnswersApi({ deleteJson } as unknown as ApiClient);

    await api.cancel("workspace", "run");

    expect(deleteJson).toHaveBeenCalledWith("/api/v2/workspaces/workspace/answer-runs/run");
  });
});

describe("AnswersApi 证据清单", () => {
  it("解析引用协议与编号绑定，缺字段的历史回答按旧协议返回", async () => {
    const get = vi.fn(async () => ({
      run_id: "run",
      evidence: [],
      protocol_version: "citation-binding-v1",
      allowed_evidence_ids: ["passage:c1"],
      citation_bindings: [{ number: 1, evidence_id: "passage:c1", citation_ids: ["citation-1"] }]
    }));
    const api = new AnswersApi({ get } as unknown as ApiClient);

    const manifest = await api.getEvidence("workspace", "run");

    expect(get).toHaveBeenCalledWith("/api/v2/workspaces/workspace/answer-runs/run/evidence");
    expect(manifest.citation_protocol).toEqual({
      state: "BINDING_V1",
      protocolVersion: "citation-binding-v1",
      allowedEvidenceIds: ["passage:c1"],
      citationBindings: [{ number: 1, evidenceId: "passage:c1", citationIds: ["citation-1"] }]
    });
  });

  it("缺协议字段时为 UNKNOWN，不推断历史", async () => {
    const get = vi.fn(async () => ({ run_id: "run", evidence: [] }));
    const api = new AnswersApi({ get } as unknown as ApiClient);

    expect((await api.getEvidence("workspace", "run")).citation_protocol).toMatchObject({ state: "UNKNOWN" });
  });
});