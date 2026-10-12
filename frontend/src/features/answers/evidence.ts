import type { MemoryReference } from "../memory/model";

/** 回答证据清单中的一条证据，与后端 /answer-runs/{id}/evidence 的响应字段一致。 */
export type AnswerEvidence = {
  rank: number;
  evidence_id: string;
  kind: string;
  source_id: string | null;
  passage_id: string | null;
  knowledge_item_id: string | null;
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

export type AnswerEvidenceManifest = {
  run_id: string;
  evidence: AnswerEvidence[];
  retrieval_rewrite?: RetrievalRewrite | null;
  evidence_sufficiency?: EvidenceSufficiency | string | null;
  /** 这次回答实际编入的记忆（2C）。 */
  memory_references?: MemoryReference[] | null;
};

/** 面向用户展示的引用：标题与摘录来自引用文本，类型和位置来自证据清单。 */
export type CitationView = {
  index: number;
  title: string;
  quote: string;
  kindLabel: string;
  graphPath: string[];
  /** 片段来自 OCR 识别时的说明，文字层提取的片段为空。 */
  ocrNote: string;
  /** OCR 置信度低于阈值，需要提醒核对原文。 */
  ocrLowConfidence: boolean;
  location: string;
};

// 三种回答模式产出的证据 ID 前缀不同，用来区分证据来自哪条检索链路。
const KIND_BY_PREFIX: Array<[prefix: string, label: string]> = [
  ["passage:", "原文段落"],
  ["note-window:", "阅读窗口"],
  ["knowledge-version:", "Wiki 页面"]
];

export function evidenceKindLabel(evidence: Pick<AnswerEvidence, "evidence_id" | "kind">) {
  const match = KIND_BY_PREFIX.find(([prefix]) => evidence.evidence_id.startsWith(prefix));
  if (match) return match[1];
  return evidence.kind === "KNOWLEDGE_VERSION" ? "Wiki 页面" : "原文段落";
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
      location: readableLocation(matched?.location ?? null),
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
