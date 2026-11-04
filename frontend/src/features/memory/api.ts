import { apiClient, type ApiClient } from "../../shared/api";
import {
  type CreateMemorySignalInput,
  type MemoryControlPack,
  type MemoryItem,
  type MemoryPromotionResult,
  type MemoryReviewDecisionInput,
  type MemoryReviewDecisionResult,
  type MemorySignal,
  type RevokedMemoryItem
} from "./model";

export class MemoryApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  listItems(workspaceId: string, init?: RequestInit) {
    const path = `/api/v2/workspaces/${workspaceId}/memory/items`;
    return init
      ? this.client.get<MemoryItem[]>(path, init)
      : this.client.get<MemoryItem[]>(path);
  }

  /** 用户停用过、可以重新启用的记忆。 */
  listRevokedItems(workspaceId: string) {
    return this.client.get<RevokedMemoryItem[]>(`/api/v2/workspaces/${workspaceId}/memory/items/revoked`);
  }

  /** 重新启用一条已停用的记忆。 */
  restoreItem(workspaceId: string, memoryItemId: string) {
    return this.client.post<MemoryReviewDecisionResult>(
      `/api/v2/workspaces/${workspaceId}/memory/items/${memoryItemId}/restore`,
      {}
    );
  }

  /** 预览某个问题在指定回答模式下会编入哪些记忆。 */
  previewChatControlPack(workspaceId: string, answerMode: string, query: string) {
    const params = new URLSearchParams({ answer_mode: answerMode, query });
    return this.client.get<MemoryControlPack>(`/api/v2/workspaces/${workspaceId}/memory/control-pack/chat?${params}`);
  }

  createSignal(workspaceId: string, input: CreateMemorySignalInput) {
    return this.client.post<MemorySignal>(`/api/v2/workspaces/${workspaceId}/memory/signals`, input);
  }

  promoteSignals(workspaceId: string, signalIds: string[]) {
    return this.client.post<MemoryPromotionResult>(
      `/api/v2/workspaces/${workspaceId}/memory/promotions`,
      { signal_ids: signalIds }
    );
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
