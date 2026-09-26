# Deep Research 最小简历闭环执行计划

> 目标：用最少工程量，把当前 NoteWeave Research Agent 收口为一条可真实运行、可中断恢复、可逐项追溯的 Deep Research 链路。  
> 范围：只实现简历描述能够被演示和追问验证的部分，不追求生产级高并发、安全加固或完整平台治理。  
> 当前判断：最小简历闭环已完成；后续仅保留明确列出的生产化增强项。

> 执行进度（2026-09-20）：MIN-1～MIN-4 已完成，Docker 运行时健康，最小验收门禁为 `8/8 PASSED`、`pending_runtime_count=0`、`resume_claim_ready=true`。最终 API 对比 Run `a1125180-c7df-4b45-94cd-663873856183` 达到 `COMPLETED_VERIFIED / ALL_REQUIRED_CELLS_VERIFIED`：4 个 Cell 全部 VERIFIED，8 张官方域名 exact-quote Evidence、4 个通过 canonical qualification 的 Candidate、4 次 ACCEPTED merge、4 条完整引用链与报告 artifact 均已留存。quote 篡改、来源冲突、Provider 故障、证据缺失和 Worker 中断恢复场景也均保留真实运行证据。

## 1. 最终可写的简历描述

> 设计并实现基于 Table-as-State 的可恢复 Deep Research Agent，显式维护研究 Cell、Evidence 与结论；通过局部 Replan、证据校验和受限反证控制研究漂移，并利用 Checkpoint、Execution Fencing 与引用审计支持任务恢复和结论逐项回溯。

完成后必须能现场解释并演示：

1. 用户问题如何变成研究表格和待验证 Cell。
2. 搜索、抓取、抽取的 Evidence 如何进入 Cell，而不是直接生成报告。
3. 某个 Cell 证据不足时，系统如何只修复该 Cell，不重跑整个研究。
4. Worker 中断后，任务如何重新领取并复用已完成结果。
5. 报告中的结论如何回溯到原网页、快照和精确引文。

## 2. 当前已有能力

- `[当前实现]` Java 建 Run、Kafka 派发、Python Worker 执行的 MA4 主链路已经存在。
- `[当前实现]` 真实搜索、网页读取、GLM 抽取及结构化 Extraction Diagnostics 已跑通过真实 Run。
- `[当前实现]` Table-as-State 的 Cell、Candidate、Evidence、Verifier 和诚实终态主体已经存在。
- `[当前实现]` Candidate 提升由 Java 基于 canonical ledger 独立校验，不直接相信 Worker 结论。
- `[当前实现]` Checkpoint Hydration、Task Execution Grant 和旧回调拒绝所需的基础结构已经存在。
- `[已测-模拟]` Research Worker 全量测试 631 passed，MA4 Task 恢复定向测试 7 passed。
- `[生产待验证]` MA4 主路径尚未证明真实局部 Replan、真实 Worker 中断恢复和完整引用回溯。

## 3. 明确不做

以下内容不阻塞本次简历能力完成：

- WIDE_DISCOVERY、多 Agent 协作和并发扩容。
- 多 Worker 压测、自动伸缩、跨机房容灾。
- 完整安全审计、复杂权限模型、密钥轮换和攻击面加固。
- 为极低概率竞态建设完整锁矩阵；D-33 MySQL 锁顺序仅作为后续稳定性项保留。
- 24 题全量 benchmark、统计显著性和对外宣称成功率。
- legacy loop 的恢复能力；唯一验收路径固定为 `Kafka → agent_kafka_consumer → DeepCellExecutor`。
- 新的前端研究工作台大改版；本期只要求现有页面能查看状态、报告与引用。

## 4. 最小执行路径

### MIN-1：把局部 Replan 接入 MA4 主路径（2～3 天）

状态：`REAL RUNTIME PASSED`（真实局部 Replan 已证明只重建失败 Cell；已 VERIFIED Cell 不重跑，并留存 plan revision 与 replan audit）。

目标：一个 Cell 失败时只产生该 Cell 的修复动作，不重新搜索已经完成的 Cell。

实施：

1. 在 `DeepCellExecutor` 完成抽取后，将失败原因归一为少量可行动原因：无结果、抓取失败、无有效引文、证据冲突。
2. 复用现有 `CellRecoveryPolicy` 生成局部修复决策，不再另建一套策略。
3. 只为受影响 Cell 创建下一次 task；已 VERIFIED Cell 保持不动。
4. 记录最小审计信息：`run_id`、目标 Cell、原因、旧/新 plan revision、策略动作和预算差异。
5. 限制每个 Cell 最多修复 2 次；耗尽后进入 limitation 或 insufficient evidence，避免死循环。

验收：

- 构造一个两 Cell Run：A 成功、B 首次无有效引文。
- 第二轮只出现 B 的 task，A 的搜索/抓取调用次数不增加。
- `research_agent_replan_audit` 至少产生一条真实记录。
- B 修复成功则报告完成；仍失败则诚实输出 limitation，不伪造结论。

### MIN-2：完成 Task 级中断恢复（1～2 天）

状态：`REAL RUNTIME PASSED`（真实 Worker 在网页归档后以 exit code 86 退出；不同身份的新 Worker 将同一 Task 从 lease/fencing 1/1 重领为 2/2，恢复时只执行 extract；旧 1/1 completion 被 `RESEARCH_AGENT_TASK_STALE_LEASE` 拒绝，canonical state 未改变）。

目标：证明 Worker 在一个 DEEP_CELL Task 中断后，新 Execution 能继续执行，并且旧 Execution 不能覆盖新结果。

实施：

1. 以当前 Task 重新领取机制为准，不建设 Run 级工作流引擎。
2. 在搜索或抓取完成后注入一次 Worker 退出。
3. 新 Execution 领取同一 Task 时读取已归档的网页/收据，跳过已经确认完成的外部调用。
4. 保留现有 Execution Grant/Fencing；只做最小接线和日志，不扩展安全协议。
5. 对搜索、抓取、读取、抽取各记录一次调用计数，便于证明恢复没有重复计费。

验收：

- 首次 Execution 在抓取后中断，第二次 Execution 最终完成 Task。
- 已归档 URL 不再次 fetch/read；如果抽取尚未完成，只补抽取。
- 首次 Execution 的延迟 completion 返回稳定拒绝码，canonical Cell 不被改写。
- reservation 的 consumed + released 等于 reserved。

### MIN-3：实现最小引用审计与三类报告（1～2 天）

状态：`REAL VERIFIED PATH PASSED`（verified、completed with limitations、insufficient evidence 三条代码路径均已有诚实报告；真实 verified Run 已验证 Claim → Cell → Candidate → Evidence → exact quote → snapshot → URL）。

目标：报告中的每一条正式结论都能追溯到真实证据；证据不足时不生成看似完整的报告。

实施：

1. 生成报告前查询 canonical ledger，只使用已通过 Evidence Qualification 的 Candidate。
2. 为每个 Claim 输出或持久化最小引用链：`Claim → Cell → Candidate → Evidence → exact quote → snapshot → URL`。
3. 报告只保留三种结果：
   - verified：有可提升结论和完整引用；
   - completed with limitations：部分结论成立，明确列出缺失项；
   - insufficient evidence：没有足够证据，只输出研究过程与失败原因。
4. Markdown 为空时不创建 `report_artifact`。
5. 前端只需支持点击引用查看 URL、快照标识和精确引文，不做复杂可视化。

验收：

- 抽查每个正式 Claim，引用链均能落到数据库中的 Evidence 和网页快照。
- 人为篡改或删除 quote 后，该 Claim 不进入最终报告。
- 三类终态各有一个固定测试样本。

### MIN-4：完成 8 题最小验收与演示材料（1 天）

状态：`REAL RUNTIME PASSED / 8 OF 8 REAL RUNS PASSED`。

2026-09-19 已交付：

- `experiments/deep-research/minimal-eval/manifest.json`：2 正常 + 2 缺失 + 1 冲突 + 1 Worker 恢复 + 1 引文篡改 + 1 Provider 故障。
- `scripts/deepresearch/check_minimal_eval.py`：拒绝题型漂移，也拒绝无 Run ID / 证据文件却标记 `PASSED`。
- `scripts/deepresearch/run_minimal_eval.py`：串行执行 5 个无需故障注入的真实题并保存公开 API 证据；对 3 个故障题 fail-closed，防止普通 Run 被误标为故障验收。
- `DEMO.md` 与 `CLAIM-EVIDENCE-MAP.md`：固化演示步骤和面试追问证据。
- `[已测-真实运行]` 8 题已全部通过：2 正常、2 缺失、来源冲突、Worker 恢复、引文篡改、Provider 非法 JSON。
- `[已测-真实运行]` `min-normal-api-comparison` 从两家官方域名生成 4 个独立 Cell 任务，4 个 required/optional Cells 均 VERIFIED，报告与引用清单已生成。
- `[已测-真实运行]` `min-conflict-remote-productivity` 已完成真实冲突保留、受限反证、局部 Replan 与诚实 `INSUFFICIENT_EVIDENCE` 终态验收。
- `[当前实现/已测]` quorum 零候选回执统一为 `QUORUM_PENDING`，Java 幂等回放回归已通过；Python Worker 客户端已接受 `QUORUM_PENDING / QUORUM_MERGED / QUORUM_REPAIR_REQUIRED`，避免把合法回执误报为协议错误。
- `[当前实现/已测]` 单域 quorum 槽会优先使用与发布方匹配的比较子句；用户问题中明确给出的白名单 URL 会在真实 Serper 调用后作为高优先级 seed 进入抓取，53 项 Worker 定向测试通过。真实 Run 已命中 Nature 主论文，NBER `w28983` 精确 URL 的新一轮运行仍待完成审计。
- 校验器已输出 `passed_count=8`、`pending_runtime_count=0`、`resume_claim_ready=true`。

目标：用小而覆盖关键行为的题集证明功能，不追求大规模效果数字。

题集：

- 2 题正常研究。
- 2 题部分证据缺失。
- 1 题来源冲突。
- 1 题 Worker 中断恢复。
- 1 题引文不支持 Claim。
- 1 题搜索或模型 Provider 故障。

最低门禁：

- 8 个 Run 都有可解释终态。
- 正式 Claim 引用可追溯率 100%。
- 无合格 Evidence 的 Candidate 提升数为 0。
- 旧 Execution 回调被接受数为 0。
- 中断恢复样本中，已确认完成的 fetch/read 重复次数为 0。
- 至少留存 1 条真实 Replan 审计记录和 1 组恢复前后调用计数。

产物：

- `experiments/deep-research/minimal-eval/`：8 题输入、Run ID、终态和关键指标。
- 一份 3～5 分钟演示步骤：正常 Run、局部 Replan、中断恢复、点击引用。
- 一张简历主张到代码/数据库/测试证据的映射表。

## 5. 执行顺序与依赖

```text
MIN-1 局部 Replan
        ↓
MIN-2 Task 恢复 ──→ MIN-3 引用审计与报告
                         ↓
                    MIN-4 八题验收
```

MIN-1 优先，因为它是当前简历描述中最明显的真实主路径缺口。MIN-2 与 MIN-3 在 MIN-1 接口稳定后可以并行，但默认按顺序实施，减少脏工作树冲突。

## 6. 完成定义

只有同时满足以下条件，才把简历描述标为完成：

- 真实链路为 `Java Run → Kafka → Python Worker → Search/Fetch/Extract → Java Canonical Ledger → Report`。
- MA4 主路径发生过一次真实局部 Replan，且只影响失败 Cell。
- 发生过一次真实 Task 重领，旧 Execution 被拒绝，已完成外部调用未重复。
- 三种报告终态都能生成正确内容，空报告不产生 artifact。
- 每个正式 Claim 都能回溯到 exact quote、snapshot 和 URL。
- 8 题验收全部达到最低门禁，并保存可复现证据。

## 7. 本轮之后再考虑的事项

D-33 锁矩阵、24 题完整评测、并发压测、WIDE_DISCOVERY、多 Agent、安全加固和更完整的产品 UI 均进入后续清单。它们可以提升生产质量，但不应继续阻塞最小简历闭环。
