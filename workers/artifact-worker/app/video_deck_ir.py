from __future__ import annotations

import hashlib
import json
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.video_knowledge_plan import VideoKnowledgePlanV1
from app.video_material_bundle import VideoMaterialBundleV1


class DeckRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DeckSlideV1(DeckRecord):
    sequence_no: int = Field(ge=1, le=32)
    node_id: str
    title: str = Field(min_length=1, max_length=160)
    frame_id: str
    file_id: str
    image_checksum_sha256: str
    at_ms: int = Field(ge=0)
    part: int = Field(ge=1)
    claims: list[str] = Field(default_factory=list, max_length=2)
    evidence_refs: list[str] = Field(default_factory=list, max_length=16)
    coverage_gaps: list[str] = Field(default_factory=list, max_length=20)


class VideoDeckIRV1(DeckRecord):
    schema_version: Literal["video-deck-ir-v1"] = "video-deck-ir-v1"
    language: Literal["zh-CN", "en", "zh-EN"]
    bundle_content_digest: str
    plan_content_digest: str
    bvid: str
    part: int
    title: str
    slides: list[DeckSlideV1] = Field(min_length=1, max_length=32)
    content_digest: str

    @model_validator(mode="after")
    def validate_shape(self) -> "VideoDeckIRV1":
        if [slide.sequence_no for slide in self.slides] != list(range(1, len(self.slides) + 1)) \
                or len({slide.frame_id for slide in self.slides}) != len(self.slides) \
                or self.content_digest != self.digest():
            raise ValueError("video deck IR has invalid order, duplicate frame, or digest")
        return self

    def digest(self) -> str:
        data = self.model_dump(mode="json", exclude={"content_digest"})
        return _digest(data)

    def verify_against(self, bundle: VideoMaterialBundleV1,
                       plan: VideoKnowledgePlanV1) -> None:
        if self != build_video_deck_ir(bundle, plan, self.language):
            raise ValueError("video deck IR does not match frozen picture and knowledge evidence")


def _digest(data: dict[str, object]) -> str:
    encoded = json.dumps(data, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def build_video_deck_ir(bundle: VideoMaterialBundleV1,
                        plan: VideoKnowledgePlanV1,
                        language: str = "zh-CN") -> VideoDeckIRV1:
    """Map each original frame to one ordered slide without inventing visual facts."""
    plan.verify_against_bundle(bundle)
    if language not in {"zh-CN", "en", "zh-EN"}:
        raise ValueError("unsupported video deck language")
    if not bundle.frames:
        raise ValueError("original-image deck requires frozen video frames")
    if not any(node.kind in {"CONCEPT", "EXAMPLE"} and any(
            claim.status == "EXTRACTED" for claim in node.claims) for node in plan.nodes):
        raise ValueError("original-image deck requires verified semantic claims")
    frames = {frame.frame_id: frame for frame in bundle.frames}
    pending: list[tuple[int, str, dict[str, object]]] = []
    for node in plan.nodes:
        claims = [claim for claim in node.claims if claim.status == "EXTRACTED"]
        selected = claims[:2]
        refs = list(dict.fromkeys(ref for claim in selected for ref in claim.evidence_refs))
        for frame_id in node.frame_ids:
            frame = frames[frame_id]
            gaps = list(dict.fromkeys(node.missing + (
                ["VISUAL_SEMANTICS_UNVERIFIED"] if node.kind == "EVIDENCE_WINDOW" else [])))
            pending.append((frame.at_ms, frame_id, {
                "node_id": node.node_id,
                "title": node.title,
                "frame_id": frame_id,
                "file_id": frame.file_id,
                "image_checksum_sha256": frame.checksum_sha256,
                "at_ms": frame.at_ms,
                "part": frame.part,
                "claims": [claim.text for claim in selected],
                "evidence_refs": refs,
                "coverage_gaps": gaps,
            }))
    pending.sort(key=lambda item: (item[0], item[1]))
    if len(pending) != len(bundle.frames):
        raise ValueError("video deck does not assign every frozen frame exactly once")
    slides = [DeckSlideV1(sequence_no=index, **fields)
              for index, (_, _, fields) in enumerate(pending, start=1)]
    data: dict[str, object] = {
        "schema_version": "video-deck-ir-v1",
        "language": language,
        "bundle_content_digest": bundle.content_digest(),
        "plan_content_digest": plan.content_digest(),
        "bvid": bundle.bvid,
        "part": bundle.part,
        "title": plan.nodes[0].title,
        "slides": [slide.model_dump(mode="json") for slide in slides],
    }
    data["content_digest"] = _digest(data)
    return VideoDeckIRV1.model_validate(data)
