# Parse Worker

阶段 5A 的本地文档转换服务，基于 Docling 2.138.0。更新时间：2026-10-13。

## 接口

`POST /internal/parse` 接收 `application/octet-stream` 的上传字节，不接受 URL 或文件系统路径。请求须包含 `X-NoteWeave-Internal-Token`，令牌为空或不匹配时拒绝解析。

必需查询参数：`workspace_id`、`source_id`、`snapshot_id`、`file_name`、`mime_type`、`sha256`。`file_name` 最长 300 字符，与 Java 上传约定一致；`sha256` 必须为上传内容的 SHA-256 小写十六进制值；可选 `max_chars` 降低本次输出上限。服务先流式写入私有临时目录，核对实际字节数量及哈希，再调用 Docling SDK。文件名只用于选择允许的扩展名，实际文件路径由服务生成。

响应固定包含 `schema_version=1`、三个标识字段、`sha256`、`mime_type`、`markdown`、`page_count`、`elements`、`parser=DOCLING`、`truncated`、`warnings`。每个元素包含 `element_type`、`text`、`page`、`bbox`、`heading_level`。表格通过 Docling 的 `export_to_markdown(doc=document)` 导出；其他元素使用实际文本，并按 `iterate_items` 的顺序保留标题、图注、公式和列表结构。表格内已经导出的单元格不会重复加入正文。

`page` 和 `bbox` 来自元素的 `prov`，缺失时为 `null`；坐标保持 `TOPLEFT` 或 `BOTTOMLEFT` 原始方向。跨页元素保留完整文本，位置记录首个真实来源并告警。`page_count` 取真实页面记录数量，Office、EPUB 或 Markdown 没有页面记录时为 `0`。没有真实级别的标题不推算 `heading_level`。

支持 PDF、DOCX、DOC、PPTX、XLSX、RTF、EPUB、ODT、HTML、Markdown、TXT、CSV。扩展名、MIME 和 Docling 实际检测的格式必须一致。PPTX 使用 `PowerpointFormatOption`，XLSX 使用 `ExcelFormatOption`，CSV 使用 `CsvFormatOption`，保留 SDK 输出的真实标题、表格与来源定位；PPTX/XLSX 不启用额外的图表图片渲染。RTF 与旧 DOC 的本地转换需要 LibreOffice，镜像已安装 `libreoffice-writer`；EPUB 使用 Docling 的直接支持；ODT 额外安装固定版本的 `format-opendocument` 依赖。所有已配置文档后端的本地/远程资源抓取关闭。PDF 当前设置 `do_ocr=False`，扫描件若没有可提取文本会返回错误，按页 OCR 来源在阶段 5D 补充。

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

输出超限时只保留完整元素的连续前缀，设置 `truncated=true` 并返回告警；表格、图注和公式不会截取一半。首个完整文本元素超过限制、导致无法返回可用正文时返回 413。并发槽位用尽返回 429。SDK 转换失败、部分转换或无文本返回真实错误，不返回空成功。转换等待超时返回 504，但执行中的线程继续占用槽位和临时文件，实际结束后才清理和释放，防止超时请求造成无界线程并发。

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

测试显式注入 Fake converter，覆盖鉴权、流式上限、哈希、有限并发、坐标和标题结构、真实表格导出调用、完整元素截断、失败与超时清理、SDK 安全配置。轻量测试无需安装 Docling；使用已有 Python 环境时只需 FastAPI、Pydantic Settings、HTTPX、Pytest。真实文档质量评测与镜像构建需在具备固定依赖、模型和 LibreOffice 的环境独立执行。

SDK 依据：[DocumentConverter](https://docling-project.github.io/docling/reference/document_converter/)、[DoclingDocument](https://docling-project.github.io/docling/reference/docling_document/)、[PipelineOptions](https://docling-project.github.io/docling/reference/pipeline_options/)、[格式支持](https://docling-project.github.io/docling/usage/supported_formats/)。
