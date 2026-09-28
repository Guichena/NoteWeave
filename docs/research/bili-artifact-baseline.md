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

异步 Provider 与字幕自动冻结批：Host 请求 Worker 先持久化 Provider ACK、暂不在 ACK 调用内恢复，然后以当前 Outbox 投递令牌发起恢复；已终态 Task 的重复 ACK 不再恢复，暂无有效令牌则使 ACK 可重试。Worker 在完成回调前，仅从受控沙箱内的完整 SRT、实际视频时长和一致的 BVID/分集元数据建立字幕素材，并将 Host 回执写入 Candidate；预览、缺少时长或分集不符会跳过素材冻结。完整 Worker 恢复回放首次暴露 Host Run 无本地 Version ID 时仍更新 Worker 版本仓库，已修正为仅旧本地版本执行该 Trace 同步。修复后 Worker 全套 **279 passed，32.43 秒**；Host `Phase6ResearchArtifactContractTest` **47 个用例，39 执行通过、8 个既有跳过**，`ArtifactCandidateTest` **4 passed**、`ArtifactRollbackGateTest` **2 passed**、`ArtifactWorkerControlServiceTest` **3 passed**、`HttpArtifactWorkerControlClientTest` **2 passed**。回放使用合成字幕、Provider 回执和本地 PDF；真实 B 站获取、长期 Provider 等待、MinIO 故障与跨进程重试尚未实测。画面/知识树、素材跨任务复用及 PDF Bundle 输入仍未完成。

## C1 主题和约束影子投影

新增独立于旧前缀摘要的 v2 主题、连续片段和用户约束表；影子服务按 Conversation 行锁从消息 Ledger 重算并增量写入，A→B→A 可复用同一 `topic_id` 而维持三个不重叠片段，重复刷新不追加重复行。显式用户语句可记录格式、语言和否定约束；更正撤销同主题旧约束，助手文本不会成为约束，含糊“那个格式”保持 `UNRESOLVED`。删除消息时覆盖片段与锚点变 `STALE`，源约束撤销并清空文本。固定样本的首轮 Segmenter 测试曾把“对象什么时候回收？”误分为新主题，加入明确追问规则后通过。

这一批是**影子实现**：没有接入 QA/Note/Wiki/Research/Artifact 编译入口，不产生 v2 Run 快照；旧 v1 路径继续运行。主题判定目前是保守规则，不足以宣称 C1 完整验收；候选主题解释、迟到判定栅栏、删除后的全量重建与旧快照脱敏仍需后续工作。最终回归：`TopicSegmenterV2Test` **5 passed**、`ConversationConstraintProjectorV2Test` **2 passed**、`ContextProjectionV2ContractTest` **3 passed**、`ConversationTurnModuleContractTest` **38 passed**；Flyway v113 在 Spring 集成测试中成功应用。

## P1 类型化内容 IR 增量门禁

Worker 在内容/引用验证通过后，将产物类型、标题、章节、引用和已渲染 Markdown 摘要封装成 `artifact-content-v1`，再执行文件渲染；Candidate 带 IR 摘要。Host 对带 IR 的回调核对字段、章节与结果载荷、标题、Markdown SHA-256 和规范化 IR 摘要，不一致则拒绝 Version。固定 Java/Python 摘要向量一致。旧 Worker 的无 IR 回调仍兼容，**新 Run 尚未强制要求 IR**，因此不能把此增量视为所有历史 Skill 均已完成类型化输出迁移。Worker 全套 **280 passed，36.81 秒**；Host `ArtifactCandidateTest` **5 passed**，`Phase6ResearchArtifactContractTest` **47 个用例，39 执行通过、8 个既有跳过**。

## P2 画面素材 Host 暂存门禁

`V114__artifact_video_material_file.sql` 将画面文件元数据与用户产物 `artifact_file` 分开。Host 对收到的 Bundle 校验画面 File ID、格式、摘要、大小、分集、时间位置、去重引用、知识节点引用及显式覆盖缺口；从 Worker 受控导出接口读取 PNG/JPEG，实际解码并核对字节后写到独立的 `artifacts/video-material/` 前缀。文件与 Bundle 元数据同一数据库事务发布，回滚时清理已写对象；同内容重试返回原 Bundle 回执。读取文件仍需当前 Outbox 投递令牌，且再次核对存储摘要。

合成画面的 Host 契约测试覆盖成功冻结、重复提交、跨分集/时间引用拒绝、事务回滚后对象清理和文件读回。`Phase6ResearchArtifactContractTest` **48 个用例，40 执行通过、8 个既有跳过**；`ArtifactWorkerInputControllerTest` **5 passed**。随后增加独立前缀的 24 小时老化对象巡检：只删除没有 `artifact_video_material_file` 引用的旧画面对象，跳过已提交、近期和其他前缀的对象；`ArtifactVideoMaterialCleanupTest` **1 passed**，同批 Artifact 回归仍为 **48 个用例，40 执行通过、8 个既有跳过**、控制器 **5 passed**。尚未接入真实视频画面采集、视觉分析和 PDF 画面输入；这些不能算 P2 完成。

## P1/P2 冻结视频素材的 IR 必填门禁

Worker 已在内容校验后产生 `artifact-content-v1`；Host 原先只在回调携带它时验证。现在已冻结视频素材的 Candidate 必须同时携带 IR 与其摘要：缺失会在发布 Version 前拒绝，格式及内容一致性仍走 `ArtifactContentIr` 校验。历史无素材包回调继续兼容。定向合成回放覆盖缺 IR 拒绝、完整 IR 发布，`Phase6ResearchArtifactContractTest` **48 个用例，40 执行通过、8 个既有跳过**；`ArtifactCandidateTest` **5 passed**；孤儿文件回归 **1 passed**。这尚未将 IR 对所有新 Skill Run 设为必填。

## C2 影子窗口选择器

在 C0 契约和 C1 主题投影之上新增纯函数式 `ContextWindowPlannerV2`，只接收已冻结、已授权的消息/摘要/约束/Memory 引用。优先保留当前输入、适用的有效用户约束、传入的 Memory Revision 和最近 8 条连续原文；当前片段缺 READY 摘要时扩大原文，超过 256 条或预算不足会明确报错。同主题旧片段摘要只在其 READY、位于原文窗口之前且活动主题判定确定时纳入；异主题和不确定主题均留可解释的排除决定。预算暂以 UTF-8 字节数作为保守单位，尚未固定模型 tokenizer。

首轮测试发现活动片段为 `UNCERTAIN` 时仍带入旧同主题摘要，修正后 `ContextWindowPlannerV2Test` **3 passed**；同批 C0/C1 契约 **10 passed**，合计 **13 passed**。该组件还未读取数据库、做执行者权限检查、增量 Summary Revision、Memory 状态指纹或接入 QA/Note/Wiki/Research/Artifact；因此只是 C2 影子选择层，不能宣称任务上下文已迁移。

## P2 冻结画面读回客户端

Worker 新增按 Bundle 文件清单从 Host 受控端点读取画面的客户端，发送当前 Outbox 投递令牌与内部令牌，限制文件 ID、单文件和总大小，逐项核对 MIME，再按冻结清单验证字节摘要与图片可解码性。只通过 Host File ID 读取，不接受 Worker 绝对路径或外部 URL。定向回归 **17 passed**，Worker 全套 **281 passed，42.06 秒**。目前 PDF 生成还没有调用这条路径；这只是后续复用素材的受控读接口。

## P2 本地抽帧与 MCP 获取入口

新增受控本地抽帧模块与 `capture_bilibili_frames` MCP 工具：输入仅接受单一 BVID/分集 URL，先校验实际元数据的 BVID、分集与时长，再限制下载流大小、抽帧数、输出文件大小和沙箱路径。抽样覆盖开头及近结尾，`ffmpeg showinfo` 给出实际帧时间；只有像素完全相同才去重，保留重复关系、文件摘要和缺口。Worker Dockerfile 补 `ffmpeg` 与 Pillow。合成视频及模拟 ffmpeg/yt-dlp 的定向测试 **15 passed**；MCP 脚本 stdio `tools/list` 启动成功；Worker 全套 **285 passed，44.85 秒**。

这一批尚未接入系统 Capability 注册、独立可恢复 Operation Receipt、字幕素材合并、真实 B 站下载或 PDF 画面渲染。当前 Windows 回放环境没有 ffmpeg，因此抽帧测试使用受控假执行器；Docker 镜像依赖已补但尚未构建验收。不能据此声称 P2 视觉链路完成。

## P2 PDF 冻结画面输入与真实文件嵌入

PDF MCP 渲染输入扩展 `video_part`、`video_duration_ms` 与逐章节 `image_refs`。每个画面必须带 File ID、沙箱路径、SHA-256、分集和实际时间；渲染前验证权限边界内的文件、摘要、可解码 PNG/JPEG、分集/时间和大小，再复制到受控导出目录并复核摘要。XeLaTeX 路径生成 `\includegraphics`，ReportLab 路径实际绘图；无 ReportLab 时若有图则明确失败，不能输出伪称含图的 PDF。Worker 的 `export_artifact_if_required` 增加冻结 Bundle 输入分支，将标题一致的知识节点画面分配到章节，要求所有冻结帧均有章节归属并再次验证 Bundle 文件字节。

合成画面测试检查生成 PDF 中的 `/Subtype /Image`、TeX 图片命令、错误分集/摘要拒绝与 Bundle 输入映射；定向 **16 passed**，Worker 全套 **287 passed，32.74 秒**。当前主运行路径尚未从 Host 读取并传入冻结 Bundle，真实 B 站视频及 XeLaTeX 含图页尚未实测；不能把这一批等同于 PDF 生产链已接通。

## P2 跨任务冻结资料包复用

发布 Catalog 为 B 站 PDF Skill 增加可选 `video_material_bundle_id`；旧字段与必填 `url` 保持兼容。Host 只对冻结输入中明确指定该 ID、且 Workspace/BVID/分 P 一致的当前 Task 开放 Bundle 和画面文件读取；端点受当前 Outbox 投递令牌保护。Candidate 必须引用该 Bundle 的准确 ID、版本和摘要，并携带内容 IR。Worker 在编译前向 Host 获取资料包，把纠错字幕与时间段写入 CCO，采集计划改为本地规范化，不再发起 `EXTRACT_TRANSCRIPT`。若有画面，则按 Host File ID 读回并校验，缺少同名章节时为 PDF 补充有出处的知识节点章节，重新渲染并更新文件 Manifest。

首轮 Host 全批有 1 个旧目录断言失败，首轮 Worker 全批也有 1 个同类失败；两端旧 v1 契约断言已改为检查旧属性仍存在、旧必填项不变，并单独检查新增可选字段。最终 Host `Phase6ResearchArtifactContractTest` **48 个用例，40 执行通过、8 个既有跳过**，`ArtifactWorkerInputControllerTest` **5 passed**，`ArtifactCandidateTest` **5 passed**；Worker 全套 **289 passed，67.72 秒**，其中包含无 Provider 重提取的冻结字幕运行与含帧补章节测试。三份 Catalog 字节 SHA-256 一致。当前仍缺真实 B 站的字幕/抽帧链路、画面 Capability 的异步恢复、完整视觉知识树和真实 XeLaTeX/MinIO 端到端验收；此批仅闭合已有 Bundle 的安全复用。

## P2 知识节点画面引用闭包

Host 与 Worker 的 Bundle v1 校验现在要求每个画面恰好归属一个知识节点，且节点标题在去首尾空白与大小写归一后唯一，避免 PDF 按标题分派时重复或漏图。Host 定向合同测试补未分配画面、重复标题负例；Worker 模型测试补未分配、重复标题、重复画面三种负例。首轮 Worker 定向测试暴露旧客户端夹具含画面却无节点，补齐夹具后定向 **34 passed**。最终 Worker 全套 **292 passed，92.85 秒**；Host `Phase6ResearchArtifactContractTest` **48 个用例，40 执行通过、8 个既有跳过**。目前仍没有自动生成类型化知识树或视觉观察能力；该提交只锁定素材契约。

## P2 复用输入同一性

复查发现先前跨任务引用仅核对 Workspace/BVID/分 P，不足以阻止同一视频但字幕语言或采集选项不同的任务误用旧资料包。Host 现在比较父子 Run 冻结的规范化输入映射，排除仅用于选中已存资料包的 `video_material_bundle_id`；其余字段必须逐项一致。新增同视频同分 P、语言改为 `en` 的负例，仍允许原输入重用。首轮测试用了 Catalog 不允许的 `en-US`，创建时即被拒；改为目录允许的 `en` 后，Host 两个 P2 定向合同 **2 passed**。更大范围的 Host 合约回归在前一批已通过，本批未重跑全套。

Worker 复查又发现冻结 Bundle 与额外外部 URL 同时存在时，专用计划会错误覆盖外部读取所需的 `READ_WEB_PAGE`。现在只有单一冻结视频路由走免 Provider 计划；混合路由保留外部读取能力，并由现有 Action 权限策略拒绝 PDF Skill 不允许的额外网页读取。首轮测试原预期 PDF 可运行，实际按权限策略返回 `action_scope_denied`；修正为验证该拒绝后，Worker Callback 定向 **19 passed**。这避免了把额外来源当作已读取证据，也不触发视频字幕重抓。

## P2 抽帧回执转冻结 Bundle 候选

新增 Worker `merge_captured_video_frames`，把已有 MCP 抽帧回执与字幕阶段 Bundle 组合。它核对 BVID、分 P、实际时长、缺口和文件清单，从 Worker 沙箱读图并用 Bundle 契约校验真实图片字节，然后仅把 File ID、摘要和时间引用放入 Bundle；画面暂存到 Host 既有的受控任务导出口。每帧有一个按时间命名的证据节点，关联同时间的字幕段。这些节点是**时间段证据容器**，不冒充完成的语义知识树。重复调用内容摘要稳定，错误分集、文件摘要、沙箱路径和覆盖缺口均拒绝。定向合成测试 **18 passed**。这一批仍未把 `CAPTURE_FRAMES` 注册到生产采集计划、异步回执或字幕合并回调，不能声称自动采集已接通。

## P2 字幕后可恢复的抽帧阶段

发布 Catalog、生产 Action、Provider Registry 和系统 MCP 注册了 `CAPTURE_VIDEO_FRAMES`。仅新 `bilibili_course_note_pdf` URL 采集计划在 `EXTRACT_TRANSCRIPT` 后加入 `CAPTURE_FRAMES`，两个 Operation 各有独立请求、回调令牌与可恢复等待；已冻结 Bundle 输入仍不触发视频 Provider。画面调用最多 16 帧，固定 30 秒基础采样，MCP 自身仍校验实际分集、时长与实际画面时间。Worker 在两项回执齐备后把字幕与画面合成一次 Host Bundle 提交，逐帧暂存到受控导出口，再生成含图 PDF 和文件 Manifest。系统 Provider 恢复仍沿已有持久化 Operation Outcome 与 Host ACK；自定义字幕 Provider 的回执也可与内建画面 Provider 合并。

首轮 Worker 回归有 5 项旧测试假定字幕 ACK 即完成；改为分别断言字幕 ACK 后仍等待、不会提前提交素材/Version、画面 ACK 后完成。另有一次 Windows 临时配置文件替换拒绝，重跑未复现。合成回放用 PDF 解析器检查最终文件含真实图片对象，检查 Host 导出口的 PNG 字节与 Bundle 时间引用。最终 Worker 全套 **300 passed，43.03 秒**；Host `Phase6ResearchArtifactContractTest` **48 个用例，40 执行通过、8 个既有跳过**，`ArtifactSkillCatalogPublicationTest` **2 passed**，三份发布 Catalog 的 SHA-256 一致。真实 B 站抓取、Docker/ffmpeg、跨进程 Provider 崩溃恢复、MinIO 联调与语义知识树/视觉观察仍未实测或实现；时间节点当前仅做可追溯的画面分配。

## P2 视频采集策略冻结

PDF Skill 发布输入新增 `frame_density`（LOW/STANDARD/HIGH，默认 STANDARD）与 `asr_fallback`（ALLOW/DENY，默认 ALLOW）。Host 在 Run 输入快照冻结并用 Catalog 枚举校验；Worker 将策略解析为逐 Operation 的 `tool_arguments` 并持久化，字幕工具按策略决定是否允许转写兜底，抽帧工具按密度设置间隔与上限。历史资料包的复用比较忽略两个默认值，以便旧快照仍可被同设置的新 Run 引用；非默认值仍需严格相等。

新增 Host 契约验证有效值冻结与无效值拒绝，`Phase6ResearchArtifactContractTest#videoAcquisitionPolicyIsFrozenAndInvalidValuesAreRejected` **1 passed**。Worker Callback 定向 **21 passed**，全套 **301 passed，37.50 秒**。首次定向测试失败源于新增测试误嵌入相邻用例、并误读 `ArtifactTaskResult.status`；修正测试后通过。三份发布 Catalog 原始字节 SHA-256 一致。尚未在真实 B 站或 Docker Provider 环境验证这些策略的实际下载/转写效果。

## P2 不完整字幕回执发布门禁

自动视频采集完成时，若字幕 Operation 没有实际 Provider 回执，或回执只有预览、缺少可验证的完整字幕文件与视频元数据，Worker 会拒绝 Candidate 完成并向 Host 发送非重试任务失败。原先这两种情况会跳过素材冻结，仍可能发布没有 Bundle 引用的 PDF。明确返回 `no_subtitle_available` 且元数据完整的回执仍可冻结为带 `NO_SUBTITLE` 缺口的资料包；此门禁针对不完整或无法验证的回执。

合成测试覆盖预览、空回执、失败回调及既有异步恢复：Worker Callback **23 passed**，全套 **303 passed，40.45 秒**。本批未更改 Host 协议；真实 Provider 的 ASR 失败路径仍需有凭据的视频回放验证。

## P2 显式视频分集输入

PDF Skill 新增可选字符串 `part`，Host 仅接受 1–1000 的规范十进制值，并核对 B 站视频链接中已有的 `p` 参数。链接未带 `p` 且 `part>1` 时，Host 把该参数写进冻结 URL，让现有字幕/抽帧 Provider 读取同一分集；相互冲突的分集在创建 Run 前拒绝。资料包复用比较将与 URL 分集一致的显式 `part` 视为同一采集输入，使旧无 `part` 的冻结资料包可被新 Run 引用。

首次 Host 定向测试用了非 10 位 BVID 的旧演示字符串，被新增的真实 URL 校验拒绝；换为有效格式后通过。最终 Host `Phase6ResearchArtifactContractTest` **50 个用例，42 执行通过、8 个既有跳过**，`ArtifactSkillCatalogPublicationTest` **2 passed**；Worker Catalog **7 passed**，全套 **303 passed，46.20 秒**。三份发布 Catalog SHA-256 均为 `9C0CEE6E5928C36B60F539326B1D6D261A391D0F06836481C345FD9E6092E8C1`。尚未用真实多分集视频验证 Provider 回执与分集元数据。

## P2 受控画面文字观察工具与采集隔离

系统 MCP 增加 `analyze_frame` 工具入口，按 Task ID、画面 File ID 和冻结 SHA-256 读取 Worker 沙箱中的已暂存 PNG/JPEG；限制路径、符号链接、大小、像素和 OCR 输出。Tesseract 只产实际识别出的文字行、置信度和不确定标记；没有文字时记录 `NO_READABLE_TEXT`，始终记录 `VISUAL_SEMANTICS_UNVERIFIED`，不把 OCR 结果冒充代码、表格或图表的语义理解。Docker Worker 依赖增加 Tesseract 英文和简体中文包。另将抽帧 MCP 输出目录绑定 Task ID，避免同视频并发任务共享默认 BVID 目录。

定向合成测试验证正确摘要、错误摘要、越界标识、无文字、OCR 失败、MCP 调用参数及双任务目录隔离，**40 passed**；Worker 全套 **307 passed，39.36 秒**。Windows 测试使用注入式 OCR 回执，未运行真实 Tesseract；Docker 镜像也未构建。`analyze_frame` 尚未加入自动采集计划、独立 Operation Receipt 或冻结 Bundle，因此本批只是 P2 观察工具与隔离基础，不代表视觉观察链路已完成。

## P2 独立画面观察 Operation Receipt

PDF 的视频 URL 采集顺序现为 `EXTRACT_TRANSCRIPT` → `CAPTURE_FRAMES` → `ANALYZE_FRAMES`，三阶段各有独立的持久化请求、回调令牌和等待/恢复边界。`ANALYZE_FRAME` 能力绑定系统 MCP 的 `analyze_frames`，仅把已确认的抽帧文件 ID、SHA-256 和 Task ID 传给工具；工具从该 Task 的捕获目录读取，不接受回执提供的任意路径。Worker 完成 Candidate 前要求观察 ACK，逐项核对任务、文件全集、摘要、MIME、文字置信度、不确定标记及覆盖缺口，并把规范化观察回执摘要放入结果载荷。观察只是 OCR 文字证据；没有图表、代码或表格的可信语义判断。

首轮定向测试有 4 项仍以抽帧 ACK 作为完成点；改为观察 ACK 后提交，并核对观察 Operation 的工具参数与 Trace。首次 Worker 全套又有 2 个旧阶段断言失败；更新后全套 **310 passed，56.70 秒**。其后加强观察回执的字段、MIME 与低置信度校验，相关定向 **7 passed**。Host `Phase6ResearchArtifactContractTest` **50 个用例，42 执行通过、8 个既有跳过**，`ArtifactSkillCatalogPublicationTest` **2 passed**。三份发布 Catalog SHA-256 均为 `B16402352C2839CA29FF1535DBE4BD41E5B397C37335742C148A2B717581D186`。观察结果目前在 Operation Receipt/结果 Trace 中，尚未作为不可变 Bundle 的类型化字段、与字幕生成语义知识树或通过真实 Tesseract/Docker 视频回放；这些 P2 门禁仍未完成。

## P2 冻结资料包中的画面观察引用

在既有 `video-material-v1` 上增加可选 `frame_observations`；旧 Bundle 省略该字段，Worker 摘要仍按旧字段计算，保留历史资料包的内容身份。新自动采集任务在观察 ACK 校验后把每张画面的 OCR 文字、置信度、显式不确定与覆盖缺口写进一次性提交的 Bundle。Worker 与 Host 都要求观察文件集合恰好等于画面文件集合，并核对 Task、File ID、摘要、MIME、字段与文字类型；Host 在读取 Worker PNG/JPEG 时还将观察宽高与实际解码图像比较。新字段进入 Bundle 内容摘要，Candidate 仍引用整个冻结 Bundle 的准确摘要。

定向 Worker Callback/Bundle **40 passed**；Host 新契约验证错误画面摘要和伪造尺寸拒绝、有效观察随 Bundle 冻结。最终 Worker 全套 **311 passed，60.86 秒**；Host `Phase6ResearchArtifactContractTest` **51 个用例，43 执行通过、8 个既有跳过**、`ArtifactSkillCatalogPublicationTest` **2 passed**、`ArtifactWorkerInputControllerTest` **5 passed**。旧无观察字段的合成资料包合同仍通过。此字段只保存实际 OCR 文字证据，尚无可验收的图表/代码/表格理解或语义知识树；真实 Tesseract 与 Docker/MinIO 端到端仍未验收。

## P1 新 Run 类型化内容 IR 必填

Host 对带发布 Catalog 摘要的新 Run，要求 Candidate 同时携带 `artifact-content-v1` 和准确的 `content_ir_digest`；缺失在认领完成任务及创建 Version 前返回 `ARTIFACT_CONTENT_IR_REQUIRED`。旧无发布摘要的历史快照仍可按原回调回放。视频素材引用检查先于 Candidate 检查，保持缺失/伪造素材引用的专用错误；Worker 原有全 Skill 内容生成路径已在验证通过、文件渲染前生成 IR。测试新增缺 IR 不产生 Version、完整 IR 可提交的 Host 合同，更新合成 Worker 回调夹具。

首次全批有 15 项旧合成回调因无 IR 而失败；迁移共享夹具后仅两项直接 Manifest 回调未携带 IR，补齐后通过。再一次全批出现既有孤儿对象巡检测试的一次调用未扫到目标：巡检是游标分页，测试改为最多 100 轮内验证最终清理且仍保留近期/已引用/前缀外对象。最终 Host `Phase6ResearchArtifactContractTest` **52 个用例，44 执行通过、8 个既有跳过**，`ArtifactCandidateTest` **6 passed**，`ArtifactRollbackGateTest` **2 passed**，`ArtifactSkillCatalogPublicationTest` **2 passed**，`ArtifactWorkerInputControllerTest` **5 passed**。类型化 IR 目前仍是通用章节契约，`artifact_type` 尚未与每个 Skill 的专用输出 Schema 绑定；P3/P4 的产物专用 IR 不因此视为完成。

## P2 类型化知识规划契约（Worker 基础）

新增独立的 `video-knowledge-plan-v1` 模型和生成入口，规划对象以冻结 Bundle 内容摘要、BVID、分 P 和时长绑定素材，避免把知识计划嵌入 Bundle 后形成循环摘要。模型要求唯一主题根节点、合法父子层级与时间包含关系，全部字幕与画面被引用，画面只归一个节点。术语必须出现在所引纠错字幕或 OCR 文字中；`EXTRACTED` 主张须逐字落在合法引用中；没有证据的主张须标记 `UNVERIFIED` 和显式缺口。LLM 返回 JSON 后再做本地校验；空响应、越界响应、错误摘要与跨分集引用不进入知识规划。画面 OCR 仍只代表文字观察，不证明图表或代码语义。

定向 Worker **13 passed**；Worker 全套 **324 passed，35.43 秒**。目前这是独立的 Worker 规划契约，尚未由 Host 保存/发布不可变规划版本，也未接入现有 PDF 或 P3/P4 生成链；因此不能称为已完成的共享语义知识树。真实模型、B 站与视觉观察回放仍待验收。

## P2 知识规划的 Host 冻结与读取门禁

新增 `artifact_video_knowledge_plan` 表，每个已冻结 Bundle 只允许一个不可变规划版本；同摘要重试返回原回执，不同内容冲突。Host 独立复核规划的 Bundle 摘要、分集、时间、树层级、字幕/画面覆盖和逐字证据，拒绝伪造 OCR 主张。Worker API 的提交与读取受 Outbox 投递令牌保护；跨任务读取沿用 Bundle 对 Workspace、视频、分 P 和冻结输入的约束。Worker 回调客户端已提供提交和读取适配方法，现有 PDF 默认运行尚不生成此规划。

Host 编译通过；`Phase6ResearchArtifactContractTest` **52 个用例，44 执行通过、8 个既有跳过**，`ArtifactWorkerInputControllerTest` **5 passed**；新增错误分集读取门禁定向 **1 passed**。Worker 全套 **324 passed，33.65 秒**。当前缺自动规划任务的调用和失败恢复，也没有供博客/问答/PPTX 共同消费的发布路径；本批只闭合独立存储与访问契约。

## P2 本地证据索引的运行时冻结

视频自动采集在 Bundle 冻结后，用本地确定性代码建立 `TOPIC` 根节点和按画面、字幕分组的 `EVIDENCE_WINDOW`；节点只索引时间和引用，不产术语、语义断言或画面解释，因此不能称为类型化语义知识树。Worker 先读取已有 Host 规划，重试时复用并复核 Bundle 摘要；首次运行将本地索引提交到 Host。超过节点上限或规划服务暂不可用时记录 `knowledge_plan_gap`，保留旧 PDF 发布路径。此步骤没有把字幕或 OCR 送到外部模型。

第一次 Callback 定向测试有 3 项模拟 Host 未实现新读取端点，夹具补齐后 Callback + 规划契约 **38 passed**；最终 Worker 全套 **328 passed，35.85 秒**。自动审批明确拒绝了把冻结字幕和 OCR 资料接入外部 LLM 规划的运行时改动，理由是外部接收方及资料外发范围尚未获明确授权；因此外部语义规划保持未接通，后续若需要该能力必须先获得授权。P3/P4 仍未实现，当前本地索引不能替代语义树。

## C2 影子窗口的连续覆盖修正

影子编译器原先只要求当前主题摘要结束于 Raw Tail 之前，可能在摘要末尾与最近 8 条原文开头之间漏掉未总结的消息。现在要求当前主题摘要从活动 Segment 起点开始、恰好覆盖到 Raw Tail 前一条；否则从活动 Segment 起点扩展原文，仍受 256 条和必需信息预算门禁约束。回归分别验证有缺口时扩展，以及摘要连续时维持 8 条 Raw Tail。`ContextWindowPlannerV2Test` **5 passed**，`ContextProjectionV2ContractTest` **3 passed**，`TopicSegmenterV2Test` **5 passed**。这仍是 C2 影子选择器，尚未接入 QA/Note/Wiki 或任何 Run 快照。

## C2 v2 主题摘要修订与失效

新增独立的 `conversation_topic_summary_revision_v2` 迁移。影子主题投影刷新后，针对可信片段和最近 8 条原文之前的覆盖区间建立 BUILDING 修订、任务及出箱消息；Kafka v2 路由从冻结消息生成确定性摘录，发布时复核当前消息序列与正文摘要、片段/主题状态，并校验摘录正文与 SHA-256。删除被覆盖消息会清空修订正文、标记 STALE 并使引用它的 Run 回放脱敏；旧 v1 摘要路由保持原样。测试时发现历史账本的 `content_hash` 与当前正文存在不一致，因此 v2 冻结指纹以实际读取的正文重新计算，不依赖该旧字段。

首轮完整回归 **51 tests，1 error**，错误为上述旧 `content_hash` 不一致；调整后定向 **15 passed**，随后新增伪造正文拒绝断言，最终 `ConversationTurnModuleContractTest` **38 passed**、`KafkaTaskConsumerTest` **9 passed**、`ContextWindowPlannerV2Test` **5 passed**，合计 **52 passed**。v2 构建目前由显式影子刷新触发；窗口尚未从数据库编译任务输入，也未接入 QA/Note/Wiki/Research/Artifact，因此 C2/C3 仍未完成。

## P1 内容 IR 与冻结 Skill Action 的一致性

Host 原本只校验通用 `artifact-content-v1` 的内容摘要、Markdown 摘要和章节，却不检查 `artifact_type` 是否属于当前 Skill。现在对 Run 快照中记录的 Catalog 摘要与当前发布目录一致的任务，在 Candidate 提交和回放两条路径上，用目录中的 Action Key 校验 `artifact_type`；旧目录摘要不一致的历史 Run 继续按原有内容门禁回放。现有 `artifact_job.action_key` 在 Skill 创建路径为空，不能作为该门禁的依据；首次测试据此修正为读取已冻结 Catalog 摘要并查对应目录绑定。

新增摘要正确但 `artifact_type` 错误的完成回调，确认 Host 返回 `ARTIFACT_CONTENT_IR_INVALID` 且没有产生 Version；同时修正 B 站 PDF 测试夹具，使其使用目录定义的 `COURSE_NOTES`。首轮 Host 全批因测试夹具将类型写成旧内部名有 1 项失败，修正后 `Phase6ResearchArtifactContractTest` **52 个用例，44 执行通过、8 个既有跳过**，`ArtifactCandidateTest` **6 passed**。P3/P4 的专用内容字段、章节结构和文件契约仍须另行实现；此批只锁定当前通用 IR 的 Skill 身份。

## P1 章节证据引用必须对应本次资料

Worker 原先把任何非空 `source_refs` 都统计为已覆盖，即使引用标题不在本次取得的资料中。现在覆盖统计只接受本次 Canonical Content Object 的标题，且该 CCO 必须带 `source:` 来源 trace；未知标题标为 `INVALID_REF`，章节计入缺证据。合并 CCO 可作为引用标题，但必须能追溯到其组成来源，不能把没有来源的任意合并标题当作证据。外部来源与已入库 Source 使用同一检查。

首轮 Runner 105 项中 14 项失败，原因是现有生成节点合法引用了 `Mixed Context Bundle`；核对运行时 CCO 的来源 trace 后补充该允许条件。最终 Runner **105 passed**，Worker 全套 **329 passed，36.24 秒**。此检查证明引用标识存在于本次 Worker 已取得的资料，不证明正文所有事实正确；P3/P4 仍需逐条主张与具体证据的专用校验。

## C2 数据库影子编译入口

新增只读 `ConversationContextCompilerV2Service`，以 Workspace 成员权限和当前登录执行者校验为入口，仅允许在当前消息 Head 冻结完整、无删除/待处理消息的 Ledger 前缀。它读取 v2 主题片段、READY 摘要、用户约束和 Runtime Memory 当前有效修订，再调用纯窗口选择器；无会话的独立请求只带当前输入和有效 Memory。片段缺口、陈旧投影、旧 cutoff 和已删除消息显式失败，防止从后来扩展的主题投影重建旧 Run 的历史上下文。契约在 A→B→A 样本上验证 8 条连续 Raw Tail、早期同主题摘要、执行者不匹配、旧 cutoff 和删除后的拒绝。

定向契约 **1 passed**；完整 `ConversationTurnModuleContractTest` **38 passed**、`ContextWindowPlannerV2Test` **5 passed**、`ContextProjectionV2ContractTest` **3 passed**，合计 **46 passed**。结果仍仅是影子编译，没有持久化 v2 Run 输入快照或切换生产 QA/Note/Wiki/Research/Artifact；Memory 撤销后的新编译由 Runtime 当前状态过滤，已冻结任务的撤销传播需在 C3 单独验收。

## C2 用户约束的主题作用域

核对投影器发现它生成的 `CURRENT_TOPIC` 约束原先不会被窗口选择器纳入，导致“改用中文”等明确更正在同主题回跳时漏失。选择器现按来源消息所在 Segment 与当前 Segment 的可信主题 ID 比较；`GLOBAL`、`CONVERSATION` 和与任务目的显式相同的作用域分别处理，不把另一主题的格式要求误带入。A→B→A 测试验证 A 的规则被保留、B 的规则被排除、会话级规则被保留。`ContextWindowPlannerV2Test` **6 passed**，数据库影子编译定向契约 **1 passed**。更宽的 `TECHNICAL_ANSWERS` 等语义作用域仍要求调用者明确提供相同任务目的；尚未做自然语言适用性推断。

## C3 Answer 冻结时点的前缀门禁

现有 Answer `run_input_snapshot` 在 USER Query 消息的序号处冻结，此时后一条 ASSISTANT 占位还处于 PENDING。新增 `refreshForInputCutoff`：锁定会话后确认只有紧随 USER Query 的 PENDING 助手占位，按 cutoff 投影，摘要队列也只取 CURRENT 前缀。数据库影子编译器允许这一受约束边界，拒绝已完成助手后的旧 cutoff、非连续消息和删除消息；不会把占位内容纳入上下文。定向契约 **1 passed**，完整 `ConversationTurnModuleContractTest` **39 passed**、`ContextWindowPlannerV2Test` **6 passed**、`ContextProjectionV2ContractTest` **3 passed**，合计 **48 passed**。这只是 C3 输入时点的前置门禁；尚未在 Run 中持久化 v2 Context 或切换生产 v1 快照。

## C4 固定标注样本的影子选择基线

新增直接读取 `context-v2-gold-v1.json` 的离线回放测试，对五个可编译场景计算 Raw Tail、用户约束、Memory Revision 与可用主题摘要的误选/漏选计数。删除样本不送入正常编译器，继续由脱敏契约处理。五个样本共 **0 Raw Tail 不一致、0 约束不一致、0 Memory 不一致、0 主题摘要不一致、0 超预算**。首轮回放与 v2 Schema/选择器测试 **10 passed**；补入摘要选择计数后的回放与选择器 **7 passed**。这是合成标注的离线门禁，未比较真实 v1/v2 Run、未测真实用户误选率，也未开启 Workspace 灰度。

## C3 v2 冻结视图的确定性脱敏

`ContextProjectionV2.redacted()` 保留消息、摘要、约束、Memory 和决策的结构化标识，同时清空当前输入、原文、摘要、规则及 Memory 正文，更新文本摘要，移除可能携带敏感词的决策原因与降级详情，并转成 `METADATA_ONLY`。重复脱敏幂等。`ContextProjectionV2ContractTest` **4 passed**、窗口选择器 **6 passed**、固定样本回放 **1 passed**，合计 **11 passed**。这只是未来持久化快照可复用的脱敏函数；v2 Run 快照表和实际删除传播尚未接入，不能把它当成已完成的 C3 回放门禁。

## C3 Answer 影子 Run 快照与精确引用脱敏

新增 `context_v2_shadow_snapshot` 与引用索引表，开关 `noteweave.context.v2.shadow-enabled` 默认关闭。启用后，Answer 准备事务提交、助手生成前冻结 USER Query 截止的 v2 Context，记录编译器版本、完整 JSON、SHA-256 和消息/摘要/Memory 修订引用。影子编译失败由独立事务记为 FAILED，原 Answer 继续使用 v1；默认关闭时不创建影子记录。消息删除会与冻结共用 Conversation 行锁，对命中的影子快照写回无正文的 `METADATA_ONLY` 投影；Memory 选中修订在写入前重新加锁校验，撤销和 Summary 失效通过精确引用索引触发同一脱敏过程。没有把影子投影交给生成模型或 Worker。

定向契约验证正常冻结、SHA-256、助手占位排除、Query 删除后正文清空与重新计算 SHA-256，以及模拟预算错误时 Answer 的 v1 快照仍写入。最终 `ContextV2ShadowSnapshotContractTest` **2 passed**、`ConversationTurnModuleContractTest` **39 passed**、构造/命令测试 **3 passed**、`RunReplayRedactionServiceTest` **3 passed**、窗口选择器 **6 passed**、v2 投影契约 **4 passed**，合计 **57 passed**。尚未验证真实 MySQL 并发撤销与 MinIO/Kafka 跨进程时序，未开启 Workspace 粒度灰度；QA/Note/Wiki 的生产上下文仍是 v1，Research/Artifact 的 v2 冻结仍未接入。

## C3 Memory 撤销与旧主题摘要删除传播

沿实际 Memory 审核 API 验证 ACTIVE 修订进入 Answer 影子快照后，复审 REVOKE 会保留 Revision 标识并清空快照里的 Memory 文本；沿 A→B→A 会话验证 READY 的早期 A 摘要被回跳 Answer 选中后，删除摘要覆盖的旧消息会使修订 STALE、快照 REDACTED，且冻结摘要正文无法再回放。首轮 Memory 测试返回 `MEMORY_RUNTIME_REVISION_NOT_REVIEWABLE`：当前审核规则只接受 `REVIEW_REQUIRED` 或 `STALE` 的 ACTIVE 修订复审；测试先置入合法复审状态再撤销，没有放宽产品门禁。冻结与删除共用 Conversation 锁，Memory 修订在影子快照提交前加锁复核，减少撤销和冻结交错时的敏感文本残留风险。

定向 Memory 撤销 **3 passed**，旧摘要删除 **1 passed**；最终 `ContextV2ShadowSnapshotContractTest` **4 passed**、`ConversationTurnModuleContractTest` **39 passed**、`MemoryRuntimeContractTest` **16 passed**、`RunReplayRedactionServiceTest` **3 passed**，合计 **62 passed**。真实 MySQL 多进程竞态仍需故障注入验证；影子投影尚未用于回答生成或 Worker。

## C4 Workspace 影子开关与全局关闭门禁

新增 `context_v2_workspace_rollout` 和管理员读写接口，缺省 `OFF`，目前只允许 `OFF`、`SHADOW`；`ACTIVE` 明确拒绝。全局配置 `noteweave.context.v2.shadow-enabled:false` 仍是总闸，Workspace 设为 SHADOW 也不能绕开全局关闭。影子快照服务仅在两层开关都允许时运行。启用、关闭、再次启用与 ACTIVE 拒绝在同一 Workspace 会话中回放；默认全局关闭的会话测试也把 Workspace 设为 SHADOW，确认不会产生影子记录。

最终 `ContextV2ShadowSnapshotContractTest` **5 passed**、`ConversationTurnModuleContractTest` **39 passed**、命令构造 **3 passed**、回放脱敏 **3 passed**，合计 **50 passed**。这是影子采样开关，尚无生产 v2 Context 消费、v1/v2 实际任务输入差异指标或 Workspace 灰度放量门禁。

## C4 实际 Answer 冻结引用差异读取

新增管理员只读接口，对同一 Answer Run 的 v1 `run_input_snapshot` 与 v2 影子快照逐类比较消息、摘要和 Memory Revision 标识，并返回编译器版本、回放状态、影子失败原因及 v2 约束数量。响应只含引用标识和计数，不返回正文；缺少影子快照、编译失败和 v1 未就绪均有明确状态。读取前重新校验 v2 冻结 JSON 的 SHA-256，篡改摘要返回 `CONTEXT_V2_SHADOW_CORRUPT`。接口把 `accuracy_status` 标为 `GOLD_LABELS_UNAVAILABLE`，因此差异不能自动判定为误选或漏选。

READY、FAILED、REDACTED、OFF 和摘要篡改的定向契约通过；最终 `ContextV2ShadowSnapshotContractTest` **5 passed**、`ConversationTurnModuleContractTest` **39 passed**、固定样本回放 **1 passed**，合计 **45 passed**。这只是可核对的 v1/v2 引用差异，不是 C4 的真实用户正确率、灰度基线或生产 v2 启用门禁。

## P3 两种文字产物的冻结证据 IR 基础

新增 `VideoDerivedTextV1`：博客章节与面试问答各有专用结构、独立内容摘要和 Markdown 摘要，问答固定简答、详答、相关知识。两种产物均从同一份冻结 `VideoMaterialBundleV1` 与 `VideoKnowledgePlanV1` 派生，只复制已被知识计划校验过的 `EXTRACTED` 主张及证据引用；`UNVERIFIED` 只呈现明确缺口。本地证据窗口计划没有语义主张，会被拒绝生成，避免空索引被误当作学习文章或问答。Verifier 对照冻结 Bundle/Plan、重新确定性派生并比对 Markdown，篡改主张、引用或渲染会失败。

首轮定向测试 **3 failed、1 passed**，定位到构造预览 IR 时嵌套模型未被解析；修复后另有 **1 failed、20 passed**，是伪造测试未同步更新 Markdown 摘要，随后改为完整伪造场景。最终定向 **21 passed**，Worker 全套 **333 passed，34.66 秒**。此批只是 P3 的内容契约基础；尚未注册生产 Skill、创建独立 Job/Version 或接入本地 Repair。外部语义规划的数据发送仍未获授权，本地证据窗口不足以触发这两种产物的 READY。

## P2/P3 冻结视频资料读取完整性

Host 从 `artifact_video_material_bundle` 和 `artifact_video_knowledge_plan` 读取已冻结 JSON 时，现会用规范 JSON 重算 SHA-256 并与入库摘要比较。原始 Task 与受控引用 Task 共用这条读取门禁；库内 JSON 被改写时返回明确的 `VIDEO_MATERIAL_DEGRADED` 或 `VIDEO_KNOWLEDGE_PLAN_DEGRADED`，不继续把损坏资料交给派生产物。新增故障回放，分别篡改计划和资料包 JSON，并验证恢复原内容后可以读取。

定向 Host 测试 **1 passed**；Artifact 契约回归 `Phase6ResearchArtifactContractTest` **52 个用例，44 执行通过、8 个既有跳过**，`ArtifactCandidateTest` **6 passed**、`ArtifactRollbackGateTest` **2 passed**、`ArtifactWorkerInputControllerTest` **5 passed**。这验证读取时的内容完整性，不代表 P3 已接入独立 Job、Version 或 READY。

## P3 博客与问答的独立生产链路首批

在权威 v2 Skill 目录、Host 和 Worker 三份相同字节目录中注册 `knowledge_blog`、`interview_qa`，各要求 `url` 与冻结 `video_material_bundle_id`，共享同一采集语言范围校验。Worker 在执行前通过 Host 受控引用端点读取 Bundle 和已冻结知识计划，用专用确定性分支分别生成专用 IR、Markdown、通用内容 IR、Worker Candidate 与 Markdown 文件清单；该分支不调用外部 LLM。本地证据窗口计划没有语义主张时直接失败。Host 完成回调在发布前复核 Bundle/Plan 摘要、知识计划证据、派生 IR 的章节或问答结构、逐条主张、术语、缺口、引用、Markdown 和通用章节。旧 B 站 PDF 的帧渲染仍只在 PDF Skill 上触发。

Host 集成回放创建两个独立 Job，引同一个 PDF Job 冻结的素材和语义计划；博客先成功生成 READY Version 和 READY Markdown，伪造问答简答的回调被拒且未影响博客版本，修复后的问答回调再生成自己的 READY Version 和 READY Markdown。初次目录回归因权威参考目录仍是旧字节、旧测试要求 Skill 集合完全相等而 **2 failed**，更新参考目录和兼容断言后通过；初次跨 Skill 引用因新 Skill 缺少冻结采集语言而失败，补入同一默认语言后通过。Worker 全套首次 **2 failed、333 passed**，失败均为旧目录精确数量断言；最终 **336 passed，32.95 秒**。Host 最终 `Phase6ResearchArtifactContractTest` **52 个用例，44 执行通过、8 个既有跳过**，另有 Candidate **6 passed**、回滚 **2 passed**、Worker 输入 **5 passed**、目录 **2 passed**、专用验证器 **1 passed**。

此批证明手工已冻结且含 `EXTRACTED` 主张的计划可派生独立版本。生产自动规划仍只冻结无语义主张的本地证据窗口，因外部语义规划数据外发未获授权，不会自动生成文章或问答 READY；当前输出是带证据的确定性摘录模板，尚缺完整的文章化写作、局部 Repair、模板语言本地化和 P3 的真实视频验收，不能据此宣称 P3 全部完成。

## P3 模板语言绑定与节点局部 Repair

专用派生 IR 现在显式冻结 `language`；中文、英文和中英双语分别渲染来源摘要、术语、证据、缺口和问答三段式标题。Worker 从已冻结 Skill 输入取语言，Host 发布前核对语言与该 Run 的输入一致，并校验派生标题等于通用内容 IR 标题。局部 Repair 接口只接受与原 Bundle/Plan/Skill/语言/标题完全相同的草稿，逐节点对照已核验的 `EXTRACTED` 主张，最多重建 3 个错误节点或术语项；外来节点、重复节点和身份篡改直接失败。Worker 执行函数在收到内部草稿时走这条 Repair 路径并记录动作，最终仍由证据和 Markdown Verifier 复核。

语言与 Repair 定向 Worker **12 passed**，Host 本地化发布与篡改门禁定向通过；最终 Worker 全套 **341 passed，39.55 秒**。Host Artifact 回归 `Phase6ResearchArtifactContractTest` **52 个用例，44 执行通过、8 个既有跳过**，Candidate **6 passed**、回滚 **2 passed**、Worker 输入 **5 passed**、目录 **2 passed**、专用验证器 **1 passed**。目前生产生成器是确定性派生，不产生需要修复的模型草稿；局部 Repair 的故障回放通过内部草稿入口验证，真实模型 Repair Yield 尚无样本。文章化写作和真实视频验收仍未完成。

## P4 原图 PPTX 的页清单与结构校验基础

新增 `VideoDeckIRV1`，从冻结视频资料包和知识计划确定性生成页序、原画面文件摘要、时间、节点、已抽取主张与证据引用。资料只有画面索引而没有语义主张时拒绝成稿。固定模板以 `python-pptx` 嵌入原始画面字节，按长宽比适配且不裁边；标题、主张和证据备注保持可编辑。写出后重新打开 PPTX，核对页数、尺寸、每页画面 SHA-256、裁切/出界和文字。篡改页画面引用与文件字节的故障用例均被拒绝。

定向 **4 passed，0.48 秒**；Worker 全套 **345 passed，32.66 秒**。当前机器未装 LibreOffice，尚未生成或人工检查实际逐页 PPTX 渲染预览；固定文本长度门禁也不能代替视觉溢出检查。因此这批只证明 IR 与 PPTX 结构基础，未注册生产 `video_learning_deck` Skill，也未把 PPTX 或预览纳入 Host 文件提交门禁。

## P4 逐页预览的转换与故障门禁

新增隔离 LibreOffice Profile 的 PPTX→PDF 转换入口，并使用 Poppler 检查 PDF 页数、逐页输出 PNG、重新解码图片、核对最低尺寸；转换器失败、缺页、多页或损坏页均拒绝。当前开发机没有 LibreOffice，所以自动测试使用生成的 PDF 验证逐页栅格化及多页拒绝，定向 **5 passed，1.64 秒**；Worker 全套 **346 passed，33.88 秒**。另外用本机 PowerPoint 对相同的合成 PPTX 实际转成 PDF，再走相同栅格化入口得到一张 PNG，人工查看标题、原画面、主张和页脚均可见且无明显溢出。PowerPoint 冒烟验证不能替代部署环境的 LibreOffice 回归；上线还需固定真实视频样本与逐页视觉验收。

## P4 冻结资料到 PPTX Candidate 的 Worker 路径

新增 `video_learning_deck` 专用 Worker 入口，从 Host 冻结 Bundle/Plan 生成通用内容 IR、页清单和 Markdown；回调前按受控文件 ID 读取画面并与冻结摘要核对，渲染原图 PPTX 及逐页预览，最后把 Markdown、PPTX 与每页 PNG 的角色、序号、MIME、大小和摘要写入同一个 Candidate。清单在 Worker 侧打开 ZIP 和 PNG、检查每页对应的文件数与路径。若预览转换失败，不发送完成回调。这里的测试用合成 PDF/PNG 代替缺失的 LibreOffice，明确只覆盖 Candidate 组装；实际转换另由上一批的 PowerPoint 冒烟记录覆盖。

定向 **7 passed，0.79 秒**；Worker 全套 **348 passed，33.42 秒**。Host 对页清单的专用验证、发布目录注册、真实视频与部署转换器验收仍待完成，因此这批尚未开放生产 Skill。

## P4 Host 页清单与冻结证据复核

新增 Host `VideoDeckValidator`，以 Host 已入库的 Bundle/Plan 重建每页的节点、时间、原画面文件 ID/SHA、已抽取主张、证据引用与缺口，并校验 Worker 页序、IR 摘要、Markdown、通用内容章节、导出回执和每页文件角色清单。受控引用读取允许 `video_learning_deck` 作为独立 Skill 使用相同冻结资料。Python 产生的跨语言固定 Candidate 在 Java 验证通过；伪造画面文件 ID 和删掉 PNG 预览均被拒绝。尚未校验 PPTX 内部逐页画面与预览 PNG 的语义对应，因此暂不注册生产 Skill。

`VideoDeckValidatorTest` **1 passed**；`Phase6ResearchArtifactContractTest` **52 用例，44 执行通过、8 个既有跳过**；`ArtifactCandidateTest` **6 passed**；`ArtifactRollbackGateTest` **2 passed**。Host 编译通过。部署转换器和真实视频页预览仍未验收。

## P4 PPTX 内部画面与可编辑文字门禁

Host 在 Candidate 文件预检中解析受限 OOXML ZIP，要求 PPTX 页数等于冻结页清单，每页只有一张原画面，图片关系指向内嵌媒体，媒体 SHA-256 等于对应冻结画面摘要，无裁切参数，并含页标题和已抽取主张的可编辑文字。ZIP 条目数量、展开大小和 XML 外部实体均受限。用 Python 实际渲染的固定 PPTX 验证通过，改写 PPTX 内图片字节后即使外层文件可重新计算摘要也被拒绝。Worker 镜像定义补入 `python-pptx`、LibreOffice Impress 和 Poppler。

`VideoDeckFileVerifierTest` **1 passed**，`VideoDeckValidatorTest` **1 passed**；Artifact 完成契约 **52 用例，44 执行通过、8 个既有跳过**，Candidate **6 passed**，回滚 **2 passed**。本机 Docker daemon 未运行，未构建或验证更新后的 Worker 镜像；当前开发机也未装 LibreOffice。PNG 预览是否忠实对应 PPTX 仍需实际转换和视觉验收，固定真实视频样本未取得，生产 Skill 暂未注册。

## P4 生产目录与独立版本发布回放

在三份逐字节相同的 v2 发布目录中注册 `video_learning_deck`，要求 URL、语言和受控 `video_material_bundle_id`，声明 Markdown、原图 PPTX 与逐页 PNG 角色。Worker 生产 Action/Graph/Recipe 走冻结资料的专用分支，IR 现显式冻结 `zh-CN | en | zh-EN`，中文/英文/双语的时间、原画面、证据、缺口标签和无语义主张提示相应本地化；Host 核对 IR 语言与 Run 输入相同。修复 Host 旧导出解析把任意 `COMPILED` 都当成 PDF 的冲突：PPTX 从 Candidate 的 `PRIMARY_PPTX` 角色读取，旧 PDF 文件名路径保持原语义。

Host 集成回放从一份冻结 Bundle 与含语义主张的 Plan 创建独立 PPTX Job：伪造画面文件 ID 的回调返回冲突且未生成版本；合法回调生成 READY Version，Markdown/PPTX/预览三个文件角色都为 READY，PPTX 可按 file ID 下载，回滚后第二版仍保留三类文件。首轮端到端测试因 Python 固定 Bundle 把未执行 OCR 的 `frame_observations` 写成 `null`，而 Host 要求该可选字段缺席或完整覆盖全部帧，出现 **1 error**；修正夹具后定向回放通过。Worker 目录/页清单首轮因旧精确 Skill 数量断言出现 **1 failed、16 passed**；更新发布目录契约后定向 **17 passed**，Worker 全套 **351 passed，45.34 秒**。Host 最终 Artifact 完成契约 **53 用例，45 执行通过、8 个既有跳过**，目录 **2 passed**、页清单 **1 passed**、PPTX 内部文件 **1 passed**、Candidate **6 passed**、回滚 **2 passed**、Worker 输入 **5 passed**。

发布目录已能接受该独立 Skill，但普通视频的本地知识计划仍只有证据窗口、没有可发布的语义主张；自动成稿会在内容门禁失败。当前机器没有 LibreOffice，Docker daemon 未运行，故未验证更新后容器中的 PPTX→PDF→PNG 转换。固定真实视频的逐页可读性、字体和预览与 PPTX 的视觉一致性尚未验收；不能把本次合成回放视为 P4 全部完成。

## P4 渲染后逐页文字可见性门禁

Worker 在 LibreOffice 将 PPTX 转成 PDF 后，使用 `pypdf` 逐页提取实际 PDF 文字，并按冻结 IR 检查每页标题和所有已抽取主张仍可见；缺页、多页、PDF 解析失败、过大的转换文件或任一页文字缺失均不生成预览清单。这个检查发生在 PNG 栅格化前，避免结构上含文字但导出时丢字的 Candidate 提交。`pypdf` 加入 Worker 依赖、Dockerfile 和 `uv.lock`；锁文件离线解析因本机 uv 缓存缺包失败，联网解析成功，锁定 49 个包。

定向 **10 passed，1.11 秒**，包含 PDF 保留文字、缺主张和多页故障；Worker 全套 **351 passed，43.88 秒**。本机 PowerPoint 将固定合成 PPTX 实际转成 PDF，再通过相同的逐页文字与 PNG 门禁。Docker Desktop 启动尝试后进程未保持运行，Linux daemon 仍不可用，无法构建镜像或实测 LibreOffice。PDF 文字提取不能证明版面没有局部挤压或字体替换，仍需逐页人工视觉验收和真实视频样本。

## C3 Artifact 独立 Run 的 v2 Context 影子冻结

新增 V119 影子快照与 Memory Revision 引用表。Workspace 同时开启全局影子配置和本地 SHADOW 模式时，Artifact 初次创建及再生成在 Run 输入快照写入的同一事务内编译独立 v2 Context，校验所选 Memory Revision 仍为当前 ACTIVE，并按 `input_snapshot_id` 冻结完整投影与 SHA-256。独立请求的会话 ID 为空、cutoff 为 0；每个 Run 单独编译，不读取会话最新 Head。Memory Revision 撤销后按精确引用脱敏影子投影。Worker 仍消费原 `artifact-input-v1` 快照，影子模式不是 v2 生产启用。

后端编译通过；Flyway 在 H2 测试库将 102 个迁移应用至 V119。`ArtifactContextV2ShadowSnapshotContractTest` **2 passed**（关闭/开启模式、独立 Run 冻结及 Memory 撤销传播）；`RunReplayRedactionServiceTest` **3 passed**。尚未扩展 Worker 输入合同以消费 v2、覆盖会话关联的 Artifact/Research/QA/Note/Wiki 生产迁移，也未验证已发布文件包含被删内容时的下载阻断。当前影子编译失败会使已选择 SHADOW 的 Artifact 创建事务失败；生产默认关闭。

## C3 Artifact 冻结 Memory 撤销门禁

现有 v1 控制包在 Run 创建时记录了精确 Memory Revision 用量，但 Worker 获取输入、继续读取 Source 窗口、提交 Candidate 和下载已发布文件之前没有检查 Revision 是否仍有效。新增按 `memory_usage_log` 冻结引用与当前 ACTIVE Revision 对比的门禁；撤销或更新后拒绝上述路径。撤销传播同时清空对应 Artifact Run 输入快照、Run 记录和当前 Job 中的 v1 控制包，并把 Run 快照设为 `METADATA_ONLY`；v2 影子快照按同一 Revision 精确引用脱敏。历史没有 Run 或没有 Revision 用量记录的版本维持兼容。

`ArtifactContextV2ShadowSnapshotContractTest` **2 passed**，其中撤销测试确认 Worker 输入被拒、v1 控制包清空及 v2 投影脱敏；`RunReplayRedactionServiceTest` **3 passed**。Artifact/Research 集成回归 `Phase6ResearchArtifactContractTest` **53 个用例，45 执行通过、8 个既有跳过**。下载边界代码共用同一门禁，但尚未有真实 READY 文件在 Memory 撤销前后的专用下载故障测试；这项仍需补足。

补充 READY 文件故障用例 `Phase6ResearchArtifactContractTest#revokingFrozenMemoryMustBlockPreviouslyPublishedArtifactDownload` **1 passed**：发布前引用被记录，文件首次下载成功，撤销后同一文件下载返回 HTTP 409 / `ARTIFACT_MEMORY_REVOKED`。这是 H2 与本地对象存储契约，未覆盖跨进程 Worker 已缓存控制包、MinIO 或用户已下载文件。

## C3 Artifact Worker 输入的 v2 影子身份合同

Worker 输入现在可选携带 `context_v2_shadow`，包括与当前 `input_snapshot_id` 绑定的影子快照 ID、完整投影 SHA-256、编译器版本、所选 Memory Revision ID 和回放可用性。Host 取出时重新计算投影摘要；篡改后拒绝提供 Worker 输入。关闭影子模式的旧 Run 返回空字段，Worker 继续使用 v1 `input_payload` 和控制包生成，影子字段仅供诊断，不替代生产 Context。Python 输入模型显式解析该字段。

Host `ArtifactContextV2ShadowSnapshotContractTest` **2 passed**（含影子关闭、冻结身份和摘要篡改），`ArtifactWorkerInputControllerTest` **5 passed**。Worker 首轮新用例因夹具省略既有控制包必填字段而 **1 failed**；补齐后新用例 **1 passed**，与 callback 兼容批合跑 **24 passed，39.74 秒**。这仍不是 C3 的 v2 生成接入；Worker 不消费影子文字，Artifact 也尚无会话来源的冻结主题引用。

## P2/P3 跨语言素材复用修正

核对 Worker 的视频采集与资料包模型后，`language` 只控制输出文章、问答、PPTX 或 PDF 的呈现，不控制字幕和画面采集。Host 原来把它当作素材身份的一部分，导致中文 PDF 冻结的 Bundle 无法供英文独立产物复用。复用校验现排除输出语言，继续核对 BVID、分集、Workspace、采集策略及 Bundle 摘要。原契约中“不同语言必拒绝”的断言与实际数据边界冲突，已改为验证英文 PDF 和英文博客复用同一冻结 Bundle。`Phase6ResearchArtifactContractTest#subtitleMaterialBundleIsFrozenToVideoTaskAndIdempotent` **1 passed**。这只消除错误拒绝；英文内容质量与四选入口仍待验收。

## P6 父请求身份与选择持久化底座

V120 新增 `video_learning_request` 和 `video_learning_request_choice`：父请求冻结 Workspace、B站 URL/分集、输出语言、采集策略、模板版本、用户要求及 1–4 个明确选择；选项表对父请求与 Skill Key 唯一，子 Job 链接唯一。`VideoLearningRequestDraft` 规范化分集 URL，并按长度前缀对完整请求计算 SHA-256。仓储以 Workspace + 客户端请求 ID 幂等重放，同 ID 不同内容冲突；素材 Task 绑定与子 Job 绑定使用条件更新，素材未 READY 时不返回待创建子项。Flyway H2 迁移应用到 V120，`VideoLearningRequestRepositoryContractTest` **3 passed**，覆盖相同请求重放、选项去重、跨 Workspace 素材 Task 拒绝、无 Bundle/Plan 禁止 READY 及非法 URL/选择。

这一批只有未对外开放的持久化底座。素材 Task 的 Worker Graph、Outbox、Bundle/Plan 就绪复核、父子协调器、取消和 UI 仍未实现；不能把它当成可创建的“四选”功能。下一批需要把父请求绑定的素材身份与采集策略对账后再开放 READY 转移。

## P6 父请求的素材 READY 门禁

V121 为父请求增加 Bundle 和 KnowledgePlan 内容摘要。READY 转移现在锁定父请求，并核对同 Workspace 的 Bundle/Plan 关联、BVID、分集、冻结采集密度与 ASR 策略，重新读取并核对两份入库 JSON 的 SHA-256，再用 Host 的知识计划验证器复核证据引用。通过后在同一事务中固定两个 ID 与摘要。已冻结的合成 OCR 素材可使父请求 READY，并把博客、问答分别关联到独立 Job；采集密度、分集不符或 Plan 摘要篡改均被拒。H2 Flyway 应用至 V121；`VideoLearningRequestRepositoryContractTest` **3 passed**，`Phase6ResearchArtifactContractTest#observedVideoMaterialBindsOcrEvidenceToFrozenFrameBytes` **1 passed**（含 READY、两子 Job、三类拒绝）。素材 Task 派发与父子自动协调仍未接入，入口未开放。

## P6 父请求执行者身份冻结

V122 在父请求记录创建者 User ID；同一客户端请求 ID 的重放还需匹配原创建者。为后台协调预备的读取边界会在子 Job 调度前重新检查 Workspace、用户及成员状态，并要求当前角色为 OWNER 或 EDITOR。测试中先以 `local-user` 创建并 READY，随后将该成员置为 SUSPENDED，读取立即拒绝；恢复 ACTIVE 后仍可关联两项独立产物。`VideoLearningRequestRepositoryContractTest` **3 passed**，合成 OCR 素材的 `Phase6ResearchArtifactContractTest#observedVideoMaterialBindsOcrEvidenceToFrozenFrameBytes` **1 passed**；Flyway 已应用到 V122。后台协调器尚未调用这条门禁，不能把它当成完整 ACL 恢复保障。

## P6 父请求状态投影与取消意图

新增父请求 GET 与取消接口。GET 从素材 Task 和各子 Job/Task 推导独立状态；未创建的子项保持 `NOT_STARTED`。取消只将尚未投递的 Outbox 置为 `CANCELLED`，通过 Task 状态机终止仍为 PENDING 的任务；已被消费者接管的 Outbox 与 Task 保持原状态，同时冻结父请求取消意图，阻止后续子 Job 补建。已 READY 的素材和子 Version 不在取消时回撤。取消限原请求创建者，仍需当前 Workspace 执行权限。

首次 Maven 测试因断言使用 Java 驼峰字段而非 API 的 snake_case 序列化，**5 用例中 1 failed**；修正测试后编译发现局部变量遮蔽注入的 `TaskService`，修正后再次执行 `VideoLearningRequestRepositoryContractTest` **5 passed，0 failed/0 error/0 skipped**，覆盖 GET 投影、HTTP 取消、幂等重复取消、非创建者拒绝、未投递停止及已接管任务保留。当前运行中的 Worker 只收到持久化取消意图，尚未接入协作取消回调；父请求创建入口、素材专用 Worker Task/Outbox、后台子 Job 协调仍未开放。

素材 Task 绑定追加数据库条件：Task 必须是同 Workspace、`VIDEO_MATERIAL` 类型、以该父请求为 `VIDEO_LEARNING_REQUEST` 目标且仍 PENDING；仅靠传入 Task ID 不可绑定其他父请求或伪装的 Artifact Task。契约新增错误目标与错误类型场景，`VideoLearningRequestRepositoryContractTest` **5 passed，0 failed/0 error/0 skipped**。

## P6 独立素材任务的 Bundle/Plan 所有权

V123 给 `artifact_video_material_bundle` 增加互斥来源：保留旧 `artifact_job_id`，新增 `video_learning_request_id`，同一行只能归属一个。Host 新增父资料 Task 的 Bundle 与 KnowledgePlan 冻结入口，沿用摘要、字幕、画面、证据闭包和幂等冲突校验；父请求的 READY 门禁对独立来源要求 Bundle 的 Task/父 ID 精确一致，且 Task 已 COMPLETED。下游子 Job 读取父资料时仍核对 Workspace、父请求冻结摘要、BVID、分集和采集策略；父请求取消不撤回已有 READY 资料。

首次定向测试在嵌套 JDBC lambda 中复用了变量名，**编译失败**；修正后 V123 在 H2 成功应用。`VideoLearningRequestRepositoryContractTest` **6 passed**，覆盖无占位 PDF Job 冻结、相同内容重放、不同内容冲突、Plan 重放、Task 未完成时 READY 拒绝、完成后 READY、取消后保持 READY；旧 PDF Bundle 的 `Phase6ResearchArtifactContractTest#observedVideoMaterialBindsOcrEvidenceToFrozenFrameBytes` **1 passed**。新父资料入口目前仅是内部 Service；专用 Worker 命令、回调、对外创建 API 和子 Job 协调尚未实现。

随后给父资料 Bundle/Plan 写入和父 READY 晋升增加创建者当前权限复核：Workspace、用户及成员必须 ACTIVE，角色仍为 OWNER/EDITOR。契约在 Bundle 已冻结后暂停成员权限，Plan 提交被拒；恢复后 Plan 成功；Task 完成后再次暂停，READY 晋升被拒，恢复后才成功。`VideoLearningRequestRepositoryContractTest` **6 passed，0 failed/0 error/0 skipped**。

## P6 父请求创建事务与命令区分

新增显式四选父请求 POST、幂等客户端请求 ID、同事务 `VIDEO_MATERIAL` Task 与 Outbox，素材任务占用 Artifact 工作量配额；子 Artifact Job 仍须等待资料 READY。Outbox Kafka 发布器给资料任务发 `video-material-command.v1`，旧命令继续发 `artifact-command.v1`，命令只含 Task ID 与投递令牌，不带 URL 或用户正文。创建入口由 `noteweave.video-learning.enabled` 控制，默认 **false**，因为 Worker 尚未消费资料任务命令，当前不能对用户开放功能。

`VideoLearningRequestCreationContractTest` **1 passed**（父请求/Task/Outbox 同事务、重复请求不重建、未创建占位 PDF Job）；`KafkaArtifactOutboxPublisherTest` **3 passed**（旧协议兼容、新协议和隐私字段不外泄）；`VideoLearningRequestRepositoryContractTest` **6 passed**。尚需 Worker 专用采集执行、回调终态和子 Job 协调，再开启入口灰度。

## P6 素材 Worker 的 Host 接收边界

新增独立内部 Worker 路由：持有效 Outbox 投递令牌领取冻结输入时启动 `VIDEO_MATERIAL` Task；Bundle、Plan 各自经已有 Host 内容门禁冻结；完成回调在同一事务内依次将 Task 置 COMPLETED、将父请求置 READY 并确认 Outbox。任一 Bundle/Plan ID 不符或创建者权限已撤销时整笔事务回滚，Task 仍 RUNNING、Outbox 仍 PROCESSING。失败回调标记 Task/父请求 FAILED；若父请求已申请取消，完成或失败回调转为 CANCELLED 并确认 Outbox，不发布 READY。终态回调使用任务类型绑定的 HMAC Token，且额外核对活跃投递令牌。

`VideoLearningRequestCreationContractTest` **1 passed**（领输入、冻结输入身份、鉴权失败回调、Outbox 确认），`VideoLearningRequestRepositoryContractTest` **7 passed**（含默认关闭、非法 Bundle 回滚、权限撤销回滚、成功 READY、运行中取消）。最后追加非法 Bundle 回滚断言后单独复跑后者 **7 passed，0 failed/0 error/0 skipped**。Worker 目前尚未识别 `video-material-command.v1`，采集 Graph 与异步恢复仍需接入；生产开关继续关闭。

## P6 素材 Worker 采集与父请求回调

Worker 现识别独立的 `video-material-command.v1`，凭 Host 冻结的父请求输入复用已发布 B站 Skill 的字幕、抽帧和观察采集能力。`MATERIAL_ONLY` 分支在 Provider 等待和恢复后只构建 Bundle 与本地证据 Plan，不生成 PDF、Candidate 或 Artifact Version。Bundle/Plan 经父请求专用 Host 回调分别冻结，最后以任务类型 HMAC 和投递令牌完成 READY。内容门禁返回 409 时先尝试把当前有效投递终止为 FAILED；旧令牌重放仍由 Host 拒绝。系统 Provider 失败也分流至父请求的失败事务，非法 Provider 错误码收敛为固定码。等待恢复路径按资料任务完成，避免误走旧 Artifact Job 的发布回调。

Worker 定向 `test_video_material_task.py test_artifact_kafka_consumer.py test_callback.py` **41 passed**；Worker 全量 `pytest -q` **360 passed，38.30 秒**。Host `ArtifactWorkerControlServiceTest` **4 passed，0 failed/0 error/0 skipped**。`git diff --check` 通过。以上为模拟 Provider 响应与 H2/本地合同，未进行真实 B站跨进程采集和第三方凭据调用；父请求子 Job 协调与 UI 尚未实现，功能开关保持默认关闭。

## P6 READY 后子 Job 对账补建

新增父请求选择协调器：逐项锁定 READY 父请求及其未链接选择，复核原创建者当前 Workspace 权限、父请求原冻结的 Bundle/Plan 摘要和采集策略，再沿用 Artifact Job 创建事务写入独立 Job、Run 输入快照、Task 与 Outbox，并在同一事务链接选择。失败回滚该选项；再次扫描只补缺项，不重建已链接的 Job。后台扫描默认可处理已经接受的请求，即使新请求入口后来关闭；测试配置关闭定时扫描以便确定性故障测试。创建时的 Memory 控制包和 Context v2 影子快照显式使用已复核的原创建者，不依赖后台线程的当前登录用户。三个派生产物的受控输入补上分集、抽帧密度和 ASR 策略，以便非默认策略的子 Job 与冻结资料包对账；Worker 接受旧目录摘要下的默认策略排队 Run，拒绝把旧摘要冒充新策略。

首次 `VideoLearningRequestRepositoryContractTest` **7 passed**；追加两个子项的权限撤销、摘要损坏和恢复补建后复跑 **7 passed，0 failed/0 error/0 skipped**。联跑 `ArtifactContextV2ShadowSnapshotContractTest` 共 **9 passed**。Worker 目录与采集定向测试 **15 passed**；更新目录后的 Worker 全量 **361 passed，38.30 秒**。首次联跑 Maven 命令在 PowerShell 中未给带逗号的 `-Dtest` 参数加引号，**命令解析失败、未启动测试**；加引号后上述 9 项通过。`git diff --check` 通过。当前仍缺四选 UI、父请求完整跨进程联调、实际文件预览与灰度验收；入口开关仍默认关闭。

## P6 四选入口和父子状态展示

父请求 GET 列表按当前创建者返回最近 20 项，包含服务端新建开关、视频 URL/分集及素材与每项子 Job 的独立投影；创建入口关闭后仍能看到已接受请求。Artifact Studio 的视频资料包区域可选四种产物、语言、分集、画面密度、ASR 策略和学习重点；客户端请求 ID 在同一未修改表单的重试中保持不变。页面显示一次采集和每项产物状态，并提供取消未开始任务、同步下方产物记录的入口。旧单项 Skill 创建及 PDF 下载没有改动。服务端 `noteweave.video-learning.enabled` 仍默认关闭。

Host `VideoLearningRequestRepositoryContractTest` **7 passed，0 failed/0 error/0 skipped**，新增关闭新建但可读取列表、视频身份字段断言。首次前端构建与测试并行触发同一工作树的 pnpm 依赖链接竞争，分别报 `EEXIST`/`EBUSY`，**未运行测试**；串行离线安装完成。第一次前端全量测试 **248 passed、1 failed**，失败是新 DOM 测试未清理前一用例的渲染节点；修正测试夹具后定向 **7 passed**，全量 **249 passed/249**。第一次 TypeScript 构建发现 Workspace 字段应为 `workspace_id`，修正后生产构建通过；响应式 CSS 调整后再次构建通过，`ui:check` 通过，`git diff --check` 通过。浏览器实际渲染在约 392px 与 375px 视口检查了收起的状态列表和展开的四选表单；初次看到复选框继承输入框整宽、取消按钮挤压状态，修正为单列选择和内容宽按钮后复核无截断。预览仅使用本地模拟数据，未调用真实 B站或启用父请求入口；跨进程与真实文件验收仍缺。

## P6 素材文件丢失的下载故障门禁

冻结素材文件的普通读取和子 Run 复用读取现在共用对象字节校验；对象缺失或存储读取故障统一返回 HTTP 409 / `VIDEO_MATERIAL_FILE_DEGRADED`，不把对象 Key 路径异常暴露给客户端。故障用例在已可读取的测试帧上删除本地对象，验证两条读取路径拒绝、DB 素材引用仍保留。`Phase6ResearchArtifactContractTest#frameMaterialStoresVerifiedBytesAndRejectsCrossPartReferences` **1 passed**，`ArtifactVideoMaterialCleanupTest` **1 passed**，合计 **2 passed，0 failed/0 error/0 skipped**。现有孤儿清理只删除超过 24 小时且不在 `artifact_video_material_file` 引用表中的对象；对于已入库但父请求失败或取消的素材，尚无能证明所有子 Run/Version 引用均已解除的归一化持有记录，因此没有实施按父状态直接删除。

## P6 Workspace 灰度开关

新建父请求同时受全局 `noteweave.video-learning.enabled`（默认 `false`）与 `noteweave.video-learning.workspace-allowlist`（逗号分隔，空值表示全部 Workspace）控制。列表中的 `enabled` 与创建事务使用同一 Workspace 判定；关闭入口不会阻断已接受请求的后台协调。子 Job 创建前锁定原创建者权限读，避免权限撤销与创建事务交错。`VideoLearningRolloutPolicyTest`、`VideoLearningRequestRepositoryContractTest`、`VideoLearningRequestCreationContractTest` 联跑 **9 passed，0 failed/0 error/0 skipped**，Maven BUILD SUCCESS（55.573 秒）。

## P6 子产物独立失败门禁

同一 READY 资料包补建博客与问答 Job 后，调用真实 Worker 失败回调使博客 Task/Job FAILED。父资料仍 READY，问答仍 QUEUED 且其 Outbox 仍 READY；随后取消父请求，已 FAILED 的博客状态保持 FAILED，未开始的问答可按取消事务处理。扩展 `VideoLearningRequestRepositoryContractTest`，**7 passed，0 failed/0 error/0 skipped**，Maven BUILD SUCCESS（42.090 秒）。该测试验证 Host 父子状态及故障隔离；尚未执行真实视频跨进程端到端回放。

## P6 Skill Version 新建门禁

Host 从已发布 Skill Catalog 读取实际版本，新增 `noteweave.video-learning.blocked-skill-versions` 配置（逗号分隔 `skill_key@version`）。父请求创建事务拒绝包含被关闭版本的选择，拒绝时不创建父记录、Task 或 Outbox；列表返回当前 Workspace 可新建的 Skill，前端禁用相应勾选项。该开关只管新请求，协调器仍处理已接受父请求。现有发布目录的四个视频产物版本均为 `1.0.0`；配置不存在时保持全开放（仍受全局和 Workspace 门禁）。Host 定向 `VideoLearningRolloutPolicyTest`、`VideoLearningSkillVersionRolloutContractTest`、`VideoLearningRequestCreationContractTest` **4 passed，0 failed/0 error/0 skipped**，Maven BUILD SUCCESS（1:01）；前端 `VideoLearningPanel.dom.test.tsx` **4 passed**，`pnpm build` 通过。

## P6 各类型 Version 文件的前端下载

产物记录现在列出每个 `READY` 文件的下载动作，调用既有 Host `versions/{versionNo}/files/{fileId}` 校验端点；PPTX、Markdown、逐页 PNG 等可从最新或历史版本取回，`DEGRADED` 文件不显示下载按钮。保留原 `export.pdf` 调用和 PDF 按钮。首次定向前端测试 **11 passed**，但 TypeScript 构建发现审计视图只要求精简文件元数据、且独立动作测试缺新回调；增加完整文件元数据类型守卫并补测试夹具后，生产构建通过，定向 **12 passed**。Host 文件端点已由 P1 文件门禁覆盖；本批未做真实浏览器文件保存或 PPTX/PNG 人工预览。

## P6 父选择直达已发布子版本

父请求状态投影附上每项独立 Job 的 `latest_version_no`；前端在版本号大于 0 时提供“查看 vN”，调用已有历史版本读取与文件动作，从父选择直接进入对应产物审计。没有 Version 的排队或失败子项不显示该动作。Host `VideoLearningRequestRepositoryContractTest` **7 passed，0 failed/0 error/0 skipped**，Maven BUILD SUCCESS（1:03）；前端 `VideoLearningPanel.dom.test.tsx` **5 passed**，`pnpm build` 通过。此合同仅验证父子版本身份与 UI 路由；实际文件下载仍需浏览器联调。

## P6 失败子产物独立重试

父请求的失败选择新增原发起者专用重试入口。事务锁定父请求/选择与失败 Job，重新核对创建者权限、父资料 Bundle/Plan 身份和摘要、最后一轮冻结输入的回放状态；创建同 Job 的新 `RETRY` Run、独立 Task、预留 Version ID、输入快照与 Outbox，保留旧失败 Run 和兄弟 Job。当前运行中的同一选择重试返回 409，不重复建 Run。前端只在父资料 READY 且该选择 FAILED、父请求未取消时显示“重试”。这条路径沿用已冻结 Bundle，不重新采集视频。

Host `VideoLearningRequestRepositoryContractTest`、`ArtifactCandidateTest`、`ArtifactRollbackGateTest` 联跑 **15 passed，0 failed/0 error/0 skipped**，Maven BUILD SUCCESS（55.072 秒）；前端 API/面板定向 **10 passed**，`pnpm build` 通过。合同包含非原发起者拒绝、冻结输入复用、双击冲突、兄弟 Outbox 保持 READY 和重试再次失败后取消的状态。真实 Worker 跨进程重试仍待 P6 联调。

## P6 新 Version 的素材 Bundle 持有

V127 为 `artifact_version` 增加可空的 `material_bundle_id` 外键；父请求继续以自身 `material_bundle_id` 持有 Bundle。经候选引用校验的新视频 Version 在发布事务内写入 Bundle 身份，追加式回滚副本继承同一持有关系；非视频和历史 Version 的列保持可空。合成原画面 PPTX 发布合同验证 v1 与回滚 v2 均指向同一素材包；`Phase6ResearchArtifactContractTest#originalVideoDeckPublishesItsOwnVersionWithPptxAndPreview` **1**、`ArtifactRollbackGateTest` **2**、`VideoLearningRequestRepositoryContractTest` **7**，共 **10 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。旧历史 Version 尚未回填持有列；自动清理仍只删除超过 24 小时且没有文件引用的暂存对象，不会根据父请求终态清理已登记的 Bundle。

## P6 历史 Version 素材持有对账

增加有界定时回填：仅对持有列为空的视频 Version，核对已发布 Candidate 的 Bundle ID/版本/摘要、Bundle Workspace、Version 的原 Task 与 Job、该 Run 的冻结输入；证据闭合才写入外键。每轮扫描最多 100 行并使用游标继续，缺失或损坏的历史记录保持未绑定。合同将已发布 PPTX v1 与回滚 v2 的持有列清空，验证两者恢复、重复执行幂等；篡改 v2 Candidate 摘要后拒绝回填，恢复正确载荷后才补上。`Phase6ResearchArtifactContractTest#originalVideoDeckPublishesItsOwnVersionWithPptxAndPreview` **1 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。此对账只补关系，不执行已登记 Bundle 的删除；旧记录中缺 Candidate 引用的情况需人工审计，不能据空列推断可清理。

## C3 Answer 消费冻结 Context v2

新增独立全局 `noteweave.context.v2.active-enabled` 门禁（默认关闭）和 Workspace `ACTIVE` 模式；QA、NOTE、WIKI 在生成前读取已冻结的 READY 投影，使用所选原文、主题摘要及用户约束组装检索问题，把用户约束送入回答控制段。Memory 控制包仍按独立域编译，不并入来源引用。Run 输入快照记录 v2 快照 ID、摘要、所选引用及预算；落库前重读持久化 JSON 并核对摘要。编译 FAILED 时回退 v1，记录失败码；Workspace 关回 OFF 后，新 Run 使用 v1，已冻结 Run 保留。删除被引用的消息后，影子投影变为无正文的 REDACTED，Run 回放降为 METADATA_ONLY。

首次新合同 **1 failed**，原因是测试错误地假定无 Source 的 QA 会在助手正文回显输入；实际 QA 返回资料不足。改为核对组装结果与 Run 冻结身份后，新合同 **3 passed**。一次从 `backend` 目录直接调用 Maven 因根目录 `.mvn/settings.xml` 未找到而未执行测试；改为根目录 `-f backend/pom.xml`。随后 `ContextV2ActiveAnswerContractTest`、`ConversationRetrievalContextAssemblerV2Test`、`ContextV2ShadowSnapshotContractTest`、`ConversationTurnModuleContractTest` 联跑 **47 passed，0 failed/0 error/0 skipped**，Maven BUILD SUCCESS（1:13）。再加 ACTIVE→OFF 与删除传播合同，`ContextV2ActiveAnswerContractTest` **3 passed，0 failed/0 error/0 skipped**。这批只切换 Answer；Research 和 Artifact 尚未消费 v2，C4 真实用户正确率与生产灰度也未完成。

追加损坏投影合同：即使持久化 JSON 与攻击者替换的 SHA-256 一致，解析失败也返回 `CONTEXT_V2_SNAPSHOT_CORRUPT`，避免把损坏快照交给生成。`ContextV2ActiveAnswerContractTest` **4 passed，0 failed/0 error/0 skipped**（Maven 退出码 0）。核对 Research 发现其会话 Run 的 v1 引用由 `RunInputSnapshotService` 事后重选，而实际研究执行只使用本轮问题；下批必须同时改矩阵初始化、任务协调器和角色 Worker 的冻结执行输入，不能仅替换快照元数据。

## C3 Research 消费冻结 Context v2

会话 Research 在全局 `noteweave.context.v2.active-enabled`、独立的 `research-active-enabled` 与 Workspace `ACTIVE` 同时满足时，于建 Run 前冻结主题、原文及用户约束，并把同一投影及摘要写入 `run_input_snapshot`。原问题继续保存在 `research_run.question`；新 `execution_question` 使用冻结投影生成，供矩阵初始化、任务协调与角色 Worker 消费。当前问题放在末尾，避免 Brief 字数截断丢失本轮问题。独立全局 Research 开关默认关闭，不随 Answer 灰度自动打开；编译失败时记录原因并回退 v1。重试 Run 复制原快照至新的快照 ID，保留原问题与冻结执行输入，不重选会话 Head。

读取冻结 Brief 时复核 Run/快照身份、投影摘要、完整可回放性、当前问题和重新渲染的执行问题。消息删除或 Memory 撤销只精确脱敏受影响的 v2 投影，使其回放降为 `METADATA_ONLY`；规划、领取、心跳、提交、报告发布、Research 明细和 Collection 读取均拒绝失效快照，列表不返回失效 v2 Run。原有 v1 路径保持兼容。没有清除既有 Research 报告、Trace、派生 Source 的持久化内容；这些外部读取/下载路径仍需单独审计和门禁，不能把本批视为完整删除传播验收。

测试迭代中，首次合同因待回复助手行缺少 `PENDING` 关联和编译失败后事务被标记回滚，返回 HTTP 500；补齐关联并将编译尝试置于嵌套事务后通过。一次测试将 JSON 的 `raw_tail` 错读为驼峰字段，修正断言。随后 Research 合同从 **3 passed** 扩展到 **6 passed**，覆盖真实任务消费、编译失败回退、损坏摘要拒绝、消息删除、Memory 撤销与检查点重试。扩大联跑曾达到 **165 passed，0 failed/0 error/0 skipped**，覆盖会话、Research Task/协调/提交/最终化。最终配置及读取门禁改动后，`ContextV2ResearchContractTest` **6**、`ContextV2ActiveAnswerContractTest` **5**、`ResearchRunListQueryCountTest` **3**、`ResearchArtifactServiceTest` **2**、`ResearchAgentIncrementalFinalizationServiceTest` **10**，合计 **26 passed，0 failed/0 error/0 skipped**，Maven 退出码 0；Flyway 成功应用 V125，`git diff --check` 通过。这些是 H2/模拟合同，尚未做生产灰度或真实外部研究回放。

自动审批拒绝了清空 Research 问题、报告、Trace、规划行并取消任务/Run 的广泛清理改动，理由是会不可逆地破坏已有研究记录；该改动未执行。本批改为投影精确脱敏和消费/读取门禁。后续如需清理既有产物正文，须在明确保留与删除策略后另行处理。

## C3 Artifact 消费冻结 Context v2

V126 为 Artifact 的已冻结 Context 投影增加按 Run 固定的 `consumption_mode`。只有全局 Answer ACTIVE、独立 `artifact-active-enabled` 与 Workspace ACTIVE 同时满足时，新 Run 才标为 `ACTIVE`；默认仍为 `SHADOW`，旧 Run 与旧 Worker 输入不切换。ACTIVE Worker 输入从持久化投影验证摘要、Workspace、原需求和完整回放状态，再把已选 Memory 偏好附在生成要求后；原 `user_requirement` 和 Source 范围保持独立。Memory 被撤销后，投影变为 REDACTED，Worker 输入与完成回调拒绝继续消费；切回 OFF 只影响新 Run，已创建 ACTIVE Run 继续按原冻结模式复核。

`ArtifactContextV2ActiveContractTest` **1 passed**、`ArtifactContextV2ShadowSnapshotContractTest` **2 passed**、`ArtifactCandidateTest` **6 passed**、`ArtifactRollbackGateTest` **2 passed**，合计 **11 passed，0 failed/0 error/0 skipped**，Maven 退出码 0；Flyway 应用至 V126。新合同覆盖冻结 Memory 在 Worker 生成要求中的消费、ACTIVE→OFF 后旧 Run 不漂移、新 Run 回到 v1、投影 REDACTED 时读取失败。仍需真实 Worker 跨进程回放、完整删除传播及 C4 样本评测；此处 Memory 是生成偏好，不作为 Source 事实或 Citation。

补跑受 Artifact Worker 输入影响的 `ArtifactWorkerControlServiceTest` **4 passed** 与父资料 `VideoLearningRequestRepositoryContractTest` **7 passed**，共 **11 passed，0 failed/0 error/0 skipped**。本批两轮合计 **22 passed**。

## C3 ACTIVE Artifact 已发布文件读取门禁

文件下载在检验 Source、交付状态和对象字节前，沿 `origin_task_id` 找回原 Run 输入，并复核当时标为 ACTIVE 的 Context 投影身份、摘要及可回放状态。投影 REDACTED 时拒绝历史 Version 文件下载；SHADOW、v1 和没有冻结输入快照的历史版本仍走原有兼容路径。新增合同用同一 ACTIVE Run 的模拟 Version 验证脱敏前到达文件门禁、脱敏后先因 Context 失效拒绝；它不假装模拟 Version 已发布或有真实文件。`ArtifactContextV2ActiveContractTest` **1 passed**，既有已发布文件 Memory 撤销合同 **1 passed**，Source 删除合同 **1 passed**；合计 **3 passed，0 failed/0 error/0 skipped**。首次 Maven 选择器把 Source 删除用例名写错，因此那次只运行前两项；随后用准确方法名单独复跑 Source 合同通过。真实文件字节与浏览器下载仍待跨进程验收。

## P4 PowerPoint 实际渲染与版面门禁

本机 PowerPoint 可打开 Worker 生成的原画面 PPTX。以冻结的合成 PNG、字幕与含证据引用的 Plan 生成普通及长标题/密集正文两份单页演示文稿，PowerPoint COM 均成功打开并导出 1280×720 PNG。首次压力预览中，长标题超出右侧页边；根据实际渲染把标题按中英文显示宽度选择字号，正文按估算行高选字号，超出最低可读字号时明确拒绝生成。重新导出的压力页标题完整可见，原画面未裁边，正文在页内。`test_video_deck_ir.py` **11 passed**；Worker 全套 **362 passed，41.46 秒**。这是合成图片、单页、PowerPoint 桌面端的视觉检查；未验证真实 B站画面、多页版面、容器内 LibreOffice 转换或逐页 PNG 与 PPTX 的部署一致性。P4 仍不能宣称完成。

随后以同一合成资料增加第二张竖版画面与独立字幕/主张，生成 2 页 PPTX；PowerPoint 成功逐页导出 PNG。人工检查第 2 页的竖版原画面完整保留比例与边缘、页序/时间码为 `2/2` 与 `6.5s`，标题和正文均在页内。这补充了合成多页的桌面端预览，仍未验证容器转换器或真实视频画面。

将双页合成场景固化为 Worker 回归：横版与竖版原画面分别绑定 `f1`、`f2`，PPTX 验证两页图片摘要及无裁边，文件 Manifest 含顺序为 1、2 的两张预览。`test_video_deck_ir.py` **12 passed，0 failed**。预览 PNG 在此合同中仍是合成占位文件，实际部署转换器的逐页视觉一致性尚未验证。

## C4 管理员灰度样本汇总

新增只读 `/context-v2-rollout/cohort?limit=...`，对当前 Workspace 最近最多 100 个 Answer Run 汇总影子 READY/FAILED/REDACTED/未记录数、缺口码及 v1/v2 消息/摘要/Memory 引用差异计数。逐条沿用 SHA-256 核验，响应不携带正文或引用 ID；`accuracy_status` 仍为 `GOLD_LABELS_UNAVAILABLE`，差异数量不被误报为错误率。OFF→SHADOW→OFF 合同验证 3 个 Run 中 1 个 READY、2 个未记录及限额拒绝；`ContextV2ShadowSnapshotContractTest` **5 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。这是小范围运维观测，仍需人工标签与真实样本才能设灰度正确率门禁。

## C4 老会话按需回填合同

在 Workspace 为 OFF 时先创建一轮 QA，随后切至 SHADOW 并提交新问题；原有影子合同现在读取第二轮冻结投影，确认第一轮用户消息仍在有序 Raw Tail、本轮 cutoff 为 3、选中预算未超额。再切回 OFF，后续新 Run 不创建 v2 影子快照；已有冻结投影仍保留。定向 `ContextV2ShadowSnapshotContractTest#workspaceModeCanOptIntoShadowAndTurnItOffWithoutEnablingActive` **1 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。此处证明本地旧会话在下一轮输入时回填；尚无批量历史回填、真实用户标注或灰度误选率统计。

## P6 四选提交前资源提示

父请求创建表单按当前选择显示一次资料采集及独立产物任务数，明确画面采集密度、会使用原画面的交付种类，以及无字幕时是否可能增加 ASR 工作。具体费用与耗时仍随视频长度、字幕状态和 Provider 变化，界面不展示虚构金额。新增 DOM 合同覆盖选择 PPTX、丰富抽帧及禁用 ASR 后的提示更新；前端全套 **254 passed/254**，`pnpm build` 与 `pnpm ui:check` 均通过。此项只验证文案和 DOM，尚未在真实父请求或浏览器保存文件流程中联调。

## C3 Research 列表的损坏快照门禁

Research 列表仍由 SQL 排除已降为 `METADATA_ONLY` 的 v2 Run；对余下带 v2 快照 ID 的行，在报告摘要装配前重新验证冻结投影、SHA-256 与执行问题。损坏快照现在让列表返回冲突，避免只在详情/Worker 入口阻断。v1 行不额外触发 v2 编译。`ContextV2ResearchContractTest` **6 passed**、`ResearchRunListQueryCountTest` **3 passed**，合计 **9 passed，0 failed/0 error/0 skipped**；Maven 退出码 0。此门禁仍未覆盖已派生 Source 的所有通用检索入口。

## C3 派生 Research Source 目录读取门禁

通用 Source 目录现在对 `research_agent` 派生条目按原 Research Run 校验冻结 Context；已降为 `METADATA_ONLY` 的 v2 来源从目录过滤，即使目录本身曾被缓存也会逐次应用可见性规则。其他来源与没有 v2 快照的历史 Research 条目保持兼容；损坏或缺失的 v2 来源不会被当成可读记录。集成合同先列出派生条目，破坏投影摘要后确认目录冲突，恢复摘要，再删除原问题并复读相同目录，验证条目消失。`ContextV2ResearchContractTest` **7 passed**、`SourceServiceCacheTest` **3 passed**，共 **10 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。此批只覆盖通用目录，未覆盖 Source 正文、文件、通用检索及派生 Wiki 的所有读取路径。

## C3 派生 Research Source 资料范围消费门禁

Research Worker 的 Source Scope 装载复用同一原 Run 门禁。已撤销的 v2 来源返回 `RESEARCH_SOURCE_CONTEXT_REDACTED`，摘要损坏返回 `RESEARCH_CONTEXT_SNAPSHOT_MISMATCH`，不再把原文件摘要或样本文本交给后续 Research。集成合同在可读、损坏和撤销三种状态下检查同一 Source；`ContextV2ResearchContractTest` **7 passed**、`ResearchPersistenceJsonBoundaryTest` **4 passed**、`ResearchRunListQueryCountTest` **3 passed**，共 **14 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。此处仍未覆盖 Answer、Artifact 及通用检索对该派生 Source 的全部读取路径。

## C3 派生 Research Source 的 Answer 检索门禁

Source 身份批量复核加入 QA 本地回退与 NOTE 候选池，QA/NOTE Hydrator 在返回 Passage 所属及阅读窗口前过滤撤销来源。混合 QA 在外部 Rerank 调用前先用数据库所属关系及冻结 Context 校验候选，避免把已撤销来源的正文送往重排器；摘要损坏会明确失败而不是走 MySQL 回退。集成合同用同一派生 Source 验证可读、摘要损坏和消息撤销后的 Hydrator/NOTE 候选行为；模拟 Provider 合同确认被拒正文未到达重排调用。首次定向测试有 **6 failed**：旧 MySQL 回退单测使用全模拟 Hydrator，没有设置新增的来源可读集合，候选被清空；补齐测试夹具后复跑 `ContextV2ResearchContractTest` **7**、`QaHybridRetrieverProviderIntegrationTest` **2**、`QaPassageRetrieverTest` **15**、`RetrievalHydratorTest` **3**、`NoteRecallRepositoryQueryTest` **1**，共 **28 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。仍需审计直接读取 Source/文件、Artifact、派生 Wiki、既有答案引用及索引清理/跨进程撤销传播。

补跑 NOTE/QA 邻接消费合同：`NoteRetrievalServiceHydrationTest` **2**、`NoteRecallRetrieverTest` **4**、`NoteReadingRetrieverTest` **2**、`NoteEvidenceRetrieverTest` **3**、`QaPassageEvidenceRetrieverTest` **4**，共 **15 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。

## C3 派生 Research Source 的 Artifact 输入门禁

Artifact 新 Job 捕获来源时校验上游 Research Run；既有 Run 的 Worker 输入、Source 分窗及 Candidate 提交均复核冻结 Source 与上游引用。因而撤销的 Research 来源不会再通过已冻结的 `sample_text` 或分窗送给 Worker，损坏摘要也直接拒绝。`ContextV2ResearchContractTest` 使用同一派生 Source 先创建 Artifact Job，再破坏摘要及撤销来源，验证 Worker 输入拒绝和新 Job 不可创建。联跑 `ContextV2ResearchContractTest` **7**、`ArtifactWorkerControlServiceTest` **4**、`ArtifactCandidateTest` **6**、`ArtifactRollbackGateTest` **2**，共 **19 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。历史 Artifact Version 的已发布正文/文件读取是否随来源撤销而阻断，仍须与 Research 已发布内容的保留/删除策略一起确定。

## P1.5 实际长文档后段事实回放

Worker 将仓库的 `docs/Artifact-Skill执行架构.md`（约 25 KB 文件）按真实段落切为约 420 字符的连续窗口，每页取 4 个窗口；“Workflow History 兼容”位于第 12 个窗口之后。以“Workflow History 兼容 Activity 幂等”为查询，6 窗口/5 KB 选择预算仍取到该后段窗口，`material_resolution.selected_window_ids` 与产物 Citation 都保留其 ID，预算缺口标为 `SELECTION_BUDGET_REACHED`。`tests/test_material_resolver.py` **3 passed，0 failed**（1.79 秒）。这是对仓库真实长文档的确定性 Worker 回放，Host 的冻结 Snapshot 分页和撤销合同在既有 `artifactWindowPagesMustUseFrozenSnapshotAndRejectRevokedSource` 中覆盖；尚未用真实用户资料做人工质量评估，也未量测线上 Provider 生成质量。

## C3 已发布 Artifact Version 的派生 Research 来源门禁

Version 详情、PDF/文件下载、回滚、保存为 Source 与知识库写回，在读取或复制正文前，沿 `origin_task_id` 复核冻结的 Artifact 输入、Source Snapshot 与上游 Research Run。已撤销消息或 Memory 会阻断旧 Version 的这些入口；摘要损坏仍返回原 Research 快照错误。无冻结输入快照的旧 Version 保持历史兼容，不伪称已完成该类版本的撤销传播。

首次定向联跑 10 项中 **1 failed**：派生 Source 的目录过滤把 Research 撤销错误折叠为通用 Source 错误。改为从 Source 持久化身份直接调用原 Run 门禁；随后一次编译因当前 Java 不支持 `List.getFirst()` 失败，改用 `get(0)` 后重跑 `ContextV2ResearchContractTest` **7**、`ArtifactContextV2ActiveContractTest` **1**、`ArtifactRollbackGateTest` **2**，共 **10 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。合同中的 Version 为数据库构造的旧 Version，文件下载断言验证在查找文件前阻断；尚未使用真实对象存储字节及浏览器保存流程。

## C3 派生 Research Source 的 Wiki 当前版本读取门禁

Wiki 当前版本的列表、搜索、相关页和详情，按当前引用的 Source ID 批量复核可读性；详情即使命中页面内容缓存也重新验证来源。混合来源页面只要有一项撤销就不返回，相关页的 5 条限制在过滤后执行。`KnowledgeQueryServiceTest` 增加缓存命中后撤销的回归合同；首次编译因 Spring JDBC `query` 回调重载不明确而失败，指定 `RowCallbackHandler` 后复跑通过。最终联跑 `KnowledgeQueryServiceTest` **7**、`ContextV2ResearchContractTest` **7**，共 **14 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。独立的历史 Wiki Version 服务、Wiki 首页索引统计和既有答案引用仍未覆盖；本合同的 Wiki 来源可读性为模拟门禁，真实 Research Run 门禁由 Research 合同覆盖。

## C3 历史 Knowledge Version 引用门禁

将引用来源的 Workspace/Version 查询抽为共用门禁。Knowledge 历史版本列表在展示摘要前过滤撤销引用，历史详情在返回正文及 Citation 前再校验；当前版本继续复用该门禁。新增合同使旧版本正文仍留存于数据库但对撤销后的读取不可见。联跑 `KnowledgeQueryServiceTest` **7**、`KnowledgeVersionServiceTest` **4**、`KnowledgeGovernanceServiceTest` **6**、`KnowledgeCommandServiceTest` **4**、`ContextV2ResearchContractTest` **7**，共 **28 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。版本引用查询仍依赖已持久化 Citation；无引用的手工版本保持可读。

## C3 答案生成前的派生 Research 引用门禁

答案生成适配器装配本次消息的 Citation 文本时，先根据 Source 持久化的 `generated_by/generated_ref_id` 复核 Research 原 Run，再把标题与 `quote_text` 放入生成材料。撤销后阻断该次装配，避免引用文本送往后续模型调用。H2 合同验证可读与撤销两态；`ChatAnswerGenerationAdapterTest` **6**、`ContextV2ResearchContractTest` **7**，共 **13 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。测试中的 Research 门禁为模拟，真实 Run 的损坏/撤销状态由 Research 合同验证；已保存答案正文及其引用展示没有在此批迁移。

## C3 Wiki 首页与索引元数据读取门禁

Wiki 首页及相关页的链接须两端页面当前版本可读；索引“最近资料”重新核对 Research Source 原 Run，再计算推荐页面。索引最近更新、任务和问题条目若指向撤销页面或资料，过滤其标题与进度文本。合同覆盖页面缓存命中后撤销、首页链接、索引最近更新/任务/问题，以及独立派生 Research Source 的最近资料标题。`KnowledgeQueryServiceTest` **8**、`ContextV2ResearchContractTest` **7**，共 **15 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。索引聚合计数仍来自持久化行，表示历史/运行数据量，不可解释为当前可读对象数；对来源关联不完整的旧页面也不能证明全面脱敏。

## C3 Wiki 检索与 Citation ID 提取之间的撤销门禁

Wiki Evidence Retriever 向 Citation ID 查询传入 Workspace；查询在返回 ID 前重新验证每个版本的归属与来源可读性。已选页面若在检索后撤销，拒绝整个证据组装，避免把旧页面正文与 Citation ID 带入答案。历史版本改写复制 Citation ID 的入口也复核原 Version，阻止已撤销引用继续传播。首次联跑 **32 项中 1 error**：`KnowledgeCommandServiceTest` 的简化 H2 Schema 缺少 `citation` 表；补齐夹具后 `KnowledgeQueryServiceTest` **8**、`KnowledgeVersionServiceTest` **4**、`WikiEvidenceRetrieverTest` **3**、`KnowledgeGovernanceServiceTest` **6**、`KnowledgeCommandServiceTest` **4**、`ContextV2ResearchContractTest` **7**，共 **32 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。该测试覆盖同步读取边界，不等于生产级并发隔离证明。

## C3 Knowledge 新版本写入的 Citation 来源门禁

新建 Note/Wiki 或追加版本在插入 Version 前，逐个核对 Citation ID 属于同一 Workspace，所指 Source 仍为 READY，派生 Research 原 Run 可读。撤销后返回 `KNOWLEDGE_CITATION_REVOKED`，不继续复制引用。首次联跑 **30 项中 1 error**：`KnowledgeGovernanceServiceTest` 过去只传伪 Citation ID，未创建对应引用行；补齐夹具并增加真实 Research Source 集成合同后，`KnowledgeCommandServiceTest` **5**、`KnowledgeVersionServiceTest` **4**、`KnowledgeGovernanceServiceTest` **6**、`KnowledgeQueryServiceTest` **8**、`ContextV2ResearchContractTest` **7**，共 **30 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。合同证实撤销前可绑定真实来源，撤销后新 Wiki 写入拒绝且未创建新 Item；已存正文的保留/删除策略仍待定。

## C3 Artifact 已发布文件清单门禁

`/versions/{versionNo}/files` 在返回文件名、对象键和状态前，复用已发布 Version 的冻结输入、Source 与 Research Context 可读性校验。撤销后的派生 Research 来源会阻断文件清单，与详情及文件下载一致。定向联跑 `ContextV2ResearchContractTest` **7**（74.00 秒）、`ArtifactContextV2ActiveContractTest` **1**（28.02 秒）、`ArtifactRollbackGateTest` **2**，共 **10 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。该合同的 Version 文件为模拟记录，没有真实对象存储字节。

## C3 Artifact Version 列表标题门禁

历史 Version 保留在数据库；列表在返回标题前逐个复核冻结来源。明确的 Source/Memory/Research Context 撤销使该 Version 从列表隐藏；Research 摘要损坏继续失败，不把完整性异常伪装成空列表。`ContextV2ResearchContractTest` 覆盖可读、摘要损坏和撤销三态；与 `ArtifactContextV2ActiveContractTest`、`ArtifactRollbackGateTest` 联跑共 **10 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。收紧异常分类后再跑 Research 合同 **7**、Artifact ACTIVE 合同 **1**，共 **8 passed，0 failed/0 error/0 skipped**；Artifact Context v2 的通用不可用错误仍显式失败。

## P1 A1/A2 文件恢复读回门禁

`reconcileDegradedFiles` 恢复缺失或损坏的对象后，读回同一对象并与已提交文件摘要核对；读回不匹配时 Version 保持 `DEGRADED`，下一轮重试读回正确才改为 `READY`。H2 加模拟对象存储的故障测试 `ArtifactFileReconcileReadbackTest` **1 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。邻接的 `Phase6ResearchArtifactContractTest` 文件元数据完整性及历史版本再生成/比较/回滚合同 **2 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。这里的对象读回由测试替身模拟，尚非真实对象存储或跨进程崩溃恢复验证。

恢复来源随后按文件角色修正：主 Markdown 从 Version 正文重建；`SOURCE_MD`、PDF、PPTX 和逐页预览使用原任务的 Worker 文件接口取回，并再次核对已提交摘要。故障用例增加独立 `SOURCE_MD` 丢失与取回验证；联跑 `ArtifactFileReconcileReadbackTest` **1**、邻接 `Phase6ResearchArtifactContractTest` **2**，共 **3 passed，0 failed/0 error/0 skipped**，Maven 退出码 0。辅助文件能否跨进程恢复仍取决于 Worker 文件的保留时间，真实存储与 Worker 过期场景待验证。

## P4 逐页预览界面

`video_learning_deck` 的 Version 记录现在可展开逐页 PNG 预览，按页通过已有文件下载接口取图；用户可上一页/下一页浏览，离开页面时释放浏览器对象 URL。PPTX 与 PNG 原有下载入口保留。前端 `ArtifactSlidePreview.dom.test.tsx`、`ArtifactVersionActions.dom.test.tsx`、`ArtifactStudioActivity.dom.test.tsx` 首轮 **5 passed**；补充活动界面的真实接线断言后复跑预览与活动界面 **5 passed**，均 0 failed；`pnpm exec tsc -b --pretty false` 退出码 0。此处验证了 DOM 交互和类型检查，真实多页画面的视觉验收仍未完成。

## P6 浏览器文件保存检查

使用本机 Chrome 和本地 Vite 页面执行 Playwright：调用前端现用的 `downloadBlob`，确认浏览器发出下载事件、建议文件名为 `slide-notes.md`，实际保存字节为 `# saved artifact\n`。首次运行因 Playwright 自带 Chromium 未安装而未启动；改用已安装 Chrome 后 `artifact-browser-save.spec.ts` **1 passed**，TypeScript 构建检查退出码 0。此项验证浏览器保存动作，不等于已在带真实后端的 Artifact Version 页面完成文件下载；该全链路仍待验收。

## P0/P2 用户提供 BV1MZYT6pEzy 的真实资料回放

2026-09-27 对用户提供的 [视频](https://www.bilibili.com/video/BV1MZYT6pEzy/) 做本地试跑。B站公开元数据返回《面试官：RAG权限管控怎么设计？》、约 311 秒、单分集；公开视频页面无法由 Web 抓取，但 Worker 的 `yt-dlp` 元数据与音频下载成功。字幕适配器未找到手工或自动字幕，ASR 回退成功。`tiny` 模型生成 85 段，`small` 模型生成 233 段，后者覆盖 0–310.32 秒；抽检均有 RAG、权限、向量、校验等术语的错听，不能直接作为已核实语义主张。

首次画面采集得到 0/8：临时指向的 Conda 包缓存内 `ffmpeg.exe` 在 Windows 加载阶段以 `-1073741515` 退出，因缺失 DLL 没有解码画面。仓库专用 Conda 环境安装又因 conda-forge TLS 握手失败而未完成。改用 Worker 虚拟环境中的自包含 ffmpeg 后，原 `capture_local_video` 函数在同一已下载视频上取得 **8/8** 张 PNG，时间点为 0、45、90、135、180、225、270、310.167 秒；抽看首、中、末张确认是原课程画面，末张为推广/关注页，说明均匀采样不能保证每页都适合学习产物。

将 `small` ASR 与这 8 张画面交给 `subtitle_only_bundle`、`merge_captured_video_frames`、`build_local_evidence_plan`：Bundle **233 段字幕、8 张画面、8 个摘要校验文件**，本地证据 Plan **11 节点**，均通过冻结引用和字节校验。Bundle 摘要 `c434bd6149490f0f4c7ff1db95e71dbaa6990d6261bf47d92c91d3e8201d0ffb`，Plan 摘要 `9e034d39cfd9396830dd8b669b9436f0f92353df340e0ac7031d7c8d6ca919ec`。对应 Worker 合同 **47 passed，0 failed**（字幕/画面/Bundle/Plan 40，画面合并 7）。音频、字幕、视频、画面及 Bundle/Plan JSON 仅在 Git 忽略的 `workers/artifact-worker/runtime/real-BV1MZYT6pEzy/`，未提交或发布。用户提供链接可用于本次本地试跑，进一步分发或复用许可尚未核实。

本机没有 Tesseract OCR；首次试跑的改造工作树进程未加载主工作目录的模型环境变量。此回放未经过 Java Host 发布，也未生成可验收的博客、问答、PPTX 或 PDF。真实样本因此证明资料采集与冻结链路的一部分，**不证明 P0/P2/P3/P4 完成**。

后续核对确认主工作目录 `.env` 已配置 Artifact 模型，改造工作树没有自己的 `.env`，之前称“未配置 Provider”是误判。代码检查还发现父视频资料完成路径及普通视频资料发布路径均直接使用 `build_local_evidence_plan`，没有调用已实现的 `plan_video_knowledge`。现已接通配置模型时的语义规划；模型失败时父资料仍冻结本地证据索引，普通视频资料不发布伪语义 Plan。针对回调与规划的合同测试 **43 passed**。使用主工作目录配置做实际请求：小型 JSON 请求约 19.5 秒成功；全视频 233 段字幕在 60 秒和 180 秒分别超时，截取真实视频前 10 段字幕的规划请求在 90 秒超时。可用性瓶颈是视频规划请求的时延/输出复杂度，不能归因于模型未配置；语义 Plan、博客、问答、PPTX 的真实效果仍未验收。

## P3/P4 冻结资料的下游条件回放

按用户要求暂将语义规划视作已成功，不改模型请求。2026-09-27 用上述真实 Bundle 的 **233 段 ASR、8 张原画面和 8 个文件摘要**，构造一份仅供合同回放的语义 Plan：8 个 `CONCEPT` 节点各逐字引用一段现有字幕，另以 2 个字幕窗口覆盖所有字幕；Plan 通过 `verify_against_bundle`。由此分别生成并复核博客 **8 节**、面试问答 **8 条**、原图 PPTX **8 页**；`verify_against`、`verify_original_video_deck` 均通过，P3/P4 相关合同 **24 passed，0 failed**。本机 PowerPoint 打开 PPTX 并导出 **8/8** 张 PNG；抽看第 1、4、8 页，原画面完整、版式无明显溢出。

这份 Plan **不是模型真实成功输出**，字幕也未经人工纠错，因而这里只证明冻结证据后的三个独立渲染路径可用，不证明文章论述、问答质量或 PPTX 教学内容合格。第 8 页实际是视频推广/关注画面，显示均匀抽帧会把非教学页面带入产物。PowerPoint 导出的 8 页用于本地 Candidate 文件清单校验：总计 **10 个文件项**（Markdown 1、PPTX 1、逐页预览 8），页序 1–8、格式与摘要检查通过。此检查没有走生产容器的 LibreOffice 转换或 Host 下载。生成的 Plan、Markdown、PPTX、PowerPoint PNG 均保存在 Git 忽略的 `runtime/real-BV1MZYT6pEzy/`，未发布为 Host Version。

## P6 WSL Docker 构建与隔离启动

2026-09-27 改用 Ubuntu WSL2 内的 Docker Engine 27.5.1、Compose 2.32.4。主项目 Compose 栈已占用固定容器名和主机端口；本工作树使用 Git 忽略的隔离 override、独立卷和内部端口启动，没有停止主项目容器。首次构建时本机代理需供 Maven、Corepack、apt 使用；代理设置仅存在于本地构建过程，已从正式 Maven/前端配置撤回。Artifact Worker Dockerfile 将 Debian apt 源改为 HTTPS 后构建成功。

本分支 Backend、Frontend、Research Worker、Artifact Worker 四个镜像均构建成功；其中 Backend 生产源码编译 634 个 Java 文件并完成 JAR，Frontend `tsc -b && vite build` 成功。隔离栈的 MySQL、Redis、Kafka、MinIO、Elasticsearch、Backend、Frontend、Artifact Worker 启动；Backend `/actuator/health` 返回 `UP`。Worker 中 `LibreOffice 25.2.3.2`、`ffmpeg 7.1.5`、`tesseract 5.5.0` 可运行；容器内生成的最小 1 页 PPTX 经 LibreOffice 转为 1 页 PDF，`pdfinfo` 读回页数为 1。

首次启动期间 Ubuntu WSL 实例在前台命令结束后反复关机，Docker daemon 随之停止，MySQL 断连使 Backend 启动失败。保持一个前台 WSL 会话后，Backend、MySQL、Artifact Worker 均达到 healthy。此项验证了镜像和基础渲染运行时，尚未验证真实 BV 素材经 Kafka、MinIO、Host 发布、Version 页面下载的完整跨进程链路，也没有验证真实模型效果。

后续尝试仅在隔离栈打开视频功能开关时，Compose 重建 Backend 报 Docker 旧进程为 zombie、无法停止；已清理本次重建留下的 `Created` 临时容器，原隔离 Backend 仍保持 healthy。未重启 Docker daemon，以免中断主项目 Compose 栈；所以本次没有把视频 API 或真实文件下载记为通过。

## P6 WSL Docker 四选请求及故障回执

2026-09-28 隔离栈 Backend 启用视频入口，使用现有 bootstrap 账号新建独立验收 Workspace。登录、Workspace 列表、视频入口均返回 HTTP 200；视频入口 `enabled=true`，列出 `bilibili_course_note_pdf`、`interview_qa`、`knowledge_blog`、`video_learning_deck` 四个 Skill。隔离环境配额服务返回 `WORKLOAD_QUOTA_UNAVAILABLE`，仅在隔离 override 关闭配额后，同一四选请求创建成功，父任务由 `QUEUED` 到 `RUNNING`，四个子项均保持 `NOT_STARTED`。

真实 Kafka 消费暴露两处代码问题并已修复：`/internal/worker/video-material-tasks/` 未归入 Artifact 内部 Token 路由，Worker 获取输入被 401 拒绝；Host 向 FastAPI 转发 Provider ACK 时用默认 camelCase JSON，Worker 返回 422。修复后内部认证合同 **5/5**、Host→Worker 控制客户端合同 **2/2** 通过，真实回执进入 Worker `/callbacks/acquisition/ack` 为 HTTP 200。Worker 公开 operation 不带 `task_id`，Host 改从 receipt 取任务 ID；失败回执重复投递时跳过已终态任务。控制服务合同 **5/5** 通过。隔离容器中对已持久化的同一失败回执首次重放返回 HTTP 200，父任务变 `FAILED`；再次重放仍为 HTTP 200，四个子项保持 `NOT_STARTED`，Bundle/Plan/Version 均未误发布。

本次真实 BV 采集在 Worker 内由 `yt-dlp` 访问 B站时 TLS 握手超时，无法形成 Bundle。提出让 Docker 网关转发 WSL 本机代理的脚本被自动审批拒绝，理由是它会让 Docker 网络内其他容器连接该代理，访问面超出单条视频验收；未执行或换方式绕过。因此生产容器的真实视频成功发布、逐页预览和前端 Version 下载仍未通过。此次故障验证的是认证、回执、终态和幂等失败路径，不能算作四种产物成功链路。

## 2026-09-28 全量回归与容器原画面 PPTX

Frontend `pnpm test`：**72 个文件、256 passed**；Artifact Worker `.venv\\Scripts\\python.exe -m pytest -q`：**367 passed**。Backend 首次完整 `mvn test`：**1132 项、5 failed、11 skipped**。其中一项架构断言把 Windows CRLF 当成配置错误，另有三项旧合同仍要求删除来源后 Worker 取得 `METADATA_ONLY` 输入，与当前 C3 读取门禁冲突；第五项架构断言发现 `ArtifactJobService` 中新增的三处直接 SQL。将换行断言兼容 Windows、更新旧合同为拒绝读取，并将直接 SQL 移入既有 Repository 后，再跑 Backend 全量测试：**1132 项、0 failed、0 error、11 skipped**，Maven 退出码 0。前后端与 Worker 的完整单元/合同回归至此通过；这不代替真实模型与跨进程成功链路验收。

把本地冻结 Bundle 派生的实际 **8 页原画面 PPTX** 拷入隔离 Artifact Worker，容器 LibreOffice 转成 PDF，`pdfinfo` 读回 **8 页**、文件约 **636 KB**。这补齐了 P4 的容器转换页数检查；真实模型 Plan 的教学内容、逐页预览一致性和 Host Version 下载仍待验收。

## 2026-09-28 真实 BV 直连重试与导出认证修复

此轮 Worker 选用 `audio.srt`，转录模式为 `bundled_faster_whisper`，即实际走 ASR 回退；206 段不能称为直接提取到的视频 CC 字幕。

隔离 Worker 对用户提供的 BV 页面直连返回 HTTP 200。新四选请求 `8322de5f-4ea1-42a4-a8c3-109b6bcbe632` 经 Kafka 开始采集；Worker 持久化记录显示字幕提取 **206 段**，画面采集、画面观察和规范化阶段全部 `COMPLETED`。随后 Host 的 `/video-material-tasks/{taskId}/bundle` 返回 HTTP 502，父任务 `FAILED`，四个子项仍 `NOT_STARTED`。Worker 堆栈和 Host 访问日志显示 Host 回读 `/tasks/{taskId}/exports/frame-1-000.png` 得到 **401**：`HttpArtifactWorkerExportClient` 误用通用内部令牌，而 Worker 路由要求 Artifact 专用令牌。客户端改用与控制客户端相同的 Artifact 令牌配置，新增 HTTP 合同 **1 passed**，修复版 Backend 已打包并在隔离栈健康启动。

修复后同一 BV 的独立重试请求 `027506b3-c12d-4601-889c-69786adfd41c` 完成真实字幕、画面、观察与规范化；Host 成功保存 Bundle 和 Plan，父任务变 `READY`，四个独立子 Job 均建立。此 Plan 在真实模型规划失败后回退为本地证据索引，**没有经模型核实的语义主张**。四个子项随后分别 `FAILED`，Version 均为 0：博客和问答报 `frozen knowledge plan has no verified semantic claims`，原画面 PPTX 报 `original-image deck requires verified semantic claims`，PDF 报 `Artifact LLM returned no usable content; extractive fallback is disabled`。用户界面 API 保留各子项独立状态和错误信息。此轮证明真实视频经 WSL Docker/Kafka/Worker/Host 到 Bundle/Plan 的发布链路及失败隔离，**不证明四种产物 READY 或真实模型质量**。按用户此前要求，模型输出效果门禁继续单列，不为让验收通过而放松证据校验。

导出认证修复后再次运行 Backend 完整 `mvn test`：**1133 项、0 failed、0 error、11 skipped**，Maven 退出码 0；新增的 `HttpArtifactWorkerExportClientTest` 包含在内。

## 2026-09-28 隔离 MinIO 重启与素材对象读回

对已发布的真实 BV Bundle，在隔离 MySQL 读取 `artifact_video_material_file` 清单，共 **12** 个 PNG 对象。逐个从隔离 MinIO 读回，比对实际字节长度和 SHA-256：**12/12 一致**。仅重启 `bili-artifact-test-minio`，待健康检查恢复后重新读回同一清单，仍 **12/12 一致**。未操作主项目 Compose 栈，也未移动或删除对象。这验证了素材文件在隔离 MinIO 进程重启后的持久性与数据库摘要一致性；不等同于已发布 Artifact Version 的跨进程暂存恢复或浏览器下载。

定向复跑 `ArtifactFileReconcileReadbackTest` **1**、`ArtifactRollbackGateTest` **2**、`ArtifactCandidateTest` **6**，共 **9 passed、0 failed/0 error/0 skipped**，Maven 退出码 0。前两组分别是模拟对象读回损坏后的 DEGRADED→READY 对账及缺少回滚源文件时不创建 Version 的服务合同；尚未用真实 READY Version 对 MinIO 做缺文件故障注入。

## 2026-09-28 真实长 Source、Version 下载与回滚故障回放

在隔离 Workspace 通过真实上传 API 提交仓库的 `docs/Artifact-Skill执行架构.md`（**24,953 字节**）。Source 异步解析/索引期间创建 Artifact Job 被 `ARTIFACT_SOURCE_SCOPE_INVALID` 拒绝；转为 `READY/PARSED/INDEXED` 后重试成功。数据库显示 **76 个 Source Window**；“Workflow History”位于 chunk 17 的两个窗口，ID 为 `8dd87bdc-3e0a-46a2-bdee-8ef36ad70227`、`c8cfa772-4a49-400c-babd-8a6c2f791dfe`。实际 `study_guide` Job 由 Worker 完成并发布 **Version 1 / READY**，冻结输入快照和预留 Version ID 均有记录。产物正文包含 Temporal 迁移、Workflow History 与 Activity 幂等；Version Citation 属于同一 Source，引用的 12 个 Window ID 包含上述两个后段窗口。该回放验证了真实上传 Source→Host 冻结→Worker 生成→后段 Citation→Version 的链路；不替代人工内容质量审阅。

公开 Version API 返回详情与 1 个 `PRIMARY_MARKDOWN` 文件；下载 HTTP **200**、附件头存在，**4,902 字节**与文件表大小及 SHA-256 一致。仅在隔离 MinIO 中备份并暂时移走这个 Version 的单个对象，公开下载返回 HTTP **409 `ARTIFACT_FILE_DEGRADED`**；脚本在同次执行中恢复对象，随后下载重新 HTTP 200 且摘要一致。未在数据库采样到瞬时 DEGRADED 行，不能给出降级持续时间；服务端定时对账可能已在采样前恢复。

公开回滚接口将 Version 1 复制为 **Version 2**，新版本文件下载 HTTP **200**、大小 **4,902 字节**，SHA-256 与源版本一致。仅重启隔离 Backend 与 MinIO 并等待双方健康后，再分别下载 Version 1/2：均 HTTP 200、附件头、大小与 SHA-256 通过。此项验证已发布 Markdown Version 的跨进程持久性、公开下载与成功回滚；尚未覆盖提交到一半时的进程崩溃、Worker 导出文件过期后的非 Markdown 恢复，或真实浏览器保存动作。

复跑 `Phase6ResearchArtifactContractTest#artifactWindowPagesMustUseFrozenSnapshotAndRejectRevokedSource`：**1 passed、0 failed/0 error/0 skipped**，Maven `BUILD SUCCESS`。合同覆盖冻结窗口分页、输入快照及 Source 撤销后的拒绝读取。
