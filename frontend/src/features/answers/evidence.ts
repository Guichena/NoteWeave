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
};

export type AnswerEvidenceManifest = {
  run_id: string;
  evidence: AnswerEvidence[];
};

/** 面向用户展示的引用：标题与摘录来自引用文本，类型和位置来自证据清单。 */
export type CitationView = {
  index: number;
  title: string;
  quote: string;
  kindLabel: string;
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
      location: readableLocation(matched?.location ?? null)
    };
  });
}
