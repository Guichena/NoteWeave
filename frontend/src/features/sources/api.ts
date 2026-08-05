import { apiClient, type ApiClient } from "../../shared/api";
import {
  type CompletedSourceUpload,
  type DeletedSource,
  type NoteSourceDraft,
  type NoteSourceDraftInput,
  type SaveAnswerAsSourceInput,
  type SavedAnswerSource,
  type SourceAsset,
  type RetrievalIndexStatus,
  type RetrievalBackfillResult
} from "./model";

export class SourcesApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  list(workspaceId: string) {
    return this.client.get<SourceAsset[]>(`/api/v2/workspaces/${workspaceId}/sources`);
  }

  buildNoteSourceDraft(messageId: string, input: NoteSourceDraftInput = {}) {
    return this.client.post<NoteSourceDraft>(`/api/v2/messages/${messageId}/source-draft`, input);
  }

  saveAnswerAsSource(messageId: string, input: SaveAnswerAsSourceInput) {
    return this.client.post<SavedAnswerSource>(`/api/v2/messages/${messageId}/save-as-source`, input);
  }

  async uploadText(workspaceId: string, content: string, fileName = "frontend-source.md") {
    const bytes = new TextEncoder().encode(content);
    const upload = await this.client.post<{ upload_id: string }>(
      `/api/v2/workspaces/${workspaceId}/uploads`,
      {
        file_name: fileName,
        file_size: bytes.byteLength,
        mime_type: "text/markdown",
        chunk_size: bytes.byteLength,
        total_chunks: 1
      }
    );
    await this.client.raw(`/api/v2/uploads/${upload.upload_id}/chunks/0`, {
      method: "PUT",
      headers: { "Content-Type": "application/octet-stream" },
      body: bytes
    });
    return this.client.post<CompletedSourceUpload>(
      `/api/v2/uploads/${upload.upload_id}/complete`,
      {}
    );
  }

  remove(workspaceId: string, sourceId: string) {
    return this.client.deleteJson<DeletedSource>(
      `/api/v2/workspaces/${workspaceId}/sources/${sourceId}`
    );
  }

  retrievalStatus(workspaceId: string) {
    return this.client.get<RetrievalIndexStatus>(
      `/api/v2/workspaces/${workspaceId}/retrieval-indexes`
    );
  }

  rebuildRetrievalIndexes(workspaceId: string) {
    return this.client.post<RetrievalBackfillResult>(
      `/api/v2/workspaces/${workspaceId}/retrieval-indexes/rebuild`, {}
    );
  }
}

export const sourcesApi = new SourcesApi();
