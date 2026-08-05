import { apiClient, type ApiClient } from "../../shared/api";
import {
  type MemoryReviewDecisionInput,
  type MemoryReviewDecisionResult,
  type MemoryReviewItem
} from "./model";

export class MemoryApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  listReviews(workspaceId: string, init?: RequestInit) {
    const path = `/api/v2/workspaces/${workspaceId}/memory/review`;
    return init
      ? this.client.get<MemoryReviewItem[]>(path, init)
      : this.client.get<MemoryReviewItem[]>(path);
  }

  decideReview(
    workspaceId: string,
    revisionId: string,
    input: MemoryReviewDecisionInput,
    init?: RequestInit
  ) {
    const path = `/api/v2/workspaces/${workspaceId}/memory/revisions/${revisionId}/review`;
    return init
      ? this.client.post<MemoryReviewDecisionResult>(path, input, init)
      : this.client.post<MemoryReviewDecisionResult>(path, input);
  }
}

export const memoryApi = new MemoryApi();
