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
