import { useRef, useState, type ChangeEvent, type DragEvent } from "react";
import { AudioLines,
  CircleAlert,
  FileSpreadsheet,
  FileText,
  Globe,
  LoaderCircle,
  NotebookPen,
  Plus,
  Settings2,
  Upload,
  X,
  type LucideIcon
} from "lucide-react";
import {
  formatSourceIndexStatus,
  formatSourceProcessingStatus,
  isSourceReadableOnly,
  isSourceSearchable,
  type SourceAsset
} from "../sources/model";
import { SOURCE_FILE_ACCEPT, sourceKind, validateSourceFile } from "../sources/sourceFiles";
import type { Workspace } from "../workspace/model";

export type ChatSourcesPaneProps = {
  sources: SourceAsset[];
  workspace: Workspace | null;
  conversationCount: number;
  onOpenSourceLibrary: () => void;
  /** QA 范围：空数组 = 使用全部可检索来源。 */
  selectedSourceIds?: string[];
  onChangeSelectedSourceIds?: (next: string[]) => void;
  scopeApplies?: boolean;
  uploadSourceFile?: (file: File) => Promise<void>;
  uploadBusy?: boolean;
  disabled?: boolean;
  onClose?: () => void;
};

const SOURCE_KIND_ICONS: Record<ReturnType<typeof sourceKind>, LucideIcon> = {
  pdf: FileText,
  doc: FileText,
  data: FileSpreadsheet,
  web: Globe,
  note: NotebookPen,
  media: AudioLines
};

export function ChatSourcesPane({
  sources,
  workspace,
  onOpenSourceLibrary,
  selectedSourceIds = [],
  onChangeSelectedSourceIds,
  scopeApplies = true,
  uploadSourceFile,
  uploadBusy = false,
  disabled = false,
  onClose
}: ChatSourcesPaneProps) {
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [uploadError, setUploadError] = useState("");
  const searchableSources = sources.filter(isSourceSearchable);
  const searchableIds = searchableSources.map((source) => source.source_id);
  const effectiveSelected = new Set(selectedSourceIds.length > 0 ? selectedSourceIds : searchableIds);
  const allSelected = searchableIds.length > 0 && searchableIds.every((id) => effectiveSelected.has(id));
  const someSelected = !allSelected && searchableIds.some((id) => effectiveSelected.has(id));
  const canUpload = Boolean(uploadSourceFile && workspace) && !uploadBusy;
  const canSelect = Boolean(onChangeSelectedSourceIds) && !disabled;

  function toggleSource(sourceId: string) {
    if (!onChangeSelectedSourceIds) return;
    const current = selectedSourceIds.length > 0 ? selectedSourceIds : searchableIds;
    const next = current.includes(sourceId)
      ? current.filter((id) => id !== sourceId)
      : [...current, sourceId];
    // 至少保留一份来源；全部选中时回到“使用全部”（空数组）。
    if (next.length === 0) return;
    onChangeSelectedSourceIds(next.length === searchableIds.length ? [] : next);
  }

  function selectAll() {
    if (!onChangeSelectedSourceIds || allSelected) return;
    onChangeSelectedSourceIds([]);
  }

  async function uploadFiles(files: FileList | File[]) {
    if (!uploadSourceFile) return;
    const selectedFiles = Array.from(files);
    const validationError = selectedFiles.map(validateSourceFile).find(Boolean) ?? "";
    if (validationError) {
      setUploadError(validationError);
      return;
    }
    setUploadError("");
    for (const file of selectedFiles) {
      await uploadSourceFile(file);
    }
  }

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    if (event.target.files?.length) void uploadFiles(event.target.files);
    event.target.value = "";
  }

  function handleDrop(event: DragEvent<HTMLElement>) {
    event.preventDefault();
    setDragActive(false);
    if (canUpload && event.dataTransfer.files.length > 0) {
      void uploadFiles(event.dataTransfer.files);
    }
  }

  return (
    <section
      className={`sources-pane source-drawer panel-view${dragActive ? " is-drag-active" : ""}`}
      aria-label="来源"
      onDragEnter={(event) => {
        if (!canUpload || !event.dataTransfer.types.includes("Files")) return;
        event.preventDefault();
        setDragActive(true);
      }}
      onDragOver={(event) => {
        if (canUpload) event.preventDefault();
      }}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragActive(false);
      }}
      onDrop={handleDrop}
    >
      <header className="pane-header">
        <h2 className="pane-title">来源</h2>
        {sources.length > 0 ? <span className="pane-count">{sources.length}</span> : null}
        <div className="pane-header-actions">
          <button
            type="button"
            className="icon-button"
            aria-label="打开资料库"
            title="在资料库中管理全部来源"
            onClick={onOpenSourceLibrary}
            disabled={!workspace}
          >
            <Settings2 size={16} aria-hidden="true" />
          </button>
          {onClose ? (
            <button type="button" className="icon-button pane-close" aria-label="收起面板" title="收起面板" onClick={onClose}>
              <X size={17} aria-hidden="true" />
            </button>
          ) : null}
        </div>
      </header>

      <div className="pane-body sources-pane-body">
        <input
          ref={fileInputRef}
          className="source-file-input"
          type="file"
          accept={SOURCE_FILE_ACCEPT}
          multiple
          hidden
          onChange={handleFileChange}
        />
        <button
          type="button"
          className="source-add-button"
          disabled={!canUpload}
          onClick={() => (uploadSourceFile ? fileInputRef.current?.click() : onOpenSourceLibrary())}
        >
          {uploadBusy ? <LoaderCircle className="is-spinning" size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}
          {uploadBusy ? "正在添加…" : "添加来源"}
        </button>
        {uploadError ? (
          <p className="source-pane-alert" role="alert">
            <CircleAlert size={14} aria-hidden="true" />
            <span>{uploadError}</span>
          </p>
        ) : null}

        {sources.length > 0 ? (
          <>
            {searchableIds.length > 0 ? (
              <label className={`source-select-all${canSelect && scopeApplies ? "" : " is-static"}`}>
                <span>选择全部来源</span>
                <input
                  type="checkbox"
                  checked={allSelected}
                  ref={(node) => {
                    if (node) node.indeterminate = someSelected;
                  }}
                  disabled={!canSelect || !scopeApplies}
                  onChange={selectAll}
                  aria-label="选择全部来源"
                />
              </label>
            ) : null}
            <ul className="source-list" aria-label="工作台来源">
              {sources.map((source) => {
                const searchable = isSourceSearchable(source);
                const readableOnly = isSourceReadableOnly(source);
                const processing = !searchable && !readableOnly && !/FAILED|ERROR/i.test(source.status);
                const failed = /FAILED|ERROR/i.test(source.status) || /FAILED|ERROR/i.test(source.index_status);
                const checked = searchable && effectiveSelected.has(source.source_id);
                const kind = sourceKind(source);
                const Icon = SOURCE_KIND_ICONS[kind];
                const statusText = `${formatSourceProcessingStatus(source.status)} · ${formatSourceIndexStatus(source.index_status)}`;
                return (
                  <li
                    key={source.source_id}
                    className={[
                      "source-row",
                      checked && scopeApplies ? "is-checked" : "",
                      searchable ? "" : "is-unsearchable",
                      failed ? "is-failed" : ""
                    ].filter(Boolean).join(" ")}
                  >
                    <label className="source-row-label" title={source.title}>
                      <span className={`source-icon kind-${kind}`} aria-hidden="true">
                        {processing ? <LoaderCircle className="is-spinning" size={15} /> : <Icon size={15} />}
                      </span>
                      <span className="source-row-copy">
                        <strong>{source.title}</strong>
                        <small>{statusText}</small>
                      </span>
                      {searchable ? (
                        <input
                          type="checkbox"
                          className="source-row-check"
                          checked={checked}
                          disabled={!canSelect || !scopeApplies}
                          onChange={() => toggleSource(source.source_id)}
                          aria-label={`在问答中使用 ${source.title}`}
                        />
                      ) : (
                        <span className="source-row-state">{processing ? "处理中" : failed ? "失败" : "仅阅读"}</span>
                      )}
                    </label>
                  </li>
                );
              })}
            </ul>
          </>
        ) : (
          <div className="source-empty">
            <span className="source-empty-icon" aria-hidden="true"><Upload size={20} /></span>
            <strong>还没有资料</strong>
            <p>添加 PDF、Markdown 或文本后，就可以围绕它们提问、生成产物。</p>
          </div>
        )}
      </div>

      {sources.length > 0 ? (
        <footer className="pane-footnote">
          {!scopeApplies
            ? "精读与知识库模式会使用全部可用来源"
            : searchableIds.length === 0
              ? "资料建立检索索引后即可用于问答"
              : allSelected
                ? `问答将使用全部 ${searchableIds.length} 个可检索来源`
                : `问答将使用已选 ${effectiveSelected.size} / ${searchableIds.length} 个来源`}
        </footer>
      ) : null}

      {dragActive ? (
        <div className="source-drop-overlay" aria-hidden="true">
          <Upload size={22} />
          <strong>松开即可添加到来源</strong>
        </div>
      ) : null}
    </section>
  );
}
