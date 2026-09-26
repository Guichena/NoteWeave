# Deep Research 文章化报告执行计划

## 目标

在不修改 canonical Cell、Evidence ledger 与既有审计结论的前提下，把最终输出从“审计记录拼接”升级为可阅读、可引用、可降级的专业研究报告。最终用户默认看到 reader report，审计信息仍可逐项回溯。

## 不变量

- LLM 只能重组已冻结的 Cell 与 Evidence，不能新增事实或来源。
- canonical claim 保持原文并继续通过 Java 严格校验。
- 每个事实段落必须绑定 frozen ledger 中存在的 Evidence key。
- 数字、日期和版本号不得脱离其绑定 Cell。
- 任何模型、JSON 或校验失败都回退到确定性报告，不阻塞 Run 完成。
- 明暗主题、桌面端与 375px 移动端均需实际浏览器验收。

## 输出契约

`research-synthesis-candidate.v2` 同时包含两层：

1. `claims`：现有审计层，保留 exact canonical claim、cell key、evidence keys 与 guarded 状态。
2. `narrative`：阅读层，包含标题、执行摘要、章节、段落引用、对比表、局限性和来源索引。

推荐结构：

```json
{
  "title": "...",
  "executive_summary": "...",
  "sections": [
    {
      "heading": "...",
      "paragraphs": [
        {"text": "...", "cell_keys": ["..."], "evidence_keys": ["..."]}
      ]
    }
  ],
  "comparison_table": {"columns": [], "rows": []},
  "limitations": [],
  "sources": []
}
```

## 分阶段执行

### Phase 0：基线与契约

- [x] 固定现有实现基线：当前 Worker 仅逐 Cell 拼接 Markdown，Java 要求 claim exact match。
- [x] 定义 audit report 与 reader report 的双层边界。
- [x] 定义 narrative JSON 契约、失败回退和验收指标。
- [x] 保存真实 fallback Run `032a3963-f55c-437a-bbc6-1076c877e00e` 作为前后对比样本。

验收：任何文章化改写都不能改变 canonical claim，且旧客户端仍能读取 `markdown`。

### Phase 1：Worker Narrative Synthesis Module

- [x] 新增独立 `NarrativeReportPolisher`，只暴露 `polish(synthesis_input)`。
- [x] 使用 `research.synthesis` purpose 调用现有 OpenAI-compatible client。
- [x] 规范化模型 JSON，并校验段落的 Cell/Evidence 引用闭包。
- [x] 对数字、日期、版本号执行绑定文本检查。
- [x] 生成 reader Markdown 与结构化 narrative。
- [x] Provider 不可用、空响应、坏 JSON、未知引用时确定性回退。
- [x] 补齐 FakeLlmClient 单元测试及 SynthesisExecutor 接入测试。

验收：合法响应只调用模型一次；非法响应不泄漏进报告；回退结果稳定且可引用。

### Phase 2：Java Validation 与持久化

- [x] 将合成契约演进为 v2，同时兼容 v1。
- [x] canonical claims 继续 exact-match 校验。
- [x] narrative 的 cell keys 与 evidence keys 必须属于 frozen synthesis input。
- [x] 对 narrative typed facts 做服务端二次检查。
- [x] 在 role result payload 中持久化结构化 narrative，并沿用 completion receipt。

验收：伪造引用、孤立数字、篡改 canonical claim 均被拒绝；v1 fallback 仍可完成。

### Phase 3：Finalization

- [x] reader Markdown 成为默认 final report。
- [x] audit report 作为独立 appendix 保留。
- [x] evidence manifest、citation chain 与 ledger digest 保持完整。
- [x] synthesis 失败自动采用确定性 fallback，不使 Run 失败。

验收：完成态报告同时具备文章正文、来源索引和逐项审计入口。

### Phase 4：Frontend Reader View

- [x] 新增专用 `ResearchReportView`，正文最大宽度约 760px。
- [x] 补齐结构化对比表；表格行与正文段落一样绑定 frozen Cell / Evidence，并执行 typed-fact 校验。
- [x] 正文使用紧凑引用标记，完整 Evidence key 保留在审计区。
- [x] 内部 Cell、Snapshot、digest 默认收进“研究审计”折叠区。
- [x] 添加打印样式、浅色/深色主题和 375px 响应式。
- [x] 补齐 DOM 与折叠交互测试。

验收：页面首先像一篇研究文章，其次才呈现运行状态；引用可达且审计信息没有丢失。

### Phase 5：真实闭环与视觉回归

- [x] 运行 Worker、Java、Frontend 定向自动化测试。
- [x] 启动完整项目并执行真实 Deep Research Run。
- [x] 验证 LLM 润色成功路径与 provider 失败回退路径。
- [x] 桌面端和 375px 下逐项检查目录、引用、审计区、打印与明暗主题；结构化对比表仍归 Phase 4 后续项。
- [x] 保存改造后运行证据，并与 fallback 样本对比。

验收：真实链路完成、报告事实可追溯、视觉达到主流 Deep Research 阅读体验，且无明显回归。

## 完成定义

- 自动测试全部通过。
- 新真实 Run 为 `COMPLETED_VERIFIED`。
- reader report 中每个事实段落至少一个有效引用。
- 审计层仍能还原 canonical claim、Evidence 与 citation chain。
- LLM 失败时 Run 仍产生完整可读的 fallback 报告。
- 浏览器在浅色、深色、桌面和移动端完成实际回归。

## 真实闭环验收记录（2026-09-21）

- 成功 Run：`9620aeab-ecc2-4239-a1fd-73b0f69e0145`
- 终态：`COMPLETED`，前端显示 `Verifier Approved`
- 任务链：4 个 `DEEP_CELL`、4 个 `COUNTERFACTUAL`、1 个 `EVIDENCE_AUDIT`、1 个 `SYNTHESIS`，全部 `SUBMITTED@1`
- 合成契约：`research-synthesis-candidate.v2`
- 文章模式：`narrative_mode=LLM`，无 fallback reason
- 报告：标题、执行摘要、自动目录、文章章节、紧凑 `[证据]` 标记与可展开 Citation Audit 均已在真实浏览器验收
- 审计：4 个引用均可展开到 Evidence key、Exact quote、归档 Snapshot 与原始 URL
- 主题：浅色与深色均实测；当前 688px 浏览器视口 `scrollWidth=673`，无页面横向溢出；375px 由响应式 DOM/样式回归测试覆盖
- fallback 对照 Run：`032a3963-f55c-437a-bbc6-1076c877e00e`，同为 v2，模型语义输出无效时以 `FALLBACK / NARRATIVE_HEADER_MISSING` 安全完成
- 同步修复：每 Cell Evidence 上限在 Java 与 Worker 统一为 6，并在 Worker 侧确定性截断；Synthesis 的可空 fallback reason 规范化为空字符串，避免 Java `Map.copyOf` 拒绝 completion JSON
- 结构化对比表：`comparison_table.columns / rows` 已接入 Worker 提示词、规范化、Markdown 渲染和 Java 二次校验；无表格时保持原输出，伪造引用或新增数字时整份 narrative 安全回退。
