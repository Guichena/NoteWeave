import type {
  KnowledgeCitation,
  KnowledgeItemDetail,
  KnowledgeVersionSummary,
  WikiGraph,
  WikiHome,
  WikiIndex,
  WikiIssue,
  WikiLink,
  WikiPage,
  WikiRebuildAdvice,
  WikiStats
} from "../../features/knowledge/model";

// 知识库设计数据：以混合检索为中心的一组页面，包含页面互链、一条未解析链接和来源回链。

const now = Date.now();
const minutesAgo = (minutes: number) => new Date(now - minutes * 60_000).toISOString();

type PageSeed = {
  id: string;
  title: string;
  kind: string;
  version: number;
  summary: string;
  content: string;
  links: string[];
  citations: Array<[sourceId: string, title: string, quote: string, location: string]>;
  updated: number;
};

const seeds: PageSeed[] = [
  {
    id: "hybrid", title: "混合检索", kind: "CONCEPT", version: 3, updated: 35,
    summary: "同时使用关键词召回与语义召回，再融合排序。",
    content: "混合检索同时使用 [[BM25 关键词召回]] 与 [[向量语义召回]] 两路召回，两路结果经 [[RRF 融合排序]] 合并，再交给 [[Rerank 重排]] 精排。\n\n## 为什么需要两路召回\n\n关键词召回擅长精确术语与编号，语义召回擅长同义表达，两者互补。\n\n## 在本工作台的参数\n\n- 每路取前 50 条候选\n- 融合后保留前 80 条进入重排",
    links: ["BM25 关键词召回", "向量语义召回", "RRF 融合排序", "Rerank 重排"],
    citations: [["src-rag", "RAG 检索链路设计.md", "混合检索分两路召回：BM25 取前 50，向量取前 50；融合使用 RRF。", "第 3 节"]]
  },
  {
    id: "rrf", title: "RRF 融合排序", kind: "CONCEPT", version: 2, updated: 50,
    summary: "按名次融合多路结果，不依赖原始分数。",
    content: "RRF 只使用每路结果中的名次：score = Σ 1 / (k + rank)，k 通常取 60。\n\n由于不依赖分数分布，BM25 与向量两路可以直接融合，无需为每个数据集调权重。融合后的候选交给 [[Rerank 重排]]。\n\n后续可以结合 [[查询改写]] 提升召回。",
    links: ["Rerank 重排", "查询改写"],
    citations: [
      ["src-rag", "RAG 检索链路设计.md", "融合使用 RRF，score = Σ 1 / (60 + rank)。", "第 3 节"],
      ["src-bench", "向量数据库压测记录.pdf", "RRF 使用固定 k = 60，NDCG@10 与调参后的加权方案差异小于 1%。", "第 7 页"]
    ]
  },
  {
    id: "rerank", title: "Rerank 重排", kind: "CONCEPT", version: 1, updated: 70,
    summary: "用交叉编码模型对融合后的候选重新打分。",
    content: "Rerank 使用交叉编码模型对 [[RRF 融合排序]] 输出的前 80 条候选重新打分，再按字符预算截取证据进入回答。",
    links: ["RRF 融合排序"],
    citations: [["src-rag", "RAG 检索链路设计.md", "融合后的前 80 条候选交给 Rerank 重新打分，最终按字符预算截取证据。", "第 4 节"]]
  },
  {
    id: "bm25", title: "BM25 关键词召回", kind: "CONCEPT", version: 1, updated: 120,
    summary: "基于词频与文档长度的经典关键词检索。",
    content: "BM25 依赖分词质量，中文场景需要配合 IK 等分词插件。它是 [[混合检索]] 的关键词一路。",
    links: ["混合检索"],
    citations: [["src-ik", "Elasticsearch 中文分词实践.md", "中文检索使用 IK 分词，自定义词典支持热更新。", "第 2 节"]]
  },
  {
    id: "vector", title: "向量语义召回", kind: "CONCEPT", version: 2, updated: 95,
    summary: "把文本编码为向量，按语义相似度召回。",
    content: "向量召回使用 1024 维嵌入，适合处理同义表达。它是 [[混合检索]] 的语义一路。",
    links: ["混合检索"],
    citations: [["src-bench", "向量数据库压测记录.pdf", "嵌入维度 1024，HNSW 索引在百万级数据下 P95 延迟约 18 ms。", "第 4 页"]]
  },
  {
    id: "longdoc", title: "长文档窗口阅读", kind: "TOPIC", version: 1, updated: 26,
    summary: "精读模式按连续原文窗口组织长文档上下文。",
    content: "精读时先定位锚点窗口，再向前后各扩展一个窗口，保证论证完整。设计细节见 [[RAG 检索链路设计]]。",
    links: ["RAG 检索链路设计"],
    citations: [["src-rag", "RAG 检索链路设计.md", "命中锚点窗口后，向前后各扩展一个窗口，一起交给模型阅读。", "第 5 节"]]
  },
  {
    id: "engine", title: "检索引擎选型", kind: "COMPARISON", version: 1, updated: 18,
    summary: "Elasticsearch、Milvus 与 pgvector 的适用场景对比。",
    content: "三种方案都能支撑 [[混合检索]]，区别在于运维成本与扩展上限。",
    links: ["混合检索"],
    citations: [["src-bench", "向量数据库压测记录.pdf", "Milvus 分布式部署依赖 etcd、MinIO 与 Pulsar，运维成本最高。", "第 9 页"]]
  },
  {
    id: "rag-design", title: "RAG 检索链路设计", kind: "SOURCE_SUMMARY", version: 1, updated: 180,
    summary: "资料摘要：检索链路的整体设计。",
    content: "这份资料描述了 [[混合检索]] 的召回、融合与重排，以及 [[长文档窗口阅读]] 的窗口策略。",
    links: ["混合检索", "长文档窗口阅读"],
    citations: [["src-rag", "RAG 检索链路设计.md", "本文描述问答、精读与 Wiki 三条检索链路的设计。", "摘要"]]
  }
];

const idByTitle = new Map(seeds.map((seed) => [seed.title, seed.id] as const));

const allLinks: WikiLink[] = seeds.flatMap((seed) => seed.links.map((target) => ({
  source_item_id: seed.id,
  target_item_id: idByTitle.get(target) ?? null,
  target_title: target,
  relation_type: "WIKI_LINK",
  relation_status: idByTitle.has(target) ? "RESOLVED" : "UNRESOLVED",
  mention_count: 1
})));

function toPage(seed: PageSeed): WikiPage {
  return {
    item_id: seed.id,
    item_type: "WIKI",
    page_kind: seed.kind,
    title: seed.title,
    latest_version_id: `${seed.id}-v${seed.version}`,
    latest_version_no: seed.version,
    summary: seed.summary,
    updated_at: minutesAgo(seed.updated),
    outgoing_count: allLinks.filter((link) => link.source_item_id === seed.id).length,
    backlink_count: allLinks.filter((link) => link.target_item_id === seed.id).length,
    citation_count: seed.citations.length,
    unresolved_count: allLinks.filter((link) => link.source_item_id === seed.id && !link.target_item_id).length
  };
}

export const pages = seeds.map(toPage);

function citations(seed: PageSeed): KnowledgeCitation[] {
  return seed.citations.map(([sourceId, title, quote, location], index) => ({
    citation_id: `${seed.id}-c${index + 1}`,
    source_id: sourceId,
    title,
    quote_text: quote,
    page_no: null,
    location_info: location,
    generated_by: "",
    generated_ref_id: ""
  }));
}

export const itemDetails: Record<string, KnowledgeItemDetail> = Object.fromEntries(seeds.map((seed) => [seed.id, {
  ...toPage(seed),
  content: seed.content,
  source_message_id: null,
  citations: citations(seed),
  outgoing_links: allLinks.filter((link) => link.source_item_id === seed.id),
  backlinks: allLinks.filter((link) => link.target_item_id === seed.id),
  version_created_at: minutesAgo(seed.updated)
}]));

export const versionsByItem: Record<string, KnowledgeVersionSummary[]> = Object.fromEntries(seeds.map((seed) => [
  seed.id,
  Array.from({ length: seed.version }, (_, index) => ({
    version_id: `${seed.id}-v${seed.version - index}`,
    version_no: seed.version - index,
    summary: index === 0 ? seed.summary : "早期版本",
    source_message_id: null,
    citation_count: seed.citations.length,
    created_at: minutesAgo(seed.updated + index * 240)
  }))
]));

const issues: WikiIssue[] = [{
  item_id: "rrf",
  issue_type: "UNRESOLVED_LINK",
  severity: "MEDIUM",
  title: "RRF 融合排序",
  message: "链接的页面「查询改写」尚未创建",
  suggested_action: "创建页面或移除链接",
  auto_fixable: false,
  action_code: "CREATE_TARGET_PAGE"
}];

const resolved = allLinks.filter((link) => link.target_item_id).length;
const pagesByKind = pages.reduce<Record<string, number>>((counts, page) => {
  counts[page.page_kind] = (counts[page.page_kind] ?? 0) + 1;
  return counts;
}, {});
const recentUpdates = [...pages].sort((a, b) => b.updated_at.localeCompare(a.updated_at)).slice(0, 5);

export const home: WikiHome = { workspace_id: "", wiki_url: "", pages, links: allLinks };

export const stats: WikiStats = {
  page_count: pages.length,
  link_count: allLinks.length,
  resolved_link_count: resolved,
  unresolved_link_count: allLinks.length - resolved,
  citation_count: seeds.reduce((sum, seed) => sum + seed.citations.length, 0),
  issue_count: issues.length,
  auto_fixable_issue_count: 0,
  manual_review_issue_count: issues.length,
  pages_by_kind: pagesByKind,
  recent_updates: recentUpdates,
  recent_tasks: [],
  pending_task_count: 0,
  wiki_enabled: true
};

export const index: WikiIndex = {
  workspace_id: "",
  wiki_enabled: true,
  ready_source_count: 4,
  page_count: pages.length,
  source_backed_page_count: pages.length,
  manual_page_count: 0,
  link_count: allLinks.length,
  resolved_link_count: resolved,
  unresolved_link_count: allLinks.length - resolved,
  citation_count: stats.citation_count,
  issue_count: issues.length,
  auto_fixable_issue_count: 0,
  manual_review_issue_count: issues.length,
  pending_task_count: 0,
  pages_by_kind: pagesByKind,
  recent_updates: recentUpdates,
  recent_tasks: [],
  recent_sources: [],
  top_issues: issues
};

export const rebuildAdvice: WikiRebuildAdvice = {
  should_enable_wiki: false,
  ready_source_count: 4,
  active_wiki_page_count: pages.length,
  message: "知识库已与资料同步。",
  recommended_action: "",
  recommended_issue_type: "",
  focus_item_id: "",
  focus_title: ""
};

export const wikiIssues = issues;

export function graph(mode: string, center: string | null): WikiGraph {
  const nodeIds = mode === "ego" && center
    ? new Set([center, ...allLinks.filter((link) => link.source_item_id === center || link.target_item_id === center)
      .flatMap((link) => [link.source_item_id, link.target_item_id ?? ""]).filter(Boolean)])
    : new Set(seeds.map((seed) => seed.id));
  const edges = allLinks.filter((link) => nodeIds.has(link.source_item_id) && (!link.target_item_id || nodeIds.has(link.target_item_id)));
  return {
    nodes: pages.filter((page) => nodeIds.has(page.item_id)).map((page) => ({
      item_id: page.item_id,
      title: page.title,
      page_kind: page.page_kind,
      version_no: page.latest_version_no,
      degree: page.outgoing_count + page.backlink_count,
      outgoing_count: page.outgoing_count,
      backlink_count: page.backlink_count,
      citation_count: page.citation_count,
      unresolved_count: page.unresolved_count
    })),
    edges: edges.map((link) => ({
      source_item_id: link.source_item_id,
      source_title: seeds.find((seed) => seed.id === link.source_item_id)?.title ?? "",
      target_item_id: link.target_item_id,
      target_title: link.target_title,
      relation_type: link.relation_type,
      relation_status: link.relation_status,
      mention_count: link.mention_count
    })),
    meta: {
      mode: mode === "ego" ? "ego" : "overview",
      center_item_id: center ?? "",
      depth: 1,
      total_nodes: seeds.length,
      returned_nodes: nodeIds.size,
      truncated: false
    }
  };
}
