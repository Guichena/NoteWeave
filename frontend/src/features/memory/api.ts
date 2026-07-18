import { apiClient, type ApiClient } from "../../shared/api";
import {
  type AppendMemoryVersionInput,
  type MemoryItemKind,
  type MemoryReviewDecisionInput,
  type MemoryReviewDecisionResult,
  type MemoryReviewItem,
  type MemoryReviewKind,
  type MemoryVersion
} from "./model";

export class MemoryApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  listReviews(
    workspaceId: string,
    kind: MemoryReviewKind = "ALL",
    limit = 50,
    init?: RequestInit
  ) {
    const query = new URLSearchParams({
      kind,
      limit: String(Math.max(1, Math.min(limit, 200)))
    });
    return this.get<MemoryReviewItem[]>(
      `/api/v2/workspaces/${workspaceId}/memory/reviews?${query}`,
      init
    );
  }

  decideReview(
    workspaceId: string,
    reviewKind: MemoryItemKind,
    reviewId: string,
    input: MemoryReviewDecisionInput,
    init?: RequestInit
  ) {
    const path = `/api/v2/workspaces/${workspaceId}/memory/reviews/${reviewKind}/${reviewId}/decisions`;
    return init
      ? this.client.post<MemoryReviewDecisionResult>(path, input, init)
      : this.client.post<MemoryReviewDecisionResult>(path, input);
  }

  listVersions(workspaceId: string, memoryObjectId: string, init?: RequestInit) {
    return this.get<MemoryVersion[]>(
      `/api/v2/workspaces/${workspaceId}/memory/objects/${memoryObjectId}/versions`,
      init
    );
  }

  getVersion(
    workspaceId: string,
    memoryObjectId: string,
    memoryVersionId: string,
    init?: RequestInit
  ) {
    return this.get<MemoryVersion>(
      `/api/v2/workspaces/${workspaceId}/memory/objects/${memoryObjectId}/versions/${memoryVersionId}`,
      init
    );
  }

  appendVersion(
    workspaceId: string,
    memoryObjectId: string,
    input: AppendMemoryVersionInput,
    init?: RequestInit
  ) {
    const path = `/api/v2/workspaces/${workspaceId}/memory/objects/${memoryObjectId}/versions`;
    return init
      ? this.client.post<MemoryVersion>(path, input, init)
      : this.client.post<MemoryVersion>(path, input);
  }

  revoke(workspaceId: string, memoryObjectId: string, init?: RequestInit) {
    const path = `/api/v2/workspaces/${workspaceId}/memory/objects/${memoryObjectId}/revoke`;
    return init
      ? this.client.post<MemoryVersion>(path, {}, init)
      : this.client.post<MemoryVersion>(path, {});
  }

  private get<T>(path: string, init?: RequestInit) {
    return init ? this.client.get<T>(path, init) : this.client.get<T>(path);
  }
}

export const memoryApi = new MemoryApi();
