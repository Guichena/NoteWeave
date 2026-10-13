"""按固定 SDK 配置构造本地转换器，直到首次合法请求才加载模型依赖。"""

from app.errors import ParseFailure


FORMATS = {
    ".pdf": ("PDF", {"application/pdf"}),
    ".docx": ("DOCX", {"application/vnd.openxmlformats-officedocument.wordprocessingml.document"}),
    ".doc": ("DOC", {"application/msword", "application/x-msword"}),
    ".pptx": ("PPTX", {"application/vnd.openxmlformats-officedocument.presentationml.presentation"}),
    ".xlsx": ("XLSX", {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"}),
    ".rtf": ("RTF", {"application/rtf", "text/rtf", "application/x-rtf"}),
    ".epub": ("EPUB", {"application/epub+zip"}),
    ".odt": ("ODT", {"application/vnd.oasis.opendocument.text"}),
    ".html": ("HTML", {"text/html", "application/xhtml+xml"}),
    ".htm": ("HTML", {"text/html", "application/xhtml+xml"}),
    ".md": ("MD", {"text/markdown", "text/x-markdown", "text/plain"}),
    ".markdown": ("MD", {"text/markdown", "text/x-markdown", "text/plain"}),
    ".txt": ("MD", {"text/plain"}),
    ".csv": ("CSV", {"text/csv"}),
}


def build_converter(settings):
    try:
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.backend_options import (
            CsvBackendOptions, DeclarativeBackendOptions, EpubBackendOptions, HTMLBackendOptions,
            MarkdownBackendOptions, MsExcelBackendOptions, MsPowerpointBackendOptions, MsWordBackendOptions,
        )
        from docling.datamodel.pipeline_options import ConvertPipelineOptions, PdfPipelineOptions
        from docling.document_converter import (
            CsvFormatOption, DocumentConverter, EpubFormatOption, ExcelFormatOption, HTMLFormatOption,
            MarkdownFormatOption, OdtFormatOption, PdfFormatOption, PowerpointFormatOption, WordFormatOption,
        )
    except ImportError as exc:
        raise ParseFailure(503, "DOCLING_UNAVAILABLE", "本地 Docling 依赖尚未就绪") from exc
    common = {"document_timeout": settings.document_timeout_seconds,
        "enable_remote_services": False, "allow_external_plugins": False,
        "artifacts_path": settings.artifacts_path}
    pdf = PdfPipelineOptions(**common, do_ocr=False, do_table_structure=True,
        do_picture_description=False, do_picture_classification=False,
        do_code_enrichment=False, do_formula_enrichment=False)
    simple = ConvertPipelineOptions(**common)
    word_backend = MsWordBackendOptions(enable_remote_fetch=False, enable_local_fetch=False,
                                        render_chart_images=False)
    options = {
        InputFormat.PDF: PdfFormatOption(pipeline_options=pdf),
        InputFormat.DOCX: WordFormatOption(pipeline_options=simple, backend_options=word_backend),
        InputFormat.DOC: WordFormatOption(pipeline_options=simple, backend_options=word_backend),
        InputFormat.RTF: WordFormatOption(pipeline_options=simple, backend_options=word_backend),
        InputFormat.PPTX: PowerpointFormatOption(pipeline_options=simple, backend_options=MsPowerpointBackendOptions(
            enable_remote_fetch=False, enable_local_fetch=False, render_chart_images=False)),
        InputFormat.XLSX: ExcelFormatOption(pipeline_options=simple, backend_options=MsExcelBackendOptions(
            enable_remote_fetch=False, enable_local_fetch=False, render_chart_images=False)),
        InputFormat.EPUB: EpubFormatOption(pipeline_options=simple, backend_options=EpubBackendOptions(
            enable_remote_fetch=False, enable_local_fetch=False, fetch_images=False)),
        InputFormat.ODT: OdtFormatOption(pipeline_options=simple, backend_options=DeclarativeBackendOptions(
            enable_remote_fetch=False, enable_local_fetch=False)),
        InputFormat.HTML: HTMLFormatOption(pipeline_options=simple, backend_options=HTMLBackendOptions(
            enable_remote_fetch=False, enable_local_fetch=False, render_page=False)),
        InputFormat.MD: MarkdownFormatOption(pipeline_options=simple, backend_options=MarkdownBackendOptions(
            enable_remote_fetch=False, enable_local_fetch=False, fetch_images=False)),
        InputFormat.CSV: CsvFormatOption(pipeline_options=simple, backend_options=CsvBackendOptions(
            enable_remote_fetch=False, enable_local_fetch=False)),
    }
    return DocumentConverter(allowed_formats=list(options), format_options=options)
