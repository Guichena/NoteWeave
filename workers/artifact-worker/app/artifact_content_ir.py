from __future__ import annotations

import hashlib
import json
import re

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.models import ArtifactSectionDraft


SHA256 = re.compile(r"^[0-9a-f]{64}$")


class ArtifactContentIRV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: str = "artifact-content-v1"
    artifact_type: str = Field(min_length=1)
    title: str = Field(min_length=1)
    sections: list[ArtifactSectionDraft] = Field(min_length=1)
    markdown_sha256: str
    content_digest: str

    @model_validator(mode="after")
    def validate_content(self) -> "ArtifactContentIRV1":
        if self.schema_version != "artifact-content-v1" or not SHA256.fullmatch(self.markdown_sha256):
            raise ValueError("unsupported Artifact content IR or markdown digest")
        if any(not section.heading.strip() or not section.body.strip()
               or any(not ref.strip() for ref in section.source_refs) for section in self.sections):
            raise ValueError("Artifact content IR section is empty")
        if self.content_digest != self.digest():
            raise ValueError("Artifact content IR digest does not match its sections")
        return self

    def digest(self) -> str:
        value = self.model_dump(mode="json", exclude={"content_digest"})
        encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def build_content_ir(
    *, artifact_type: str, title: str, sections: list[ArtifactSectionDraft], markdown: str,
) -> ArtifactContentIRV1:
    fields = {
        "schema_version": "artifact-content-v1",
        "artifact_type": artifact_type,
        "title": title,
        "sections": [section.model_dump(mode="json") for section in sections],
        "markdown_sha256": hashlib.sha256(markdown.encode("utf-8")).hexdigest(),
    }
    encoded = json.dumps(fields, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    fields["content_digest"] = hashlib.sha256(encoded.encode("utf-8")).hexdigest()
    return ArtifactContentIRV1.model_validate(fields)
