# Parse Worker

阶段 5A 与 5D 的本地文档转换服务，基于 Docling 2.138.0。更新时间：2026-10-13。

## 接口

`POST /internal/parse` 接收 `application/octet-stream` 的上传字节，不接受 URL 或文件系统路径。请求须包含 `X-NoteWeave-Internal-Token`，令牌为空或不匹配时拒绝解析。

必需查询参数：`workspace_id`、`source_id`、`snapshot_id`、`file_name`、`mime_type`、`sha256`。`file_name` 最长 300 字符，与 Java 上传约定一致；`sha256` 必须为上传内容的 SHA-256 小写十六进制值；可选 `max_chars` 降低本次输出上限。服务先流式写入私有临时目录，核对实际字节数量及哈希，再调用 Docling SDK。文件名只用于选择允许的扩展名，实际文件路径由服务生成。

响应固定包含 `schema_version=1`、三个标识字段、`sha256`、`mime_type`、`markdown`、`page_count`、`elements`、`parser=DOCLING`、`truncated`、`warnings`、`page_sources`。没有真实 PDF 页审计时 `page_sources` 返回空数组；不补造页记录。每个元素包含 `element_type`、`text`、`page`、`bbox`、`heading_level`，并增加可空的 `text_origin` 与 `ocr_confidence`。表格通过 Docling 的 `export_to_markdown(doc=document)` 导出；其他元素使用实际文本，并按 `iterate_items` 的顺序保留标题、图注、公式和列表结构。公式的 `text` 为空时使用 SDK 的真实 `orig`；表格内已经导出的单元格不会重复加入正文。

`page` 和 `bbox` 来自元素的 `prov`，缺失时为 `null`；坐标保持 `TOPLEFT` 或 `BOTTOMLEFT` 原始方向。跨页元素保留完整文本，位置记录首个真实来源并告警。`page_count` 取真实页面记录数量，Office、EPUB 或 Markdown 没有页面记录时为 `0`。没有真实级别的标题不推算 `heading_level`。

支持 PDF、DOCX、DOC、PPTX、XLSX、RTF、EPUB、ODT、HTML、Markdown、TXT、CSV。扩展名、MIME 和 Docling 实际检测的格式必须一致。PPTX 使用 `PowerpointFormatOption`，XLSX 使用 `ExcelFormatOption`，CSV 使用 `CsvFormatOption`，保留 SDK 输出的真实标题、表格与来源定位；PPTX/XLSX 不启用额外的图表图片渲染。RTF 与旧 DOC 的本地转换需要 LibreOffice，镜像已安装 `libreoffice-writer`；EPUB 使用 Docling 的直接支持；ODT 额外安装固定版本的 `format-opendocument` 依赖。所有已配置文档后端的本地/远程资源抓取关闭。

PDF 默认按页选择性开启 OCR。仅当原生文本的非空白字符数小于 20，且后端提供实际正面积位图时调用官方 RapidOCR ONNX 引擎，语言为 `ch`。OCR 引擎在首个真正需要识别的页面上才初始化；普通文本 PDF 不会提前初始化或下载 OCR 模型。使用 `PDF_AWARE_LAYOUT_REGIONS`，由 Docling 保留已有文本层并处理版面、表格与阅读顺序，不另加双栏算法。后端没有位图能力或原生文本来源不明确时保守跳过并告警；后端查询或 OCR 引擎报错时返回真实解析失败。

`page_sources` 的每项包含 `page`、`text_origin`、`ocr_confidence`、`native_char_count`、`has_images`、`ocr_attempted`。直接使用 SDK 的 1 起始页码；底层 `load_page` 的零起始索引不进入响应。页面存在真实 OCR 单元时标记 `OCR`，只有原生文本时为 `TEXT_LAYER`，无文本或无法确认时为 `null`；同页混合来源会明确告警。OCR 置信度取该页真实 OCR 单元分数的算术均值，缺失、非有限或超出 0..1 时留空，不使用布局置信度或原生文本默认分数。

元素来源从 `ConversionResult.assembled` 的真实 `cluster.cells`，按页码和转换到相同坐标方向后的完整 `prov` 逐一精确关联。纯文本层元素为 `TEXT_LAYER`，纯 OCR 元素为 `OCR` 并附真实关联单元分数均值。跨页元素检查全部来源；混合元素、重复位置造成歧义、无法关联的富表格、缺失来源标记均留空并告警。页面标记不会覆盖同页原生元素的来源。每次转换使用独立的私有审计状态及锁，跨内部阶段线程直接传递该文档的 `ConversionResult`，不依赖线程局部变量或全局页面缓存。

普通 JSON 由 Java 的既有纯文本降级读取，Worker 明确拒绝普通 `application/json`。Docling 的 `JSON_DOCLING` 专门读取 `DoclingDocument` JSON，不能替代任意 JSON 文件解析。

## 配置与运行

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `NOTEWEAVE_PARSE_INTERNAL_AUTH_TOKEN` | 空 | 必须配置，与内部调用方保持一致 |
| `NOTEWEAVE_PARSE_MAX_FILE_BYTES` | `268435456` | 上传字节上限，不能超过 256 MiB |
| `NOTEWEAVE_PARSE_MAX_CHARS` | `20000000` | Markdown 输出上限，不能超过 20M 字符 |
| `NOTEWEAVE_PARSE_MAX_ELEMENTS` | `100000` | 元素数量上限，不能超过 100000 |
| `NOTEWEAVE_PARSE_MAX_CONCURRENT_DOCUMENTS` | `1` | 同时接纳请求数，范围 1..4；共享模型转换仍串行 |
| `NOTEWEAVE_PARSE_DOCUMENT_TIMEOUT_SECONDS` | `120` | SDK 与 HTTP 等待转换的有限超时，最大 600 秒 |
| `NOTEWEAVE_PARSE_UPLOAD_TIMEOUT_SECONDS` | `120` | 流式上传的有限超时，最大 600 秒 |
| `NOTEWEAVE_PARSE_ARTIFACTS_PATH` | 空 | 已预先准备的 Docling 模型目录 |
| `NOTEWEAVE_PARSE_TEMP_ROOT` | 系统临时目录 | 上传私有临时目录的父目录 |
| `NOTEWEAVE_PARSE_OCR_ENABLED` | `true` | 按页选择性开启真实 OCR |
| `NOTEWEAVE_PARSE_OCR_MIN_NATIVE_CHARS` | `20` | 原生非空白字符数低于此值且确有位图时才 OCR，范围 1..1000 |

输出超限时只保留完整元素的连续前缀，设置 `truncated=true` 并返回告警；表格、图注和公式不会截取一半。首个完整文本元素超过限制、导致无法返回可用正文时返回 413。并发槽位用尽返回 429。SDK 转换失败、部分转换或无文本返回真实错误，不返回空成功。转换等待超时返回 504，但执行中的线程继续占用槽位和临时文件，实际结束后才清理和释放，防止超时请求造成无界线程并发。

OCR 实际引擎调用另有独立串行锁；即便上一文档的 Docling 内部 OCR 线程在 SDK 超时后仍运行，下一文档也不会并发调用同一引擎。该锁不与引擎初始化锁共用。Docling 的文档超时会在有限等待后放弃仍阻塞的内部阶段线程，外层返回不能证明全部 SDK 内部线程已结束。其他模型阶段以及真实超时资源释放仍需实际运行验证；本阶段未执行该验证。

默认关闭远程服务及外部插件。PDF 需要本地布局及表格模型；首次加载可能触发 SDK 的模型下载，生产环境应预先下载模型并设置 `NOTEWEAVE_PARSE_ARTIFACTS_PATH`，离线部署还应由运行环境禁止外网连接。镜像安装 Python 依赖，不预下载模型。模型目录应按固定 Docling 版本准备，并挂载到容器内配置路径。

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install '.[test]'
$env:NOTEWEAVE_PARSE_INTERNAL_AUTH_TOKEN = 'replace-with-internal-token'
.venv/Scripts/python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers 1
```

必须使用一个 Uvicorn 进程，避免每个进程独立加载模型和突破共享并发限制。Docker 容器监听 8000，项目部署可映射为宿主 18103。`GET /health` 仅报告进程存活和解析器名称，不暴露令牌或模型路径，也不保证模型已加载。

## 自动化测试

```powershell
.venv/Scripts/python.exe -m pytest -q
```

测试显式注入 Fake converter，覆盖鉴权、流式上限、哈希、有限并发、坐标和标题结构、真实表格导出调用、完整元素截断、失败与超时清理、SDK 安全配置。5D 增加选择性识别、惰性初始化、文本层保留、混合来源、跨页及精确位置关联、缺失与无效置信度、私有文档审计线程隔离和 HTTP 响应契约。轻量测试无需安装 Docling；使用已有 Python 环境时只需 FastAPI、Pydantic Settings、HTTPX、Pytest。生产依赖固定为 `docling-slim[feat-ocr-rapidocr-onnx]==2.138.0`，由该版本限定 RapidOCR 与 ONNX Runtime 依赖。未执行真实 SDK 模型转换或质量评测；真实文档质量评测与镜像构建需在具备固定依赖、模型和 LibreOffice 的环境独立执行。

SDK 依据：[DocumentConverter](https://docling-project.github.io/docling/reference/document_converter/)、[DoclingDocument](https://docling-project.github.io/docling/reference/docling_document/)、[PipelineOptions](https://docling-project.github.io/docling/reference/pipeline_options/)、[格式支持](https://docling-project.github.io/docling/usage/supported_formats/)。

5D 固定版本源码依据：[PDF 阶段与 OCR 扩展点](https://github.com/docling-project/docling/blob/v2.138.0/docling/pipeline/standard_pdf_pipeline.py)、[按 1 起始页号选择页面](https://github.com/docling-project/docling/blob/v2.138.0/docling/pipeline/base_pipeline.py)、[底层零起始页面加载](https://github.com/docling-project/docling/blob/v2.138.0/docling/backend/pdf_backend.py)、[RapidOCR 真实单元与识别分数](https://github.com/docling-project/docling/blob/v2.138.0/docling/models/stages/ocr/rapid_ocr_model.py)、[保留文本层的 OCR 合并](https://github.com/docling-project/docling/blob/v2.138.0/docling/models/base_ocr_model.py)、[ReadingOrder 来源转换](https://github.com/docling-project/docling/blob/v2.138.0/docling/models/stages/reading_order/readingorder_model.py)。
