# bili-2-ppt 与 NoteWeave 产物能力升级评估

> 2026-09-26 核对。上游固定在 [`5ebdd759`](https://github.com/ewkzcz/bili-2-ppt/tree/5ebdd759f9493855b82e49ab1d92319b272f906c)；原工作区的参考克隆位于 `reference/bili-2-ppt`。本文是方案评估，不代表下面的能力已接入 NoteWeave。

## 结论

最有价值的升级是把 B 站视频变成**可复用的、带时间与画面证据的学习资料包**，再从同一份资料包派生 PDF、学习文章、面试问答和 PPTX。优先补强现有 `bilibili_course_note_pdf`，之后再开放新产物。直接安装上游 Skill 不能代替 NoteWeave 的 Workspace 授权、异步任务、文件版本与验证契约。

## 当前实现与增量

| 能力 | NoteWeave 当前源码 | 上游可借鉴点 | 判断 |
| --- | --- | --- | --- |
| B 站链接与字幕 | `bilibili_course_note_pdf`、`video_summary`、`course_notes` 已在 Java/Python Catalog；系统 MCP 可取 B 站字幕，取不到可走 ASR | 手工字幕、网页 AI 字幕、本地 ASR 分级兜底；取到后立即纠错并统一术语 | 补强，不重复建字幕入口 |
| 画面证据 | 当前 `export_artifact_if_required` 只把标题、章节正文和 URL 交给 PDF 渲染器；没有把截图清单送进该导出路径 | 按时间采样、连续画面去重、保留最后完整帧、覆盖不足时局部补采 | 第一优先级；让“图文讲义”有可核验的图 |
| 知识组织 | 已有通用结构化笔记、学习指南、测验题和思维导图；各 Skill 没有共享视频知识树输入 | `notes.plan.json` 对齐定时字幕、术语、知识点和画面；新版可按时间段自动分派画面 | 建一个受 Schema 管理的中间资料包，多个交付物共用 |
| Markdown 学习材料 | 现有通用 Skill 能生成文本，但没有“知识博客文章”和三段式面试问答的固定格式 | `描述.md` frontmatter + `案例.md` 扩展模板，结构校验脚本 | 可先以两个新 Skill 或模板版本试点 |
| 演示文稿 | 当前 Catalog 无 PPTX Skill；既有调研把 `SLIDE_DECK` 放在下一阶段 | 原图版和原生图形版、逐步动画、模板设计语言、PPTX 结构与渲染校验 | 新增较大能力；先交付原图版，再评估图形版 |
| 可恢复与交付 | NoteWeave 已有 Artifact Job、Version、File、Verifier 与导出路径 | 上游按阶段保存清单并复用已有中间结果 | 复用现有控制面，不另建一套任务和文件真源 |

当前实现依据：[Java Skill Catalog](../../backend/src/main/java/com/noteweave/artifact/ArtifactSkillCatalogService.java)、[Python Skill Catalog](../../workers/artifact-worker/app/artifact_skill_catalog.py)、[系统 MCP 注册](../../workers/artifact-worker/app/system_mcp_registry.py)、[PDF 导出](../../workers/artifact-worker/app/export_runtime.py)、[既有演示文稿调研](./Artifact可视化产物与MCP-Skill集成调研.md)。

上游依据：[总流程](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/SKILL.md)、[字幕纠错](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/bili-subtitle-asr/references/subtitle-correction.md)、[画面采集与去重](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/bili-keyframes/SKILL.md)、[时间段画面分派](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/bili-knowledge-tree/scripts/assign_frames.py)、[文档模板](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/bili-document-builder/SKILL.md)、[PPTX 交付与校验](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/bili-pptx/SKILL.md)。

## 建议落地顺序

### 1. 先补视频资料包和 PDF 的画面链路

输入保留 B 站 URL、语言和用户约束；新增 `part`、画面密度与是否允许 ASR 等明确参数。一次获取后冻结 `VideoMaterialManifest`：视频/分集标识、字幕原文与纠错稿及来源、时间段、画面清单与去重关系、覆盖缺口、术语表、知识节点到字幕和画面的引用，以及每个文件的摘要。中间文件存在 Run Sandbox；版本和文件元数据仍由 Artifact 控制面管理。

从这份资料包生成 `bilibili_course_note_pdf`。每个含图章节的图必须有画面引用；没有对应画面的章节可以保留文字，但需记录覆盖缺口。验证字幕时间与画面所属分集、图片文件可读、引用存在、PDF 可打开和关键页可渲染。把字幕和画面获取拆成可恢复阶段，避免重新生成 PDF 时再次抓视频。

**验收**：同一输入重跑可复用资料包；一份有画面的测试视频生成的 PDF 中实际出现对应画面；断言不同分集或错误时间段的图无法通过验证；无字幕时能明确报告 ASR 路径或失败原因。

### 2. 从同一知识树派生学习文章和面试问答

新增 `knowledge_blog` 和 `interview_qa` 两种明确产物契约，或先在当前学习类 Skill 上建立版本化模板。共用知识节点、术语表和证据引用；面试问答固定“简要回答 / 详细问答 / 相关知识”。上游模板机制可借鉴，但模板必须作为发布版本的一部分，经服务端 Schema、大小和内容校验后才能进入生产运行。

**验收**：同一资料包生成两份文档，术语与关键结论一致；缺少证据的断言被标记为待核实；模板结构错误在提交 Artifact Version 前失败。

### 3. 新增视频学习 PPTX

新增 `video_learning_deck` Skill、页清单和 PPTX File Contract。先做“原图版”：按知识节点排页、每页绑定画面和文字证据，导出 PPTX 与逐页预览，校验页数、溢出、字体、文件可打开和画面引用。再加分步动画。最后单独试点“原生图形版”，并要求与原图版逐页对照人工抽检；不要把模型重绘结果自动当作已核实的视觉事实。

既有调研中的 Marp 适合快速得到可演示 PPTX，但普通导出的内容通常不可编辑；上游 `python-pptx` + OOXML 路线更接近“图形版可编辑”和逐步动画，代价是模板复刻、字体和跨环境渲染验证。产品应明确区分原图版与图形版的可编辑范围。

**验收**：一段固定视频得到页序、字幕要点和画面一致的 PPTX；逐页预览可读；动画与图形版分别通过结构校验和人工抽检后才对外标称可用。

## 集成边界与风险

- 上游主要是 Agent Skill 与本机浏览器工作流，不是现成的端到端服务。其浏览器会话脚本可能复制 `Local State`、`Cookies` 等登录配置到临时 profile；后台 CDP、登录态与临时目录不能直接搬进多用户 Worker。服务端应使用隔离浏览器、显式凭据策略、域名允许列表、受控存储和登录态副本清理。优先用无登录样本试点，需登录的素材再设计授权路径。[会话脚本](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/scripts/browser_session.py)
- 上游知识树仍由 Agent 编写，脚本只在已有时间段上辅助分派画面；图形版是 Agent 对照截图绘制原生形状，并非自动 OCR 或矢量化。去重依赖画面相似度启发式，小幅文字变化可能漏掉。Markdown/PPTX 的脚本校验不能证明重绘或字幕纠错准确；需保留原字幕与纠错稿的差异、画面接触图和抽检记录。[画面分派](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/bili-knowledge-tree/scripts/assign_frames.py)、[去重实现](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/bili-keyframes/scripts/dedupe_frames.py)
- 多集长视频可能带来 ASR、截图、预览和 LLM 成本。资料包按阶段保存、按内容摘要复用，并给每阶段独立预算与失败原因。
- 上游一键环境配置可能安装依赖并下载大型 ASR 模型；服务端应在部署时固定依赖，运行中只执行能力检查和按需启用。[环境发现脚本](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/scripts/discover_env.py)
- 上游代码为 [MIT](https://github.com/ewkzcz/bili-2-ppt/blob/5ebdd759f9493855b82e49ab1d92319b272f906c/LICENSE)。若复制代码或模板，保留许可与归属；视频画面、字幕的使用范围另按素材授权与平台规则处理。

## 本分支范围

此分支先完成上游版本核对、能力差距与落地顺序。进入实现时，先按第 1 阶段建立测试样本和资料包契约，再修改 Catalog、Worker、导出与前端。当前评估没有把上游脚本注册为生产 Skill，也没有声称 PPTX 或图形版已实现。
