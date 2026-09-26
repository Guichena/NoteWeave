from __future__ import annotations

import hashlib
import json
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
        expected = derive_video_text(bundle, plan, self.artifact_type)
        if self != expected:
            raise ValueError("derived text contains claims outside the frozen plan")


def _digest(value: dict[str, object]) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def render_derived_markdown(ir: VideoDerivedTextV1) -> str:
    lines = [f"# {ir.title}", "", f"Source: {ir.bundle_content_digest}",
             f"Knowledge plan: {ir.plan_content_digest}", ""]
    if ir.terms:
        lines.extend(["## Terms", "", ", ".join(ir.terms), ""])
    if ir.artifact_type == "knowledge_blog":
        for section in ir.blog_sections:
            lines.extend([f"## {section.heading}", ""])
            for claim in section.claims:
                lines.extend([claim.text, f"Evidence: {', '.join(claim.evidence_refs)}", ""])
            for gap in section.gaps:
                lines.extend([f"Coverage gap: {gap}", ""])
    else:
        for question in ir.interview_questions:
            lines.extend([f"## {question.question}", "", "### Short answer", "",
                          question.short_answer, "", "### Detailed answer", ""])
            for claim in question.detailed_answer:
                lines.extend([claim.text, f"Evidence: {', '.join(claim.evidence_refs)}", ""])
            lines.extend(["### Related knowledge", "",
                          ", ".join(question.related_knowledge) or "No verified terms.", ""])
            for gap in question.gaps:
                lines.extend([f"Coverage gap: {gap}", ""])
    return "\n".join(lines).rstrip() + "\n"


def derive_video_text(bundle: VideoMaterialBundleV1, plan: VideoKnowledgePlanV1,
                      artifact_type: Literal["knowledge_blog", "interview_qa"]
                      ) -> VideoDerivedTextV1:
    plan.verify_against_bundle(bundle)
    if artifact_type not in {"knowledge_blog", "interview_qa"}:
        raise ValueError("unsupported video derived text type")
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
                node_id=node.node_id, question=f"What does the source say about {node.title}?",
                short_answer=claims[0].text, detailed_answer=claims,
                related_knowledge=node.terms, gaps=gaps))
    fields: dict[str, object] = {
        "artifact_type": artifact_type,
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
