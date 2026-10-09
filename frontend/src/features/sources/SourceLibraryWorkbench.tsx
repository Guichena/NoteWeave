import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent, type DragEvent } from "react";
import { CircleAlert, FileSearch, Search, Upload, X } from "lucide-react";
import { ApiError } from "../../shared/api/error";
import { executionsApi } from "../executions/api";
import type { ExecutionSnapshot } from "../executions/model";
import type { Workspace } from "../workspace/model";
import type { SourceUploadItem } from "../conversations/useChatSourceActions";
import type { SourceAsset, SourceReprocessResult } from "./model";
import { SourceLibraryRow, type RecoveryState } from "./SourceLibraryRow";
import { buildSourcePipeline } from "./sourcePipeline";
import { SOURCE_FILE_ACCEPT, SUPPORTED_SOURCE_FORMATS, validateSourceFile } from "./sourceFiles";

export { validateSourceFile };

type SourceFilter = "all" | "searchable" | "readable" | "processing" | "failed";

const IDLE_RECOVERY: RecoveryState = { running: false, message: "", tone: "normal" };

/** 有资料仍在处理时，按这个间隔刷新列表以更新阶段。 */
const PROCESSING_REFRESH_MS = 4000;

export type SourceLibraryWorkbenchProps = {
  sources: SourceAsset[];
  sourceText: string;
  setSourceText: (value: string) => void;
  uploadSource: () => void;
  uploadSourceFile: (file: File) => Promise<void>;
  uploads: SourceUploadItem[];
  dismissUpload: (id: string) => void;
  workspace: Workspace | null;
  deleteSource: (source: SourceAsset) => void;
  refreshSources: () => Promise<void>;
  reprocessSource: (sourceId: string) => Promise<SourceReprocessResult>;
  loadTask?: (taskId: string) => Promise<ExecutionSnapshot>;
  sourcesBusy: boolean;
};

export function SourceLibraryWorkbench(props: SourceLibraryWorkbenchProps) {
  const {
    sources,
    sourceText,
    setSourceText,
    uploadSource,
    uploadSourceFile,
    uploads,
    workspace,
    refreshSources,
    sourcesBusy
  } = props;
  const loadTask = props.loadTask ?? defaultLoadTask;
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<SourceFilter>("all");
  const [expandedId, setExpandedId] = useState("");
  const [fileValidationError, setFileValidationError] = useState("");
  const [recovery, setRecovery] = useState<Record<string, RecoveryState>>({});

  const pipelines = useMemo(
    () => new Map(sources.map((source) => [source.source_id, buildSourcePipeline(source)])),
    [sources]
  );
  const counts = useMemo(() => {
    const result = { searchable: 0, readable: 0, processing: 0, failed: 0 };
    pipelines.forEach((pipeline) => { result[pipeline.state] += 1; });
    return result;
  }, [pipelines]);
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const visibleSources = sources.filter((source) => {
    if (normalizedQuery && !source.title.toLocaleLowerCase().includes(normalizedQuery)) return false;
    return filter === "all" || pipelines.get(source.source_id)?.state === filter;
  });

  // 资料解析与建立索引在后台异步完成，处理中时定期刷新列表。
  const refreshRef = useRef(refreshSources);
  refreshRef.current = refreshSources;
  useEffect(() => {
    if (counts.processing === 0) return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void refreshRef.current().catch(() => undefined);
    }, PROCESSING_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [counts.processing]);

  // 重新处理单个资料：解析失败从解析阶段开始，只有索引失败时从向量化阶段开始
  const reprocess = useCallback(async (sourceId: string) => {
    setRecovery((current) => ({ ...current, [sourceId]: { running: true, message: "", tone: "normal" } }));
    try {
      const result = await props.reprocessSource(sourceId);
      await refreshRef.current();
      setRecovery((current) => ({
        ...current,
        [sourceId]: {
          running: false,
          message: result.restart_from === "PARSE" ? "已从解析阶段重新开始处理。" : "已从向量化阶段重新开始，已有的切片保留。",
          tone: "normal"
        }
      }));
    } catch (error) {
      setRecovery((current) => ({
        ...current,
        [sourceId]: {
          running: false,
          message: error instanceof ApiError && error.status === 403
            ? "你没有编辑这份资料的权限。"
            : error instanceof Error ? error.message : "重新处理失败，请稍后再试。",
          tone: "danger"
        }
      }));
    }
  }, [props.reprocessSource]);

  async function uploadFiles(files: FileList | File[]) {
    const selectedFiles = Array.from(files);
    const validationError = selectedFiles.map(validateSourceFile).find(Boolean) ?? "";
    if (validationError) {
      setFileValidationError(validationError);
      return;
    }
    setFileValidationError("");
    for (const file of selectedFiles) {
      await uploadSourceFile(file);
    }
  }

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    if (event.target.files?.length) void uploadFiles(event.target.files);
    event.target.value = "";
  }

  function handleDrop(event: DragEvent<HTMLButtonElement>) {
    event.preventDefault();
    setDragActive(false);
    if (!sourcesBusy && workspace && event.dataTransfer.files.length > 0) {
      void uploadFiles(event.dataTransfer.files);
    }
  }

  const filters = ([
    ["all", `全部 ${sources.length}`],
    ["searchable", `可检索 ${counts.searchable}`],
    ["readable", `仅可阅读 ${counts.readable}`],
    ["processing", `处理中 ${counts.processing}`],
    ["failed", `失败 ${counts.failed}`]
  ] as Array<[SourceFilter, string]>).filter(([value]) => value !== "failed" || counts.failed > 0);
  const hasItems = sources.length > 0 || uploads.length > 0;

  return (
    <section className="source-library-page workbench-page" aria-labelledby="source-library-title">
      <header className="source-library-hero">
        <div className="source-library-hero-copy">
          <h2 id="source-library-title">资料库</h2>
          <p>所有对话、深度研究与知识库共用这里的资料。上传后会在后台完成解析、切片、向量化和索引。</p>
        </div>
      </header>

      <div className={`source-library-layout${hasItems ? "" : " is-empty"}`}>
        {hasItems ? (
          <div className="source-library-catalog">
            <div className="source-library-catalog-header">
              <div className="source-library-filter-row" role="group" aria-label="筛选资料状态">
                {filters.map(([value, label]) => (
                  <button
                    key={value}
                    type="button"
                    className={`${filter === value ? "active" : ""}${value === "failed" ? " is-failed" : ""}`}
                    aria-pressed={filter === value}
                    onClick={() => setFilter(value)}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <label className="source-library-search">
                <Search size={15} aria-hidden="true" />
                <span className="sr-only">搜索资料</span>
                <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索标题" />
              </label>
            </div>

            {uploads.length > 0 ? (
              <ul className="source-upload-list" aria-label="正在上传">
                {uploads.map((upload) => <SourceUploadRow key={upload.id} upload={upload} onDismiss={() => props.dismissUpload(upload.id)} />)}
              </ul>
            ) : null}

            {visibleSources.length > 0 ? (
              <div className="source-library-list" aria-label="工作台资料列表">
                {visibleSources.map((source) => (
                  <SourceLibraryRow
                    key={source.source_id}
                    source={source}
                    expanded={expandedId === source.source_id}
                    onToggle={() => setExpandedId((current) => current === source.source_id ? "" : source.source_id)}
                    busy={sourcesBusy}
                    onDelete={() => props.deleteSource(source)}
                    loadTask={loadTask}
                    recovery={recovery[source.source_id] ?? IDLE_RECOVERY}
                    onReprocess={() => void reprocess(source.source_id)}
                  />
                ))}
              </div>
            ) : sources.length > 0 ? (
              <div className="source-library-filter-empty">
                <FileSearch size={22} aria-hidden="true" />
                <strong>没有符合条件的资料</strong>
                <p>更换状态筛选或清除搜索内容。</p>
                <button type="button" className="secondary-button" onClick={() => { setQuery(""); setFilter("all"); }}>清除筛选</button>
              </div>
            ) : null}
          </div>
        ) : null}

        <aside className="source-library-tools" aria-label="添加资料">
          <section className="source-library-upload-tool">
            <div className="source-library-tool-heading">
              <span aria-hidden="true"><Upload size={17} /></span>
              <div><strong>添加资料</strong></div>
            </div>
            <input
              ref={fileInputRef}
              className="source-file-input"
              type="file"
              accept={SOURCE_FILE_ACCEPT}
              multiple
              onChange={handleFileChange}
            />
            <button
              type="button"
              className={`source-upload-dropzone${dragActive ? " is-drag-active" : ""}`}
              disabled={sourcesBusy || !workspace}
              onClick={() => fileInputRef.current?.click()}
              onDragEnter={(event) => { event.preventDefault(); if (!sourcesBusy && workspace) setDragActive(true); }}
              onDragOver={(event) => event.preventDefault()}
              onDragLeave={() => setDragActive(false)}
              onDrop={handleDrop}
            >
              <span className="source-upload-icon" aria-hidden="true"><Upload size={19} /></span>
              <span className="source-upload-copy">
                <strong>{sourcesBusy ? "正在上传" : "拖放或选择文件"}</strong>
                <small>大文件分片上传，处理在后台进行，可以离开这个页面</small>
              </span>
            </button>
            <div className="source-format-row" aria-label="支持的资料格式">
              {SUPPORTED_SOURCE_FORMATS.map((format) => <span key={format}>{format}</span>)}
              <small>单文件最大 128 MB</small>
            </div>
            {fileValidationError ? (
              <p className="source-library-warning" role="alert"><CircleAlert size={15} aria-hidden="true" />{fileValidationError}</p>
            ) : null}
            <details className="source-paste-disclosure">
              <summary>粘贴文本</summary>
              <label className="input-block">
                <span>文本内容</span>
                <textarea value={sourceText} onChange={(event) => setSourceText(event.target.value)} rows={5} placeholder="粘贴需要加入当前工作台的内容" />
              </label>
              <button className="primary-action" onClick={uploadSource} disabled={sourcesBusy || !workspace || !sourceText.trim()}>
                <Upload size={16} aria-hidden="true" />{sourcesBusy ? "处理中" : "保存为资料"}
              </button>
            </details>
            {!workspace ? <p className="source-library-warning"><CircleAlert size={15} aria-hidden="true" />请先创建或选择工作台。</p> : null}
          </section>
        </aside>
      </div>
    </section>
  );
}

function SourceUploadRow({ upload, onDismiss }: { upload: SourceUploadItem; onDismiss: () => void }) {
  const percent = upload.totalBytes > 0 ? Math.round((upload.uploadedBytes / upload.totalBytes) * 100) : 0;
  const failed = upload.state === "failed";
  return (
    <li className={`source-upload-row${failed ? " is-failed" : ""}`}>
      <span className="source-upload-row-copy">
        <strong title={upload.fileName}>{upload.fileName}</strong>
        <small>
          {failed
            ? `上传失败：${upload.error}`
            : `上传中 · 第 ${Math.min(upload.uploadedChunks + 1, upload.totalChunks)} / ${upload.totalChunks} 片 · ${formatBytes(upload.uploadedBytes)} / ${formatBytes(upload.totalBytes)}`}
        </small>
      </span>
      {failed ? (
        <button type="button" className="icon-button" aria-label={`移除 ${upload.fileName}`} onClick={onDismiss}>
          <X size={15} aria-hidden="true" />
        </button>
      ) : (
        <span className="source-upload-percent">{percent}%</span>
      )}
      <span className="source-upload-bar" role="progressbar" aria-label={`${upload.fileName} 上传进度`}
        aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}>
        <span style={{ width: `${failed ? 100 : percent}%` }} />
      </span>
    </li>
  );
}

function formatBytes(bytes: number) {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

function defaultLoadTask(taskId: string) {
  return executionsApi.load(taskId);
}
