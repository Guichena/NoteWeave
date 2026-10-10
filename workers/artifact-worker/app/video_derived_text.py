from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.video_knowledge_plan import VideoKnowledgePlanV1
from app.video_material_bundle import VideoMaterialBundleV1


class DerivedRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EvidenceClaim(DerivedRecord):
    text: str = Field(min_length=1, max_length=1_000)
    evidence_refs: list[str] = Field(min_length=1, max_length=8)


class BlogSection(DerivedRecord):
    node_id: str
    heading: str
    claims: list[EvidenceClaim] = Field(min_length=1)
    gaps: list[str] = Field(default_factory=list)


class InterviewQuestion(DerivedRecord):
    node_id: str
    question: str
    short_answer: str
    detailed_answer: list[EvidenceClaim] = Field(min_length=1)
    related_knowledge: list[str] = Field(default_factory=list)
    gaps: list[str] = Field(default_factory=list)


class VideoDerivedTextV1(DerivedRecord):
    schema_version: Literal["video-derived-text-v1"] = "video-derived-text-v1"
    artifact_type: Literal["knowledge_blog", "interview_qa"]
    language: Literal["zh-CN", "en", "zh-EN"]
    bundle_content_digest: str
    plan_content_digest: str
    title: str
    terms: list[str]
    blog_sections: list[BlogSection] = Field(default_factory=list)
    interview_questions: list[InterviewQuestion] = Field(default_factory=list)
    markdown_sha256: str
    content_digest: str

    @model_validator(mode="after")
    def validate_shape_and_digest(self) -> "VideoDerivedTextV1":
        if self.artifact_type == "knowledge_blog":
            valid = bool(self.blog_sections) and not self.interview_questions
        else:
            valid = bool(self.interview_questions) and not self.blog_sections
        if not valid or len(self.terms) != len(set(self.terms)):
            raise ValueError("derived text has an invalid artifact shape")
        if self.content_digest != self.digest():
            raise ValueError("derived text content digest mismatch")
        return self

    def digest(self) -> str:
        return _digest(self.model_dump(mode="json", exclude={"content_digest"}))

    def verify_against(self, bundle: VideoMaterialBundleV1,
                       plan: VideoKnowledgePlanV1, markdown: str) -> None:
        plan.verify_against_bundle(bundle)
        if self.bundle_content_digest != bundle.content_digest() \
                or self.plan_content_digest != plan.content_digest() \
                or self.markdown_sha256 != hashlib.sha256(markdown.encode("utf-8")).hexdigest() \
                or markdown != render_derived_markdown(self):
            raise ValueError("derived text differs from frozen inputs or rendering")
        expected = derive_video_text(bundle, plan, self.artifact_type, self.language)
        if self != expected:
            raise ValueError("derived text contains claims outside the frozen plan")


def _digest(value: dict[str, object]) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


_LABELS = {
    "en": ("Source", "Knowledge plan", "Terms", "Evidence", "Coverage gap",
           "Short answer", "Detailed answer", "Related knowledge", "No verified terms."),
    "zh-CN": ("素材摘要", "知识规划摘要", "术语", "证据", "覆盖缺口",
              "简要回答", "详细问答", "相关知识", "暂无已核实术语。"),
    "zh-EN": ("素材摘要 / Source", "知识规划摘要 / Knowledge plan", "术语 / Terms",
              "证据 / Evidence", "覆盖缺口 / Coverage gap", "简要回答 / Short answer",
              "详细问答 / Detailed answer", "相关知识 / Related knowledge",
              "暂无已核实术语 / No verified terms."),
}


def _question(title: str, language: str) -> str:
    if language == "zh-CN":
        return f"资料如何解释{title}？"
    if language == "zh-EN":
        return f"资料如何解释{title}？ / What does the source say about {title}?"
    return f"What does the source say about {title}?"


def render_derived_markdown(ir: VideoDerivedTextV1) -> str:
    source, plan_label, terms_label, evidence, gap_label, short, detailed, related, no_terms = \
        _LABELS[ir.language]
    # 摘要只保留在结构化 IR 里（宿主据此校验），读者看到的正文不再展示两串哈希
    lines = [f"# {ir.title}", ""]
    if ir.terms:
        lines.extend([f"## {terms_label}", "", ", ".join(ir.terms), ""])
    if ir.artifact_type == "knowledge_blog":
        for section in ir.blog_sections:
            lines.extend([f"## {section.heading}", ""])
            for claim in section.claims:
                lines.extend([claim.text, f"{evidence}: {', '.join(claim.evidence_refs)}", ""])
            for gap in section.gaps:
                lines.extend([f"{gap_label}: {gap}", ""])
    else:
        for question in ir.interview_questions:
            lines.extend([f"## {question.question}", "", f"### {short}", "",
                          question.short_answer, "", f"### {detailed}", ""])
            for claim in question.detailed_answer:
                lines.extend([claim.text, f"{evidence}: {', '.join(claim.evidence_refs)}", ""])
            lines.extend([f"### {related}", "",
                          ", ".join(question.related_knowledge) or no_terms, ""])
            for gap in question.gaps:
                lines.extend([f"{gap_label}: {gap}", ""])
    return "\n".join(lines).rstrip() + "\n"


def derive_video_text(bundle: VideoMaterialBundleV1, plan: VideoKnowledgePlanV1,
                      artifact_type: Literal["knowledge_blog", "interview_qa"],
                      language: Literal["zh-CN", "en", "zh-EN"] = "en",
                      ) -> VideoDerivedTextV1:
    plan.verify_against_bundle(bundle)
    if artifact_type not in {"knowledge_blog", "interview_qa"}:
        raise ValueError("unsupported video derived text type")
    if language not in _LABELS:
        raise ValueError("unsupported video derived text language")
    semantic_nodes = [node for node in plan.nodes if node.kind in {"CONCEPT", "EXAMPLE"}
                      and any(claim.status == "EXTRACTED" for claim in node.claims)]
    if not semantic_nodes:
        raise ValueError("frozen knowledge plan has no verified semantic claims")
    terms = list(dict.fromkeys(term for node in semantic_nodes for term in node.terms))
    sections = []
    questions = []
    for node in semantic_nodes:
        claims = [EvidenceClaim(text=claim.text, evidence_refs=claim.evidence_refs)
                  for claim in node.claims if claim.status == "EXTRACTED"]
        gaps = list(dict.fromkeys(node.missing + ["UNVERIFIED_CLAIM"]
                                  * any(claim.status == "UNVERIFIED" for claim in node.claims)))
        if artifact_type == "knowledge_blog":
            sections.append(BlogSection(node_id=node.node_id, heading=node.title,
                                        claims=claims, gaps=gaps))
        else:
            questions.append(InterviewQuestion(
                node_id=node.node_id, question=_question(node.title, language),
                short_answer=claims[0].text, detailed_answer=claims,
                related_knowledge=node.terms, gaps=gaps))
    fields: dict[str, object] = {
        "artifact_type": artifact_type,
        "language": language,
        "bundle_content_digest": bundle.content_digest(),
        "plan_content_digest": plan.content_digest(),
        "title": plan.nodes[0].title,
        "terms": terms,
        "blog_sections": [item.model_dump(mode="json") for item in sections],
        "interview_questions": [item.model_dump(mode="json") for item in questions],
    }
    # Rendering does not depend on either digest, so construct once with placeholders.
    preview = VideoDerivedTextV1.model_construct(
        **{**fields, "blog_sections": sections, "interview_questions": questions},
        markdown_sha256="", content_digest="")
    fields["markdown_sha256"] = hashlib.sha256(
        render_derived_markdown(preview).encode("utf-8")).hexdigest()
    fields["content_digest"] = _digest({"schema_version": "video-derived-text-v1", **fields})
    return VideoDerivedTextV1.model_validate(fields)


def repair_video_derived_text(
    draft: dict[str, object], bundle: VideoMaterialBundleV1,
    plan: VideoKnowledgePlanV1, artifact_type: Literal["knowledge_blog", "interview_qa"],
    language: Literal["zh-CN", "en", "zh-EN"], *, max_repairs: int = 3,
) -> tuple[VideoDerivedTextV1, list[str]]:
    """Rebuild bounded invalid nodes from frozen evidence; never repair source identity."""
    expected = derive_video_text(bundle, plan, artifact_type, language)
    clean = expected.model_dump(mode="json")
    if not isinstance(draft, dict) or set(draft) != set(clean):
        raise ValueError("derived text repair cannot change the output schema")
    for key in ("schema_version", "artifact_type", "language", "bundle_content_digest",
                "plan_content_digest", "title"):
        if draft[key] != clean[key]:
            raise ValueError("derived text repair cannot change frozen identity")
    active = "blog_sections" if artifact_type == "knowledge_blog" else "interview_questions"
    inactive = "interview_questions" if artifact_type == "knowledge_blog" else "blog_sections"
    if draft[inactive] != [] or not isinstance(draft[active], list):
        raise ValueError("derived text repair cannot switch artifact structure")
    repaired = deepcopy(clean)
    actions: list[str] = []
    if draft["terms"] != clean["terms"]:
        actions.append("terms")
    raw_nodes = draft[active]
    node_ids = [item.get("node_id") if isinstance(item, dict) else None
                for item in raw_nodes]
    if any(not isinstance(node_id, str) for node_id in node_ids) \
            or len(node_ids) != len(set(node_ids)) or any(
            node_id not in {item["node_id"] for item in clean[active]}
            for node_id in node_ids):
        raise ValueError("derived text repair contains duplicate or foreign node IDs")
    by_id = {item["node_id"]: item for item in raw_nodes}
    for expected_node in clean[active]:
        node_id = expected_node["node_id"]
        if by_id.get(node_id) != expected_node:
            actions.append(f"node:{node_id}")
    if len(actions) > max_repairs:
        raise ValueError("derived text local repair limit exceeded")
    if not actions and (draft["markdown_sha256"] != clean["markdown_sha256"]
                        or draft["content_digest"] != clean["content_digest"]):
        actions.append("digest")
    # Deterministic output includes fresh Markdown and IR digests after local repair.
    return VideoDerivedTextV1.model_validate(repaired), actions
