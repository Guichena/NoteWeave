import type { ExecutionTask } from "../../features/executions/model";
import type { SourceAsset } from "../../features/sources/model";

// 资料库设计数据：覆盖可检索、仅可阅读、切片中、向量化中、解析失败和向量化失败（等待自动重试）几种状态，
// 每份上传资料都关联一个解析任务及其事件记录，字段与后端接口一致。

const now = Date.now();
const minutesAgo = (minutes: number) => new Date(now - minutes * 60_000).toISOString();
const secondsAgo = (seconds: number) => new Date(now - seconds * 1000).toISOString();

function source(
  id: string,
  title: string,
  status: string,
  parseStatus: string,
  indexStatus: string,
  updatedMinutesAgo: number,
  counts: { chunks?: number; pages?: number } = {},
  pipeline: { stage?: string; attempts?: number; retryInMinutes?: number } = {}
): SourceAsset {
  return {
    source_id: id,
    title,
    source_type: "USER_UPLOAD",
    status,
    parse_status: parseStatus,
    index_status: indexStatus,
    generated_by: "",
    generated_ref_id: "",
    updated_at: minutesAgo(updatedMinutesAgo),
    chunk_count: counts.chunks ?? null,
    page_count: counts.pages ?? null,
    task_id: `task-${id}`,
    processing_stage: pipeline.stage ?? (status === "READY" ? "READY" : null),
    index_attempt_count: pipeline.attempts ?? 0,
    next_index_retry_at: pipeline.retryInMinutes
      ? new Date(now + pipeline.retryInMinutes * 60_000).toISOString() : null
  };
}

export const sources: SourceAsset[] = [
  source("src-eval", "RAG 评测数据集说明.pdf", "PROCESSING", "PENDING", "PENDING", 0, { pages: 24 }, { stage: "CHUNKING" }),
  source("src-audio", "检索评测分享会录音转写.txt", "PROCESSING", "PARSED", "INDEXING", 1, { chunks: 64 }, { stage: "EMBEDDING" }),
  source("src-rag", "RAG 检索链路设计.md", "READY", "PARSED", "INDEXED", 180, { chunks: 36 }),
  source("src-bench", "向量数据库压测记录.pdf", "READY", "PARSED", "INDEXED", 150, { chunks: 48, pages: 12 }),
  source("src-ik", "Elasticsearch 中文分词实践.md", "READY", "PARSED", "INDEXED", 90, { chunks: 22 }),
  source("src-memory", "长期记忆设计草稿.md", "READY", "PARSED", "DISABLED", 40, { chunks: 9 }),
  source("src-arch", "旧版架构评审纪要.pdf", "FAILED", "PARSED", "FAILED", 300, { chunks: 31, pages: 8 },
    { stage: "EMBEDDING", attempts: 1, retryInMinutes: 2 }),
  source("src-scan", "扫描版合同.pdf", "FAILED", "FAILED", "PENDING", 20, { pages: 6 }, { stage: "EXTRACTING" }),
  source("src-meeting", "产物模块周会录音.m4a", "PROCESSING", "PENDING", "PENDING", 2, {}, { stage: "TRANSCRIBING" })
];

type TaskSeed = {
  status: string;
  phase: string;
  error?: string;
  events: Array<[type: string, secondsBefore: number, payload?: Record<string, unknown>, message?: string]>;
};

const taskSeeds: Record<string, TaskSeed> = {
  "src-eval": {
    status: "RUNNING", phase: "CHUNKING",
    events: [["TASK_CREATED", 20], ["TASK_RUNNING", 18],
      ["TASK_PROGRESS", 6, {}, "解析完成，提取 18420 个字符（24 页），等待切片"]]
  },
  "src-audio": {
    status: "RUNNING", phase: "EMBEDDING",
    events: [["TASK_CREATED", 75], ["TASK_RUNNING", 74],
      ["TASK_PROGRESS", 70, {}, "解析完成，提取 52310 个字符，等待切片"],
      ["TASK_PROGRESS", 66, {}, "切片完成，共 64 个片段，等待向量化"]]
  },
  "src-rag": {
    status: "COMPLETED", phase: "INDEXED",
    events: [["TASK_CREATED", 10_812], ["TASK_RUNNING", 10_811], ["TASK_COMPLETED", 10_803]]
  },
  "src-bench": {
    status: "COMPLETED", phase: "INDEXED",
    events: [["TASK_CREATED", 9_030], ["TASK_RUNNING", 9_029], ["TASK_COMPLETED", 9_004]]
  },
  "src-ik": {
    status: "COMPLETED", phase: "INDEXED",
    events: [["TASK_CREATED", 5_415], ["TASK_RUNNING", 5_414], ["TASK_COMPLETED", 5_406]]
  },
  "src-memory": {
    status: "COMPLETED", phase: "PARSED_LOCAL_ONLY",
    events: [["TASK_CREATED", 2_404], ["TASK_RUNNING", 2_403], ["TASK_COMPLETED", 2_401]]
  },
  "src-arch": {
    status: "FAILED", phase: "INDEX_FAILED",
    error: "资料解析已完成，但检索索引生成失败，将在 2 分钟后自动重试",
    events: [
      ["TASK_CREATED", 190], ["TASK_RUNNING", 189],
      ["TASK_PROGRESS", 186, {}, "解析完成，提取 9860 个字符（8 页），等待切片"],
      ["TASK_PROGRESS", 184, {}, "切片完成，共 31 个片段，等待向量化"],
      ["TASK_FAILED", 60, { error_code: "EMBEDDING_PROVIDER_TIMEOUT", retryable: true }]
    ]
  },
  "src-meeting": {
    status: "RUNNING", phase: "TRANSCRIBING",
    events: [["TASK_CREATED", 140], ["TASK_RUNNING", 139],
      ["TASK_PROGRESS", 136, {}, "已提交音视频转写，等待 MCP 转写工具返回文字稿"]]
  },
  "src-scan": {
    status: "FAILED", phase: "SOURCE_PARSE_FAILED",
    error: "PDF 未提取到可检索文本，请先对扫描件执行 OCR：扫描版合同.pdf",
    events: [
      ["TASK_CREATED", 1_210], ["TASK_RUNNING", 1_209],
      ["TASK_FAILED", 1_200, { error_code: "SOURCE_PDF_TEXT_EMPTY", retryable: false }]
    ]
  }
};

export const sourceTasks: Record<string, ExecutionTask> = Object.fromEntries(
  Object.entries(taskSeeds).map(([sourceId, seed]) => [`task-${sourceId}`, {
    task_id: `task-${sourceId}`,
    task_type: "SOURCE_PARSE",
    task_status: seed.status,
    progress_phase: seed.phase,
    progress_message: "",
    result_ref: "",
    error_message: seed.error ?? "",
    target_type: "SOURCE",
    target_id: sourceId,
    wait_context: null
  }])
);

/** 与 /api/v2/tasks/{id}/event-history 的响应结构一致。 */
export const sourceTaskEvents: Record<string, Array<Record<string, string>>> = Object.fromEntries(
  Object.entries(taskSeeds).map(([sourceId, seed]) => [`task-${sourceId}`, seed.events.map(([type, secondsBefore, payload, message], index) => ({
    event_id: `${sourceId}-event-${index + 1}`,
    event_type: type,
    message: message ?? "",
    payload_json: JSON.stringify(payload ?? {}),
    created_at: secondsAgo(secondsBefore)
  }))])
);

/** 重新处理：解析失败的资料回到解析中，只有索引失败的资料回到向量化中，与后端的重新处理接口一致。 */
export function reprocessMockSource(sourceId: string) {
  const target = sources.find((item) => item.source_id === sourceId);
  if (!target) throw new Error(`unknown mock source ${sourceId}`);
  const restartFrom = target.parse_status === "FAILED" ? "PARSE" : "INDEX";
  Object.assign(target, restartFrom === "PARSE"
    ? { status: "PROCESSING", parse_status: "PENDING", index_status: "PENDING", processing_stage: null }
    : { status: "PROCESSING", index_status: "INDEXING", processing_stage: "EMBEDDING", index_attempt_count: 0, next_index_retry_at: null });
  target.updated_at = new Date().toISOString();
  return { source_id: sourceId, task_id: target.task_id, restart_from: restartFrom };
}
