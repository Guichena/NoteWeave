import { describe, expect, it } from "vitest";
import {
  allowedCitationNumbers,
  buildBoundCitationViews,
  buildCitationViews,
  evidenceKindLabel,
  parseAnswerEvidenceManifest,
  parseCitationLine,
  parseCitationProtocol,
  parseCitationProtocolEvent,
  type AnswerEvidence
} from "./evidence";

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

describe("parseCitationProtocol", () => {
  it("没有明确协议时保持 UNKNOWN，不猜测历史", () => {
    expect(parseCitationProtocol({ run_id: "run-1", evidence: [] })).toMatchObject({ state: "UNKNOWN" });
    expect(parseCitationProtocol({ protocol_version: "" })).toMatchObject({ state: "INVALID" });
  });

  it("解析新协议的允许集合与编号绑定，并按下划线字段名读取", () => {
    const protocol = parseCitationProtocol({
      protocol_version: "citation-binding-v1",
      allowed_evidence_ids: ["knowledge-version:v2", "passage:c1"],
      citation_bindings: [
        { number: 2, evidence_id: "passage:c1", citation_ids: ["citation-b"] },
        { number: 1, evidence_id: "knowledge-version:v2", citation_ids: ["citation-a1", "citation-a2"] }
      ]
    });

    expect(protocol).toEqual({
      state: "BINDING_V1" as const,
      protocolVersion: "citation-binding-v1",
      allowedEvidenceIds: ["knowledge-version:v2", "passage:c1"],
      citationBindings: [
        { number: 1, evidenceId: "knowledge-version:v2", citationIds: ["citation-a1", "citation-a2"] },
        { number: 2, evidenceId: "passage:c1", citationIds: ["citation-b"] }
      ]
    });
  });

  it("缺编号或重复编号使整个协议 INVALID，不能挑合法行继续显示", () => {
    const protocol = parseCitationProtocol({
      protocol_version: "citation-binding-v1",
      citation_bindings: [
        { number: 1, evidence_id: "passage:c1" },
        { number: 1, evidence_id: "passage:dup" },
        { number: 0, evidence_id: "passage:zero" },
        { evidence_id: "passage:no-number" },
        { number: 3 }
      ]
    });

    expect(protocol).toMatchObject({ state: "INVALID", citationBindings: [] });
  });

  it("流式 evidence.ready 事件的正文就是协议本身，解析失败标为 INVALID", () => {
    const protocol = parseCitationProtocolEvent(JSON.stringify({
      protocol_version: "citation-binding-v1",
      allowed_evidence_ids: ["passage:c1"],
      citation_bindings: [{ number: 1, evidence_id: "passage:c1", citation_ids: [] }]
    }));

    expect(protocol?.protocolVersion).toBe("citation-binding-v1");
    expect(protocol?.citationBindings).toHaveLength(1);
    expect(parseCitationProtocolEvent("not-json")).toMatchObject({ state: "INVALID" });
    expect(parseCitationProtocolEvent("")).toMatchObject({ state: "INVALID" });
  });
});

describe("parseAnswerEvidenceManifest", () => {
  it("补齐后端已有的快照与知识版本字段，并保留未带协议的旧回答", () => {
    const manifest = parseAnswerEvidenceManifest({
      run_id: "run-1",
      evidence: [{
        rank: 1, evidence_id: "knowledge-version:v2", kind: "KNOWLEDGE_VERSION",
        source_id: null, source_snapshot_id: null, passage_id: null,
        knowledge_item_id: "k1", knowledge_version_id: "v2",
        title: "RRF 融合排序", excerpt: "按名次融合", location: null, character_cost: 5
      }],
      evidence_sufficiency: "SUFFICIENT"
    });

    expect(manifest.evidence[0]).toMatchObject({
      source_snapshot_id: null,
      knowledge_version_id: "v2"
    });
    expect(manifest.citation_protocol).toMatchObject({ state: "UNKNOWN" });
    expect(manifest.evidence_sufficiency).toBe("SUFFICIENT");
  });
});

describe("buildBoundCitationViews", () => {
  it("按编号绑定取证据，不按标题或排名猜，并带上数据版本身份", () => {
    const protocol = {
      state: "BINDING_V1" as const,
      protocolVersion: "citation-binding-v1",
      allowedEvidenceIds: ["knowledge-version:v2", "passage:c1"],
      citationBindings: [
        { number: 1, evidenceId: "knowledge-version:v2", citationIds: ["citation-a"] },
        { number: 2, evidenceId: "passage:c1", citationIds: ["citation-b"] }
      ]
    };
    const manifest: AnswerEvidence[] = [
      { rank: 1, evidence_id: "passage:c1", kind: "PASSAGE", source_id: "s1", passage_id: "c1",
        knowledge_item_id: null, title: "标题不同的段落", excerpt: "段落摘录", location: "第 3 页", character_cost: 10 },
      { rank: 2, evidence_id: "knowledge-version:v2", kind: "KNOWLEDGE_VERSION", source_id: null, passage_id: null,
        knowledge_item_id: "k1", knowledge_version_id: "v2", title: "RRF 融合排序", excerpt: "按名次融合",
        location: "knowledge-version:v2", character_cost: 5 }
    ];

    const views = buildBoundCitationViews(protocol, manifest);

    // 编号 1 绑定的是 Wiki 版本证据，排名 1 的段落不参与
    expect(views[0]).toMatchObject({
      index: 1, evidenceId: "knowledge-version:v2", knowledgeVersionId: "v2", knowledgeItemId: "k1",
      title: "RRF 融合排序", kindLabel: "Wiki 页面", location: ""
    });
    expect(views[1]).toMatchObject({
      index: 2, evidenceId: "passage:c1", knowledgeVersionId: null, title: "标题不同的段落", location: "第 3 页"
    });
  });

  it("证据清单还没到手时按绑定占位，不退回标题匹配", () => {
    const views = buildBoundCitationViews({
      state: "BINDING_V1" as const,
      protocolVersion: "citation-binding-v1",
      allowedEvidenceIds: ["passage:c1"],
      citationBindings: [{ number: 4, evidenceId: "passage:c1", citationIds: [] }]
    }, null);

    expect(views).toHaveLength(1);
    expect(views[0]).toMatchObject({ index: 4, evidenceId: "passage:c1", title: "来源 4", quote: "" });
  });
});

describe("allowedCitationNumbers", () => {
  it("只放行同时在绑定与允许集合里的编号", () => {
    expect(allowedCitationNumbers({
      state: "BINDING_V1" as const,
      protocolVersion: "citation-binding-v1",
      allowedEvidenceIds: ["passage:c1"],
      citationBindings: [
        { number: 1, evidenceId: "passage:c1", citationIds: [] },
        { number: 2, evidenceId: "passage:revoked", citationIds: [] }
      ]
    })).toEqual([1]);
  });
});

describe("F1 协议状态反例", () => {
  const ready = { protocol_state: "BINDING_V1", protocol_version: "citation-binding-v1", allowed_evidence_ids: ["p:1"], citation_bindings: [{ number: 1, evidence_id: "p:1", citation_ids: [] }] };
  it("缺失不是历史，未知版本与损坏绑定不是 v1", () => {
    expect(parseCitationProtocol({})).toMatchObject({ state: "UNKNOWN" });
    expect(parseCitationProtocol({ protocol_state: "LEGACY" })).toMatchObject({ state: "LEGACY" });
    expect(parseCitationProtocol({ ...ready, protocol_version: "future-v2" })).toMatchObject({ state: "INVALID" });
    expect(parseCitationProtocol({ ...ready, citation_bindings: [...ready.citation_bindings, ...ready.citation_bindings] })).toMatchObject({ state: "INVALID" });
    expect(parseCitationProtocolEvent("bad-json")).toMatchObject({ state: "INVALID" });
  });
  it("manifest 使用与事件相同的嵌套冻结对象", () => {
    expect(parseAnswerEvidenceManifest({ run_id: "run", citation_protocol: ready }).citation_protocol).toMatchObject({ state: "BINDING_V1", citationBindings: [{ number: 1, evidenceId: "p:1", citationIds: [] }] });
  });
});
