# 资料解析对比工具

本目录为阶段 5C 提供 Docling 与 MinerU 的评测入口。当前只完成工具代码和自动化测试，真实质量评测按用户要求暂缓。`manifest.json` 的 `samples` 为空，目标数量为 30；没有项目样例、人工标注、实测分数或选型结论。

## 准备样例和标注

在清单的 `sample_root` 中指定真实资料根目录。根目录可以是本地绝对路径，样例及标注路径必须相对于该目录，不能越出该目录。每个样例须包含以下字段：

| 字段 | 内容 |
|---|---|
| `sample_id` | 不重复的英文、数字、下划线或连字符标识，最长 64 字符 |
| `input_path` | 原始资料的相对路径 |
| `sha256` | 人工确认的原件 SHA-256，64 位小写十六进制；尚未确认时可为空 |
| `mime_type` | 原件 MIME 类型 |
| `gold_text_path` | 人工核对的 UTF-8 正文文件；缺失时可为空 |
| `gold_layout_path` | 符合 `normalized-document.schema.json` 的人工结构标注；缺失时可为空 |

人工结构标注的 `parser` 为 `GOLD`，`sample_id` 和 `input_sha256` 必须匹配清单。元素按真实阅读顺序排列，包含正文、标题、表格、图注与公式。页码从 1 开始；没有可确认页码、坐标、标题级别、文字来源或置信度时填 `null`。无法确认的结构不要标为已知。

`gold_text_path` 保存与结构元素一致的阅读正文，包含标题、图注、公式和表格的真实文字；不包含展示用 Markdown 表格分隔线或加粗标记。图片本身没有正文时不补造说明。表格网格能够确认时在 `table_cells` 中逐行填写真实单元格，令 `table_structure_known=true`；合并单元格或无法确认的结构填 `table_cells=null`、`table_structure_known=false`。

输入缺失、摘要不符、人工文本或版面标注缺失、标注不完整、清单数量不足 30 时，真实执行会被拒绝。工具不会自动生成标注，也不会用解析结果冒充人工标注。

## 未来执行命令

默认命令只校验准备情况，不调用 HTTP、MinerU 命令或版本探测，也不安装模型。空清单会输出 `NOT_RUN`、原因码和空结果数组：

```powershell
python experiments/pipeline/pipeline_eval.py --manifest experiments/pipeline/manifest.json --output experiments/pipeline/runs/readiness-001
```

未来备齐资料与标注，并准备好带专用令牌的 Parse Worker 和本机 MinerU 4 环境后，使用新的输出目录显式执行：

```powershell
$env:NOTEWEAVE_PARSE_INTERNAL_AUTH_TOKEN = '本机配置的专用令牌'
python experiments/pipeline/pipeline_eval.py --manifest experiments/pipeline/manifest.json --output experiments/pipeline/runs/comparison-001 --execute --docling-url http://localhost:18103 --mineru-executable mineru-kit
```

令牌只用于请求头，不写进报告或命令行。`--execute` 下所有解析器都未执行时返回非零退出码；默认准备检查的 `NOT_RUN` 返回 0。部分解析器失败时返回 1。已有结果文件或解析产物不会被覆盖。

Docling 接口使用本项目 `POST /internal/parse` 二进制流，严格核对身份、MIME 和摘要，接收有大小上限。内部接口的元素来源字段和可选 `page_sources` 按 5D 约定校验，未知值保持为空。当前服务没有返回 Docling SDK 版本，因此报告的 `parser_version=null`，原因是 `REMOTE_PARSER_VERSION_UNAVAILABLE`。项目依赖中的固定版本不作为运行时观测版本。

MinerU 使用无状态的 `mineru-kit parse ... --format zip --pages all`，默认 `standard`。只读取同一 ZIP 中的 `structured_content.json` 与 `middle_json.json`，不向文件系统解压成员、不加载图片或外部链接。ZIP 成员路径、数量、文件大小和实际读取大小均有上限。中间产物须为 `docvortex.middle` 协议 2.0，生产者版本须为 MinerU 4。正文优先从原始内联元素读取，保留真实字面符号，避免把渲染器新增的 Markdown 格式计入 CER。协议、元素类型或两个产物的页块对齐无法确认时失败，不猜测纯文本。

## 指标与产物

每个样例、每个解析器独立记录 `NOT_RUN / COMPLETED / FAILED`、原因、原始产物路径及 SHA-256、标准结构路径、观测版本和耗时。未执行或失败时 `metrics=null`。没有测量的指标保持为空。

- CER：先做 Unicode NFC，并去掉空白字符，再计算真实 Levenshtein 编辑距离与人工参考字符数之比；不把大于 1 的错误率截成 1。默认最多计算 400 万个动态规划单元；超出返回 `EDIT_DISTANCE_BUDGET_EXCEEDED`，指标为空。空参考文本没有分数。
- 标题：以真实页码和规范化文字做多重集合匹配，分别给出精确率、召回率及计数；只有匹配标题的双方层级都已知时才比较层级准确率。
- 表格：支持简单 Markdown 和 HTML 网格，分别比较行列形状与单元格全文，保留表格总数、可比较数和已知结构数。`rowspan`、`colspan`、嵌套表格及无法确认的网格结构保持未知，不伪造单元格。
- 阅读顺序：仅匹配同页且文本唯一的真实元素，输出匹配覆盖率、实际比较的元素对和逆序数。匹配不足两块时没有顺序分数；覆盖率低于 0.8 时为 `LOW_MATCH_COVERAGE`，保留真实计数，顺序分数为空。0.8 是工具的保守默认值，尚未用项目样例校准。

MinerU 当前渲染结果中的视觉说明没有保留原始索引，图注和脚注的顺序标为未知，文字仍保留。坐标保留真实值；无法确认坐标方向时为 `UNKNOWN`。不将两个解析器不同单位的坐标直接计算 IoU。

耗时为本机观测的适配器调用耗时，含传输、原始产物保存和结构转换，不能直接用作服务纯解析耗时或 P99。远端 Docling 的内存不可观测，`peak_rss_bytes=null`；本工具没有可靠采集 MinerU 子进程及其子任务的内存，同样为空，原因分别明确记录。不把本工具进程的内存冒充解析器内存。

标准结构保留真实的可空 `text_origin / ocr_confidence`。只有已确认来自 OCR 且 SDK 提供有限的 0 至 1 分数时才保存置信度；未提供的来源或置信度不推断。此结构可供 5D 来源审计复用。

## 轻量验证

以下测试只用临时文件与替身，不使用项目质量样例，不访问真实解析服务，也不启动真实 MinerU：

```powershell
python -m unittest discover -s experiments/pipeline/tests -v
python -m compileall -q experiments/pipeline
```

测试中的 30 份临时结构输入用于验证清单门控和报告契约，不能计入要求的 30 份项目样例。所有临时文件在测试结束后清理。

## 官方协议依据

- [MinerU 命令行工具](https://opendatalab.github.io/MinerU/usage/cli_tools/)：无状态解析、完整页面与 CLI 使用约定。
- [MinerU 输出协议](https://opendatalab.github.io/MinerU/reference/output_files/)：ZIP、结构产物、中间协议与生产者版本。
- [DocVortex 中间协议 schema](https://github.com/myhloli/DocVortex/blob/main/schemas/middle-2.0.json)：原始文本、内联元素和表格正文结构。

若未来实测显示 Docling 未覆盖特定版面问题，应根据对应原件、人工标注与原始产物补处理。当前没有实测证据，因此没有增加猜测双栏、推断公式或重排文本的生产逻辑。
