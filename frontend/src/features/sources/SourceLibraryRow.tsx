import { useEffect, useState } from "react";
import { AudioLines, Check, ChevronDown, CircleAlert, FileText, LoaderCircle, RefreshCw, Trash2 } from "lucide-react";
import { formatRelativeTime } from "../../shared/util/datetime";
import type { ExecutionSnapshot } from "../executions/model";
import type { SourceAsset } from "./model";
import { sourceKind } from "./sourceFiles";
import {
  buildSourcePipeline,
  buildSourceTaskEntries,
  describeSourceMeta,
  summarizeTaskDuration,
  type SourcePipeline
} from "./sourcePipeline";

type SourceLibraryRowProps = {
  source: SourceAsset;
  expanded: boolean;
  onToggle: () => void;
  busy: boolean;
  onDelete: () => void;
  loadTask: (taskId: string) => Promise<ExecutionSnapshot>;
  recovery: RecoveryState;
  onReprocess: () => void;
};

export type RecoveryState = { running: boolean; message: string; tone: "normal" | "danger" };

/** 资料行：标题、格式与切片信息、处理阶段；展开后显示处理记录与恢复方式。 */
export function SourceLibraryRow(props: SourceLibraryRowProps) {
  const { source, expanded, onToggle, busy } = props;
  const pipeline = buildSourcePipeline(source);
  const [pendingDelete, setPendingDelete] = useState(false);
  const detailId = `source-detail-${source.source_id}`;

  return (
    <article className={`source-library-row is-${pipeline.state}${expanded ? " is-expanded" : ""}`}>
      <div className="source-library-row-head">
        <button
          type="button"
          className="source-library-row-main"
          aria-expanded={expanded}
          aria-controls={detailId}
          data-source-detail-trigger
          onClick={onToggle}
        >
          <span className="source-library-file-icon" aria-hidden="true">
            {sourceKind(source) === "media" ? <AudioLines size={18} /> : <FileText size={18} />}
          </span>
          <span className="source-library-row-copy">
            <strong title={source.title}>{source.title}</strong>
            <small>{describeSourceMeta(source)}</small>
          </span>
          <span className="source-library-row-status">
            {pipeline.state === "processing" ? <SourceStageTrack pipeline={pipeline} /> : null}
            <span className={`source-status-badge is-${pipeline.state}`}>
              {pipeline.state === "processing" ? <LoaderCircle className="is-spinning" size={12} aria-hidden="true" /> : null}
              {pipeline.state === "failed" ? <CircleAlert size={12} aria-hidden="true" /> : null}
              {pipeline.label}
            </span>
          </span>
          <time className="source-library-row-time" dateTime={source.updated_at}>
            {source.updated_at ? formatRelativeTime(source.updated_at) : ""}
          </time>
          <ChevronDown className="source-library-row-chevron" size={15} aria-hidden="true" />
        </button>
        <div className="source-library-delete">
          {pendingDelete ? (
            <>
              <button type="button" className="source-delete-confirm" disabled={busy} onClick={() => {
                setPendingDelete(false);
                props.onDelete();
              }}>确认删除</button>
              <button type="button" className="source-delete-cancel" onClick={() => setPendingDelete(false)}>取消</button>
            </>
          ) : (
            <button
              type="button"
              className="source-delete-button"
              aria-label={`删除资料 ${source.title}`}
              title="删除资料"
              onClick={() => setPendingDelete(true)}
              disabled={busy}
            >
              <Trash2 size={15} aria-hidden="true" />
            </button>
          )}
        </div>
      </div>
      {expanded ? (
        <SourceProcessingDetail
          id={detailId}
          source={source}
          pipeline={pipeline}
          loadTask={props.loadTask}
          recovery={props.recovery}
          onReprocess={props.onReprocess}
        />
      ) : null}
    </article>
  );
}

function SourceStageTrack({ pipeline }: { pipeline: SourcePipeline }) {
  return (
    <span className="source-stage-track" aria-hidden="true">
      {pipeline.stages.map((stage) => <span key={stage.key} className={`is-${stage.status}`} />)}
    </span>
  );
}

function SourceProcessingDetail({
  id,
  source,
  pipeline,
  loadTask,
  recovery,
  onReprocess
}: {
  id: string;
  source: SourceAsset;
  pipeline: SourcePipeline;
  loadTask: (taskId: string) => Promise<ExecutionSnapshot>;
  recovery: RecoveryState;
  onReprocess: () => void;
}) {
  const [snapshot, setSnapshot] = useState<ExecutionSnapshot | null>(null);
  const [taskError, setTaskError] = useState("");
  const taskId = source.task_id ?? "";

  // 资料状态变化时重新读取任务，处理中的资料会随列表刷新而更新记录。
  useEffect(() => {
    if (!taskId) return;
    let cancelled = false;
    loadTask(taskId)
      .then((next) => {
        if (!cancelled) {
          setSnapshot(next);
          setTaskError("");
        }
      })
      .catch(() => {
        if (!cancelled) setTaskError("处理记录暂时无法读取");
      });
    return () => {
      cancelled = true;
    };
  }, [loadTask, taskId, source.parse_status, source.index_status, source.status, source.processing_stage]);

  const entries = snapshot ? buildSourceTaskEntries(snapshot.events) : [];
  const running = pipeline.state === "processing";
  const duration = snapshot ? summarizeTaskDuration(snapshot.events, running) : "";
  const redriveCount = snapshot?.events.filter((event) => event.eventType === "TASK_REDRIVEN").length ?? 0;
  const failureMessage = snapshot?.task.error_message?.trim() ?? "";

  return (
    <div className="source-detail" id={id}>
      <ol className="source-stage-list" aria-label="处理阶段">
        {pipeline.stages.map((stage) => (
          <li key={stage.key} className={`is-${stage.status}`}>
            <span className="source-stage-dot" aria-hidden="true">
              {stage.status === "done" ? <Check size={11} strokeWidth={3} /> : null}
              {stage.status === "failed" ? <CircleAlert size={11} strokeWidth={2.5} /> : null}
            </span>
            <span className="source-stage-copy">
              <strong>{stage.label}</strong>
              <small>{describeStage(stage.key, stage.status, source)}</small>
            </span>
          </li>
        ))}
      </ol>

      {pipeline.failure ? (
        <div className="source-recovery" role="group" aria-label="重新处理">
          <strong>{failureMessage || (pipeline.failure === "index" ? "向量化或写入索引时出错" : "文件内容无法解析")}</strong>
          <p>{describeRecovery(pipeline, source)}</p>
          <button type="button" className="secondary-button" disabled={recovery.running} onClick={onReprocess}>
            {recovery.running ? <LoaderCircle className="is-spinning" size={14} aria-hidden="true" /> : <RefreshCw size={14} aria-hidden="true" />}
            {recovery.running ? "正在提交" : "重新处理"}
          </button>
          {recovery.message ? (
            <small className={recovery.tone === "danger" ? "is-danger" : ""} role="status">{recovery.message}</small>
          ) : null}
        </div>
      ) : null}

      <section className="source-task-log" aria-label="处理记录">
        <header>
          <strong>处理记录</strong>
          {duration ? <span>{running ? "已进行" : "耗时"} {duration}</span> : null}
          {redriveCount > 0 ? <span>重新投递 {redriveCount} 次</span> : null}
          {taskId ? <span className="source-task-id" title={taskId}>任务 {taskId.slice(0, 8)}</span> : null}
        </header>
        {!taskId ? (
          <p className="source-task-empty">这份资料由系统生成，没有单独的处理任务。</p>
        ) : taskError ? (
          <p className="source-task-empty">{taskError}</p>
        ) : !snapshot ? (
          <p className="source-task-empty"><LoaderCircle className="is-spinning" size={13} aria-hidden="true" />正在读取</p>
        ) : entries.length === 0 ? (
          <p className="source-task-empty">暂无记录</p>
        ) : (
          <ol>
            {entries.map((entry) => (
              <li key={entry.key} className={`is-${entry.tone}`}>
                <time dateTime={entry.time}>{formatClock(entry.time)}</time>
                <span>{entry.label}</span>
                {entry.detail ? <small>{entry.detail}</small> : null}
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

/** 失败后的恢复说明：索引失败会按退避自动重试，解析失败需要手动重新处理。 */
function describeRecovery(pipeline: SourcePipeline, source: SourceAsset, now = Date.now()) {
  if (pipeline.failure === "parse") {
    return "重新处理会从解析阶段开始。如果文件加密或是没有文字层的扫描件，重新处理仍会失败，需要换一份文件。";
  }
  if (pipeline.retryAt) {
    const minutes = Math.max(1, Math.ceil((Date.parse(pipeline.retryAt) - now) / 60_000));
    const attempts = source.index_attempt_count ?? 0;
    return `约 ${minutes} 分钟后自动重试（已自动重试 ${attempts} 次，最多 3 次）。也可以现在手动重新处理，从向量化阶段开始，已有的切片保留。`;
  }
  return "自动重试已停止。重新处理会从向量化阶段开始，已有的切片保留。";
}

function describeStage(key: string, status: string, source: SourceAsset) {
  if (status === "failed") return "失败";
  if (status === "skipped") return "未开启";
  if (status === "pending") return "等待";
  if (status === "active") return "进行中";
  if (key === "parse") {
    if (sourceKind(source) === "media") return "已转写";
    return source.page_count ? `${source.page_count} 页` : "完成";
  }
  if (key === "chunk") return source.chunk_count ? `${source.chunk_count} 个切片` : "完成";
  return "完成";
}

function formatClock(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
}
