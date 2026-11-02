import { apiClient, type ApiClient } from "../../shared/api";
import { parseAnswerEvidenceManifest, parseCitationProtocol, type AnswerEvidenceManifest } from "./evidence";
import {
  type AnswerFeedbackInput,
  type AnswerRunSnapshot,
  type SendAnswerInput,
  type SendAnswerResponse
} from "./model";

export class AnswersApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  send(conversationId: string, input: SendAnswerInput) {
    return this.client.post<SendAnswerResponse>(
      `/api/v2/conversations/${conversationId}/messages`,
      input
    );
  }

  getRun(workspaceId: string, runId: string) {
    return this.client.get<AnswerRunSnapshot>(
      `/api/v2/workspaces/${workspaceId}/answer-runs/${runId}`
    ).then((run) => ({ ...run, citation_protocol: parseCitationProtocol(run.citation_protocol) }));
  }

  /** 证据清单：按后端冻结的协议解析，编号绑定与允许集合以协议为准。 */
  getEvidence(workspaceId: string, runId: string): Promise<AnswerEvidenceManifest> {
    return this.client
      .get<unknown>(`/api/v2/workspaces/${workspaceId}/answer-runs/${runId}/evidence`)
      .then(parseAnswerEvidenceManifest);
  }

  sendFeedback(workspaceId: string, runId: string, input: AnswerFeedbackInput) {
    return this.client.post<unknown>(
      `/api/v2/workspaces/${workspaceId}/answer-runs/${runId}/feedback`,
      input
    );
  }

  /** 停止正在生成的回答；后端把运行置为 CANCELLED 并广播终态事件。 */
  cancel(workspaceId: string, runId: string) {
    return this.client.deleteJson<AnswerRunSnapshot>(
      `/api/v2/workspaces/${workspaceId}/answer-runs/${runId}`
    );
  }
}

export const answersApi = new AnswersApi();
