import type { MemoryReference } from "../memory/model";

/** 后端冻结的引用协议标识；历史回答没有这个值，前端按旧协议解析。 */
export const CITATION_BINDING_PROTOCOL = "citation-binding-v1";

/** 回答证据清单中的一条证据，与后端 /answer-runs/{id}/evidence 的响应字段一致。 */
export type AnswerEvidence = {
  rank: number;
  evidence_id: string;
  kind: string;
  source_id: string | null;
  /** 证据对应的资料快照；资料重新解析后据此判断证据是否仍是当时那一份。 */
  source_snapshot_id?: string | null;
  passage_id: string | null;
  knowledge_item_id: string | null;
  /** Wiki 证据对应的知识版本 ID；点击时按这个版本展示，不按标题打开最新页。 */
  knowledge_version_id?: string | null;
  title: string;
  excerpt: string;
  location: string | null;
  character_cost: number;
  /** Wiki 模式通过图扩展得到的页面才有：种子页到该页经过的页面标题。 */
  graph_path?: string[] | null;
  /** 5D：片段文字来自 PDF 文字层还是 OCR 识别。 */
  text_origin?: "TEXT_LAYER" | "OCR" | string | null;
  /** OCR 识别的平均置信度，0 到 1。 */
  ocr_confidence?: number | null;
};

/** 多轮对话中检索实际使用的独立问题；首轮问题或模型不可用时 method 为 RULE。 */
export type RetrievalRewrite = {
  is_standalone: boolean;
  standalone_query: string;
  keyword_query: string;
  method: "LLM" | "RULE" | string;
  degradation_reasons: string[];
};

export type EvidenceSufficiency = "SUFFICIENT" | "PARTIAL" | "INSUFFICIENT" | "NOT_RUN";

/** 编号到证据身份与引用行的绑定；number 与正文里的 [n] 一一对应。 */
export type CitationBinding = {
  number: number;
  evidenceId: string;
  /** 这条证据对应的引用行 ID；Wiki 页面可以绑定多个来源引用。 */
  citationIds: string[];
};

/** 引用协议信息：新协议带允许集合与编号绑定，历史回答没有这份信息。 */
export type CitationProtocol = {
  /** 有值表示新协议（citation-binding-v1）；null 表示历史回答，按旧协议解析。 */
  protocolVersion: string | null;
  /** 本次回答允许引用的证据 ID；正文里不在集合内的编号只渲染成普通文字。 */
  allowedEvidenceIds: string[];
  citationBindings: CitationBinding[];
};

/** 打开 Wiki 证据对应的知识版本，避免按标题临时打开最新正文充当当时的证据。 */
export type WikiEvidenceVersionTarget = {
  itemId: string;
  versionId: string;
  title: string;
};

export type AnswerEvidenceManifest = {
  run_id: string;
  evidence: AnswerEvidence[];
  retrieval_rewrite?: RetrievalRewrite | null;
  evidence_sufficiency?: EvidenceSufficiency | string | null;
  /** 这次回答实际编入的记忆（2C）。 */
  memory_references?: MemoryReference[] | null;
  /** 冻结的引用协议；历史回答没有这个值，前端按旧协议解析。 */
  citation_protocol?: CitationProtocol | null;
};

/** 面向用户展示的引用：标题与摘录来自引用文本，类型和位置来自证据清单。 */
export type CitationView = {
  index: number;
  /** 新协议下这条来源对应的证据 ID；旧协议按标题解析时为空。 */
  evidenceId?: string;
  /** Wiki 证据对应的知识版本 ID；旧协议不推断版本，保持为空。 */
  knowledgeVersionId?: string | null;
  knowledgeItemId?: string | null;
  title: string;
  quote: string;
  kindLabel: string;
  graphPath: string[];
  /** 片段来自 OCR 识别时的说明，文字层提取的片段为空。 */
  ocrNote: string;
  /** OCR 置信度低于阈值，需要提醒核对原文。 */
  ocrLowConfidence: boolean;
  location: string;
  /** 研究报告引用的网页地址，可以直接打开原文。 */
  url: string;
};

// 各检索链路产出的证据 ID 前缀不同，用来区分证据来自哪条检索链路。
const KIND_BY_PREFIX: Array<[prefix: string, label: string]> = [
  ["passage:", "原文段落"],
  ["note-window:", "阅读窗口"],
  ["knowledge-version:", "Wiki 页面"],
  ["research-evidence:", "研究报告引用"]
];

export function evidenceKindLabel(evidence: Pick<AnswerEvidence, "evidence_id" | "kind">) {
  const match = KIND_BY_PREFIX.find(([prefix]) => evidence.evidence_id.startsWith(prefix));
  if (match) return match[1];
  if (evidence.kind === "RESEARCH_EVIDENCE") return "研究报告引用";
  return evidence.kind === "KNOWLEDGE_VERSION" ? "Wiki 页面" : "原文段落";
}

function webUrl(location: string | null) {
  return location && /^https?:\/\//i.test(location) ? location : "";
}

/** 引用文本的格式为 `标题 | 摘录`，旧数据可能只有标题。 */
export function parseCitationLine(line: string) {
  const cleaned = line.replace(/^\[\d{1,2}\]\s*/, "").trim();
  const separator = cleaned.indexOf(" | ");
  if (separator < 0) return { title: cleaned, quote: "" };
  return { title: cleaned.slice(0, separator).trim(), quote: cleaned.slice(separator + 3).trim() };
}

// 知识版本的定位信息只是内部 ID，对用户没有意义，不展示。
function readableLocation(location: string | null) {
  if (!location || location.startsWith("knowledge-version:")) return "";
  return location;
}

/**
 * 把引用与证据清单对应起来。引用顺序通常与证据排名一致，但不作保证，
 * 因此先按标题匹配，匹配不到再按排名兜底。
 */
export function buildCitationViews(citations: string[], manifest: AnswerEvidence[] | null): CitationView[] {
  const used = new Set<string>();
  return citations.map((line, position) => {
    const { title, quote } = parseCitationLine(line);
    const index = position + 1;
    const candidates = (manifest ?? []).filter((item) => !used.has(item.evidence_id));
    const matched = candidates.find((item) => item.title && title.startsWith(item.title))
      ?? candidates.find((item) => item.rank === index);
    if (matched) used.add(matched.evidence_id);
    return {
      index,
      title: title || matched?.title || `来源 ${index}`,
      quote: quote || matched?.excerpt || "",
      kindLabel: matched ? evidenceKindLabel(matched) : "",
      // 网页地址单独做成链接，不再重复显示成定位文字
      location: webUrl(matched?.location ?? null) ? "" : readableLocation(matched?.location ?? null),
      url: webUrl(matched?.location ?? null),
      graphPath: matched?.graph_path ?? [],
      ...describeOcr(matched)
    };
  });
}

/** 低于这个置信度的 OCR 片段提示用户核对原文。 */
export const OCR_LOW_CONFIDENCE = 0.8;

function describeOcr(evidence: AnswerEvidence | undefined): Pick<CitationView, "ocrNote" | "ocrLowConfidence"> {
  if (evidence?.text_origin !== "OCR") return { ocrNote: "", ocrLowConfidence: false };
  const confidence = evidence.ocr_confidence;
  if (typeof confidence !== "number" || !Number.isFinite(confidence)) {
    return { ocrNote: "文字由 OCR 识别", ocrLowConfidence: false };
  }
  return {
    ocrNote: `文字由 OCR 识别 · 置信度 ${Math.round(confidence * 100)}%`,
    ocrLowConfidence: confidence < OCR_LOW_CONFIDENCE
  };
}


/** 新协议下按编号绑定生成来源列表，不按标题或排名猜对应关系。 */
export function buildBoundCitationViews(
  protocol: CitationProtocol,
  manifest: AnswerEvidence[] | null
): CitationView[] {
  const byEvidenceId = new Map((manifest ?? []).map((item) => [item.evidence_id, item]));
  return protocol.citationBindings.map((binding) => {
    const matched = byEvidenceId.get(binding.evidenceId);
    const index = binding.number;
    return {
      index,
      evidenceId: binding.evidenceId,
      knowledgeVersionId: matched?.knowledge_version_id ?? null,
      knowledgeItemId: matched?.knowledge_item_id ?? null,
      title: matched?.title || `来源 ${index}`,
      quote: matched?.excerpt || "",
      kindLabel: matched ? evidenceKindLabel(matched) : "",
      location: webUrl(matched?.location ?? null) ? "" : readableLocation(matched?.location ?? null),
      url: webUrl(matched?.location ?? null),
      graphPath: matched?.graph_path ?? [],
      ...describeOcr(matched)
    };
  });
}

/**
 * 新协议下正文里允许出角标的编号。绑定证据不在白名单时同样不给角标，
 * 避免把越界的编号渲染成看起来有效的来源按钮。
 */
export function allowedCitationNumbers(protocol: CitationProtocol): number[] {
  const allowed = new Set(protocol.allowedEvidenceIds);
  return protocol.citationBindings
    .filter((binding) => allowed.has(binding.evidenceId))
    .map((binding) => binding.number);
}

/** 解析证据清单响应；引用协议单独归一化，缺字段的绑定直接丢掉。 */
export function parseAnswerEvidenceManifest(raw: unknown): AnswerEvidenceManifest {
  const record = asRecord(raw);
  return {
    run_id: text(record.run_id ?? record.runId),
    evidence: Array.isArray(record.evidence) ? record.evidence as AnswerEvidence[] : [],
    retrieval_rewrite: (record.retrieval_rewrite ?? record.retrievalRewrite ?? null) as RetrievalRewrite | null,
    evidence_sufficiency: record.evidence_sufficiency == null && record.evidenceSufficiency == null
      ? null
      : text(record.evidence_sufficiency ?? record.evidenceSufficiency),
    memory_references: Array.isArray(record.memory_references)
      ? record.memory_references as MemoryReference[]
      : [],
    citation_protocol: parseCitationProtocol(record)
  };
}

/** 读出引用协议；没有 protocol_version 表示历史回答，保持旧协议解析。 */
export function parseCitationProtocol(raw: unknown): CitationProtocol | null {
  const record = asRecord(raw);
  const protocolVersion = text(record.protocol_version ?? record.protocolVersion);
  if (!protocolVersion) return null;
  return {
    protocolVersion,
    allowedEvidenceIds: stringList(record.allowed_evidence_ids ?? record.allowedEvidenceIds),
    citationBindings: parseCitationBindings(record.citation_bindings ?? record.citationBindings)
  };
}

/** 流式 evidence.ready 事件的正文就是协议本身；解析失败按未知处理。 */
export function parseCitationProtocolEvent(data: string): CitationProtocol | null {
  if (!data.trim()) return null;
  try {
    return parseCitationProtocol(JSON.parse(data));
  } catch {
    return null;
  }
}

function parseCitationBindings(raw: unknown): CitationBinding[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<number>();
  const bindings: CitationBinding[] = [];
  for (const value of raw) {
    const record = asRecord(value);
    const number = Number(record.number);
    const evidenceId = text(record.evidence_id ?? record.evidenceId);
    // 没有编号或证据身份的绑定不可用，丢掉，避免渲染出点不开的角标
    if (!Number.isInteger(number) || number <= 0 || !evidenceId || seen.has(number)) continue;
    seen.add(number);
    bindings.push({
      number,
      evidenceId,
      citationIds: stringList(record.citation_ids ?? record.citationIds)
    });
  }
  return bindings.sort((left, right) => left.number - right.number);
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => text(item)).filter(Boolean) : [];
}

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function text(value: unknown) {
  return value == null ? "" : String(value);
}