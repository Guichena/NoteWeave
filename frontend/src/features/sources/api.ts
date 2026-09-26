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

  async uploadText(workspaceId: string, content: string, fileName = pastedSourceFileName(content)) {
    const bytes = new TextEncoder().encode(content);
    return this.uploadBytes(workspaceId, bytes, fileName, "text/markdown");
  }

  async uploadFile(workspaceId: string, file: File) {
    const mimeType = resolveSourceMimeType(file.name, file.type);
    if (!mimeType) {
      throw new Error("仅支持 PDF、Markdown、TXT、JSON 和 CSV 资料");
    }
    if (file.size <= 0) {
      throw new Error("不能上传空文件");
    }
    if (file.size > MAX_SOURCE_FILE_SIZE) {
      throw new Error("单个资料文件不能超过 128MB");
    }
    return this.uploadBlob(workspaceId, file, file.name, mimeType);
  }

  private async uploadBytes(
    workspaceId: string,
    bytes: Uint8Array,
    fileName: string,
    mimeType: string
  ) {
    return this.uploadBlob(workspaceId, new Blob([bytes]), fileName, mimeType);
  }

  private async uploadBlob(
    workspaceId: string,
    file: Blob,
    fileName: string,
    mimeType: string
  ) {
    const chunkSize = Math.min(MAX_SOURCE_CHUNK_SIZE, file.size);
    const totalChunks = Math.ceil(file.size / chunkSize);
    const upload = await this.client.post<{ upload_id: string }>(
      `/api/v2/workspaces/${workspaceId}/uploads`,
      {
        file_name: fileName,
        file_size: file.size,
        mime_type: mimeType,
        chunk_size: chunkSize,
        total_chunks: totalChunks
      }
    );
    for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex += 1) {
      const start = chunkIndex * chunkSize;
      const end = Math.min(file.size, start + chunkSize);
      const chunk = new Uint8Array(await file.slice(start, end).arrayBuffer());
      await this.client.raw(`/api/v2/uploads/${upload.upload_id}/chunks/${chunkIndex}`, {
        method: "PUT",
        headers: { "Content-Type": "application/octet-stream" },
        body: chunk
      });
    }
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

function pastedSourceFileName(content: string) {
  const firstMeaningfulLine = content.split(/\r?\n/).map((line) => line.trim()).find(Boolean) ?? "粘贴资料";
  const title = firstMeaningfulLine
    .replace(/^#{1,6}\s+/, "")
    .replace(/[\\/:*?"<>|]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 60)
    .trim();
  return `${title || "粘贴资料"}.md`;
}

const MAX_SOURCE_CHUNK_SIZE = 8 * 1024 * 1024;
const MAX_SOURCE_FILE_SIZE = 128 * 1024 * 1024;

const SOURCE_MIME_BY_EXTENSION: Record<string, string> = {
  pdf: "application/pdf",
  md: "text/markdown",
  markdown: "text/markdown",
  txt: "text/plain",
  json: "application/json",
  csv: "text/csv"
};

function resolveSourceMimeType(fileName: string, browserMimeType: string) {
  const extension = fileName.split(".").pop()?.toLowerCase() ?? "";
  const expected = SOURCE_MIME_BY_EXTENSION[extension];
  void browserMimeType;
  return expected ?? "";
}

export const sourcesApi = new SourcesApi();
