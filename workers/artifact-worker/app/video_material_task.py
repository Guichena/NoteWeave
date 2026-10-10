from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.artifact_skill_catalog import CATALOG_DIGEST
from app.models import ArtifactTaskInput, ArtifactTaskInputPayload, ControlPack


class VideoMaterialTaskInput(BaseModel):
    """Host-owned acquisition identity for a material-only Worker execution."""

    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["video-material-input.v1"]
    task_id: str = Field(min_length=1)
    request_id: str = Field(min_length=1)
    workspace_id: str = Field(min_length=1)
    input_snapshot_id: str = Field(min_length=1)
    template_version: Literal["original-v1"]
    inputs: dict[str, object]

    @model_validator(mode="after")
    def validate_identity(self) -> VideoMaterialTaskInput:
        if self.input_snapshot_id != self.request_id:
            raise ValueError("material input snapshot does not match parent request")
        if set(self.inputs) != {
            "url", "part", "language", "frame_density", "asr_fallback"
        }:
            raise ValueError("material acquisition inputs are incomplete")
        if self.inputs["frame_density"] not in {"LOW", "STANDARD", "HIGH"} \
                or self.inputs["asr_fallback"] not in {"ALLOW", "DENY"}:
            raise ValueError("material acquisition policy is invalid")
        if str(self.inputs["part"]) not in {str(i) for i in range(1, 1001)}:
            raise ValueError("material part is invalid")
        return self

    def to_acquisition_input(self) -> ArtifactTaskInput:
        """Reuse the published Bilibili acquisition graph without creating a PDF Candidate."""
        return ArtifactTaskInput(
            task_id=self.task_id,
            workspace_id=self.workspace_id,
            target_id=self.request_id,
            input_snapshot_id=self.input_snapshot_id,
            catalog_digest=CATALOG_DIGEST,
            control_pack=ControlPack(
                pack_type="VIDEO_MATERIAL",
                target_key="bilibili_course_note_pdf",
                task_neighborhood="VIDEO_MATERIAL",
            ),
            input_payload=ArtifactTaskInputPayload(
                skill_key="bilibili_course_note_pdf",
                user_requirement="Collect the frozen video material and evidence plan.",
                generation_brief="Collect the frozen video material and evidence plan.",
                inputs=dict(self.inputs),
                writeback_mode="MATERIAL_ONLY",
            ),
        )
