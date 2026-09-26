# B 站 Artifact 离线现状基线（2026-09-26）

这是 P0 的**离线回放基线**。测试里的 `BV1NoteWeaveDemo`、`BV1ArchiveFailure` 等均为虚构 ID；素材许可状态为“内部合成，未涉及第三方视频”。它们可以复现当前字幕和 PDF 控制路径，不能证明真实 B 站视频的内容质量、耗时或画面覆盖。真实短视频、无字幕视频、多分集视频与各自许可状态仍待冻结。

| 场景 | 固定回放入口 | 当前可观察结果 | 缺口 |
| --- | --- | --- | --- |
| 短字幕片段 | `workers/artifact-worker/tests/test_bilibili_render_pdf_mcp_server.py::test_get_bilibili_subtitle_should_fetch_remote_subtitle_artifact` | 两段 0–4 秒合成字幕，记录来源与 SRT 路径 | 无真实画面、分集信息、纠错稿 |
| 无字幕片段 | 同文件 `test_get_bilibili_subtitle_should_fallback_to_transcription_when_subtitles_missing` | 字幕列表为空时转到合成音频/ASR，产出 SRT | 不代表真实 ASR 准确度和成本 |
| PDF 导出 | 同文件 `test_render_latex_pdf_should_write_tex_and_real_pdf_artifacts`；`tests/test_export_runtime.py` | 模板生成与可打开的测试 PDF；导出 Trace 指向文件 | 当前导出只传章节标题、正文和 URL，没有画面清单 |
| 视频摘要、课程笔记 | `tests/test_runner.py` 中 `test_run_artifact_task_should_generate_multiple_default_artifacts` 与媒体用例 | 确定性测试 Provider 生成章节和运行 Trace | 非真实模型质量样本 |
| 多分集 | 尚无可回放样本 | 当前输入契约没有明确 `part`，无跨分集引用校验 | P2 前不能声称支持 |

复现命令（在 `workers/artifact-worker` 下，用 Python 3.12 与锁定依赖）：

```powershell
python -m pytest tests -q
```

本次执行使用已安装依赖，并将 `TMP`、`TEMP`、`NOTEWEAVE_MCP_SANDBOX_ROOT` 和 pytest `--basetemp` 指向可写目录。全套结果：**248 passed，14.84 秒**。最初直接在沙箱默认目录运行得到 5 failed、35 errors，错误源于临时目录与 MCP `runtime` 无写权限；改为可写测试目录后同一套测试全绿。没有运行真实 B 站下载或真实 Provider E2E，因此不报告视频处理耗时、实际 PDF 画面数或用户收益。

Host 现状回放为 `Phase6ResearchArtifactContractTest`（38 个用例，30 执行通过、8 个既有跳过）及 Candidate/回滚门禁测试。它使用 H2、合成 Worker PDF 和本地对象存储；已覆盖重复提交、再生成快照、缺文件拒绝发布、回滚文件先验、文件丢失后的 DEGRADED/恢复，不覆盖 MinIO/Kafka 跨进程故障。

## P1.5 冻结 Source 窗口回放

`Phase6ResearchArtifactContractTest` 后续增至 40 个用例（32 执行通过、8 个既有跳过）：后段事实可由冻结 Snapshot 的游标页读取；另建较新 Snapshot 后，旧 Run 仍返回旧 Snapshot；Source 删除后窗口读取与已发布文件下载均被拒。Worker `test_material_resolver.py` 验证跨页选中后段窗口、摘要不匹配拒绝生成、引用窗口 ID 进入 Trace。Worker 全套 **250 passed，17.04 秒**。这些是离线契约结果，不代表长文档选材的真实召回率；256 窗口扫描与 64 KiB 选择上限到达时会显式记录缺口。

## P1 文件 Manifest 与对账补测

Worker 在内容验证后对 Markdown 与实际导出的 PDF 字节形成必需文件清单；Host 在 READY 前复核大小、MIME、SHA-256 与 PDF 文件名。测试暴露了渲染器会规范化中文标题文件名、Worker 原来按标题猜文件名的问题，已改为使用渲染器实际返回的路径名。`tests/test_candidate_file_manifest.py` 覆盖文件摘要和沙箱外路径拒绝；`Phase6ResearchArtifactContractTest#candidateManifestMustMatchTheMarkdownPublishedByHost` 验证伪造摘要不产生 Version，改正后可以提交。此批 Worker 全套 **260 passed，10.89 秒**；Host Artifact 批次 `Phase6ResearchArtifactContractTest` **41 个用例，33 执行通过、8 个既有跳过**，`ArtifactCandidateTest` 与 `ArtifactRollbackGateTest` 各 2 个通过。

对账故障补测 `Phase6ResearchArtifactContractTest#reconciliationMustNotMarkVersionReadyWhenRequiredFileMetadataIsNotReady` **1 passed**：必需文件元数据不是 READY 时版本保持 DEGRADED，恢复元数据并核对摘要后才转 READY。对象存储与数据库跨进程崩溃时的孤儿暂存物回收仍未覆盖。

## P2 与 Context C0 的契约样本

`tests/test_video_material_bundle.py` **9 passed**：合成分集 2 的时间段、画面 File ID、摘要、知识节点引用、无字幕/无画面缺口与实际图片解码；这不是已接入生产的资料包采集或复用。`ContextProjectionV2ContractTest` **3 passed**，固定六种合成会话标注：A→B→A、相似实体异主题、否定更正、跨主题格式偏好、摘要滞后和删除脱敏。v2 Compiler、开关、快照写入与 Artifact 接入尚未实现。
