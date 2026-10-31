import { describe, expect, it } from "vitest";
import { buildCitationViews, evidenceKindLabel, parseCitationLine, type AnswerEvidence } from "./evidence";

function evidence(overrides: Partial<AnswerEvidence>): AnswerEvidence {
  return {
    rank: 1,
    evidence_id: "passage:c1",
    kind: "PASSAGE",
    source_id: "s1",
    passage_id: "c1",
    knowledge_item_id: null,
    title: "标题",
    excerpt: "摘录",
    location: null,
    character_cost: 10,
    ...overrides
  };
}

describe("parseCitationLine", () => {
  it("拆分标题与摘录，并去掉编号前缀", () => {
    expect(parseCitationLine("[2] ES 文档 | kNN with RRF")).toEqual({ title: "ES 文档", quote: "kNN with RRF" });
    expect(parseCitationLine("只有标题")).toEqual({ title: "只有标题", quote: "" });
  });
});

describe("evidenceKindLabel", () => {
  it("按证据 ID 区分三条检索链路", () => {
    expect(evidenceKindLabel({ evidence_id: "passage:c1", kind: "PASSAGE" })).toBe("原文段落");
    expect(evidenceKindLabel({ evidence_id: "note-window:c1:3", kind: "PASSAGE" })).toBe("阅读窗口");
    expect(evidenceKindLabel({ evidence_id: "knowledge-version:v1", kind: "KNOWLEDGE_VERSION" })).toBe("Wiki 页面");
    expect(evidenceKindLabel({ evidence_id: "research-evidence:r1:abc", kind: "RESEARCH_EVIDENCE" })).toBe("研究报告引用");
  });
});

describe("buildCitationViews", () => {
  it("优先按标题匹配证据，匹配不到时按排名兜底", () => {
    const manifest = [
      evidence({ rank: 1, evidence_id: "knowledge-version:v1", kind: "KNOWLEDGE_VERSION", title: "B 页面", location: "knowledge-version:v1" }),
      evidence({ rank: 2, evidence_id: "note-window:c2:4", title: "改名前的标题", location: "第 3 页" })
    ];

    const views = buildCitationViews(["B 页面 | 页面摘录", "新标题 | 其他摘录"], manifest);

    expect(views[0]).toMatchObject({ index: 1, title: "B 页面", kindLabel: "Wiki 页面", location: "" });
    expect(views[1]).toMatchObject({ index: 2, title: "新标题", quote: "其他摘录", kindLabel: "阅读窗口", location: "第 3 页" });
  });

  it("没有证据清单时只展示引用文本", () => {
    expect(buildCitationViews(["A 文档 | 摘录"], null)).toEqual([
      { index: 1, title: "A 文档", quote: "摘录", kindLabel: "", location: "", url: "", graphPath: [], ocrNote: "", ocrLowConfidence: false }
    ]);
  });

  it("研究报告引用的网页地址做成链接，不再当定位文字重复显示", () => {
    const views = buildCitationViews(["Kafka 文档 | Kafka only orders within a partition."], [
      evidence({ evidence_id: "research-evidence:r1:abc", kind: "RESEARCH_EVIDENCE", source_id: null,
        title: "Kafka 文档", location: "https://kafka.apache.org/documentation/#semantics" })
    ]);

    expect(views[0]).toMatchObject({
      kindLabel: "研究报告引用", location: "", url: "https://kafka.apache.org/documentation/#semantics"
    });
  });

  it("OCR 片段标注置信度，低于阈值时要求核对原文", () => {
    const base = { kind: "PASSAGE", source_id: "s", passage_id: "p", knowledge_item_id: null, excerpt: "", location: null, character_cost: 1 };
    const views = buildCitationViews(["扫描件 | 甲", "扫描件 | 乙", "文字版 | 丙"], [
      { ...base, rank: 1, evidence_id: "passage:1", title: "扫描件", text_origin: "OCR", ocr_confidence: 0.93 },
      { ...base, rank: 2, evidence_id: "passage:2", title: "扫描件", text_origin: "OCR", ocr_confidence: 0.61 },
      { ...base, rank: 3, evidence_id: "passage:3", title: "文字版", text_origin: "TEXT_LAYER" }
    ]);

    expect(views[0]).toMatchObject({ ocrNote: "文字由 OCR 识别 · 置信度 93%", ocrLowConfidence: false });
    expect(views[1]).toMatchObject({ ocrNote: "文字由 OCR 识别 · 置信度 61%", ocrLowConfidence: true });
    expect(views[2]).toMatchObject({ ocrNote: "", ocrLowConfidence: false });
  });
});
