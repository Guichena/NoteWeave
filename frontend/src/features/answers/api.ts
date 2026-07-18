import { apiClient, type ApiClient } from "../../shared/api";
import {
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
    );
  }
}

export const answersApi = new AnswersApi();
