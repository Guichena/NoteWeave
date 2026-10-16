// 断点续传记录：按工作台和文件指纹保存未完成的上传会话。刷新页面后重新选择同一个文件，
// 可以从服务端已确认的分片之后继续上传。

export type UploadResumeRecord = {
  upload_id: string;
  chunk_size: number;
  total_chunks: number;
  file_size: number;
  saved_at: number;
};

const PREFIX = "noteweave.upload-resume";
// 超过这个时间的记录不再尝试续传，服务端的临时分片也会被清理
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

function key(workspaceId: string, sha256: string) {
  return `${PREFIX}:${workspaceId}:${sha256}`;
}

function storage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function readUploadResume(workspaceId: string, sha256: string, fileSize: number, now = Date.now()) {
  try {
    const raw = storage()?.getItem(key(workspaceId, sha256));
    if (!raw) return null;
    const record = JSON.parse(raw) as UploadResumeRecord;
    if (record.file_size !== fileSize || now - record.saved_at > MAX_AGE_MS) {
      clearUploadResume(workspaceId, sha256);
      return null;
    }
    return record;
  } catch {
    return null;
  }
}

export function saveUploadResume(workspaceId: string, sha256: string, record: Omit<UploadResumeRecord, "saved_at">) {
  try {
    storage()?.setItem(key(workspaceId, sha256), JSON.stringify({ ...record, saved_at: Date.now() }));
  } catch {
    // 浏览器禁用存储时只是失去续传能力，不影响本次上传
  }
}

export function clearUploadResume(workspaceId: string, sha256: string) {
  try {
    storage()?.removeItem(key(workspaceId, sha256));
  } catch {
    // 同上
  }
}
