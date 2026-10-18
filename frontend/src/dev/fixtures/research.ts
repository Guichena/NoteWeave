import type {
  ResearchCell,
  ResearchCounterCheck,
  ResearchDiscoveryProposal,
  ResearchReplan,
  ResearchPlan,
  ResearchPlanRevision,
  ResearchRunDetail,
  ResearchRunSummary
} from "../../features/research/model";

// 结构与研究运行接口响应完全一致的设计数据。
// 包含一个已完成的运行（研究表已探索完毕，其中一处冲突由反证分支消解）和一个仍在进行中的运行。

const now = Date.now();
const minutesAgo = (minutes: number) => new Date(now - minutes * 60_000).toISOString();


type CellSeed = [row: string, column: string, value: string, status: string, refs: string[], decision?: string, repairs?: number];

const cellSeeds: CellSeed[] = [
  ["elasticsearch", "hybrid_search", "原生支持 BM25 + kNN，并可用 RRF 融合排序", "VERIFIED", ["ev-1", "ev-2"], "SUPPORTS"],
  ["elasticsearch", "chinese_analyzer", "需安装 IK / smartcn 插件，词典可热更新", "VERIFIED", ["ev-3"], "SUPPORTS"],
  ["elasticsearch", "filtering", "文档级安全与过滤查询可在检索时生效", "VERIFIED", ["ev-4"], "SUPPORTS"],
  ["elasticsearch", "ops_cost", "JVM 集群需要调优堆内存与分片", "FILLED", ["ev-5"], "PARTIALLY_SUPPORTS"],
  ["elasticsearch", "scale_limit", "分片横向扩展，单集群可达数十亿文档", "VERIFIED", ["ev-6"], "SUPPORTS"],
  ["milvus", "hybrid_search", "2.4 起支持稀疏 + 稠密多向量检索与 RRF", "VERIFIED", ["ev-7"], "SUPPORTS"],
  ["milvus", "chinese_analyzer", "2.5 内置 jieba 分析器；早期版本需外部分词", "CONFLICTED", ["ev-8", "ev-9"], "CONTRADICTS", 1],
  ["milvus", "filtering", "标量过滤 + 分区键，行级权限需业务层实现", "VERIFIED", ["ev-10"], "SUPPORTS"],
  ["milvus", "ops_cost", "分布式版依赖 etcd、MinIO、Pulsar 等组件", "VERIFIED", ["ev-11"], "SUPPORTS"],
  ["milvus", "scale_limit", "存算分离，面向百亿级向量", "VERIFIED", ["ev-12"], "SUPPORTS"],
  ["pgvector", "hybrid_search", "需自行组合 tsvector 全文检索与向量相似度", "VERIFIED", ["ev-13"], "SUPPORTS"],
  ["pgvector", "chinese_analyzer", "", "MISSING", [], "NOT_ENOUGH_INFO"],
  ["pgvector", "filtering", "直接复用 SQL WHERE 与行级安全策略", "VERIFIED", ["ev-14"], "SUPPORTS"],
  ["pgvector", "ops_cost", "随 PostgreSQL 部署，无额外组件", "VERIFIED", ["ev-15"], "SUPPORTS"],
  ["pgvector", "scale_limit", "HNSW 索引受单机内存约束，千万级后需分片方案", "NEEDS_REPAIR", ["ev-16"], "PARTIALLY_SUPPORTS", 1]
];

const evidenceSeeds: Array<[key: string, title: string, url: string, quote: string, relation: string, score: number]> = [
  ["ev-1", "Elasticsearch Reference · Hybrid search", "https://www.elastic.co/guide/en/elasticsearch/reference/current/knn-search.html", "You can combine kNN search with a standard query… and use reciprocal rank fusion to merge the result sets.", "SUPPORTS", 0.93],
  ["ev-2", "Elastic Blog · RRF in Elasticsearch", "https://www.elastic.co/blog/improving-information-retrieval-elastic-stack-hybrid", "Reciprocal Rank Fusion requires no tuning and blends lexical and semantic rankings.", "SUPPORTS", 0.88],
  ["ev-3", "IK Analysis for Elasticsearch", "https://github.com/infinilabs/analysis-ik", "The IK Analysis plugin integrates Lucene IK analyzer into Elasticsearch and supports hot-reload of custom dictionaries.", "SUPPORTS", 0.9],
  ["ev-4", "Elasticsearch Reference · Document level security", "https://www.elastic.co/guide/en/elasticsearch/reference/current/document-level-security.html", "Document level security restricts the documents that users have read access to.", "SUPPORTS", 0.86],
  ["ev-5", "Elastic Docs · Size your shards", "https://www.elastic.co/guide/en/elasticsearch/reference/current/size-your-shards.html", "Aim for shard sizes between 10GB and 50GB… oversharding increases overhead.", "PARTIALLY_SUPPORTS", 0.64],
  ["ev-6", "Elastic Docs · Scalability and resilience", "https://www.elastic.co/guide/en/elasticsearch/reference/current/scalability.html", "Elasticsearch distributes data across shards so the cluster can grow horizontally.", "SUPPORTS", 0.81],
  ["ev-7", "Milvus Docs · Hybrid Search", "https://milvus.io/docs/multi-vector-search.md", "Milvus supports hybrid search across multiple vector fields and reranks results with RRFRanker.", "SUPPORTS", 0.91],
  ["ev-8", "Milvus Docs · Analyzer overview (v2.5)", "https://milvus.io/docs/analyzer-overview.md", "Milvus 2.5 provides a built-in jieba tokenizer for Chinese text.", "SUPPORTS", 0.84],
  ["ev-9", "社区问答 · Milvus 2.3 中文全文检索", "https://github.com/milvus-io/milvus/discussions", "2.3 版本不提供全文检索，中文需要在写入前自行分词生成稀疏向量。", "CONTRADICTS", 0.72],
  ["ev-10", "Milvus Docs · Filtering", "https://milvus.io/docs/boolean.md", "Scalar filtering narrows search results with boolean expressions on scalar fields.", "SUPPORTS", 0.87],
  ["ev-11", "Milvus Docs · Architecture overview", "https://milvus.io/docs/architecture_overview.md", "Milvus cluster relies on etcd for metadata, MinIO/S3 for storage and Pulsar/Kafka as the log broker.", "SUPPORTS", 0.9],
  ["ev-12", "Milvus Docs · Overview", "https://milvus.io/docs/overview.md", "A cloud-native vector database built for billion-scale vector similarity search.", "SUPPORTS", 0.83],
  ["ev-13", "pgvector README · Hybrid search", "https://github.com/pgvector/pgvector#hybrid-search", "Use together with Postgres full-text search for hybrid search… combine results with Reciprocal Rank Fusion.", "SUPPORTS", 0.89],
  ["ev-14", "PostgreSQL Docs · Row Security Policies", "https://www.postgresql.org/docs/current/ddl-rowsecurity.html", "Row security policies restrict, on a per-user basis, which rows can be returned by normal queries.", "SUPPORTS", 0.85],
  ["ev-15", "pgvector README · Installation", "https://github.com/pgvector/pgvector#installation", "Open-source vector similarity search for Postgres… store your vectors with the rest of your data.", "SUPPORTS", 0.8],
  ["ev-16", "pgvector README · HNSW", "https://github.com/pgvector/pgvector#hnsw", "Indexes build significantly faster when the graph fits into maintenance_work_mem.", "PARTIALLY_SUPPORTS", 0.58]
];

const rowTitles: Record<string, string> = { elasticsearch: "Elasticsearch", milvus: "Milvus", pgvector: "pgvector" };

// 与后端一致：单元格携带规划器给出的列显示名
const columnLabels: Record<string, string> = {
  hybrid_search: "混合检索",
  chinese_analyzer: "中文分词",
  filtering: "过滤与权限",
  ops_cost: "运维成本",
  scale_limit: "规模上限"
};

function buildCells() {
  return cellSeeds.map(([row, column, value, status, refs, decision = "", repairs = 0]) => ({
    cell_id: `${row}:${column}`,
    row_id: row,
    branch_id: row === "milvus" && column === "chinese_analyzer" ? "cf-1" : "main",
    column_key: column,
    column_label: columnLabels[column] ?? column,
    candidate_value: value,
    status,
    confidence: status === "VERIFIED" ? 0.86 : status === "MISSING" ? null : 0.55,
    evidence_refs: refs,
    last_verifier_decision: decision,
    repair_count: repairs
  }));
}

function buildEvidence() {
  return evidenceSeeds.map(([key, title, url, quote, relation, score]) => ({
    evidence_id: key,
    source_title: title,
    source_url: url,
    provider: "web",
    quote_text: quote,
    claim_text: cellSeeds.find((cell) => cell[4].includes(key))?.[2] ?? "",
    relation_type: relation,
    support_score: score
  }));
}

function buildRows() {
  return Object.entries(rowTitles).map(([rowId, title]) => {
    const cells = cellSeeds.filter((cell) => cell[0] === rowId);
    const verified = cells.filter((cell) => cell[3] === "VERIFIED").length;
    return {
      row_id: rowId,
      source_title: title,
      read_focus: `${title} 在中文 RAG 场景下的能力`,
      row_status: cells.some((cell) => cell[3] === "CONFLICTED") ? "CONFLICTED" : verified === cells.length ? "VERIFIED" : "NEEDS_REPAIR",
      required_column_count: cells.length,
      completed_column_count: verified,
      missing_columns: cells.filter((cell) => cell[3] === "MISSING").map((cell) => cell[1])
    };
  });
}

const rounds = [
  {
    round_no: 1,
    search_hit_count: 42,
    read_window_count: 18,
    evidence_card_count: 9,
    search_queries: ["Elasticsearch hybrid search RRF", "Milvus full text search Chinese", "pgvector hybrid search"],
    evidence_ids: ["ev-1", "ev-3", "ev-7", "ev-13"],
    branch_decision: "EXPAND",
    global_decision: "CONTINUE",
    plan_note: "建立 3 × 5 研究表，优先补齐混合检索与中文分词两列"
  },
  {
    round_no: 2,
    search_hit_count: 31,
    read_window_count: 14,
    evidence_card_count: 8,
    search_queries: ["Milvus 2.5 jieba analyzer", "Milvus architecture etcd pulsar", "pgvector HNSW memory"],
    evidence_ids: ["ev-8", "ev-9", "ev-11", "ev-16"],
    branch_decision: "COUNTERFACTUAL",
    global_decision: "REPLAN",
    plan_note: "Milvus 中文分词出现互相矛盾的证据，开启受限反证分支按版本核对"
  },
  {
    round_no: 3,
    search_hit_count: 12,
    read_window_count: 6,
    evidence_card_count: 4,
    search_queries: ["Milvus release notes 2.5 analyzer", "pgvector sharding citus"],
    evidence_ids: ["ev-8", "ev-12", "ev-16"],
    branch_decision: "MERGE",
    global_decision: "COMPLETE",
    plan_note: "反证分支确认差异来自版本；pgvector 扩展上限证据不足，标记待修复后收敛"
  }
];

const checkpoints = rounds.map((round, index) => ({
  checkpoint_no: index + 1,
  snapshot_type: index === 2 ? "FINAL" : "ROUND",
  object_key: `research/checkpoints/run-rag/${index + 1}.json`,
  payload_sha256: `3f9c${index}a71e5b2`,
  content_size: 18_000 + index * 6_400,
  active_branch_id: index === 1 ? "cf-1" : "main",
  final_loop_decision: round.global_decision,
  summary: {},
  verified_row_count: [4, 9, 12][index],
  conflicted_row_count: [0, 1, 1][index],
  created_at: minutesAgo(38 - index * 9)
}));

const counterfactual = {
  has_counterfactual_recheck: true,
  counterfactual_branch_count: 1,
  conflicted_row_count: 1,
  local_verifier_status: "PASSED_WITH_WARNINGS",
  global_verifier_decision: "COMPLETE",
  recovery_mode: "",
  counterfactual_branch_ids: ["cf-1"],
  active_counterfactual_branch_ids: [],
  branch_reasons: ["CONFLICTING_EVIDENCE"],
  target_evidence_ids: ["ev-8", "ev-9"],
  branches: [{
    branch_id: "cf-1",
    parent_branch_id: "main",
    branch_reason: "CONFLICTING_EVIDENCE",
    branch_status: "MERGED",
    decision: "VERSION_SCOPED",
    verifier_scope: "milvus:chinese_analyzer",
    hypothesis_summary: "假设「Milvus 不支持中文分词」成立，检索 2.5 之后的官方发布说明进行反证",
    target_evidence_ids: ["ev-8", "ev-9"]
  }]
};

const reportMarkdown = `# 中文 RAG 检索方案选型：Elasticsearch、Milvus 与 pgvector

## 结论

- **需要成熟的 BM25 + 向量混合检索，且团队已有 ES 运维经验**：选 Elasticsearch。原生支持 kNN 与 RRF 融合 [evidence:ev-1]，中文依赖 IK 插件且词典可热更新 [evidence:ev-3]。
- **向量规模在十亿级以上、需要多向量检索**：选 Milvus。2.4 起支持稀疏 + 稠密混合检索 [evidence:ev-7]，但分布式部署依赖 etcd、MinIO 与 Pulsar，运维成本最高 [evidence:ev-11]。
- **数据量在千万级以内、希望与业务库共用权限模型**：选 pgvector。可直接复用行级安全策略 [evidence:ev-14]，混合检索需要自行组合全文检索与 RRF [evidence:ev-13]。

## 关键差异

| 维度 | Elasticsearch | Milvus | pgvector |
| --- | --- | --- | --- |
| 混合检索 | 原生 RRF | 多向量 + RRF | 需自行组合 |
| 中文分词 | IK 插件 | 2.5 起内置 jieba | 依赖 zhparser 等扩展 |
| 运维成本 | 中 | 高 | 低 |

## 版本差异说明

Milvus 的中文能力在不同版本间存在矛盾证据：2.5 文档提供内置 jieba 分析器 [evidence:ev-8]，而 2.3 的社区回答指出需在写入前自行分词 [evidence:ev-9]。反证分支确认两者都成立，差异来自版本，因此本结论只适用于 2.5 及以上。

## 尚未确认

- pgvector 的中文全文检索能力缺少可引用的一手资料，研究表中保留为缺失。
- pgvector 在千万级以上的扩展方案只有部分证据 [evidence:ev-16]，建议补充 Citus 分片的实测资料。
`;

export const completedRun: ResearchRunDetail = {
  research_run_id: "run-rag",
  workspace_id: "",
  task_id: "task-run-rag",
  question: "中文资料问答场景下，Elasticsearch、Milvus 与 pgvector 该如何选型？",
  profile_key: "default",
  research_intent: {
    research_goal: "给出按场景划分的选型建议，并标注证据不足之处",
    deliverable_format: "选型报告",
    constraints: ["只采用官方文档或一手资料", "中文场景优先"],
    time_range: "2024-2026",
    depth: "STANDARD",
    research_type: "TECH_SOLUTION_COMPARISON"
  },
  resumed_from_research_run_id: "",
  resumed_from_checkpoint_no: null,
  status: "COMPLETED",
  completion_terminal_state: "COMPLETED",
  final_report_title: "中文 RAG 检索方案选型：Elasticsearch、Milvus 与 pgvector",
  final_report_markdown: reportMarkdown,
  report_structure: null,
  counterfactual_summary: counterfactual,
  research_process_summary: {
    source_scope_count: 2,
    search_read_timeline: {
      loop_round_count: 3,
      total_search_hit_count: 85,
      total_read_window_count: 38,
      total_evidence_card_count: 21,
      all_search_queries: rounds.flatMap((round) => round.search_queries),
      final_loop_decision: "COMPLETE",
      final_loop_reason: "REQUIRED_COLUMNS_COVERED",
      terminal_disposition: "COMPLETED",
      handoff_required: false,
      abandon_reason: "",
      rounds
    },
    source_evidence_summary: {
      source_basis: "官方文档为主，辅以一条社区回答作为反证",
      primary_quality: "PRIMARY",
      quality_mix_label: "一手资料 15 · 社区 1",
      read_strategy_mix_label: "",
      fetch_foundation_label: "",
      orchestration_foundation_label: "",
      verified_finding_count: 12,
      citation_count: 16
    },
    audit_summary: {
      local_verifier_status: "PASSED_WITH_WARNINGS",
      global_verifier_decision: "COMPLETE",
      final_loop_decision: "COMPLETE",
      has_counterfactual_recheck: true,
      counterfactual_branch_count: 1,
      checkpoint_count: 3,
      blocked_row_count: 0,
      conflicted_row_count: 1,
      guardrailed_row_count: 0,
      recovery_target_count: 1
    }
  },
  trace_summary: "",
  source_scope: [
    { source_id: "src-1", title: "RAG 检索链路设计.md", summary: "", sample_text: "", generated_by: "", generated_ref_id: "" },
    { source_id: "src-2", title: "向量数据库压测记录.pdf", summary: "", sample_text: "", generated_by: "", generated_ref_id: "" }
  ],
  control_pack: {},
  saved_report_source: null,
  wait_context: null,
  closed_loop_state: {
    active_branch_id: "main",
    local_verifier_status: "PASSED_WITH_WARNINGS",
    global_verifier_decision: "COMPLETE",
    final_loop_decision: "COMPLETE",
    loop_rounds_count: 3,
    ledger_row_count: 3,
    branch_count: 2,
    verifier_decision_count: 15,
    harness_summary: {},
    checkpoint_candidate: {},
    counterfactual_summary: counterfactual,
    recovery_targets: null,
    state_ledger: {},
    local_verifier: {},
    global_verifier: {},
    branches: [],
    rows: buildRows(),
    cells: buildCells(),
    verifier_decisions: [],
    checkpoints,
    source_evidence: buildEvidence(),
    cell_evidence: [],
    branch_decisions: [],
    loop_rounds: rounds,
    loop_decision_payload: {}
  },
  traces: [],
  created_at: minutesAgo(42),
  updated_at: minutesAgo(20)
};

function partialRun(): ResearchRunDetail {
  const cells = buildCells().map((cell, index) => (
    index < 6 ? cell : { ...cell, status: index < 9 ? "PENDING" : "EMPTY", candidate_value: "", evidence_refs: [], last_verifier_decision: "" }
  ));
  return {
    ...completedRun,
    research_run_id: "run-agent-memory",
    task_id: "task-run-agent-memory",
    question: "长期记忆在 Agent 产品中如何避免污染后续事实？",
    status: "RUNNING",
    completion_terminal_state: "",
    final_report_title: "",
    final_report_markdown: "",
    counterfactual_summary: null,
    research_process_summary: {
      ...completedRun.research_process_summary!,
      search_read_timeline: { ...completedRun.research_process_summary!.search_read_timeline, loop_round_count: 1, rounds: rounds.slice(0, 1), final_loop_decision: "CONTINUE" }
    },
    closed_loop_state: {
      ...completedRun.closed_loop_state,
      final_loop_decision: "CONTINUE",
      cells,
      checkpoints: checkpoints.slice(0, 1),
      loop_rounds: rounds.slice(0, 1)
    },
    created_at: minutesAgo(4),
    updated_at: minutesAgo(1)
  };
}

export const runningRun = partialRun();

export const runs: ResearchRunSummary[] = [runningRun, completedRun].map((run) => ({
  research_run_id: run.research_run_id,
  task_id: run.task_id,
  question: run.question,
  profile_key: run.profile_key,
  status: run.status,
  final_report_title: run.final_report_title,
  resumed_from_research_run_id: "",
  resumed_from_checkpoint_no: null,
  source_scope_count: run.source_scope.length,
  checkpoint_count: run.closed_loop_state.checkpoints.length,
  active_branch_id: "main",
  local_verifier_status: run.closed_loop_state.local_verifier_status,
  local_verifier_reason: "",
  ledger_row_count: 3,
  verified_row_count: run.closed_loop_state.cells.filter((cell) => cell.status === "VERIFIED").length,
  conflicted_row_count: run.closed_loop_state.cells.filter((cell) => cell.status === "CONFLICTED").length,
  blocked_row_count: 0,
  guardrailed_row_count: 0,
  recovery_targeted_blocked_row_count: 0,
  uncovered_blocked_row_count: 0,
  requirement_partial_blocked_row_count: 0,
  global_verifier_decision: run.closed_loop_state.global_verifier_decision,
  global_verifier_reason: "",
  final_loop_decision: run.closed_loop_state.final_loop_decision,
  final_loop_reason: "",
  recovery_mode: "",
  research_intent_alignment_status: "ALIGNED",
  research_intent_alignment_reason: "",
  intent_satisfied_constraint_count: 2,
  intent_constraint_count: 2,
  intent_satisfied_requirement_count: 4,
  intent_requirement_count: 5,
  intent_pending_requirement_count: 1,
  missing_intent_requirements: [],
  research_process_summary: run.research_process_summary,
  counterfactual_summary: run.counterfactual_summary,
  created_at: run.created_at,
  updated_at: run.updated_at
}));

export const runDetails: Record<string, ResearchRunDetail> = {
  [completedRun.research_run_id]: completedRun,
  [runningRun.research_run_id]: runningRun
};

export const researchTasks: Record<string, Record<string, unknown>> = {
  [completedRun.task_id]: {
    task_id: completedRun.task_id, task_type: "RESEARCH", task_status: "SUCCEEDED",
    progress_phase: "COMPLETED", progress_message: "研究完成", result_ref: completedRun.research_run_id,
    error_message: "", target_type: "RESEARCH_RUN", target_id: completedRun.research_run_id, wait_context: null
  },
  [runningRun.task_id]: {
    task_id: runningRun.task_id, task_type: "RESEARCH", task_status: "RUNNING",
    progress_phase: "VERIFYING", progress_message: "第 2 轮：核验 Evidence 并补齐缺失字段", result_ref: runningRun.research_run_id,
    error_message: "", target_type: "RESEARCH_RUN", target_id: runningRun.research_run_id, wait_context: null
  }
};

// 研究表格：进行中的 Run 停在确认阶段，便于预览修改和开始研究；其余 Run 已开始研究。
const planStates: Record<string, ResearchPlan> = {};

function initialPlan(runId: string): ResearchPlan {
  return {
    plan_status: runId === runningRun.research_run_id ? "REVIEW" : "READY",
    review_deadline: null,
    planner: "llm-matrix.v1",
    rows: [
      { entity_key: "entity-postgresql", label: "PostgreSQL 17" },
      { entity_key: "entity-mysql", label: "MySQL 8.4" }
    ],
    columns: [
      { column_key: "dim-index-types", label: "JSON 索引类型", description: "支持哪些可加速 JSON 查询的索引", answer_format: "LIST", high_risk: false },
      { column_key: "dim-operators", label: "可加速的查询运算符", description: "索引能覆盖哪些 JSON 运算符和函数", answer_format: "LIST", high_risk: false },
      { column_key: "dim-limits", label: "限制与代价", description: "索引体积、写入开销和不支持的场景", answer_format: "LONG_TEXT", high_risk: false }
    ],
    brief: { objective: "比较两者 JSON 索引能力的覆盖范围与代价", exclusions: ["其他数据库"] },
    effort_tier: "STANDARD",
    authoritative_domains: ["postgresql.org", "dev.mysql.com"],
    perspectives: [{ name: "应用开发者", questions: ["哪些查询能用上索引"] }]
  };
}

export const researchPlan = {
  read(runId: string): ResearchPlan {
    planStates[runId] ??= initialPlan(runId);
    return planStates[runId];
  },
  approve(runId: string): ResearchPlan {
    planStates[runId] = { ...researchPlan.read(runId), plan_status: "READY" };
    return planStates[runId];
  },
  revise(runId: string, revision: ResearchPlanRevision): ResearchPlan {
    planStates[runId] = {
      ...researchPlan.read(runId),
      planner: "user-revision.1",
      rows: revision.entities.map((entity, index) => ({ entity_key: `entity-${index}`, label: entity.label })),
      columns: revision.dimensions.map((dimension, index) => ({
        column_key: `dim-${index}`, label: dimension.label, description: dimension.description ?? "",
        answer_format: "SHORT_TEXT", high_risk: false
      }))
    };
    return planStates[runId];
  }
};

// 1C、1D、1E 的附属数据：完成的 Run 有完整记录，进行中的 Run 只有部分单元格。
const cellFixtures: ResearchCell[] = [
  {
    cell_key: "entity-postgresql:dim-index-types", entity_label: "PostgreSQL 17", column_label: "JSON 索引类型",
    cell_status: "VERIFIED", candidate_value: "GIN（jsonb_ops、jsonb_path_ops）、表达式 B-tree",
    equivalent_claims: [
      { evidence_key: "ev-pg-1", claim_text: "jsonb 支持 GIN 索引，提供 jsonb_ops 与 jsonb_path_ops 两种操作符类", source_domain: "postgresql.org" },
      { evidence_key: "ev-pg-2", claim_text: "可以对 jsonb 字段建 GIN 索引，或对提取出的值建表达式索引", source_domain: "crunchydata.com" }
    ],
    independent_source_count: 2, confidence_ppm: 910000, conflicts: [], evidence_level: "VERIFIED"
  },
  {
    cell_key: "entity-postgresql:dim-operators", entity_label: "PostgreSQL 17", column_label: "可加速的查询运算符",
    cell_status: "CANDIDATE_READY", candidate_value: "@>、?、?|、?&、@?、@@",
    equivalent_claims: [
      { evidence_key: "ev-pg-3", claim_text: "jsonb_ops 支持 @>、?、?|、?&、@?、@@ 运算符", source_domain: "postgresql.org" }
    ],
    independent_source_count: 1, confidence_ppm: 720000, conflicts: []
  },
  {
    cell_key: "entity-mysql:dim-index-types", entity_label: "MySQL 8.4", column_label: "JSON 索引类型",
    cell_status: "CONFLICTED", candidate_value: "多值索引、生成列上的 B-tree 索引",
    equivalent_claims: [
      { evidence_key: "ev-my-1", claim_text: "可以在 JSON 数组上建多值索引", source_domain: "dev.mysql.com" }
    ],
    independent_source_count: 1, confidence_ppm: 640000,
    conflicts: [{ kind: "SEMANTIC_CONTRADICTION", other_value: "JSON 列不能建任何索引，只能借助生成列", status: "OPEN" }]
  },
  {
    cell_key: "entity-mysql:dim-operators", entity_label: "MySQL 8.4", column_label: "可加速的查询运算符",
    cell_status: "GAP", candidate_value: null, equivalent_claims: [], independent_source_count: 0, confidence_ppm: null, conflicts: [],
    evidence_level: "SOURCED", lead_value: "JSON_CONTAINS() 与 MEMBER OF() 可以用上多值索引",
    lead_quotes: [{
      evidence_key: "ev-my-lead", quote_text: "Multi-valued indexes can be used with MEMBER OF(), JSON_CONTAINS() and JSON_OVERLAPS().",
      source_title: "MySQL 多值索引实践（第三方博客）", source_url: "https://example.com/mysql-multi-valued-index"
    }]
  }
];

const replanFixtures: ResearchReplan[] = [
  {
    plan_revision_from: 1, plan_revision_to: 2, deviation_type: "EVIDENCE_CONFLICT", repair_cause: "EVIDENCE_CONFLICT",
    observed_facts: "两个来源对 MySQL JSON 列能否直接建索引的说法相反",
    affected_cells: ["MySQL 8.4 · JSON 索引类型"], strategy: "COUNTER_EVIDENCE", query_count: 3, created_at: "2026-10-10T08:12:00Z"
  },
  {
    plan_revision_from: 2, plan_revision_to: 3, deviation_type: "MARGINAL_GAIN_EXHAUSTED", repair_cause: "MARGINAL_GAIN_EXHAUSTED",
    observed_facts: "连续两轮检索没有找到 MySQL 运算符相关的新证据",
    affected_cells: ["MySQL 8.4 · 可加速的查询运算符"], strategy: "SWITCH_LANGUAGE", query_count: 2, created_at: "2026-10-10T08:15:00Z"
  }
];

const counterCheckFixtures: ResearchCounterCheck[] = [
  {
    cell_key: "entity-mysql:dim-index-types", entity_label: "MySQL 8.4", column_label: "JSON 索引类型",
    claim_text: "JSON 列不能直接建索引", verdict: "NOT_REFUTED", counter_query_count: 2, refuting_evidence_keys: [],
    checked_at: "2026-10-10T08:13:00Z"
  },
  {
    cell_key: "entity-mysql:dim-index-types", entity_label: "MySQL 8.4", column_label: "JSON 索引类型",
    claim_text: "JSON 列不能建任何索引", verdict: "REFUTED", counter_query_count: 2, refuting_evidence_keys: ["ev-my-1"],
    checked_at: "2026-10-10T08:13:30Z"
  },
  {
    cell_key: "entity-postgresql:dim-index-types", entity_label: "PostgreSQL 17", column_label: "JSON 索引类型",
    claim_text: "jsonb_path_ops 只支持 @>、@?、@@", verdict: "NO_COUNTER_EVIDENCE_FOUND", counter_query_count: 1,
    refuting_evidence_keys: [], checked_at: "2026-10-10T08:14:00Z"
  }
];

const discoveryFixtures: ResearchDiscoveryProposal[] = [
  {
    proposal_type: "DIMENSION", label: "部分更新是否重写整行", rationale: "多篇对比文章把 JSON 部分更新的写放大作为选型因素",
    confidence_ppm: 780000, supporting_domain_count: 3, status: "ACCEPTED"
  },
  {
    proposal_type: "ENTITY", label: "MariaDB 11", rationale: "检索结果中频繁与 MySQL 一同出现",
    confidence_ppm: 410000, supporting_domain_count: 2, status: "SHADOW"
  },
  {
    proposal_type: "SEARCH_QUERY", label: "mysql multi-valued index json_contains explain", rationale: "补充运算符是否走索引的证据",
    confidence_ppm: 650000, supporting_domain_count: 1, status: "REJECTED"
  }
];

export const researchInsights = {
  cells(runId: string): ResearchCell[] {
    return runId === runningRun.research_run_id ? cellFixtures.filter((cell) => cell.cell_status !== "VERIFIED") : cellFixtures;
  },
  replans(runId: string): ResearchReplan[] {
    return runId === runningRun.research_run_id ? replanFixtures.slice(0, 1) : replanFixtures;
  },
  counterChecks(runId: string): ResearchCounterCheck[] {
    return runId === runningRun.research_run_id ? [] : counterCheckFixtures;
  },
  discoveryProposals(_runId: string): ResearchDiscoveryProposal[] {
    return discoveryFixtures;
  }
};
