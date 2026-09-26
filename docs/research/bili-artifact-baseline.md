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
