from __future__ import annotations

import hashlib
import json
import re

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.io_limits import validate_json_payload_size
from app.llm_client import LlmClient
from app.video_material_bundle import VideoMaterialBundleV1


SHA256 = re.compile(r"[0-9a-f]{64}")


def _clock(ms: int) -> str:
    seconds = max(0, int(ms)) // 1000
    return f"{seconds // 60:02d}:{seconds % 60:02d}"


class PlanRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")


class KnowledgeClaim(PlanRecord):
    text: str = Field(min_length=1, max_length=1_000)
    status: str
    evidence_refs: list[str] = Field(default_factory=list, max_length=8)

    @model_validator(mode="after")
    def validate_status(self) -> "KnowledgeClaim":
        if self.status not in {"EXTRACTED", "UNVERIFIED"} \
                or (self.status == "EXTRACTED") != bool(self.evidence_refs):
            raise ValueError("knowledge claim must cite evidence or be unverified")
        return self


class KnowledgePlanNode(PlanRecord):
    node_id: str = Field(min_length=1, max_length=100)
    parent_id: str = ""
    kind: str
    title: str = Field(min_length=1, max_length=160)
    start_ms: int = Field(ge=0)
    end_ms: int = Field(gt=0)
    transcript_segment_ids: list[str] = Field(default_factory=list, max_length=128)
    frame_ids: list[str] = Field(default_factory=list, max_length=32)
    terms: list[str] = Field(default_factory=list, max_length=20)
    claims: list[KnowledgeClaim] = Field(default_factory=list, max_length=10)
    missing: list[str] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def validate_node(self) -> "KnowledgePlanNode":
        if self.kind not in {"TOPIC", "CONCEPT", "EXAMPLE", "EVIDENCE_WINDOW"} \
                or self.start_ms >= self.end_ms \
                or len(self.transcript_segment_ids) != len(set(self.transcript_segment_ids)) \
                or len(self.frame_ids) != len(set(self.frame_ids)) \
                or any(not term.strip() or len(term) > 100 for term in self.terms) \
                or len(self.terms) != len(set(self.terms)):
            raise ValueError("knowledge plan node has invalid structure")
        return self


class VideoKnowledgePlanV1(PlanRecord):
    schema_version: str = "video-knowledge-plan-v1"
    bundle_content_digest: str
    bvid: str
    part: int = Field(ge=1, le=1_000)
    duration_ms: int = Field(gt=0)
    nodes: list[KnowledgePlanNode] = Field(min_length=1, max_length=128)

    @model_validator(mode="after")
    def validate_plan(self) -> "VideoKnowledgePlanV1":
        if self.schema_version != "video-knowledge-plan-v1" \
                or not SHA256.fullmatch(self.bundle_content_digest):
            raise ValueError("knowledge plan schema or Bundle digest is invalid")
        ids = [node.node_id for node in self.nodes]
        if len(ids) != len(set(ids)):
            raise ValueError("knowledge plan node IDs are duplicated")
        roots = [node for node in self.nodes if not node.parent_id]
        if len(roots) != 1 or roots[0].kind != "TOPIC":
            raise ValueError("knowledge plan needs exactly one topic root")
        nodes = {node.node_id: node for node in self.nodes}
        for node in self.nodes:
            if node.end_ms > self.duration_ms:
                raise ValueError("knowledge plan node exceeds video duration")
            seen = {node.node_id}
            parent_id = node.parent_id
            while parent_id:
                parent = nodes.get(parent_id)
                if parent is None or parent_id in seen \
                        or not parent.start_ms <= node.start_ms \
                        or not node.end_ms <= parent.end_ms:
                    raise ValueError("knowledge plan hierarchy is invalid")
                seen.add(parent_id)
                parent_id = parent.parent_id
        return self

    def verify_against_bundle(self, bundle: VideoMaterialBundleV1) -> None:
        if self.bundle_content_digest != bundle.content_digest() \
                or self.bvid != bundle.bvid or self.part != bundle.part \
                or self.duration_ms != bundle.duration_ms:
            raise ValueError("knowledge plan is not bound to the frozen Bundle")
        segments = {item.segment_id: item for item in bundle.transcript_segments}
        frames = {item.frame_id: item for item in bundle.frames}
        observations = {item.file_id: item for item in bundle.frame_observations or []}
        covered_segments: set[str] = set()
        assigned_frames: set[str] = set()
        for node in self.nodes:
            texts: dict[str, list[str]] = {}
            for segment_id in node.transcript_segment_ids:
                segment = segments.get(segment_id)
                if segment is None or segment.end_ms < node.start_ms \
                        or segment.start_ms > node.end_ms:
                    raise ValueError("knowledge plan cites a subtitle outside its time range")
                covered_segments.add(segment_id)
                texts[f"segment:{segment_id}"] = [segment.corrected_text]
            for frame_id in node.frame_ids:
                frame = frames.get(frame_id)
                if frame is None or not node.start_ms <= frame.at_ms <= node.end_ms \
                        or frame_id in assigned_frames:
                    raise ValueError("knowledge plan frame assignment is invalid")
                assigned_frames.add(frame_id)
                observation = observations.get(frame.file_id)
                if observation is not None:
                    texts[f"frame:{frame_id}"] = [
                        item.text for item in observation.observations]
            for term in node.terms:
                if not any(term.casefold() in piece.casefold()
                           for pieces in texts.values() for piece in pieces):
                    raise ValueError("knowledge plan term is absent from cited evidence")
            for claim in node.claims:
                if claim.status == "EXTRACTED":
                    if any(ref not in texts for ref in claim.evidence_refs) \
                            or not any(claim.text in piece for ref in claim.evidence_refs
                                       for piece in texts[ref]):
                        raise ValueError("knowledge plan claim is absent from cited evidence")
                if claim.status == "UNVERIFIED" and "UNVERIFIED_CLAIM" not in node.missing:
                    raise ValueError("unverified knowledge claim needs an explicit gap")
            if not node.transcript_segment_ids and not node.frame_ids \
                    and not node.missing and not any(
                        child.parent_id == node.node_id for child in self.nodes):
                raise ValueError("knowledge plan node has no evidence or child")
        if covered_segments != set(segments) or assigned_frames != set(frames):
            raise ValueError("knowledge plan does not cover every subtitle and frame")

    def content_digest(self) -> str:
        canonical = json.dumps(self.model_dump(mode="json"), ensure_ascii=False,
                               sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def plan_video_knowledge(
    bundle: VideoMaterialBundleV1, client: LlmClient,
) -> VideoKnowledgePlanV1:
    """Request a semantic hierarchy, then accept only claims present in frozen evidence.

    The plan is a separate immutable object keyed to the Bundle digest, so there is
    no circular digest dependency or change to historical Bundle v1 bytes.
    """
    payload: dict[str, object] = {
        "contract": "video-knowledge-outline-v1",
        "output_schema": {
            "title": "string, the video's main topic",
            "concepts": [{
                "title": "string, one knowledge point (max 60 chars)",
                "segment_ids": ["segment_id values of the subtitle segments explaining it"],
                "terms": ["key terms copied exactly from those segments"],
                "claims": [{"segment_id": "one of segment_ids",
                            "quote": "a sentence copied verbatim from that segment's corrected_text"}],
            }],
        },
        "rules": [
            "Return 3-12 concepts in video order; each concept cites consecutive subtitle segments.",
            "Every quote must be an exact substring of the cited segment's corrected_text.",
            "Use only the supplied corrected subtitles and frame OCR; never invent facts.",
            "Do not infer visual semantics from OCR text or the presence of a frame.",
        ],
        "bundle_content_digest": bundle.content_digest(),
        "bvid": bundle.bvid,
        "part": bundle.part,
        "duration_ms": bundle.duration_ms,
        "segments": [segment.model_dump(mode="json") for segment in bundle.transcript_segments],
        "frames": [frame.model_dump(mode="json") for frame in bundle.frames],
        "frame_observations": [item.model_dump(mode="json")
                               for item in bundle.frame_observations or []],
        "coverage_gaps": bundle.coverage_gaps,
    }
    validate_json_payload_size(payload, label="video knowledge planning evidence")
    raw = client.complete_json("video_knowledge_plan", payload)
    if not raw or len(raw.encode("utf-8")) > 1_000_000:
        raise ValueError("video knowledge planner returned no bounded plan")
    try:
        candidate = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError("video knowledge planner returned invalid JSON") from exc
    if isinstance(candidate, dict) and "concepts" in candidate and "nodes" not in candidate:
        plan = build_plan_from_outline(bundle, candidate)
    else:
        plan = VideoKnowledgePlanV1.model_validate(candidate)
    plan.verify_against_bundle(bundle)
    return plan


def build_plan_from_outline(bundle: VideoMaterialBundleV1, outline: dict[str, object]) -> VideoKnowledgePlanV1:
    """Turn the model's semantic outline into a contract-complete plan.

    The model only decides *meaning* (which segments form a concept, which sentences matter).
    Everything the contract checks mechanically is derived here from frozen evidence: node time
    ranges, full subtitle/frame coverage, and claims, which are kept only when the quote really
    occurs in the cited segment. Nothing the model wrote is trusted as a fact on its own.
    """
    segments = sorted(bundle.transcript_segments, key=lambda item: (item.start_ms, item.segment_id))
    by_id = {item.segment_id: item for item in segments}
    observations = {item.file_id: item for item in bundle.frame_observations or []}
    assigned: set[str] = set()
    nodes: list[dict[str, object]] = []
    raw_concepts = outline.get("concepts")
    for raw in (raw_concepts if isinstance(raw_concepts, list) else [])[:20]:
        if not isinstance(raw, dict):
            continue
        ids = [str(item) for item in raw.get("segment_ids") or [] if str(item) in by_id
               and str(item) not in assigned]
        ids = list(dict.fromkeys(ids))[:128]
        if not ids:
            continue
        cited = [by_id[item] for item in ids]
        start_ms = min(item.start_ms for item in cited)
        end_ms = max(item.end_ms for item in cited)
        texts = [item.corrected_text for item in cited]
        claims = []
        for claim in raw.get("claims") or []:
            if not isinstance(claim, dict) or len(claims) >= 10:
                continue
            segment_id, quote = str(claim.get("segment_id", "")), str(claim.get("quote", "")).strip()
            if segment_id in ids and quote and len(quote) <= 1_000 and quote in by_id[segment_id].corrected_text:
                claims.append({"text": quote, "status": "EXTRACTED",
                               "evidence_refs": [f"segment:{segment_id}"]})
        terms = list(dict.fromkeys(
            str(term).strip() for term in raw.get("terms") or []
            if str(term).strip() and len(str(term).strip()) <= 100
            and any(str(term).strip().casefold() in text.casefold() for text in texts)))[:20]
        title = str(raw.get("title") or "").strip()[:160] or cited[0].corrected_text[:40] or "Concept"
        assigned.update(ids)
        nodes.append({"node_id": f"concept-{len(nodes) + 1:03d}", "parent_id": "root", "kind": "CONCEPT",
                      "title": title, "start_ms": start_ms, "end_ms": end_ms,
                      "transcript_segment_ids": ids, "terms": terms, "claims": claims})
    # Segments the model did not place still have to be covered, as plain evidence windows.
    leftover = [item for item in segments if item.segment_id not in assigned]
    window: list = []
    for item in leftover + [None]:
        if item is not None and (not window or len(window) < 128):
            window.append(item)
            continue
        if window:
            nodes.append({"node_id": f"subtitle-window-{len(nodes) + 1:03d}", "parent_id": "root",
                          "kind": "EVIDENCE_WINDOW",
                          "title": f"字幕片段 {_clock(window[0].start_ms)}–{_clock(window[-1].end_ms)}",
                          "start_ms": min(seg.start_ms for seg in window),
                          "end_ms": max(seg.end_ms for seg in window),
                          "transcript_segment_ids": [seg.segment_id for seg in window]})
        window = [item] if item is not None else []
    # Frames join the first concept whose time range holds them; the rest get their own window.
    for frame in sorted(bundle.frames, key=lambda item: item.at_ms):
        host = next((node for node in nodes if node["kind"] == "CONCEPT"
                     and node["start_ms"] <= frame.at_ms <= node["end_ms"]
                     and len(node.setdefault("frame_ids", [])) < 32), None)
        if host is not None:
            host["frame_ids"].append(frame.frame_id)
            continue
        start = min(frame.at_ms, bundle.duration_ms - 1)
        nodes.append({"node_id": f"frame-window-{len(nodes) + 1:03d}", "parent_id": "root",
                      "kind": "EVIDENCE_WINDOW", "title": f"画面 · {_clock(frame.at_ms)}",
                      "start_ms": start, "end_ms": start + 1, "frame_ids": [frame.frame_id],
                      "missing": ["VISUAL_SEMANTICS_UNVERIFIED"]})
    if len(nodes) + 1 > 128:
        raise ValueError("video knowledge outline exceeds the plan node limit")
    topic = str(outline.get("title") or "").strip()[:160] or "Video knowledge"
    nodes.insert(0, {"node_id": "root", "parent_id": "", "kind": "TOPIC", "title": topic,
                     "start_ms": 0, "end_ms": bundle.duration_ms,
                     "missing": [] if nodes else list(bundle.coverage_gaps) or ["NO_EVIDENCE"]})
    if not any(node.get("claims") for node in nodes):
        raise ValueError("video knowledge outline produced no verifiable claim")
    return VideoKnowledgePlanV1.model_validate({
        "bundle_content_digest": bundle.content_digest(),
        "bvid": bundle.bvid, "part": bundle.part,
        "duration_ms": bundle.duration_ms, "nodes": nodes,
    })


def build_local_evidence_plan(bundle: VideoMaterialBundleV1) -> VideoKnowledgePlanV1:
    """Freeze a complete evidence index without interpreting subtitle or image meaning."""
    nodes: list[dict[str, object]] = [{
        "node_id": "source", "parent_id": "", "kind": "TOPIC",
        "title": "Video source evidence", "start_ms": 0, "end_ms": bundle.duration_ms,
        "missing": list(bundle.coverage_gaps) if not bundle.frames
        and not bundle.transcript_segments else [],
    }]
    for index, frame in enumerate(bundle.frames, start=1):
        nodes.append({
            "node_id": f"frame-window-{index:03d}", "parent_id": "source",
            "kind": "EVIDENCE_WINDOW", "title": f"画面 · {_clock(frame.at_ms)}",
            "start_ms": frame.at_ms,
            "end_ms": min(bundle.duration_ms, frame.at_ms + 1),
            "frame_ids": [frame.frame_id],
            "missing": ["VISUAL_SEMANTICS_UNVERIFIED"],
        })
    segments = bundle.transcript_segments
    for offset in range(0, len(segments), 128):
        group = segments[offset:offset + 128]
        nodes.append({
            "node_id": f"subtitle-window-{offset // 128 + 1:03d}",
            "parent_id": "source", "kind": "EVIDENCE_WINDOW",
            "title": f"字幕片段 {_clock(group[0].start_ms)}–{_clock(group[-1].end_ms)}",
            "start_ms": min(item.start_ms for item in group),
            "end_ms": max(item.end_ms for item in group),
            "transcript_segment_ids": [item.segment_id for item in group],
        })
    if len(nodes) > 128:
        raise ValueError("video evidence exceeds knowledge plan window limit")
    plan = VideoKnowledgePlanV1.model_validate({
        "bundle_content_digest": bundle.content_digest(),
        "bvid": bundle.bvid, "part": bundle.part,
        "duration_ms": bundle.duration_ms, "nodes": nodes,
    })
    plan.verify_against_bundle(bundle)
    return plan
