from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


ElementType = Literal["TITLE", "SECTION_HEADER", "TEXT", "TABLE", "CAPTION", "FORMULA", "CODE",
                      "LIST_ITEM", "FOOTNOTE", "PICTURE"]
TextOrigin = Literal["TEXT_LAYER", "OCR"]


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
    text_origin: TextOrigin | None = None
    ocr_confidence: float | None = Field(default=None, ge=0, le=1, allow_inf_nan=False)

    @model_validator(mode="after")
    def confidence_requires_ocr_source(self):
        if self.text_origin != "OCR" and self.ocr_confidence is not None:
            raise ValueError("只有真实 OCR 文本可以附带 OCR 置信度")
        return self


class PageSource(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    page: int = Field(ge=1)
    text_origin: TextOrigin | None = None
    ocr_confidence: float | None = Field(default=None, ge=0, le=1, allow_inf_nan=False)
    native_char_count: int | None = Field(default=None, ge=0)
    has_images: bool | None = None
    ocr_attempted: bool

    @model_validator(mode="after")
    def confidence_requires_ocr_source(self):
        if self.text_origin != "OCR" and self.ocr_confidence is not None:
            raise ValueError("只有包含真实 OCR 文本的页面可以附带 OCR 置信度")
        return self


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
    page_sources: list[PageSource] = Field(default_factory=list)
