from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


ElementType = Literal["TITLE", "SECTION_HEADER", "TEXT", "TABLE", "CAPTION", "FORMULA", "CODE",
                      "LIST_ITEM", "FOOTNOTE", "PICTURE"]


class BoundingBox(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    left: float = Field(allow_inf_nan=False)
    top: float = Field(allow_inf_nan=False)
    right: float = Field(allow_inf_nan=False)
    bottom: float = Field(allow_inf_nan=False)
    coord_origin: Literal["TOPLEFT", "BOTTOMLEFT"]


class ParsedElement(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    element_type: ElementType
    text: str
    page: int | None = Field(default=None, ge=1)
    bbox: BoundingBox | None = None
    heading_level: int | None = Field(default=None, ge=1)


class ParseResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    schema_version: Literal[1] = 1
    workspace_id: str
    source_id: str
    snapshot_id: str
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    mime_type: str
    markdown: str = Field(min_length=1)
    page_count: int = Field(ge=0)
    elements: list[ParsedElement] = Field(min_length=1)
    parser: Literal["DOCLING"] = "DOCLING"
    truncated: bool
    warnings: list[str]
