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

PDF 字节边界补测 `Phase6ResearchArtifactContractTest#pdfCandidateManifestMustMatchFetchedPdfBytesBeforeVersionPublication` **1 passed**：Worker 声明了 PDF 文件名和大小，但摘要与 Host 实际获取的可打开 PDF 不符时回调被拒，Version 数保持零。

## P2 与 Context C0 的契约样本

`tests/test_video_material_bundle.py` **9 passed**：合成分集 2 的时间段、画面 File ID、摘要、知识节点引用、无字幕/无画面缺口与实际图片解码；这不是已接入生产的资料包采集或复用。`ContextProjectionV2ContractTest` **3 passed**，固定六种合成会话标注：A→B→A、相似实体异主题、否定更正、跨主题格式偏好、摘要滞后和删除脱敏。v2 Compiler、开关、快照写入与 Artifact 接入尚未实现。

## P2 字幕原文与预览边界

现有获取结果若同时给出完整 SRT 和六行预览，Worker 原路径先采用预览。本批改为优先读取受控沙箱内的完整文件，并加入分集/时间段 SRT 解析、逐段纠错、原文与纠错稿分存的纯函数。外部 Custom MCP 的旧回调若只给预览或返回沙箱外不可读取的路径，仍可按兼容路径继续，但标 `FULL_SUBTITLE_FILE_UNAVAILABLE`，不把预览称作完整字幕，也不读取沙箱外文件。`test_video_subtitle_material.py` 与相邻 Acquisition/Runner 定向测试 **120 passed**；Worker 全套 **266 passed，12.17 秒**。此阶段还没有画面采集、Host 素材版本或 PDF Bundle 输入，因此 P2 交付门禁未通过。

Host/Worker Catalog 对照发现旧视频输入别名漂移：Worker 列了 `video_url`、`bilibili_url`，Host 只列 `url`。Host 现接受旧别名并在创建快照前归一为 `url`；冲突值拒绝。`Phase6ResearchArtifactContractTest#videoUrlAliasesNormalizeToOneFrozenUrlAndRejectConflicts` **1 passed**。Catalog 仍未统一为单一发布源和发布摘要，不能把这次对齐视为 P1 Catalog 门禁完成。

最终合并回归：Host `Phase6ResearchArtifactContractTest` **44 个用例，36 执行通过、8 个既有跳过**；`ArtifactCandidateTest` **2 passed**、`ArtifactRollbackGateTest` **2 passed**、`ContextProjectionV2ContractTest` **3 passed**；Worker 全套 **266 passed**。首次合并回归暴露旧别名出现在公开表单 Schema、与既有跨语言目录合同冲突；已改为内部接受别名、公开表单仍只显示 `url`，重跑 Host 合并批与 Worker 目录合同均通过。

## P1 发布 Catalog 源与摘要

新增 `reference/artifact-skill-catalog-v2.json` 作为已发布 12 个 Skill 的机器可读定义，构建时分别打包进 Host 资源和 Worker 应用；两端运行时从 JSON 加载。Graph、Prompt 与能力列表从当前生产 Action 注册表核对，旧别名仍仅在内部接收，公开表单保持原有 `url` 契约。双端测试比较三份文件的原始字节与 SHA-256，防止发布包漂移。此批 Worker 全套 **267 passed**；Host `ArtifactSkillCatalogPublicationTest` **1 passed**、`Phase6ResearchArtifactContractTest` **44 个用例，36 执行通过、8 个既有跳过**。当前摘要尚未绑定 Run/Candidate，因此不能声称发布版本冻结门禁完成。

后续绑定批：新 Run 把 Catalog SHA-256 写入其不可变输入快照的 `compiler_version`，再生成创建新 Run 时记录当次发布摘要；Worker 输入携带该值，执行前拒绝与本地发布包不一致的摘要，并在 Candidate 中回传；Host 对带 Candidate 的回调核对冻结摘要。旧的无摘要快照保留兼容。Worker 全套 **268 passed**；Host `ArtifactCandidateTest` **3 passed**、`ArtifactSkillCatalogPublicationTest` **1 passed**、`Phase6ResearchArtifactContractTest` **44 个用例，36 执行通过、8 个既有跳过**。

Candidate 必填门禁批：Host 对新 Run 缺 Candidate 的回调返回 `ARTIFACT_CANDIDATE_REQUIRED`，旧无摘要快照仍可按历史协议回放。第一次运行暴露 11 个合成成功回调仍使用旧协议；更新测试夹具为带内容验证与必需文件摘要的 Candidate，并固定测试 PDF 字节后，`ArtifactCandidateTest` **4 passed**、`Phase6ResearchArtifactContractTest` **44 个用例，36 执行通过、8 个既有跳过**。跨版本 Catalog 存档及已入库旧 Run 的可重放能力仍需独立验收。

必需文件角色批：Host 改读发布 Catalog 的 `required_file_roles`，在交付前核对角色全集，并据此判断 PDF 是否必需。首轮测试发现已发布版本追加 `SLIDE_PREVIEW` 时本批没有 Markdown；修正为现有 READY 文件与本批文件的合集，初次发布仍必须包含所有必需角色。`ArtifactSkillCatalogPublicationTest` **1 passed**、`ArtifactRollbackGateTest` **2 passed**、`Phase6ResearchArtifactContractTest` **44 个用例，36 执行通过、8 个既有跳过**。

对象暂存回收批：本地存储与 MinIO 增加受前缀和页大小限制的对象清单；Host 每轮最多扫描 1000 个 `artifacts/staged/` 对象，按游标续扫，只删除超过 24 小时且无 `artifact_file` 元数据引用的对象。故障测试覆盖旧孤儿、近期文件、已提交引用和前缀外文件。首次测试中后台定时任务先于显式调用删掉旧孤儿，使“本次删除数量”断言不稳定；改为断言最终文件状态。Host 全批 `Phase6ResearchArtifactContractTest` **45 个用例，37 执行通过、8 个既有跳过**；`LocalObjectStorageTest` **3 passed**、`MinioObjectStorageTest` **1 passed**。MinIO 真实服务的跨进程中断与恢复仍未实测。

Catalog 发布准入批：Worker 启动时核对每条定义的版本、字段、输入 Schema、Graph、Prompt、能力与当前生产 Action；Host 拒绝不支持的版本、未知字段、未知能力与输入类型。畸形版本、任意脚本字段、任意能力和对象类型输入的四种合成负例均被拒。Worker 全套 **272 passed**；Host `ArtifactSkillCatalogPublicationTest` **2 passed**、`Phase6ResearchArtifactContractTest` **45 个用例，37 执行通过、8 个既有跳过**。这仍不等于各产物的类型化输出 IR 已完成。

多文件 Manifest 批：Host 按 `(role, variant, sequence_no)` 核对 Candidate，并从 Worker 受控导出口获取额外的 PNG/PPTX/Markdown 文件，验证摘要、大小、MIME 与可打开性；Worker 仍限定任务沙箱内的文件名和扩展名。新增 PDF 加两张 PNG 预览的契约，篡改任一预览摘要时 Version 数保持零，完整清单提交后两张预览各自可下载。Worker 全套 **273 passed**；Host `Phase6ResearchArtifactContractTest` **46 个用例，38 执行通过、8 个既有跳过**、`ArtifactCandidateTest` **4 passed**、`ArtifactRollbackGateTest` **2 passed**。PPTX 渲染与多文件生产 Skill 尚未接入，不能把这项基础设施视为 P4 已交付。

## P2 字幕阶段素材冻结

Host 新增不可变 `artifact_video_material_bundle` 表及受 Outbox 投递令牌保护的内部提交/读取接口。当前仅接收字幕阶段的 `VideoMaterialBundle v1`：BVID、分集、Workspace、规范化 Run 输入摘要、时间段、原文/纠错稿和显式 `NO_FRAMES` 缺口均需匹配；同一 Task 的同内容重试返回原回执，异内容重试拒绝。Worker 增加相同的冻结输入摘要函数和素材包客户端。首次定向回归暴露测试用例按原始 URL 算摘要，而 Host 在快照中保存了归一化输入；改为读取冻结快照并重算后通过。

Host 全批 `Phase6ResearchArtifactContractTest` **47 个用例，39 执行通过、8 个既有跳过**，`ArtifactWorkerInputControllerTest` **5 passed**，`ArtifactCandidateTest` **4 passed**，`ArtifactRollbackGateTest` **2 passed**。Worker 定向 **19 passed**，全套 **275 passed，23.62 秒**。素材包提交尚未接入生产视频执行流程，也没有画面文件、知识树、Candidate 绑定或 PDF Bundle 复用；这些验收项仍未完成。

后续 Candidate 引用门禁批：同一任务已冻结素材时，完成回调必须在 Candidate 中引用准确的素材 ID、Bundle ID、版本和内容摘要；缺失或伪造引用均返回 `VIDEO_MATERIAL_REFERENCE_INVALID`，且不产生 Version，正确引用可发布。Host 全批 `Phase6ResearchArtifactContractTest` **47 个用例，39 执行通过、8 个既有跳过**，`ArtifactCandidateTest` **4 passed**，`ArtifactRollbackGateTest` **2 passed**。目前仍需解决异步 Provider 恢复时的投递令牌传递，才能把素材客户端接入生产执行流；通过引用门禁不代表素材采集已经自动运行。
