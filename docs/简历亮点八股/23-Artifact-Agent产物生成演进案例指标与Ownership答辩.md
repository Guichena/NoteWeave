# Artifact Agent：产物生成演进、质量指标与 Ownership 答辩

> 默认主回答见[Agent 执行与 Artifact 一体化面试手册](32-Agent执行与Artifact产物一体化面试手册.md)。本文只负责 Artifact 业务演进、学校产物案例、质量与成本指标、产品宣传口径和 Ownership；通用 Task、Quota、Kafka 与 Lease 不在这里重复展开。

> 本文把 Artifact 业务与通用 Agent 执行框架分开说明。`[当前实现]` 只表示源码、迁移或测试能够证明；`[演练案例]`、`[理想消融数据]` 和 `[生产待验证]` 不能冒充真实线上结果。通用调度、配额、Kafka、Lease、Fencing 和 Callback 见 [Agent 执行框架专项](21-Agent执行框架演进案例消融与Ownership答辩.md)。
>
> 请求、输入快照、结果载荷、版本、文件、审批、失败和写回的代码级字段与第二轮缺口审计，见 [Artifact Agent 契约级数据模型与面试官下钻](24-Artifact-Agent契约级数据模型与面试官下钻.md)。
>
> 面试推荐方案以[面试推荐架构与规模化演进裁决](43-面试推荐架构与规模化演进裁决.md)为准：Kafka Consumer 只幂等登记 Durable Execution，Host 预留唯一 Artifact Version ID，Worker 返回 Candidate。内容 Contract 通过后，Host 创建内部 `DELIVERY_PENDING` Version 并冻结全部必需文件 Manifest；必需文件全部 `READY` 后才提升为用户可见的 `READY`，写回只能绑定 `READY` Version。下面的 Delivery Token、长 Poll 和回调后建 Version 只描述当前实现与迁移起点。

## 1. 审查结论

Artifact Agent 不是“Research Agent 换一个 Prompt”，也不只是执行框架的 Demo。它解决的是把 Workspace 资料转换为可验证、可版本化、可下载、可比较、可回滚和可写回的业务产物。

现有代码已经覆盖 Catalog、Job、冻结输入、异步执行、Verifier/Repair、不可变 Version、Markdown 文件归档、特定 PDF 导出、版本比较、追加式回滚、保存为 Source 和知识写回。此前资料对可靠执行讲得较深，但缺少独立的产物能力矩阵、按类型质量指标、完整用户案例、视觉与可编辑性边界、人工节时口径和产品对标。因此不能只背 `04` 和 `21` 就声称 Artifact Agent 资料完整。

最重要的事实边界如下：

- `[当前实现]` Java 与 Python Catalog 对齐注册 11 个内置 Skill。
- `[当前实现]` 11 个 Skill 都能产生 Markdown 逻辑产物，Host 可将 Markdown 归档为对象文件。
- `[当前实现]` `bilibili_course_note_pdf` 还会通过受控 MCP 调用 LaTeX PDF 导出，并由 Host 拉取、计算 SHA-256、写入对象存储和 `artifact_file`。
- `[当前实现]` PPTX、DOCX 和通用 PDF 排版器不存在，不能写成已支持。
- `[当前实现]` 通用 Verifier 检查标题、章节、必需短语和 Evidence Coverage；Quiz、Wiki、Resume 还有类型特定规则。
- `[当前实现]` PDF 测试证明能产出以 `%PDF-1.` 开头的非空文件，生产链路保存大小与摘要；尚未实现逐页渲染、文字溢出、字体替换、图片清晰度和视觉回归门禁。

## 2. 为什么需要 Agent 生成产物

学校原型最早面对的是课程资料、实验文档、视频和复习材料。用户不是只想得到一段聊天答案，而是想得到能继续编辑、分发、归档或写回知识库的报告、题集、学习指南和讲义。

| 方案 | 适合场景 | 主要问题 | NoteWeave 的选择 |
|---|---|---|---|
| 固定模板引擎 | 字段固定、内容短、无需外部资料 | 无法处理资料范围、证据差异和缺失字段 | 用于最终结构与样式约束 |
| 一次 Prompt | 低风险 Markdown 草稿 | 输入、结构、引用、失败位置和副作用不可控 | 仅作为早期原型和消融基线 |
| 受控 Artifact Agent | 多来源、长任务、类型规则、外部工具、版本和写回 | 需要维护 Skill、Graph、Validator 和运行状态 | 当前主路径 |

Agent 的价值不是“会自由思考”，而是能在冻结输入和能力边界内完成资料获取、结构规划、分节生成、验证、局部修复、导出和受控提交。固定规则控制不可违反的 Contract，模型只负责需要语义判断的内容生成。

## 3. 从学校原型到当前实现

```text
V0 聊天框一次生成 Markdown
  -> V1 按报告、题集、Wiki 写 Action 分支
  -> V2 Skill Catalog 统一输入 Schema 和类型绑定
  -> V3 Skill Graph 拆分获取、生成、验证与修复
  -> V4 Job、Task、Outbox、Waiting/Resume 支撑长任务
  -> V5 Artifact Version、File Manifest、Compare、Rollback
  -> V6 Host Writeback 与 B 站讲义 PDF 受控导出
```

这是一条设计因果线，不表示项目有六次真实生产发布。

### 3.1 V0 为什么失败

同一个 Prompt 同时承担资料选择、内容规划、格式控制和文件输出，出现四类问题：不同资料版本导致结果不可复现；缺少章节只能整篇重做；模型声称生成文件但没有真实文件；用户修改 Source 后旧结果可能覆盖新版本。

### 3.2 为什么从 Action 演进到 Skill

Action 分支能快速支持新类型，但 Prompt、输入字段、能力、校验和修复散落在代码中。Skill 把这些内容变成版本化契约，Host 先做确定性输入校验，Worker 再绑定 Action、Graph、Prompt Recipe、Style Profile 和 Capability。代价是 Java Catalog 与 Python Catalog 必须保持契约一致，并建立 Catalog Diff 测试。

### 3.3 为什么产物必须版本化

生成结果不是临时消息。再生成、修复、回滚和写回都可能改变内容或文件，因此 `ArtifactJob` 表示用户意图，`ArtifactRun` 表示一次不可变的生成、再生成或追加式回滚请求，`ExecutionAttempt` 表示基础设施执行代际，Task 表示可调度工作，`ArtifactVersion` 表示通过门禁的不可变逻辑结果，`ArtifactFile` 表示该版本的文件载荷。回滚采用追加式复制，不改写旧 Version。

## 4. 当前能力矩阵

| Skill Key | 用户产物 | 输入特点 | 逻辑交付 | 类型特定校验 | 二进制导出 |
|---|---|---|---|---|---|
| `resume_highlight` | 简历亮点 | 语言 | Markdown | 候选、关键词等结构规则 | 无 |
| `study_guide` | 学习指南 | 语言 | Markdown | 通用章节与 Evidence | 无 |
| `quiz_pack` | 测验题集 | 语言 | Markdown | 题目、答案解析、评分结构 | 无 |
| `wiki_page` | Wiki 页面 | 语言 | Markdown | 定义、机制、相关页面结构 | 无 |
| `bilibili_course_note_pdf` | B 站图文讲义 | 必填 URL、语言 | Markdown + PDF | 课程笔记通用 Contract | LaTeX PDF |
| `report_draft` | 结构化报告 | 语言 | Markdown | 通用章节与 Evidence | 无 |
| `faq_draft` | FAQ 草稿 | 语言 | Markdown | 通用章节与 Evidence | 无 |
| `structured_note` | 结构化笔记 | 语言 | Markdown | 通用章节与 Evidence | 无 |
| `video_summary` | 视频总结 | 必填 URL、语言 | Markdown | 通用章节与 Evidence | 无 |
| `audio_minutes` | 音频纪要 | 语言 | Markdown | 通用章节与 Evidence | 无 |
| `course_notes` | 课程笔记 | 可选 URL、语言 | Markdown | 通用章节与 Evidence | 无 |

这 11 个 Skill 是产品入口数量，不等于 11 套独立模型，也不等于 11 种文件格式。大部分共用通用运行图和结构化生成能力，差异主要在 Action、Outline、Prompt Recipe、Evidence 要求和类型校验。

## 5. 一条真实代码链路

以“基于数据库课程资料生成学习指南”为例：

1. 前端从 `/api/v2/skills` 读取 Catalog，提交 `skill_key=study_guide`、语言、用户要求和 Source Scope。
2. `ArtifactJobService.createJob()` 校验 Workspace 与 Skill 输入，在事务中创建 Job、Task、冻结输入和 Artifact Outbox。
3. `[当前实现]` Kafka Consumer 获取命令并保持 Delivery 所有权，Worker 通过内部接口拉取冻结输入，而不是读取 Workspace 当前 Head。
4. `intent_compiler` 与 Registry 把 Skill 绑定到 Action、Prompt、Style、Graph 和 Capability，生成执行计划。
5. Worker 获取 Source 内容，构造 CCO，按 Graph 运行内容生成节点。
6. `verifier.py` 检查标题、Outline、必需短语和 Evidence Coverage；可修复缺陷进入有限局部 Repair，最后重新执行全局 Contract。
7. `composer.py` 生成 Markdown，`export_runtime.py` 对普通 Skill 返回 `MARKDOWN/NOT_REQUIRED`。
8. Worker 用 Delivery Token 回调；Host 校验任务状态和回调身份后追加 `ArtifactVersion`。这是当前协议，推荐方案改为 Host 在运行前预分配唯一 Version ID，Worker 只返回绑定该 ID 的 Candidate。
9. `ArtifactExportService.materializeExports()` 将 Markdown 写入对象存储并保存格式、MIME、大小、SHA-256 和状态。
10. 用户可以查看版本、比较两个版本、再生成、追加式回滚、保存为 Source 或写回知识区。

`bilibili_course_note_pdf` 在第 7 步多一条真实分支：Worker 调用系统注册的 Bilibili MCP 获取字幕或转写，再用 `render_latex_pdf` 生成 `.tex` 和 PDF；Host 依据 `export_trace` 拉取 PDF，校验文件名与非空载荷后归档。

推荐迁移后，命令链路改为 `Kafka Command -> Durable Execution Registration -> Offset Commit -> Scheduler Claim -> Worker Candidate -> 内容验证 -> Host DELIVERY_PENDING Version/Manifest -> DeliveryAttempt -> Staging File -> 文件验证 -> File READY -> Version READY`。内容通过后先冻结全部必需文件义务，避免第一轮导出没有启动时失败样本从 File Ready Rate 分母消失。Kafka 只衡量命令接纳，小时级执行由 Queue Age、Active Permit、Execution Lease 和 Resource Class 衡量。Worker Repository 可以保留 Attempt 调试记录，但不能再创建第二套业务 Version。

## 6. 一个完整学校场景

### 6.1 可按当前实现演示的版本

`[演练案例]` 教师把数据库课程讲义、实验说明和复习范围放进 Workspace，选择学习指南 Skill，要求中文输出，覆盖事务、索引、锁、日志和复制，并保留资料引用。

- 输入：冻结 Source Scope、语言和用户要求。
- 规划：Skill 映射到固定 Action 与 Graph，生成章节 Outline。
- 生成：按章节读取材料、生成内容并记录 Evidence Coverage。
- 验证：检查必需章节、短语和 Evidence；局部缺章只修该节点。
- 交付：保存不可变 Version 和 Markdown 文件。
- 后续：教师比较两个版本，回滚到旧版，或保存为新 Source 继续进入检索链路。

这个故事与现有代码一致。不能把它讲成已经生成可编辑 PPTX。

### 6.2 B 站讲义 PDF 版本

`[演练案例]` 用户提交 B 站数据库课程链接，选择 B 站讲义 PDF Skill。系统校验 URL，获取字幕；字幕不可用时进入转写 Provider 路径；生成结构化章节后调用 LaTeX 导出；Worker 回调包含导出 Trace，Host 将 Markdown 和 PDF 分别归档。`[当前实现]` 这里仍有明确边界：前端按 Trace 为 `COMPILED` 展示下载动作，尚未与 Host 的 PDF `READY` Manifest 做联合门禁。

这条链路证明“特定 Skill 的 PDF 生成和归档”，不证明任意 Markdown 都能转 PDF，也不证明视觉版式达到出版标准。

### 6.3 不能当作当前实现的版本

“生成 20 页可编辑 PPTX，自动选图、检查每页溢出并保持元素可编辑”属于 `[目标设计]`。若未来实现，至少还需 Presentation IR、PPTX Renderer、字体与图片资产策略、逐页渲染、视觉回归、元素可编辑性检查和 Office/LibreOffice 兼容矩阵。

## 7. 产物质量不是一个准确率

### 7.1 内容 Contract

```text
Structure Completeness = 通过结构检查的必需章节数 / 必需章节总数
First Pass Yield = 首轮通过内容 Contract 的 Candidate 数 / 已接受首轮 Contract 判定的 Candidate 数
Final Contract Pass = 最终通过内容 Contract 的 Candidate 数 / 已接受最终 Contract 判定的 Candidate 数
Repair Yield = Repair 后通过最终 Contract 的 Candidate 数 / 进入 Repair 的 Candidate 数
Repair Attempt Success = 本次 Repair 关闭目标缺陷且未破坏最终 Contract 的次数 / Repair Attempt 数
Collateral Change Rate = Repair 导致非目标正确区域变化的次数 / Repair 次数
```

Final Pass 高但 First Pass 很低，说明系统靠反复修复堆出结果；Repair Yield 高但 Collateral Change 也高，说明局部修复边界不可靠。若同一 Candidate 可以多次 Repair，还要单列 Repair Attempt Success，不能把 Attempt 级分母和 Candidate 级 Yield 混在一起。

### 7.2 证据与事实

```text
Claim-Evidence Coverage = 被一组 Evidence 完整支持的原子 Claim 数 / 全部可核验原子 Claim 数
Section Evidence Coverage = 满足 Skill 证据密度要求的章节数 / 要求证据的章节数
Invalid Citation Rate = 不存在、越权或无法支持 Claim 的引用数 / 全部引用数
```

现有 Evidence Coverage 是结构化门禁的一部分，但链接存在不等于事实被完整支持。要宣传 Claim-Evidence Coverage，必须建立原子 Claim Gold 和人工审核，并与只检查漏引的 Citation Completeness 分开，不能用 Citation 数量替代。

### 7.3 文件交付

```text
File Ready Rate = READY 且对象可读取的必需文件数 / 内容通过后冻结的全部必需文件数
Openable Rate = 在批准解析器或阅读器矩阵中成功打开的文件数 / 被测文件数
Digest Match Rate = 下载后摘要与 Manifest SHA-256 一致的文件数 / 下载文件数
Export Fidelity = 导出后仍满足内容、结构和资产 Contract 的检查数 / 导出检查总数
```

当前代码能计算 File Ready、大小和 Digest；Openable 与 Fidelity 只有局部 PDF 测试证据，尚未形成跨阅读器评测。

### 7.4 视觉与可编辑性

```text
Layout Overflow Rate = 存在文字、表格或图片越界的页面数 / 总页面数
Asset Render Success = 成功显示且分辨率达标的资产数 / 应显示资产数
Editable Element Ratio = 可在目标编辑器中独立修改的语义元素数 / 应可编辑元素数
Cross Renderer Consistency = 关键视觉与结构检查一致的文件数 / 被测文件数
```

这些指标对 PPTX、DOCX 和复杂 PDF 很重要，但当前项目没有对应 Renderer 与视觉 Gold，必须标记 `[目标设计]`，不能因 PDF 可打开就声称排版质量好。

### 7.5 用户业务价值

```text
Authoring Time Saved = (纯人工中位时长 - Agent 辅助中位时长) / 纯人工中位时长
Major Rework Rate = 需要重做结构或核心事实的产物数 / 被审核产物数
Meaningful Edit Rate = 审核者进行事实、结构或结论修改的产物数 / 被审核产物数
Verified Output Throughput = 通过最终 Contract 的文档或页面数 / 人工小时
Cost per Accepted Artifact = 模型、工具、转写、存储和重试成本 / 观察窗口内有明确用户接受动作的产物数
```

节时实验必须固定任务、资料、验收 Rubric 和参与者熟练度；Agent 运行时间与人的等待时间要分别记录。只统计“点击生成到下载”会忽略审核和返工。

## 8. Gold Set 怎样建立

按产物类型分层，不做一个含糊总分：

| 切片 | 建议样本 | Gold 内容 | 自动检查 | 人工检查 |
|---|---:|---|---|---|
| 报告/学习指南 | 20 | 必需章节、关键 Claim、资料范围 | Schema、章节、引用存在 | 事实支持、结构合理 |
| Quiz | 20 | 题目、答案、解析、难度 | 数量、字段、答案存在 | 答案正确、干扰项质量 |
| Wiki | 15 | 定义、机制、关系、来源 | 章节、链接、相关页数量 | 概念准确、关系合理 |
| Resume | 15 | 事实卡、可宣传指标、禁写项 | 结构、关键词、敏感字段 | 是否夸大、表达质量 |
| B 站讲义 PDF | 10 | 字幕片段、章节、文件要求 | URL、章节、PDF Header、Digest | 字幕忠实、可读性、版式 |

Gold 必须锁定 Source Snapshot、Skill/Graph/Prompt/Model/Validator 版本和运行配置。每次改动输出逐样本结果与失败分类，不能只报平均分。

## 9. 理想消融数据

以下全部是 `[理想消融数据]`，用于演练如何设计实验和解释结果，不是仓库测试或真实用户结果。

### 9.1 内容型 Artifact

假设固定 70 个报告、Quiz、Wiki 和 Resume 任务，模型、资料与硬件一致：

| Variant | First Pass | Final Contract Pass | Claim-Evidence Coverage | Major Rework | P95 | 单位通过成本 |
|---|---:|---:|---:|---:|---:|---:|
| 单次 Prompt | 60.0% | 60.0% | 72.8% | 31.4% | 88 s | 0.74 元 |
| + 类型 Schema/Outline | 75.7% | 75.7% | 78.5% | 22.9% | 105 s | 0.82 元 |
| + Evidence Gate | 78.6% | 78.6% | 89.7% | 15.7% | 123 s | 0.96 元 |
| + 局部 Repair/Final Gate | 78.6% | 92.9% | 92.1% | 8.6% | 158 s | 1.08 元 |

Schema 主要改善结构稳定性；Evidence Gate 主要改善 Claim-Evidence Coverage；Repair 把最终通过率提高 14.3 个百分点，但不改善 First Pass，并增加时延和成本。若只报 92.9%，面试官会继续问成本、初次质量和 Verifier 误判。

### 9.2 PDF 交付

假设固定 30 个公开视频讲义任务，包含有字幕、无字幕、长视频、中文公式和图片缺失：

| Variant | Transcript Ready | PDF Compiled | Openable | Digest Match | 人工重大返工 |
|---|---:|---:|---:|---:|---:|
| 仅远程字幕 + 一次导出 | 21/30 | 19/30 | 19/19 | 19/19 | 11/19 |
| + 转写 Fallback | 28/30 | 25/30 | 25/25 | 25/25 | 10/25 |
| + 有界 Repair/文件门禁 | 28/30 | 27/30 | 27/27 | 27/27 | 5/27 |

`Openable=100%` 只说明成功交付的文件能被测试阅读器打开；失败的 3 个任务仍需保留在任务级分母中。

### 9.3 理想数字达到后是否值得宣传

以下判断仍以第 9 节的 `[理想消融数据]` 为前提。它回答宣传门槛，不把理想值改写成实测值。

| 理想结果 | 若真实复现实验后的判断 | 可以怎样说 | 不能怎样说 |
|---|---|---|---|
| Final Contract Pass `92.9%`，相对一次 Prompt `60.0%` | 对固定内部 Gold 是明显提升，适合写简历 | 固定 70 个任务、同模型下提升 32.9 个百分点，并报告成本与 P95 | 行业 SOTA、准确率 92.9% |
| Claim-Evidence Coverage `92.1%` | 若由原子 Claim Gold 和双人标注得到，属于有价值的内容指标 | 报数据集、标注一致性、无支持 Claim 数和切片 | 引用准确率 92.1%，或模型事实正确率 92.1% |
| PDF Compiled `27/30`、交付内 Openable `27/27` | 任务级成功率仍是 90%，可用于试点复盘但不够证明成熟生产 SLO | 30 个固定视频任务中 27 个完成，已交付文件全部通过指定阅读器 | PDF 成功率 100%，生产可用性 100% |
| Major Rework `5/27` | 约 18.5% 仍需重大返工，说明能辅助而非自动替代 | 与基线 `11/19` 一起报告返工下降和失败分类 | 无需人工审核、自动完成讲义 |
| 单位通过成本 `1.08 元`、P95 `158 s` | 只有结合人工节时和接受率才有业务意义 | 质量提升同时增加成本与尾延迟，给出每个接受产物成本 | 成本低、性能好，不给任务规模和 Provider |

论文和产品没有与 NoteWeave 完全同口径的公开阈值，因此即使真实复现这些理想值，也只能宣传为“固定内部 Gold 相对基线的可复现提升”，不能宣传为行业领先。真正更有说服力的是同时给出 Manifest、失败样本、成本和人工采用事件。

## 10. 与同类产品怎样比较

外部产品的一手来源与口径见 [Artifact 产物生成产品宣传与评测口径](../research/Artifact产物生成产品宣传与评测口径.md)。比较时遵守三条规则：

1. 官网的“秒级生成”“支持多格式”“可协作编辑”是功能或体验主张，不等于固定任务集质量分数。
2. 用户数、融资、模板数量和模型 Benchmark 不能推导 NoteWeave 的 Artifact 质量。
3. NoteWeave 只比较同一输入资料、同一目标格式、同一人工验收 Rubric 下的任务结果，同时报告失败、返工、成本和尾延迟。

NoteWeave 更适合宣传的差异不是模板数量，而是来源快照、证据门禁、不可变版本、局部修复、受控写回和恢复 Trace。前提是用 Gold 与运行 Receipt 证明，不能只展示架构图。

## 11. 关键 Trade-off

### 11.1 Markdown 作为统一逻辑表示

优点是易生成、易 Diff、易保存为 Source，也便于类型校验。缺点是不能表达 PPTX 元素、Word 样式、分页、母版和复杂图表。当前先把内容正确性和版本治理做深，只对一个高价值场景增加 PDF Renderer。

### 11.2 类型 Skill 而不是万能 Prompt

类型 Skill 让输入、章节、证据和校验明确，代价是 Catalog 与测试矩阵增长。只有复用率高、验收规则明确的产物才应成为内置 Skill；一次性需求保留通用报告草稿，不为每个 Prompt 建 Skill。

### 11.3 局部 Repair 而不是整篇重生成

局部 Repair 节省 Token 并减少已正确内容漂移，但需要稳定章节标识、依赖范围和 Final Gate。无法定位缺陷影响范围时宁可整篇重生成；涉及事实冲突时不能只补一句话绕过 Evidence Gate。

### 11.4 Host 持有文件与写回

Worker 适合模型、转写和渲染依赖，Host 持有 Workspace ACL、Version、File Metadata 和最终写回。代价是跨服务 Callback 和文件拉取更复杂，但避免 Worker 凭内部执行身份直接改业务真源。

## 12. 安全与版权追问

Source、字幕、网页、Skill 描述和 MCP 返回都视为不可信数据，不能修改 System Policy、Capability 或写回目标。执行层只接受结构化参数，工具和网络经过 Allowlist；最终结果还要做敏感信息、越权引用和危险内容检查。当前路径与文件名有约束，但 `mcp_sandbox_root` 只是目录边界，不等于完整 OS Sandbox。

图片、视频和字幕的来源、获取方式、许可或用户授权状态应进入 Receipt。当前 B 站链路能记录来源与生成 Trace，不代表已经解决所有版权许可；没有授权时应只保留文本引用或要求用户确认。

## 13. 简历表达

### 13.1 当前可写

> 设计并实现 Artifact Agent，将 Workspace Source、Note、Wiki 与 Research 结果编译为 11 类版本化 Skill 任务，通过结构/Evidence Contract、局部 Repair、不可变 Artifact Version、文件 Manifest、版本比较、追加式回滚和 Host 受控写回交付 Markdown 产物；针对 B 站课程讲义接入字幕/转写与 LaTeX PDF 导出链路。

> 将内容生成与文件交付拆分：Python Worker 处理冻结输入、Skill Graph、Verifier/Repair 和特定 PDF 渲染，Java Host 持有 Workspace ACL、Job/Version、对象归档、SHA-256、回滚与写回，避免 Worker 直接修改业务真源。

### 13.2 完成固定评测后才可写

> 在固定 Artifact Gold Set 上，最终 Contract Pass、Claim Support、PDF Openable、Major Rework 和单位通过成本分别达到评测 Manifest 记录的结果；相较一次 Prompt 的提升按类型、失败样本和成本共同报告。

### 13.3 禁止写

- 支持 PPTX、DOCX 或任意格式导出。
- PDF 可打开，所以视觉排版准确率 100%。
- 11 个 Skill 等于 11 个 Agent 或 11 个模型。
- Verifier 能保证内容绝对正确。
- 版本回滚等于外部知识写回也自动回滚。
- 一个人与 AI 完成项目，所以可以省略测试和事实标签。

## 14. Ownership 答辩

### 14.1 这个功能由你和 AI 一起写，你的工作是什么

我负责把“生成文档”拆成内容契约、运行状态、文件交付和业务写回四个边界，决定哪些类型值得做 Skill，定义 Java Host 与 Python Worker 的权限，设计 Version/File/Receipt 不变量，审查 AI 生成的编译、校验、回调和前端代码，并用测试验证失败窗口。AI 提高实现速度，但产品范围、架构取舍、事实核对和最终验收由我负责。

### 14.2 为什么只做一个特定 PDF，没有一次做全格式

多格式不是多写几个文件后缀。PPTX、DOCX、PDF 的中间表示、分页、字体、图片、图表、可编辑性和验证方法不同。学校场景中视频讲义有明确需求和可复用 LaTeX 工具，所以先打通一条端到端 PDF；其他产物先用 Markdown 保证内容和版本链路，避免把未验证的格式支持包装成能力数量。

### 14.3 最大真实性缺口是什么

缺少真实用户 Artifact Gold、人工节时对照、跨阅读器文件矩阵和视觉质量评测。代码能证明链路和不变量，测试能证明受控样本，但不能推出生产质量、用户效率或市场竞争力。

## 15. 面试官连续追问

### 15.1 这与普通文档生成接口有什么区别

普通接口返回一次文本；Artifact Agent 固化输入与 Skill 版本，按图执行并保留 Trace，通过类型 Contract 后生成不可变 Version 和 File Manifest，支持比较、回滚和受控写回。区别在可恢复、可验证和可治理，不在 Prompt 更长。

### 15.2 Markdown 和 PDF 内容不一致怎么办

当前 PDF 从同一次结构化章节生成，但还缺少导出后内容等价验证。生产方案应从统一 Artifact IR 派生 Markdown 与 PDF，并对标题、章节、表格、引用和摘要做双向提取对账；摘要相同只证明文件字节未变，不证明语义一致。

### 15.3 如何验证 Quiz 答案真的正确

结构校验只能证明题目、答案和解析字段存在。答案正确性需要把题目拆成可核验 Claim，回到冻结 Source 做 Evidence 对齐，必要时用规则求解或人工 Gold。LLM Judge 可以辅助排序，但不能独立批准高风险答案。

### 15.4 如何防止回滚覆盖用户新修改

Artifact 内部回滚是追加新 Version。写回外部 Source/Wiki 时必须带目标期望版本或 Revision；当前 Head 不匹配就返回冲突，保留 Artifact 结果供比较，不能 Last Write Wins。

### 15.5 为什么不用 Gamma、Canva 或 Office API

它们在设计模板、协作编辑和格式兼容上更成熟。NoteWeave 的核心问题是学校私有资料范围、来源版本、证据、长任务恢复和知识回流。若未来要做高质量 Presentation，合理方案是保留 NoteWeave 的输入、证据和版本控制面，把渲染委托给经过评测的 Provider，而不是重写完整 Office 引擎。

## 16. 后续验证优先级

| 优先级 | 补全项 | 完成证据 | 宣传解锁 |
|---|---|---|---|
| P0 | 11 个 Skill 的锁定 Gold 与失败分类 | Manifest、逐样本结果、人工标注 | 可报按类型 Contract/Claim Support |
| P0 | B 站 PDF 端到端样本矩阵 | Transcript、Compile、Openable、Digest、失败 Receipt | 可报特定 PDF 交付结果 |
| P1 | Verifier Precision/Recall 与 Repair Collateral | 双人标注、差异报告 | 可解释 Repair 收益 |
| P1 | 人工与 Agent 辅助 A/B | 任务、参与者、时长、返工和接受事件 | 可报节时与返工 |
| P1 | PDF 逐页渲染和视觉检查 | 页面图片、溢出/字体/资产 Gold | 可报视觉质量 |
| P2 | Presentation IR 与 PPTX Provider 试点 | 可编辑元素、跨 Office/LibreOffice 矩阵 | 才能宣传可编辑 PPTX |

## 17. 最终面试官审查

| 维度 | 当前资料已经能回答 | 仍需证据或实现 |
|---|---|---|
| 业务起点 | 学校课程资料到可交付产物 | 真实教师/学生访谈与采用事件 |
| 真实能力 | 11 个 Markdown Skill、特定 B 站 PDF | 通用 PDF、PPTX、DOCX |
| 完整链路 | Catalog 到 Version/File/Writeback | 持续 E2E 运行 Receipt |
| 内容质量 | 结构、Evidence、Quiz/Wiki/Resume 规则 | Claim Gold、Judge 校准 |
| 文件质量 | READY、大小、Digest、局部 PDF 测试 | 跨阅读器、逐页视觉回归 |
| 业务指标 | 公式、实验设计、理想数据 | 真实节时、返工、接受率和成本 |
| 取舍 | 模板、Prompt、Skill、Renderer 边界 | Provider 采购与格式扩展实测 |
| Ownership | 单人负责决策、AI 辅助实现和审查 | 用 Git、测试和评测 Manifest 现场举证 |

这篇补齐后，Artifact Agent 的面试资料在“架构、代码链路、指标、取舍和边界”上完整；在“真实质量数字和视觉产物竞争力”上仍明确未完成。这个边界本身就是可信回答的一部分。
