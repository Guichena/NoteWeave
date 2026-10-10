import { useEffect, useRef, useState } from "react";
import {
  ArrowLeft,
  Check,
  CircleAlert,
  Download,
  LoaderCircle,
  MoreHorizontal,
  X
} from "lucide-react";
import { MarkdownSurface } from "../../shared/ui/MarkdownSurface";
import { artifactsApi, type ArtifactsApi } from "./api";
import type { ArtifactRailProps } from "./ArtifactRailProps";
import { ArtifactMindMapPreview, isMindMapArtifact } from "./ArtifactMindMapPreview";
import { ArtifactSlidePreview } from "./ArtifactSlidePreview";
import { buildArtifactRuntimeDetailSections } from "./artifactRuntimeTraceDetails";
import {
  buildArtifactProcessView,
  isArtifactOutputActive,
  type ArtifactOutputItem,
  type ArtifactProcessView
} from "./artifactOutputs";
import type { ArtifactVersionComparison, ArtifactVersionDetail, ArtifactVersionSummary } from "./model";

type ArtifactReaderProps = Pick<
  ArtifactRailProps,
  | "isBusy"
  | "formatRelativeTime"
  | "saveArtifactVersionAsSource"
  | "artifactSavedSourceByVersionId"
  | "artifactVersionSaveKey"
  | "writeArtifactVersionToKnowledge"
  | "artifactWritebackByVersionId"
  | "regenerateArtifactVersion"
  | "compareArtifactWithPreviousVersion"
  | "rollbackArtifactVersion"
  | "downloadArtifactVersionPdf"
  | "downloadArtifactVersionFile"
> & {
  workspaceId: string;
  item: ArtifactOutputItem;
  versionNo: number;
  version: ArtifactVersionDetail | null;
  loading: boolean;
  onBack: () => void;
  onClose?: () => void;
  onSelectVersion: (versionNo: number) => void;
  /** 回滚会追加一个新版本，完成后切回最新版本。 */
  onRolledBack: () => void;
  api?: Pick<ArtifactsApi, "listVersions" | "downloadFile">;
};

/** 产物阅读视图：正文、版本切换、导出与沉淀，以及可展开的生成过程。 */
export function ArtifactReader(props: ArtifactReaderProps) {
  const {
    workspaceId,
    item,
    versionNo,
    version,
    loading,
    isBusy,
    formatRelativeTime,
    onBack,
    onClose,
    onSelectVersion,
    api = artifactsApi
  } = props;
  const [versions, setVersions] = useState<ArtifactVersionSummary[]>([]);
  const [comparison, setComparison] = useState<ArtifactVersionComparison | null>(null);
  const menuRef = useRef<HTMLDetailsElement>(null);

  useEffect(() => {
    let cancelled = false;
    setVersions([]);
    void api.listVersions(workspaceId, item.artifactJobId)
      .then((list) => {
        if (!cancelled) setVersions([...list].sort((left, right) => right.version_no - left.version_no));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [api, workspaceId, item.artifactJobId, item.versionNo]);

  useEffect(() => {
    setComparison(null);
  }, [item.artifactJobId, versionNo]);

  const versionNumbers = versions.length > 0
    ? versions.map((entry) => entry.version_no)
    : Array.from({ length: item.versionNo }, (_, index) => item.versionNo - index);
  const isLatest = versionNo === item.versionNo;
  const target = { artifact_job_id: item.artifactJobId, version_no: versionNo };
  const saveKey = props.artifactVersionSaveKey(target);
  const title = version?.title || item.title;
  const files = version?.files?.filter((file) => file.status === "READY") ?? [];
  const pdfReady = version?.runtime_trace?.export_trace?.status === "COMPILED"
    && !files.some((file) => file.file_format === "PDF");
  const regenerating = isArtifactOutputActive(item);

  function runMenuAction(action: () => void | Promise<unknown>) {
    menuRef.current?.removeAttribute("open");
    void action();
  }

  async function compare() {
    const result = await props.compareArtifactWithPreviousVersion(target);
    setComparison(result ?? null);
  }

  async function rollback() {
    await props.rollbackArtifactVersion(target);
    props.onRolledBack();
  }

  return (
    <div className="artifact-reader">
      <header className="pane-header artifact-reader-header">
        <button type="button" className="icon-button" onClick={onBack} aria-label="返回产物列表" title="返回">
          <ArrowLeft size={17} aria-hidden="true" />
        </button>
        <div className="artifact-reader-heading">
          <h2 className="pane-title" title={title}>{title}</h2>
          <div className="artifact-reader-meta">
            <span>{item.typeLabel}</span>
            <select
              aria-label="选择版本"
              value={versionNo}
              disabled={versionNumbers.length <= 1}
              onChange={(event) => onSelectVersion(Number(event.target.value))}
            >
              {versionNumbers.map((value) => (
                <option key={value} value={value}>
                  v{value}{value === item.versionNo ? " · 最新" : ""}
                </option>
              ))}
            </select>
            {version ? <span>{formatRelativeTime(version.created_at)}</span> : null}
          </div>
        </div>
        <div className="pane-header-actions">
          <details
            className="artifact-reader-more"
            ref={menuRef}
            onKeyDown={(event) => {
              // 菜单展开时 Esc 只收起菜单，不关闭整个面板
              if (event.key !== "Escape" || !menuRef.current?.open) return;
              event.preventDefault();
              menuRef.current.removeAttribute("open");
            }}
          >
            <summary aria-label="更多操作" title="更多操作">
              <MoreHorizontal size={17} aria-hidden="true" />
            </summary>
            <div role="menu">
              <button type="button" role="menuitem" className="artifact-menu-item" disabled={isBusy || regenerating}
                onClick={() => runMenuAction(() => props.regenerateArtifactVersion(target))}>
                重新生成
              </button>
              <button type="button" role="menuitem" className="artifact-menu-item" disabled={isBusy || versionNo <= 1}
                onClick={() => runMenuAction(compare)}>
                与上一版比较
              </button>
              {!isLatest ? (
                <button type="button" role="menuitem" className="artifact-menu-item" disabled={isBusy}
                  title="以这个版本的内容追加一个新版本，原有版本都会保留"
                  onClick={() => runMenuAction(rollback)}>
                  恢复为此版本
                </button>
              ) : null}
            </div>
          </details>
          {onClose ? (
            <button type="button" className="icon-button pane-close" onClick={onClose} aria-label="关闭产物工作台" title="关闭">
              <X size={17} aria-hidden="true" />
            </button>
          ) : null}
        </div>
      </header>

      <div className="pane-body artifact-reader-body">
        <div className="artifact-reader-actions">
          {pdfReady ? (
            <button type="button" className="secondary-button" disabled={isBusy}
              onClick={() => version && void props.downloadArtifactVersionPdf(version)}>
              <Download size={15} aria-hidden="true" />
              下载 PDF
            </button>
          ) : null}
          {files.filter((file) => file.file_format !== "PNG").map((file) => (
            <button key={file.file_id} type="button" className="secondary-button" disabled={isBusy}
              onClick={() => void props.downloadArtifactVersionFile(target, file)}>
              <Download size={15} aria-hidden="true" />
              下载 {formatFileLabel(file.file_format)}
            </button>
          ))}
          <button type="button" className="secondary-button" disabled={isBusy || !version}
            onClick={() => void props.saveArtifactVersionAsSource(target)}>
            {props.artifactSavedSourceByVersionId[saveKey] ? "已保存为资料" : "保存为资料"}
          </button>
          <button type="button" className="secondary-button" disabled={isBusy || !version}
            onClick={() => void props.writeArtifactVersionToKnowledge({ ...target, title }, "NOTE")}>
            {props.artifactWritebackByVersionId[saveKey]?.includes("NOTE") ? "已写入 Note" : "写入 Note"}
          </button>
          <button type="button" className="secondary-button" disabled={isBusy || !version}
            onClick={() => void props.writeArtifactVersionToKnowledge({ ...target, title }, "WIKI")}>
            {props.artifactWritebackByVersionId[saveKey]?.includes("WIKI") ? "已写入 Wiki" : "写入 Wiki"}
          </button>
        </div>

        {regenerating ? (
          <p className="artifact-reader-notice" role="status">
            <LoaderCircle className="is-spinning" size={14} aria-hidden="true" />
            正在生成新版本 · {item.statusLabel}，完成后会出现在版本列表中。
          </p>
        ) : null}

        {comparison ? (
          <p className="artifact-reader-notice" role="status">
            <span>
              与 v{comparison.from_version_no} 相比：新增 {comparison.added_lines} 行，删除 {comparison.removed_lines} 行
              {comparison.title_changed ? "，标题有变化" : ""}。
            </span>
            <button type="button" className="icon-button" aria-label="关闭比较结果" onClick={() => setComparison(null)}>
              <X size={14} aria-hidden="true" />
            </button>
          </p>
        ) : null}

        {loading || !version ? (
          <div className="artifact-pending-state" role="status">
            <LoaderCircle className="is-spinning" size={16} aria-hidden="true" />
            <span>正在加载 v{versionNo}</span>
          </div>
        ) : (
          <>
            <ArtifactProcessPanel version={version} />
            <article className="artifact-reader-content">
              {isMindMapArtifact(version.skill_key) ? (
                <ArtifactMindMapPreview title={title} markdown={version.content_markdown} variant="inline" />
              ) : null}
              {version.skill_key === "video_learning_deck" && files.length > 0 ? (
                <ArtifactSlidePreview workspaceId={workspaceId} artifactJobId={item.artifactJobId}
                  versionNo={versionNo} files={files} api={api} />
              ) : null}
              {isMindMapArtifact(version.skill_key) ? null : (
                <MarkdownSurface className="artifact-reader-markdown" content={stripLeadingTitle(version.content_markdown, title)} />
              )}
            </article>
          </>
        )}
      </div>
    </div>
  );
}

const VERIFICATION_LABELS: Record<Exclude<ArtifactProcessView["verification"], "">, { label: string; tone: string }> = {
  PASS: { label: "校验通过", tone: "ok" },
  WARN: { label: "校验有提醒", tone: "warn" },
  FAIL: { label: "校验未通过", tone: "danger" }
};

/** 生成过程：五个阶段、输出契约、来源覆盖和调用的能力，原始运行记录再折叠一层。 */
function ArtifactProcessPanel({ version }: { version: ArtifactVersionDetail }) {
  const process = buildArtifactProcessView(version.runtime_trace);
  const rawSections = buildArtifactRuntimeDetailSections(version.runtime_trace ?? null);
  if (!process) return null;
  const verification = process.verification ? VERIFICATION_LABELS[process.verification] : null;
  const mcpCount = process.capabilities.filter((capability) => capability.via).length;

  return (
    <details className="artifact-process">
      <summary>
        <span className="artifact-process-title">生成过程</span>
        <span className="artifact-process-badges">
          {verification ? <span className={`artifact-badge tone-${verification.tone}`}>{verification.label}</span> : null}
          {process.coverage ? (
            <span className="artifact-badge">来源覆盖 {process.coverage.covered}/{process.coverage.total}</span>
          ) : null}
          {mcpCount > 0 ? <span className="artifact-badge">MCP 工具 {mcpCount}</span> : null}
        </span>
      </summary>

      <div className="artifact-process-body">
        {process.steps.length > 0 ? (
          <ol className="artifact-process-steps" aria-label="生成阶段">
            {process.steps.map((step) => (
              <li key={step.key} className={`is-${step.status}`}>
                <span className="artifact-step-dot" aria-hidden="true">
                  {step.status === "done" ? <Check size={11} strokeWidth={3} /> : null}
                  {step.status === "failed" ? <CircleAlert size={11} strokeWidth={2.5} /> : null}
                </span>
                <span>{step.label}</span>
              </li>
            ))}
          </ol>
        ) : null}

        <dl className="artifact-process-facts">
          {process.checks ? (
            <div>
              <dt>输出契约</dt>
              <dd>
                通过 {process.checks.passed} 项
                {process.checks.repaired > 0 ? ` · 自动修复 ${process.checks.repaired} 项` : ""}
                {process.checks.failed > 0 ? ` · 未通过 ${process.checks.failed} 项` : ""}
              </dd>
            </div>
          ) : null}
          {process.coverage ? (
            <div>
              <dt>来源覆盖</dt>
              <dd>
                {process.coverage.total} 个章节中 {process.coverage.covered} 个有来源支撑
                {process.coverage.missing.length > 0 ? `，缺少：${process.coverage.missing.join("、")}` : ""}
              </dd>
            </div>
          ) : null}
          {process.sourceCount > 0 || process.model ? (
            <div>
              <dt>生成依据</dt>
              <dd>{[process.sourceCount > 0 ? `${process.sourceCount} 份资料` : "", process.model].filter(Boolean).join(" · ")}</dd>
            </div>
          ) : null}
          {process.capabilities.length > 0 ? (
            <div>
              <dt>调用能力</dt>
              <dd className="artifact-capability-list">
                {process.capabilities.map((capability) => (
                  <span key={capability.key} className={capability.via ? "is-mcp" : ""} title={capability.via || "内置能力"}>
                    {capability.label}
                    {capability.via ? <small>MCP</small> : null}
                  </span>
                ))}
              </dd>
            </div>
          ) : null}
        </dl>

        {rawSections.length > 0 ? (
          <details className="artifact-process-raw">
            <summary>完整运行记录</summary>
            <div className="artifact-runtime-detail-grid">
              {rawSections.map((section) => (
                <section key={section.title} className="artifact-runtime-detail-section">
                  <strong>{section.title}</strong>
                  {section.lines.map((line) => <small key={line}>{line}</small>)}
                </section>
              ))}
            </div>
          </details>
        ) : null}
      </div>
    </details>
  );
}

const FILE_LABELS: Record<string, string> = { MARKDOWN: "Markdown", PPTX: "PPTX", PDF: "PDF" };

function formatFileLabel(format: string) {
  return FILE_LABELS[format.toUpperCase()] ?? format;
}

/** 正文开头的一级标题与产物标题相同时去掉，避免与页头重复。 */
export function stripLeadingTitle(markdown: string, title: string) {
  const match = markdown.match(/^\s*#\s+(.+?)\s*(?:\r?\n|$)/);
  return match && match[1].trim() === title.trim() ? markdown.slice(match[0].length).replace(/^\s+/, "") : markdown;
}
