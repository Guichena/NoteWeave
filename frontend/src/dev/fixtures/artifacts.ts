import type { ArtifactRuntimeTrace } from "../../features/artifacts/artifactRuntimeTrace";
import type {
  ArtifactJobSummary,
  ArtifactVersionComparison,
  ArtifactVersionDetail,
  ArtifactVersionSummary,
  VideoLearningOverview
} from "../../features/artifacts/model";

// 产物页设计数据：覆盖生成中、等待外部服务、失败和已完成（含多个版本、PDF 文件、MCP 能力调用）几种状态。

const now = Date.now();
const minutesAgo = (minutes: number) => new Date(now - minutes * 60_000).toISOString();

function job(
  id: string,
  skillKey: string,
  status: string,
  phase: string,
  title: string,
  latestVersionNo: number,
  updatedMinutesAgo: number,
  extra: Partial<ArtifactJobSummary> = {}
): ArtifactJobSummary {
  return {
    artifact_job_id: id,
    workspace_id: "",
    task_id: `task-${id}`,
    skill_key: skillKey,
    status,
    task_status: status,
    progress_phase: phase,
    progress_message: "",
    result_title: title,
    wait_context: null,
    latest_version_no: latestVersionNo,
    created_at: minutesAgo(updatedMinutesAgo + 5),
    updated_at: minutesAgo(updatedMinutesAgo),
    ...extra
  };
}

export const jobs: ArtifactJobSummary[] = [
  job("job-quiz", "quiz_pack", "RUNNING", "COMPOSING", "", 0, 1),
  job("job-minutes", "audio_minutes", "WAITING_FOR_PROVIDER", "WAITING_FOR_PROVIDER", "", 0, 6, {
    task_status: "WAITING",
    wait_context: {
      status: "WAITING_FOR_PROVIDER",
      provider_job: { provider_id: "media-mcp", capability_name: "TRANSCRIBE_AUDIO", operation_key: "transcribe_audio" }
    }
  }),
  job("job-report", "report_draft", "COMPLETED", "EXPORTING", "RAG 检索链路综合报告", 3, 25),
  job("job-mindmap", "mindmap_from_workspace", "COMPLETED", "EXPORTING", "混合检索知识导图", 1, 130),
  job("job-faq", "faq_draft", "FAILED", "VERIFYING", "", 0, 190, {
    progress_message: "输出契约校验未通过，3 个章节缺少来源引用"
  }),
  job("job-bili", "bilibili_course_note_pdf", "COMPLETED", "EXPORTING", "Elasticsearch 中文分词讲义", 1, 1500)
];

const PHASES = ["RESOLVING", "ACQUIRING", "COMPOSING", "VERIFYING", "EXPORTING"];

function builtinCapabilities(names: string[]) {
  return names.map((capability_name) => ({ capability_name, scope_type: "BUILTIN", runtime_status: "READY" }));
}

function trace(options: {
  verification: "PASS" | "WARN";
  passed: number;
  repaired: string[];
  sections: number;
  covered: number;
  missing: string[];
  sourceCount: number;
  capabilities: NonNullable<ArtifactRuntimeTrace["capability_union_trace"]>["capability_decisions"];
  exportStatus?: string;
}): ArtifactRuntimeTrace {
  const passed = Array.from({ length: options.passed }, (_, index) => `contract_check_${index + 1}`);
  return {
    verification: { status: options.verification, passed_checks: passed, repaired_checks: options.repaired, failed_checks: [], warnings: [] },
    generation_trace: { mode: "LLM", provider: "siliconflow", model: "Qwen2.5-72B-Instruct", attempted: true, applied: true, source_count: options.sourceCount, generated_section_count: options.sections },
    export_trace: { status: options.exportStatus ?? "COMPILED", format: "PDF", file_name: "artifact.pdf" },
    evidence_coverage: {
      status: options.missing.length > 0 ? "PARTIAL" : "COVERED",
      section_count: options.sections,
      covered_section_count: options.covered,
      sections_missing_evidence: options.missing,
      supporting_source_ids: ["src-rag", "src-bench", "src-ik"]
    },
    output_contract_trace: {
      status: "PASSED",
      passed_checks: passed,
      repaired_checks: options.repaired,
      failed_checks: [],
      contract_checks: [
        { label: "章节结构符合输出契约", status: "PASS" },
        { label: "每个结论都附来源", status: options.missing.length > 0 ? "WARN" : "PASS" }
      ]
    },
    capability_union_trace: { status: "ALLOWED", decision: "ALLOW", capability_decisions: options.capabilities },
    lifecycle_trace: {
      status: "COMPLETED",
      current_phase: "EXPORTING",
      steps: PHASES.map((phase, index) => ({ phase, status: "COMPLETED", progress_percent: [10, 30, 60, 85, 100][index], message: "" }))
    }
  };
}

const reportV3 = `# RAG 检索链路综合报告

## 结论

当前工作台的问答链路采用 **BM25 + 向量双路召回，经 RRF 融合后交给 Rerank 重排**。在三个评测集上，这一组合比单路向量检索的 NDCG@10 高 6 到 9 个百分点，且无需为不同数据集调节融合权重。

## 关键证据

- **融合方式**：RRF 只使用名次，得分为各路 1 / (60 + rank) 之和，避免了 BM25 与余弦相似度量纲不一致的问题。
- **召回规模**：两路各取前 50 条，融合后保留前 80 条候选进入重排，重排后截取 8 条进入上下文。
- **中文分词**：BM25 一路依赖 IK 分词，专有名词需要维护自定义词典，否则召回率明显下降。

## 分歧与风险

| 方案 | 优点 | 风险 |
| --- | --- | --- |
| 加权求和 | 实现直观 | 每个数据集都要重新调参 |
| RRF | 参数稳定 | 丢失原始分数中的置信度信息 |
| 学习排序 | 效果上限高 | 需要标注数据和持续维护 |

## 行动建议

1. 保持 RRF 作为默认融合方式，k 取 60。
2. 为 IK 分词补充领域词典，并在每次资料导入后抽检召回结果。
3. 在评测集中加入长文档样本，观察 Rerank 截断对答案完整性的影响。
`;

const reportV2 = reportV3
  .replace("在三个评测集上，这一组合比单路向量检索的 NDCG@10 高 6 到 9 个百分点，且无需为不同数据集调节融合权重。", "这一组合在评测中优于单路向量检索。")
  .replace(/\n## 分歧与风险[\s\S]*?(?=\n## 行动建议)/, "\n");

const reportV1 = `# RAG 检索链路综合报告

## 结论

问答链路使用混合检索：BM25 与向量两路召回，融合后重排。

## 关键证据

- 融合使用 RRF。
- 中文分词依赖 IK 插件。
`;

const mindmap = `# 混合检索

## 召回
- BM25 关键词召回
  - IK 中文分词
  - 自定义领域词典
- 向量语义召回
  - bge-m3 向量
  - HNSW 索引

## 融合
- RRF 名次融合
  - k = 60
- 加权求和（对比方案）

## 重排
- Rerank 精排
  - 候选 80 条
  - 截取 8 条进入上下文

## 评测
- NDCG@10
- 越界引用率
`;

const biliNotes = `# Elasticsearch 中文分词讲义

> 根据 B 站视频《Elasticsearch 中文分词实战》第 2 集整理，截取了 6 张关键画面。

## 一、为什么需要中文分词

英文天然以空格分词，中文需要借助词典或模型切分。未做分词时，BM25 只能按单字匹配，召回噪声很大。

## 二、IK 分词器的两种模式

- **ik_max_word**：尽可能多地切分，适合建索引。
- **ik_smart**：最粗粒度切分，适合查询时使用。

## 三、自定义词典与热更新

视频演示了通过远程词典地址实现热更新：修改词典文件后，节点会在一分钟内拉取新词，无需重启集群。

## 小结

建索引用 ik_max_word，查询用 ik_smart；专有名词放入自定义词典并定期维护。
`;

const reportCapabilities = builtinCapabilities(["READ_WORKSPACE_DOC", "GENERATE_STRUCTURED_TEXT", "VERIFY_OUTPUT"]);

function version(
  jobId: string,
  skillKey: string,
  versionNo: number,
  title: string,
  markdown: string,
  runtime: ArtifactRuntimeTrace,
  createdMinutesAgo: number,
  files: ArtifactVersionDetail["files"] = []
): ArtifactVersionDetail {
  return {
    version_id: `${jobId}-v${versionNo}`,
    artifact_job_id: jobId,
    skill_key: skillKey,
    version_no: versionNo,
    title,
    content_markdown: markdown,
    trace_summary: "",
    citations: [],
    runtime_trace: runtime,
    files,
    created_at: minutesAgo(createdMinutesAgo)
  };
}

function file(id: string, format: string, name: string, mediaType: string, size: number): ArtifactVersionDetail["files"][number] {
  return {
    file_id: id, file_format: format, file_name: name, media_type: mediaType,
    storage_backend: "MINIO", bucket_name: "artifacts", object_key: `artifacts/${name}`,
    size_bytes: size, checksum_sha256: "", status: "READY", error_message: "", created_at: minutesAgo(1500)
  };
}

export const versionDetails: Record<string, ArtifactVersionDetail> = Object.fromEntries([
  version("job-report", "report_draft", 3, "RAG 检索链路综合报告", reportV3, trace({
    verification: "PASS", passed: 12, repaired: ["补齐表格列", "统一术语写法"], sections: 4, covered: 4, missing: [],
    sourceCount: 4, capabilities: reportCapabilities
  }), 25),
  version("job-report", "report_draft", 2, "RAG 检索链路综合报告", reportV2, trace({
    verification: "PASS", passed: 11, repaired: ["补齐章节标题"], sections: 3, covered: 3, missing: [],
    sourceCount: 3, capabilities: reportCapabilities
  }), 80),
  version("job-report", "report_draft", 1, "RAG 检索链路综合报告", reportV1, trace({
    verification: "WARN", passed: 8, repaired: [], sections: 2, covered: 1, missing: ["关键证据"],
    sourceCount: 2, capabilities: reportCapabilities
  }), 200),
  version("job-mindmap", "mindmap_from_workspace", 1, "混合检索知识导图", mindmap, trace({
    verification: "PASS", passed: 9, repaired: [], sections: 4, covered: 4, missing: [],
    sourceCount: 3, capabilities: reportCapabilities, exportStatus: "SKIPPED"
  }), 130),
  version("job-bili", "bilibili_course_note_pdf", 1, "Elasticsearch 中文分词讲义", biliNotes, trace({
    verification: "PASS", passed: 14, repaired: ["压缩重复画面"], sections: 4, covered: 3, missing: ["小结"],
    sourceCount: 1,
    capabilities: [
      ...builtinCapabilities(["GENERATE_STRUCTURED_TEXT", "VERIFY_OUTPUT"]),
      { capability_name: "EXTRACT_TRANSCRIPT", scope_type: "SYSTEM_MCP", server_id: "bilibili", tool_name: "get_subtitle", runtime_status: "READY" },
      { capability_name: "CAPTURE_VIDEO_FRAMES", scope_type: "SYSTEM_MCP", server_id: "bilibili", tool_name: "capture_bilibili_frames", runtime_status: "READY" },
      { capability_name: "ANALYZE_FRAME", scope_type: "SYSTEM_MCP", server_id: "bilibili", tool_name: "analyze_frames", runtime_status: "READY" }
    ]
  }), 1500, [
    file("file-bili-pdf", "PDF", "es-chinese-analyzer.pdf", "application/pdf", 1_842_331),
    file("file-bili-md", "MARKDOWN", "es-chinese-analyzer.md", "text/markdown", 4_812)
  ])
].map((detail) => [`${detail.artifact_job_id}:${detail.version_no}`, detail]));

export function versionList(jobId: string): ArtifactVersionSummary[] {
  return Object.values(versionDetails)
    .filter((detail) => detail.artifact_job_id === jobId)
    .map(({ version_id, artifact_job_id, skill_key, version_no, title, created_at }) => ({
      version_id, artifact_job_id, skill_key, version_no, title, created_at
    }));
}

export function compareVersions(from: number, to: number): ArtifactVersionComparison {
  return {
    from_version_no: from,
    to_version_no: to,
    title_changed: false,
    added_lines: to === 3 ? 9 : 14,
    removed_lines: to === 3 ? 1 : 5,
    unchanged_lines: 18,
    summary: `v${from} → v${to}`
  };
}

export const videoLearning: VideoLearningOverview = {
  enabled: true,
  available_skills: ["knowledge_blog", "interview_qa", "video_learning_deck", "bilibili_course_note_pdf"],
  requests: []
};
