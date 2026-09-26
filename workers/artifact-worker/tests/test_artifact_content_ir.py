from __future__ import annotations

import pytest

from app.artifact_content_ir import ArtifactContentIRV1, build_content_ir
from app.models import ArtifactSectionDraft


def test_typed_content_binds_sections_and_rendered_markdown() -> None:
    sections = [ArtifactSectionDraft(
        heading="Cache consistency", body="Write ordering follows the source.",
        source_refs=["source-1"],
    )]
    first = build_content_ir(artifact_type="COURSE_NOTE", title="Notes",
                             sections=sections, markdown="# Notes\n\n### Cache consistency")
    assert first.schema_version == "artifact-content-v1"
    assert first.content_digest == first.digest()
    assert first.content_digest == build_content_ir(
        artifact_type="COURSE_NOTE", title="Notes", sections=sections,
        markdown="# Notes\n\n### Cache consistency").content_digest
    assert first.markdown_sha256 != build_content_ir(
        artifact_type="COURSE_NOTE", title="Notes", sections=sections,
        markdown="# Notes\n\n### Other").markdown_sha256

    tampered = first.model_dump(mode="json")
    tampered["sections"][0]["body"] = "Ungrounded replacement"
    with pytest.raises(ValueError, match="digest"):
        ArtifactContentIRV1.model_validate(tampered)
