import { apiClient, type ApiClient } from "../../shared/api";
import { hashFile, readBlobBytes } from "../../shared/util/sha256";
import {
  MAX_SOURCE_FILE_BYTES,
  cachedSourceFormats,
  describeSourceFormats,
  resolveSourceMimeType,
  type SourceFormat
} from "./sourceFiles";
import { clearUploadResume, readUploadResume, saveUploadResume } from "./uploadResume";
import {
  type CompletedSourceUpload,
  type DeletedSource,
  type NoteSourceDraft,
  type NoteSourceDraftInput,
  type SaveAnswerAsSourceInput,
  type SavedAnswerSource,
  type SourceAsset,
  type RetrievalIndexStatus,
  type RetrievalBackfillResult,
  type SourceReprocessResult
} from "./model";

export class SourcesApi {
  constructor(private readonly client: ApiClient = apiClient) {}

  previewLink(workspaceId: string, sourceUrl: string) {
    return this.client.post<import("./model").SourceVideoInfo>(`/api/v2/workspaces/${workspaceId}/sources/links/preview`, { source_url: sourceUrl });
  }

  addLink(workspaceId: string, sourceUrl: string, parts: number[]) {
    return this.client.post<{ source_id: string; snapshot_id: string; task_id: string }>(`/api/v2/workspaces/${workspaceId}/sources/links`, { source_url: sourceUrl, parts });
  }

  readMedia(workspaceId: string, sourceId: string) {
    return this.client.get<import("./model").SourceMediaDocument>(`/api/v2/workspaces/${workspaceId}/sources/${sourceId}/media`);
  }

  readFrame(workspaceId: string, sourceId: string, frameId: string) {
    return this.client.blob(`/api/v2/workspaces/${workspaceId}/sources/${sourceId}/frames/${encodeURIComponent(frameId)}`);
  }

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

  async uploadFile(
    workspaceId: string,
    file: File,
    onProgress?: (progress: SourceUploadProgress) => void,
    formats: SourceFormat[] = cachedSourceFormats(workspaceId)
  ) {
    const mimeType = resolveSourceMimeType(file.name, formats);
    if (!mimeType) {
      throw new Error(`格式不受支持，目前支持 ${describeSourceFormats(formats)}`);
    }
    if (file.size <= 0) {
      throw new Error("不能上传空文件");
    }
    if (file.size > MAX_SOURCE_FILE_BYTES) {
      throw new Error("单个资料文件不能超过 128MB");
    }
    return this.uploadBlob(workspaceId, file, file.name, mimeType, onProgress);
  }

  /** 后端支持的资料格式，用于文件选择器过滤；格式列表不区分工作台。 */
  listFormats() {
    return this.client.get<SourceFormat[]>("/api/v2/uploads/formats");
  }

  private async uploadBytes(
    workspaceId: string,
    bytes: Uint8Array,
    fileName: string,
    mimeType: string
  ) {
    return this.uploadBlob(workspaceId, new Blob([bytes]), fileName, mimeType);
  }

  /**
   * 先计算文件指纹：服务端已有同样的文件时直接秒传；本机有未完成的同一文件上传时，
   * 向服务端确认已收到的分片后只补传缺少的分片；否则新建上传会话。
   */
  private async uploadBlob(
    workspaceId: string,
    file: Blob,
    fileName: string,
    mimeType: string,
    onProgress?: (progress: SourceUploadProgress) => void
  ): Promise<SourceUploadResult> {
    const totalBytes = file.size;
    const sha256 = await hashFile(file, (hashedBytes) => onProgress?.({
      phase: "hashing", hashedBytes, uploadedChunks: 0, totalChunks: 0, uploadedBytes: 0, totalBytes, resumedChunks: 0
    }));
    const session = await this.openUploadSession(workspaceId, file, fileName, mimeType, sha256);
    if (session.kind === "done") {
      clearUploadResume(workspaceId, sha256);
      return session.result;
    }
    const { uploadId, chunkSize, totalChunks, received } = session;
    const chunkBytes = (index: number) => Math.min(totalBytes, (index + 1) * chunkSize) - index * chunkSize;
    let uploadedChunks = received.size;
    let uploadedBytes = Array.from(received).reduce((sum, index) => sum + chunkBytes(index), 0);
    const report = () => onProgress?.({
      phase: "uploading", hashedBytes: totalBytes, uploadedChunks, totalChunks, uploadedBytes, totalBytes,
      resumedChunks: received.size
    });
    report();
    for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex += 1) {
      if (received.has(chunkIndex)) continue;
      const start = chunkIndex * chunkSize;
      const chunk = await readBlobBytes(file.slice(start, start + chunkBytes(chunkIndex)));
      await this.client.raw(`/api/v2/uploads/${uploadId}/chunks/${chunkIndex}`, {
        method: "PUT",
        headers: { "Content-Type": "application/octet-stream" },
        body: chunk
      });
      uploadedChunks += 1;
      uploadedBytes += chunk.length;
      report();
    }
    const completed = await this.client.post<CompletedSourceUpload>(`/api/v2/uploads/${uploadId}/complete`, {});
    clearUploadResume(workspaceId, sha256);
    return { ...completed, instant: false, resumed_chunks: received.size };
  }

  private async openUploadSession(
    workspaceId: string,
    file: Blob,
    fileName: string,
    mimeType: string,
    sha256: string
  ): Promise<UploadSession> {
    const record = readUploadResume(workspaceId, sha256, file.size);
    if (record) {
      try {
        const status = await this.client.get<UploadStatus>(`/api/v2/uploads/${record.upload_id}`);
        if (status.status === "UPLOADING" && status.total_chunks === record.total_chunks) {
          return {
            kind: "upload",
            uploadId: record.upload_id,
            chunkSize: status.chunk_size,
            totalChunks: status.total_chunks,
            received: new Set(status.uploaded_chunk_indexes)
          };
        }
        if (status.status === "COMPLETED") {
          const completed = await this.client.post<CompletedSourceUpload>(`/api/v2/uploads/${record.upload_id}/complete`, {});
          return { kind: "done", result: { ...completed, instant: false, resumed_chunks: status.total_chunks } };
        }
      } catch {
        // 会话已过期或接口不可用时重新上传
      }
      clearUploadResume(workspaceId, sha256);
    }
    const chunkSize = Math.max(1, Math.min(MAX_SOURCE_CHUNK_SIZE, file.size));
    const totalChunks = Math.max(1, Math.ceil(file.size / chunkSize));
    const created = await this.client.post<CreateUploadResult>(`/api/v2/workspaces/${workspaceId}/uploads`, {
      file_name: fileName,
      file_size: file.size,
      mime_type: mimeType,
      chunk_size: chunkSize,
      total_chunks: totalChunks,
      sha256
    });
    if (created.instant && created.source_id) {
      return {
        kind: "done",
        result: {
          source_id: created.source_id,
          task_id: created.task_id ?? "",
          parse_status: created.parse_status ?? "",
          index_status: created.index_status ?? "",
          instant: true,
          resumed_chunks: 0
        }
      };
    }
    saveUploadResume(workspaceId, sha256, {
      upload_id: created.upload_id, chunk_size: chunkSize, total_chunks: totalChunks, file_size: file.size
    });
    return { kind: "upload", uploadId: created.upload_id, chunkSize, totalChunks, received: new Set() };
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

  reprocessSource(workspaceId: string, sourceId: string) {
    return this.client.post<SourceReprocessResult>(
      `/api/v2/workspaces/${workspaceId}/sources/${sourceId}/reprocess`, {}
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

/** 上传进度：计算指纹时按读取的字节回调，上传时每个分片完成后回调一次。 */
export type SourceUploadProgress = {
  phase: "hashing" | "uploading";
  hashedBytes: number;
  uploadedChunks: number;
  totalChunks: number;
  uploadedBytes: number;
  totalBytes: number;
  /** 续传时服务端已经收到、本次跳过的分片数。 */
  resumedChunks: number;
};

/** instant 为 true 表示服务端已有同样的文件，没有上传任何分片。 */
export type SourceUploadResult = CompletedSourceUpload & { instant: boolean; resumed_chunks: number };

type CreateUploadResult = {
  upload_id: string;
  status: string;
  chunk_size: number;
  total_chunks: number;
  instant?: boolean;
  source_id?: string;
  task_id?: string;
  parse_status?: string;
  index_status?: string;
};

type UploadStatus = {
  upload_id: string;
  status: string;
  total_chunks: number;
  chunk_size: number;
  uploaded_chunk_indexes: number[];
};

type UploadSession =
  | { kind: "done"; result: SourceUploadResult }
  | { kind: "upload"; uploadId: string; chunkSize: number; totalChunks: number; received: Set<number> };

export const sourcesApi = new SourcesApi();
