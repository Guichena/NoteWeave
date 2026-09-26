from __future__ import annotations

import hashlib
import json
import re
from io import BytesIO
from typing import Callable

from PIL import Image

from pydantic import BaseModel, ConfigDict, Field, model_validator


SHA256 = re.compile(r"^[0-9a-f]{64}$")
BVID = re.compile(r"^BV[0-9A-Za-z]{10}$")


def frozen_video_input_digest(input_snapshot_id: str, inputs: dict[str, object]) -> str:
    """Bind material to Host's immutable Run input snapshot and normalized inputs."""
    if not input_snapshot_id:
        raise ValueError("video material requires a frozen input snapshot")
    canonical = json.dumps(inputs, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(f"{input_snapshot_id}:{canonical}".encode("utf-8")).hexdigest()


class BundleRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MaterialFile(BundleRecord):
    file_id: str
    role: str
    media_type: str
    size_bytes: int = Field(gt=0)
    checksum_sha256: str


class TranscriptSegment(BundleRecord):
    segment_id: str
    part: int = Field(ge=1)
    start_ms: int = Field(ge=0)
    end_ms: int = Field(gt=0)
    original_text: str
    corrected_text: str


class VideoFrame(BundleRecord):
    frame_id: str
    part: int = Field(ge=1)
    at_ms: int = Field(ge=0)
    file_id: str
    checksum_sha256: str
    dedupe_of: str = ""


class KnowledgeNode(BundleRecord):
    node_id: str
    title: str
    start_ms: int = Field(ge=0)
    end_ms: int = Field(gt=0)
    transcript_segment_ids: list[str] = Field(default_factory=list)
    frame_ids: list[str] = Field(default_factory=list)
    missing: list[str] = Field(default_factory=list)


class FrameObservationText(BundleRecord):
    kind: str
    text: str
    confidence: float = Field(ge=0, le=100)
    uncertain: bool


class FrameObservation(BundleRecord):
    schema_version: str
    task_id: str
    file_id: str
    checksum_sha256: str
    media_type: str
    width: int = Field(ge=1)
    height: int = Field(ge=1)
    observations: list[FrameObservationText]
    coverage_gaps: list[str]


class VideoMaterialBundleV1(BundleRecord):
    schema_version: str = "video-material-v1"
    bundle_id: str
    bundle_version: int = Field(ge=1)
    workspace_id: str
    bvid: str
    part: int = Field(ge=1)
    duration_ms: int = Field(gt=0)
    input_digest: str
    subtitle_source: str
    transcript_original: str = ""
    transcript_corrected: str = ""
    transcript_segments: list[TranscriptSegment] = Field(default_factory=list)
    frames: list[VideoFrame] = Field(default_factory=list)
    knowledge_nodes: list[KnowledgeNode] = Field(default_factory=list)
    frame_observations: list[FrameObservation] | None = None
    files: list[MaterialFile] = Field(default_factory=list)
    coverage_gaps: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_references(self) -> "VideoMaterialBundleV1":
        if self.schema_version != "video-material-v1" or not BVID.fullmatch(self.bvid):
            raise ValueError("unsupported video material schema or BVID")
        if not self.bundle_id.strip() or not self.workspace_id.strip():
            raise ValueError("bundle and workspace identity are required")
        if not SHA256.fullmatch(self.input_digest):
            raise ValueError("video input digest must be SHA-256")
        if self.subtitle_source not in {"MANUAL", "AI_CAPTION", "ASR", "NONE"}:
            raise ValueError("unknown subtitle source")
        if self.subtitle_source == "NONE":
            if self.transcript_segments or self.transcript_original or self.transcript_corrected \
                    or "NO_SUBTITLE" not in self.coverage_gaps:
                raise ValueError("missing subtitles require an explicit coverage gap")
        elif not self.transcript_segments:
            raise ValueError("subtitle source requires transcript segments")
        elif "NO_SUBTITLE" in self.coverage_gaps:
            raise ValueError("subtitle coverage gap contradicts transcript segments")
        if self.transcript_segments and (not self.transcript_original or not self.transcript_corrected):
            raise ValueError("original and corrected transcripts must both be retained")

        def unique(items: list[object], attr: str) -> dict[str, object]:
            values = [str(getattr(item, attr)) for item in items]
            if any(not value for value in values) or len(values) != len(set(values)):
                raise ValueError(f"duplicate or empty {attr}")
            return {value: item for value, item in zip(values, items)}

        files = unique(self.files, "file_id")
        segments = unique(self.transcript_segments, "segment_id")
        frames = unique(self.frames, "frame_id")
        if not self.frames and "NO_FRAMES" not in self.coverage_gaps:
            raise ValueError("missing frames require an explicit coverage gap")
        if self.frames and "NO_FRAMES" in self.coverage_gaps:
            raise ValueError("frame coverage gap contradicts captured frames")
        unique(self.knowledge_nodes, "node_id")
        node_titles = [node.title.strip().casefold() for node in self.knowledge_nodes]
        if any(not title for title in node_titles) or len(node_titles) != len(set(node_titles)):
            raise ValueError("knowledge node titles must be unique and nonempty")
        for file in self.files:
            if not SHA256.fullmatch(file.checksum_sha256):
                raise ValueError("material file checksum must be SHA-256")
            if file.role != "VIDEO_FRAME" or file.media_type not in {"image/png", "image/jpeg"}:
                raise ValueError("unsupported video material file role or media type")
        for segment in self.transcript_segments:
            if segment.part != self.part or segment.start_ms >= segment.end_ms \
                    or segment.end_ms > self.duration_ms:
                raise ValueError("transcript segment belongs to another part or time range")
        for frame in self.frames:
            asset = files.get(frame.file_id)
            if frame.part != self.part or frame.at_ms >= self.duration_ms \
                    or asset is None or asset.role != "VIDEO_FRAME" \
                    or asset.checksum_sha256 != frame.checksum_sha256:
                raise ValueError("frame belongs to another part, time range, or file")
            if frame.dedupe_of and (frame.dedupe_of not in frames
                                    or frame.dedupe_of == frame.frame_id):
                raise ValueError("frame dedupe reference is invalid")
        for frame in self.frames:
            seen = {frame.frame_id}
            parent_id = frame.dedupe_of
            while parent_id:
                if parent_id in seen:
                    raise ValueError("frame dedupe reference contains a cycle")
                seen.add(parent_id)
                parent_id = frames[parent_id].dedupe_of
        assigned_frames: set[str] = set()
        for node in self.knowledge_nodes:
            if node.start_ms >= node.end_ms or node.end_ms > self.duration_ms:
                raise ValueError("knowledge node time range is invalid")
            for segment_id in node.transcript_segment_ids:
                segment = segments.get(segment_id)
                if segment is None or segment.end_ms < node.start_ms \
                        or segment.start_ms > node.end_ms:
                    raise ValueError("knowledge node transcript reference is outside its time range")
            for frame_id in node.frame_ids:
                frame = frames.get(frame_id)
                if frame is None or not node.start_ms <= frame.at_ms <= node.end_ms:
                    raise ValueError("knowledge node frame reference is outside its part or time range")
                if frame_id in assigned_frames:
                    raise ValueError("video frame is assigned to multiple knowledge nodes")
                assigned_frames.add(frame_id)
            if not node.transcript_segment_ids and not node.frame_ids and not node.missing:
                raise ValueError("knowledge node without evidence needs an explicit gap")
        if assigned_frames != set(frames):
            raise ValueError("every video frame must belong to one knowledge node")
        if self.frame_observations is not None:
            observed = unique(self.frame_observations, "file_id")
            if set(observed) != set(files):
                raise ValueError("frame observations must cover every material file")
            for observation in self.frame_observations:
                file = files[observation.file_id]
                if observation.schema_version != "frame-observation-v1" \
                        or not observation.task_id.strip() \
                        or observation.checksum_sha256 != file.checksum_sha256 \
                        or observation.media_type != file.media_type \
                        or observation.width * observation.height > 50_000_000 \
                        or len(observation.observations) > 64 \
                        or "VISUAL_SEMANTICS_UNVERIFIED" not in observation.coverage_gaps \
                        or len(observation.coverage_gaps) != len(set(observation.coverage_gaps)) \
                        or not set(observation.coverage_gaps) <= {
                            "NO_READABLE_TEXT", "VISUAL_SEMANTICS_UNVERIFIED"} \
                        or (not observation.observations) != (
                            "NO_READABLE_TEXT" in observation.coverage_gaps):
                    raise ValueError("frame observation does not match its frozen file")
                for text in observation.observations:
                    if text.kind != "TEXT" or not 0 < len(text.text) <= 1_000 \
                            or text.uncertain != (text.confidence < 80):
                        raise ValueError("frame observation contains ungrounded text")
        return self

    def content_digest(self) -> str:
        encoded = json.dumps(
            self.model_dump(mode="json", exclude_none=True), ensure_ascii=False, sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    def verify_file_bytes(self, read_file: Callable[[str], bytes]) -> None:
        """Verify referenced file IDs through a controlled reader, never a Worker path."""
        for file in self.files:
            content = read_file(file.file_id)
            if len(content) != file.size_bytes or len(content) > 50_000_000 \
                    or hashlib.sha256(content).hexdigest() != file.checksum_sha256:
                raise ValueError(f"material file bytes do not match manifest: {file.file_id}")
            try:
                with Image.open(BytesIO(content)) as image:
                    expected = "PNG" if file.media_type == "image/png" else "JPEG"
                    if image.format != expected or image.width < 1 or image.height < 1 \
                            or image.width * image.height > 50_000_000:
                        raise ValueError("invalid frame format or dimensions")
                    image.verify()
            except (OSError, ValueError) as exc:
                raise ValueError(f"material frame is not decodable: {file.file_id}") from exc
