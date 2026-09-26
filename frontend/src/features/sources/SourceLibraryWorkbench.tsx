import { useMemo, useRef, useState, type ChangeEvent, type DragEvent } from "react";
import {
  Activity,
  BookOpenCheck,
  CircleAlert,
  FileSearch,
  FileText,
  Files,
  Link2,
  MessagesSquare,
  Search,
  Trash2,
  Upload
} from "lucide-react";
import { buildWaitContextNarrative, summarizeRunStatus, type WaitContextDetailLine } from "../../runStatus";
import { formatRelativeTime } from "../../shared/util/datetime";
import type { ExecutionEvent, ExecutionTask } from "../executions/model";
import type { SignalChip } from "../research/model";
import type { Workspace } from "../workspace/model";
import {
  formatSourceIndexStatus,
  formatSourceProcessingStatus,
  isSourceReadableOnly,
  isSourceSearchable,
  type SourceAsset
} from "./model";
import { SOURCE_FILE_ACCEPT, SUPPORTED_SOURCE_FORMATS, validateSourceFile } from "./sourceFiles";

export { validateSourceFile };

type SourceFilter = "all" | "searchable" | "readable" | "processing";

export type SourceLibraryWorkbenchProps = {
  sources: SourceAsset[];
  sourceText: string;
  setSourceText: (value: string) => void;
  uploadSource: () => void;
  uploadSourceFile: (file: File) => Promise<void>;
  workspace: Workspace | null;
  conversationCount: number;
  latestTask: ExecutionTask | null;
  latestWorkspaceTaskWaitSignals: SignalChip[];
  latestWorkspaceTaskWaitDetails: WaitContextDetailLine[];
  latestWorkspaceProgressEvent: ExecutionEvent | null;
  taskEvents: ExecutionEvent[];
  deleteSource: (source: SourceAsset) => void;
  buildSourceOriginBadge: (source: SourceAsset) => string;
  buildGenericTaskRuntimeSnapshot: (
    event: ExecutionEvent | null,
    task: ExecutionTask | null
  ) => string;
  buildTaskEventNarrative: (event: ExecutionEvent, task: ExecutionTask | null) => string;
  sourcesBusy: boolean;
};


export function SourceLibraryWorkbench(props: SourceLibraryWorkbenchProps) {
  const {
    sources,
    sourceText,
    setSourceText,
    uploadSource,
    uploadSourceFile,
    workspace,
    conversationCount,
    latestTask,
    latestWorkspaceTaskWaitSignals,
    latestWorkspaceTaskWaitDetails,
    latestWorkspaceProgressEvent,
    taskEvents,
    deleteSource,
    buildSourceOriginBadge,
    buildGenericTaskRuntimeSnapshot,
    buildTaskEventNarrative,
    sourcesBusy
  } = props;
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<SourceFilter>("all");
  const [pendingDeleteId, setPendingDeleteId] = useState("");
  const [fileValidationError, setFileValidationError] = useState("");

  const searchableSourceCount = sources.filter(isSourceSearchable).length;
  const readableOnlySourceCount = sources.filter(isSourceReadableOnly).length;
  const processingSourceCount = sources.length - searchableSourceCount - readableOnlySourceCount;
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const visibleSources = useMemo(() => sources.filter((source) => {
    if (normalizedQuery && !source.title.toLocaleLowerCase().includes(normalizedQuery)) return false;
    if (filter === "searchable") return isSourceSearchable(source);
    if (filter === "readable") return isSourceReadableOnly(source);
    if (filter === "processing") return !isSourceSearchable(source) && !isSourceReadableOnly(source);
    return true;
  }), [filter, normalizedQuery, sources]);

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

  return (
    <section className="source-library-page workbench-page" aria-labelledby="source-library-title">
      <header className="source-library-hero">
        <div className="source-library-hero-copy">
          <h2 id="source-library-title">资料库</h2>
          <p>资料绑定当前工作台，由所有会话、Research 和 Wiki 共享。解析完成不等于已经建立检索索引。</p>
        </div>
        <div className="source-library-workspace" aria-label="资料库归属">
          <span><Link2 size={14} aria-hidden="true" />当前工作台</span>
          <strong title={workspace?.name || "未选择工作台"}>{workspace?.name || "未选择工作台"}</strong>
          <small><MessagesSquare size={13} aria-hidden="true" />{conversationCount} 个会话共享</small>
        </div>
      </header>

      <dl className="source-library-metrics" aria-label="资料状态总览">
        <div>
          <dt>全部资料</dt>
          <dd>{sources.length}</dd>
          <small>当前工作台</small>
        </div>
        <div className="is-searchable">
          <dt>可参与 Chat / RAG</dt>
          <dd>{searchableSourceCount}</dd>
          <small>已解析并建立检索索引</small>
        </div>
        <div className="is-readable">
          <dt>可用于 Research</dt>
          <dd>{searchableSourceCount + readableOnlySourceCount}</dd>
          <small>{readableOnlySourceCount} 份尚无检索索引</small>
        </div>
        <div className={processingSourceCount > 0 ? "is-processing" : ""}>
          <dt>处理中或异常</dt>
          <dd>{processingSourceCount}</dd>
          <small>{processingSourceCount > 0 ? "请查看资料状态" : "没有待处理资料"}</small>
        </div>
      </dl>

      <div className="source-library-layout">
        <div className="source-library-catalog">
          <div className="source-library-catalog-header">
            <div>
              <h3>全部来源</h3>
            </div>
            {sources.length > 0 ? (
              <label className="source-library-search">
                <Search size={15} aria-hidden="true" />
                <span className="sr-only">搜索资料</span>
                <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索标题" />
              </label>
            ) : null}
          </div>
          {sources.length > 0 ? (
            <div className="source-library-filter-row" role="group" aria-label="筛选资料状态">
              {([
                ["all", `全部 ${sources.length}`],
                ["searchable", `可检索 ${searchableSourceCount}`],
                ["readable", `仅可阅读 ${readableOnlySourceCount}`],
                ["processing", `处理中 ${processingSourceCount}`]
              ] as Array<[SourceFilter, string]>).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  className={filter === value ? "active" : ""}
                  aria-pressed={filter === value}
                  onClick={() => setFilter(value)}
                >
                  {label}
                </button>
              ))}
            </div>
          ) : null}

          {visibleSources.length > 0 ? (
            <div className="source-library-list" aria-label="工作台资料列表">
              {visibleSources.map((source) => {
                const searchable = isSourceSearchable(source);
                const readableOnly = isSourceReadableOnly(source);
                const deletePending = pendingDeleteId === source.source_id;
                return (
                  <article className="source-library-row" key={source.source_id}>
                    <span className={`source-library-file-icon${searchable ? " is-searchable" : readableOnly ? " is-readable" : " is-processing"}`} aria-hidden="true">
                      <FileText size={18} />
                    </span>
                    <div className="source-library-row-copy">
                      <strong title={source.title}>{source.title}</strong>
                      <div className="source-library-statuses">
                        <span className={sourceStatusTone(source.status)}>{formatSourceProcessingStatus(source.status)}</span>
                        <span className={sourceIndexTone(source.index_status)}>{formatSourceIndexStatus(source.index_status)}</span>
                        {source.generated_by === "research_agent" ? <span>{buildSourceOriginBadge(source)}</span> : null}
                      </div>
                    </div>
                    <div className="source-library-row-meta">
                      <small>{source.source_type || "资料"}</small>
                      <time dateTime={source.updated_at}>{source.updated_at ? formatRelativeTime(source.updated_at) : "更新时间未知"}</time>
                    </div>
                    <div className="source-library-delete">
                      {deletePending ? (
                        <>
                          <button type="button" className="source-delete-confirm" onClick={() => {
                            setPendingDeleteId("");
                            deleteSource(source);
                          }} disabled={sourcesBusy}>确认删除</button>
                          <button type="button" className="source-delete-cancel" onClick={() => setPendingDeleteId("")}>取消</button>
                        </>
                      ) : (
                        <button
                          type="button"
                          className="source-delete-button"
                          aria-label={`删除资料 ${source.title}`}
                          title="删除资料"
                          onClick={() => setPendingDeleteId(source.source_id)}
                          disabled={sourcesBusy}
                        >
                          <Trash2 size={15} aria-hidden="true" />
                        </button>
                      )}
                    </div>
                  </article>
                );
              })}
            </div>
          ) : sources.length > 0 ? (
            <div className="source-library-filter-empty">
              <FileSearch size={22} aria-hidden="true" />
              <strong>没有符合条件的资料</strong>
              <p>更换状态筛选或清除搜索内容。</p>
              <button type="button" className="secondary-button" onClick={() => { setQuery(""); setFilter("all"); }}>清除筛选</button>
            </div>
          ) : (
            <div className="source-library-empty">
              <Files size={24} aria-hidden="true" />
              <strong>资料库还是空的</strong>
              <p>从添加资料区上传第一份 PDF、Markdown 或文本资料，之后即可在同一工作台内共享。</p>
              <button type="button" className="source-library-empty-action" disabled={sourcesBusy || !workspace} onClick={() => fileInputRef.current?.click()}>
                <Upload size={16} aria-hidden="true" />选择第一份资料
              </button>
            </div>
          )}
        </div>

        <aside className="source-library-tools" aria-label="添加资料与处理记录">
          <section className="source-library-upload-tool">
            <div className="source-library-tool-heading">
              <span aria-hidden="true"><Upload size={17} /></span>
              <div><strong>添加资料</strong><small>写入当前工作台</small></div>
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
                <strong>{sourcesBusy ? "正在处理资料" : "拖放或选择文件"}</strong>
                <small>支持一次选择多个文件</small>
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

          <section className="source-library-availability">
            <div className="source-library-tool-heading">
              <span aria-hidden="true"><BookOpenCheck size={17} /></span>
              <div><strong>使用范围</strong><small>按真实后端状态判断</small></div>
            </div>
            <p><i className="is-searchable" />已建立索引的资料可参与 Chat / RAG。</p>
            <p><i className="is-readable" />只完成解析的资料可作为 Research 输入，但不能参与检索问答。</p>
          </section>

          {latestTask ? (
            <details className="source-task-disclosure">
              <summary>
                <span className="source-task-icon" aria-hidden="true"><Activity size={15} /></span>
                <span className="source-task-summary-copy">
                  <strong>最近处理记录</strong>
                  <small>{summarizeRunStatus(latestTask.task_status)} · {latestTask.progress_phase}</small>
                </span>
              </summary>
              <div className="source-task-detail">
                <span>{latestTask.task_type}</span>
                <span>{latestTask.progress_message}</span>
                {buildWaitContextNarrative(latestTask.wait_context) ? <small>{buildWaitContextNarrative(latestTask.wait_context)}</small> : null}
                {latestWorkspaceTaskWaitSignals.length > 0 ? (
                  <div className="signal-chip-row artifact-wait-signal-row">
                    {latestWorkspaceTaskWaitSignals.map((chip, index) => (
                      <span key={`workspace-task-wait-signal-${index}`} className={`signal-chip tone-${chip.tone}`}>{chip.label}: {chip.value}</span>
                    ))}
                  </div>
                ) : null}
                {latestWorkspaceTaskWaitDetails.map((line, index) => (
                  <small key={`workspace-task-wait-detail-${index}`} className="artifact-runtime-trace-line"><strong>{line.label}</strong> · {line.value}</small>
                ))}
                {buildGenericTaskRuntimeSnapshot(latestWorkspaceProgressEvent, latestTask) ? (
                  <small>{buildGenericTaskRuntimeSnapshot(latestWorkspaceProgressEvent, latestTask)}</small>
                ) : null}
                {taskEvents.slice(-4).map((event, index) => (
                  <small key={`${event.event}-${index}`}>{buildTaskEventNarrative(event, latestTask)}</small>
                ))}
              </div>
            </details>
          ) : null}
        </aside>
      </div>
    </section>
  );
}

function sourceStatusTone(status: string) {
  return /FAILED|ERROR/.test(status.toUpperCase()) ? "is-error" : status.toUpperCase() === "READY" ? "is-ready" : "is-pending";
}

function sourceIndexTone(status: string) {
  return status.toUpperCase() === "INDEXED" ? "is-indexed" : /FAILED|ERROR/.test(status.toUpperCase()) ? "is-error" : "is-unindexed";
}
