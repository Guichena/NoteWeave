import type { ExecutionEvent } from "../executions/model";
import type { SourceAsset } from "./model";

// 资料处理的四个阶段，每个阶段是一个独立的异步任务，后端通过 processing_stage 告知当前所处的阶段。
export const SOURCE_STAGES = [
  { key: "parse", label: "解析" },
  { key: "chunk", label: "切片" },
  { key: "embed", label: "向量化" },
  { key: "index", label: "索引" }
] as const;

export type SourceStageKey = (typeof SOURCE_STAGES)[number]["key"];
export type SourceStageStatus = "done" | "active" | "failed" | "pending" | "skipped";

export type SourcePipeline = {
  state: "searchable" | "readable" | "processing" | "failed";
  stages: Array<{ key: SourceStageKey; label: string; status: SourceStageStatus }>;
  /** 当前所处阶段或结果的简短说明。 */
  label: string;
  /** 失败发生在解析阶段（解析、切片）还是索引阶段（向量化、索引），决定重新处理从哪里开始。 */
  failure: "parse" | "index" | null;
  /** 检索索引失败后下次自动重试的时间；不会再自动重试时为空。 */
  retryAt: string | null;
};

type StageStatuses = [parse: SourceStageStatus, chunk: SourceStageStatus, embed: SourceStageStatus, index: SourceStageStatus];

export function buildSourcePipeline(
  source: Pick<SourceAsset, "status" | "parse_status" | "index_status" | "processing_stage" | "next_index_retry_at">
): SourcePipeline {
  const status = normalize(source.status);
  const parse = normalize(source.parse_status);
  const index = normalize(source.index_status);
  const stage = normalize(source.processing_stage);

  if (parse === "FAILED" || (status === "FAILED" && parse !== "PARSED")) {
    if (stage === "CHUNKING") return pipeline("failed", ["done", "failed", "pending", "pending"], "切片失败", "parse");
    return pipeline("failed", ["failed", "pending", "pending", "pending"],
      stage === "TRANSCRIBING" ? "转写失败" : "解析失败", "parse");
  }
  if (parse !== "PARSED") {
    if (stage === "CHUNKING") return pipeline("processing", ["done", "active", "pending", "pending"], "切片中", null);
    // 音视频的解析阶段是转写：Worker 通过 MCP 转写工具生成文字稿
    return pipeline("processing", ["active", "pending", "pending", "pending"],
      stage === "TRANSCRIBING" ? "转写中" : "解析中", null);
  }
  if (index === "INDEXED") {
    return pipeline("searchable", ["done", "done", "done", "done"], "可检索", null);
  }
  if (index === "DISABLED") {
    return pipeline("readable", ["done", "done", "skipped", "skipped"], "仅可阅读", null);
  }
  if (index === "FAILED" || status === "FAILED") {
    const retryAt = source.next_index_retry_at || null;
    const failed = stage === "INDEXING"
      ? pipeline("failed", ["done", "done", "done", "failed"], "写入索引失败", "index")
      : pipeline("failed", ["done", "done", "failed", "pending"], "向量化失败", "index");
    return { ...failed, label: retryAt ? `${failed.label}，稍后自动重试` : failed.label, retryAt };
  }
  return stage === "INDEXING"
    ? pipeline("processing", ["done", "done", "done", "active"], "写入索引中", null)
    : pipeline("processing", ["done", "done", "active", "pending"], "向量化中", null);
}

function pipeline(
  state: SourcePipeline["state"],
  statuses: StageStatuses,
  label: string,
  failure: SourcePipeline["failure"]
): SourcePipeline {
  return {
    state,
    stages: SOURCE_STAGES.map((stage, index) => ({ key: stage.key, label: stage.label, status: statuses[index] })),
    label,
    failure,
    retryAt: null
  };
}

const FORMAT_LABELS: Record<string, string> = {
  mp3: "音频",
  m4a: "音频",
  wav: "音频",
  ogg: "音频",
  flac: "音频",
  webm: "音频",
  mp4: "视频",
  pdf: "PDF",
  md: "Markdown",
  markdown: "Markdown",
  txt: "文本",
  json: "JSON",
  csv: "CSV"
};

const ORIGIN_LABELS: Record<string, string> = {
  research_agent: "深度研究报告",
  artifact_agent: "产物",
  note_answer: "精读回答"
};

/** 资料行的副标题：来源或格式、页数和切片数。 */
export function describeSourceMeta(
  source: Pick<SourceAsset, "title" | "source_type" | "generated_by" | "chunk_count" | "page_count">
) {
  const extension = source.title.includes(".") ? source.title.split(".").pop()?.toLowerCase() ?? "" : "";
  const origin = ORIGIN_LABELS[source.generated_by] ?? "";
  const format = FORMAT_LABELS[extension] ?? (source.source_type === "USER_UPLOAD" ? "文件" : "生成资料");
  return [
    origin || format,
    source.page_count ? `${source.page_count} 页` : "",
    source.chunk_count ? `${source.chunk_count} 个切片` : ""
  ].filter(Boolean).join(" · ");
}

const EVENT_LABELS: Record<string, string> = {
  TASK_CREATED: "已加入处理队列",
  TASK_RUNNING: "开始处理",
  TASK_PROGRESS: "处理进度更新",
  TASK_COMPLETED: "处理完成",
  TASK_FAILED: "处理失败",
  TASK_CANCELLED: "已取消",
  TASK_REDRIVEN: "重新投递"
};

export type SourceTaskEntry = {
  key: string;
  label: string;
  detail: string;
  time: string;
  tone: "normal" | "done" | "failed";
};

/** 把解析任务的事件记录整理成处理记录，心跳事件不展示。 */
export function buildSourceTaskEntries(events: ExecutionEvent[]): SourceTaskEntry[] {
  return events
    .filter((event) => !isHeartbeat(event))
    .map((event, index) => {
      const type = normalize(event.eventType);
      const errorCode = typeof event.payload.error_code === "string" ? event.payload.error_code : "";
      const retryable = event.payload.retryable === true;
      return {
        key: event.id || `${type}-${index}`,
        // 阶段进度直接展示后端写入的说明，例如“切片完成，共 36 个片段，等待向量化”
        label: type === "TASK_PROGRESS" && event.message ? event.message : EVENT_LABELS[type] ?? (event.message || type),
        detail: type === "TASK_FAILED"
          ? [errorCode, retryable ? "可自动重试" : ""].filter(Boolean).join(" · ")
          : "",
        time: event.createdAt,
        tone: type === "TASK_COMPLETED" ? "done" : type === "TASK_FAILED" ? "failed" : "normal"
      };
    });
}

/**
 * 处理记录的耗时摘要（不含心跳）。任务结束时取首尾事件的间隔；
 * 仍在进行时从第一条事件算到 now。
 */
export function summarizeTaskDuration(events: ExecutionEvent[], running = false, now = Date.now()): string {
  const times = events
    .filter((event) => !isHeartbeat(event))
    .map((event) => Date.parse(event.createdAt))
    .filter((value) => Number.isFinite(value));
  if (times.length === 0 || (!running && times.length < 2)) return "";
  const end = running ? now : Math.max(...times);
  const seconds = Math.max(0, Math.round((end - Math.min(...times)) / 1000));
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes} 分 ${seconds % 60} 秒`;
}

function isHeartbeat(event: ExecutionEvent) {
  return event.eventType === "TASK_HEARTBEAT" || event.event === "task.heartbeat";
}

function normalize(value?: string | null) {
  return (value ?? "").trim().toUpperCase();
}
