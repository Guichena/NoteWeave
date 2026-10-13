from enum import Enum
import sys
from types import ModuleType

from pydantic import ValidationError
import pytest

from app.config import MAX_OUTPUT_CHARS, MAX_UPLOAD_BYTES, Settings
from app.converter import build_converter
from app.errors import ParseFailure


class RecordedOptions:
    def __init__(self, **values):
        self.values = values


def test_docling_sdk_factory_disables_ocr_remote_fetch_and_plugins(monkeypatch, tmp_path):
    formats = Enum("InputFormat", {name: name.lower() for name in (
        "PDF", "DOCX", "DOC", "RTF", "PPTX", "XLSX", "EPUB", "ODT", "HTML", "MD", "CSV")})
    modules = {name: ModuleType(name) for name in ("docling", "docling.datamodel",
        "docling.datamodel.base_models", "docling.datamodel.backend_options",
        "docling.datamodel.pipeline_options", "docling.document_converter")}
    modules["docling.datamodel.base_models"].InputFormat = formats
    for name in ("CsvBackendOptions", "DeclarativeBackendOptions", "EpubBackendOptions", "HTMLBackendOptions",
                 "MarkdownBackendOptions", "MsExcelBackendOptions", "MsPowerpointBackendOptions", "MsWordBackendOptions"):
        setattr(modules["docling.datamodel.backend_options"], name, type(name, (RecordedOptions,), {}))
    for name in ("ConvertPipelineOptions", "PdfPipelineOptions"):
        setattr(modules["docling.datamodel.pipeline_options"], name, type(name, (RecordedOptions,), {}))
    for name in ("CsvFormatOption", "DocumentConverter", "EpubFormatOption", "ExcelFormatOption", "HTMLFormatOption",
                 "MarkdownFormatOption", "OdtFormatOption", "PdfFormatOption", "PowerpointFormatOption", "WordFormatOption"):
        setattr(modules["docling.document_converter"], name, type(name, (RecordedOptions,), {}))
    for name, module in modules.items():
        monkeypatch.setitem(sys.modules, name, module)
    converter = build_converter(Settings(artifacts_path=tmp_path, document_timeout_seconds=90))
    options = converter.values["format_options"]
    assert set(converter.values["allowed_formats"]) == set(formats)
    assert type(options[formats.RTF]).__name__ == "WordFormatOption"
    assert type(options[formats.EPUB]).__name__ == "EpubFormatOption"
    assert type(options[formats.PPTX]).__name__ == "PowerpointFormatOption"
    assert type(options[formats.XLSX]).__name__ == "ExcelFormatOption"
    assert type(options[formats.CSV]).__name__ == "CsvFormatOption"
    assert type(options[formats.PPTX].values["backend_options"]).__name__ == "MsPowerpointBackendOptions"
    assert type(options[formats.XLSX].values["backend_options"]).__name__ == "MsExcelBackendOptions"
    assert options[formats.PPTX].values["backend_options"].values["render_chart_images"] is False
    assert options[formats.XLSX].values["backend_options"].values["render_chart_images"] is False
    pdf = options[formats.PDF].values["pipeline_options"].values
    assert pdf["do_ocr"] is False and pdf["do_table_structure"] is True
    for name in ("do_picture_description", "do_picture_classification", "do_code_enrichment", "do_formula_enrichment"):
        assert pdf[name] is False
    for option in options.values():
        pipeline = option.values["pipeline_options"].values
        assert pipeline["document_timeout"] == 90 and pipeline["artifacts_path"] == tmp_path
        assert pipeline["enable_remote_services"] is False and pipeline["allow_external_plugins"] is False
        backend = option.values.get("backend_options")
        if backend:
            assert backend.values["enable_remote_fetch"] is False
            assert backend.values["enable_local_fetch"] is False
    assert options[formats.EPUB].values["backend_options"].values["fetch_images"] is False
    assert options[formats.MD].values["backend_options"].values["fetch_images"] is False
    assert options[formats.HTML].values["backend_options"].values["render_page"] is False


def test_missing_docling_is_explicit_unavailable_not_empty_success(monkeypatch):
    monkeypatch.setitem(sys.modules, "docling", None)
    with pytest.raises(ParseFailure) as failure:
        build_converter(Settings())
    assert failure.value.status_code == 503 and failure.value.code == "DOCLING_UNAVAILABLE"


def test_documented_environment_names(monkeypatch, tmp_path):
    for name, value in {"INTERNAL_AUTH_TOKEN": "env-token", "MAX_CHARS": "1000",
        "MAX_FILE_BYTES": "2048", "MAX_CONCURRENT_DOCUMENTS": "2", "DOCUMENT_TIMEOUT_SECONDS": "60",
        "ARTIFACTS_PATH": str(tmp_path)}.items():
        monkeypatch.setenv("NOTEWEAVE_PARSE_" + name, value)
    settings = Settings()
    assert settings.internal_auth_token.get_secret_value() == "env-token"
    assert settings.max_output_chars == 1000 and settings.max_upload_bytes == 2048
    assert settings.max_concurrency == 2 and settings.document_timeout_seconds == 60
    assert settings.artifacts_path == tmp_path


@pytest.mark.parametrize("value", ["", "   "])
def test_empty_environment_paths_do_not_point_at_working_directory(monkeypatch, value):
    monkeypatch.setenv("NOTEWEAVE_PARSE_ARTIFACTS_PATH", value)
    monkeypatch.setenv("NOTEWEAVE_PARSE_TEMP_ROOT", value)
    settings = Settings()
    assert settings.artifacts_path is None and settings.temp_root is None


@pytest.mark.parametrize("values", [{"max_output_chars": MAX_OUTPUT_CHARS + 1},
    {"max_upload_bytes": MAX_UPLOAD_BYTES + 1}, {"max_concurrency": 0},
    {"document_timeout_seconds": 0}, {"document_timeout_seconds": float("inf")}])
def test_configuration_cannot_remove_resource_bounds(values):
    with pytest.raises(ValidationError):
        Settings(**values)
