# Artifact 产物生成产品宣传与评测口径

> 调研日期：2026-08-18。本文只引用产品官方页面、官方项目页和 arXiv 原始条目。产品功能会变化，面试前应重新核对。论文数字均为作者在其数据集、模型和评测器上的自报告结果，不是 NoteWeave 成绩。

## 1. 结论

产物生成产品很少公开一个可以横向比较的“生成准确率”。官方宣传集中在五类可观察能力：

1. 从 Prompt、Outline、文档或资料生成初稿。
2. 在生成前确认结构，在生成后逐页或逐块迭代。
3. 保留品牌、主题、模板和布局规则。
4. 导出为 PPTX、PDF、HTML、DOCX 等真实文件，并继续编辑。
5. 用协作、反馈、保留率或人工节时证明结果被采用。

研究论文则将质量拆为内容、设计、连贯性、来源支持、任务完成、可编辑性和视觉布局。NoteWeave 不应宣传“11 个 Skill”或“PDF 可打开”就等于质量领先，更适合报告来源快照、Contract Pass、Claim Support、File Ready/Openable、Repair Collateral、Meaningful Edit、Artifact Kept 和单位接受成本。

## 2. 官方产品如何宣传

| 产品或工具 | 官方页面公开主张 | 可迁移指标 | 不能直接比较的原因 |
|---|---|---|---|
| Microsoft Copilot in PowerPoint | 从 Prompt 或文件创建演示文稿，先澄清受众、风格等，再生成 Outline 和 Slides；结果可继续编辑。官方说明评估结合比较分析、人评、自动化、红队和客户参与指标，并点名用户反馈与 Presentation-kept Rate | 澄清必要性、Outline 接受率、Presentation-kept Rate、人工修改率、红队漏拦截 | 未公开统一数据集、Kept Rate 数值或对外基线 |
| Beautiful.ai Create with AI | 支持 Prompt、Outline、文档或 Source Material；先调整逐页 Outline，再生成设计；可切换布局并保留 Copy 与数字；支持品牌规则、实时协作、分析和可编辑 PowerPoint 导出 | Outline 接受率、布局切换后内容保留、品牌规则通过、PPTX 可编辑元素比例 | 官网主张不是受控 Benchmark，未给公开质量百分比 |
| Notion AI | 总结、脑暴、粗稿、纠错和翻译；官方明确粗稿需要用户编辑，也明确可能输出错误、过时、有害或有偏见内容 | Draft Acceptance、Meaningful Edit、事实错误率、反馈率 | 面向页面内容辅助，不等价于复杂文件渲染或版本化 Artifact Agent |
| Marp | Markdown 写演示文稿，支持主题、CSS、公式和图片，可导出 HTML、PDF 和 PowerPoint | 编译成功、Openable、跨格式结构一致、主题 Contract | 是确定性转换器，不负责资料检索、事实生成或 Agent 恢复 |
| Pandoc | 通过统一文档 AST 在多种标记、Word、PowerPoint、HTML、PDF 和引用格式间转换 | 格式覆盖、转换成功、引用保留、Round-trip Fidelity | 格式支持多不等于内容质量高；部分格式只支持单向转换 |

### 2.1 Microsoft 的评估口径最值得借鉴

Microsoft 官方支持页不是只写“更快制作 Slides”，而是列出：比较分析、人工审核、自动化测试、红队和客户参与指标，并说明用户反馈与 Presentation-kept Rate 用于衡量生成结果有效性。页面同时要求 AI 生成内容必须人工审核和编辑。

这可以转成 NoteWeave 的完整漏斗：

```text
Generated
  -> Passed Deterministic Contract
  -> Opened/Previewed
  -> Kept or Saved
  -> Meaningfully Edited
  -> Shared or Written Back
```

```text
Artifact Kept Rate
  = 生成后在观察窗内未被删除或回滚且发生保存、分享或写回的 Artifact 数
    / 被用户打开审阅的 Artifact 数
```

Kept 不能只定义为“文件仍存在”。观察窗、取消、重复生成、自动归档和测试数据都要排除；还要与 Meaningful Edit 和 Major Rework 一起看。

### 2.2 Beautiful.ai 的关键不是“一键生成”

Beautiful.ai 官方页强调分阶段工作流：接受 Prompt、完整 Outline 或 Source Material，先审阅逐页 Outline，再进入高保真设计；用户可以尝试不同布局，同时保留原 Copy 和数字。官网还公开可编辑 PowerPoint 导出、品牌控制、实时协作和分析能力。

对 NoteWeave 的启发是：若未来做 Presentation，不应只有最终 PPTX 下载。还要观测 Outline 接受、布局切换后的内容不变性、品牌规则、可编辑元素和导出后兼容性。

### 2.3 Notion AI 主动承认“粗稿”边界

Notion 官方帮助页将生成结果称为 Rough Draft 的起点，并列出错误、过时、有害和偏见风险。这个口径比“自动完成文档”更可信。NoteWeave 对报告、Wiki、Resume 等 Markdown 产物也应区分 Draft、Verified Candidate、Saved Version 和 Accepted/Shared，不能把模型生成成功当用户完成任务。

### 2.4 Marp 与 Pandoc 代表确定性 Renderer

Marp 明确从 Markdown 导出 HTML、PDF 和 PowerPoint；Pandoc 明确列出输入输出方向，并通过统一 AST、模板和 Filter 支持多格式。这类工具适合承载文件转换，却不能替代 Source ACL、Evidence、Skill Selection、Agent Recovery 和 Writeback。

合理分层是：

```text
NoteWeave Control Plane
  -> Artifact IR / Markdown
  -> Marp、Pandoc、LaTeX 或受测 SaaS Renderer
  -> File Verification
  -> Artifact Version and Writeback
```

## 3. 论文怎样评测产物生成

### 3.1 PPTAgent 与 PPTEval

PPTAgent 将文档到 Presentation 拆成参考演示分析、功能类型与内容 Schema、Outline、参考页选择和编辑动作。PPTEval 明确使用三个维度：Content、Design 和 Coherence。

这说明只测内容章节或文件能否打开不够。NoteWeave 当前 Markdown Artifact 能覆盖 Content Contract 与部分 Evidence；Design 和 Coherence 的视觉部分仍缺 Renderer 与渲染后评测。

来源：[PPTAgent: Generating and Evaluating Presentations Beyond Text-to-Slides](https://arxiv.org/abs/2501.03936)

### 3.2 UniPPTBench 与场景化评测

UniPPTBench 划分 Vague Prompt、Long Document、Multimodal Document 和 Multi-source 四种输入场景，并指出通用视觉吸引力、布局和整体连贯性不能替代 Grounded Compression、Visual-text Alignment 与 Cross-source Synthesis 等场景特定能力。

NoteWeave 应按 `Workspace 单 Source / 多 Source / 视频转写 / Research Writeback` 切片，不能用一个 Artifact 总分覆盖输入难度差异。

来源：[UniPPTBench: A Unified Benchmark for Presentation Generation Across Diverse Input Settings](https://arxiv.org/abs/2605.17356)

### 3.3 PresentBench 与逐实例 Rubric

PresentBench 包含 238 个实例，每个实例平均 54.1 个二值 Checklist，并报告这种细粒度 Rubric 比整体主观打分更符合人类偏好。它的关键思想不是照搬样本量，而是让每个任务根据背景材料拥有可验证、实例特定的验收项。

NoteWeave 的 Quiz 可以检查题目数、答案、解析和指定知识点；Wiki 检查定义、机制、相关页和引用；课程讲义检查章节、转写忠实、公式、文件和版式。不同 Skill 不应共用一个“整体质量 1 至 5 分”。

来源：[PresentBench: A Fine-Grained Rubric-Based Benchmark for Slide Generation](https://arxiv.org/abs/2603.07244)

### 3.4 SlidesGen-Bench 的三维框架

SlidesGen-Bench 将终端输出统一渲染后，从 Content、Aesthetics 和 Editability 三个维度评测，并建立覆盖 9 个系统、7 个场景的人类偏好对齐数据集。其方法提醒我们：只对生成代码或 Markdown 做检查，无法证明最终视觉文件质量。

来源：[SlidesGen-Bench: Evaluating Slides Generation via Computational and Quantitative Metrics](https://arxiv.org/abs/2601.09487)

### 3.5 AeSlides 的可验证布局指标

AeSlides 将宽高比合规、留白、元素碰撞和视觉不平衡变成可计算奖励。作者在自己的设置中报告：Aspect Ratio Compliance 从 36% 到 85%，Whitespace 降低 44%，Element Collisions 降低 43%，Visual Imbalance 降低 28%，人评从 3.31 到 3.56。

这些数字只能说明“布局可被确定性指标约束”，不能与 NoteWeave 当前 Markdown/PDF 直接横比。NoteWeave 只有在实现页面级 Renderer、相同缺陷定义和人工校准后，才可报告 Layout Overflow、Collision 和 Visual Balance。

来源：[AeSlides: Incentivizing Aesthetic Layout in LLM-Based Slide Generation via Verifiable Rewards](https://arxiv.org/abs/2604.22840)

## 4. NoteWeave 可采用的对标矩阵

| 层 | 最小指标 | 计算对象 | 当前成熟度 |
|---|---|---|---|
| 意图与规划 | Skill Top-1、Missing Slot、Clarification Precision/Recall、Outline Acceptance | 冻结意图集与用户事件 | 部分实现，统一评测为 `[目标设计]` |
| 内容 | Structure Completeness、First/Final Contract Pass、Claim Support | Candidate、Validator Trace、Claim Gold | 结构已实现，Claim Gold 为 `[目标设计]` |
| 视觉 | Overflow、Collision、Asset Render、Visual Preference | 渲染页面与视觉 Gold | `[目标设计]` |
| 可编辑性 | Editable Element Ratio、Edit Instruction Success | PPTX/DOCX 元素与编辑任务 | `[目标设计]` |
| 文件 | File Ready、Openable、Digest Match、Cross-renderer Consistency | Manifest、对象、阅读器矩阵 | Ready/Digest 部分实现 |
| 采用 | Artifact Kept、Meaningful Edit、Major Rework、Share/Writeback | 产品事件与 Revision Diff | `[生产待验证]` |
| 效率 | Authoring Time Saved、Verified Throughput、Cost per Accepted Artifact | A/B 任务、成本 Receipt | `[生产待验证]` |
| 安全 | Scope Violation、Dangerous Tool Blocking、Copyright/Provenance Coverage | 红队、ACL、Asset Receipt | 部分实现，版权 Receipt 为 `[目标设计]` |

## 5. 什么数字可以宣传

### 5.1 当前可以说

- Catalog 注册 11 个内置 Artifact Skill，但要同时说明它们主要交付 Markdown。
- 特定 B 站讲义 Skill 存在字幕/转写、LaTeX PDF、Host 归档和下载链路。
- Version、File Metadata、SHA-256、比较、追加式回滚、保存为 Source 和知识写回存在真实代码入口。
- 定向测试通过数量可以作为该版本回归证据，但不能外推生产质量与成功率。

### 5.2 完成评测后才可以说

- Contract Pass、Claim Support、PDF Openable、Artifact Kept、Major Rework、Authoring Time Saved、Cost per Accepted Artifact。
- 任何“比一次 Prompt 提升多少”的数字，都必须固定任务、模型、资料、Prompt/Skill/Validator 版本和失败分母。

### 5.3 不能宣传

- 用论文的 85%、43% 或其他自报告数字当项目结果。
- 用 Markdown Skill 数量暗示支持 11 种文件格式。
- 用 `%PDF-1.` Header 与非空文件证明视觉质量。
- 用生成耗时替代人工总耗时，忽略审核和返工。
- 用“行业都没有公开准确率”作为不做内部 Gold 的理由。

## 6. 面试官会追问的可比性

### 6.1 你的 92.9% 为什么比论文好

不能这么比较。任务、输入、格式、模型、评测器、失败过滤和版本都不同。理想消融数据只用于解释实验设计；真实对比必须在同一 Gold、同一 Renderer 和同一 Rubric 上运行双方系统。

### 6.2 为什么不直接报用户节时

节时受任务难度、参与者熟练度、审核严格度和等待时间影响。应随机或交叉安排纯人工与 Agent 辅助任务，记录主动编辑时间、等待时间、重大返工和最终接受，至少报告中位数与分布。

### 6.3 Presentation-kept Rate 会不会被“懒得删除”抬高

会。Kept 必须要求观察窗内出现保存、继续编辑、分享、下载或写回等有效采用事件，并排除自动归档、测试任务、重复版本和未打开结果。还要同时看 Meaningful Edit 与 Major Rework。

### 6.4 为什么产品宣传多格式，你却只做 Markdown 和特定 PDF

NoteWeave 当前核心是来源治理、验证、恢复、版本和知识回流，不是完整 Office Renderer。先把逻辑产物与一个高价值 PDF 场景闭环，再根据真实需求接入 Marp、Pandoc、Office API 或受测 SaaS Provider，是更诚实也更可维护的边界。

## 7. 一手来源

### 官方产品与工具

- [Microsoft Support: Create a new presentation with Copilot in PowerPoint](https://support.microsoft.com/en-us/office/create-a-new-presentation-with-copilot-in-powerpoint-3222ee03-f5a4-4d27-8642-9c387ab4854d)
- [Beautiful.ai: AI Presentation Maker](https://www.beautiful.ai/designerbot)
- [Notion Help: Using Notion AI to extend your impact](https://www.notion.com/help/guides/using-notion-ai)
- [Marp: Markdown Presentation Ecosystem](https://marp.app/)
- [Pandoc: A universal document converter](https://pandoc.org/)

### 论文与 Benchmark

- [PPTAgent and PPTEval](https://arxiv.org/abs/2501.03936)
- [UniPPTBench and UniPPTEval](https://arxiv.org/abs/2605.17356)
- [PresentBench](https://arxiv.org/abs/2603.07244)
- [SlidesGen-Bench](https://arxiv.org/abs/2601.09487)
- [AeSlides](https://arxiv.org/abs/2604.22840)
