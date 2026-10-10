import { describe, expect, it } from "vitest";
import {
  buildSourcePipeline,
  buildSourceTaskEntries,
  describeSourceMeta,
  summarizeTaskDuration
} from "./sourcePipeline";

const stages = (status: string, parse: string, index: string, processing_stage: string | null = null) =>
  buildSourcePipeline({ status, parse_status: parse, index_status: index, processing_stage })
    .stages.map((stage) => stage.status);

function event(eventType: string, createdAt: string, payload: Record<string, unknown> = {}) {
  return { id: `${eventType}-${createdAt}`, event: "task.progress", data: "", message: "", payload, eventType, createdAt };
}

describe("buildSourcePipeline", () => {
  it("maps the backend processing stage to the four processing stages", () => {
    expect(stages("PROCESSING", "PENDING", "PENDING")).toEqual(["active", "pending", "pending", "pending"]);
    expect(stages("PROCESSING", "PENDING", "PENDING", "CHUNKING")).toEqual(["done", "active", "pending", "pending"]);
    expect(stages("PROCESSING", "PARSED", "INDEXING", "EMBEDDING")).toEqual(["done", "done", "active", "pending"]);
    expect(stages("PROCESSING", "PARSED", "INDEXING", "INDEXING")).toEqual(["done", "done", "done", "active"]);
    expect(stages("READY", "PARSED", "INDEXED")).toEqual(["done", "done", "done", "done"]);
    expect(stages("READY", "PARSED", "DISABLED")).toEqual(["done", "done", "skipped", "skipped"]);
  });

  it("tells parse failures apart from index failures", () => {
    expect(buildSourcePipeline({ status: "FAILED", parse_status: "FAILED", index_status: "PENDING" }))
      .toMatchObject({ state: "failed", failure: "parse", label: "解析失败" });
    // 解析任务在投递阶段耗尽重试时，资料状态为 FAILED 而解析状态可能仍是 PENDING
    expect(buildSourcePipeline({ status: "FAILED", parse_status: "PENDING", index_status: "PENDING" }))
      .toMatchObject({ failure: "parse" });
    expect(buildSourcePipeline({ status: "FAILED", parse_status: "FAILED", index_status: "PENDING", processing_stage: "CHUNKING" }))
      .toMatchObject({ failure: "parse", label: "切片失败" });
    expect(buildSourcePipeline({ status: "FAILED", parse_status: "PARSED", index_status: "FAILED", processing_stage: "EMBEDDING" }))
      .toMatchObject({ state: "failed", failure: "index", label: "向量化失败", retryAt: null });
    expect(buildSourcePipeline({ status: "FAILED", parse_status: "PARSED", index_status: "FAILED", processing_stage: "INDEXING" }))
      .toMatchObject({ failure: "index", label: "写入索引失败" });
  });

  it("shows the transcription step for audio and video sources", () => {
    expect(buildSourcePipeline({ status: "PROCESSING", parse_status: "PENDING", index_status: "PENDING", processing_stage: "TRANSCRIBING" }))
      .toMatchObject({ state: "processing", label: "转写中" });
    expect(buildSourcePipeline({ status: "FAILED", parse_status: "FAILED", index_status: "PENDING", processing_stage: "TRANSCRIBING" }))
      .toMatchObject({ failure: "parse", label: "转写失败" });
    expect(describeSourceMeta({ title: "组会录音.mp3", source_type: "USER_UPLOAD", generated_by: "", chunk_count: 12 }))
      .toBe("音频 · 12 个切片");
  });

  it("marks index failures that will be retried automatically", () => {
    expect(buildSourcePipeline({
      status: "FAILED", parse_status: "PARSED", index_status: "FAILED",
      processing_stage: "EMBEDDING", next_index_retry_at: "2026-09-30T10:02:00Z"
    })).toMatchObject({ label: "向量化失败，稍后自动重试", retryAt: "2026-09-30T10:02:00Z" });
  });
});

describe("describeSourceMeta", () => {
  it("shows format, pages and chunks, and names generated sources by origin", () => {
    expect(describeSourceMeta({ title: "压测.pdf", source_type: "USER_UPLOAD", generated_by: "", page_count: 12, chunk_count: 48 }))
      .toBe("PDF · 12 页 · 48 个切片");
    expect(describeSourceMeta({ title: "草稿", source_type: "USER_UPLOAD", generated_by: "", chunk_count: null }))
      .toBe("文件");
    expect(describeSourceMeta({ title: "报告.md", source_type: "GENERATED", generated_by: "research_agent", chunk_count: 5 }))
      .toBe("深度研究报告 · 5 个切片");
  });
});

describe("task record", () => {
  const events = [
    event("TASK_CREATED", "2026-09-01T10:00:00Z"),
    event("TASK_RUNNING", "2026-09-01T10:00:01Z"),
    event("TASK_HEARTBEAT", "2026-09-01T10:05:00Z"),
    event("TASK_FAILED", "2026-09-01T10:01:15Z", { error_code: "EMBEDDING_REQUEST_FAILED", retryable: false })
  ];

  it("lists events without heartbeats and explains failures", () => {
    const entries = buildSourceTaskEntries(events);
    expect(entries.map((entry) => entry.label)).toEqual(["已加入处理队列", "开始处理", "处理失败"]);
    expect(entries[2]).toMatchObject({ tone: "failed", detail: "EMBEDDING_REQUEST_FAILED" });
  });

  it("measures finished tasks between events and running tasks up to now", () => {
    expect(summarizeTaskDuration(events)).toBe("1 分 15 秒");
    expect(summarizeTaskDuration(events.slice(0, 2), true, Date.parse("2026-09-01T10:00:40Z"))).toBe("40 秒");
    expect(summarizeTaskDuration(events.slice(0, 1))).toBe("");
  });
});
