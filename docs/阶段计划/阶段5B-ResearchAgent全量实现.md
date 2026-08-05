# 阶段5B-Research Agent 全量实现

## 1. 目标

这份文档用于把 `Deep Research 智能体` 从当前可运行 MVP 推进到完整可交付形态。

当前已完成的是：

1. Java 创建 `research_run / task / task_outbox`。
2. Research 的 `INCREMENTAL_V1` agent task 通过唯一正式 topic `noteweave.research.agent.command` 下发；旧 `noteweave.research.run` 链路已删除。
3. Python Research Worker 能消费 Kafka、拉取 Java 输入、回调 progress / complete / fail。
4. Worker 内部已有轻量 `Plan -> Search -> Read -> Extract -> Verify -> Branch -> Report` 闭环。
5. Search 命中已具备 `search_angles / matched_fields / coverage_score`。
6. `ResearchHarness` 与 `ResearchStepTrace` 已接入 runner，结果里会输出 `harness_trace / harness_summary`。
7. `SearchAdapter` 已完成第一阶段落地：无外部 key 时只走工作台资料，有外部 key 时叠加外部搜索并做预算控制、结果归一化和去重。
8. `ReadAdapter` 已完成第一阶段落地：workspace hit 打开资料窗口，external url hit 在未启用网页抓取时生成可解释 fallback。
9. `LlmClient / JSON Repair / LLM Extract / LLM Verify` 已完成第一阶段落地：无模型配置时保持规则 fallback，有模型配置时尝试 OpenAI-compatible JSON 契约。
10. `LoopRuntime / Stop Contract` 已完成第一阶段落地：默认最多 2 轮，冲突证据触发反证复核，预算耗尽强制 guardrails 收口。
11. `research_checkpoint_candidate / report_source_candidate` 已输出到 Worker result payload。
12. Java 已支持 `save-report-as-source`，能把完成后的研究报告写回工作台资料池并复用 source parse/index 链路。
13. Research Worker 启动脚本与 smoke test 已完成第一阶段落地。

还没有完成的是：

1. checkpoint / URL snapshot 正式保存到 MinIO。

## 1.1 本轮纠偏记录（2026-07-05）

本轮继续完善前，先统一产品入口口径：

1. `Deep Research` 是工作台内的独立功能，不是从 `Ask / Note / Wiki` 聊天链路里升级出来的模式。
2. `Deep Research` 的启动输入以用户显式提交的研究问题为准，而不是依赖聊天上下文、Note 上下文或 Wiki 上下文。
3. 工作台与 `Deep Research` 的绑定点主要体现在：
   - 用户可选择把研究报告或确认后的研究资料回写进工作台资料池。
   - 已保存的研究产物可以像普通资料一样进入后续检索、引用与产物生成链路。
4. 后续所有 `Research Agent` 设计与实现文档，都要避免把它描述成 `问答 / Note / Wiki` 的派生入口，而应描述为独立研究入口 + 工作台资料回流能力。

## 1.2 本轮推进重点（2026-07-05）

在上一轮已经把 worker 内部的 `Table-as-State / Dual Verifier / Counterfactual Branching` 从摘要式输出推进到更结构化的运行时状态之后，这一轮继续补以下缺口：

1. 检查 Java 主系统是否已经真正消费并持久化 `research_checkpoint_candidate / state_ledger / verifier_decisions / branch_decisions`，避免 Research Harness 只在 worker 内部成立。
2. 如果 Java 侧仍停留在“只保存最终报告和整包 result payload”，则优先补齐：
   - `research_trace` 中对关键 verifier / branch / checkpoint 事件的映射；
   - `research_run` 详情接口对关键闭环状态的透出；
   - 必要的契约测试，确保主系统和 worker 对 `Closed-Loop Research` 的状态对象保持一致。
3. 本轮实现目标不是只加更多字段，而是让 `Research Harness` 的关键控制对象真正穿过 `worker -> callback -> Java persistence -> query/detail API` 这条主链路。

## 1.3 本轮推进重点（二）（2026-07-05）

在上一轮已经把 `Closed-Loop Research` 的关键状态对象贯通到 Java 主系统之后，这一轮继续修正 `Research Run` 的创建契约：

1. `Deep Research` 作为独立入口，创建任务时的必选输入只有用户显式提交的研究问题。
2. 工作台资料不应再被默认整包继承为研究输入；如果用户没有显式指定资料范围，`source_scope` 应允许为空。
3. 当用户显式指定资料范围时，Java 主系统需要把该范围冻结到 `research_run.source_scope_json`，并准确传给 worker。
4. 契约测试需要覆盖两种情况：
   - 未指定资料范围时，worker 输入中的 `source_scope` 为空；
   - 指定资料范围时，只携带用户选择的资料，而不是工作台全部 READY 资料。

## 1.4 本轮推进重点（三）（2026-07-06）

在入口契约已经纠偏并落地之后，这一轮继续补 `Table-as-State` 的细粒度真源：

1. 不再只依赖 `research_trace + result_payload` 作为研究过程状态的唯一保存方式。
2. 优先把以下对象落到 MySQL 真源表：
   - `research_branch`
   - `research_row`
   - `research_cell`
   - `research_verifier_decision`
3. 第一阶段不要求把 worker 全部中间对象都拆光，但至少要保证：
   - worker 输出的 `state_ledger / branch_decisions / local_verifier / global_verifier`
     能被 Java 主系统映射进细粒度状态表；
   - `Research Run detail` 可以直接读取这些表形成结构化闭环状态，而不只是转述 trace。
4. 契约测试要覆盖：
   - 完成回调后，上述细粒度状态表中存在对应记录；
   - 冲突 / 分支 / verifier 决策可以通过详情接口或数据库直接验证。

## 1.5 本轮推进重点（四）（2026-07-06）

在 `research_branch / research_row / research_cell / research_verifier_decision` 已经落到 MySQL 真源后，下一步优先补 `checkpoint` 与 `evidence binding`：

1. `research_checkpoint_candidate` 不能只停留在 trace / payload，至少要形成正式 checkpoint 元数据与对象存储落盘。
2. `evidence_cards` 不能只作为报告附属数组存在，至少要能映射为：
   - `source_evidence`
   - `research_cell_evidence`
3. 第一阶段目标：
   - 先把 checkpoint snapshot 落到对象存储，并把元数据写入 `research_execution_checkpoint`；
   - 先把 worker 返回的 `evidence_cards` 与 `state_ledger.rows/cells` 映射为 evidence 真源关系；
   - detail / trace / DB 三处都能验证这些对象已经存在。
4. 如果 URL snapshot 暂时还没有真实抓取正文，则允许先把 `read_windows` 中已有的 `url / snapshot_status / snapshot_key` 以可扩展方式保存，后续再补真实网页快照持久化。

## 1.6 本轮纠偏记录（二）（2026-07-06）

围绕 `Deep Research` 的任务进入方式，继续统一产品口径：

1. `Deep Research` 是与工作台绑定的独立功能，不承接 `Ask / Note / Wiki` 上下文升级，也不从任意页面上下文隐式派生研究任务。
2. 研究任务的启动输入统一为用户显式填写的研究问题，以及可选的资料范围、研究档位、预算档位、外部检索开关与报告偏好。
3. 工作台在这里承担的是资料容器与研究产物回流位置，而不是研究任务的上下文来源；已选资料仅作为可选 `source scope`，不改变任务入口定义。
4. 后续设计文档、时序图、模块职责若再出现“收集上下文”“从 Note / Wiki 发起研究”等表述，都需要统一改写为“装配用户输入与可选 `source scope / control pack`”。

## 1.7 本轮推进重点（五）（2026-07-06）

在 `checkpoint / evidence / resume-from-checkpoint` 已经贯通之后，这一轮继续补 `Research Harness` 的结果透出层，使 `Closed-Loop Research` 的关键结论不再只能通过 markdown 报告间接消费：

1. worker 侧需要把最终研究报告拆成结构化 `report_structure`，至少覆盖：
   - `verified_findings`
   - `conflict_and_counterfactual_review`
   - `recovery_status`
   - `next_actions`
   - `resume_checkpoint`
   - `recovery_mode`
2. 这些结构化字段应与 `Table-as-State / Dual Verifier / 反证分支` 的最终状态保持同源，避免前端或上层系统通过解析 markdown 反推出闭环状态。
3. Java 主系统的 `Research Run detail` 需要直接透出结构化报告对象，使工作台后续可以按 section 渲染、按状态提示恢复动作、按 verifier 结论展示风险，而不是把 `Deep Research` 继续当作一段长文本。
4. 契约测试需要覆盖：
   - worker `result_payload` 中存在结构化 `report_structure`；
   - `Research Run detail` 接口中存在对应结构化报告字段；
   - 结构化 section 与 `global_verifier / stop_contract / checkpoint` 等闭环状态相互一致。

## 1.8 本轮推进重点（六）（2026-07-06）

在 worker 与 Java detail API 已经能稳定输出结构化 `report_structure` 之后，下一步优先把 `Deep Research` 接回工作台 UI，避免 `Closed-Loop Research` 继续停留在“接口存在但用户不可见”的状态：

1. 前端需要提供 `Deep Research` 独立入口，启动输入仍然只来自用户显式填写的研究问题，并允许用户显式勾选可选 `source scope`，而不是隐式继承聊天上下文。
2. 工作台中的 `Deep Research` 区域至少要能展示：
   - `Research Run` 当前状态与任务进度；
   - 结构化 `report_structure` 的关键 section；
   - `closed_loop_state` 的核心 verifier / branch / checkpoint 指标；
   - checkpoint 列表与单个 checkpoint 正文回放；
   - `resume-from-checkpoint` 与 `save-report-as-source` 的显式操作入口。
3. 这样前端看到的就不再只是“最终 markdown 报告”，而是 `Research Harness / Table-as-State / Dual Verifier / 反证分支` 的可见控制面板，便于审计、恢复和路径纠偏。
4. 验证目标：
   - 前端构建通过；
   - 可以从工作台创建 `Deep Research` 任务；
   - 可以在 UI 中看到结构化报告与 checkpoint 回放所需字段；
   - 恢复与写回入口均绑定到显式 `Research Run` 对象，而不是聊天态临时动作。

## 1.9 本轮推进重点（七）（2026-07-06）

在工作台已经能操作“当前一个 `Research Run`”之后，下一步优先补齐研究历史与显式 run 管理，避免 `Deep Research` 退化成一次性面板：

1. Java 主系统需要提供 `Research Run` 列表接口，至少返回：
   - `research_run_id / task_id / question / profile_key / status`
   - `resumed_from_research_run_id / resumed_from_checkpoint_no`
   - `final_report_title`
   - `source_scope_count / checkpoint_count`
   - `active_branch_id / global_verifier_decision / final_loop_decision`
   - `created_at / updated_at`
2. 工作台前端需要把这个列表渲染成研究历史面板，使用户可以在多个 run 之间切换查看，而不是只能围绕最后一次任务工作。
3. 这样 `Closed-Loop Research` 的 run、checkpoint、恢复、写回才真正成为显式对象管理，而不是“当前页状态里碰巧保留着哪个 run id”。
4. 验证目标：
   - backend 有正式 `list research runs` 接口；
   - 前端可在历史面板中切换不同 run 并查看详情；
   - 恢复 run 在历史中可见，且能显示 `resumed_from_*` 来源；
   - backend / frontend 相关测试或构建验证通过。

## 1.10 本轮推进重点（八）（2026-07-06）

在独立 `Research Workbench` 已经成型之后，下一步优先把 checkpoint 回放从“原始 JSON 调试视图”升级为“结构化闭环回放视图”，否则用户仍然很难直接验证路径、状态与纠偏动作：

1. checkpoint 回放区域应优先展示结构化对象，而不是先展示整包 JSON：
   - checkpoint 内的 `report_structure`
   - checkpoint 内的 `state_ledger` 摘要
   - checkpoint 内的 `loop_decision / local_verifier / global_verifier`
   - checkpoint 内的 `evidence_cards / branch_decisions`
2. 这样用户才能在回放时直接看到：
   - 当时有哪些 `Verified Findings`
   - 当时有哪些冲突与反证分支
   - 当时 verifier 为什么要求继续恢复、为什么允许合成
   - 当时 `Table-as-State` 已经推进到了什么粒度
3. 原始 checkpoint JSON 仍可保留，但应退居到调试补充位，而不再作为默认阅读界面。
4. 验证目标：
   - 前端构建通过；
   - checkpoint 回放可直接看到结构化 `report_structure / ledger / verifier / evidence` 摘要；
   - 原始 JSON 不被移除，但不再是默认唯一视图。

## 1.11 本轮推进重点（九）（2026-07-06）

在 checkpoint 已经能结构化回放之后，下一步优先补“checkpoint 到当前 run 的增量对比”，否则用户仍然只能看到两个静态切片，难以判断恢复与纠偏到底有没有带来推进：

1. 回放区应增加 `Checkpoint -> Current Run` 的结构化 diff，至少包括：
   - `Table-as-State` 的行数、verified 数、conflicted 数变化
   - `evidence_cards` 数量变化
   - `local/global verifier` 决策变化
   - `loop_decision` 变化
   - `Verified Findings` 中新增或保留的结论
2. 这样用户就能直接回答：
   - 从该 checkpoint 恢复后，系统到底多读到了什么、验证到了什么
   - verifier guardrail 是否收紧或放松
   - 反证分支之后冲突是否减少，还是只是换了表述
3. 这个 diff 视图比“再看一份 JSON”更贴近 `Research Harness / Closed-Loop Research / Dual Verifier` 的真实价值，因为它直接展示了闭环执行是否产生净推进。
4. 验证目标：
   - 前端构建通过；
   - checkpoint 回放区可直接看到 `snapshot vs current` 的结构化差异；
   - 新增结论、verifier 变化、ledger 变化不依赖人工比对 JSON。

## 1.12 本轮推进重点（十）（2026-07-06）

在 checkpoint 已经支持 `snapshot -> current` 与 `checkpoint -> checkpoint` 对比之后，下一步优先补“恢复 run 相对来源 checkpoint”的专用恢复视图，否则 `resume-from-checkpoint` 仍然主要停留在对象关系层，用户很难直观看到恢复是否带来了真实推进：

1. 对 `resumed_from_research_run_id / resumed_from_checkpoint_no` 不为空的 run，前端应自动加载来源 checkpoint。
2. 工作台需要展示 `Resume Recovery Diff`，至少包括：
   - 来源 checkpoint 与当前 run 的 `Table-as-State` 行数 / verified / conflicted 对比
   - 来源 checkpoint 与当前 run 的 `local/global verifier`、`loop decision` 对比
   - 来源 checkpoint 与当前 run 的 evidence 数量变化
   - 恢复后新增的 `Verified Findings`
3. 这样用户就能直接判断：
   - 这次恢复是不是只是“重新跑了一次”
   - 恢复后 verifier guardrail 是否真的放松
   - 反证分支或定向恢复后是否真的新增了结论或减少了冲突
4. 验证目标：
   - 前端构建通过；
   - resumed run 可自动展示来源 checkpoint；
   - `Resume Recovery Diff` 可直接展示恢复净推进，而不依赖人工跳转比对。

## 1.13 本轮推进重点（十一）（2026-07-06）

在恢复 diff 已经具备之后，下一步优先把 `反证分支 / COUNTERFACTUAL_RECHECK` 做成专门视图，否则 `Branch Decision` 虽然已经存在，但用户仍然很难直接判断纠偏动作是否有效：

1. 工作台需要新增 `Counterfactual Branch Diff`，把以下对象放到同一个阅读面上：
   - 当前 run 的 `counterfactual branch decisions`
   - 当前 run 的冲突字段数量
   - 当前 run 的目标 evidence / branch reason
   - 与基线 checkpoint 或恢复来源 checkpoint 的对比
2. 这样用户才能直接回答：
   - 这次反证分支到底是新开的，还是之前分支的延续
   - 冲突字段数量有没有减少
   - verifier 要求的反证目标 evidence 是否被真正覆盖
   - 路径纠偏后是否带来了新的 verifier-approved finding
3. 这个视图比单纯显示 `branch_decisions.length` 更贴近 `Research Harness / Closed-Loop Research / Dual Verifier / 反证分支` 的核心价值，因为它展示的是纠偏动作本身，而不是只展示结果字段。
4. 验证目标：
   - 前端构建通过；
   - 对存在 `COUNTERFACTUAL_RECHECK` 的 run，可直接看到专门 branch diff；
   - 当前 branch reason / target evidence / conflict delta 不依赖人工翻 JSON。

## 1.14 本轮纠偏记录（三）（2026-07-06）

围绕设计文档中 `4.1 任务进入与研究启动` 的产品表述，再补一层口径收束：

1. `Deep Research` 不是“从问答上下文、Note 上下文、Wiki 上下文或已选资料上下文进入”的研究模式，而是工作台中的独立功能入口。
2. 研究任务的成立条件始终是用户显式输入研究问题；如果需要附带主题说明、目标、限制条件、期望输出格式，也都属于用户在创建任务时显式填写的输入，而不是页面上下文继承。
3. 工作台与 `Deep Research` 的关系应统一描述为：
   - 工作台提供任务容器；
   - 用户可选附带 `source scope`；
   - 研究完成后的报告或确认后的研究资料可回流进工作台资料池。
4. 后续若文档里出现“基于问答上下文发起研究”“基于 Note/Wiki 上下文升级为 Deep Research”“基于已选资料直接进入研究”等表述，都应改写为“用户创建独立研究任务，并可显式附带可选资料范围”。

## 2. 参考项目映射

### 2.1 MiroFlow / MiroThinker

吸收点：

1. 单主 Agent 通过工具管理器调用搜索、读取、推理工具。
2. 每次任务都有结构化 tracer，记录步骤、状态、错误和最终结果。
3. LLM client 与工具执行解耦，方便替换模型与工具实现。
4. 长任务需要可观测执行轨迹，而不是只存最终答案。

NoteWeave 落地口径：

1. 不照搬多子 Agent 复杂形态。
2. 保留单主 Research Worker。
3. 引入 `ResearchHarness` 作为 LLM/规则双轨执行入口。
4. 引入 `ResearchStepTrace`，先写入 worker result payload，后续再落 DB checkpoint。

### 2.2 Marco-Agent-DeepResearch

吸收点：

1. 搜索、访问页面、最终回答由工具调用循环驱动。
2. 有强制收口策略，达到回合或预算上限后必须生成答案。
3. Accuracy profile 会打开 verify，Efficiency profile 会减少验证成本。

NoteWeave 落地口径：

1. Profile 映射到 `StopContract` 和 verifier 策略。
2. 不让模型自由无限循环。
3. 每次 Search / Read / Extract / Verify 都必须生成结构化对象。

### 2.3 Table-as-Search / TaS

吸收点：

1. 研究过程不是普通聊天历史，而是状态表。
2. Search / Read / Verify 都围绕表格状态补字段。
3. 查询不只是一句 prompt，而是一组面向字段、来源和反证的查询。

NoteWeave 落地口径：

1. 当前 `state_ledger` 是第一阶段 Table-as-State。
2. 后续升级为 `research_checkpoint` 和细粒度 `research_trace`。
3. Query bundle 保留 direct / source_scoped / coverage_gap / counterfactual。

## 3. 总体施工树

```text
5B.1 Harness 契约与 Trace
  -> 定义 ResearchHarness / ResearchHarnessMode
  -> 定义 ResearchStepTrace
  -> runner 输出 harness_trace
  -> TDD 覆盖规则模式和 fallback

5B.2 真实搜索 Adapter
  -> 定义 SearchAdapter
  -> workspace adapter 作为默认实现
  -> web search adapter 读取环境变量配置
  -> 无 key 时自动降级 workspace adapter
  -> TDD 覆盖 query bundle、搜索预算、空结果恢复
  -> 状态：已完成第一阶段实现

5B.3 读取与网页快照 Adapter
  -> 定义 ReadAdapter
  -> workspace source_window 默认实现
  -> url reader / fetched page snapshot 预留实现
  -> TDD 覆盖窗口预算、来源快照、失败降级
  -> 状态：已完成第一阶段实现

5B.4 LLM 驱动抽取与验证
  -> 定义 LlmClient 协议
  -> 默认 Fake / RuleBased client 保证本地测试稳定
  -> OpenAI-compatible client 读取 env
  -> Evidence Extractor 支持 JSON schema 输出
  -> Local / Global Verifier 支持 LLM judge + rule fallback
  -> TDD 覆盖 JSON 解析、坏输出修复、证据不足警告
  -> 状态：已完成第一阶段实现

5B.5 Loop Runtime 与 Stop Contract
  -> 引入 bounded loop，不再固定单轮
  -> 每轮决定 SEARCH_MORE / READ_MORE / VERIFY / WRITE
  -> StopContract 控制最大轮次、最大搜索数、最小证据数
  -> TDD 覆盖预算耗尽、冲突分支、强制收口
  -> 状态：已完成第一阶段实现

5B.6 过程快照与报告回写
  -> Java 增加 report save-as-source 接口实现
  -> Worker result 输出 report_source_candidate
  -> Research 完成后可由 Java 回写资料池
  -> TDD 覆盖 generated_by=research_agent 和 citation 保留
  -> 状态：已完成第一阶段实现

5B.7 启动部署与运行脚本
  -> conda worker 环境
  -> Research API server 启动
  -> Research Kafka consumer 启动
  -> docker compose / PowerShell 本地脚本
  -> TDD / smoke 覆盖 health、consumer import、配置读取
  -> 状态：已完成第一阶段实现
```

## 4. 5B.1 Harness 契约与 Trace

### 4.1 新增文件

1. `workers/research-worker/app/harness.py`
2. `workers/research-worker/tests/test_harness.py`

### 4.2 模型变更

在 `models.py` 增加：

1. `ResearchStepTrace`
2. `ResearchHarnessDecision`

字段建议：

```text
trace_id
phase
status
message
inputs
outputs
warnings
```

### 4.3 函数设计

```text
build_harness(task_input) -> ResearchHarness
ResearchHarness.plan(task_input) -> tuple[ResearchPlan, list[ResearchStepTrace]]
ResearchHarness.summarize_trace(...) -> dict
```

### 4.4 TDD

先写测试：

1. `test_harness_should_emit_planning_trace`
2. `test_runner_should_include_harness_trace_in_result_payload`
3. `test_harness_should_fallback_to_rule_mode_without_llm_config`

完成定义：

1. 不配置 LLM 时所有测试稳定通过。
2. `result_payload.harness_trace` 至少包含 `PLANNING / SEARCHING / READING / EXTRACTING / VERIFYING / WRITING`。
3. Trace 不包含大段全文，只存摘要和关键对象 id。

## 5. 5B.2 真实搜索 Adapter

状态：已完成第一阶段实现。

### 5.1 新增文件

1. `workers/research-worker/app/search_adapters.py`
2. `workers/research-worker/tests/test_search_adapters.py`

### 5.2 函数设计

```text
SearchAdapter.search(task_input, plan) -> list[ResearchSearchHit]
WorkspaceSearchAdapter.search(...)
ExternalSearchAdapter.search(...)
CompositeSearchAdapter.search(...)
```

### 5.3 已落地行为

1. `build_default_search_adapter()` 默认返回 `WorkspaceSearchAdapter`。
2. 配置 `NOTEWEAVE_RESEARCH_SEARCH_API_KEY`、`SERPER_API_KEY` 或 `SEARCH_API_KEY` 后，自动启用 `CompositeSearchAdapter`。
3. 外部搜索 transport 采用 Serper-compatible JSON HTTP 契约，且不引入额外 Python 依赖。
4. `CompositeSearchAdapter` 按 `global_search_limit` 控制总搜索预算。
5. 合并结果时按 `source_id / url / title` 去重，工作台资料优先级高于外部同名结果。
6. 外部搜索命中统一归一化为 `ResearchSearchHit`，携带 `url / provider / adapter / search_angle / retrieval_reason / confidence_score`。
7. runner 已从直接调用 `run_workspace_search` 改为调用 `run_research_search`。

### 5.4 TDD

1. 无外部 key 时只使用 workspace adapter。
2. 有外部 key 时外部搜索结果与 workspace 命中合并去重。
3. 所有 hit 必须有 `search_angle / retrieval_reason / confidence_score`。
4. 空结果必须触发 branch recovery。

## 6. 5B.3 读取 Adapter

状态：已完成第一阶段实现。

### 6.1 新增文件

1. `workers/research-worker/app/read_adapters.py`
2. `workers/research-worker/tests/test_read_adapters.py`

### 6.2 已落地行为

1. `WorkspaceReadAdapter` 负责读取工作台资料命中，优先使用 `sample_text`，其次使用搜索 snippet 和 summary。
2. `UrlReadAdapter` 负责读取外部 URL 命中。
3. 默认不主动抓取网页，未配置 URL reader 时会生成包含搜索 snippet 与 URL 的 `FALLBACK` 读取窗口。
4. 配置 `NOTEWEAVE_RESEARCH_ENABLE_URL_READER=true` 后，会启用轻量 `HttpUrlSnapshotTransport` 尝试抓取网页文本。
5. `CompositeReadAdapter` 按搜索命中顺序读取，并统一遵守 `tool_response_retention_budget`。
6. `ResearchReadWindow` 已携带 `url / provider / adapter / snapshot_status / snapshot_key`，为后续 MinIO 快照落盘预留字段。
7. runner 已从直接调用 `open_read_windows` 改为调用 `run_research_read`。

### 6.3 TDD

1. workspace hit 能打开窗口。
2. url hit 在没有网络 reader 时生成可解释 fallback。
3. retention budget 生效。
4. read window 必须携带 `source_id / query / read_focus / token_estimate`。

## 7. 5B.4 LLM 抽取与验证

状态：已完成第一阶段实现。

### 7.1 新增文件

1. `workers/research-worker/app/llm_client.py`
2. `workers/research-worker/app/json_repair.py`
3. `workers/research-worker/tests/test_llm_extract_verify.py`

### 7.2 已落地行为

1. `LlmClient` 定义 `complete_json(purpose, payload)` 契约。
2. `FakeLlmClient` 用于 TDD，不依赖真实 API。
3. `OpenAICompatibleLlmClient` 使用轻量标准库 HTTP 请求，不额外引入依赖。
4. `build_default_llm_client()` 读取 `NOTEWEAVE_LLM_API_KEY / NOTEWEAVE_LLM_MODEL / NOTEWEAVE_LLM_BASE_URL`。
5. 不配置 key 或 model 时 runner 保持规则模式。
6. `json_repair` 支持 fenced JSON、截取 JSON 对象和 trailing comma 修复。
7. `extract_evidence_cards` 支持 LLM JSON schema 输出，坏输出自动回落到规则抽取。
8. `run_local_verifier` 支持可选 LLM judge 警告与恢复动作合并。
9. `write_research_report` 只渲染真实 `evidence_cards` 中存在的证据 ID，避免 ghost evidence 进入报告。

### 7.3 TDD

1. fake LLM 返回合法 JSON 时生成 evidence cards。
2. fake LLM 返回坏 JSON 时走 repair / fallback。
3. verifier 必须拒绝无证据结论。
4. report synthesizer 必须只引用 evidence cards 中存在的证据。

## 8. 5B.5 Loop Runtime

状态：已完成第一阶段实现。

### 8.1 新增文件

1. `workers/research-worker/app/loop_runtime.py`
2. `workers/research-worker/tests/test_loop_runtime.py`

### 8.2 已落地行为

1. 新增 `ResearchLoopDecision` 与 `ResearchLoopRoundSummary`。
2. `StopContract` 增加 `max_loop_rounds=2` 与 `min_evidence_cards=1`。
3. `run_research_loop()` 统一执行 `Search -> Read -> Extract -> Verify -> Branch -> Global Verify`。
4. 第一轮无搜索命中时输出 `EXPAND_SOURCE_SCOPE`，不继续空转。
5. 有搜索命中但无 evidence card 时输出 `READ_MORE` 或 `EXTRACT_AGAIN`。
6. 冲突证据在预算内触发 `COUNTERFACTUAL_RECHECK`。
7. 达到最大轮数仍未满足条件时输出 `WRITE_WITH_GUARDRAILS`。
8. runner 已从固定单轮改为调用 `run_research_loop()`。
9. result payload 新增 `loop_rounds` 与 `loop_decision`。

### 8.3 TDD

1. 默认最大 2 轮。
2. 第一轮无证据时触发 `SEARCH_MORE` 或 `EXPAND_SOURCE_SCOPE`。
3. 冲突证据触发 `COUNTERFACTUAL_RECHECK`。
4. 达到预算必须 `WRITE_WITH_GUARDRAILS`，不能无限循环。

## 9. 5B.6 报告回写

状态：已完成第一阶段实现。

### 9.1 Java 侧新增或完善

1. `POST /api/v2/research-runs/{researchRunId}/save-report-as-source`
2. `source.generated_by = research_agent`
3. report markdown 存 MinIO / local storage
4. citation 关系保留

### 9.2 已落地行为

1. Worker result payload 输出 `research_checkpoint_candidate`。
2. Worker result payload 输出 `report_source_candidate`。
3. 新增 migration `V013__add_research_report_source_mapping.sql`。
4. `source` 增加 `generated_by / generated_ref_id`。
5. `research_run` 增加 `report_source_id`。
6. Java 新增 `POST /api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/save-report-as-source`。
7. 已完成的 Research Run 可以生成 `GENERATED_RESEARCH_REPORT` 类型资料。
8. 保存后的报告会创建 `file_object / source / source_snapshot / source_chunk / source_window`。
9. 保存后会调用 `SourceParseService.parseAndIndex`，因此该报告与用户上传资料处于同一检索层级。
10. 未完成或无报告正文的 Research Run 会返回 `RESEARCH_REPORT_NOT_READY`。

当前边界：

1. checkpoint candidate 已输出，但还没有正式落 MinIO。
2. URL snapshot 正式持久化还没有落地。
3. citation 当前保留在 research trace/result payload，尚未额外拆成专门关系表。

### 9.3 TDD

1. 完成的 research run 可以保存为 source。
2. 未完成或失败的 run 不能保存。
3. 保存后的 source 能被 QA / Note / Wiki 检索链路使用。

## 10. 5B.7 启动部署

状态：已完成第一阶段实现。

### 10.1 当前脚本

1. `workers/scripts/start-research-consumer.ps1`

### 10.2 已落地行为

1. `setup-workers-conda.ps1` 负责创建/更新 `noteweave-workers` conda 环境。
2. `start-research-consumer.ps1` 进入 `workers/research-worker` 并通过 conda 运行唯一正式入口 `python -m app.agent_kafka_consumer`。
3. Kafka consumer 支持 `NOTEWEAVE_KAFKA_BOOTSTRAP_SERVERS` 覆盖。
4. 仅 health 的 Research HTTP API 已退出部署并删除，smoke test 覆盖 consumer import、Kafka 配置读取与该删除边界。

### 10.3 TDD / Smoke

1. `python -m app.agent_kafka_consumer` import 成功。
2. Research Worker 不再暴露无业务用途的 HTTP health/API 进程。
3. `NOTEWEAVE_KAFKA_BOOTSTRAP_SERVERS` 能覆盖默认值。

## 11. 当前下一步

阶段 5B 第一阶段已经完成。

后续增强建议单独进入新阶段：

1. checkpoint / URL snapshot 正式保存到 MinIO。
2. Research Run 过程态细粒度落库。
3. 外部 URL reader 接入更稳的网页抓取服务。
4. Artifact Worker 的 Skill / MCP 编排增强。
5. `Table-as-State / Dual Verifier / Counterfactual Branching` 的状态账本进一步细化到 cell / branch / verifier decision 级别。

## 12. 本轮执行追加（2026-07-06）

本轮继续沿着 `Research Harness -> Closed-Loop Research -> Table-as-State -> Dual Verifier -> 反证分支` 这条主链补完“可验证真源”能力，新增约束如下：

1. `research_checkpoint_candidate` 需要升级为正式 checkpoint：
   - checkpoint 正文写入对象存储；
   - checkpoint 元数据进入 `research_execution_checkpoint`；
   - `Research Run detail` 直接返回 checkpoint 列表，支持闭环回放与审计。
2. `evidence_cards` 需要升级为状态真源，而不只是报告附件：
   - 证据本体落到 `source_evidence`；
   - cell 到 evidence 的绑定关系落到 `research_cell_evidence`；
   - detail / trace / DB 三处能交叉验证同一条证据。
3. `Table-as-State` 需要补齐验证锚点：
   - 每个 cell 能回查 `evidence_refs`；
   - 每条 evidence 保留最小验证上下文，如 `source_id / source_url / quote_text / read_window`；
   - 反证分支中的冲突 evidence 可联动 branch / verifier decision 回查。
4. URL snapshot 本轮先落“元数据级可扩展持久化”：
   - 先保存 worker 已返回的 `read_windows.url / snapshot_status / snapshot_key`；
   - 暂不强制接入完整网页抓取；
   - 后续接入真实快照抓取时不推翻当前表结构和 detail 响应。
5. 本轮完成判定标准：
   - worker completion 后，checkpoint、source evidence、cell evidence 均有正式持久化记录；
   - `closed_loop_state` 可直接暴露这些对象；
   - 契约测试能验证对象存储、数据库与详情接口三者一致。

## 13. 本轮执行追加（二）（2026-07-06）

在 checkpoint 已经能正式落盘之后，这一轮继续补齐“可回放闭环”而不是只停留在“可列出元数据”：

1. `Research Run detail` 中的 `checkpoints` 目前只够做索引和审计入口，还不够支持真正的回放。
2. 需要新增正式 API，按 `workspace_id + research_run_id + checkpoint_no` 读取 checkpoint：
   - 先校验 run 归属；
   - 再读取 `research_execution_checkpoint` 元数据；
   - 最后从对象存储取回 checkpoint payload。
3. 返回对象至少要覆盖：
   - checkpoint 元数据，如 `checkpoint_no / snapshot_type / object_key / payload_sha256 / active_branch_id / final_loop_decision`；
   - checkpoint 正文 payload；
   - 这样前端或后续 worker 才能基于同一个 Research Harness 快照做回放、审计或恢复。
4. 契约测试需要新增覆盖：
   - 完成回调后可以通过 API 读回 checkpoint；
   - API 返回的 payload 与对象存储内容一致；
   - 返回值中能看到 `state_ledger / read_windows / evidence_cards / verifier` 等闭环关键对象。

## 14. 本轮执行追加（三）（2026-07-06）

在 checkpoint 已具备“正式落盘 + 正式读回”能力之后，下一步优先补齐“从 checkpoint 派生恢复任务”的研究恢复契约：

1. `Deep Research` 作为独立功能入口，不应把恢复能力做成聊天态临时续跑，而应做成显式的 `resume-from-checkpoint` 任务创建动作。
2. 恢复动作的最小闭环要求：
   - 用户显式选择某个 `research_run + checkpoint_no`；
   - Java 主系统创建一个新的 `Research Run`；
   - 新 run 保留原问题、原 profile、原 source scope 与原 control pack；
   - 新 run 记录来源 `resumed_from_research_run_id / resumed_from_checkpoint_no`；
   - worker input 中带上 `resume_checkpoint`，其中包含 checkpoint 元数据与 payload。
3. 这样 `Research Harness` 才能在后续真正支持：
   - 失败后局部恢复；
   - 反证分支后的定点续跑；
   - 前端或后续 worker 基于同一个 checkpoint 做恢复审计。
4. 契约测试本轮至少覆盖：
   - 已完成 run 可以从 checkpoint 创建新的恢复 run；
   - 新 run 的 worker input 中包含 `resume_checkpoint`；
   - `resume_checkpoint.payload` 中能看到 `state_ledger / evidence_cards / loop_decision` 等闭环关键对象；
   - 新 run 在数据库中保留恢复来源字段。

## 15. 本轮执行追加（四）（2026-07-06）

在恢复任务创建契约已经打通之后，下一步必须补齐“worker 真正消费 checkpoint 续跑”的执行语义：

1. 现在的 `resume_checkpoint` 还只是输入载体，如果 worker 不消费它，恢复能力仍然只是“重新起一个新任务”。
2. 本轮续跑目标：
   - worker 从 `resume_checkpoint.payload` 读取已有 `loop_rounds / loop_decision / state_ledger / read_windows / evidence_cards`；
   - 若上一个 checkpoint 已经终态收口，则允许直接基于 checkpoint 做审计式回放；
   - 若上一个 checkpoint 仍处于 `READ_MORE / EXTRACT_AGAIN / COUNTERFACTUAL_RECHECK` 之类的中间决策，则从下一轮继续执行。
3. 续跑时不能丢失已有研究资产：
   - 恢复前的 `read_windows / evidence_cards / branch_decisions / verifier decisions` 需要继续保留；
   - 新一轮资产与恢复前资产需要合并成新的 `Table-as-State`；
   - 最终 checkpoint 需要比旧 checkpoint 更接近真实“局部恢复后继续前进”的状态，而不是覆盖旧状态。
4. 本轮测试至少要覆盖：
   - 带 `resume_checkpoint` 的 worker 输入会触发续跑逻辑；
   - checkpoint 中已有 evidence 会保留到新结果；
   - checkpoint 中已有 `loop_rounds` 会与新一轮 round 组成连续闭环；
   - 新结果中的 `research_checkpoint_candidate` 体现的是恢复后状态，而不是只回显旧 payload。

## 16. 本轮执行追加（五）（2026-07-06）

在 worker 已经能消费 `resume_checkpoint` 做续跑之后，下一步优先补“按 `loop_decision` 定向恢复”，避免所有恢复都退化成统一重跑：

1. `resume_checkpoint` 中的 `loop_decision` 不只是展示字段，而应真正影响下一轮恢复策略。
2. 第一批需要显式区分的恢复策略：
   - `COUNTERFACTUAL_RECHECK`
     继续围绕冲突 evidence 和冲突 source 做定向反证，而不是重新铺开全量问题。
   - `READ_MORE`
     优先扩大阅读窗口和保留已有搜索命中，而不是先丢掉旧窗口再做全量搜索。
   - `EXTRACT_AGAIN`
     优先复用已有 read windows 重新抽证，而不是先重复 search/read。
3. 这样 `Research Harness` 才算真的把“研究、读取、验证与修正”组织成受控闭环：
   - Search / Read / Extract 的恢复粒度与 verifier 决策对齐；
   - `反证分支` 可以定向推进，而不是泛化重试；
   - `Table-as-State` 中已沉淀的证据和窗口能够真正影响下一轮动作选择。
4. 本轮测试至少要覆盖：
   - `resume_checkpoint.loop_decision=EXTRACT_AGAIN` 时优先复用旧窗口；
   - `resume_checkpoint.loop_decision=READ_MORE` 时保留旧命中并扩大读取；
   - `resume_checkpoint.loop_decision=COUNTERFACTUAL_RECHECK` 时 query bundle 带定向反证语义；
   - 新 checkpoint 中保留恢复前资产并叠加恢复后资产。

## 17. 本轮执行追加（六）（2026-07-06）

在 runtime 已经能够按 `loop_decision` 分流恢复之后，下一步必须把恢复意图继续下沉到 adapter 层，否则 Search / Read 仍然是“感知不到恢复语义”的通用执行器：

1. `SearchAdapter` 需要理解恢复模式：
   - `COUNTERFACTUAL_RECHECK` 时优先只跑带反证语义的 query；
   - 如存在 `recovery_target_sources`，则优先围绕这些 source 的 query 做定向检索；
   - 避免在恢复分支里又重新发散成全量搜索。
2. `ReadAdapter` 需要理解恢复模式：
   - `READ_MORE` 时优先围绕已有命中和目标 source 扩窗；
   - `COUNTERFACTUAL_RECHECK` 时 read focus 应直接体现“反证/冲突复核”；
   - 恢复阶段的 retention reason 应体现它是恢复窗口而不是普通首轮阅读。
3. 这样恢复闭环才能真正满足：
   - runtime 决策；
   - adapter 执行；
   - Table-as-State 合并；
   - verifier 再判定；
   四层一致，而不是只有 runtime 知道自己在恢复。
4. 本轮测试至少覆盖：
   - counterfactual recovery 只调用定向 query；
   - recovery target source 在读取阶段被优先保留；
   - read focus / retention reason 带恢复语义。

## 18. 本轮执行追加（七）（2026-07-06）

在 adapter 层已经理解恢复语义之后，下一步必须把恢复意图继续下沉到 `Extractor / Verifier`，否则闭环仍然会在“读到了恢复窗口”之后退回通用抽证与通用判定：

1. `Evidence Extractor` 需要理解恢复模式：
   - `EXTRACT_AGAIN` 时优先采用更严格的抽证策略，避免继续输出与上轮同质的弱证据；
   - `COUNTERFACTUAL_RECHECK` 时优先围绕冲突 source / target source 输出带反证语义的 evidence card；
   - 恢复阶段的抽证 payload 需要显式带上 `recovery_mode / recovery_target_sources`。
2. `Verifier` 需要理解恢复是否真的产生了增量：
   - `READ_MORE` 时至少要看到读窗数量相对 checkpoint 增长；
   - `EXTRACT_AGAIN` 时至少要看到 evidence card 数量或质量相对 checkpoint 有恢复；
   - `COUNTERFACTUAL_RECHECK` 时至少要看到反证窗口、反证证据或 conflict 复核痕迹。
3. 这样恢复闭环才真正形成：
   - Planner / Runtime 决定恢复策略；
   - Adapter 执行定向搜索与阅读；
   - Extractor 输出恢复态证据；
   - Verifier 判断恢复是否有效；
   四层一起构成可验证的 `Closed-Loop Research`。
4. 本轮测试至少覆盖：
   - `EXTRACT_AGAIN` 会触发更严格的 rule extraction 行为；
   - `COUNTERFACTUAL_RECHECK` 会输出带反证语义的 evidence card；
   - `READ_MORE / EXTRACT_AGAIN / COUNTERFACTUAL_RECHECK` 会在 verifier 中产生对应恢复判定。

## 19. 本轮执行追加（八）（2026-07-06）

在 `Extractor / Verifier` 已经能输出恢复态证据和恢复态判定之后，下一步必须把这些闭环结论继续落到 `Reporter`，否则最终产物仍然会把“已验证结论、冲突复核、待恢复项”混写在一起：

1. `Reporter` 需要理解 verifier-gated synthesis：
   - `VERIFIED` rows 优先进入 `Verified Findings`；
   - `CONFLICTED` rows 进入 `Conflict And Counterfactual Review`；
   - 非 `VERIFIED` 且非 `CONFLICTED` 的 guardrailed rows 进入 `Recovery Status / Next Actions`。
2. 恢复态报告需要显式暴露：
   - `resume checkpoint` 来源；
   - `recovery_mode`；
   - `global verifier decision` 是否仍为 `WRITE_WITH_GUARDRAILS`；
   - 尚未恢复完成的 unresolved questions 与 recovery actions。
3. 这样最终报告才真正成为 `Closed-Loop Research` 的产物，而不是普通摘要：
   - 只把 verifier 认可的内容当作稳定结论；
   - 把冲突证据和反证分支单独呈现；
   - 把待恢复项明确暴露给后续恢复任务或人工复核。
4. 本轮测试至少覆盖：
   - 报告中存在 `Verified Findings / Conflict And Counterfactual Review / Recovery Status`；
   - `ghost evidence` 仍然不能进入报告；
   - `WRITE_WITH_GUARDRAILS` 时报告会显式强调继续恢复而不是假装收口。

## 20. 本轮执行追加（九）（2026-07-06）

在工作台已经能展示 `Counterfactual Branch Diff` 之后，下一步优先把 `反证分支` 从“前端基于原始 branch decisions 临时推断”升级为“后端正式结构对象”，否则 `Closed-Loop Research` 在最关键的路径纠偏环节仍然缺少稳定契约：

1. Java 主系统需要把 `counterfactual branch summary` 做成 `Research Run detail / list` 的正式返回字段，而不是要求前端自己去扫描 `branch_decisions`。
2. 第一阶段至少要结构化暴露：
   - `counterfactual_branch_count`
   - `active_counterfactual_branch_ids`
   - `new_counterfactual_branch_ids`
   - `branch_reasons`
   - `target_evidence_ids`
   - `conflicted_row_count`
   - `has_counterfactual_recheck`
3. 这些字段应尽量从 `Table-as-State / branch_decisions / verifier_decisions / report_structure.conflict_and_counterfactual_review` 同源提取，避免前后端各自推断出不同结论。
4. 工作台前端随后应改为直接消费该结构对象来渲染：
   - 当前 counterfactual branch 摘要；
   - 相对 checkpoint 或 resume source 的 branch 增量；
   - 新增 verified findings 与冲突行变化。
5. 本轮测试至少覆盖：
   - `Research Run detail` 返回结构化 counterfactual summary；
   - `Research Run list` 返回轻量 counterfactual summary；
   - resumed run 仍能正确给出 counterfactual baseline 所需字段；
   - 前端构建与 UI contract 校验通过。

## 21. 本轮执行追加（十）（2026-07-06）

在 Java 主系统已经把 `counterfactual summary` 做成正式返回字段之后，下一步必须把这类摘要继续下沉到 worker 原始产物本身，否则 `反证分支` 仍然不是严格意义上的同源闭环对象：

1. Python Research Worker 需要直接生成结构化 `counterfactual_summary`，并把它写入：
   - `report_structure.counterfactual_summary`
   - `result_payload.counterfactual_summary`
   - `research_checkpoint_candidate.counterfactual_summary`
2. 该摘要至少要与当前已暴露的 Java 契约保持一致，覆盖：
   - `has_counterfactual_recheck`
   - `counterfactual_branch_count`
   - `conflicted_row_count`
   - `local_verifier_status`
   - `global_verifier_decision`
   - `recovery_mode`
   - `counterfactual_branch_ids`
   - `active_counterfactual_branch_ids`
   - `branch_reasons`
   - `target_evidence_ids`
   - `branches`
3. Java 主系统随后应优先消费 worker 直接产出的 `counterfactual_summary`，只有在旧 checkpoint 或旧 payload 没有该字段时才 fallback 到本地归纳逻辑。
4. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的关键纠偏摘要才真正满足：
   - worker 是原始状态生产者；
   - checkpoint 是同源快照；
   - Java 是持久化与透出层；
   - 前端只是展示层。
5. 本轮测试至少覆盖：
   - worker runner 的 `result_payload` 中存在结构化 `counterfactual_summary`；
   - `report_structure` 和 checkpoint payload 中存在同源 `counterfactual_summary`；
   - backend detail/list/checkpoint 优先返回该同源对象；
   - worker / backend / frontend 验证均通过。

## 22. 本轮执行追加（十一）（2026-07-06）

在 worker 已经把 `counterfactual_summary` 直接写入 `report_structure / result_payload / checkpoint` 之后，下一步还需要把它继续接入审计态与回放态，否则 `Closed-Loop Research` 在 trace 与 runtime state 两个关键观察面上仍然不够同源：

1. Java 主系统需要把 `counterfactual_summary` 作为正式 structured trace 类型持久化，例如：
   - `COUNTERFACTUAL_SUMMARY`
   - payload key 为 `counterfactual_summary`
2. `ResearchClosedLoopStateResponse` 也需要直接暴露 `counterfactual_summary`，避免前端必须绕回 detail 顶层字段或重新拼接。
3. 这样 `counterfactual_summary` 应同时存在于四个观察面：
   - worker `result_payload`
   - checkpoint payload
   - research trace
   - closed_loop_state
4. 前端随后应优先把 `closed_loop_state.counterfactual_summary` 作为运行态展示与审计 fallback，保证：
   - detail 顶层；
   - checkpoint 回放；
   - run 级闭环状态；
   三处对反证分支的理解一致。
5. 本轮测试至少覆盖：
   - `FINAL_REPORT` 之外存在 `COUNTERFACTUAL_SUMMARY` trace；
   - `closed_loop_state.counterfactual_summary` 可读；
   - `counterfactual_summary` 在 detail / closed_loop_state / checkpoint 三处字段一致；
   - backend / frontend 验证通过。

## 23. 本轮执行追加（十二）（2026-07-06）
围绕设计文档中 `4.1 任务进入与研究启动` 的产品口径，再做一次收束，避免后续实现把 `Deep Research` 误做成“工作台上下文升级模式”：

1. `Deep Research` 的任务入口只来自用户显式提交的研究问题，以及用户在创建任务时主动填写的目标、范围、限制条件、期望输出等研究输入。
2. 工作台不是研究任务的上下文来源；它只承担：
   - 承载独立 `Deep Research` 功能的入口、运行面板与历史管理；
   - 在研究完成后接收报告或确认后的研究资料回流进资料池。
3. 如果后续需要支持资料范围约束，也应定义为用户在创建任务时显式提供的研究输入或 `source scope`，而不是来自 Ask / Note / Wiki / 页面态的隐式继承上下文。
4. 后续文档、API、前端交互与 runtime 建模都应遵守这个边界：
   - 研究启动条件由用户输入驱动；
   - 工作台绑定的是运行容器与产物回流位置；
   - 不是“从某个上下文进入 Deep Research”。

## 24. 本轮执行追加（十三）（2026-07-06）
在 `counterfactual_summary` 已经进入 detail / list / checkpoint payload / closed_loop_state 之后，下一步优先补齐 `checkpoint lightweight summary`，避免研究历史与回放列表仍然必须依赖全量 payload 重放才能看懂当前闭环状态：

1. `research_checkpoints.summary_json` 不应只保存一个极薄的 checkpoint meta，而应成为 `Closed-Loop Research` 的轻量观察面，至少覆盖：
   - `loop_decision`
   - `local_verifier.status`
   - `global_verifier.decision`
   - `state_ledger.verified_row_count`
   - `state_ledger.conflicted_row_count`
   - `counterfactual_summary`
2. 这样 checkpoint 列表、resume 选择器、审计面板与 run 历史视图就能直接看到：
   - 当前 checkpoint 是继续读取、重新抽证还是反证复核；
   - verifier 认为它已经 `PASS / WRITE_WITH_GUARDRAILS / RECOVER` 到什么程度；
   - `Table-as-State` 里已经沉淀了多少已验证行、冲突行与反证分支迹象。
3. Java 主系统需要把这些字段以同源 lightweight summary 方式持久化并读回，而不是让前端从 `payload` 中二次推断。
4. 工作台前端随后应优先消费 checkpoint summary 来展示 checkpoint 历史卡片与对比信息，确保 `Research Harness / Closed-Loop Research / Dual Verifier / 反证分支` 在“历史视角”里也具备稳定契约。
5. 本轮测试至少覆盖：
   - checkpoint `summary_json` 中存在 verifier / ledger / counterfactual 关键字段；
   - checkpoint list / detail API 能稳定读回这些轻量字段；
   - 前端 checkpoint 摘要展示不依赖全量 payload 也能表达闭环状态；
   - backend / frontend 验证通过。

## 25. 本轮执行追加（十四）（2026-07-06）
在 checkpoint 已经拥有 richer `summary_json` 之后，下一步优先把工作台里的 `Checkpoint To Current Diff / Checkpoint To Checkpoint Diff / Resume Recovery Diff` 三类比较视图切换为 `summary-first`，避免最常见的历史比较仍然强依赖 full payload：

1. diff 视图中的第一层比较指标应优先来自 checkpoint summary，而不是来自完整 checkpoint payload，再次保证轻量观察面可独立成立。
2. 第一批优先切换为 summary-first 的字段至少包括：
   - `loop_decision.decision`
   - `local_verifier.status`
   - `global_verifier.decision`
   - `state_ledger.row_count / verified_row_count / conflicted_row_count / evidence_card_count`
   - `counterfactual_summary.counterfactual_branch_count`
3. 只有 `Verified Findings`、`Conflict Review`、`Evidence Ledger` 这类需要原始研究内容的深度面板，才继续回退到 payload 读取。
4. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在“历史比较”场景下也能满足：
   - 先看 verifier/loop/ledger/counterfactual 的闭环状态变化；
   - 再按需展开 payload 级细节；
   - 避免历史对比与恢复对比退化成“必须全量回放 JSON 才能看懂”。
5. 本轮测试至少覆盖：
   - checkpoint/current/checkpoint-resume diff 的核心数字优先来自 summary；
   - 当前没有 payload 明细时，summary 仍能支撑第一层差异展示；
   - 前端构建与 UI contract 校验通过。

## 26. 本轮执行追加（十五）（2026-07-06）
在 checkpoint diff 已经切到 `summary-first` 之后，下一步优先把 `Research Run 历史` 本身也升级为轻量闭环审计面，避免多 run 之间仍然只能看标题和状态：

1. `Research Run summary` 应继续补齐最关键的闭环字段，至少包括：
   - `local_verifier_status`
   - `ledger_row_count`
   - `verified_row_count`
   - `conflicted_row_count`
   - `counterfactual_summary.counterfactual_branch_count`
2. 工作台前端随后应基于这些 lightweight run summary 字段提供 `Run History Drift / Resume Lineage Diff`，至少能比较：
   - `status / local verifier / global verifier / final loop decision`
   - `ledger rows / verified rows / conflicted rows`
   - `checkpoint_count / source_scope_count`
   - `counterfactual branch count`
3. 如果当前 run 是恢复任务，则优先把恢复来源 run 作为 baseline；否则优先选择历史中的上一条 run 作为 baseline，形成稳定的 `run -> run` 观察链路。
4. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在“run 级历史视角”里也具备：
   - 轻量摘要即可理解研究状态是否变得更稳；
   - 恢复链路是否真的减少冲突、增加 verified rows；
   - 反证分支是在扩张、收敛还是被 guardrail 保留。
5. 本轮测试至少覆盖：
   - `list research runs` 返回新增 lightweight summary 字段；
   - 前端 run 历史对比面板优先消费这些字段；
   - backend / frontend 验证通过。

## 27. 本轮执行追加（十六）（2026-07-06）
在 run history 已经能比较 `status / verifier / row counts / counterfactual branches` 之后，下一步优先补齐 `reason-level summary`，让工作台能直接回答“为什么进入恢复、为什么还没有收敛”：

1. `Research Run summary` 除了状态字段，还应继续暴露最关键的原因字段，至少包括：
   - `local_verifier_reason`
   - `global_verifier_reason`
   - `final_loop_reason`
   - `recovery_mode`
2. 这些字段的优先来源应保持同源：
   - `local/global verifier decision_records.reason_code`
   - `loop_decision.reason`
   - `counterfactual_summary.recovery_mode` 或 `report_structure.recovery_mode`
3. 工作台前端随后应在 run 历史卡片、run 摘要和 `Run History Drift` 里显式展示这些原因，至少让用户能快速判断：
   - 当前 run 是因为 `CONFLICTING_EVIDENCE`、`LOW_CONFIDENCE` 还是 `STOP_CONTRACT_SATISFIED` 走到了当前状态；
   - 当前 `Dual Verifier` 是在放行、加 guardrail 还是要求恢复；
   - 当前 `反证分支` 是主动 recovery 还是历史遗留 unresolved mode。
4. 这样 run 级历史视角才能真正成为 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的轻量审计面，而不是只展示状态标签。
5. 本轮测试至少覆盖：
   - `list research runs` 返回新增 reason 字段；
   - 正常 run / conflict run / resumed queued run 的 reason 行为正确；
   - 前端构建与 UI contract 校验通过。

## 28. 本轮执行追加（十七）（2026-07-06）
在 run history 已经拥有 reason-level summary 之后，下一步优先把这些 reason 变成时间线上的可扫描信号，避免用户仍然需要逐卡阅读长文本才能定位失稳节点：

1. 工作台前端应把 `Research Run 历史` 升级为 `signal-aware timeline`，至少支持：
   - 按 `All / Recovery / Conflict / Stable / Resumed` 做轻量筛选；
   - 在 run 卡片上直接展示 `reason chips`；
   - 让高风险节点在视觉上更容易被扫到。
2. `checkpoint list` 也应直接展示轻量 signal，优先来自 summary 中的：
   - `local/global verifier status`
   - `loop_decision.reason`
   - `counterfactual_summary.recovery_mode`
3. signal 的优先级应服务于 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的审计目标：
   - `CONFLICTING_EVIDENCE`、`COUNTERFACTUAL_OR_LOW_CONFIDENCE`、`WRITE_WITH_GUARDRAILS` 等属于风险/纠偏信号；
   - `CHECKPOINT_VALID`、`STOP_CONTRACT_SATISFIED`、`READY_TO_WRITE` 更偏稳定/收敛信号；
   - `resumed_from_*` 属于 lineage 信号。
4. 这样 run/checkpoint 时间线就不再只是历史列表，而能直接回答：
   - 哪一跳开始进入 guardrail；
   - 哪一跳触发 counterfactual recovery；
   - 哪一跳已经重新回到 stable path。
5. 本轮测试至少覆盖：
   - 历史筛选可用；
   - run/checkpoint 卡片可见 signal chips；
   - 前端构建与 UI contract 校验通过。

## 29. 本轮执行追加（十八）（2026-07-06）
在 run/checkpoint 历史已经具备 signal chips 与筛选之后，下一步优先把这些历史信号继续上提为 `milestone timeline`，让工作台直接告诉用户“哪一跳开始失稳、哪一跳进入恢复、哪一跳重新稳定”：

1. 工作台前端应基于已有 `Research Run summary` 字段提炼关键转折点，至少包括：
   - 首次进入 `conflict`
   - 首次进入 `recovery`
   - 首次恢复到 `stable path`
   - 若存在 `resumed` 任务，则可单独标记第一次恢复链路节点
2. 这些 milestone 应优先按 run 的时间顺序从 lightweight summary 聚合，而不是通过 detail payload 回放推断。
3. milestone 视图应显式关联：
   - run 标题 / run id
   - 时间
   - signal chips
   - 该转折点的主 reason
4. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的运行轨迹就不只是“很多历史卡片”，而是更接近：
   - 从 stable 到 drift；
   - 从 drift 到 recovery；
   - 从 recovery 到 re-stabilize；
   的闭环收敛时间线。
5. 本轮测试至少覆盖：
   - milestone timeline 可见；
   - milestone 由 lightweight summary 聚合得出；
   - 前端构建与 UI contract 校验通过。

## 30. 本轮执行追加（十九）（2026-07-06）
在 milestone timeline 已经能标出关键转折点之后，下一步优先把这些 milestone 继续压缩成 `transition path summary`，让用户能一眼看出 `Closed-Loop Research` 的收敛轨迹，而不是自己在时间线上拼接路径：

1. 工作台前端应基于已有 milestone 与 run tone 提炼一条轻量路径摘要，至少能表达：
   - `stable -> conflict`
   - `conflict -> recovery`
   - `recovery -> stable`
   - 若存在 `resumed` 链路，则应能在路径中插入 `resume`
2. 这条路径摘要仍然只依赖 lightweight run summary 与 milestone 聚合结果，不通过 detail payload 反推。
3. 路径摘要应同时暴露：
   - transition 序列本身；
   - 当前路径停在什么阶段；
   - 是否已经出现 `re-stabilize`
4. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的运行轨迹就不只是“有几个 milestone”，而是可以被阅读成：
   - 从稳定起步；
   - 在哪里失稳；
   - 通过什么恢复方式纠偏；
   - 是否已经重新收敛。
5. 本轮测试至少覆盖：
   - transition path summary 可见；
   - path summary 由 lightweight summary / milestone 聚合得出；
   - 前端构建与 UI contract 校验通过。

## 31. 本轮执行追加（二十）（2026-07-06）
在 `transition path summary` 已经能展示全局闭环路径之后，下一步优先把它与“当前选中的 run”对齐，避免用户仍然要自己判断当前查看对象落在路径的哪一阶段：

1. 工作台前端应显式展示：
   - 当前路径停留阶段；
   - 当前选中 run 所在阶段；
   - 二者是否一致。
2. 当前 run 的阶段判断仍然应优先基于 lightweight run summary 的 signal/tone，而不是依赖 detail payload 反推。
3. 若当前 run 不是全局最新阶段，也应明确提示用户：
   - 当前查看的是历史阶段；
   - 全局路径已经前进到哪个阶段。
4. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的时间线就同时具备：
   - 全局闭环路径；
   - 当前 run 所在位置；
   - 当前 run 与整体路径之间的关系。
5. 本轮测试至少覆盖：
   - 路径摘要中可见当前 run stage；
   - stage 判断来自 lightweight run summary；
   - 前端构建与 UI contract 校验通过。

## 32. 本轮执行追加（二十一）（2026-07-06）
在路径摘要已经能显示“当前 run 所在阶段”之后，下一步优先把这个阶段同步高亮到历史节点本身，避免路径、节点和当前选择仍然是三块割裂的信息：

1. 工作台前端应让与“当前 run 阶段”一致的历史卡片和 milestone 产生联动高亮，而不仅仅在路径摘要里显示文字说明。
2. 这种联动仍应基于 lightweight run summary 的阶段判断，不依赖 detail payload。
3. 若 milestone 是 `回稳`，则它与当前 run 的 `稳定` 阶段应能被视为同一类闭环收敛阶段，避免高亮错失。
4. 这样 `Closed-Loop Research` 时间线就能同时回答：
   - 当前 run 所在阶段是什么；
   - 历史中哪些节点属于同类阶段；
   - 当前查看对象与整体闭环路径的对应关系。
5. 本轮测试至少覆盖：
   - milestone 可显示当前阶段命中；
   - history card 可显示当前阶段命中；
   - 前端构建与 UI contract 校验通过。

## 33. 本轮执行追加（二十二）（2026-07-06）
围绕设计文档 `4.1 任务进入与研究启动` 的产品边界，本轮先收紧 `Deep Research` 的入口定义，避免后续实现再次滑回“从上下文升级进入研究”的错误方向：

1. `Deep Research` 必须被定义为工作台内的独立功能，而不是 Ask / Note / Wiki / 页面态的一种升级模式。
2. 研究任务的启动条件只来自用户显式输入，系统不应把任何页面、对话、笔记或资料上下文当作隐式研究输入。
3. 若需要限定资料范围、时间范围、排除项、关键词或输出格式，也应统一建模为用户在创建研究任务时填写的显式输入，而不是上下文继承。
4. 工作台与 `Deep Research` 的关系应固定为两点：
   - 承载独立研究入口、运行面板与历史管理；
   - 在研究完成后接收报告或确认后的研究资料回流进入资料池。
5. 本轮设计文档修正后，后续 API、前端交互、运行时建模与资料写回策略都应遵守这条边界：
   - 任务由用户输入驱动；
   - 工作台只负责承载与回流；
   - 不再出现“从某个上下文进入 Deep Research”的产品叙述。

## 34. 本轮执行追加（二十三）（2026-07-06）
在设计文档已经明确 `Deep Research` 不继承聊天/Note/Wiki/页面上下文之后，下一步要把这条边界继续推进到研究任务契约本身，避免 Java 主系统、worker 输入和前端请求仍然透出旧的 `context snapshot` 语义：

1. `CreateResearchRunRequest` 不应再接受 `context_snapshot_id` 作为研究启动输入，因为这会暗示研究任务可以从工作台上下文隐式派生。
2. `ResearchWorkerInputResponse / ResearchWorkerInputPayload / ResearchRunDetailResponse` 也不应继续暴露 `context_snapshot` 相关字段，避免 worker 与前端运行面板仍基于旧契约理解 Deep Research。
3. 研究任务唯一允许的启动输入应收敛为：
   - 用户显式问题；
   - 研究 profile；
   - 用户显式选择的 `source scope`；
   - 以及后续若扩展时同样由用户显式填写的研究约束项。
4. 数据库存量列可以暂时保留以兼容旧数据，但新建 run、resume run、worker input 和详情 API 均不应再依赖或回传该字段。
5. 本轮测试至少覆盖：
   - research create 接口在无 `context_snapshot_id` 时通过；
   - worker input 不再返回 `context_snapshot`；
   - research detail 不再返回 `context_snapshot_id`；
   - backend / frontend / worker 相关校验通过。

## 35. 本轮执行追加（二十四）（2026-07-06）
在 `Deep Research` 的启动契约已经收紧到“显式问题 + profile + source scope”之后，下一步优先把“显式研究约束”正式建模为 `ResearchIntent`，避免研究任务仍然只靠一句自然语言问题启动，导致目标、范围、交付物和预算难以被 `Research Harness` 审计：

1. `CreateResearchRunRequest` 应继续扩展为显式研究输入契约，至少覆盖：
   - `question`
   - `research_goal`
   - `deliverable_format`
   - `constraints`
   - `time_range`
   - `depth`
   - `source_scope_source_ids`
2. Java 主系统应把这组输入持久化为研究任务的 `ResearchIntent` 真源，而不是只在前端表单或 worker 内存态里暂存。
3. `ResearchWorkerInputPayload` 与 worker planner 应显式消费 `ResearchIntent`，并把它转成：
   - 更稳定的 query set；
   - 更明确的 stop contract；
   - 更可审计的 planner notes / report sections。
4. 前端 `Deep Research` 独立入口应暴露这些显式输入，使用户创建 run 时能主动指定研究目标、输出格式、深度档位与限制条件，而不是全部塞进一个问题文本框。
5. 本轮测试至少覆盖：
   - research create / detail / worker input 能贯通 `ResearchIntent`；
   - worker planner 会根据显式约束调整 query set 与 stop contract；
   - backend / frontend / worker 验证通过。

## 36. 本轮执行追加（二十五）（2026-07-06）
围绕设计文档 `4.1 任务进入与研究启动`，继续把产品边界收紧到“独立功能 + 用户输入驱动”，避免 `Deep Research` 再被误写成“与工作台上下文绑定的升级模式”：

1. `Deep Research` 的任务进入方式应只有一种：用户显式创建研究任务；不存在“从问答上下文进入”“从 Note / Wiki 进入”或“基于已选资料上下文进入”的入口分类。
2. 工作台与 `Deep Research` 的绑定只体现在：
   - 提供独立入口、运行面板与历史管理；
   - 接收研究完成后的报告或确认后的研究资料回流到资料池。
3. 研究任务的启动输入全部由用户填写：
   - `question`
   - `research_goal`
   - `deliverable_format`
   - `constraints`
   - `time_range`
   - `depth`
   - 可选 `source scope`
   - 可选写回策略
4. 若后续支持定向研究，也应被定义为“用户显式填写了资料范围或研究约束”的同一入口下的可选输入，而不是新增一个“基于上下文发起研究”的产品模式。
5. 本轮文档修正后，后续编排设计、工程落地设计、API 命名与前端交互文案都应遵守这条边界：
   - 工作台负责承载与回流；
   - 研究任务由用户输入成立；
   - 上下文不再被视为研究入口。

## 37. 本轮执行追加（二十六）（2026-07-06）
在 `ResearchIntent` 已经能贯通 create/detail/worker/verifier 之后，下一步优先把它从“对齐信号”继续下沉为 `Table-as-State` 的完成契约，避免 `Closed-Loop Research` 仍然只能在报告阶段才判断是否跑偏：

1. `state_ledger` 不应只记录已有 row/cell/evidence 数量，还应显式记录“当前研究任务到底要求哪些结论字段被填满并通过验证”。
2. 第一阶段优先把 `ResearchIntent` 折叠成可审计的 `intent completion contract`，至少覆盖：
   - 必须满足的研究目标摘要；
   - 交付格式要求；
   - 时间范围要求；
   - 显式约束项；
   - 每个约束当前是否已经在 `Table-as-State` 中找到对应验证锚点。
3. worker 应把这个 contract 直接写入 `state_ledger` 与 `closed_loop_state`，让 `Local Verifier / Global Verifier / checkpoint summary / report structure` 消费同一个真源，而不是各自临时推断。
4. Java 主系统与前端随后都应直接透出这组结构对象，使工作台可以明确展示：
   - 当前还缺哪类 requirement；
   - 哪些 requirement 已经被证据覆盖；
   - 当前 run 是因为哪些 requirement 未满足而继续恢复或进入 guardrail。
5. 本轮测试至少覆盖：
   - worker `state_ledger` / `closed_loop_state` 中存在 intent completion contract；
   - backend detail / run summary / checkpoint summary 能稳定读回关键字段；
   - frontend 至少能在当前 run 视图中展示 requirement completion 摘要；
   - backend / frontend / worker 验证通过。

## 38. 本轮执行追加（二十七）（2026-07-06）
在 `intent completion contract` 已经进入 worker / backend / frontend 之后，下一步优先把它继续压到 `row / cell` 完成语义本身，避免 `Table-as-State` 仍然只是在事后展示 requirement 状态：

1. `Research Planner` 需要把 `ResearchIntent` 继续折叠成更细粒度的 `required finding contract`，至少定义：
   - 哪些 finding 类型属于本次研究必须覆盖；
   - 每类 finding 需要哪些核心 columns 才算完成；
   - 哪类 finding 必须达到 `VERIFIED`，哪类 finding 允许以 `CONFLICTED / NEXT_ACTION` 形式保留。
2. `ResearchStateRow / ResearchStateCell` 不应只保存事实抽取结果，还应显式记录：
   - 该 row / cell 在满足哪个 requirement；
   - 当前 requirement completion status；
   - 还缺哪些 required columns 或 verification 条件。
3. `Local Verifier / Global Verifier` 之后对是否继续恢复、是否允许合成的判断，应优先消费这些 row/cell 级 completion signals，而不是只看总 row 数或最终 alignment 摘要。
4. Java 主系统与工作台随后应直接显示这类完成度信息，使用户能判断：
   - 当前哪些 row 只是有证据，但还不满足 requirement；
   - 哪些 row 已经可以作为 verifier-ready finding；
   - 为什么某条 requirement 仍然卡在 `PARTIAL / MISSING`。
5. 本轮测试至少覆盖：
   - worker `state_ledger` 中存在 row/cell 级 requirement completion 字段；
   - verifier 会基于 requirement-ready signals 给出更准确的 PASS/WARN；
   - backend / frontend 至少能透出 requirement-ready row count 或 missing column 摘要；
   - backend / frontend / worker 验证通过。

## 39. 本轮执行追加（二十八）（2026-07-06）
在 `required finding contract` 已经进入 `Table-as-State` 与 `Dual Verifier` 之后，下一步优先把“哪些 requirement 仍未完成”前推为检索、读取与恢复阶段的调度信号，避免 `Closed-Loop Research` 仍然主要依赖末端 verifier 才发现路径跑偏：

1. `Research Harness` 应基于 `required_finding_progress` 生成显式的 `recovery target requirements`，至少包括：
   - 当前缺失的是哪类 finding；
   - 每类 finding 还缺哪些 required columns；
   - 下一轮更适合优先补 search、read 还是 counterfactual branch。
2. `Research Search` 不应只按通用 query bundle 或 `recovery_target_sources` 排序，还应优先放大与未完成 requirement 更相关的 query：
   - goal finding 缺失时优先补主问题直答查询；
   - evidence finding 缺失时优先补带有证据/验证语义的查询；
   - counterfactual / 反证 finding 缺失时优先补冲突、替代解释、反例方向的查询。
3. `Research Read` 不应只按 source 命中与 recovery mode 排序，还应优先保留更可能补齐 requirement columns 的窗口，使 `Table-as-State` 的空列能被定向填补，而不是随机增加窗口数量。
4. `Loop Runtime` 在决定下一轮恢复策略时，应把 `required_finding_progress` 视为 first-class signal：
   - requirement 仍是 `MISSING` 时优先进入定向补齐；
   - requirement 长期停在 `PARTIAL` 时优先进入 targeted read / extract；
   - 反证 requirement 缺失时优先触发 bounded counterfactual recheck。
5. 本轮测试至少覆盖：
   - worker 会把 requirement-driven recovery targets 写回 `stop_contract` 或等价 runtime hints；
   - search/read 排序会受未完成 requirement 影响；
   - loop recovery 会优先针对缺失 requirement 纠偏，而不是只走通用恢复分支；
   - backend / frontend / worker 验证通过。

## 40. 本轮执行追加（二十九）（2026-07-06）
在 requirement-driven recovery hints 已经进入 worker runtime 之后，下一步优先把它们透出为可审计的 `closed-loop recovery target view`，避免 `Research Harness` 虽然已经定向纠偏，但工作台仍然只能看到“进入了恢复模式”，却看不到“究竟在补哪类 requirement、缺哪几列、下一轮要查什么”：

1. worker `report_structure.closed_loop_state` 应直接暴露当前恢复靶点，至少包括：
   - `recovery_target_requirement_ids`
   - `recovery_target_requirement_labels`
   - `recovery_target_requirement_types`
   - `recovery_target_columns`
   - `recovery_target_queries`
   - `recovery_target_sources`
2. 这些字段应与 `Loop Runtime` 中写回 `stop_contract` 的 runtime hints 同源，避免 report、checkpoint summary、前端视图各自重建一套“恢复原因”。
3. Java 主系统随后应把这组 recovery target 摘要稳定写入：
   - run detail 的 `closed_loop_state`
   - checkpoint summary / checkpoint payload 的轻量审计摘要
   - 必要时 run summary，供历史对比直接判断“这一轮恢复在补什么”
4. 工作台前端应显式显示 recovery target 摘要，使用户能够直接判断：
   - 当前恢复在补哪类 requirement；
   - 当前缺的核心 columns 是什么；
   - 下一轮检索/读取会优先围绕哪些 query/source 展开；
   - `反证分支` 是在补 conflict finding，还是普通 evidence finding。
5. 本轮测试至少覆盖：
   - worker report / closed_loop_state 中可见 recovery target hints；
   - backend detail / checkpoint summary 稳定透出 recovery target 摘要；
   - frontend 当前 run / checkpoint 视图能显示 recovery targets；
   - backend / frontend / worker 验证通过。

## 41. 本轮执行追加（三十）（2026-07-06）
在 recovery target 摘要已经进入当前 run 与 checkpoint 审计面之后，下一步优先把它继续贯通到 `run history summary / Run History Drift`，避免历史链路仍然只能看到“恢复发生了”，却看不到“历史上每一轮恢复分别在补什么 requirement”：

1. Java `list research runs` 的 lightweight summary 应继续补齐 recovery target 摘要，至少包括：
   - `recovery_target_requirement_labels`
   - `recovery_target_requirement_types`
   - `recovery_target_columns`
   - `recovery_target_queries`
   - `recovery_target_sources`
2. 这些字段应直接来自 run detail / report structure / checkpoint summary 同源的 recovery target 真源，而不是在 run summary 接口里临时根据 reason code 二次猜测。
3. 工作台前端随后应在以下两个位置直接显示 recovery target 摘要：
   - `Research Run Summary`
   - `Run History Drift`
   使用户可以判断当前 run 相比 baseline：
   - 恢复靶点是否从 goal finding 转成了 evidence finding；
   - 缺失列是否已经变化；
   - query/source 是否已经从泛恢复收敛到定向 recovery。
4. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在历史视角里才能真正回答：
   - 哪一轮恢复是在补 conflict finding；
   - 哪一轮恢复已经把目标缩小到具体 column/query/source；
   - run-to-run 漂移是否正在收敛，还是 recovery target 仍然在发散。
5. 本轮测试至少覆盖：
   - backend run summary 返回 recovery target 摘要；
   - frontend run summary / drift diff 显示 recovery target 摘要；
   - backend / frontend / worker 验证通过。

## 42. 本轮执行追加（三十一）（2026-07-06）
在 recovery target 已经进入 run summary / drift diff 之后，下一步优先把它继续压进 `timeline / signal chips` 这一层，避免用户仍然需要进入卡片正文才能看懂“这一轮恢复到底在补哪类 requirement”：

1. `buildRunSignalChips` 应直接消费 run summary 中的 recovery target 摘要，至少产出：
   - 当前恢复瞄准的是 `goal / evidence / conflict` 哪一类 requirement；
   - 当前恢复聚焦的是哪几个核心 columns 或 source/query 维度。
2. `关键转折时间线` 与 `Research Run 历史卡片` 应复用同一套 recovery target signal，而不是各自再拼一段单独文案。
3. 这样时间线视角就能直接回答：
   - 首次进入 recovery 时补的是不是 conflict finding；
   - 后续 recovery 是否已经从广义恢复收敛到具体 columns；
   - 当前 milestone 的纠偏方向与当前 run 是否一致。
4. `Run History Drift` 随后应保持与 signal 同源，使 drift 卡片里的 target 变化能与时间线 chip 一一对应，而不是出现两套不同口径。
5. 本轮测试至少覆盖：
   - frontend build / UI contract 通过；
   - recovery target signal 出现在 run history / timeline 相关渲染路径中；
   - backend / frontend / worker 验证通过。

## 43. 本轮执行追加（三十二）（2026-07-06）
在 recovery target 已经进入 run / checkpoint 的 signal 与 diff 之后，下一步优先把它继续贯通到 `Counterfactual Branch Diff / Resume Recovery Diff` 的解释层，避免工作台虽然能显示“发生了反证分支”，却仍然不能直接回答“这次反证恢复到底在补 conflict target，还是只是普通 evidence recovery”：

1. `Counterfactual Branch Diff` 应显式显示 baseline 与 current 的 recovery target 摘要，至少包括：
   - recovery target type
   - recovery target columns
   - recovery target queries / sources
2. `Resume Recovery Diff` 与 `Counterfactual Branch Diff` 应共享同一套 recovery target 摘要函数，避免一个视图说的是“conflict/evidence/goal”，另一个视图又退化回原始 query 文本。
3. 这样用户就能直接判断：
   - 当前反证恢复是否真的在围绕 `CONFLICT_FINDING` 收敛；
   - 当前 resume 后的 recovery target 是否比来源 checkpoint 更具体；
   - 反证分支是在继续扩散，还是已经收敛到少数 columns / queries。
4. 这一步会进一步强化 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的“路径纠偏可解释性”，让反证不只表现为 branch count，而是表现为“有明确靶点的纠偏动作”。
5. 本轮测试至少覆盖：
   - frontend build / UI contract 通过；
   - counterfactual / resume diff 中出现 recovery target delta；
   - backend / frontend / worker 验证通过。

## 44. 本轮执行追加（三十三）（2026-07-06）
围绕升级设计文档 `4.1 任务进入与研究启动` 的产品边界，再做一次口径收紧，避免 `Deep Research` 在后续实现中被重新写回“工作台上下文升级模式”：

1. `Deep Research` 必须持续定义为工作台中的独立功能，只是入口、运行容器、历史管理与产物回流绑定在工作台里，而不是从 `Ask / Note / Wiki / 页面态` 升级出来的研究模式。
2. 研究任务的成立条件只能是“用户显式输入了研究问题与研究约束”；研究输入不应依赖任何聊天上下文、Note 上下文、Wiki 上下文或当前页面状态。
3. 若用户希望做定向研究，已选资料、粘贴资料、上传资料、关键词范围、排除项、时间范围等，都应被定义为同一个创建入口下的显式输入字段，而不是新的“基于上下文发起研究”入口分类。
4. 工作台既不负责提供研究问题来源，也不负责隐式拼装研究上下文；它只负责承载 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的运行面板，以及接收最终确认后的研究报告与资料回流。
5. 后续若继续推进前端入口、后端 create 接口、worker intent 契约或文档时序图，都必须遵守这条边界：只接收用户输入，不继承上下文；只回流产物，不把上下文当成任务来源。

## 45. 本轮执行追加（三十四）（2026-07-06）
在 recovery target 已经进入 run summary、signal chips、checkpoint diff 与 counterfactual/resume diff 之后，下一步优先把它继续压进 `timeline milestone / path narrative` 这一层，避免 `Research Harness` 虽然已经显式给出纠偏靶点，但用户仍然只能在卡片字段里拼装理解，无法从时间线叙事里直接看出 `Closed-Loop Research` 到底在往哪里收敛：

1. 时间线不应只显示“进入 recovery / 发生 counterfactual / verifier 发出警告”这类阶段事件，还应直接说明这次纠偏针对的是哪类 requirement：
   - 是 `goal finding`；
   - 是 `evidence finding`；
   - 还是 `conflict finding / 反证分支`。
2. 路径叙事不应只说系统“正在恢复”“继续修正”，而应补出恢复焦点是否已经收敛到具体 `columns / queries / sources`，从而让 `Table-as-State` 的缺口与 `Dual Verifier` 的纠偏方向在 narrative 层可见。
3. `Research Timeline`、`Run History Path Summary` 与相关 milestone 描述应优先复用现有 recovery target helper，保持与 run summary / drift diff / signal chips 同源，避免前端再次生成另一套不一致的“恢复原因文案”。
4. 这样工作台才能直接解释：
   - 这轮 Closed-Loop Recovery 是在补哪类 requirement；
   - 路径是否已经从泛化恢复收敛到定向 column/query/source；
   - 反证分支是在扩散还是已经围绕少数冲突点收束。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - 时间线 / 路径叙事中出现 recovery target narrative；
   - backend / worker 无需改动时保持契约兼容。

## 46. 本轮执行追加（三十五）（2026-07-06）
在 recovery target narrative 已经进入 `timeline milestone / path narrative` 之后，下一步优先把它继续贯通到 `checkpoint playback / resume recovery / counterfactual branch` 的正文解释层，避免当前工作台虽然已经能在 diff 与 signal chip 里展示靶点，但用户在阅读 checkpoint 审计正文时仍然只能看到泛化的“恢复中 / 发生分支 / 从 checkpoint 续跑”说明：

1. checkpoint 回放正文不应只枚举字段 delta，还应直接说明当时 `Research Harness` 的纠偏目标属于哪类 requirement，以及靶点是否已经收敛到具体 `columns / queries / sources`。
2. `Resume Recovery Diff` 与 `Counterfactual Branch Diff` 不应只在标题下展示结构化 delta，还应在正文说明中复用同一套 recovery target narrative，保证 `Closed-Loop Research` 的恢复、续跑、反证分支三条解释链路同源。
3. `Checkpoint To Current Diff / Checkpoint To Checkpoint Diff` 若已经出现 recovery target delta，也应在紧邻描述文本里补出“恢复目标是扩散了还是收敛了”的一句话总结，避免用户还要自己读列表推断。
4. 这样 `Table-as-State / Dual Verifier / 反证分支` 在回放视图里才能直接回答：
   - 当时缺的是 `goal / evidence / conflict` 哪类 requirement；
   - 续跑后是否把恢复范围缩小到了更具体的列、查询或来源；
   - 反证分支是在继续扩散，还是已经围绕少量冲突点收束。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - checkpoint / resume / counterfactual 视图出现 recovery target narrative；
   - backend / worker 契约保持兼容。

## 47. 本轮执行追加（三十六）（2026-07-06）
在 recovery target narrative 已经进入 checkpoint / resume / counterfactual 回放解释层之后，下一步优先把它继续贯通到 `当前 run 主摘要 / Run History Drift`，避免工作台只在时间线或 checkpoint 审计里能看出纠偏方向，但用户回到主视图时又只能看到结构化字段，无法直接读出当前 `Research Harness` 是否正在收敛：

1. `Run 摘要` 不应只展示 `recovery_targets / columns / queries` 原始字段，还应直接给出一句当前 recovery target narrative，说明当前闭环在补哪类 requirement、聚焦点是否已经收敛到 `columns / queries / sources`。
2. `Run History Drift` 不应只显示 baseline 与 current 的 target delta，还应给出一句 drift narrative，直接判断恢复目标是更收敛、更扩散、保持稳定还是发生迁移。
3. 当前态与历史态都应复用 checkpoint / timeline 已使用的同一套 narrative helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在整个工作台中采用同源解释，而不是每个视图各写一套文案。
4. 这样主视图就能直接回答：
   - 当前 run 正在补 `goal / evidence / conflict` 哪类 requirement；
   - 当前恢复焦点是否已经缩到少数列、查询或来源；
   - 与历史 baseline 相比，路径是在继续漂移还是已经重新收敛。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - current run / run history drift 视图出现 recovery target narrative；
   - backend / worker 契约保持兼容。

## 48. 本轮执行追加（三十七）（2026-07-06）
在 recovery target narrative 已经进入当前 run 主摘要与 Run History Drift 之后，下一步优先把它继续贯通到 `当前 run 的 closed-loop 明细卡 / side events`，避免工作台虽然已经能在摘要、历史与 checkpoint 里解释纠偏方向，但用户回到当前执行态时仍然只能看到 verifier reason、next actions 与 recovery target 原始字段，无法直接读出 `Dual Verifier` 此刻到底在围绕什么 requirement 做闭环修正：

1. `Current Recovery Status / Closed-Loop State` 不应只显示 `recovery targets / columns / queries`，还应直接给出一句当前 recovery target narrative，说明当前闭环在补哪类 requirement、焦点是否已经收敛到 `columns / queries / sources`。
2. `side events / timeline-like event feed` 不应只堆叠原始事件文本；当当前 run 处于 recovery / conflict / counterfactual path 时，应补出一条与事件同源的 recovery narrative，帮助用户快速理解本轮漂移正在如何被 `Research Harness` 纠偏。
3. 当前执行态、主摘要、历史态、checkpoint 回放态都应复用同一套 recovery narrative helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在所有主视图中的解释口径一致。
4. 这样当前 run 主视图就能直接回答：
   - `Local Verifier / Global Verifier` 当前在补 `goal / evidence / conflict` 哪类 requirement；
   - 当前恢复焦点是否已经收敛到具体列、查询或来源；
   - 当前 side events 指向的是泛化恢复、定向纠偏还是反证收束。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - current closed-loop state / side events 视图出现 recovery target narrative；
   - backend / worker 契约保持兼容。

## 49. 本轮执行追加（三十八）（2026-07-06）
在 recovery target narrative 已经进入当前 run 的 closed-loop 明细卡与 side events 之后，下一步优先把它继续贯通到 `checkpoint 列表摘要 / run 历史卡片摘要`，避免用户只有点进详情后才能看懂当前 `Closed-Loop Research` 的纠偏方向，而在列表层仍然只能看到状态字段与原始 target 标签：

1. checkpoint 列表项不应只显示 `recovery=...` 或 verifier 状态，还应直接补一条 recovery narrative，让用户在进入某个 checkpoint 详情前就能知道该快照主要在补哪类 requirement、焦点是否已经收敛到具体 `columns / queries / sources`。
2. run 历史卡片不应只依赖 signal chips 承载恢复含义，还应在摘要正文中补一条当前 recovery narrative，便于列表浏览时直接区分“泛化恢复”“定向纠偏”“反证收束”等不同路径状态。
3. 这两层摘要视图应继续复用与当前执行态、Run History Drift、checkpoint diff 相同的 helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的叙事在列表态与详情态之间保持同源。
4. 这样用户在不进入详情的情况下也能快速判断：
   - 某个 checkpoint / run 正在补 `goal / evidence / conflict` 哪类 requirement；
   - 恢复焦点是已经收敛到少数列、查询或来源，还是仍在扩散；
   - 哪些历史节点更像普通恢复，哪些节点已经进入反证分支的定向收束。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - checkpoint 列表 / run 历史卡片出现 recovery target narrative；
   - backend / worker 契约保持兼容。

## 50. 本轮执行追加（三十九）（2026-07-06）
在 recovery target narrative 已经进入 checkpoint 列表摘要与 run 历史卡片摘要之后，下一步优先把它继续贯通到 `当前 run 页面顶部摘要 / report_structure 段落说明`，避免工作台外围卡片已经能解释当前 `Closed-Loop Research` 的纠偏方向，但用户真正阅读研究报告与结构化段落时，又只能看到原始字段与 next actions，看不到 `Research Harness` 为什么把路径收敛到这些 requirement / columns / queries / sources：

1. 当前 run 页面顶部摘要不应只重复基础状态字段，还应直接给出一句 recovery narrative，让用户一进入 run 正文就知道当前闭环在补哪类 requirement、焦点是否已收敛。
2. `report_structure` 中的 `Recovery Status`、`Conflict And Counterfactual Review` 等段落不应只展示策略名、冲突行或 next actions，还应补出与当前 run 同源的 recovery narrative，说明 `Dual Verifier` 当前为何要求继续恢复、是否已经进入反证分支定向收束。
3. 这层报告区说明必须继续复用现有 narrative helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在列表、摘要、当前执行态与正文结构化报告之间采用同一口径。
4. 这样用户在阅读报告区时就能直接判断：
   - 当前 run 正在补 `goal / evidence / conflict` 哪类 requirement；
   - 恢复焦点是否已经缩到少数列、查询或来源；
   - 当前 report_structure 描述的是普通恢复、定向纠偏，还是反证收束路径。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - 当前 run 页面顶部摘要 / report_structure 段落出现 recovery target narrative；
   - backend / worker 契约保持兼容。

## 51. 本轮执行追加（四十）（2026-07-06）
在 recovery target narrative 已经进入当前 run 页面顶部摘要与 `report_structure` 段落说明之后，下一步优先把它继续贯通到 `Final Report Preview / 报告写回资料池前确认层`，避免 `Closed-Loop Research` 的纠偏解释仍然停留在工作台运行视图里，而到了最终产物出口时只剩一份正文或一个保存动作，看不出这份产物是在怎样的 `Research Harness` 收敛路径下产出的：

1. `Final Report Preview` 不应只展示报告正文，还应补一条 recovery narrative 摘要，让用户在阅读最终输出前就能知道当前 run 最后一次恢复主要在补哪类 requirement、焦点是否已经收敛到具体 `columns / queries / sources`。
2. 报告写回资料池前的确认层或写回动作附近，不应只给“写回”按钮；还应补出一句与当前 run 同源的 recovery narrative，提醒用户即将沉淀的产物对应的是普通恢复、定向纠偏还是反证收束路径。
3. 这层产物出口说明应继续复用现有 helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在运行视图、报告结构视图与产物输出视图之间保持同一套解释口径。
4. 这样用户在最终产物阶段就能直接判断：
   - 当前要保存/复用的报告主要补齐了 `goal / evidence / conflict` 哪类 requirement；
   - 最终收口前恢复焦点是否已经缩到少数列、查询或来源；
   - 这份报告更像稳定综合结果，还是沿着反证分支收束后的输出。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - Final Report Preview / 写回动作附近出现 recovery target narrative；
   - backend / worker 契约保持兼容。

## 52. 本轮执行追加（四十一）（2026-07-06）
在 recovery target narrative 已经进入 `Final Report Preview / 报告写回资料池前确认层` 之后，下一步优先把它继续贯通到 `全局入口层摘要`，避免用户一离开当前 run 详情，就又只能看到“最近一次 run / 空态 / 写回成功提示”这类泛化入口，而看不到当前 `Closed-Loop Research` 是在收敛、迁移还是扩散：

1. `最近一次 Research Run` 概览不应只显示状态与原始 recovery 字段，还应直接补一条 recovery narrative，让用户在研究入口页就能快速知道当前 run 的纠偏焦点与路径状态。
2. 写回资料池成功后的入口反馈，不应只提示 `source_id / status / index_status`，还应补出一句与当前 run 同源的 recovery narrative，说明被沉淀下来的产物对应的是怎样的闭环收敛路径。
3. 研究空态或入口提示不应完全脱离当前 `Research Harness` 语境；如果当前没有 run，应保持独立 Deep Research 入口口径；如果已有最近一次 run，则应优先展示当前 recovery narrative，帮助用户决定是继续恢复、查看报告，还是回放 checkpoint。
4. 这层入口摘要必须继续复用现有 narrative helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在全局入口、列表、详情、回放与产物出口各层之间保持同源解释。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - 最近一次 Research Run / 写回反馈 / 入口层出现 recovery target narrative；
   - backend / worker 契约保持兼容。

## 53. 本轮执行追加（四十二）（2026-07-06）
在 recovery target narrative 已经进入全局入口层摘要之后，下一步优先把它继续贯通到 `创建 run / 从 checkpoint 恢复 / 生命周期系统消息`，避免用户在真正触发 Deep Research 生命周期动作时，仍然只能看到“已创建 run”“已从 checkpoint 恢复”“已写回资料池”这类结果提示，而看不到这些动作接下来会沿着怎样的 `Closed-Loop Research` 路径继续推进：

1. 创建 Deep Research run 的系统消息不应只提示 `run/profile/depth/source_scope`，还应补出一句入口层或当前恢复 narrative，提醒用户该 run 将围绕显式问题与显式约束进入何种研究/恢复路径。
2. 从 checkpoint 发起恢复、从 lineage 续跑或类似恢复类动作的系统消息，不应只提示“创建了恢复任务”，还应补出一句 recovery narrative，让用户立即知道本次恢复主要在补哪类 requirement、是普通恢复还是反证收束路径。
3. 写回资料池成功消息已经带上产物出口 narrative，接下来创建/恢复类消息也应复用同一套 helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在生命周期通知层与详情视图之间保持同源解释。
4. 这样从创建、恢复到产物沉淀的整条链路都能直接回答：
   - 当前新建的 run 将围绕什么显式目标开始闭环研究；
   - 当前恢复动作是在泛化恢复、定向纠偏，还是反证分支收束；
   - 用户收到系统通知时就能判断下一步应该查看 run、继续恢复，还是回放 checkpoint。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - 创建 run / checkpoint 恢复 / 生命周期消息出现 recovery target narrative；
   - backend / worker 契约保持兼容。

## 54. 本轮执行追加（四十三）（2026-07-06）
在 recovery target narrative 已经进入创建 / 恢复 / 生命周期系统消息之后，下一步优先把它继续贯通到 `research traces / loop rounds / verifier decisions` 这些逐步审计流，避免用户虽然已经能知道“当前在纠偏什么”，却仍然看不清每一轮 `Closed-Loop Research` 到底是怎样一步步从漂移走向收敛的：

1. `Research Traces` 不应只显示原始 `trace_type / trace_message / payload`，还应补出一句与当前 run 同源的 recovery narrative，让用户判断这条 trace 属于泛化恢复、定向纠偏还是反证收束。
2. `loop rounds / verifier decisions` 不应只列出状态与 reason code，还应补出围绕 recovery target 的解释，使用户能看懂某一轮 `Local Verifier / Global Verifier` 为什么继续放行、警告或要求恢复。
3. 如果某一轮已经没有显式 recovery target，也应在 narrative 中说明当前视图回退到了更泛化的闭环状态，避免用户误以为数据缺失。
4. 这一层审计流必须继续复用现有 helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在入口、详情、回放和逐步审计流之间保持同源解释。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - traces / loop rounds / verifier decisions 视图出现 recovery target narrative；
   - backend / worker 契约保持兼容。

## 55. 本轮执行追加（四十四）（2026-07-06）
在 recovery target narrative 已经进入 `research traces / loop rounds / verifier decisions` 逐步审计流之后，下一步优先把它继续推进到 `按轮次对比 / trace 到 checkpoint 的关联跳转`，避免用户虽然已经能看到每一轮都在讲什么，却仍然不能直接判断“哪一轮开始收敛、哪一轮沉淀成 checkpoint、哪一轮触发了反证分支”：

1. 审计流中的 `loop rounds / verifier decisions` 不应只是孤立卡片，还应补出相邻轮次的 recovery target delta 说明，让用户直接看到某一轮相对上一轮是在收敛、扩散、迁移还是保持稳定。
2. `Research Traces` 若 payload 中存在 `checkpoint_no / branch_id / round_no / snapshot_type` 等关联键，应显式展示出来，并尽量提供跳转入口或至少提供明确的定位提示，使用户知道这条 trace 最终沉淀到了哪个 checkpoint 或属于哪一轮循环。
3. 如果某轮审计记录没有显式 checkpoint 关联，也应在说明中明确它仍停留在运行态闭环里，尚未沉淀为 checkpoint，避免用户把“无关联字段”误解成前端缺失。
4. 这一层对比与关联说明仍必须复用现有 recovery helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在“当前态解释”和“轮次演化解释”之间口径一致。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - 审计流中出现 round-to-round recovery narrative 或 checkpoint linkage 提示；
   - backend / worker 契约保持兼容。

## 56. 本轮执行追加（四十五）（2026-07-06）
在 recovery target narrative 已经进入 `按轮次对比 / trace 到 checkpoint 的关联跳转` 之后，下一步优先把它继续压回 `checkpoint 回放页本身`，避免 checkpoint 仍然主要表现为静态快照，用户需要自己在 traces / loop rounds / verifier decisions 里来回比对，才能判断这个 checkpoint 究竟是从哪一轮闭环收敛里长出来的：

1. `checkpoint 回放` 详情区不应只展示 snapshot 本身，还应补出“来源轮次摘要”，至少说明：
   - 该 checkpoint 对应的 `round_no`；
   - 对应 trace 是否已经显式沉淀出 `checkpoint_no / snapshot_type`；
   - 对应 verifier / loop 决策在进入 checkpoint 前的 recovery target 是收敛、扩散还是迁移。
2. 如果某个 checkpoint 能在当前 run 的 `Research Traces / Loop Rounds / Verifier Decisions` 中找到来源记录，应把这些来源记录的关键摘要直接拉回 checkpoint 详情页，而不是要求用户手动跳出去查。
3. 如果当前无法找到显式来源记录，也应在 checkpoint 详情页明确提示“当前只有静态快照、缺少来源轮次锚点”，避免用户把这理解成前端漏渲染。
4. 这一层来源摘要仍必须复用现有 recovery helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在当前态审计流与 checkpoint 回放之间保持同源解释。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - checkpoint 回放页出现来源轮次摘要或缺失提示；
   - backend / worker 契约保持兼容。

## 57. 本轮执行追加（四十六）（2026-07-06）
在 checkpoint 回放页已经具备“来源轮次摘要”之后，下一步优先把这层 provenance 继续前推到 `checkpoint 列表卡`，避免用户只有点进某个 checkpoint 详情后才能知道它来自哪一轮闭环，而在列表层仍然只能看到 checkpoint 编号、verifier 状态与 recovery 标签：

1. checkpoint 列表项不应只显示 `checkpoint_no / recovery / verifier status`，还应补出一条来源轮次摘要，至少说明：
   - 该 checkpoint 是否已在当前 `Research Traces` 中找到显式来源 trace；
   - 若能找到，对应的是哪个 `round_no / snapshot_type`；
   - 若找不到，应明确提示当前只有静态 checkpoint 卡片。
2. checkpoint 列表项还应尽量复用现有 `轮次演化说明` 或 recovery target delta helper，使用户在不打开详情时就能预判该 checkpoint 更像“已收敛的定向纠偏结果”还是“仍在泛化恢复中的中间快照”。
3. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的解释不只在详情页成立，也能前移到列表浏览阶段，降低用户在多个 checkpoint 间切换的成本。
4. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - checkpoint 列表卡出现 provenance 摘要或缺失提示；
   - backend / worker 契约保持兼容。

## 58. 本轮执行追加（四十七）（2026-07-06）
在 checkpoint provenance 已经前推到列表卡层之后，下一步优先把它继续贯通到 `run 历史卡片 / 关键转折时间线 milestone`，避免用户在更高层浏览历史演化时，仍然只能看到 run 状态与阶段标签，而看不到“这一轮 run 是否已经沉淀出关键 checkpoint、其来源闭环是否已经收敛”：

1. `Research Run 历史卡片` 不应只显示 run 级别的 recovery narrative，还应补出 checkpoint 命中提示，至少说明：
   - 该 run 当前是否已有可回放 checkpoint；
   - 若已有，优先提示最近一个或关键 checkpoint 的 `checkpoint_no / snapshot_type`；
   - 若能从 provenance 中判断，还应指出该 checkpoint 是否来自已收敛的闭环轮次。
2. `关键转折时间线` milestone 不应只展示阶段标签与 signal chips，还应尽量补出该 milestone 所属 run 是否已经沉淀出关键 checkpoint，帮助用户把“阶段变化”和“真正落盘的闭环快照”对应起来。
3. 这层高阶视图仍必须复用现有 provenance / recovery helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在列表、时间线、checkpoint 回放与审计流之间保持同源解释。
4. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - run 历史卡片 / milestone 出现 checkpoint provenance 提示；
   - backend / worker 契约保持兼容。

## 59. 本轮执行追加（四十八）（2026-07-06）
在 checkpoint provenance 已经进入 run 历史卡片与 milestone 之后，下一步优先把它继续推进到 `分支视角`，避免用户虽然已经知道“某个 run / checkpoint 是否存在”，却仍然看不清这些 checkpoint 到底是从主分支沉淀出来的，还是已经落在 `反证分支` 上：

1. `Counterfactual Branch Diff` 不应只展示 branch count、target delta 与 conflicted rows，还应补出当前分支相关 checkpoint 的 provenance 提示，说明：
   - 当前 active branch 是否已经沉淀出 checkpoint；
   - 这些 checkpoint 更偏主分支还是反证分支；
   - 分支沉淀对应的 recovery target 是在收敛、扩散还是迁移。
2. `Run History Drift` 不应只比较 run 级别字段，还应补出 baseline / current 的 checkpoint branch provenance，对比这两轮 run 沉淀的是主分支 checkpoint 还是反证分支 checkpoint。
3. 这一层分支视角应继续复用当前 `checkpoint.active_branch_id / run.active_branch_id / counterfactual branch ids / provenance helper`，保持 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在 branch 级解释中的同源性。
4. 这样用户就能直接判断：
   - 当前反证分支是否已经不只是运行态，而是沉淀出可回放 checkpoint；
   - 某次 run drift 是沿主分支继续收敛，还是切入了反证分支并在那里落盘；
   - 当前 counterfactual diff 对应的是临时 branch activity，还是已形成具有 checkpoint 价值的闭环节点。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - Counterfactual Branch Diff / Run History Drift 出现 checkpoint branch provenance 提示；
   - backend / worker 契约保持兼容。

## 60. 本轮执行追加（四十九）（2026-07-06）
在 checkpoint branch provenance 已经进入 `Counterfactual Branch Diff / Run History Drift` 之后，下一步优先把它继续贯通到 `Checkpoint To Checkpoint Diff / Resume Recovery Diff`，避免用户虽然已经能看懂 run 级 drift 与分支切换，却仍然无法直接判断“恢复来源 checkpoint 和当前 run 是否还在同一条分支上持续收敛”：

1. `Checkpoint To Checkpoint Diff` 不应只比较 `rows / recovery targets / verifier / evidence`，还应补出 checkpoint branch provenance，对比 baseline snapshot 与 current snapshot 是继续在主分支沉淀，还是已经切换到反证分支沉淀。
2. `Resume Recovery Diff` 不应只显示 source checkpoint 到 current run 的 target delta，还应明确提示恢复来源 checkpoint 的分支沉淀状态，以及 current run 是否沿同一分支继续收敛、还是已经切换分支。
3. 这层 diff 视图必须继续复用现有 `branchLaneLabel / buildBranchCheckpointNarrative / buildBranchCheckpointDeltaNarrative` 等 helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在 run diff、checkpoint diff、resume diff 间采用同源解释。
4. 这样用户就能直接判断：
   - 某个 checkpoint 对比另一个 checkpoint，是主分支上的连续收敛，还是已经切到反证分支；
   - 某次 resume 后的 run 是否仍沿来源 checkpoint 的分支继续推进；
   - 当前 diff 描述的是 branch drift 还是 branch-consistent recovery。
5. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - Checkpoint To Checkpoint Diff / Resume Recovery Diff 出现 branch provenance 提示；
   - backend / worker 契约保持兼容。

## 61. 本轮执行追加（五十）（2026-07-06）
在 branch provenance 已经进入 `Checkpoint To Checkpoint Diff / Resume Recovery Diff` 之后，下一步优先把它继续下沉到 `Checkpoint Provenance` 区块内部的来源卡片本身，避免当前虽然已经能看出 checkpoint 所属分支，但仍然无法直接判断“究竟是哪条来源 trace / loop round / verifier decision 在这条分支上沉淀成了 checkpoint”：

1. `Checkpoint Provenance` 中的来源 `trace / loop round / verifier decision` 卡片不应只展示各自的原始摘要与 recovery narrative，还应直接补出 branch-level provenance，说明：
   - 该来源节点属于主分支还是反证分支；
   - 它是否与当前 checkpoint 的 active branch 一致；
   - 它是“该分支上的沉淀节点”还是“仅作为运行态中间节点被引用”。
2. 如果来源节点没有显式 branch id，也应明确提示当前只能回退到 checkpoint 级分支判断，避免用户把缺字段误解成前端漏展示。
3. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在 checkpoint provenance 视图里就不只是在回答“checkpoint 来自哪一轮”，还能继续回答“它是在什么分支语境下从哪一个审计节点长出来的”。
4. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - Checkpoint Provenance 来源卡出现 branch-level provenance 说明；
   - backend / worker 契约保持兼容。

## 62. 本轮执行追加（五十一）（2026-07-06）
在 `Checkpoint Provenance` 来源卡已经具备 branch-level provenance 说明之后，下一步优先把它继续扩展为 `可导航的审计入口`，避免用户虽然已经读懂“这个 checkpoint 来自哪条分支上的哪个来源节点”，却仍然需要手动滚动或搜索整页，才能在 `Research Traces / Loop Rounds / Verifier Decisions / Counterfactual Branches` 中重新找到那个节点：

1. provenance 来源卡不应只停留在解释层，还应尽量提供最小导航能力，例如：
   - 从来源 trace 直接回到对应 `Research Traces` 条目；
   - 从来源 loop round 直接定位到对应 `Loop Rounds` 条目；
   - 从来源 verifier decision 直接定位到对应 `Verifier Decisions` 条目；
   - 必要时提示关联 `branch_id / round_no / checkpoint_no`，帮助用户快速完成人工核对。
2. 如果当前前端结构不足以做真正跳转，也应至少提供清晰的“定位提示 + 高亮目标”机制，使审计链从“可读”升级为“可导航”。
3. 这一层导航能力仍必须复用现有 provenance / recovery helper，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在解释与导航两层采用同源定位锚点。
4. 本轮测试至少覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - provenance 来源卡出现审计定位入口或高亮提示；
   - backend / worker 契约保持兼容。

## 63. 本轮执行追加（五十二）（2026-07-06）
针对 `docs/深度研究智能体编排升级设计.md` 中 `4.1 任务进入与研究启动` 的产品边界，再做一次收敛修正，避免文档仍然残留“从上下文进入研究”的阅读歧义：

1. `Deep Research` 必须明确为工作台中的独立功能，而不是 Ask / Note / Wiki / 页面态上的复杂问题升级模式。
2. 研究任务的唯一来源是 `用户显式输入`；即使功能入口挂在工作台里，也不继承当前页面、当前资料选择、当前对话或当前工作区状态作为研究输入。
3. 若用户需要带入资料，也只能通过创建任务时显式粘贴、上传、指定或填写引用范围的方式进入，不能把“工作台已有上下文”误写成研究启动条件。
4. 工作台在该模型中的职责应统一收敛为：承载 Deep Research 的入口、运行、历史与产物回流；研究完成后的报告或确认后的研究资料，才可以再融入工作台资料池。
5. 这次修正属于产品入口边界澄清，不改变 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的执行闭环设计，但会收紧入口层设计叙述，确保后续实现不把上下文绑定误做成任务来源。

## 64. 本轮执行追加（五十三）（2026-07-06）
在 `Checkpoint Provenance` 来源卡片已经具备 recovery narrative 与 branch-level provenance 说明之后，下一步优先把它继续推进为 `可定位的审计导航入口`，让 `Closed-Loop Research` 不只是能读懂，还能顺着证据链快速回到对应审计节点：

1. provenance 来源卡片不应停留在“解释 checkpoint 从哪里来”，还应尽量提供最小定位能力，把来源 `trace / loop round / verifier decision` 直接映射回 `Research Traces / Loop Rounds / Verifier Decisions` 中的对应卡片。
2. 如果当前前端结构不足以做复杂跳转，也应至少支持 `定位提示 + 目标高亮 + 关联键展示`，例如基于 `trace_id / round_no / verifier index / branch_id / checkpoint_no` 给出明确锚点，避免用户重新手动搜索整页。
3. 这层导航能力仍需复用当前 `recovery / provenance / branch narrative helpers`，保持 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在解释层和导航层使用同源语义，而不是新造一套脱节的 UI 文案。
4. 实现后的审计流需要帮助用户直接回答：
   - 某个 checkpoint 的来源 trace 在哪一轮闭环里开始收敛；
   - 某次 verifier decision 是主分支推进还是反证分支纠偏；
   - 当前 provenance 卡片指向的是可沉淀 checkpoint 的形成节点，还是仅被引用的运行态节点。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - provenance 来源卡片出现审计定位入口或高亮提示；
   - 被定位的 `Research Traces / Loop Rounds / Verifier Decisions` 卡片出现明确高亮反馈。

## 65. 本轮执行追加（五十四）（2026-07-06）
在 provenance 审计导航已经可用之后，下一步优先把 `研究产物出口 -> 工作台资料池` 的写回凭证也做成可持续查看的闭环对象，避免 `save-report-as-source` 仍然只在动作当下给出一次性提示，刷新后就失去“这份报告到底落到了哪条资料、当前索引状态如何”的可验证证据：

1. `Deep Research` 的最终报告写回，不应只表现为一次按钮动作或一条 system message；当前 run detail 应尽量显式暴露已绑定的 `report source` 摘要，至少包括 `source_id / title / source_type / status / parse_status / index_status / generated_by / generated_ref_id`。
2. 前端研究页需要补一个稳定的 `Writeback Receipt / Report Source Mapping` 区块，让用户在重新打开 run、刷新页面或继续恢复研究时，仍能确认：
   - 当前 final report 是否已经写回资料池；
   - 写回对象是否仍然保持 `generated_by = research_agent` 与 `generated_ref_id = research_run_id`；
   - 当前资料池里的这条研究产物是否已进入可检索状态。
3. 这层写回凭证说明也应继续复用当前产物出口 narrative，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在“研究报告形成路径”和“资料池落盘结果”之间保持同源解释，而不是把写回动作变成与闭环状态脱节的孤立操作。
4. 如果当前 run 尚未写回报告，界面也应显式说明“尚未形成资料池映射”，避免用户把“没有显示 receipt”误解为前端漏渲染。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - research run detail 刷新后仍能展示稳定的 report source/writeback receipt 信息。

## 66. 本轮执行追加（五十五）（2026-07-06）
在 `Writeback Receipt / Report Source Mapping` 已经能稳定证明“研究报告确实回流到了资料池”之后，下一步优先把这份回流产物继续接回 `显式 source scope`，避免 Deep Research 的最终报告虽然已经成为工作台资料，却仍然需要用户自己在资料池和研究入口之间手动重新对齐：

1. `Writeback Receipt` 不应只负责证明写回成功，还应尽量提供最小复用入口，让当前 research-generated source 可以一键纳入下一轮研究的显式 `source scope`。
2. 前端研究页至少需要补以下闭环反馈：
   - receipt 能显示当前研究报告是否已经纳入显式 `source scope`；
   - 用户可从 receipt 直接执行“加入当前 source scope”或“移出当前 source scope”；
   - 被回流的 research-generated source 在 `显式资料范围` 区域里应出现明确高亮或标记，避免它在资料池里与普通上传文件完全混在一起。
3. 这样用户就能直接验证：
   - 这份 Deep Research 报告已经落盘为 source；
   - 这条 source 是否已被显式纳入下一轮研究输入；
   - 当前 `Closed-Loop Research` 的产物，是否真正成为后续研究可控复用的一部分。
4. 这层“回流再复用”说明仍需复用现有产物出口 narrative，保证 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在“研究报告形成”“资料池写回”“下一轮显式输入”三层之间保持同源解释。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - `Writeback Receipt` 出现 source scope 纳入状态与操作入口；
   - `显式资料范围` 区域对当前 research-generated source 出现明确高亮或闭环标记。

## 67. 本轮执行追加（五十六）（2026-07-06）
在 writeback receipt 已经能把 research-generated source 纳入 `显式 source scope` 之后，下一步优先把这种“研究产物来源身份”继续贯通到 `工作台资料池列表 / source scope 列表本身`，避免用户一离开当前 run detail，就又无法稳定分辨哪些 source 是普通上传资料，哪些 source 是由 `Research Harness` 产出的回流研究报告：

1. `/api/v2/workspaces/{workspaceId}/sources` 返回的 source 摘要，不应只包含 `title / source_type / status / parse_status / index_status`，还应尽量带出 `generated_by / generated_ref_id`，这样前端才能在资料池视图稳定识别 research-generated source。
2. 前端至少需要在两个位置补上显式来源标记：
   - `显式资料范围` 中的 source button/list，明确标识当前条目是否来自 `research_agent`；
   - 工作台主资料列表中，明确标识“研究报告回流 source / 对应 research_run_id”，避免它和普通上传文件完全同质化。
3. 这样用户就能直接验证：
   - 当前 source 是用户上传资料，还是某次 `Closed-Loop Research` 沉淀出的研究报告；
   - 这条回流资料具体对应哪个 `research_run_id`；
   - 后续检索、续跑或再次研究时，自己引用的是原始资料，还是之前已经合成过的研究产物。
4. 这层来源身份解释应继续服务于 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的闭环语义，让“研究产物回流为 source”不只是数据事实，也成为用户可读、可判断的工作台对象身份。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - source 列表与显式 source scope 都能稳定展示 research-generated source 标记。

## 68. 本轮执行追加（五十七）（2026-07-06）
针对 `docs/深度研究智能体编排升级设计.md` 中 `4.1 任务进入与研究启动` 的产品口径，再做一次收束修正，重点解决“工作台绑定”容易被误读成“上下文驱动入口”的问题：

1. `Deep Research` 必须继续明确为工作台中的独立功能，不能写成 Ask、Note、Wiki、已选资料或页面态上的“升级模式”。
2. 研究任务的唯一启动来源仍然是 `用户显式输入`；研究问题、范围、约束、参考资料与写回策略都必须在创建任务时由用户显式给出，而不是从当前上下文继承。
3. 工作台与 `Deep Research` 的关系应统一收敛为“承载入口、运行面板、历史管理与产物回流”，其中“产物可融入工作台资料池”属于研究完成后的输出回流，而不是研究启动时的输入继承。
4. 文档表述上应避免继续使用任何会让人联想到“基于上下文进入研究”“从已选资料直接发起研究”的描述；即使用户要带资料进入研究，也只能通过创建任务时显式附带。
5. 本轮修正主要落在设计文档口径层，目的是为后续 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的实现继续锁定正确产品边界，避免入口层设计回退。

## 69. 本轮执行追加（五十八）（2026-07-06）
在 `Writeback Receipt`、`source scope` 与资料池列表都已经能识别 research-generated source 之后，下一步优先把这种“研究产物来源身份”继续贯通到 `后续检索 / 回答引用` 链路，避免回流研究报告一旦再次参与问答，就又在 `chat.citation` 中退化成与普通资料完全同质的匿名标题：

1. `chat.citation` 不应只输出 `title | quote_text` 这种扁平文本；当引用来自 `generated_by = research_agent` 的 source 时，至少应继续带出可读的来源身份，例如 `Research Report(<research_run_id>)`。
2. backend 侧 `citationsForMessage(...)` 需要从 source 维度补齐 `generated_by / generated_ref_id`，否则聊天引用无法判断当前证据是原始上传资料，还是某次 `Closed-Loop Research` 产出的回流报告。
3. 前端不一定要先引入新的 citation JSON 结构，但至少要保证现有消息卡片、`来源引用` 区块与 SSE 文本展示层可以稳定看到研究来源标记，让用户在阅读答案时直接识别“当前证据是否来自研究产物复用”。
4. 这样用户就能继续验证：
   - 本轮回答引用的是原始资料，还是某次 `Deep Research` 沉淀出的研究报告；
   - 若是研究报告回流 source，它具体对应哪个 `research_run_id`；
   - `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 产出的资料，是否真的进入了后续问答检索与引用闭环。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - 研究报告回流 source 再次被引用时，`chat.citation` 可见 `Research Report(` 来源标记。

## 70. 本轮执行追加（五十九）（2026-07-06）
在 `chat.citation` 已经能识别 research-generated source 之后，下一步优先把同样的来源身份继续前推到 `证据选择 / 资料定位 / 深读窗口摘要` 这些正文前置解释层，避免用户仍然要等到引用区块出现后，才能知道当前回答到底是在复用原始资料还是某次 `Closed-Loop Research` 沉淀出的研究报告：

1. QA 链路中的 `来源覆盖 / 关键依据` 不应只显示 `title / source_type / score`，当命中的 source 来自 `generated_by = research_agent` 时，也应直接显示 `Research Report(<research_run_id>)`。
2. Note 链路中的 `候选资料 / 验证摘要 / 深读窗口 / 摘录证据` 等摘要块，同样不应把 research-generated source 重新混成普通资料标题；至少要在标题层稳定保留来源身份。
3. 这层改动的重点不是新增复杂交互，而是让 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 产出的资料，一旦进入后续检索链，就能在“证据选择说明”里被直接读出来，而不是只在 citation 尾部才被看见。
4. 这样用户就能继续验证：
   - 当前回答在正文层引用的是原始资料还是研究回流报告；
   - 某个 `Deep Research` 产物是否真的进入了下一轮检索和证据选择；
   - 闭环产物的复用路径是否在“检索解释层”和“引用层”保持同源说明。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - 研究报告回流 source 进入 QA 回答后，`chat.delta` 中的证据摘要也能看到 `Research Report(` 来源标记。

## 71. 本轮执行追加（六十）（2026-07-06）
在 QA 与 Note 的主证据摘要已经能识别 research-generated source 之后，下一步优先把同样的来源身份继续补齐到 `Wiki 来源回链 / Note relation 扩展摘要`，避免聊天三条链路里仍有局部解释面把研究回流资料重新退化成普通标题：

1. Wiki 链路中的 `来源回链` 不应只显示 `citation.title + quote_text`；当来源 citation 指向 `generated_by = research_agent` 的 source 时，也应直接显示 `Research Report(<research_run_id>)`。
2. Note 链路中的 `related_entries` 关系扩展摘要，不应把研究回流资料写成无来源身份的普通 `source title`；至少要在摘要标题层稳定保留 provenance。
3. 这样 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 形成的研究产物，在 QA、Note、Wiki 三条解释链路中就能维持统一的“来源可读性”，而不是只在部分卡片里可见。
4. 这样用户就能继续验证：
   - 某个研究回流 source 在 Wiki 来源回链里是否仍被明确识别；
   - Note 的关系扩展候选里是否正在复用先前 Deep Research 产物；
   - provenance 说明是否在主证据、关系扩展和来源回链三层保持同源口径。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - 研究报告回流 source 进入 Note 回答后，`chat.delta` 中的候选资料或深读摘要可见 `Research Report(` 标记。

## 72. 本轮执行追加（六十一）（2026-07-06）
在 research workbench 前端已经能通过 `workspace source list` 反查 research-generated source 身份之后，下一步优先把这层 provenance 继续下沉到 `research detail / checkpoint payload / report_structure` 对象本身，避免 `Table-as-State` 仍然依赖前端额外 lookup 才能看懂来源：

1. `ResearchRunDetailResponse` 中的 `report_structure.verified_findings / conflict_and_counterfactual_review.conflicted_rows / evidence_ledger` 等对象，若包含 `source_id`，应尽量直接带出 `generated_by / generated_ref_id`。
2. `ResearchCheckpointResponse.payload` 中的 `state_ledger.rows / evidence_cards / report_structure.*` 等对象，同样不应只保留 `source_title`；checkpoint 回放对象本身应尽量是自解释的 provenance 载体。
3. 这一步的目标不是新增新的前端装饰逻辑，而是让 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的核心审计对象，在 API 返回层就具备“这条证据是不是来自上游研究报告”的最小完备语义。
4. 这样用户就能继续验证：
   - 当前 run detail 中的 verified findings 是否直接自带研究来源身份；
   - checkpoint 原始 payload 回放时是否仍能识别 research-generated evidence；
   - provenance 是否已经从“UI 显示能力”升级成“闭环对象内建语义”。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - downstream research run detail 中 `report_structure` 与 `closed_loop_state` 至少一层对象直接返回 `generated_by / generated_ref_id`。

## 73. 本轮执行追加（六十二）（2026-07-06）
在 `report_structure / closed_loop_state / checkpoint payload` 已经开始直接携带 research-source provenance 之后，下一步优先把同样的自解释能力补齐到 `source_scope / worker input` 这类研究专用输入对象，避免研究任务自己的显式资料范围快照反而退回成“只剩标题”的普通 source 摘要：

1. `WorkerSourceScopeItemResponse` 不应只包含 `source_id / title / summary / sample_text`；当 source 来自 `research_agent` 时，也应直接返回 `generated_by / generated_ref_id`。
2. `ResearchRunDetailResponse.source_scope`、`ResearchWorkerInputResponse.source_scope`，以及使用同一对象的 artifact worker input，都应自动继承这层 provenance，而不是要求前端额外去查 workspace source list。
3. 这一步会把 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的“输入对象”和“运行对象”都纳入同一套 provenance 语义，避免只有运行中证据对象可读，而显式输入快照本身不可读。
4. 这样用户就能继续验证：
   - 当前研究任务显式纳入的 source 是否本身就是上游研究报告；
   - worker input / resume input 是否直接携带 provenance，而不是在执行前丢失来源身份；
   - 研究输入、运行证据、输出回流三层是否已经形成同一套闭环来源语义。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - research worker input 或 run detail 的 `source_scope` 直接返回 `generated_by / generated_ref_id`。

## 74. 本轮执行追加（六十三）（2026-07-06）
在 `source_scope / worker input` 已经开始变成自解释 provenance 对象之后，下一步优先把同样的能力继续补齐到 `Research Traces` 这类最原始的闭环审计流，避免 `Research Harness` 的一手 trace 回放仍然要依赖外围对象或前端补查才能看懂来源：

1. `ResearchTraceResponse.payload` 中若包含 `source_id`，应尽量直接补齐 `generated_by / generated_ref_id`，与 `report_structure / closed_loop_state / checkpoint payload` 保持同级自解释。
2. 当前 run detail 的 `Research Traces` 卡片，不应只显示 `trace_type / trace_message / recovery narrative`；若当前 trace payload 可识别研究来源，也应给出最小可读提示，例如 `source=... · Research Report(<runId>)`。
3. 这一步的重点不是给每类 trace 新做复杂模板，而是让 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的原始审计链，从 trace 层开始就具备稳定 provenance。
4. 这样用户就能继续验证：
   - 某条 trace 是否直接涉及上游研究报告来源；
   - provenance 是否已经覆盖到最原始的闭环审计流，而不只是汇总对象；
   - trace、checkpoint payload、report_structure 三层是否保持同源解释。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - downstream research run detail 中至少一条 `trace.payload` 直接返回 `generated_by / generated_ref_id`。

## 75. 本轮执行追加（六十四）（2026-07-06）
针对 `docs/深度研究智能体编排升级设计.md` 中 `4.1 任务进入与研究启动` 的入口口径，再做一次收束修正，重点解决“虽然已经写成独立功能，但启动字段仍可能被读成上下文驱动”的残留歧义：

1. `Deep Research` 的启动条件应继续收紧为“用户显式输入研究问题与研究参数”，而不是“用户处在某种上下文里，所以系统可以替他成立一个研究任务”。
2. `工作台绑定` 只表示 `Deep Research` 的入口、运行、历史与产物回流放在工作台中，不表示 Ask、Note、Wiki、页面态或已选资料会天然成为研究启动上下文。
3. `4.1` 中的启动输入字段应改写为统一的“用户输入字段”口径，例如研究问题、目标、交付格式、范围、限制、关键词、排除项、时间范围、预算、写回策略，以及用户主动粘贴 / 上传 / 指定的参考资料；避免把它们写成独立的“上下文入口类别”。
4. 即便用户希望把工作台里的资料带入研究，也必须表现为创建任务时的显式输入动作，而不是从当前上下文、当前页面或当前已选态自动继承。
5. 本轮变更先落在设计文档边界收敛层，目的仍然是为后续 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的实现锁定正确入口模型，避免入口设计再次滑回“上下文升级模式”。

## 76. 本轮执行追加（六十五）（2026-07-06）
在 `trace.payload` 已经开始自带 research-source provenance 之后，下一步优先把同样的自解释能力继续补齐到 `checkpoint summary / resume checkpoint payload`，避免 `Closed-Loop Research` 的恢复入口和 checkpoint 列表摘要仍然停留在“只有计数、没有来源对象”的半可审计状态：

1. `loadResumeCheckpointPayload(...)` 返回给 worker 的 `resume_checkpoint.payload` 不应只是原始 checkpoint JSON；若其中存在 `source_id`，也应在恢复输入阶段直接补齐 `source_title / generated_by / generated_ref_id`，避免恢复任务一开始就丢失 provenance 语义。
2. `buildCheckpointStateLedgerSummary(...)` 不应只返回 `row_count / verified_row_count / conflicted_row_count` 这类聚合计数；至少还应补一组最小的 provenance-bearing sample，例如 `verified_row_samples / conflicted_row_samples / evidence_card_samples / read_window_samples`，让 checkpoint 列表层就能看出当前 `Table-as-State` 主要围绕哪些来源沉淀。
3. `loadPersistedCheckpoints(...)` 读取 summary 时也应继续做 provenance enrich，这样新老 checkpoint 只要 summary 内含 `source_id`，前端就能稳定读出 `Research Report(<runId>)` 这类研究来源身份。
4. 前端 `Checkpoint 回放 / Checkpoint Provenance` 需要补一个最小摘要展示，把这些 sample 直接映射成可读来源标签，帮助用户在不展开整份 payload 的情况下审计 `Research Harness / Dual Verifier / 反证分支` 的当前沉淀焦点。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `resume_checkpoint.payload` 在恢复输入里保留 provenance；
   - `closed_loop_state.checkpoints[*].summary.state_ledger` 至少返回一组可读 sample。

## 77. 本轮执行追加（六十六）（2026-07-06）
在 `checkpoint summary / resume checkpoint payload` 已经开始带出最小来源样本之后，下一步优先把同样的“来源可读性”继续推进到 `Dual Verifier / Counterfactual Branch` 这条纠偏链，避免 `Research Harness` 虽然已经暴露 verifier decision 和反证分支，但用户仍然看不出这些决策到底围绕哪些证据来源发生：

1. `loadPersistedVerifierDecisions(...)` 返回的 verifier decision 不应只包含 `decision_scope / decision_type / reason_code / evidence_ids`；还应尽量基于 `evidence_ids -> source_evidence` 反解出最小 `source_samples`，至少带出 `source_id / source_title / generated_by / generated_ref_id / evidence_id`。
2. 这样 `Dual Verifier` 的局部验证与全局验证卡片就能直接回答：这次 PASS / REPAIR / READY_TO_WRITE 是基于哪些来源做出的，而不是只剩抽象 reason code。
3. 前端 `Verifier Decisions` 需要补一个最小来源说明；当 decision 已自带 `source_samples` 时，直接渲染为 `Research Report(<runId>)` 等可读标签，从而把 verifier judgement 和 `Table-as-State` / `source_evidence` 重新绑回同一条 provenance 链。
4. 前端 `Counterfactual Branch Diff` 也应利用已有 `target_evidence_ids` 与 `source_evidence` 的映射，补出“这条反证分支主要在围绕哪些来源重查”的最小提示，强化 `反证分支` 的路径纠偏可解释性。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `closed_loop_state.verifier_decisions[*].source_samples` 至少在一条决策上返回可读来源；
   - 下游 research-generated source 进入 verifier decision 时仍保留 `generated_by / generated_ref_id`。

## 78. 本轮执行追加（六十七）（2026-07-06）
在 `Dual Verifier / Counterfactual Branch` 已经开始自带来源样本之后，下一步优先把同样的“来源驱动纠偏说明”继续推进到 `Loop Rounds`，避免 `Closed-Loop Research` 的轮次演化仍然只展示计数与 decision，而无法直接回答“这一轮到底围绕哪些查询/来源在收敛”：

1. `loop_rounds` 不应只停留在 `round_no / search_hit_count / read_window_count / evidence_card_count / decision` 这类统计对象；后端应尽量基于 `evidence_ids / target_evidence_ids / query / search_queries` 与 `source_evidence` 的映射，为每一轮补出最小 `source_samples`。
2. 这样 `Research Harness` 的每一轮推进就不再只是抽象“进入 round 2 / 进入 round 3”，而是能直接解释：当前 round 主要围绕哪些来源继续深读、验证或反证。
3. 前端 `Loop Rounds` 卡片应补一个最小来源说明；当 round 已自带 `source_samples` 时，直接渲染出 `Research Report(<runId>)` 等来源标签，让 `Table-as-State` 的演化轨迹和来源闭环保持同源解释。
4. 下游 research-generated source 若参与某轮 closed-loop 推进，也应在 `loop_rounds[*].source_samples` 中保留 `generated_by / generated_ref_id`，避免轮次层重新退化成匿名 query 说明。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `closed_loop_state.loop_rounds[*].source_samples` 至少在一条轮次上返回可读来源；
   - 下游 research-generated source 进入 loop round 时仍保留 `generated_by / generated_ref_id`。

## 79. 本轮执行追加（六十八）（2026-07-06）
在 `loop_rounds[*].source_samples` 已经开始让轮次层具备来源可读性之后，下一步优先把这层能力继续接到 `Resume Recovery Diff / Recovery Target Drift`，避免“当前 round 围绕哪些来源在跑”虽然已经可见，但恢复链和漂移链里仍然只能看到 targets / queries / sources 的扁平字符串：

1. `getCheckpoint()` 与 `loadResumeCheckpointPayload(...)` 返回的 `payload.loop_rounds` 不应只保留 worker 原始 round 记录；也应像运行态 `closed_loop_state.loop_rounds` 一样补齐最小 `source_samples`，保证 checkpoint 回放与 resume 输入能直接携带 round-level source focus。
2. 前端 `Resume Recovery Diff` 应在 `Recovery Target Delta` 卡片中补一个 `source focus` 漂移说明，把恢复来源 checkpoint 的最新 round 来源样本与当前 run 的最新 round 来源样本直接对比出来。
3. 前端 `Run History Drift` 里的 `Recovery Target Drift` 若当前 baseline 实际来自 resume source run，也应尽量复用同一套 round-level source sample，对齐“恢复目标在变化什么”和“闭环实际围绕哪些来源继续推进”。
4. 这一步的重点仍然是把 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的恢复链叙事统一成同一条来源闭环，而不是只在单个运行态视图里零散显示 provenance。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `checkpoint payload.loop_rounds[*].source_samples` 在回放接口里可见；
   - `resume_checkpoint.payload.loop_rounds[*].source_samples` 在恢复 worker 输入里可见。

## 80. 本轮执行追加（六十九）（2026-07-06）
在 `Resume Recovery Diff / Recovery Target Drift` 已经开始带出 `source focus` 之后，下一步优先把同样的 round-level 来源对比继续推进到 `Checkpoint To Current Diff / Checkpoint To Checkpoint Diff`，避免 checkpoint 审计链仍然只能看到 recovery target 的字段差异，而看不到来源焦点到底怎样收敛或漂移：

1. 前端 `Checkpoint To Current Diff` 的 `Recovery Target Delta` 不应只展示 `targets / columns / queries / sources` 的扁平字符串；若 snapshot checkpoint 的最新 `loop_rounds[*].source_samples` 可用，也应直接对比当前 run 的最新 round 来源样本。
2. 前端 `Checkpoint To Checkpoint Diff` 同样应补一个 `source focus` 漂移说明，把 baseline checkpoint 与当前 snapshot checkpoint 的最新 round 来源样本直接对齐。
3. 这样 checkpoint 审计链就能与运行态、恢复链保持同一套 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 解释口径：不仅知道 recovery target 在变什么，也知道闭环实际围绕哪些来源继续推进。
4. 这一步优先复用现有 `payload.loop_rounds[*].source_samples`，不新增新的后端语义层，只补 checkpoint diff 视图的解释闭环。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - checkpoint diff 视图编译通过并能读取 `payload.loop_rounds[*].source_samples`。

## 81. 本轮执行追加（七十）（2026-07-06）
在 `Checkpoint To Current Diff / Checkpoint To Checkpoint Diff` 已经开始带出 `source focus` 之后，下一步优先把这层来源焦点与 `Verified Findings Progress / New Findings` 的结果对上，避免用户虽然已经能看出闭环围绕哪些来源继续推进，却仍然要自己推断“这些来源变化最后对应沉淀出了哪些新增 finding”：

1. 前端 `Verified Findings Progress` 不应只展示新增/保留数量；若 `source focus` 与新增 findings 都可用，应补一条最小的“focus -> finding sources”说明。
2. 前端 `New Verified Findings Since Snapshot` 与 `New Findings Since Baseline Checkpoint` 上方，优先补一条简短来源总结，直接说明新增 finding 主要来自哪些来源标签。
3. 这一步的重点是把 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的“路径焦点变化”与“验证产物沉淀结果”接成因果闭环，而不是再新增新的后端语义层。
4. 本轮优先复用已有 `source focus` 与 `verified_findings[*].source_id/source_title/generated_by/generated_ref_id`，不新增新的 API 字段。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - findings 进度视图编译通过并能读取来源标签。

## 82. 本轮执行追加（七十一）（2026-07-06）
针对 `docs/深度研究智能体编排升级设计.md` 中 `4.1 任务进入与研究启动` 的产品口径，再做一次边界收紧，重点解决“工作台绑定”仍可能被误读成“研究任务与上下文绑定”的问题：

1. `Deep Research` 需要继续明确为工作台中的独立功能，但这种绑定只表示入口、运行、历史与产物回流挂在工作台中，不表示研究任务从工作台上下文产生。
2. 研究任务的其余启动条件应统一收敛为 `用户输入`，与 Ask、Note、Wiki、页面态、已选资料态或工作区状态无关。
3. 若用户要带入链接、摘录、文件或已有资料，也只能表现为创建任务时的显式输入动作，而不能表述成“基于某个上下文发起研究”。
4. 研究完成后的报告与资料可以融入工作台资料池，但这属于输出回流，不属于任务启动阶段的输入继承。
5. 本轮先落在设计文档收口上，为后续 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的入口契约继续锁死正确边界，避免实现再次滑回“上下文升级模式”。

## 83. 本轮执行追加（七十二）（2026-07-06）
在 `Verified Findings Progress / New Findings` 已经能把 `source focus` 与“新增 finding”连接起来之后，下一步优先把同一条闭环解释继续推进到“被修掉的冲突”和“被替换/消失的 finding”，避免工作台仍然只能解释 `Research Harness` 新沉淀出了什么，却解释不清 `Dual Verifier / 反证分支` 到底修掉了什么、推翻了什么：

1. 前端 `Checkpoint To Current Diff / Checkpoint To Checkpoint Diff / Resume Recovery Diff` 应补出 `resolved conflicts / dropped findings` 级别的结果变化摘要，而不只显示新增与保留的 verified findings。
2. 若 baseline 与 current 都已有 `source focus`、`verified_findings`、`conflicted_rows`，视图应尽量补一条最小的 `focus -> resolved conflicts` 或 `focus -> dropped finding sources` 说明，把路径焦点变化与验证纠偏结果对上。
3. `Counterfactual Branch Diff` 也应同步显示“本轮反证纠偏后新增了多少 verified findings、消解了多少 conflict、仍保留哪些冲突来源”，让 `反证分支` 的收益不仅体现在分支数量和 target 变化上。
4. 本轮优先复用已有 `verified_findings / conflicted_rows / source_samples / source_evidence` 与来源标签 helper，不新增新的后端 API 字段，重点放在前端闭环叙事补齐。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - 新增的 diff / counterfactual 视图编译通过并能显示 `resolved conflicts / dropped findings` 的来源摘要。

## 84. 本轮执行追加（七十三）（2026-07-06）
在 diff / counterfactual 视图已经能解释 `resolved conflicts / dropped findings` 之后，下一步优先把同样的闭环解释抬回“当前 run 主正文”，避免用户一回到主阅读面，就又只能看到 `Conflict And Counterfactual Review / Loop Rounds / Verifier Decisions` 的原始状态，而看不到这次 `Dual Verifier / 反证分支` 纠偏到底带来了什么结果变化：

1. 当前 run 的 `Conflict And Counterfactual Review` 应补一块 `Closed-Loop Outcome Summary`，把可用 baseline（优先已选 checkpoint，其次 resume source checkpoint）与 current run 的 `source focus / 新增 findings / dropped findings / resolved conflicts / remaining conflicts` 放在同一个阅读面里。
2. 当前 run 的 `Loop Rounds` 与 `Verifier Decisions` 也应尽量共享这组 outcome anchor，让用户在读闭环步骤时，不必切回 diff 视图才能知道这些步骤最终产出了什么纠偏结果。
3. 这一步的重点不是新增后端契约，而是把已有 `source_samples / verified_findings / conflicted_rows / checkpoint baseline` 重新组织成当前态主叙事，让 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在主视图中也保持因果闭环。
4. 本轮优先复用现有前端 helper 与已算出的 finding/conflict diff，不新增新的 API 字段，避免实现重心偏回数据补洞。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - 当前 run 主正文中的 `Conflict And Counterfactual Review / Loop Rounds / Verifier Decisions` 能显示 outcome anchor 与来源焦点摘要。

## 85. 本轮执行追加（七十四）（2026-07-06）
在当前 run 主正文已经具备 `Closed-Loop Outcome Summary / Loop Outcome Anchor / Verifier Outcome Anchor` 之后，下一步优先把这组 outcome anchor 继续下沉到 `Loop Rounds / Verifier Decisions` 的单步卡片内部，避免用户虽然知道“当前 run 整体修掉了什么”，却仍然看不出“哪一步和哪些新增/移除/消解结果直接相关”：

1. `Loop Rounds` 的每张卡片都应尽量基于自己的 `source_samples` 对齐 `new findings / dropped findings / resolved conflicts / remaining conflicts`，给出最小的 `step outcome` 摘要。
2. `Verifier Decisions` 的每张卡片也应复用同样的 outcome 匹配逻辑，至少能让用户判断某次 verifier judgement 更接近“放出了新结论”“推翻了旧结论”还是“消解了冲突”。
3. 这一步的重点是把 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的因果关系从“全局摘要”推进到“单步审计”，而不是再扩张新的 API 契约。
4. 本轮优先复用已有 `source_samples / verified_findings diff / conflicted_rows diff / source label helper`，通过前端匹配同源来源来补局部结果叙事。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - 单步 `Loop Rounds / Verifier Decisions` 卡片编译通过并能显示 `step outcome` 级别的结果摘要。

## 86. 本轮执行追加（七十五）（2026-07-06）
在 `Loop Rounds / Verifier Decisions` 已经能显示 `step outcome` 之后，下一步优先把同样的结果因果继续补到 `Research Traces / branch decisions / counterfactual branch cards`，避免 `反证分支` 这条最关键的路径纠偏链仍然只能看到“发生了什么动作”，却看不到“这次转向最终影响了哪些 finding 或 conflict”：

1. `Research Traces` 应尽量基于 trace payload 里的 source/evidence 线索，对齐 `new findings / dropped findings / resolved conflicts / remaining conflicts`，给出最小的 `trace outcome` 摘要。
2. `Conflict And Counterfactual Review` 里的 branch decisions 不应只剩一行 decision/reason 文本，而应升级为可读卡片，并补出对应 target sources 与 `branch outcome` 摘要。
3. `Current Counterfactual Branches / New Counterfactual Branches Since Baseline` 也应复用同一套 outcome helper，让用户直接读出某个 branch 更接近“放出新结论”“推翻旧结论”还是“继续保留冲突”。
4. 本轮优先复用已有 `source_evidence / target_evidence_ids / step outcome helper / source label helper`，不新增新的后端 API 字段。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `Research Traces / branch decisions / counterfactual branch cards` 编译通过并能显示 outcome 摘要。

## 87. 本轮执行追加（七十六）（2026-07-06）
在 `Research Traces / branch decisions / counterfactual branch cards` 已经能显示 outcome 摘要之后，下一步优先把这种结果因果继续补到 `selected checkpoint` 的来源链回放里，避免用户虽然已经能看到单步和分支各自影响了什么，却仍然难以直接读出“这次 counterfactual 转向是如何沉淀为 checkpoint，再如何延续到当前 run”的连续性：

1. `Checkpoint Provenance` 应新增一块面向当前 run 的 `Replay Continuity` 摘要，把 selected checkpoint 的 branch/source focus 与 current run 的 branch/source focus 放在一起，并直接补出 `new findings / dropped findings / resolved conflicts` 的延续结果。
2. `selected checkpoint` 的 source trace / source loop round / source verifier decision 卡片也应尽量复用同一套 outcome helper，给出最小的 `checkpoint continuity outcome` 说明，而不只描述“它是什么节点”。
3. 这一步的重点是把 `反证分支 -> 单步审计 -> checkpoint 沉淀 -> 当前 run 结果` 接成一条连续回放链，让 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的纠偏轨迹可以从静态 snapshot 一直读到当前态。
4. 本轮优先复用已有 `selected checkpoint source trace/loop/verifier`、`source_samples`、`checkpoint-to-current findings/conflicts diff` 与 branch helper，不新增新的 API 字段。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `Checkpoint Provenance` 与其 source 节点卡片编译通过并能显示 continuity outcome 摘要。

## 88. 本轮执行追加（七十七）（2026-07-06）
在 selected checkpoint 的 `Replay Continuity To Current Run` 已经成立之后，下一步优先把这套 continuity 口径继续推广到 `Checkpoint 回放列表 / Run History Drift / Resume Recovery Diff`，避免当前工作台仍然出现“selected checkpoint 正文能讲连续性，但历史列表和 lineage diff 还各说各话”的断层：

1. 侧边 `Checkpoint 回放` 列表应优先采用 `summary-first continuity`，即使没有 full payload，也要尽量基于 branch、source focus、verified/conflicted drift 给出面向当前 run 的 continuity 摘要。
2. `Run History Drift` 与 `Resume Recovery Diff` 应尽量复用同一套 continuity helper，让历史 run、恢复来源与当前 run 在叙事上保持同源，而不是只显示字段 diff。
3. 这一步的重点是让 `反证分支 -> checkpoint 沉淀 -> resume lineage -> 当前 run` 采用同一条闭环解释口径，进一步收紧 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的历史视角一致性。
4. 本轮优先复用已有 `branch helper / source sample helper / current run summary snapshot / checkpoint summary counts`，避免为列表态强行引入 full payload 依赖。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `Checkpoint 回放 / Run History Drift / Resume Recovery Diff` 编译通过并能显示 continuity 摘要。

## 89. 本轮执行追加（七十八）（2026-07-06）
在 checkpoint 列表与 lineage diff 已经开始复用 continuity helper 之后，下一步优先把这套高层 continuity 口径继续推广到 `关键转折时间线 / Research Run 历史卡片 / 闭环路径摘要`，避免用户在最外层历史导航里仍然只能看到 tone、signal chip 和基础计数，而看不出每一跳相对基线是在“收敛、失稳、平移还是重排”：

1. `关键转折时间线` 上的每个 milestone 卡片应尽量补出 continuity baseline 与 continuity judgement，让“首次失稳 / 首次恢复 / 首次回稳”不仅是阶段标签，也能读出 Table-as-State 的净变化方向。
2. `Research Run 历史卡片` 也应复用同一套 summary-first continuity helper，至少直接说明该 run 相对其 baseline 是沿同一分支沉淀、切换了分支，还是在 verified/conflicted 上出现明显收敛或失稳。
3. `闭环路径摘要` 若当前有 selected run，也应尽量补一句 continuity-level 判断，把当前 run 放回完整历史路径中解释，而不只显示阶段序列。
4. 本轮优先复用已有 `chronologicalResearchRuns / extractRunSummarySnapshot / buildContinuityStateNarrative / branch helper`，不新增新的 API 字段。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `关键转折时间线 / Research Run 历史卡片 / 闭环路径摘要` 编译通过并能显示 continuity 摘要。

## 90. 本轮执行追加（七十九）（2026-07-06）
在高层历史导航已经开始复用 continuity helper 之后，下一步优先把这套 continuity 叙事继续拉回 `当前 run 顶部主摘要 / Deep Research 任务入口卡 / 空态提示`，避免用户虽然在历史区能读懂闭环收敛故事，但回到当前入口层时又只剩状态字段与按钮：

1. `Run 摘要` 应显式补出当前 run 的 continuity baseline 与 continuity judgement，让当前态主摘要也能直接回答“这轮相对基线是在收敛、失稳还是重排”。
2. 侧边 `Deep Research 任务` 卡片若当前已有 selected run，也应复用同一套 continuity helper，至少给出一句当前任务正在沿哪条 lineage/branch 推进的说明。
3. `还没有选中的 Research Run` 的空态提示若已有历史路径，也应补一句路径级 continuity 提示，避免入口空态完全脱离当前 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 语境。
4. 本轮优先复用已有 `runContinuityBaselineById / buildRunContinuityBaselineLabel / buildRunContinuityNarrative / researchTimelinePath`，不新增新的 API 字段。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `Run 摘要 / Deep Research 任务 / 空态提示` 编译通过并能显示 continuity 摘要。

## 91. 本轮执行追加（八十）（2026-07-06）
在当前 run 顶部与入口层已经开始复用 continuity helper 之后，下一步优先把这套叙事继续推广到 `创建 run / 从 checkpoint 恢复 / 报告写回资料池` 这些生命周期系统消息，避免用户真正触发动作时，反馈又退化成“已创建 / 已恢复 / 已写回”的结果提示，而看不到接下来会沿哪条闭环路径推进：

1. 创建 `Deep Research` 成功后的系统消息应补出 continuity 或入口级 narrative，至少说明本次 run 将围绕显式问题、深度档位和资料范围进入怎样的收敛路径。
2. `resume-from-checkpoint` 的系统消息应显式补出 resume lineage 与 continuity judgement，让用户在动作发生当下就知道这次恢复是沿同一分支继续收敛，还是在 counterfactual 路径上重新展开。
3. `save-report-as-source` 的系统消息也应复用 continuity 口径，明确这份产物是当前闭环在怎样的收敛状态下沉淀并回流到资料池的。
4. 本轮优先复用已有 `buildLifecycleCreationNarrative / buildLifecycleResumeNarrative / buildRunContinuityBaselineLabel / buildRunContinuityNarrative`，不新增新的 API 字段。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - 创建 / 恢复 / 写回系统消息编译通过并能显示 continuity 摘要。

## 92. 本轮执行追加（八十一）（2026-07-06）
在生命周期系统消息已经补齐 continuity 叙事之后，下一步优先把同样的闭环解释继续下沉到 `Deep Research 任务事件流 / 资料处理任务事件流`，避免侧边任务卡与运行中状态仍然停留在 `task.status: ... / task.progress: ...` 这种原始 SSE 文本层，而无法直接回答“Research Harness 现在正在做什么、当前闭环走到哪一段、是否已进入 verifier / 反证分支 / 写回出口”：

1. `researchTaskEvents` 不应继续直接展示原始 `task.status / task.progress / task.completed / task.failed` 拼接字符串，而应按 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的语义，把 `PLANNING / SEARCHING / READING / EXTRACTING / VERIFYING / WRITING / RESEARCH_REPORTED` 映射成可读的运行中叙事。
2. `Deep Research 任务` 卡片里的事件摘要应与现有 continuity / recovery narrative 保持同一口径：创建事件说明“显式用户输入已进入独立 Deep Research”，验证事件说明“当前正在围绕 Table-as-State 做 Dual Verifier 校验与必要的反证纠偏”，完成事件说明“闭环结果已经沉淀为报告/检查点并沿既有 lineage 收敛”。
3. 普通 `taskEvents` 也不应裸露 SSE 原文；至少要把 `SOURCE_PARSE / WIKI_RETRACT / ARTIFACT_JOB` 等任务翻译成面向工作台的可读事件语义，避免整个工作台只有 Deep Research 区是解释式的，其它任务区仍然停留在底层事件名。
4. 本轮优先复用现有 `parseEventStream / buildRunContinuityBaselineLabel / buildRunContinuityNarrative / buildRecoveryEventNarrative / summarizeText` 等 helper，不新增新的后端 API 字段；重点放在前端事件解释层，把“运行时正在发生什么”也纳入同一套闭环审计叙事。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `Deep Research 任务 / 资料处理任务` 卡片编译通过，并能显示 continuity-aware 的任务事件摘要而不是原始 SSE 拼接文本。

## 93. 本轮执行追加（八十二）（2026-07-06）
在任务事件流已经从原始 SSE 文本升级为可读叙事之后，下一步优先把这层进一步升级成“叙事 + 运行计量”的闭环观测面，避免 `Research Harness` 虽然能说明“正在 SEARCHING / VERIFYING / WRITING”，但仍然说不清当前 `Table-as-State` 累积了多少 row、`Dual Verifier` 正在围绕多少 evidence / branch / round 校验，导致运行时仍然缺少可验证的过程证据：

1. 后端 `task_event` 的 SSE 输出不应只返回 message 文本；对于 `TASK_PROGRESS / TASK_HEARTBEAT / TASK_FAILED` 等事件，已落库的 `payload_json` 应通过事件流暴露给前端，形成“message + metrics/payload”的运行态证据载体。
2. `researchTaskEvents` 的前端解释层应优先消费这些 payload 指标，把 `query_count / search_hits / read_windows / evidence_cards / ledger_rows / branch_count / loop_rounds / loop_decision / local_status / global_status` 组织成可读的 runtime snapshot，让用户在运行中直接审计当前 `Closed-Loop Research` 是在扩展、收敛、纠偏还是准备写出。
3. `Deep Research 任务` 卡片除了事件摘要外，还应补出最小的 `runtime snapshot`，明确当前 `Research Harness / Table-as-State / Dual Verifier / 反证分支` 的关键计量，而不是只保留阶段名与一条自然语言消息。
4. 普通 `taskEvents` 也应兼容新的结构化事件数据，但本轮重点仍然放在研究任务的闭环运行证据，不引入新的后端业务 API；优先复用现有 `task_event.payload_json`、worker progress metrics 与前端 helper。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - `Deep Research 任务` 卡片编译通过，并能显示基于事件 payload 的 runtime snapshot 与闭环计量摘要。

## 94. 本轮执行追加（八十三）（2026-07-06）
在任务事件已经开始携带 payload 与 runtime snapshot 之后，下一步优先修正 `research worker progress event` 自身的阶段真实性，避免 `PLANNING / SEARCHING / READING / EXTRACTING / VERIFYING / WRITING` 六个阶段虽然名字不同，但实际都复用同一份最终 metrics，导致运行态看上去像是在“提前知道终局”，削弱 `Closed-Loop Research` 的可审计性与状态漂移解释力：

1. `ResearchProgressEvent` 不应对所有阶段复用同一组最终统计量；每个阶段都应只暴露“到当前阶段为止已经形成”的最小闭环快照，至少保证 `SEARCHING` 主要看到 search hit 规模、`READING` 主要看到 read window 规模、`EXTRACTING` 主要看到 evidence / row 沉淀、`VERIFYING` 才开始暴露 verifier / branch / loop judgement。
2. 这一步的重点不是把 worker 变成真正流式增量执行器，而是在当前实现框架下，确保事件语义与 payload 语义一致，避免前端把“最终态指标”误解释成“阶段中态证据”。
3. `Deep Research 任务` 卡片中的 runtime snapshot 与事件叙事应据此自动获得更真实的阶段递增信息，从而更准确地说明 `Research Harness / Table-as-State / Dual Verifier / 反证分支` 是如何逐步展开的。
4. 本轮优先在 worker 事件构造层修正阶段指标，不新增新的业务 API；前端尽量复用现有结构化事件解析与 runtime snapshot helper。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - research worker progress event 的 payload 在不同 phase 间表现为阶段化递增，而不是所有阶段都暴露最终 metrics 全量快照。

## 95. 本轮执行追加（八十四）（2026-07-06）
在 progress event 已经阶段化递增之后，下一步优先把 `phase-to-phase delta` 与 `worker heartbeat` 也纳入任务事件解释层，避免用户虽然能看到每个阶段的单点快照，却仍然难以直接回答“这一阶段相对上一阶段到底新增了哪些证据/读窗/行状态”和“当前只是 worker 还活着，还是闭环真的又向前推进了一步”：

1. `Deep Research 任务` 卡片应补出相邻 progress event 之间的最小增量叙事，例如 `SEARCHING -> READING` 新增了多少 read windows，`EXTRACTING -> VERIFYING` 新增了多少 evidence / rows 并开始出现 verifier / branch judgement，让 `Closed-Loop Research` 的推进方向从“阶段名”升级成“阶段变化”。
2. `TASK_HEARTBEAT` 不应继续与 `TASK_PROGRESS` 共用同一个前端事件语义；事件流层至少要把 heartbeat 区分成独立事件类型，避免 worker liveness 被误解释成 `Table-as-State / Dual Verifier / 反证分支` 已经发生新推进。
3. 前端事件叙事层应显式区分：
   - 进度推进：闭环状态真的发生了新增 hits / windows / evidence / rows / verifier judgement；
   - 心跳保活：当前 worker 仍在运行，但未必形成新的研究状态沉淀。
4. 本轮优先复用现有结构化 SSE 事件、阶段化 metrics 与任务卡 helper，不新增业务 API；重点放在“进展增量”和“保活信号”的解释边界。
5. 本轮测试至少要覆盖：
   - backend contract test 通过；
   - frontend build 通过；
   - UI contract 通过；
   - heartbeat 事件可被独立识别；
   - `Deep Research 任务` 卡片编译通过，并能显示相对上一阶段的 delta 叙事。

## 96. 本轮执行追加（八十五）（2026-07-06）
在任务卡已经能解释单阶段快照、相邻阶段 delta 与 heartbeat 保活之后，下一步优先补齐 `phase path audit` 这一层，避免用户仍然要自己把事件列表串起来判断当前 `Closed-Loop Research` 的路径是平稳推进、跳阶段、回退，还是停在某个 phase 但 worker 还活着：

1. `Deep Research 任务` 卡片应补出最小的 `phase path` 审计摘要，明确 `PLANNING -> SEARCHING -> READING -> EXTRACTING -> VERIFYING -> WRITING` 当前已经走到哪里、路径是否保持单调推进，以及是否出现跳阶段或回退。
2. 若最近一条任务事件是 `TASK_HEARTBEAT` 且 phase 未变化，前端应显式说明“当前停驻在某个 phase，但 worker 仍在存活”，避免 heartbeat 被误判成新的闭环进展，也避免用户把无新进展误读成界面没刷新。
3. 若 progress 序列本身出现 `rank skip / regression / duplicate plateau`，任务卡应给出路径级 judgement，把 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的运行轨迹从“阶段片段”升级成“路径判断”。
4. 本轮优先复用现有结构化事件、phase rank helper、delta helper 与 task card，不新增业务 API；重点放在运行轨迹可审计性，而不是新交互。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - `Deep Research 任务` 卡片编译通过，并能显示 phase path audit 摘要。

## 97. 本轮执行追加（八十六）（2026-07-06）
在任务卡已经能给出 `phase path audit` 之后，下一步优先把它补成“活跃度与停驻态判断”，避免用户虽然知道当前路径是单调推进还是跳变，却仍然难以判断“这个 phase 是刚刚进入、已经停了一会但 worker 仍在心跳，还是界面里看到的只是历史事件而非当前活跃运行态”：

1. `Deep Research 任务` 卡片应补出 phase 进入时间、最近一次 progress 时间，以及自最近 progress 以来连续 heartbeat 的数量，让用户更直接判断当前 `Closed-Loop Research` 是新近推进还是停驻保活。
2. `phase path audit` 应把“路径是否正常”与“当前是否活跃”合并成一句可读 judgement，例如：
   - 当前路径保持单调推进，最近刚进入 `VERIFYING`；
   - 当前停驻在 `READING`，自上次 progress 以来已收到 3 次 heartbeat，worker 仍存活；
   - 当前路径出现回退，且最近一次活跃推进发生在某个更早阶段。
3. 本轮仍优先复用现有结构化事件里的 `created_at / heartbeat_at / phase` 信息，不新增业务 API；重点是把 Research Harness 的运行态从“事件序列可读”推进到“活跃状态可判断”。
4. 前端任务事件层应尽量避免把历史完成态误说成当前活跃态；若最后事件是 `task.completed` 或 `task.failed`，路径审计应显式转成终态 judgement。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - `Deep Research 任务` 卡片编译通过，并能显示 phase 活跃度/停驻态判断摘要。

## 98. 本轮执行追加（八十七）（2026-07-06）
针对 `docs/深度研究智能体编排升级设计.md` 中 `4.1 任务进入与研究启动` 的入口表述，再做一次收束修正，重点消除“工作台绑定”被误读成“研究输入来自上下文”的残留歧义：

1. `Deep Research` 必须继续明确为工作台中的独立功能，但这种绑定只表示入口、运行、历史与产物回流挂在工作台里，不表示研究任务从工作台、页面态、Ask、Note、Wiki 或资料选择上下文派生。
2. 研究任务的成立条件应统一表述为 `用户显式输入`；研究问题、目标、范围、限制、预算、输出格式、参考资料与写回策略都属于用户在创建任务时主动填写或主动附带的输入字段。
3. 即便用户最终带入的是工作台里的已有资料，本质上也仍然是“创建任务时的显式输入动作”，而不是“当前上下文自动继承”。
4. 研究完成后的报告或确认后的研究资料可以继续融入工作台资料池，但这是 `Deep Research -> 工作台` 的输出回流，不应再写成启动阶段的上下文来源。
5. 本轮先只修正文档边界，不改动 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的执行闭环；目的是把入口模型锁定为“独立功能 + 用户输入 + 产物回流”。

## 99. 本轮执行追加（八十八）（2026-07-06）
在 `Deep Research 任务` 卡片已经能解释 `phase / delta / path / heartbeat` 之后，下一步优先把“当前闭环到底围绕什么在收敛”直接抬到运行态主摘要，避免用户虽然知道系统活着、阶段在推进，却仍然看不出 `Research Harness / Dual Verifier / 反证分支` 此刻正在校验哪类 requirement、围绕哪些 source focus 纠偏：

1. `Deep Research 任务` 卡片应补出一条 `runtime focus` 叙事，优先把 `recovery target / current source focus / latest verifier target` 组织成同一条运行中的审计说明。
2. 当 `Dual Verifier` 已经产生最新 decision 时，任务卡应尽量直接说明当前校验焦点，例如 `scope / branch / target requirement / source focus`，避免用户只能从下方明细区自行拼装。
3. 当系统仍处于恢复或纠偏态时，任务卡应优先说明当前 `反证分支` 或恢复靶点落在 `columns / queries / sources` 哪一层，让“当前为什么还没写报告”能在侧边卡片一眼读出来。
4. 本轮继续优先复用已有 `recovery target helper / source sample helper / verifier decision / run summary`，不新增业务 API；重点是让 `Closed-Loop Research` 的“运行焦点”比单纯 phase 更可解释。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - `Deep Research 任务` 卡片编译通过，并能显示 runtime focus 说明。

## 100. 本轮执行追加（八十九）（2026-07-06）
在任务卡已经能展示当前 `runtime focus` 之后，下一步优先把这条解释从“基于当前 run detail 的静态摘要”升级为“基于每一跳 progress event 的 phase-specific runtime snapshot”，避免早期阶段仍然被最终状态污染，也避免前端无法判断 `Closed-Loop Research` 的焦点到底是稳定、迁移还是扩散：

1. `worker -> progress callback -> task event` 这条链路应为 `ResearchProgressEvent` 补出结构化 `payload`，至少允许携带 phase-specific 的 `source_samples / recovery_targets / verifier_focus / recovery_mode`。
2. 这些 payload 必须按阶段真实裁剪，而不是把最终闭环状态整包复用到所有阶段：
   - `PLANNING` 围绕 query / source scope；
   - `SEARCHING` 围绕命中 source 与 query angle；
   - `READING` 围绕 read window / read focus；
   - `EXTRACTING` 围绕 evidence source 与 claim focus；
   - `VERIFYING / WRITING` 才逐步暴露 `recovery target / verifier focus / loop decision`。
3. 前端 `Deep Research 任务` 卡片随后应补出一条 `focus drift` 审计，至少能说明：
   - 当前闭环焦点是否相对上一跳保持稳定；
   - 是否从 `queries -> sources -> columns` 进一步收敛；
   - 是否从普通 research path 迁移到了 `Dual Verifier / 反证分支` 的 requirement-level 校验。
4. 本轮继续优先复用现有 `task event payload / source sample helper / recovery target helper`，不新增业务 API；重点是把 `Research Harness` 的“每一跳真实焦点”而不是“最终摘要”暴露出来。
5. 本轮测试至少要覆盖：
   - worker runner 测试通过；
   - backend research task contract 测试通过；
   - frontend build 与 UI contract 通过；
   - `Deep Research 任务` 卡片编译通过，并能显示基于 progress payload 的 focus drift 说明。

## 101. 本轮执行追加（九十）（2026-07-06）
在 `task.progress` 已经能携带 phase-specific runtime payload 之后，下一步优先把这些 payload 继续转成“单条事件可读叙事”，避免侧边任务卡虽然已经有 `runtime focus / focus drift` 主摘要，但逐条 progress 事件仍然停留在“阶段名 + metrics”的半结构化说明：

1. `buildResearchTaskEventNarrative` 应优先消费 progress event 自身的 `query_samples / source_samples / read_focuses / claim_samples / recovery_targets / verifier_focus / loop_decision`，而不是只按 phase 输出固定模板。
2. 事件叙事应做到“这一跳自己能解释自己”：
   - `PLANNING` 说明当前围绕哪些 query / source scope 组装 stop contract；
   - `SEARCHING` 说明当前命中哪些 source focus / search angle；
   - `READING` 说明当前压缩到哪些 read focus；
   - `EXTRACTING` 说明当前沉淀哪些 claim / evidence focus；
   - `VERIFYING / WRITING` 说明当前 verifier / recovery target / loop decision 为什么把闭环收敛到当前出口。
3. 若存在上一条 progress event，单条事件叙事还应尽量复用 `focus drift` helper，把“相对上一跳是否变窄 / 是否转入 verifier-level 校验”带进事件正文，而不是只放在任务卡主摘要里。
4. 本轮仍优先复用前一轮已经落下的 phase-specific payload 与 drift helper，不新增业务 API；重点是让 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在“事件流视角”里也具备同源解释。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - `Deep Research 任务` 卡片编译通过，并能显示 payload-aware 的 progress 事件叙事。

## 102. 本轮执行追加（九十一）（2026-07-06）
在任务卡事件流已经能基于 progress payload 解释“这一跳为什么收敛到这里”之后，下一步优先把这套同源解释继续推广到 `Run History Drift / Resume Recovery Diff`，避免当前历史视图虽然已经能列出 `targets / sources / verifier / loop` 的前后差异，但仍然需要用户自己脑内拼出“这是焦点稳定、焦点迁移，还是从普通 research path 转入 verifier/recovery gate”：

1. `Run History Drift` 不应只保留扁平字段对比；至少要补一条 `history focus drift` 说明，把 baseline run 与 current run 的 `source focus / recovery target / verifier gate` 串成一句连续判断。
2. `Resume Recovery Diff` 同样应补一条 `resume focus continuity` 说明，明确当前 run 相比来源 checkpoint 是继续沿同一恢复靶点收敛、已经换靶、还是已经从 recovery gate 收敛到写出出口。
3. 这层历史叙事必须继续复用现有 `source sample helper / recovery target delta helper / verifier status fields / loop decision fields`，保持 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在运行态、事件流与历史视图中的同源解释。
4. 本轮仍不新增业务 API；重点是让用户在历史视角中一眼看懂“为什么 drift、为什么 resume、为什么现在比之前更接近收敛”。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - `Run History Drift / Resume Recovery Diff` 编译通过，并能显示 history focus drift 说明。

## 103. 本轮执行追加（九十二）（2026-07-06）
在 `Run History Drift / Resume Recovery Diff` 已经开始复用历史焦点叙事之后，下一步优先把这套同源解释继续下沉到 `Checkpoint To Current Diff / Checkpoint To Checkpoint Diff`，避免 checkpoint 审计链仍然主要停留在 `targets / sources / verifier / loop` 的字段对比层，而不能直接回答“这个 checkpoint 相对 current 或相对另一份 checkpoint，到底是在继续收敛、已经换靶，还是已经转入更强的 verifier/recovery gate”：

1. `Checkpoint To Current Diff` 应补一条 `checkpoint focus drift` 说明，把 snapshot checkpoint 与 current run 的 `source focus / recovery target / verifier gate / recovery mode` 串成一句连续判断。
2. `Checkpoint To Checkpoint Diff` 同样应补一条 `checkpoint-to-checkpoint focus drift` 说明，明确 baseline snapshot 与 current snapshot 的收敛方向、换靶情况与 gate 迁移。
3. 这层叙事必须继续复用已有 `buildHistoricalFocusDriftNarrative`、`source sample helper`、`recovery target delta helper` 与 verifier/loop 字段，保持 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在运行态、事件流、run 历史和 checkpoint 审计中的同源解释。
4. 本轮仍不新增业务 API；重点是把 checkpoint 视角也拉进同一条 `Closed-Loop Research` 审计语言里。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - `Checkpoint To Current Diff / Checkpoint To Checkpoint Diff` 编译通过，并能显示 checkpoint focus drift 说明。

## 104. 本轮执行追加（九十三）（2026-07-06）
在 checkpoint diff 视图已经能解释 `source focus / recovery target / verifier gate` 的焦点迁移之后，下一步优先把这套解释继续下沉到 `Verified Findings Progress / Conflict Resolution Progress`，避免这些结果卡片仍然主要停留在“新增多少、移除多少、解决多少”的数量层，而没有直接说明“这些变化为什么代表闭环有效收敛或路径纠偏生效”：

1. `Verified Findings Progress` 不应只显示 `added / retained / dropped` 计数；至少要补一条 `outcome drift` 说明，明确当前相对 baseline 是净新增结论、主要在替换旧结论，还是暂时没有形成新的 verifier-approved 收敛。
2. `Conflict Resolution Progress` 同样应补一条 `conflict outcome` 说明，明确当前相对 baseline 是主要在消解冲突、仍在同一批来源上僵持，还是冲突靶点已经迁移。
3. 这层结果叙事必须继续复用现有 `partitionResearchRows`、`source sample helper` 与 findings/conflicts diff 结果，保持 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 在“焦点怎么变”与“结果怎么变”之间仍然采用同源解释。
4. 本轮仍不新增业务 API；重点是让 checkpoint 结果卡片直接回答“为什么这次纠偏有用”。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - checkpoint 结果进展卡片编译通过，并能显示 outcome-level drift 说明。

## 105. 本轮执行追加（九十四）（2026-07-06）
在 checkpoint / resume 的结果卡片已经开始解释“新增了什么、移除了什么、解决了什么”之后，下一步优先把同样的 outcome-level 解释推进到 `Counterfactual Branch Diff`，因为 `反证分支` 正是 `Research Harness / Dual Verifier` 路径纠偏是否有效的最关键观察面：

1. `Counterfactual Branch Diff` 不应只显示 branch 数、target 变化与冲突计数；至少要补一条 `counterfactual focus drift` 说明，明确当前反证路径相对 baseline 是继续沿同一靶点深挖，还是已经换到新的 source/recovery gate。
2. `Verified Findings After Recheck` 应补一条 `counterfactual findings outcome` 说明，明确这次反证分支主要是净新增结论、推翻旧结论，还是仍停留在重排阶段。
3. `Conflict Delta` 同样应补一条 `counterfactual conflict outcome` 说明，明确这次反证分支到底真正消解了哪些冲突、还卡在哪些来源上。
4. 本轮继续优先复用已有 `buildHistoricalFocusDriftNarrative / buildFindingsOutcomeDriftNarrative / buildConflictOutcomeNarrative`，不新增业务 API；重点是让 `反证分支` 视图也直接回答“这次纠偏为什么有用或为什么还没收敛”。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - `Counterfactual Branch Diff` 编译通过，并能显示 focus/outcome drift 说明。

## 106. 本轮执行追加（九十五）（2026-07-06）
承接 `Counterfactual Branch Diff` 已经具备 `counterfactualFocusDriftNarrative / counterfactualFindingsOutcomeNarrative / counterfactualConflictOutcomeNarrative` 的前端推导变量，本轮先把这三条说明真正落到反证分支 diff 卡片上，并完成一次编译与 UI 校验，避免 `反证分支` 仍停留在“数据已算出但界面未解释”的半成品状态：

1. `Counterfactual Target Delta` 卡片应直接展示 `counterfactual focus drift` 说明，让用户在看 target/source delta 时同步知道这次反证纠偏是继续沿原靶点收敛，还是已经切换了新的 recovery focus。
2. `Verified Findings After Recheck` 卡片应直接展示 `counterfactual findings outcome` 说明，把“新增了多少 finding”升级为“这次反证重查到底形成了新的 verifier-approved 收敛，还是仍处于替换/重排阶段”。
3. `Conflict Delta` 卡片应直接展示 `counterfactual conflict outcome` 说明，明确冲突变化是被真正消解、仅发生迁移，还是仍卡在同一批 source 上。
4. 本轮继续复用已有 `buildHistoricalFocusDriftNarrative / buildFindingsOutcomeDriftNarrative / buildConflictOutcomeNarrative` 与 counterfactual baseline helper，不新增业务 API；重点是把 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的解释闭环补齐到最后一个关键审计视图。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - `Counterfactual Branch Diff` 能实际显示 focus/outcome narrative，而不只是结构化计数与 delta。

## 107. 本轮执行追加（九十六）（2026-07-06）
在 `Counterfactual Branch Diff` 已经能解释“反证纠偏有没有产出净推进”之后，下一步优先把 `Table-as-State + Dual Verifier` 的“当前到底卡住了哪些行、为什么卡住、恢复靶点有没有命中这些卡点”直接抬到工作台审计面，否则用户虽然能看到 branch / target / findings / conflict 的结果变化，仍然无法直接回答“这次 Closed-Loop Research 还没放行的具体阻塞项是什么”：

1. 工作台应新增一个面向当前 run 的 `Verifier-Gated Rows` 审计卡，把 `conflicted_rows / guardrailed_rows / rows` 中仍未放行的对象聚到同一个阅读面，避免用户在 `Closed-Loop State / Conflict Review / Recovery Status` 之间来回拼装。
2. 每条被卡住的 row 至少要显示：
   - `row_status / requirement_completion_status / verifier_note / repair_hint`；
   - 命中的 requirement 或缺失 requirement；
   - 缺失列 / read focus / evidence 锚点；
   - 当前 `recovery_targets` 是否直接覆盖该 row。
3. 这张卡不应只列原始字段；至少要补一条聚合叙事，说明当前阻塞更偏向：
   - requirement 覆盖不足；
   - verifier conflict 未消解；
   - recovery target 已经命中但还未转成 ready rows；
   - 仍有卡点尚未被当前恢复靶点覆盖。
4. 本轮继续复用已有 `researchClosedLoopState.rows / conflict_and_counterfactual_review.conflicted_rows / recovery_status.guardrailed_rows / recovery_targets`，不新增业务 API；重点是把 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的“阻塞项真源”直接露给用户。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - 工作台能显示 `Verifier-Gated Rows` 行级审计说明。

## 108. 本轮执行追加（九十七）（2026-07-06）
在当前 run 已经能显示 `Verifier-Gated Rows` 之后，下一步优先把这套“阻塞项真源”继续贯通到 `Checkpoint To Current Diff / Checkpoint To Checkpoint Diff / Resume Recovery Diff / Counterfactual Branch Diff`，否则用户虽然能看见“当前卡住了哪些 row”，却仍然无法直接判断“这些阻塞行相对历史基线到底是在收敛、迁移，还是只是换了一批未被 recovery target 覆盖的新卡点”：

1. `Checkpoint To Current Diff` 至少应补一个 `Verifier-Gated Row Drift` 卡，直接展示 baseline checkpoint 与 current run 之间：
   - blocked rows 总量变化；
   - resolved / retained / new blocked rows；
   - 被当前 recovery target 命中的 blocked rows 与未覆盖 blocked rows；
   - requirement-partial / conflict 型阻塞是收敛还是扩散。
2. `Checkpoint To Checkpoint Diff` 与 `Resume Recovery Diff` 应复用同一套 blocked-row diff helper，避免 checkpoint baseline、resume source 与 current run 在“阻塞项变化”上各说各话。
3. 如果 `Counterfactual Branch Diff` 已经可以说明 findings/conflicts 的 outcome 变化，那么它也应继续补出最小的 blocked-row drift，明确反证分支到底是消掉了原来的 guardrailed rows，还是只是把阻塞从一批 source 迁移到另一批 source。
4. 本轮继续复用已有 `closed_loop_state.rows / checkpoint payload.state_ledger.rows / recovery_status.guardrailed_rows / conflicted_rows / recovery_targets`，不新增业务 API；重点是把 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的“阻塞项漂移”纳入历史比较链。
5. 本轮测试至少要覆盖：
   - frontend build 通过；
   - UI contract 通过；
   - 相关 diff 视图能显示 `Verifier-Gated Row Drift` 摘要。

## 109. 本轮执行追加（九十八）（2026-07-06）
在各类 full-payload diff 视图已经能解释 `Verifier-Gated Row Drift` 之后，下一步优先把这套阻塞项摘要继续沉到 `summary-first` 链路，否则 `Deep Research` 的 checkpoint 列表、run 摘要与历史入口仍然只能看见 verified/conflicted 的粗粒度统计，无法在不展开整份 payload 的前提下判断 “当前闭环还剩多少 guardrailed rows、这些阻塞项是否已被 recovery target 覆盖”：

1. 后端 `buildCheckpointStateLedgerSummary(...)` 应补出最小 `verifier-gated` 摘要，至少包括：
   - blocked row count；
   - recovery-targeted blocked row count；
   - uncovered blocked row count；
   - guardrailed / need-more-evidence / blocked row samples；
   - requirement-partial blocked row count。
2. 如果 run summary 已经承担 `summary-first continuity` 入口，那么它也应优先暴露同口径的 `verifier-gated` 统计，避免当前 run 卡片与 checkpoint 列表对“阻塞项规模”的理解继续割裂。
3. 前端 `Checkpoint 回放` 列表与 `Run 摘要` 至少要复用这套 summary-first 字段，直接显示 blocked / targeted / uncovered 的最小提示，而不是强依赖 full payload 推导。
4. 本轮继续复用现有 `state_ledger.rows / recovery_targets / intent_completion_contract`，尽量把 blocked-row summary 固化为稳定契约，而不是只在前端临时现算；重点是把 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的阻塞项摘要沉淀成真正可回放的 summary。
5. 本轮测试至少要覆盖：
   - backend 相关测试通过；
   - frontend build 通过；
   - UI contract 通过；
   - checkpoint 列表或 run summary 能显示 summary-first 的 `Verifier-Gated` 摘要。

## 110. 本轮执行追加（九十九）（2026-07-06）
用户已明确当前优先级是“先把功能搭起来”，因此本轮继续沿着 `summary-first verifier-gated` 主线补骨架，不再把重点放在 narrative 润色，而是确保 `Deep Research` 在不展开 full payload 的情况下也能稳定回答“当前闭环卡在哪里、这些卡点是否已进入修复闭环”：

1. 后端除 `buildCheckpointStateLedgerSummary(...)` 外，如当前 run 摘要链路已有对应 summary builder，也应统一补齐 `blocked / targeted / uncovered / guardrailed / partial` 最小聚合口径，避免 checkpoint 与 run 各自现算、长期漂移。
2. 前端 `Checkpoint 回放` 与 `Run 摘要` 优先展示这组稳定契约，哪怕先只做一行简洁提示，也要让工作台主阅读流能直接看见 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的阻塞面。
3. 若当前 run summary 尚无完整后端字段，但 checkpoint summary 已能稳定产出，则前端应优先复用 checkpoint 同口径渲染函数，保持用户看到的是统一的 verifier-gated 语言，而不是不同区域各说各话。
4. 本轮不新增新的研究业务流程，不改 Deep Research 入口边界；重点是把已有 closed-loop 运行态压缩成真正可用于回放、历史比较与工作台总览的 summary-first 功能骨架。
5. 本轮完成标准：
   - 阶段计划先更新；
   - backend / frontend 编译通过；
   - UI contract 通过；
   - `Checkpoint 回放` 与/或 `Run 摘要` 能在 summary 级直接显示 blocked-targeted-uncovered 提示。

## 111. 本轮执行追加（一百）（2026-07-06）
用户已进一步明确：前端应优先参考 GPT / Gemini 的 Deep Research 产品形态，而不是把 `Closed-Loop Research` 的审计字段直接推到主阅读层。因此本轮实现方向需要调整为“主流程优先、调试信息降级”，确保 Deep Research 先成为一个独立可用的研究功能，再把 `Research Harness / Table-as-State / Dual Verifier / 反证分支` 作为高级能力承接在后面：

1. Deep Research 主界面优先围绕单独研究入口展开，只从用户显式输入的问题启动，不从 Ask / Note / Wiki 上下文直接继承入口语义；与工作台的关系是结果写回、资料引用、研究历史绑定，而不是上下文混入。
2. 前端主阅读流应优先具备 GPT / Gemini 风格的最小骨架：
   - 研究问题输入与启动；
   - 研究进行中的步骤/阶段进度；
   - 已读取来源与关键发现；
   - 最终研究报告、引用与写回工作台动作。
3. `verifier-gated / checkpoint / counterfactual / closed-loop state` 等信息不删除，但应下沉到 `高级调试 / 研究过程 / 审计面板`，避免主界面继续朝“内部控制台”形态漂移。
4. 如果当前已有 `verifier-gated summary` 契约改动正在进行，应优先判断它们是否服务于后续高级调试层；能保留的保留，但本轮前端不再把它们当作首页主卖点。
5. 本轮完成标准调整为：
   - 阶段计划先更新；
   - Deep Research 主界面骨架更接近通用产品形态；
   - 审计/闭环信息被明确降级到高级区域；
   - frontend build 与 UI contract 通过。

## 112. 本轮执行追加（一百零一）（2026-07-06）
用户已再次强调要优先参考 `reference` 里的可展示智能体项目，不要继续堆叠过多“看不见、讲不清”的内部能力。因此本轮继续把实现目标压缩到“先把功能搭起来”，具体约束如下：

1. Deep Research 主流程优先对齐 GPT / Gemini / `reference/Marco-DeepResearch` 一类可演示产品形态：
   - 明确的问题输入；
   - 明确的运行阶段；
   - 明确的来源与关键发现；
   - 明确的最终报告与写回动作。
2. `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 仍然保留，但主要承担：
   - 后台控制研究质量；
   - 在高级调试区支持解释“为什么还没收敛、为什么需要纠偏”；
   - 不作为主界面的第一阅读层。
3. 本轮尽量复用现有 run detail / report structure / checkpoints / task events / run history 数据，不额外发明新的业务流程或重型 API，只做界面重组与必要契约收口。
4. 前端优先做“能展示、能讲明白”的工作台：
   - 左侧是启动、档位、资料范围、历史；
   - 中间是当前 run 的进度、来源、发现、报告；
   - 高级调试单独折叠承载 verifier/checkpoint/counterfactual/trace。
5. 本轮完成标准进一步收束为：
   - 阶段计划先更新；
   - 主界面不再像内部控制台；
   - 研究功能可顺畅演示从提问到报告写回；
   - frontend build 与 UI contract 通过；
   - 如保留 verifier-gated summary 字段，也只作为高级能力契约，不强推到首页。

## 113. 本轮执行追加（一百零二）（2026-07-06）
在参考 `reference/Marco-DeepResearch` 的产品形态之外，用户进一步要求本轮实现要服务于本项目的简历亮点与项目叙事，因此 Deep Research 的展示重点需要继续向“可讲述的作品能力”收束：

1. 前端主界面不仅要像通用 Deep Research 产品，还要优先映射 [项目亮点与简历草案](D:/java-projects/NoteWeave-v2/docs/项目亮点与简历草案.md) 中的第一、三、五项亮点：
   - `Deep Research 智能体`；
   - `三检索链路一体化知识工作流`；
   - `文件上传 / 资料池 / 写回基础设施`。
2. 因此前端主阅读流应优先强化以下可展示能力：
   - 明确输入开放式研究问题并启动独立研究任务；
   - 明确看到研究阶段推进与运行中状态；
   - 明确看到当前依赖的资料范围、来源样本与引用；
   - 明确看到最终报告如何回写到工作台资料池，并继续进入统一工作流。
3. `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 仍然是技术深度的核心，但在展示层主要承担“高级能力证明”角色，用于支撑面试时解释“为什么这个 Deep Research 比普通问答更稳”，而不是占据首页主体。
4. 本轮尽量不再新增难以展示的内部-only 功能；若某项实现无法直接改善主界面演示、项目叙事或简历表达，则优先后置。
5. 本轮完成标准补充为：
   - Deep Research 页面能更自然地支撑项目演示与简历讲解；
   - 用户能一眼看出“问题输入 -> 研究推进 -> 资料/来源 -> 报告产出 -> 写回工作台”的主链；
   - 高级闭环审计被保留，但不压过产品主流程。

## 114. 本轮执行追加（一百零三）（2026-07-06）
用户已进一步明确交互要求：`checkpoint / verifier / trace / counterfactual / verifier-gated rows / closed-loop state` 等高级研究审计信息可以保留，但应迁移到“研究详情”弹窗或详情页中，而不是继续占据主界面。因此本轮前端交互继续收束如下：

1. Deep Research 主界面只保留面向演示与主流程的核心信息：
   - 研究问题与启动；
   - 当前运行状态与阶段推进；
   - 来源范围、关键发现、最终报告；
   - 报告写回资料池与回流工作台的动作。
2. `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的详细审计对象统一迁移到 `研究详情` 弹窗，作为高级阅读层：
   - run summary / lineage；
   - checkpoint playback / diff；
   - verifier decisions / loop rounds / traces；
   - counterfactual / blocked rows / closed-loop state。
3. 这样主界面继续承担“产品演示层”，详情弹窗承担“技术深度证明层”，既能参考 GPT / Gemini 的 Deep Research 体验，也能保留后续面试讲解所需的闭环材料。
4. 本轮优先复用现有前端数据与已加载详情对象，不额外设计新的数据接口；重点是信息分层与交互收口。
5. 本轮完成标准补充为：
   - 主界面不再直接铺满高级审计卡片；
   - 用户可以通过显式按钮打开研究详情弹窗查看闭环信息；
   - 主流程演示与高级审计阅读各自职责清晰。

## 115. 本轮执行追加（一百零四）（2026-07-06）
用户继续补充了研究详情的展示方式：详情页不应只有“一层大杂烩”，而应明确拆成两层阅读结构，先看“研究过程”，再看“闭环审计”，以便更贴近 GPT / Gemini 一类 Deep Research 的可演示体验：

1. `研究详情` 弹窗新增双层结构：
   - 第一层优先展示 `调研网页 / 搜索命中 / 工具执行 / 阅读推进 / 当前阶段`；
   - 第二层再展示 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 等高级审计对象。
2. 第一层应尽量服务“看得见的研究过程”，优先复用当前已有运行态对象：
   - task progress / runtime snapshot；
   - research traces；
   - loop rounds；
   - 与来源样本、网页/证据焦点、工具推进相关的可读卡片。
3. 第二层继续保留技术深度证明能力：
   - closed-loop state；
   - verifier-gated rows；
   - checkpoint playback / diff；
   - run history drift / resume recovery / counterfactual branch diff。
4. 本轮仍以“先把功能搭起来”为主，不新增不可演示的新后端契约；优先在现有前端数据上完成信息重组与弹窗交互。
5. 本轮完成标准补充为：
   - 用户打开详情后先看到研究过程层，而不是先看到审计术语；
   - 审计层仍可一键切换查看，不丢失面试讲解所需的闭环材料；
   - 主界面、过程层、审计层三者职责清晰。

## 116. 本轮执行追加（一百零五）（2026-07-06）
在双层详情已经可用之后，下一步优先继续把第一层从“工程调试视角”收敛为“产品过程回放视角”，避免虽然已经拆层，但过程层的命名和卡片仍然偏向内部字段直出：

1. `调研过程` 层应更接近用户能直接理解的 Deep Research 过程语言：
   - 任务推进；
   - 已检索网页 / 工具动作；
   - 当前阅读与证据焦点；
   - 最近一轮研究判断。
2. 当前 `Research Traces / Loop Rounds / Verifier Decisions` 三块可继续复用现有数据，但在命名、摘要句和说明语气上应减少“工程对象名”暴露，增强“产品过程感”。
3. `闭环审计` 层继续保留原术语，不强行产品化；这里仍然承担面试讲解 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支` 的职责。
4. 本轮仍不新增后端能力，重点是前端过程叙事、信息标题与阅读节奏优化。
5. 本轮完成标准补充为：
   - 打开详情后第一眼看到的是“研究是如何推进的”，而不是“系统里有哪些结构化对象”；
   - 过程层与审计层的语言风格形成明确区分；
   - 不牺牲现有闭环材料的可追溯性。

## 117. 本轮执行追加（一百零六）（2026-07-06）
在过程层已经完成第一轮命名产品化之后，下一步优先把它继续具体化为“搜索入口 / 阅读推进 / 资料回流”三类可见过程卡片，进一步贴近参考项目里 `search + visit/read + report/writeback` 的真实研究路径：

1. `调研过程` 第一屏不应只有抽象统计，而应显式出现：
   - 搜索问题、搜索角度、候选查询；
   - 当前阅读焦点、来源样本、读窗压缩痕迹；
   - 报告写回资料池后的回流状态。
2. 这三类卡片优先复用现有前端数据：
   - `task.progress` 里的 `query_samples / search_angles / read_focuses / source_samples`；
   - `currentResearchTraces` 与 `source_evidence`；
   - `saved_report_source / currentSavedReportSourceAsset / currentSavedReportSourceInScope`。
3. 过程层仍保持“产品视角”，不在这里引入 `Research Harness / Dual Verifier` 术语泛滥；术语密度继续留给第二层 `闭环审计`。
4. 本轮仍不新增后端接口，重点是把已有研究运行态转换成更像 GPT / Gemini / reference 项目的可演示阅读体验。
5. 本轮完成标准补充为：
   - 过程层能直接回答“搜了什么、读了什么、最后怎么回流到工作台”；
   - 用户即使不看第二层审计，也能理解这次 Deep Research 的主流程；
   - 过程层与工作台资料池写回能力形成明确联动。

## 118. 本轮执行追加（一百零七）（2026-07-06）
在“搜了什么 / 读了什么 / 如何回流”三类高层卡片已经出现之后，下一步优先把更细的 `trace` 阅读层也同步拆分为可浏览的过程对象，避免用户虽然能看到高层摘要，但下面仍是一串混合的通用轨迹卡片：

1. `网页与工具轨迹` 应继续细分成至少三类可读对象：
   - 搜索轨迹卡：查询、搜索角度、命中线索；
   - 页面阅读卡：URL / provider / adapter / snapshot_status / 读窗焦点；
   - 工作台来源卡：workspace source / research report 回流来源。
2. 这层细分优先复用已有字段与 helper：
   - `trace_type / trace_message / payload / source_samples`；
   - `source_evidence.url|source_url / provider / adapter / snapshot_status / generated_by / generated_ref_id`；
   - `source_scope / saved_report_source`。
3. 过程层继续维持“像产品在浏览研究过程”的阅读感，不把 `Dual Verifier / Counterfactual / checkpoint diff` 重新混回第一层。
4. 本轮仍不新增后端接口，重点是 trace 分类、展示语言和卡片阅读体验升级。
5. 本轮完成标准补充为：
   - 用户能在第一层直接区分“这是搜索线索、这是网页阅读、这是工作台来源”；
   - Deep Research 过程层更接近参考项目里 `search + visit + source loopback` 的浏览体验；
   - 原有闭环审计材料不受影响。

## 119. 本轮执行追加（一百零八）（2026-07-06）
结合用户刚刚再次确认的“两层结构”要求，本轮继续把第一层打磨成真正可展示的 Deep Research 过程浏览界面，但明确控制改动边界：主界面不增加复杂对象，重心仍放在详情弹窗的 `调研过程` 层。

1. `调研过程` 层中的 `网页与工具轨迹` 区域继续拆细为三组过程卡：
   - `搜索轨迹卡`：呈现 query / angle / 搜索命中线索；
   - `页面阅读卡`：呈现 visit/read、URL、provider、adapter、snapshot status 与阅读焦点；
   - `工作台来源卡`：呈现 workspace source、已有资料、research report 写回来源。
2. 分类优先使用现有前端运行态做启发式识别，不新增后端接口：
   - `trace_message / trace_type / payload`；
   - `source_evidence.url|source_url / provider / adapter / snapshot_status / generated_by`；
   - `saved_report_source / source_samples / source_scope`。
3. 第一层文案继续使用面向产品演示的语言，而不是 verifier / harness / checkpoint 等内部术语；这些内容仍集中放在第二层 `闭环审计`。
4. 本轮目标是“先把功能搭起来并且好展示”，参考 reference 项目中的 `search + visit/read + writeback` 路径，不扩展新的不可见后端能力。
5. 本轮完成标准补充为：
   - 详情弹窗第一层能明显看到三类不同研究对象，而不是一串混合 trace；
   - 用户能顺着卡片直接讲清楚“怎么搜、怎么读、怎么回流到工作台”；
   - 第二层审计材料保持原样，可继续支持简历亮点和项目讲解。

## 120. 本轮执行追加（一百零九）（2026-07-06）
在第一层已经具备“搜索 / 阅读 / 工作台来源”三类浏览卡之后，下一步优先补足“可操作闭环”，避免当前虽然已经能讲过程，但用户仍然只能看不能顺手演示网页打开、来源回流和 source scope 联动。

1. `调研过程` 层继续增强为“可操作过程层”：
   - 页面阅读卡支持直接打开当前网页来源；
   - 工作台来源卡支持直接定位到 source scope；
   - 报告回流卡支持直接把写回报告加入 / 移出当前 source scope。
2. 本轮仍严格复用现有前端动作，不新增后端接口：
   - `openResearchWorkbench`；
   - `addResearchSourceToScope / removeResearchSourceFromScope`；
   - `setFocusedResearchSourceId`；
   - 已存在的 `saved_report_source / sources / selectedResearchSourceIds`。
3. 第一层交互继续保持产品语言，强调 `search + visit/read + writeback + source loopback` 的真实研究闭环；`Research Harness / Dual Verifier / checkpoint` 等深层对象仍留在第二层。
4. 本轮目标是让 Deep Research 详情弹窗第一层不只是“可读”，而是已经具备参考项目和面试演示所需的“可讲 + 可点 + 可回流”体验。
5. 本轮完成标准补充为：
   - 用户能在第一层直接打开网页阅读来源；
   - 用户能在第一层直接完成写回报告与 source scope 的联动操作；
   - Deep Research 的过程层已经具备基础闭环演示能力，而不仅是静态说明。

## 121. 本轮执行追加（一百一十）（2026-07-06）
在第一层已经具备基础闭环动作之后，下一步优先增强“研究过程回放感”和“阶段层次感”，让详情弹窗更接近 GPT / Gemini / reference 项目中的 Deep Research 演示体验，而不是一组普通信息卡片。

1. `调研过程` 层继续增强视觉结构，但仍不引入新的后端对象：
   - 增加一层研究进度总览带，汇总当前搜索、阅读、回流、校验状态；
   - 三类过程卡增加更明显的分区头、数量提示、阶段徽标；
   - 轨迹卡本身增强为更像“过程回放”的样式，而不是简单文字列表。
2. 本轮仍严格复用现有前端数据：
   - `currentResearchTraces / visibleSearchProcessTraces / visibleReadProcessTraces / visibleWorkspaceProcessTraces`；
   - `currentSavedReportSource / currentSavedReportSourceInScope`；
   - `latestResearchTask / currentLoopRounds / currentVerifierDecisions / currentSourceEvidence`。
3. 第一层文案继续采用产品语言，不把 `Research Harness / Dual Verifier / Counterfactual Branch` 等技术术语重新混回主过程视图；这些仍放在第二层。
4. 本轮目标是让用户打开详情后，第一眼就能感受到这是“研究正在推进中的回放界面”，而不是后台对象浏览器。
5. 本轮完成标准补充为：
   - 第一层具备清晰的研究进度总览与阶段分区；
   - 三类过程卡在视觉上容易区分，演示时不需要额外解释“这块是搜、这块是读、这块是回流”；
   - 不影响现有按钮能力、工作台联动能力与第二层审计材料。

## 122. 本轮执行追加（一百一十一）（2026-07-06）
在第一层已经具备总览与三段过程结构之后，下一步优先补出“时间线 / 轮次回放”能力，避免页面虽然已经更像产品，但研究推进仍然偏静态，缺少“一轮一轮收敛”的 session 感。

1. `调研过程` 层增加 `最近几轮研究推进` 区域：
   - 用轮次按钮或时间线节点展示最近几轮 round；
   - 默认聚焦最近一轮，同时允许用户切换查看前几轮；
   - 预览当前轮的判断、来源焦点、搜索/阅读规模与收敛结果。
2. 本轮仍只复用现有前端运行态：
   - `currentLoopRounds / visibleLoopRounds`；
   - `round_no / decision / reason / source_samples / search_hit_count / read_window_count / evidence_card_count`；
   - 现有 `buildStepOutcomeNarrative / buildAuditStepDeltaNarrative` 等 helper。
3. 第一层继续保持产品视角，不把 `checkpoint diff / verifier blocked rows / counterfactual branches` 混入这个时间线区域；这里强调的是“研究推进过程”，不是“底层审计结构”。
4. 本轮目标是让详情第一层具备“这次 Deep Research 是如何一轮一轮推进的”可演示体验，更贴近真实的 Deep Research session 回放。
5. 本轮完成标准补充为：
   - 用户能在第一层切换查看最近几轮研究推进；
   - 第一层不仅能看阶段，还能看时间上的推进顺序；
   - 不影响现有三类过程卡、工作台回流动作与第二层审计能力。

## 123. 本轮执行追加（一百一十二）（2026-07-06）
在轮次时间线已经出现之后，下一步优先补齐“轮次与轨迹卡联动”，避免用户虽然可以切换 round，但下面三类过程卡仍然是静态混合列表，缺少“这一轮到底做了什么”的直接对应关系。

1. `最近几轮研究推进` 与三类过程卡建立联动：
   - 选中某一轮时，优先展示该轮相关的搜索 / 阅读 / 工作台来源轨迹；
   - 卡片上显式标出 `round n` 命中关系；
   - 如果某一类在当前轮没有对应轨迹，则优雅回退到全量视图并给出说明。
2. 本轮仍严格复用已有前端数据锚点：
   - `selectedProcessRoundNo / selectedProcessRoundPreview`；
   - `readTraceCheckpointCandidate(trace).roundNo`；
   - `payload.round_no / loop_decision.round_no` 等现有 round 信息。
3. 第一层继续保持“产品回放视角”，这里强调的是“这轮研究搜了什么、读了什么、回流了什么”，而不是底层 audit diff。
4. 本轮目标是让第一层具备“选中一轮 -> 看到这一轮对应轨迹”的自然浏览体验，更接近成熟 Deep Research 产品。
5. 本轮完成标准补充为：
   - 轮次切换会影响三类过程卡的展示焦点；
   - 用户能直接讲清楚某一轮具体发生了哪些研究动作；
   - 不影响第二层审计和已有工作台回流操作。

## 124. 本轮执行追加（一百一十三）（2026-07-06）
在三类轨迹卡已经可以随 round 聚焦之后，下一步优先把顶部“搜索入口 / 页面阅读 / 回流工作台”三段摘要卡也接入同一轮次上下文，避免页面上半部分仍然始终讲当前 run 全局，而页面下半部分已经切到了某一轮。

1. 顶部三段摘要卡与当前 `selectedProcessRoundPreview` 建立联动：
   - 搜索入口卡优先展示该轮命中的 query / search angle；
   - 页面阅读卡优先展示该轮命中的 read focus / source focus；
   - 回流工作台卡优先展示该轮是否出现 writeback / workspace source 相关动作。
2. 本轮仍只复用现有前端运行态和已解析 trace：
   - `selectedProcessRoundPreview / visibleResearchProcessTraces`；
   - `tracePayload.query|search_query|query_samples|search_angles|read_focus|read_focuses`；
   - `loopRound.source_samples / currentSavedReportSource / currentSavedReportSourceInScope`。
3. 如果当前轮没有足够的显式信息，则继续优雅回退到当前 run 的全局摘要，不让顶部卡片变成空白。
4. 本轮目标是让用户从时间线选中某一轮后，页面从上到下都围绕“这一轮发生了什么”来讲述，更像完整的 Deep Research session 回放。
5. 本轮完成标准补充为：
   - 顶部三段卡会随轮次切换而变化；
   - 用户不需要自己拼接上下文，就能理解“这一轮搜了什么、读了什么、有没有回流”；
   - 保持现有回流动作按钮和轨迹卡联动能力不变。

## 125. 本轮执行追加（一百一十四）（2026-07-06）
在页面从上到下都已经可以围绕某一轮讲述之后，下一步优先补一个显式的 `整体视角 / 当前轮视角` 切换，避免用户一进入详情就默认被吸附到某一轮，而缺少从全局总览切回单轮回放的清晰控制。

1. `调研过程` 层增加显式视角切换：
   - `整体视角`：顶部摘要卡和三类轨迹卡按当前 run 的整体状态展示；
   - `当前轮视角`：顶部摘要卡和三类轨迹卡优先展示所选 round 的对应动作。
2. 本轮仍只复用现有前端状态：
   - `selectedProcessRoundNo / selectedProcessRoundPreview`；
   - 已有顶部三段卡联动逻辑；
   - 已有三类轨迹卡 round-filter/fallback 逻辑。
3. 如果用户切回 `整体视角`，轮次时间线仍然保留当前选中 round，仅作为预览，不再强制驱动下方卡片焦点。
4. 本轮目标是让第一层既能讲“整体研究怎么推进”，也能讲“某一轮具体做了什么”，阅读控制更自然。
5. 本轮完成标准补充为：
   - 用户能显式切换整体/单轮两种浏览方式；
   - 切换后顶部摘要卡和三类轨迹卡行为一致；
   - 不影响轮次时间线、工作台回流动作与第二层审计能力。

## 126. 本轮执行追加（一百一十五）（2026-07-06）
在 `整体视角 / 当前轮视角` 已经存在之后，下一步优先把第一层最顶部的研究总览也接入同一套视角语义，避免当前页面出现“上半区还在讲全局、下半区已经切到某一轮”的割裂感。

1. `调研过程` 顶部总览区与 `processNarrativeMode` 建立一致联动：
   - `整体视角` 下继续显示当前 run 的整体搜索/阅读/资料回流/闭环推进状态；
   - `当前轮视角` 下改为显示所选 round 的搜索命中、阅读推进、回流动作与轮次位置。
2. 本轮仍只复用现有前端状态，不新增后端契约：
   - `selectedProcessRoundPreview / selectedRoundSearchTraceEntries / selectedRoundReadTraceEntries / selectedRoundWorkspaceTraceEntries`；
   - `currentLoopRounds / currentVerifierDecisions / currentSavedReportSource / currentSavedReportSourceInScope`；
   - 已存在的 `loopRoundOutcomeNarrative / source_samples / traceSourceSamples`。
3. 顶部总览文案继续保持产品化表达，不把 `Dual Verifier / Harness / checkpoint` 等术语拉回第一层；这里只强调“这一轮做了什么、整体研究推进到了哪里”。
4. 本轮目标是让详情弹窗第一层从顶部到底部都能围绕同一视角讲故事，更接近参考项目以及 GPT / Gemini Deep Research 的可演示观感。
5. 本轮完成标准补充为：
   - 切到 `当前轮视角` 后，顶部总览不再停留在全局统计；
   - 顶部总览、三段摘要卡、三类轨迹卡在同一轮次语义下保持一致；
   - 不影响第二层闭环审计材料与现有回流/打开来源动作。

## 127. 本轮执行追加（一百一十六）（2026-07-06）
在第一层已经具备“总览 + 轮次回放 + 三类轨迹卡”之后，下一步优先把它补成一个更像真实 Deep Research 的 `过程浏览器`，避免用户虽然能看到搜索/阅读/回流卡片，却仍然需要自己在卡片堆里拼“这一步到底打开了什么网页、用了什么工具、读到了什么”。

1. `调研过程` 第一层继续保持两段式阅读：
   - 左侧保留三类 `搜索轨迹卡 / 页面阅读卡 / 工作台来源卡`；
   - 右侧新增 `当前动作详情` 面板，专门承接用户当前点选的网页/工具动作。
2. 详情面板优先服务于可演示体验，而不是新增审计术语：
   - 点选搜索轨迹时，展示 query / search angle / 命中线索；
   - 点选页面阅读时，展示 URL / provider / adapter / snapshot / read focus；
   - 点选工作台来源时，展示来源身份、回流状态与 source scope 联动动作。
3. 本轮仍只复用现有前端运行态与 helper，不新增后端契约：
   - `visibleSearchProcessTraces / visibleReadProcessTraces / visibleWorkspaceProcessTraces`；
   - `tracePayload / traceSourceSamples / readResearchSourceUrl / buildResearchProcessTraceNarrative`；
   - 已有 `openResearchWorkbench / addResearchSourceToScope / removeResearchSourceFromScope / openResearchCheckpoint`。
4. 交互上应保证过程层继续贴近 GPT / Gemini / reference 项目的“边看过程、边点开细节”体验：主界面仍保持干净，细节集中在详情弹窗第一层完成，不把 `Research Harness / Dual Verifier / checkpoint diff` 混回主阅读流。
5. 本轮完成标准补充为：
   - 第一层出现可点选的网页/工具动作详情面板；
   - 用户不离开过程层，就能直接看清某一步的网页参数、读取方式与回流动作；
   - 第二层闭环审计材料保持原样，不受这次产品化浏览器升级影响。

## 128. 本轮执行追加（一百一十七）（2026-07-06）
用户进一步明确：当前不需要继续把第一层做成更复杂的“过程回放器”，重点应重新回到 `最终答案质量` 与“当前 agent 相比 reference 框架还差什么”。因此本轮方向切换为：先做面向答案与框架能力的差距审计，再据此决定下一阶段实现优先级。

1. 本轮不再继续扩张第一层过程回放交互：
   - 不新增前后步播放、自动时间线、复杂回放控制；
   - 现有 `调研过程 / 闭环审计` 两层结构保持即可。
2. 评估重心切回当前 agent 的核心完成度：
   - 最终答案是否足够像 GPT / Gemini / Marco 一类 Deep Research 的“可直接交付结果”；
   - 搜索、读取、验证、纠偏、写作链路相比 reference 是否还缺关键框架能力；
   - 哪些差距是真正影响简历亮点和项目完整度的，哪些只是锦上添花。
3. 本轮优先基于现有代码与文档做硬对照，不凭感觉下结论：
   - `workers/research-worker` 的 planner / search / read / verifier / reporter / runner；
   - `backend` 当前 Research Run detail / worker input / checkpoint / summary 返回；
   - `reference/Marco-DeepResearch` 中已经公开的 agent / tool / config / benchmark 组织方式。
4. 结论输出应直接服务后续开发优先级，避免继续在“能展示但不影响完成度”的过程 UI 上消耗时间。
5. 本轮完成标准补充为：
   - 明确指出当前 NoteWeave Research Agent 相比 reference 框架仍缺哪些关键能力；
   - 区分“影响最终答案质量/完整框架完成度”的核心差距与次要差距；
   - 为下一轮实现给出更聚焦的优先方向。
## 129. 本轮执行追加（一百一十八）（2026-07-06）用户已明确接受下一阶段方向：一切继续向“可运行、可展示、可讲亮点”的 Deep Research 成品体验靠齐，但真正需要优先补强的不是过程回放，而是研究结果交付面与 agent 内部能力的可见化表达。因此本轮目标切换为“答案优先的结果层改造 + agent 能力亮点前置呈现”，让系统对外更像 GPT / Gemini / reference 项目中的 Deep Research 最终页，同时对内保留 Closed-Loop Research、Table-as-State、Dual Verifier 与反证分支的真实能力骨架。

1. 本轮先改 `report_structure` 与最终报告写法，而不是继续扩过程回放：
   - 在结果结构中前置 `final_answer`、`executive_summary`、`key_takeaways`、`evidence_highlights`、`uncertainty_and_risks`；
   - 把 `Verified Findings / Evidence Ledger / Closed-Loop State / Conflict / Recovery` 继续保留，但降为支撑层与审计层；
   - 最终 markdown 输出也要改为“先给用户答案，再给证据与审计”，避免继续把 verifier 结构直接暴露成主答案。
2. 本轮需要把 agent 内部亮点变成“可展示能力卡”，而不是停留在代码里：
   - 显式总结本次 run 是否完成了 `Research Harness` 驱动的闭环；
   - 显式总结 `Dual Verifier` 是否放行、是否带 guardrails；
   - 显式总结是否触发过 `反证分支` / counterfactual recheck；
   - 显式总结 `Table-as-State` 当前沉淀了多少 verified / conflicted / blocked 研究行。
3. 前端主界面继续保持干净，但结果展示需要从“审计面板”改成“研究交付页”：
   - 主结果区先展示最终答案摘要、关键结论、证据亮点、风险与下一步；
   - agent 亮点以结果卡方式展示，服务于简历亮点、项目介绍与现场演示；
   - 深层 verifier / checkpoint / conflict row 细节仍放在详情弹窗与第二层审计视图中。
4. 本轮优先复用现有后端运行产物，不为此新增复杂不可演示接口：
   - `verified_findings / evidence_ledger / counterfactual_summary / recovery_status / closed_loop_state / loop_rounds / verifier_decisions`；
   - 在这些已有结构之上派生答案摘要与能力亮点；
   - 保证改动完成后前端能立即消费，形成可运行 demo。
5. 本轮完成标准补充为：
   - 用户打开 Deep Research 主结果区时，第一眼看到的是“答案”和“为什么可信”，而不是 verifier 字段；
   - 用户在演示项目时，可以直接讲清楚该 agent 的闭环验证、状态管理和反证纠偏能力；
   - 现有 `研究详情` 中的过程层与审计层仍可作为第二层材料，不影响本轮结果层产品化升级。
6. 用户进一步收窄展示边界：
   - 主界面结果层重点展示“调研成果 / 报告产物 / 来源基础”；
   - 检索、阅读、网页工具轨迹等“研究过程”继续放在详情弹窗 `调研过程` 层中展示；
   - 不把过程轨迹重新塞回主结果区，避免成果层和过程层混杂。
## 130. 本轮执行追加（一百一十九）（2026-07-06）用户进一步明确当前最需要补的是内部 research 能力，尤其是“外部 web search/read 还偏轻量，工具栈不够厚”这一短板，并要求直接参考 `reference/Marco-DeepResearch/Marco-DeepResearch-Family/Table-as-Search`、`DeepWideSearch` 与 `reference/MiroFlow`。因此本轮目标从结果层展示继续下沉到 research worker 内核，优先补强外部检索、网页读取与受控调度能力，让 Closed-Loop Research 在开放网页研究场景里更像一个真正可运行的 Deep Research agent。

1. 本轮优先关注 search/read 内核，而不是继续扩前端结果展示：
   - 对照 `Table-as-Search` 看“如何把搜索行为结构化为状态驱动研究过程”；
   - 对照 `DeepWideSearch` 看“如何在深挖与广搜之间做受控扩展”；
   - 对照 `MiroFlow` 看“如何把多步网页工具调用组织成更稳定的任务流”。
2. 具体实现目标聚焦在当前 NoteWeave worker 最薄弱的部分：
   - 外部 search provider 不再只有轻量单通道 fallback；
   - 外部 read 不再只停留在简单 `urllib + html strip`；
   - search/read 输出要继续回灌到现有 `Table-as-State / Dual Verifier / 反证分支` 闭环中，而不是做成旁路能力。
3. 本轮改造原则：
   - 优先做“可运行、可接入当前 research loop、能在 demo 中体现能力增强”的实现；
   - 不做需要大规模新基础设施才能成立的方案；
   - 尽量沿用当前 `search_adapters.py / read_adapters.py / loop_runtime.py / reporter.py` 结构扩展。
4. 本轮完成标准补充为：
   - 明确当前从三个参考实现里实际吸收了哪些 search/read/flow 设计；
   - worker 能在外部网页研究场景下提供更强的 search/read 结果质量或调度能力；
   - 新能力仍然被纳入现有 closed-loop research、verifier 和 report 产物中。
## 131. 本轮执行追加（一百二十）（2026-07-06）在 search/read 内核已经开始参考 `Table-as-Search / DeepWideSearch / MiroFlow` 做补强之后，需要立刻补一份面向后续开发、联调和项目讲解的 `Research Agent 执行手册`，把“系统应该怎么跑、每一层做什么、遇到什么情况如何收敛”写成稳定口径，避免后续实现继续散开。

1. 本轮目标不是再写一份泛架构设计，而是形成可执行手册：
   - 说明总 `Research Agent` 的职责边界；
   - 说明 `search / read / extract / verify / branch / report` 的标准执行顺序；
   - 说明哪些设计来自 `Table-as-Search`，哪些约束来自 `DeepWideSearch`，哪些稳健性原则来自 `MiroFlow`。
2. 手册需要直接服务当前工程：
   - 尽量映射到现有 `planner.py / search_adapters.py / read_adapters.py / loop_runtime.py / verifier.py / reporter.py / runner.py`；
   - 说明当前已实现能力与后续待补能力；
   - 说明输入契约、过程资产、正式成果和资料回流的关系。
3. 手册中要明确的关键执行原则包括：
   - `Table-as-State` 不是展示表，而是唯一状态账本；
   - `Dual Verifier` 决定局部修复与全局放行；
   - `反证分支` 只在高风险冲突和路径漂移时受预算触发；
   - `report_structure + final_report_markdown` 是最终双产物契约。
4. 本轮完成标准补充为：
   - 形成一份可单独阅读的 `Research Agent 执行手册`；
   - 能支撑后续 search/read/tool 层继续迭代而不失去统一口径；
   - 能直接用于项目说明、面试讲解和后续开发协同。
## 132. 本轮执行追加（一百二十一）（2026-07-06）用户进一步强调：重点仍然是 `Research Agent` 内部设计本身，包括工具层、调度层和验证层；要求尽可能把 `Table-as-Search / DeepWideSearch / MiroFlow` 这三个参考方向的长处吸收到位，并推动当前 agent 朝“更完整内核”收敛。因此本轮需要在已有执行手册基础上继续下沉，把“内部 tool layer + runtime orchestration + verifier contract”整理成更完整的工程骨架，同时尽量落一层代码，而不是只停留在文档。

1. 本轮关注点继续放在 agent 内核，不回到展示层：
   - 明确当前 `planner / search / read / extract / state / verifier / report` 之间的真实调用边界；
   - 在现有 adapter/transport 基础上抽出更清晰的 internal research tools 口径；
   - 让 runtime 能更像一个完整 research agent，而不是一串松散模块。
2. 参考项目的吸收目标进一步细化为：
   - `Table-as-Search`：吸收显式状态驱动、表格补全式研究组织与 wide/deep 职责分离；
   - `DeepWideSearch`：吸收深宽兼顾的任务观与覆盖/深挖平衡意识；
   - `MiroFlow`：吸收模块化 tool layer、retry/fallback 稳健性与 workflow robustness。
3. 本轮工程落地方向：
   - 补 internal research tools 骨架；
   - 尽量让 loop runtime 通过这层工具语义组织执行；
   - 保持新能力继续回灌 `Table-as-State / Dual Verifier / 反证分支 / 报告双产物契约`。
4. 本轮完成标准补充为：
   - 文档上形成更完整的 agent 内核设计口径；
   - 代码上出现可识别的 internal research tools 骨架；
   - 当前 NoteWeave Research Agent 更接近“完整研究智能体”，而不只是已有模块集合。

## 133. 本轮执行追加（一百二十二）（2026-07-06）在现有 `search_adapters / read_adapters / extractor / verifier / loop_runtime` 已经具备基础能力之后，下一步优先把这些能力正式收束为可编排、可追踪、可恢复的 `internal research tools`。目标不是再叠更多隐藏逻辑，而是把 `Research Harness` 真正变成“通过工具层驱动闭环”的 agent 内核，直接吸收 `Table-as-Search` 的显式状态驱动、`DeepWideSearch` 的受控广搜/深读平衡，以及 `MiroFlow` 的模块化 tool orchestration 思路。

1. 本轮优先新增显式内部工具层，而不是继续让 `loop_runtime` 直接散调底层函数：
   - 把当前能力统一命名为稳定工具语义，例如 `search_web / open_read_windows / extract_evidence / update_state_ledger / verify_local / open_counterfactual_branch / verify_global`；
   - 这层工具语义直接映射当前代码，而不是只写在文档里；
   - 让后续调试、测试、trace、恢复、前端过程展示都能围绕同一套工具口径展开。
2. `loop_runtime` 本轮应改为“调用工具层完成一轮 research round”，而不是自己同时承担：
   - recovery mode 判断；
   - search/read 的复用与合并；
   - extract/state/verifier/branch/global verifier 的顺序编排；
   - 工具执行过程记录。
3. 本轮要补最小但正式的 `tool traces / tool journal`：
   - 每轮至少记录工具名、执行状态、输入摘要、输出摘要与 recovery mode；
   - 这些 trace 先沉淀在 worker result/checkpoint 中，作为后续过程展示和审计的真源；
   - 不要求现在就把它做成复杂 UI，但必须先在 agent 内部成为正式产物。
4. 本轮继续坚持现有主骨架不变：
   - `Table-as-State` 仍是唯一状态账本；
   - `Dual Verifier` 仍控制局部修复与最终放行；
   - `反证分支` 仍只在冲突或验证缺口时受预算触发；
   - 新工具层只是把这些能力收束成更完整的 runtime 内核，而不是另起一套旁路系统。
5. 本轮完成标准补充为：
   - 新增正式的 `internal research tools` 代码骨架；
   - `loop_runtime` 明确通过工具层执行 research round；
   - `result_payload / checkpoint` 中出现可消费的 `tool_traces` 或等价执行日志；
   - 编译与最小测试通过，证明这一层不是文档概念而是可运行能力。

## 134. 本轮执行追加（一百二十三）（2026-07-06）用户再次明确：展示层最后统一参考 GPT / Gemini / MiroThinker 收口，当前阶段继续只补 agent 内核。因此本轮继续沿内部能力主线推进，把“来源质量分层 + wide/deep 读取策略”正式接入 `search -> read -> state -> verifier` 主链路，吸收 `DeepWideSearch` 的宽搜/深读平衡意识，以及 `Table-as-Search` 中“行与来源类型不是同一层”的研究组织思路。

1. 当前核心缺口之一是来源语义还太薄：
   - `ResearchSearchHit` 虽然已有 `provider / adapter / coverage_score / url`，但还没有正式的 `source_domain / source_quality / search_lane`；
   - `ResearchReadWindow` 也还没有正式的 `read_strategy / source_quality`；
   - `ResearchStateRow` 仍把 `source_quality` 近似写死为 `WORKSPACE_SOURCE`，不足以支撑真正的 verifier-gated research。
2. 本轮目标是补一层正式的来源画像与读取策略，而不是只加展示字段：
   - 对 workspace / official docs / government / academic / repo / secondary / general web 等来源做最小质量分层；
   - 给每个 search hit 标记 `wide discovery / deep focus / counterfactual` 等 lane；
   - 给每个 read window 标记 `wide coverage read / balanced read / deep evidence read / counterfactual deep read` 等策略。
3. 这些新字段必须继续回灌主闭环：
   - state ledger 行和 cell 看到的来源质量要与 search/read 真源同源；
   - tool traces 要能统计本轮用了哪些来源层级与读取策略；
   - 后续 verifier/report 才能基于这套真源做“为什么这份研究更稳”的解释。
4. 本轮仍不扩展示层，不新增大 UI：
   - 重点是让后续产物、过程 trace、checkpoint 和报告都已经具备这套内部能力字段；
   - 等主内核补得足够完整后，再统一用 GPT / Gemini / MiroThinker 风格去展示。
5. 本轮完成标准补充为：
   - 代码里出现正式的来源画像 / 读取策略工具或 helper；
   - `search_hit / read_window / state_row / tool_traces` 至少四层能看到同源字段；
   - 编译与相关测试通过，证明这层不是静态注释而是运行中的 agent 能力。

## 135. 总交付执行文档（2026-07-06）
用户已明确纠偏：当前需要的不是继续按“这一轮、下一轮”拆小目标，而是先形成一份从“当前已实现状态”到“Research Agent 全部完成并可交付”的总执行文档，作为后续统一施工依据。该文档应服务于整个 `Deep Research Agent` 的完工交付，而不是只服务某一轮增量开发。

1. 这份总文档的定位：
   - 不是再写一份泛架构手册；
   - 也不是只列一些 TODO；
   - 而是明确“当前有什么、还差什么、先做什么、后做什么、做到什么算交付完成”。
2. 文档约束继续保持：
   - 参考 `Table-as-Search / DeepWideSearch / MiroFlow` 的长处；
   - 吸收其显式状态、wide/deep 平衡、模块化工具层、稳健工作流；
   - 但不把系统做成过度复杂的大型多 Agent 网络。
3. 总执行文档应覆盖至少五层：
   - `worker 内核`：planner / tools / search / read / extract / verifier / branch / report；
   - `结果与契约`：report_structure / markdown / checkpoint / callback / detail API；
   - `主系统接入`：Java 持久化、查询、恢复、写回；
   - `产品交付层`：研究结果、来源基础、过程详情、闭环审计；
   - `最终演示与验收`：像 GPT / Gemini / MiroThinker 的产品收口放在最后。
4. 文档还应明确：
   - 当前已经完成到什么程度；
   - 剩余工作按依赖顺序如何推进；
   - 哪些是必须做完才算“agent 全部完成交付”，哪些是可延后优化项。
5. 本项完成标准：
   - 形成一份单独可读的总交付执行文档；
   - 后续实现统一以该文档为依据推进；
   - 文档能直接支撑开发、联调、演示和简历/项目讲解。

## 136. Phase A 执行启动（2026-07-06）
按照 [深度研究智能体全量交付执行文档](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 的顺序，当前正式进入 `Phase A. 补齐 Worker 内核`。本阶段不再泛化扩展范围，而是先抓当前最影响交付质量的两个内核缺口：`Verifier 的来源质量门控` 与 `Reporter 的来源基础表达`，并用 TDD 推进。

1. 本阶段首个子目标聚焦：
   - `verifier.py` 不再只看 hit/read/evidence/row 数量，还要开始消费 `source_quality / read_strategy`；
   - `reporter.py` 不再只说“有几个来源”，还要说明“来源基础由什么层级构成、是否偏低信任、是否主要依赖深读窗口”。
2. TDD 约束：
   - 先补失败测试，明确低质量来源必须触发 guardrail 或 warning；
   - 先补失败测试，明确报告结构与 markdown 要能产出正式 `source foundation` 摘要；
   - 再补实现，不走“先改一堆逻辑再猜测试”的路径。
3. 本子目标完成标准：
   - 低信任来源研究路径能被 local/global verifier 感知；
   - `report_structure` 中出现正式来源基础摘要对象；
   - markdown 结果层能直接说清当前研究的来源基础，而不是只列 source title；
   - 测试与编译通过。

## 137. Phase A 子目标（二）（2026-07-06）
在 `Verifier 来源质量门控` 与 `Reporter 来源基础表达` 已经落地之后，下一步回到 [深度研究智能体全量交付执行文档](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 中 `Phase A` 的第一项缺口：完善 `Planner` 的 wide/deep 策略。本子目标仍坚持轻量吸收参考项目长处，不把 planner 做成重型策略系统，而是先把 `depth` 从“预算标签”升级为“显式执行画像”。

1. 本子目标聚焦：
   - `planner.py` 需要产出正式 `execution_profile`；
   - `depth=QUICK / STANDARD / DEEP` 需要真正影响 query 组织，而不只是影响 search_limit / loop_rounds；
   - `runner.py` 的 planning payload 需要把这套执行画像透出，便于后续审计与展示。
2. TDD 约束：
   - 先补失败测试，明确不同 depth 必须产出不同 execution profile；
   - 先补失败测试，明确 `QUICK` 不应默认展开 coverage/counterfactual，`DEEP` 应具备更强的 wide/deep 查询组织；
   - 先补失败测试，明确 planning payload / stop_contract 中可以读到执行画像。
3. 本子目标完成标准：
   - `planner` 的 depth 策略成为正式对象；
   - query_set 与 planning payload 明确体现 wide/deep 执行差异；
   - 相关测试与编译通过。

## 138. 总交付文档升级（二）（2026-07-06）
用户再次明确：这次不要继续围绕“当前这一轮要做什么”写增量计划，而是直接产出一份从“当前真实实现状态”到“全部完成交付”的完整规划文档，用作后续统一施工蓝图。因此本轮先升级 [深度研究智能体全量交付执行文档](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 本身，把它从总纲提升为更具体的工程交付手册。

1. 本轮文档升级目标：
   - 明确当前基线，不再只写抽象目标；
   - 明确从现在到交付完成的阶段拆分、依赖顺序与验收口径；
   - 明确 `Table-as-Search / DeepWideSearch / MiroFlow` 三个参考项目分别被吸收到哪一层能力中。
2. 文档必须补齐的关键信息：
   - 当前已经做到了什么，哪些能力只是“第一版可跑”；
   - 哪些是必须完成的交付项，哪些是可延后的优化项；
   - 每个阶段的主目标、关键文件、验收标准和演示价值。
3. 文档口径继续保持：
   - `Deep Research` 是工作台内独立功能，不依赖聊天 / Note / Wiki 上下文升级；
   - 重点仍是 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支`；
   - 展示层继续放在最后，先把 agent 内核和正式成果链路规划完整。
4. 本项完成标准：
   - 形成一份可以单独阅读的“从当前到交付完成”的完整规划文档；
   - 后续阶段实现可以直接按该文档逐段推进；
   - 文档既能指导开发，也能支持项目讲解与简历亮点表达。

## 139. Phase A 子目标（三）执行启动（2026-07-06）
在总交付执行文档已经升级为正式施工蓝图之后，当前开始严格按其 `Phase A` 顺序进入 `Planner wide/deep execution profile` 的 TDD 实现。本子目标不扩散到前端和 Java 契约，而是先把 `depth` 从预算标签补成 Research Harness 中可消费、可追踪、可展示的正式执行画像。

1. 本子目标要先解决的真实缺口：
   - `planner.py` 目前虽然会根据 depth 调整部分搜索规模，但还没有正式 `execution_profile`；
   - `QUICK / STANDARD / DEEP` 对 query 组织的差异还不够显式；
   - `runner.py` 的 planning payload 还没有把这套 wide/deep 画像透出给后续 trace、审计和展示层。
2. 本轮执行方式继续坚持 TDD：
   - 先审查 `planner / runner / tests` 当前状态；
   - 先补失败测试，明确不同 depth 的 query strategy 和 execution profile 差异；
   - 再补实现，最后跑 focused tests 验证。
3. 本子目标吸收参考项目的方式：
   - 从 `DeepWideSearch` 吸收 wide/deep 平衡意识；
   - 从 `Table-as-Search` 吸收围绕 coverage gap 和定向深读组织搜索的思路；
   - 从 `MiroFlow` 吸收把执行画像显式透出给 runtime trace 的工程口径。
4. 本项完成标准：
   - `planner` 产出正式 `execution_profile`；
   - `query_set` 能清楚体现 `QUICK / STANDARD / DEEP` 的 wide/deep 差异；
   - planning payload / stop contract 中能读取 execution profile；
   - 相关测试与编译通过。

## 140. Phase A 子目标（三）完成记录（2026-07-06）
`Planner wide/deep execution profile` 已按 TDD 落地并验证通过。本次改动把 `depth` 从原先主要影响预算参数的隐式标签，升级成了 `planner -> runner -> result payload` 全链路可见的正式执行画像，同时保持了现有 query 顺序约束不被破坏。

1. 已完成的代码能力：
   - `planner.py` 新增正式 `execution_profile`，至少包含：
     - `planning_mode`
     - `web_search_posture`
     - `wide_search_enabled`
     - `deep_focus_query_enabled`
     - `coverage_gap_query_enabled`
     - `counterfactual_query_enabled`
     - `source_scope_bias`
     - `search_angles / query_family_order`
   - `QUICK / STANDARD / DEEP` 现在会真正影响 query 组织，而不只是影响 `global_search_limit / max_loop_rounds`。
   - `DEEP` 新增更显式的 `deep focus / triangulation` 查询，并保持 `coverage gap check` 与 `counterfactual evidence check` 仍位于尾部，兼容已有 search adapter 顺序依赖。
   - `runner.py` 的 `PLANNING` phase payload 已透出 `execution_profile`，结果 payload 中的 `stop_contract` 也可直接读取。
2. 本次 TDD 验证结果：
   - 新增 `test_build_research_plan_should_surface_execution_profile_by_depth`
   - 新增 `test_run_research_task_should_emit_execution_profile_in_stop_contract`
   - 更新现有 planning / payload 测试断言
   - `python -m pytest workers/research-worker/tests/test_runner.py -q` 通过
   - `python -m pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py -q` 通过，共 `54 passed`
3. 本子目标的交付意义：
   - 吸收了 `DeepWideSearch` 的 wide/deep 平衡意识；
   - 吸收了 `Table-as-Search` 围绕 coverage gap 和定向深读组织查询的思路；
   - 也让后续 trace / verifier / 展示层有了正式可消费的 execution profile 真源。
4. 下一优先子目标建议回到 `Phase A` 主线中的 search/read 工具栈补强：
   - 优先把外部 `web search / read / fetch` 的能力层继续做厚；
   - 并保证新工具输出继续回灌 `Table-as-State / Dual Verifier / 反证分支` 主闭环。

## 141. Phase A 子目标（四）执行启动（2026-07-06）
在 `Planner wide/deep execution profile` 已经完成之后，当前继续按总交付文档的 `Phase A` 主线推进外部 `web search / read / fetch` 工具层补强。这一轮不去扩前端，也不先碰 Java 契约，而是优先把 worker 在开放网页研究场景下的外部读取与页面抓取能力做得更像真正可运行的 `Deep Research`。

1. 当前真实缺口：
   - `search_adapters.py` 已有 external provider chain，但“搜到之后如何继续抓、如何解释抓取结果”还偏轻量；
   - `read_adapters.py` 虽已有 `Jina snapshot + HTTP fallback`，但还缺更明确的 `fetch` 语义与抓取状态输出；
   - `research_tools.py` 目前能记录 search/read traces，但还没有把 fetch 当成正式可追踪步骤。
2. 本轮优先目标：
   - 先把外部页面抓取抽成正式 `fetch` 能力，而不是继续把所有外部读取都混在 read 中；
   - 让 fetch 结果至少能稳定输出抓取来源、抓取方式、正文可用性与失败回退原因；
   - 继续保证这些结果会回灌到 `read_windows / tool_traces / result payload`。
3. 参考项目吸收方式：
   - 从 `MiroFlow` 吸收模块化 tool orchestration 和稳健 fallback；
   - 从 `Table-as-Search` 吸收“search -> visit -> structured state update”的研究动作拆分；
   - 从 `DeepWideSearch` 吸收“外部搜索只是入口，后续深读抓取才是研究有效推进”的意识。
4. 本轮执行方式继续坚持 TDD：
   - 先审查当前 `search_adapters / read_adapters / research_tools` 与 tests；
   - 先补失败测试，明确 fetch 结果契约与 fallback 行为；
   - 再补实现，最后跑 focused tests 和回归测试。
5. 本项完成标准：
   - 代码里出现正式、可调用、可追踪的外部 `fetch` 语义；
   - `read_windows / tool_traces / result payload` 至少三层能读到 fetch 结果；
   - 测试验证外部抓取成功路径和 fallback 路径都成立；
   - 新能力继续回灌 `Closed-Loop Research` 主闭环，而不是做成旁路。

## 142. Phase A 子目标（四）完成记录（2026-07-06）
外部 `web search / read / fetch` 工具层的这一轮补强已按 TDD 落地。重点不是再加一个隐藏 transport，而是把“外部页面抓取”正式做成可消费、可追踪、可解释的 `fetch` 语义，并继续回灌到现有 `read_windows / tool_traces / result payload` 主链路。

1. 本次已完成的代码能力：
   - `ResearchReadWindow` 新增正式抓取字段：
     - `fetch_status`
     - `fetch_method`
     - `content_origin`
     - `fetch_error_reason`
     - `fetch_attempts`
   - `WorkspaceReadAdapter` 与 `UrlReadAdapter` 现在都会显式填写 fetch 元数据，而不再只靠 `snapshot_status` 隐式表达。
   - `HttpUrlSnapshotTransport / JinaUrlSnapshotTransport / CompositeUrlSnapshotTransport` 现在在成功和失败路径上都能输出正式 fetch 结果描述，而不是失败时只返回空对象。
   - `research_tools.py` 的 `open_read_windows` trace 现已汇总：
     - `fetch_status_summary`
     - `fetch_method_summary`
     - `content_origin_summary`
   - `runner.py` 的 `READING` phase payload 现已透出：
     - `fetch_statuses`
     - `fetch_methods`
     - 每个 read source sample 的 fetch 元数据
2. 本次 TDD 覆盖点：
   - 外部 URL 无 transport 时的 fallback fetch 路径；
   - 外部 URL 抓取成功时的 fetch 元数据保留；
   - transport 失败但仍 fallback 到 snippet 时的失败原因透出；
   - `TOOL_OPEN_READ_WINDOWS` trace 中的 fetch 汇总输出；
   - `READING` phase payload 中的 fetch 状态与方式输出。
3. 验证结果：
   - `python -m pytest workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_runner.py -q` 通过
   - `python -m pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py -q` 通过，共 `55 passed`
4. 本子目标的交付意义：
   - 吸收了 `MiroFlow` 的工具层稳健 fallback 和可追踪执行口径；
   - 也把 `Table-as-Search` 式的 `search -> visit/fetch -> structured state` 拆分得更清楚；
   - 后续无论是 verifier、checkpoint 还是前端过程展示，都已经有 fetch 真源可读，而不只是看 `snapshot_status` 猜测。
5. 下一优先子目标建议：
   - 继续补强 search/read 外部能力，但下一步更适合切到“多 transport / provider 的受控编排与更细的 fallback 策略”；
   - 同时让 verifier/report 更明确消费这层 fetch 能力，解释“哪些网页真正抓到了正文，哪些只是 fallback snippet”。

## 143. Phase A 子目标（五）执行启动（2026-07-06）
在外部 `fetch` 语义已经正式进入 `read_windows / tool_traces / result payload` 之后，当前继续按总交付文档推进 `Phase A`，把这层能力真正接入 `verifier / reporter`。目标不是只把抓取字段存下来，而是让系统开始区分“正文抓取成立的来源基础”和“仅 snippet fallback 的弱来源基础”，让闭环结论更像可解释的 Deep Research。

1. 当前真实缺口：
   - `fetch_status / fetch_method / content_origin` 已经进入 read 层，但 `verifier.py` 还没有把它们正式当作质量门控信号；
   - `reporter.py` 目前虽能输出 `source_foundation`，但还不能明确说明本次研究里有多少来源是真正抓到正文、多少只是 fallback snippet；
   - 因而当前系统仍较难解释“为什么这份研究更稳”或“哪些外部来源只是弱证据入口”。
2. 本轮优先目标：
   - 让 `local/global verifier` 对 `fetch_status / content_origin` 建立最小门控；
   - 让 `report_structure` 和 markdown 报告显式呈现 fetch-aware 的来源基础摘要；
   - 保证这些结论继续回灌到现有 `Closed-Loop Research` 主闭环，而不是独立旁路说明。
3. 参考项目吸收方式：
   - 从 `MiroFlow` 吸收“工具结果不止用于执行，也用于解释系统为什么相信当前结果”的口径；
   - 从 `DeepWideSearch` 吸收“宽搜入口和深读正文不是同一层证据强度”的意识；
   - 从 `Table-as-Search` 吸收“状态表与最终报告要同源表达研究覆盖与证据基础”的思路。
4. 本轮执行方式继续坚持 TDD：
   - 先审查 `verifier / reporter / tests` 的现状；
   - 先补失败测试，明确 fallback-heavy 研究路径必须触发 warning 或 guarded summary；
   - 再补实现，并跑 focused tests 与回归测试。
5. 本项完成标准：
   - verifier 能感知 fetch 质量差异；
   - report_structure / markdown 能明确表达 fetched vs fallback 的来源基础；
   - 测试验证 fetch-heavy 与 fallback-heavy 两类路径的差异行为；
   - 新能力继续服务 `Research Harness / Closed-Loop Research` 主链路。

## 144. Phase A 子目标（五）完成记录（2026-07-06）
`fetch-aware verifier / reporter` 已按 TDD 落地完成。当前系统不再只是把 `fetch_status` 存在 read window 里，而是已经开始把“抓到了正文”与“只保留 snippet fallback”当作正式研究质量信号消费，并反映到 verifier 结论和最终报告里。

1. 本次已完成的代码能力：
   - `verifier.py` 新增对外部抓取质量的最小门控：
     - 当当前已验证路径主要依赖低信任外部来源，且外部 read windows 全是 fallback snippet、没有正文抓取时；
     - local verifier 会给出正式 warning；
     - 并记录 `EXTERNAL_FETCH_FALLBACK_HEAVY` 的 decision record。
   - `reporter.py` 的 `source_foundation` 现已新增 fetch-aware 摘要字段，至少包括：
     - `fetch_status_counts`
     - `fetch_method_counts`
     - `external_window_count`
     - `fetched_window_count`
     - `fallback_window_count`
     - `fetch_mix_label`
     - `fetch_foundation_label`
   - markdown 报告的 `## Source Basis` 段现在会在存在外部页面读取时显式写出：
     - `Fetch foundation`
     - `Fetch mix`
     - `Fetched external windows`
     - `Fallback external windows`
2. 本次 TDD 覆盖点：
   - `fallback-only` 外部弱来源路径触发 verifier warning；
   - `decision_records` 中出现 `EXTERNAL_FETCH_FALLBACK_HEAVY`；
   - `source_foundation` 中出现 fetched/fallback 外部窗口统计；
   - markdown 报告中出现 fetch-aware 来源基础描述。
3. 验证结果：
   - `python -m pytest workers/research-worker/tests/test_llm_extract_verify.py -q` 通过
   - `python -m pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py -q` 通过，共 `57 passed`
4. 本子目标的交付意义：
   - 吸收了 `MiroFlow` “工具结果本身也要成为解释信号”的工程口径；
   - 吸收了 `DeepWideSearch` “搜索入口和深读正文不是同一层证据强度”的意识；
   - 也让 NoteWeave 的 `Research Harness / Closed-Loop Research` 更接近真正可解释的 Deep Research，而不是只会给出一份长报告。
5. 下一优先子目标建议：
   - 继续留在 `Phase A`，把多 provider / multi-transport 的受控编排和 fallback 策略再补厚一层；
   - 或者开始把 fetch-aware 信号进一步喂给 `global verifier / report final_answer confidence`，让最终答案层也能区分“正文级稳定来源”和“snippet 级弱来源”。

## 145. Phase A 子目标（六）执行启动（2026-07-06）
在 `fetch-aware verifier / reporter` 已经完成之后，当前继续按总交付文档推进 `Phase A`，把 fetch-aware 信号再往上接到 `global verifier / final_answer confidence`。目标不是只在 warning 和 source foundation 里解释来源强弱，而是让最终答案层也开始区分“正文抓取支撑的稳定结论”和“fallback snippet 支撑的弱结论”。

1. 当前真实缺口：
   - `local verifier` 已经能对 fallback-heavy 外部路径给出 warning；
   - `reporter.source_foundation` 也已经能解释 fetched vs fallback 的来源基础；
   - 但 `global verifier` 的 `completion_score / decision` 以及 `final_answer.confidence_label / source_basis` 还没有显式消费这层 fetch 质量信号。
2. 本轮优先目标：
   - 让 `global verifier` 在 fallback-heavy、缺少 fetched body 的外部路径下更倾向 `WRITE_WITH_GUARDRAILS`；
   - 让 `report_structure.final_answer` 的 `confidence_label / source_basis` 更明确表达 fetched-grounded 与 fallback-grounded 差异；
   - 保持这些变化继续回灌闭环，而不是只做展示层文案。
3. 参考项目吸收方式：
   - 从 `DeepWideSearch` 吸收“结论层置信度应反映深读证据强度，而不是只看是否搜到了结果”的意识；
   - 从 `MiroFlow` 吸收“工具执行质量要继续上浮到最终结论层”的工程口径；
   - 从 `Table-as-Search` 吸收“状态、验证与最终结果表达必须同源”的研究组织方式。
4. 本轮执行方式继续坚持 TDD：
   - 先审查 `global verifier / final answer / tests`；
   - 先补失败测试，明确 fetched-heavy 与 fallback-heavy 的最终答案行为差异；
   - 再补实现，并跑 focused tests 与回归测试。
5. 本项完成标准：
   - `global verifier` 能更正式地感知 fetch 质量差异；
   - `final_answer` 的 `confidence_label / source_basis` 能表达 fetched vs fallback 差异；
   - 测试覆盖 fetched-heavy 与 fallback-heavy 路径；
   - 新能力继续服务 `Research Harness / Closed-Loop Research` 主链路。

## 146. Phase A 子目标（六）完成记录（2026-07-06）
`fetch-aware global verifier / final answer` 已按 TDD 落地完成。当前 fetch 质量信号不再只停留在 local warning 和 source foundation 摘要里，而是已经继续上浮到 `global verifier.completion_score` 与 `final_answer` 的正式表达层。

1. 本次已完成的代码能力：
   - `verifier.py` 中 `run_global_verifier` 现已对 `EXTERNAL_FETCH_FALLBACK_HEAVY` 施加正式 penalty，影响 `completion_score`；
   - 因而 fetched-heavy 与 fallback-heavy 外部路径，即使 coverage 类似，也会在全局完成度上拉开差异；
   - `reporter.py` 中 `final_answer` 现已消费 `source_foundation`：
     - `confidence_label` 会在 guarded 路径下显式提示 fallback snippet 正在承载答案路径；
     - `source_basis` 会区分：
       - `Fetched-external grounded`
       - `Fallback-snippet grounded`
       - 以及原有的 official/government/academic/general-web grounded 等表达。
2. 本次 TDD 覆盖点：
   - 对比 fetched-heavy 与 fallback-heavy 两条路径的 `global_result.completion_score`；
   - 对比最终 `final_answer.source_basis / confidence_label` 是否显式体现 fallback 差异；
   - 保持现有 report writer、source foundation、runner 等路径不被破坏。
3. 验证结果：
   - `python -m pytest workers/research-worker/tests/test_llm_extract_verify.py -q` 通过
   - `python -m pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py -q` 通过，共 `59 passed`
   - `python -m py_compile workers/research-worker/app/verifier.py workers/research-worker/app/reporter.py` 通过
4. 本子目标的交付意义：
   - NoteWeave 的 Deep Research 现在不仅能解释“来源基础是什么”，还能把这层差异真正体现在最终答案强弱上；
   - 这使得 `Research Harness -> Dual Verifier -> Final Answer` 的链路更像可运行、可解释、可展示的 Deep Research agent，而不只是内部审计做得更细。
5. 下一优先子目标建议：
   - 继续留在 `Phase A`，优先补多 provider / multi-transport 的受控编排与 fallback 策略；
   - 或者继续完善 `checkpoint / recovery / resume` 的同源性，让这套 fetch-aware 能力也稳定进入恢复链路。

## 147. Phase A 子目标（七）执行启动（2026-07-06）
在 `fetch-aware global verifier / final answer` 已经落地之后，当前继续按总交付文档推进 `Phase A`，补齐多 provider / multi-transport 的受控编排与 fallback 可观测性。目标不是单纯“支持更多 provider”，而是让 `Research Harness` 在外部 search/read 失败或降级时，能正式记录“尝试过什么、最终退到了哪一层、为什么退回去”，从而让后续 verifier、checkpoint 和演示层都有真源可读。

1. 当前真实缺口：
   - `search_adapters.py` 虽已支持 provider chain，但命中结果里还缺少对 provider fallback 过程的正式表达；
   - `read_adapters.py` 已有 fetch attempts，但 transport 级退化过程还没有统一的 orchestration 摘要对象；
   - `tool_traces` 目前能看到结果汇总，却还不够清楚地表达“这一轮到底用了哪条 provider/transport 链路”。
2. 本轮优先目标：
   - 为 search/read 结果增加最小但正式的 orchestration 元数据；
   - 让 provider / transport fallback 过程进入 `search_hits / read_windows / tool_traces / result payload`；
   - 保证多 provider 与多 transport 的退化不是隐藏行为，而是可追踪闭环对象。
3. 参考项目吸收方式：
   - 从 `MiroFlow` 吸收模块化 orchestration 和 fallback robustness；
   - 从 `Table-as-Search` 吸收 `search -> visit -> state` 每一步都应有显式状态表达的思路；
   - 从 `DeepWideSearch` 吸收“开放网页研究里的失败与退化本身也是研究成本和质量信号”的意识。
4. 本轮执行方式继续坚持 TDD：
   - 先审查 `search_adapters / read_adapters / tests`；
   - 先补失败测试，明确 provider/transport fallback 元数据必须可见；
   - 再补实现，并跑 focused tests 与回归测试。
5. 本项完成标准：
   - search/read 结果中出现正式 orchestration 元数据；
   - tool traces 和 result payload 能看出 provider/transport fallback 过程；
   - 测试覆盖成功路径与 fallback 路径；
   - 新能力继续服务 `Research Harness / Closed-Loop Research` 主链路。

## 148. 全量交付规划文档重构（2026-07-06）
本次不继续拆“这一轮做什么”，而是先把从当前状态到“全部完成并可交付”的总路线图一次性写清。目标是把 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 重构成真正的主执行文档，后续阶段推进、排期、验收、演示都统一对齐这份文档。

1. 本次文档重构要解决的问题：
   - 现有总文档已经有方向，但还偏“阶段说明”和“能力清单”；
   - 还不够像一份从当前真实基线出发、一路走到最终交付的执行蓝图；
   - 还需要把“什么已完成、什么未完成、先做什么、最后交付长什么样”讲得更明确。
2. 本次重构后的文档应具备：
   - 明确的当前基线与完成定义；
   - 明确的参考项目吸收矩阵；
   - 明确的阶段里程碑、依赖顺序与验收口径；
   - 明确的“先内核、后系统、再产物、最后展示层”执行顺序。
3. 本次文档重构继续遵守既有产品口径：
   - `Deep Research` 是独立功能；
   - 任务入口只来自用户显式输入；
   - 工作台负责承载 run、详情、历史和研究产物回流，而不是研究入口上下文。
4. 本次文档重构不做的事：
   - 不把后续工作重新拆回“只看这一轮”的局部目标；
   - 不为了显得复杂去引入重型多 agent 平台方案；
   - 不提前把展示层当成主推进对象。
5. 本次重构完成标准：
   - 新总文档可以直接作为从现在到全量完成的执行母版；
   - 后续每次实现都能从文档中定位所属阶段、目标和验收标准；
   - 文档内容与当前已实现基线保持一致，不虚报进度、不提前宣称完成。

## 149. Phase A 子目标（七）正式执行：多 provider / multi-transport 编排可观测性（2026-07-06）
根据 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 中 `Phase A` 的优先顺序，当前正式进入“多 provider / multi-transport orchestration 元数据”实现。目标不是继续堆 provider 数量，而是让 `Research Harness` 能把外部检索与外部读取的退化链路正式暴露成可验证、可审计、可展示的闭环对象。

1. 当前确认的真实缺口：
   - `ResearchSearchHit` 还没有正式表达 provider 尝试链、最终命中 provider 与 fallback 原因；
   - `ResearchReadWindow` 虽已有 `fetch_attempts`，但还没有更稳定的 transport orchestration 摘要；
   - `research_tools / runner` 里虽然有 search/read 汇总，但还不够直接地表达“本轮到底走了哪条 provider/transport 链路”。
2. 本次优先交付目标：
   - 为 `search_hits` 增加正式 provider orchestration 元数据；
   - 为 `read_windows` 增加正式 transport orchestration 元数据；
   - 让这两层元数据回灌到 `tool_traces` 和 `result_payload`；
   - 保持能力服务 `Research Harness / Closed-Loop Research` 主链路，而不是只做日志说明。
3. 本次实现方式继续坚持 TDD：
   - 先补失败测试，明确 provider fallback 与 transport fallback 元数据必须可见；
   - 再补 `models / search_adapters / read_adapters / research_tools / runner` 实现；
   - 最后跑 focused tests 与回归测试。
4. 本次完成标准：
   - `ResearchSearchHit` 和 `ResearchReadWindow` 出现正式 orchestration 字段；
   - `TOOL_SEARCH_WEB / TOOL_OPEN_READ_WINDOWS` trace 能看出链路退化过程；
   - `result_payload.search_hits / read_windows` 仍能直接消费这些字段；
   - 测试覆盖成功路径与 fallback 路径，并保持现有回归通过。

## 150. Phase A 子目标（七）完成记录（2026-07-06）
`多 provider / multi-transport 编排可观测性` 已按 TDD 落地完成。当前 `Research Harness` 不再只暴露“搜到了什么、抓到了什么”，而是已经开始正式暴露“尝试过哪些 provider / transport、最后落到了哪一层、前面为什么失败”，这让 search/read 的退化过程第一次成为可消费的闭环对象。

1. 本次已完成的代码能力：
   - `ResearchSearchHit` 新增正式 provider orchestration 字段：
     - `provider_attempts`
     - `provider_resolution`
     - `provider_fallback_reason`
   - `ResearchReadWindow` 新增正式 transport orchestration 字段：
     - `transport_chain`
     - `transport_resolution`
     - `transport_fallback_reason`
   - `CompositeSearchAdapter` 现在会为外部命中补齐 provider 尝试链与 fallback 说明；
   - `CompositeUrlSnapshotTransport` 现在会把 transport 退化链路规范化输出，而不是只留下 `fetch_attempts`；
   - `research_tools.py` 现在会在：
     - `TOOL_SEARCH_WEB`
     - `TOOL_OPEN_READ_WINDOWS`
     的 trace 里输出 orchestration 汇总；
   - `runner.py` 现在会把这层元数据继续透出到 phase payload 的 `SEARCHING / READING` 结果里。
2. 本次 TDD 覆盖点：
   - 外部搜索 provider fallback 命中时，结果对象能看到完整 `provider_attempts`；
   - composite transport 从失败到成功恢复时，读取窗口能看到完整 `transport_chain`；
   - `tool_traces` 能看到 `provider_resolution_summary / provider_attempt_chain_summary / transport_resolution_summary / transport_attempt_chain_summary`；
   - `runner` phase payload 能看到 `provider_resolutions / transport_resolutions`。
3. 验证结果：
   - `python -m pytest workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_runner.py -q` 通过，共 `41 passed`
   - `python -m pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py -q` 通过，共 `61 passed`
   - `python -m py_compile workers/research-worker/app/models.py workers/research-worker/app/search_adapters.py workers/research-worker/app/read_adapters.py workers/research-worker/app/research_tools.py workers/research-worker/app/runner.py` 通过
4. 本子目标的交付意义：
   - 吸收了 `MiroFlow` 在工具编排与 fallback robustness 上的长处；
   - 也把 `Table-as-Search` 的“每一步都应有显式状态表达”继续推进到 search/read 层；
   - 同时把 `DeepWideSearch` 里“开放网页研究中的失败与退化本身也是质量信号”的意识落到了正式对象上。
5. 下一优先子目标建议：
   - 继续留在 `Phase A`，把这层 orchestration 元数据进一步喂给更强的 `Dual Verifier` 门控；
   - 或者开始补 `checkpoint / recovery / resume` 的同源闭环，让这些外部 research 轨迹能稳定进入恢复链路。

## 151. Phase A 子目标（八）执行启动：orchestration-aware Dual Verifier（2026-07-06）
在 `search / read` 已经具备正式 orchestration 元数据之后，当前继续按 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 的 `Phase A` 推进，把这层“provider / transport 退化链路”真正喂给 `Dual Verifier` 和 `reporter`。目标不是多加几条提示文案，而是让系统正式区分“主链路稳定拿到的证据”和“通过 fallback 链硬救回来的证据”。

1. 当前确认的真实缺口：
   - orchestration 元数据已经存在于 `search_hits / read_windows / tool_traces / phase payload`；
   - 但 `verifier.py` 还没有正式把 provider fallback 或 transport fallback 当成质量门控信号；
   - `reporter.py` 也还没有把这层外部 research 编排基础提炼成正式来源基础摘要。
2. 本次优先交付目标：
   - 为 `local/global verifier` 增加 orchestration-aware 质量门控；
   - 为 `source_foundation / final_answer / markdown report` 增加 provider/transport fallback 基础表达；
   - 保证这些信号继续回到 `Closed-Loop Research` 主链路，而不是只做展示性说明。
3. 本次实现方式继续坚持 TDD：
   - 先补失败测试，明确 provider fallback 和 transport fallback 必须影响 verifier 或 report；
   - 再补 `verifier / reporter` 实现；
   - 最后跑 focused tests 与回归测试。
4. 本次完成标准：
   - `decision_records` 中出现正式 orchestration-aware reason code；
   - `global verifier` 能对这类退化路径施加 completion penalty；
   - `source_foundation / final_answer / markdown` 能解释 provider/transport fallback 基础；
   - 测试覆盖局部 verifier、全局 verifier、reporter 三层，并保持回归通过。

## 152. Phase A 子目标（八）完成记录（2026-07-06）
`orchestration-aware Dual Verifier` 已按 TDD 落地完成。当前 `Deep Research` 不再只是“看见了 provider / transport fallback”，而是已经开始正式把这些退化链路当成研究质量信号消费，并反映到 verifier 决策、来源基础摘要和最终答案表达里。

1. 本次已完成的代码能力：
   - `verifier.py` 新增两类正式 orchestration-aware 门控：
     - `EXTERNAL_PROVIDER_FALLBACK_HEAVY`
     - `EXTERNAL_TRANSPORT_FALLBACK_HEAVY`
   - `local verifier` 现在会在低信任外部路径完全依赖 fallback provider 或 fallback transport 时给出正式 warning 与 recovery action；
   - `global verifier` 现在会对这些退化路径施加 completion penalty，而不是只对 snippet fallback 路径做降权；
   - `reporter.py` 的 `source_foundation` 现在会额外输出：
     - `provider_resolution_counts`
     - `provider_attempt_chain_counts`
     - `transport_resolution_counts`
     - `transport_chain_counts`
     - `fallback_provider_hit_count`
     - `fallback_transport_window_count`
     - `orchestration_foundation_label`
   - markdown 报告的 `## Source Basis` 现在会显式写出 orchestration foundation / provider mix / transport mix；
   - `final_answer.confidence_label / source_basis` 现在也会区分“主链路 fetched grounded”和“fallback chain grounded”。
2. 本次 TDD 覆盖点：
   - provider fallback 链承载低信任外部答案路径时，local verifier 给出正式 warning；
   - transport fallback 链承载 fetched 外部答案路径时，local verifier 给出正式 warning；
   - global verifier 对 orchestration fallback-heavy 路径施加 completion penalty；
   - report structure 与 markdown 报告显式透出 orchestration foundation 摘要。
3. 验证结果：
   - `python -m pytest workers/research-worker/tests/test_llm_extract_verify.py -q` 通过，共 `24 passed`
   - `python -m pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py -q` 通过，共 `65 passed`
   - `python -m py_compile workers/research-worker/app/verifier.py workers/research-worker/app/reporter.py` 通过
4. 本子目标的交付意义：
   - 把 `MiroFlow` 风格的工具层退化可解释性，真正上浮到了 verifier 与最终结果层；
   - 也把 `DeepWideSearch` 中“深读路径质量不只看有没有读到正文，还要看是怎么救回来的”进一步落到了正式门控；
   - 同时延续了 `Table-as-Search` 的同源表达思路，让 research state、verifier judgement 和 report source basis 继续保持一致。
5. 下一优先子目标建议：
   - 继续留在 `Phase A`，优先补 `checkpoint / recovery / resume` 的同源闭环；
   - 尤其把现在已经存在的 fetch/provider/transport 研究轨迹稳定进入 checkpoint candidate 和恢复链路。

## 153. Phase A 子目标（九）执行启动：checkpoint / recovery / resume 同源闭环（2026-07-06）
在 `search/read` orchestration 元数据与 `orchestration-aware Dual Verifier` 都已经落地之后，当前继续按 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 的 `Phase A` 推进，把这层外部 research 轨迹真正接进 `checkpoint / recovery / resume`。目标不是只让恢复继续能跑，而是让恢复链路也保持同样的显式状态对象和可解释性。

1. 当前确认的真实缺口：
   - `research_checkpoint_candidate` 已包含 `search_hits / read_windows / tool_traces / loop_rounds`；
   - 但旧 checkpoint 恢复时，如果缺少新引入的 orchestration 字段，还没有显式归一化补齐；
   - 恢复后的 run 虽然会继续沿用 prior artifacts，但还缺少正式的 `resume_context_summary` 来说明本次恢复究竟承接了哪些对象。
2. 本次优先交付目标：
   - 为 legacy checkpoint 恢复补齐 `provider / fetch / transport` 相关元数据归一化；
   - 为恢复链路增加正式 `resume_context_summary`；
   - 让这层恢复上下文继续进入 `result_payload / checkpoint_candidate`，保持同源表达。
3. 本次实现方式继续坚持 TDD：
   - 先补失败测试，明确 legacy checkpoint 恢复后必须补齐新元数据；
   - 再补失败测试，明确恢复后的结果里必须存在正式 `resume_context_summary`；
   - 再补 `loop_runtime / runner` 实现，最后跑 focused tests 与回归测试。
4. 本次完成标准：
   - legacy checkpoint 恢复后的 `search_hits / read_windows` 自动补齐缺失的 orchestration 字段；
   - 恢复后的结果对象与新的 checkpoint candidate 都能看到正式 `resume_context_summary`；
   - prior `tool_traces / loop_rounds` 继续被承接并进入新的 run；
   - 测试覆盖恢复兼容性与恢复同源性，并保持回归通过。

## 154. Phase A 子目标（九）完成记录（2026-07-06）
`checkpoint / recovery / resume` 的这一轮同源闭环已按 TDD 落地完成。当前恢复链路不再只是“把旧 payload 塞回来继续跑”，而是已经开始把 legacy checkpoint 中缺失的新 research 元数据自动归一化补齐，并把恢复上下文本身做成正式对象输出。

1. 本次已完成的代码能力：
   - `loop_runtime.py` 在恢复 checkpoint 时，现已对 legacy `search_hits / read_windows` 执行归一化：
     - 为恢复的 `ResearchSearchHit` 自动补齐 `provider_attempts / provider_resolution / provider_fallback_reason`
     - 为恢复的 `ResearchReadWindow` 自动补齐 `fetch_status / fetch_method / content_origin / fetch_attempts / transport_chain / transport_resolution / transport_fallback_reason`
   - 恢复链路现已正式产出 `resume_context_summary`，至少包含：
     - `source_research_run_id`
     - `checkpoint_no`
     - `snapshot_type`
     - `active_branch_id`
     - `final_loop_decision`
     - restored search/read/evidence/loop/tool trace counts
   - `runner.py` 现已把 `resume_context_summary` 同时透出到：
     - `result_payload`
     - `research_checkpoint_candidate`
   - prior `tool_traces` 会继续从恢复上下文承接到新 run，而不是在恢复后丢失。
2. 本次 TDD 覆盖点：
   - legacy checkpoint 中缺失 provider/transport 新字段时，恢复后结果对象必须自动补齐；
   - 恢复后的 `tool_traces` 必须保留 prior trace；
   - 恢复后的 `result_payload` 与新的 `research_checkpoint_candidate` 都必须存在正式 `resume_context_summary`。
3. 验证结果：
   - `python -m pytest workers/research-worker/tests/test_runner.py -q` 通过，共 `15 passed`
   - `python -m pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py -q` 通过，共 `65 passed`
   - `python -m py_compile workers/research-worker/app/loop_runtime.py workers/research-worker/app/runner.py` 通过
4. 本子目标的交付意义：
   - 这让 `checkpoint / recovery / resume` 不再停留在“能继续执行”的最低层，而是开始具备与主研究链路同等的状态表达能力；
   - 也意味着前面已经补齐的 `provider / fetch / transport` 研究轨迹，终于能稳定跨 checkpoint 继续存在；
   - 对后续 Java 持久化、详情页展示、恢复审计链路都更友好，因为恢复来源本身已经成为正式对象。
5. 下一优先子目标建议：
   - `Phase A` 可以继续往“artifact-ready output 收束”推进；
   - 或者开始转入 `Phase B`，把现在这套 `resume_context_summary / checkpoint_candidate / report_structure` 正式接进 Java 主系统契约与持久化链路。

## 155. Phase A 子目标（十）执行启动：artifact-ready output 收束（2026-07-06）
在 `checkpoint / recovery / resume` 的同源闭环已经补齐之后，当前继续按 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 的 `Phase A` 推进，完成最后一项“`report_structure` 到正式 artifact payload 的收束”。目标不是再多存一份 markdown，而是把 worker 的研究结果正式打包成一个可被上层系统直接消费的研究产物对象。

1. 当前确认的真实缺口：
   - 当前 `result_payload` 已有 `report_markdown / report_structure / report_source_candidate`；
   - 但还缺一个正式、稳定、面向上层系统的 `research_artifact_candidate`；
   - 现在的 `report_source_candidate` 仍偏“写回资料池”的轻量对象，不足以承载完整研究产物契约。
2. 本次优先交付目标：
   - 定义正式 `research_artifact_candidate`；
   - 让它与 `report_structure / report_markdown / report_source_candidate / checkpoint_candidate` 保持同源；
   - 让后续 Java 契约层可以直接承接这个对象，而不是从散落字段重新拼研究产物。
3. 本次实现方式继续坚持 TDD：
   - 先补失败测试，明确 `result_payload` 与 `research_checkpoint_candidate` 中都必须存在正式 artifact 对象；
   - 再补失败测试，明确 `report_source_candidate` 与 artifact 对象之间的同源关系；
   - 再补 `reporter / runner` 实现，最后跑 focused tests 与回归测试。
4. 本次完成标准：
   - `result_payload` 中出现正式 `research_artifact_candidate`；
   - `research_checkpoint_candidate` 中出现同源 `research_artifact_candidate`；
   - artifact 对象能稳定包含 title、question、answer/source basis、markdown、structure、citations、resume context 等关键信息；
   - 测试覆盖 artifact 契约与恢复场景，并保持回归通过。

## 156. Phase A 子目标（十）完成记录（2026-07-06）
`artifact-ready output 收束` 已按 TDD 落地完成。当前 worker 的结果不再只是“结构化报告 + markdown + 一个写回资料池候选对象”，而是已经开始正式产出一个面向上层系统的 `research_artifact_candidate`，为后续 `Phase B` 的 Java 契约承接提前把研究产物对象定稳。

1. 本次已完成的代码能力：
   - `reporter.py` 新增正式 `build_research_artifact_candidate(...)`；
   - `runner.py` 现在会生成并透出同源 `research_artifact_candidate`，至少包含：
     - `artifact_type`
     - `artifact_version`
     - `title`
     - `question`
     - `generated_by / generated_ref_type / generated_ref_id`
     - `answer_status / confidence_label / coverage_label / source_basis / answer_text`
     - `content_markdown`
     - `report_structure`
     - `source_foundation`
     - `research_intent`
     - `closed_loop_state`
     - `resume_context_summary`
     - `citation_count / citations`
   - `result_payload` 中现已正式出现 `research_artifact_candidate`；
   - `research_checkpoint_candidate` 中现已出现同源 `research_artifact_candidate`；
   - `report_source_candidate` 现在与 `research_artifact_candidate` 保持 title / content_markdown / citation_count 同源，而不再是独立拼装对象。
2. 本次 TDD 覆盖点：
   - `result_payload` 中存在正式 `research_artifact_candidate`；
   - `research_checkpoint_candidate` 中存在同源 artifact 对象；
   - artifact 对象中的 title、question、answer/source basis、markdown、citations 与现有结果链路保持一致；
   - 恢复场景中 artifact 对象继续保留 `resume_context_summary`。
3. 验证结果：
   - `python -m pytest workers/research-worker/tests/test_runner.py -q` 通过，共 `15 passed`
   - `python -m pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py -q` 通过，共 `65 passed`
   - `python -m py_compile workers/research-worker/app/reporter.py workers/research-worker/app/runner.py` 通过
4. 本子目标的交付意义：
   - 这标志着 `Phase A` 最后一项“从 `report_structure` 到正式 artifact payload 的收束”已经补上；
   - worker 侧现在已经具备更像正式研究产物的输出契约，而不是一组散落字段；
   - 后续无论是 Java 主系统承接、研究详情页、资料池回流，都会更容易直接围绕这个 artifact 对象展开。
5. 下一优先子目标建议：
   - 可以开始按总文档转入 `Phase B`，把 `research_artifact_candidate / research_checkpoint_candidate / resume_context_summary / report_structure` 正式接进 Java 主系统契约与持久化链路；
   - 如果仍留在 worker 侧，则更多已经是局部优化，而不是 `Phase A` 主收口缺口。

## 157. Phase B 子目标（一）执行启动：Java 承接 research artifact / resume context（2026-07-06）
按 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 的主路线，当前正式从 `Phase A` 转入 `Phase B`。第一步优先不是扩更多接口，而是先把 worker 已经稳定产出的 `research_artifact_candidate / resume_context_summary / research_checkpoint_candidate / report_structure` 正式接进 Java 主系统契约。

1. 当前确认的真实缺口：
   - Java 当前已经能消费 `report_structure / counterfactual_summary / report_source_candidate / research_checkpoint_candidate`；
   - 但对新引入的 `research_artifact_candidate` 还没有正式消费和透出；
   - `resume_context_summary` 目前也还没有进入 detail API 的正式结果对象。
2. 本次优先交付目标：
   - 让 worker callback 正式承接 `research_artifact_candidate`；
   - 让 `Research Run detail` 正式透出 `research_artifact_candidate` 与 `resume_context_summary`；
   - 保持这些对象与现有 `report_structure / checkpoint / saved_report_source` 同源，而不是后端临时重拼。
3. 本次实现方式继续坚持 TDD：
   - 先补 Java 契约测试，明确 detail API 和 trace/result payload 必须能看到这两个对象；
   - 再补 `ResearchRunService / Response` 实现；
   - 最后跑 focused tests 与相关验证。
4. 本次完成标准：
   - Java detail API 中正式出现 `research_artifact_candidate`；
   - Java detail API 中正式出现 `resume_context_summary`；
   - callback 落地后，这两个对象能稳定从 trace/result_payload 恢复出来；
   - 测试覆盖普通完成场景与 resume 场景，并保持现有 Phase6 契约通过。

## 158. 总交付蓝图冻结：先产出从当前状态到全部完成交付的总文档（2026-07-06）
当前这一轮先不继续直接推进 `Phase B` 代码实现，而是先冻结一份“从当前状态到全部完成交付”的正式施工蓝图，原因如下：

1. 近几轮产品口径已经基本收敛，必须先统一成一份主文档：
   - `Deep Research` 是与工作台绑定的独立功能，不是 `Ask / Note / Wiki` 的升级模式；
   - 实现优先级仍是 `agent 内核 -> 主系统契约 -> 研究产物 -> 展示层`；
   - 前端主界面重点应放在最终研究报告与来源，检索/读取过程与审计信息放入详情弹窗分层展示。
2. 当前最需要的不是继续拆某个局部子任务，而是把以下内容正式冻结：
   - 当前真实完成度与已经落地的能力边界；
   - 从现在到全部完成交付的阶段顺序与依赖关系；
   - 每个阶段的产物、验收标准与推荐施工顺序；
   - 对 `Table-as-Search / DeepWideSearch / MiroFlow` 的吸收点与明确不做项。
3. 这次文档输出不视为偏离主路线，而是后续实现的统一基线：
   - 后续每次开始实质实现前，仍先更新本阶段计划；
   - 后续实现必须能明确挂靠到总交付文档中的某个 `Phase`；
   - 在主文档未更新前，不再随意切换“先做 worker / 先做后端 / 先做前端”的优先级。
4. 本次完成标准：
   - [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 被重写为正式总路线图；
   - 文档清楚说明当前真实基线、最终完成定义、阶段推进顺序与下一步推荐执行队列；
   - 后续实现可以直接按文档逐阶段推进，而无需再次从零整理口径。

## 159. Phase B1 正式执行：Java detail API 承接 artifact / resume context（2026-07-06）
在总交付文档已经冻结后，当前正式按其 `Phase B1` 开始执行。第一刀不扩散到前端，也不继续补 worker 内部抽象，而是先把 Java detail API 这条主链闭合，让主系统能正式消费并返回 worker 已稳定产出的研究产物对象。

1. 当前确认的真实缺口：
   - `ResearchRunDetailResponse` 仍未包含 `research_artifact_candidate / resume_context_summary`；
   - `ResearchRunService.getRunDetail(...)` 仍未从 `FINAL_REPORT.result_payload` 同源提取这两个对象；
   - `Phase6ResearchArtifactContractTest` 也还没有把这两个字段纳入正式契约。
2. 本次优先交付目标：
   - detail API 正式透出 `research_artifact_candidate`；
   - detail API 在恢复续跑完成后正式透出 `resume_context_summary`；
   - 这两个对象继续从 worker 原始 `result_payload` 同源恢复，而不是后端重拼。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，覆盖普通完成场景与 resumed 完成场景；
   - 再补 `ResearchRunDetailResponse / ResearchRunService` 实现；
   - 最后跑 focused backend tests 验证契约通过。
4. 本次完成标准：
   - `$.data.research_artifact_candidate` 在 detail API 中可见；
   - `$.data.resume_context_summary` 在 resumed 完成详情中可见；
   - `FINAL_REPORT.result_payload` 与 detail API 中这两个对象保持同源；
   - 相关 `Phase6` 契约测试通过。

## 160. Phase B2 正式执行：checkpoint list 契约化（2026-07-06）
在 `Phase B1` 已经把 detail API 的 `research_artifact_candidate / resume_context_summary` 接进主系统之后，当前继续按总交付文档推进 `Phase B2`，优先补齐 `checkpoint` 的正式列表契约。目标不是再做一个调试接口，而是让 `checkpoint` 从 detail 内部的嵌套摘要，升级为主系统中可单独查询、可供工作台历史面板直接消费的显式对象列表。

1. 当前确认的真实缺口：
   - Java 已有单个 checkpoint 详情接口，但还没有正式 `list checkpoints` 接口；
   - 当前前端若要展示 checkpoint 历史，只能从 `closed_loop_state.checkpoints` 里被动读取；
   - 这不利于后续工作台把 `checkpoint history / checkpoint chooser / resume entry` 做成显式管理对象。
2. 本次优先交付目标：
   - 新增 `Research Run -> checkpoint list` 正式 API；
   - 返回 checkpoint 的关键摘要，而不是完整 payload 大包；
   - 保持列表中的摘要字段与 `research_execution_checkpoint.summary_json` 和单个 checkpoint 详情同源。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，覆盖普通完成场景与 resumed 完成场景下的 checkpoint 列表；
   - 再补 `ResearchRunController / ResearchRunService / Response` 实现；
   - 最后跑 focused backend tests 验证。
4. 本次完成标准：
   - `GET /api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/checkpoints` 可用；
   - 返回至少包含 `checkpoint_no / snapshot_type / active_branch_id / final_loop_decision / summary / created_at` 等关键字段；
   - 列表摘要与单个 checkpoint 详情保持同源；
   - 相关 `Phase6` 契约测试通过。

## 161. Phase B3 正式执行：source foundation / verifier summary 对象化（2026-07-06）
在 `Phase B2` 已经把 checkpoint history 做成正式列表契约之后，当前继续按总交付文档推进 `Phase B3`，优先补 `source_foundation` 与 `verifier_summary` 这两个结果解释层对象。目标不是继续让前端去解读散落在 `report_structure / closed_loop_state / local_verifier / global_verifier / loop_decision` 里的字段，而是让主系统先把它们收成稳定的正式对象。

1. 当前确认的真实缺口：
   - `closed_loop_state` 与 `counterfactual_summary` 已经有正式返回，但 `source_foundation` 还没有进入 Java detail 契约；
   - `Dual Verifier` 相关结论仍主要散落在 `local_verifier / global_verifier / loop_decision / recovery_targets` 里，没有正式 `verifier_summary`；
   - 这会让后续前端详情层继续依赖字段拼装，而不是消费正式解释对象。
2. 本次优先交付目标：
   - `report_structure` 正式透出 `source_foundation / final_answer`；
   - `Research Run detail` 正式透出 `verifier_summary`；
   - 以上对象继续与 worker 原始 `report_structure / result_payload` 同源。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，明确 detail API 里必须看到这些对象；
   - 再补 `Response / Service` 实现；
   - 最后跑 focused backend tests 验证。
4. 本次完成标准：
   - `$.data.report_structure.source_foundation` 可见；
   - `$.data.report_structure.final_answer` 可见；
   - `$.data.verifier_summary` 可见；
   - 相关 `Phase6` 契约测试通过。

## 162. Phase B3/B4 衔接执行：recovery target 正式对象化（2026-07-06）
在 `source_foundation / verifier_summary` 已经进入正式 detail 契约之后，当前继续沿 `Phase B3 -> B4` 推进，优先把 `recovery_targets` 从散落 `Map` 升级为主系统正式对象。原因很直接：它同时承载 `Dual Verifier` 的纠偏靶点，也是后续前端详情层、checkpoint 历史和 resume 入口最需要直接消费的闭环信号。

1. 当前确认的真实缺口：
   - `recovery_targets` 现在同时存在于 `run summary / closed_loop_state / verifier_summary / checkpoint summary`，但都还是 `Map`；
   - 这会让前端和后续后端逻辑继续依赖 key 字符串访问，而不是正式字段契约；
   - 也让 `callback -> persistence -> query` 这条主链在“恢复靶点”这一关键对象上仍不够稳定。
2. 本次优先交付目标：
   - 引入正式 `ResearchRecoveryTargetsResponse`；
   - 让 `run summary / closed_loop_state / verifier_summary / checkpoint list` 对它同源返回；
   - 保持 JSON 语义不变，但把主系统内部契约从 `Map` 提升为正式对象。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，至少覆盖 detail、summary 和 checkpoint list 对 `recovery_targets` 的返回；
   - 再补 `Response / Service` 实现；
   - 最后跑 focused backend tests 验证。
4. 本次完成标准：
   - `closed_loop_state.recovery_targets` 继续可见，但已由正式 response 对象承接；
   - `verifier_summary.recovery_targets`、`run summary.recovery_targets`、`checkpoint list.recovery_targets` 与其同源；
   - 相关 `Phase6` 契约测试通过。

## 163. 总交付文档升级：按当前真实进度重写“从现在到全量交付”的执行蓝图（2026-07-06）
这一轮先不继续拆新的代码切片，而是先把 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 升级到“反映当前真实状态、能直接指导后续全部施工”的版本。原因不是重复写文档，而是原总文档还停留在 `Phase B 即将开始` 的判断，已经落后于当前实际进度。

1. 当前已经确认的真实状态：
   - `Phase A` 已收口完成，worker 侧 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支 / research_artifact_candidate` 主骨架已稳定；
   - `Phase B1` 已完成，Java detail API 已正式透出 `research_artifact_candidate / resume_context_summary`；
   - `Phase B2` 已完成，checkpoint list 正式 API 已可用；
   - `Phase B3` 已完成第一段，`source_foundation / final_answer / verifier_summary` 已正式进入后端契约；
   - `Phase B3/B4` 已完成一段，`recovery_targets` 已从 `Map` 提升为正式 response 对象；
   - 当前真正剩余的主缺口，已经不是“Phase B 要不要开始”，而是 `Phase B 剩余契约收口 -> Phase C 成果物闭环 -> Phase D 展示层 -> Phase E 交付演示`。
2. 本次文档升级必须明确写清的内容：
   - 当前真实完成度与阶段判断，避免继续使用过时的 “Phase B 即将开始” 口径；
   - 从当前状态到全部交付的阶段顺序、依赖关系、里程碑和每阶段完成定义；
   - `Phase B` 剩余收口项要聚焦哪些正式对象，尤其是 `state_ledger` 等仍偏 raw map 的区域；
   - `Phase C / D / E` 各自的主交付物、验收标准、推荐推进顺序；
   - 面向演示与简历亮点，应优先保留哪些可见能力，哪些复杂度继续明确不做。
3. 这次升级后的主文档，应成为后续所有实现的唯一总基线：
   - 后续每次开始新的实质实现前，仍先更新本阶段计划；
   - 后续实现都必须明确挂靠到升级后总文档中的某个 `Phase / Milestone`；
   - 在总文档未再次更新前，不再使用旧阶段判断讨论“是不是已经 90%”。
4. 本次完成标准：
   - 总交付文档明确反映当前已完成到 `Phase B` 的真实位置；
   - 文档不仅描述阶段，还能直接指导从现在到全量交付的后续执行顺序；
   - 后续无论继续做后端、成果物闭环还是前端展示层，都能直接按该文档逐段推进。

## 164. Phase B3 继续执行：state_ledger 与 checkpoint ledger summary 正式对象化（2026-07-06）
按升级后的总交付文档，当前继续收口 `Phase B`，优先处理 `closed_loop_state.state_ledger` 与 checkpoint summary 内 `state_ledger` 仍然是 raw `Map` 的问题。原因很直接：`Table-as-State` 是 `Deep Research` 最关键的内部亮点之一，如果主系统里这部分还停留在松散 map，上层展示、resume 审计和后续 artifact/query 契约都会继续不稳。

1. 当前确认的真实缺口：
   - `ResearchClosedLoopStateResponse.stateLedger` 目前仍是 `Map<String, Object>`；
   - `ResearchCheckpointSummaryResponse.summary` 目前也是 raw `Map`，其中 `summary.state_ledger` 仍靠 key 字符串读取；
   - `ResearchRunService` 内已经有稳定的 ledger 摘要构造逻辑，但主系统 response 契约还没有把它们收成正式对象；
   - 这意味着 `callback -> persistence -> query` 这条链在最关键的 `Table-as-State` 对象上仍没有完全收口。
2. 本次优先交付目标：
   - 引入正式的 `ResearchStateLedgerResponse`，承接 `closed_loop_state.state_ledger`；
   - 引入正式的 `ResearchCheckpointStateLedgerSummaryResponse`，承接 checkpoint summary 里的 `state_ledger`；
   - 保持现有 JSON 结构尽量不变，但让后端内部从 raw `Map` 提升为正式 response 对象；
   - 继续让这些对象与 worker `state_ledger`、checkpoint summary builder 和 detail/checkpoint list 同源。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，至少覆盖 detail API 与 checkpoint detail/list 对 `state_ledger` 的结构化返回；
   - 再补 `Response / Service` 实现；
   - 最后跑 focused backend tests 验证。
4. 本次完成标准：
   - `$.data.closed_loop_state.state_ledger` 继续可见，但已由正式 response 对象承接；
   - `$.data.summary.state_ledger` 与 `$.data[0].summary.state_ledger` 继续可见，且与 detail 中的 ledger 摘要同源；
   - 相关 `Phase6` 契约测试通过；
   - 这一刀完成后，`Phase B` 剩余缺口将进一步聚焦到 `run history / resume / artifact query` 与 callback-query 全链闭环。

## 165. Phase B3 完成记录：state_ledger 与 checkpoint ledger summary 正式对象化（2026-07-06）
这一刀已按 TDD 落地完成。当前 `Table-as-State` 不再只是在数据库和 trace 中有细粒度状态表达，Java 主系统对外返回的 `closed_loop_state.state_ledger` 以及 checkpoint `summary.state_ledger` 也已经从 raw `Map` 提升为正式 response 对象。

1. 本次已完成的代码能力：
   - 新增 `ResearchStateLedgerResponse`，正式承接 `closed_loop_state.state_ledger`；
   - 新增 `ResearchCheckpointStateLedgerSummaryResponse`，正式承接 checkpoint `summary.state_ledger`；
   - 新增 `ResearchCheckpointSnapshotSummaryResponse`，正式承接 checkpoint `summary`，从而让 `state_ledger` 不再埋在 raw `Map` 里；
   - `ResearchClosedLoopStateResponse.checkpoints` 也从 `List<Map<...>>` 升级为 `List<ResearchCheckpointSummaryResponse>`，detail API 内的 checkpoint 摘要与独立 checkpoint list 保持同源；
   - `ResearchRunService` 现已统一把 persisted `state_ledger / checkpoint summary` 读取为正式 typed response，再供 detail/checkpoint/checkpoint-list 使用。
2. 本次 TDD 覆盖点：
   - 直接通过 `ResearchRunService` 断言 `closed_loop_state.state_ledger` 为正式 typed response，并校验 `verified_row_count / rows / intent_completion_contract`；
   - 直接断言 detail 内嵌 checkpoint、单个 checkpoint detail、checkpoint list 三处的 `summary.state_ledger` 都走正式 typed response；
   - 同时保留原有 JSON 路径断言，确保外层接口结构没有回退。
3. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`14 tests, 0 failures`
4. 本子目标的交付意义：
   - 这让 `Table-as-State` 作为 `Deep Research` 的内部主亮点，第一次在 Java 契约层真正有了正式对象表达，而不再只是 trace 附件；
   - 对后续详情弹窗、checkpoint 审计、resume 对比和 artifact/query 契约都更稳，因为研究状态真源已经不再依赖 key 字符串访问；
   - 这一刀完成后，`Phase B` 剩余主缺口进一步收缩到 `run history / resume / artifact query` 与 callback-query 全链闭环。

## 166. Phase B2/B4 继续执行：run history 的 artifact / saved-report query 收口（2026-07-06）
在 `state_ledger` 对象化完成后，当前继续按总交付文档收口 `Phase B`，优先补 `run history` 对研究产物的查询能力。原因很直接：现在 `run detail` 已经能看见 `research_artifact_candidate`，保存报告后 detail 也能看见 `saved_report_source`，但 `run history` 仍只像一个任务列表，缺少对“这次研究最终产出了什么、是否已经回流资料池”的正式表达。

1. 当前确认的真实缺口：
   - `GET /research-runs` 虽然已经能返回 run 状态、resume lineage 和 closed-loop 摘要，但还不能正式返回 `research_artifact_candidate`；
   - 保存报告后，`saved_report_source` 目前只能在 detail API 中查询，history 层还拿不到这条产物关系；
   - 这使得 `callback -> persistence -> history/detail query` 在研究成果对象这一层仍未打平。
2. 本次优先交付目标：
   - 让 `run history` 正式透出 `research_artifact_candidate`；
   - 让 `run history` 正式透出 `saved_report_source`，与 detail 同源；
   - 保持这些对象继续从 trace/result payload 和 `research_run.report_source_id` 同源恢复，而不是额外重拼。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，覆盖普通完成 run 的 artifact history 查询，以及保存报告后 history 对 `saved_report_source` 的返回；
   - 再补 `ResearchRunSummaryResponse / ResearchRunService.listRuns(...)` 实现；
   - 最后跑 focused backend tests 验证。
4. 本次完成标准：
   - `GET /api/v2/workspaces/{workspaceId}/research-runs` 中可见 `research_artifact_candidate`；
   - 保存报告后，`GET /api/v2/workspaces/{workspaceId}/research-runs` 中可见 `saved_report_source`；
   - history 与 detail 中这两类对象保持同源；
   - 相关 `Phase6` 契约测试通过。

## 167. Phase B2/B4 完成记录：run history 的 artifact / saved-report query 收口（2026-07-06）
这一刀已按 TDD 落地完成。当前 `run history` 不再只是一个研究任务状态列表，而是已经能正式查询这次 run 产出的 `research_artifact_candidate`，以及它是否已经回流成 `saved_report_source`。

1. 本次已完成的代码能力：
   - `ResearchRunSummaryResponse` 新增 `research_artifact_candidate`；
   - `ResearchRunSummaryResponse` 新增 `saved_report_source`，并与 detail 使用同一个 `SaveResearchReportSourceResponse`；
   - `ResearchRunService.listRuns(...)` 现在会从 trace/result payload 同源恢复 `research_artifact_candidate`；
   - `ResearchRunService.listRuns(...)` 现在会从 `research_run.report_source_id` 同源加载 `saved_report_source`；
   - 这使得 `history -> detail -> save-report-as-source` 三者在成果物对象上不再断层。
2. 本次 TDD 覆盖点：
   - 普通完成 run 的 history 查询中，直接断言 `research_artifact_candidate.title / generated_ref_id / citation_count`；
   - 保存报告后，直接断言 history 查询中出现 `saved_report_source.source_id / source_type / generated_ref_id`；
   - 同时补了 service 级断言，确认 `listRuns(...)` 返回的正式 response 对象里已经能直接访问这两个字段。
3. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`14 tests, 0 failures`
4. 本子目标的交付意义：
   - 这让 `run history` 第一次真正具备“研究成果查询面”的意义，而不再只是任务状态概览；
   - 对后续工作台历史面板、成果筛选、来源回流展示都更友好，因为 report artifact 与 saved source 关系已经进入正式列表契约；
   - 这一刀完成后，`Phase B` 剩余主缺口进一步聚焦到 `resume` 相关的查询打平，以及 callback-persistence-query 全链的最终收口。

## 168. Phase B2/B4 继续执行：resume source checkpoint query 正式对象化（2026-07-06）
在 run history 的 artifact query 收口之后，当前继续按总交付文档推进 `Phase B`，优先补齐 resumed run 在 public query 面上的“恢复来源 checkpoint”对象。原因很直接：现在系统已经能创建 resumed run，worker input 里也能拿到完整 `resume_checkpoint`，但 public `detail / history` 仍只有 `resumed_from_research_run_id / resumed_from_checkpoint_no` 两个标识，用户和前端还无法直接在正式查询面上看到恢复来源 checkpoint 的摘要状态。

1. 当前确认的真实缺口：
   - resumed run 的 detail 和 history 只有 lineage id，没有正式 `resume_checkpoint` 摘要对象；
   - `ResearchRunService` 内部已经有 checkpoint summary 读取和对象化能力，但还没有把“来源 checkpoint 摘要”挂回 resumed run 的 public response；
   - 这使得 `resume-from-checkpoint` 在 public query 面仍停留在对象关系层，而没有真正打平到“可直接消费的恢复来源对象”。
2. 本次优先交付目标：
   - 为 resumed run 的 detail 正式透出 `resume_checkpoint` 摘要对象；
   - 为 resumed run 的 history summary 正式透出同源 `resume_checkpoint` 摘要对象；
   - 保持该对象与来源 checkpoint 的 `summary / counterfactual / recovery_targets` 同源，而不是新拼一份 resume 专用 JSON。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，覆盖 resumed run 在 QUEUED 和 COMPLETED 状态下的 detail/history `resume_checkpoint` 查询；
   - 再补 `Response / Service` 实现；
   - 最后跑 focused backend tests 验证。
4. 本次完成标准：
   - resumed run 的 `GET /research-runs/{id}` 中可见 `resume_checkpoint`；
   - resumed run 的 `GET /research-runs` 列表中也可见同源 `resume_checkpoint`；
   - `resume_checkpoint` 至少包含 `source_research_run_id / checkpoint_no / snapshot_type / active_branch_id / final_loop_decision / summary`；
   - 相关 `Phase6` 契约测试通过。

## 169. Phase B2/B4 完成记录：resume source checkpoint query 正式对象化（2026-07-06）
这一刀已按 TDD 落地完成。当前 resumed run 在 public query 面上不再只有 lineage id，而是已经能直接查询到来源 checkpoint 的正式摘要对象。

1. 本次已完成的代码能力：
   - 新增 `ResearchResumeCheckpointSummaryResponse`，正式承接 resumed run 的来源 checkpoint 摘要；
   - `ResearchRunDetailResponse` 新增 `resume_checkpoint`；
   - `ResearchRunSummaryResponse` 新增 `resume_checkpoint`；
   - `ResearchRunService` 现在会在 detail/history 查询中，同源加载来源 checkpoint 的 `summary / counterfactual_summary / recovery_targets / created_at`；
   - 这让 `resume-from-checkpoint` 在 public query 面上真正从“两个来源 id”升级为“一个可直接消费的恢复来源对象”。
2. 本次 TDD 覆盖点：
   - resumed run 在 `QUEUED` 状态下，detail 与 history 都必须返回 `resume_checkpoint`；
   - resumed run 在 `COMPLETED` 状态下，detail 仍必须保留同源 `resume_checkpoint`；
   - service 级断言直接验证 `getRunDetail(...).resumeCheckpoint()` 与 `listRuns(...).get(0).resumeCheckpoint()`。
3. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`14 tests, 0 failures`
4. 本子目标的交付意义：
   - 这让 resumed run 第一次真正具备“恢复来源可查询、可展示、可审计”的正式 public 契约；
   - 对后续工作台历史面板、resume 审计层和恢复差异展示都更友好，因为前端不再需要靠 lineage id 反查来源 checkpoint；
   - 这一刀完成后，`Phase B` 的剩余主缺口进一步收缩到 callback-persistence-query 全链的最终收口，而不是 resume 对象本身缺失。

## 170. Phase B4 继续执行：research artifact / resume context 正式对象化（2026-07-06）
在 resumed run 的 public query 已经打平之后，当前继续按总交付文档推进 `Phase B4`，优先把 `research_artifact_candidate` 与 `resume_context_summary` 从 raw `Map` 升级为正式 response 对象。原因很直接：这两个对象分别承载 `Deep Research` 的最终成果物和恢复上下文，本身就是主系统最应该正式承接的研究对象，如果它们还停留在 map，前端和后续 Phase C 的成果物闭环仍会继续依赖 key 字符串访问。

1. 当前确认的真实缺口：
   - `ResearchRunDetailResponse.researchArtifactCandidate` 仍是 `Map<String, Object>`；
   - `ResearchRunDetailResponse.resumeContextSummary` 仍是 `Map<String, Object>`；
   - `ResearchRunSummaryResponse.researchArtifactCandidate` 也仍是 `Map<String, Object>`；
   - worker 侧已经稳定输出 `research_artifact_candidate / resume_context_summary`，但 Java 主系统还没有把它们提升为正式 response 契约。
2. 本次优先交付目标：
   - 引入正式 `ResearchArtifactCandidateResponse`；
   - 引入正式 `ResearchResumeContextSummaryResponse`；
   - detail / history 查询统一返回 typed artifact 与 typed resume context；
   - 保持 JSON 语义基本不变，但让主系统内部正式承接这两个研究对象。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，覆盖 detail/history/service 级别对 typed artifact 与 typed resume context 的断言；
   - 再补 `Response / Service` 实现；
   - 最后跑 focused backend tests 验证。
4. 本次完成标准：
   - `detail / history` 中继续可见 `research_artifact_candidate`，但已由正式 response 对象承接；
   - `detail` 中继续可见 `resume_context_summary`，但已由正式 response 对象承接；
   - service 级调用可直接访问 artifact/resume context typed 字段；
   - 相关 `Phase6` 契约测试通过。

## 171. Phase B4 完成记录：research artifact / resume context 正式对象化（2026-07-06）
这一刀已按 TDD 落地完成。当前 `research_artifact_candidate` 与 `resume_context_summary` 不再只是 Java 主系统中的 raw `Map`，而是已经成为正式 research response 对象，为后续 `Phase C` 的成果物闭环和前端展示层收口提前把主系统契约打平。

1. 本次已完成的代码能力：
   - 新增 `ResearchArtifactCandidateResponse`，正式承接 `research_artifact_candidate`；
   - 新增 `ResearchResumeContextSummaryResponse`，正式承接 `resume_context_summary`；
   - `ResearchRunDetailResponse` 已升级为 typed `research_artifact_candidate / resume_context_summary`；
   - `ResearchRunSummaryResponse` 已升级为 typed `research_artifact_candidate`；
   - `ResearchRunService` 现在会同源解析 artifact 与 resume context，而不是继续把它们作为散落 map 透出。
2. 本次 TDD 覆盖点：
   - `Phase6ResearchArtifactContractTest` 已覆盖 detail / history / service 级别对 typed artifact 与 typed resume context 的断言；
   - 保留原有 JSON 路径断言，确保外部契约语义不回退；
   - service 级断言已能直接访问正式 typed 字段。
3. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`14 tests, 0 failures`
4. 本子目标的交付意义：
   - 这让 `Deep Research` 的最终成果对象第一次在 Java 主系统中真正完成正式对象化；
   - 对后续 `run -> artifact -> md -> source pool` 的成果物闭环更友好，因为主系统不再依赖 key 字符串反查这两个核心对象；
   - 这一刀完成后，`Phase B` 的剩余主缺口进一步收缩到 callback-persistence-query 全链的最终收口，而不是 artifact / resume context 契约本身缺失。

## 172. 总交付蓝图升级启动：从当前状态直接规划到全部完成交付（2026-07-06）
当前这一轮不继续直接推进新的后端或 worker 实现，而是先把总交付文档升级为一份“从当前真实状态到全部完成交付”的正式施工蓝图。这样做不是偏离主线，而是为了让后续所有实现都明确挂靠到统一的总路线，而不是继续在局部阶段判断和零散优先级上来回切换。

1. 当前为什么必须先做这一步：
   - 用户已经明确要求本轮先直接规划“从现在情况到全部完成交付”的文档，而不是只规划下一刀；
   - 当前 `Phase B` 已进入中段，继续只写“这一轮做什么”已经不足以指导后续 `Phase C / D / E` 的总推进；
   - 现在最需要的是把当前基线、最终完成定义、阶段顺序、阶段产物、验收标准和推荐执行队列统一冻结。
2. 本次文档升级必须明确写清的内容：
   - `Deep Research` 的产品边界和工作台关系；
   - 当前真实完成度、已完成能力、未闭合主链；
   - 参考 `Table-as-Search / DeepWideSearch / MiroFlow` 后本项目保留什么、裁剪什么；
   - 从当前状态到全部交付的 `Phase B -> C -> D -> E` 正式路线、产物和验收；
   - 后续每次实现的统一执行规则与推荐优先级。
3. 本次完成标准：
   - [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 被升级为可直接指导后续全量交付的主蓝图；
   - 文档不只描述阶段名称，还能明确回答“接下来具体先做什么、后做什么、做到什么算完成”；
   - 后续无论继续做 backend、artifact 闭环还是展示层，都能直接按主文档挂靠推进。

## 173. 总交付蓝图升级完成记录：从当前状态直接规划到全部完成交付（2026-07-06）
总交付文档已升级完成。当前主文档不再只是阶段说明，而是已经变成一份可直接从现在执行到最终交付收尾的正式施工蓝图。

1. 本次已完成的文档能力：
   - 重写并压实了主文档的定位，使其明确服务于“从当前状态到全部完成交付”；
   - 统一冻结了 `Deep Research` 的产品边界、工作台关系和展示原则；
   - 按当前真实状态重述了已完成能力、未闭合主链和合理完成度判断；
   - 将后续施工明确拆成 `Phase B / Phase C / Phase D / Phase E`，并逐阶段补齐目标、交付物、验收标准和推荐执行顺序；
   - 补充了“交付物与验收视角”“项目亮点视角”“后续执行规则”等章节，便于后续实现和最终讲解统一挂靠。
2. 本次文档升级的直接价值：
   - 后续实现不再需要反复讨论“现在是不是 90%”或“这一轮先做前端还是后端”；
   - 后续每刀都可以直接挂靠到主文档中的某个 Phase 或 Milestone；
   - 对最终交付、演示准备、项目讲解和简历亮点收口都更友好，因为主文档已经提前把完成定义和推进顺序写实。
3. 本次完成后的推荐主线：
   - 先继续收口 `Phase B4`，完成 callback-persistence-query 全链闭环；
   - 再进入 `Phase C`，打通 `run -> artifact -> md -> source pool` 成果物闭环；
   - 最后再集中完成 `Phase D / Phase E` 的展示层与演示收尾。

## 174. Phase B4 继续执行：verifier_gated_summary 正式对象化（2026-07-06）
按总交付蓝图继续推进 `Phase B4`。当前 `callback -> persistence -> query` 链路中，`verifier_gated_summary` 已经会在 service 内集中构造，并会进入 detail / checkpoint summary 的 public response，但它仍然以 raw `Map` 形式透出。这个对象承载 verifier 拦截行数、recovery 覆盖情况、样例行等信息，是后续详情弹窗第二层审计展示的高价值对象，因此本轮优先把它从散 map 收口为正式 response 契约。

1. 当前确认的真实缺口：
   - `ResearchVerifierSummaryResponse.verifierGatedSummary` 仍是 `Map<String, Object>`；
   - `ResearchCheckpointSnapshotSummaryResponse.verifierGatedSummary` 仍是 `Map<String, Object>`；
   - `ResearchRunService.buildVerifierGatedSummary(...)` 已经有稳定汇总逻辑，但调用方仍靠 key 字符串读取；
   - 这会让前端详情层和后续审计展示继续依赖 raw JSON 结构。
2. 本次优先交付目标：
   - 新增正式 `ResearchVerifierGatedSummaryResponse`；
   - detail `verifier_summary.verifier_gated_summary` 使用 typed response；
   - checkpoint detail / checkpoint list / detail 内嵌 checkpoint summary 中的 `summary.verifier_gated_summary` 使用同一个 typed response；
   - 保持对外 JSON 字段名不变，避免前端契约回退。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest` service 级断言，要求可以直接访问 typed `verifierGatedSummary()` 字段；
   - 再补 response record 与 service 读取逻辑；
   - 最后运行 focused backend tests 验证。
4. 本次完成标准：
   - detail API 继续可见 `verifier_summary.verifier_gated_summary.*`；
   - checkpoint summary 继续可见 `summary.verifier_gated_summary.*`；
   - service 级调用不再需要从 map 取 `blocked_row_count` 等字段；
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过。

## 175. Phase B4 完成记录：verifier_gated_summary 正式对象化（2026-07-06）
这一刀已按 TDD 落地完成。当前 `verifier_gated_summary` 不再只是 public response 中的一段 raw `Map`，而是已经被 Java 主系统正式承接为 typed response 对象，同时保持原有 JSON 字段路径不变。

1. 本次已完成的代码能力：
   - 新增 `ResearchVerifierGatedSummaryResponse`，正式承接 verifier-gated 摘要；
   - `ResearchVerifierSummaryResponse.verifierGatedSummary` 已从 `Map<String, Object>` 升级为 `ResearchVerifierGatedSummaryResponse`；
   - `ResearchCheckpointSnapshotSummaryResponse.verifierGatedSummary` 已从 `Map<String, Object>` 升级为 `ResearchVerifierGatedSummaryResponse`；
   - `ResearchRunService` 新增 `readVerifierGatedSummaryResponse(...)` 和空对象兜底逻辑；
   - detail / checkpoint detail / checkpoint list / detail 内嵌 checkpoint summary 的 JSON 路径继续保持 `verifier_gated_summary.*` 可见。
2. 本次 TDD 覆盖点：
   - 先补 service 级 typed 访问断言，确认旧代码会因 `Map` 上不存在 `blockedRowCount()` 等方法而编译失败；
   - 再补 typed response 和 service 映射逻辑；
   - 同时保留 detail 与 checkpoint summary 的 JSON 路径断言，确认外部契约不回退。
3. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`14 tests, 0 failures, 0 errors, 0 skipped`
4. 本子目标的交付意义：
   - 这让 `Dual Verifier` 审计摘要从“前端需要按 key 猜结构”升级为主系统正式对象；
   - 对后续详情弹窗第二层审计展示更友好，因为 blocked / guardrailed / recovery targeted 等计数和样例行现在有稳定契约；
   - `Phase B4` 的剩余工作继续聚焦在 callback-persistence-query 全链最终一致性和进入 `Phase C` 前的成果物闭环准备。

## 176. Phase B4/C1 衔接执行：research report md file 查询契约（2026-07-06）
按总交付蓝图继续推进，当前从 `Phase B4` 向 `Phase C1` 做第一段衔接：让研究完成后不只在 `research_run.final_report_markdown` 中保存正文，也生成并透出一份正式 `md` 成果文件对象。原因很直接：总文档里 `Phase C1` 要求“研究完成后生成正式 md 文件，并与 research_artifact_candidate 保持同源”，而当前只有 `save-report-as-source` 时才会写入 `workspace/{workspaceId}/research/{researchRunId}/report/final.md`，完成态 detail/history 还没有一个可查询的正式 report file 契约。

1. 当前确认的真实缺口：
   - `completeFromWorker(...)` 会保存 `final_report_markdown`，但不会在完成时生成正式 `md` 文件对象；
   - detail / history 只能看到 markdown 正文或 `research_artifact_candidate`，看不到“正式 md 成果文件”的对象信息；
   - `save-report-as-source` 虽然会写 `final.md` 到 storage，但这是回流资料池动作的一部分，不等同于 run 完成后已有正式研究成果文件；
   - 这会让 `Phase C1` 的“正式 md 成果文件”缺少稳定 query 入口。
2. 本次优先交付目标：
   - 新增正式 `ResearchReportFileResponse`；
   - research run 完成后生成稳定 object key 的 `final.md`；
   - detail API 透出 `report_file`；
   - history API 透出 `report_file`；
   - `report_file` 与 `research_artifact_candidate.content_markdown / final_report_markdown` 保持同源。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，要求完成态 detail/history 可见 `report_file`，且 storage 中确实存在该 object；
   - 再补 response / service 实现；
   - 最后运行 focused backend tests 验证。
4. 本次完成标准：
   - 完成 research run 后，`workspace/{workspaceId}/research/{researchRunId}/report/final.md` 存在；
   - detail API 中可见 `$.data.report_file.object_key / file_name / mime_type / content_size / sha256`；
   - history API 中可见同源 `report_file`；
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过。

## 177. Phase B4/C1 完成记录：research report md file 查询契约（2026-07-06）
这一刀已按 TDD 落地完成。当前 research run 在 worker 完成回调后，会立即生成稳定路径的正式 `final.md` 成果文件，并且 detail / history 都能查询到同源 `report_file` 对象。

1. 本次已完成的代码能力：
   - 新增 `ResearchReportFileResponse`，正式承接研究报告 md 文件对象；
   - `ResearchRunDetailResponse` 新增 `report_file`；
   - `ResearchRunSummaryResponse` 新增 `report_file`；
   - `ResearchRunService.completeFromWorker(...)` 在保存 `final_report_markdown` 的同时写入 `workspace/{workspaceId}/research/{researchRunId}/report/final.md`；
   - `ResearchRunService` 新增统一 helper 构造 `report_file`，包含 `object_key / file_name / mime_type / content_size / sha256`；
   - `save-report-as-source` 继续复用同一份 `final.md` 路径，避免资料回流时另写一份不一致的报告对象。
2. 本次 TDD 覆盖点：
   - 先补测试要求完成态 storage 中已存在 `final.md`；
   - detail API 必须返回 `report_file`；
   - history API 必须返回同源 `report_file`；
   - service 级调用可直接访问 `detailResponse.reportFile()` 和 `listRuns(...).get(0).reportFile()`；
   - 旧代码先因 response 缺少 `reportFile()` 编译失败，随后补实现转绿。
3. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`14 tests, 0 failures, 0 errors, 0 skipped`
4. 本子目标的交付意义：
   - 这让 `Phase C1` 的“正式 md 成果文件”不再只是保存为资料时的副产物，而是在 research run 完成时就成为可查询成果物；
   - 对后续前端报告查看、导出、保存为资料和 artifact 持久化关系都更直接；
   - 下一步可以继续沿 `Phase C` 推进 `run -> artifact -> source pool` 的正式成果物关系，而不必再从 markdown 正文临时反推文件对象。

## 178. Phase C2 执行启动：research_artifact 正式查询对象（2026-07-06）
在正式 `report_file` 已经进入 detail / history 之后，当前继续按总交付蓝图推进 `Phase C2`，把散落的 `research_artifact_candidate / report_file / saved_report_source` 收成一个可直接查询的正式 `research_artifact` 对象。目标不是引入重型 artifact 平台，而是让“一个 research run 对应一个正式研究成果物”先在后端契约上成立。

1. 当前确认的真实缺口：
   - detail / history 目前分别能看到 `research_artifact_candidate`、`report_file` 和 `saved_report_source`；
   - 但还没有一个统一对象表达“这次 run 的正式研究成果物是什么、文件在哪里、是否已经回流资料池”；
   - 前端如果要展示成果物卡片，仍需要自己拼多个字段；
   - `Phase C2` 要求的 `run -> artifact -> source` 稳定关系仍缺少正式 query object。
2. 本次优先交付目标：
   - 新增 `ResearchRunArtifactResponse`；
   - detail API 透出 `research_artifact`；
   - history API 透出同源 `research_artifact`；
   - `research_artifact` 至少包含 `artifact_id / artifact_type / artifact_version / title / generated_ref_id / report_file / saved_report_source`；
   - 保存为资料后，`research_artifact.saved_report_source` 与顶层 `saved_report_source` 保持同源。
3. 本次实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，要求 detail/history/service 级别可见 `research_artifact`；
   - 再补 response / service 实现；
   - 最后运行 focused backend tests 验证。
4. 本次完成标准：
   - 完成 research run 后，detail/history 都有 `research_artifact.report_file`；
   - 保存报告为 source 后，detail/history 中 `research_artifact.saved_report_source` 可见；
   - service 级调用可直接访问 `researchArtifact()`；
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过。

## 179. Phase C2 完成记录：research_artifact 正式查询对象（2026-07-06）
这一刀已按 TDD 落地完成。当前 Deep Research 的成果物不再只是由 `research_artifact_candidate / report_file / saved_report_source` 三个字段临时拼出来，而是已经进入正式 `research_artifact` 查询对象。

1. 本次已完成的代码能力：
   - 新增 `ResearchRunArtifactResponse`，正式承接 run 级研究成果物；
   - `ResearchRunDetailResponse` 新增 `research_artifact`；
   - `ResearchRunSummaryResponse` 新增 `research_artifact`；
   - `ResearchRunService` 新增统一构造逻辑，从 `research_artifact_candidate / report_file / saved_report_source` 同源组装成果物对象；
   - 即使 worker payload 没有完整 `research_artifact_candidate`，只要 run 已有正式 `report_file`，也能形成基础 `research_artifact`；
   - 保存报告为 source 后，`research_artifact.saved_report_source` 会与顶层 `saved_report_source` 保持同源。
2. 本次 TDD 覆盖点：
   - detail API 必须返回 `research_artifact.artifact_id / artifact_type / title / generated_ref_id / report_file`；
   - history API 必须返回同源 `research_artifact.report_file`；
   - 保存报告为 source 后，detail/history 必须返回 `research_artifact.saved_report_source`；
   - service 级调用可直接访问 `detailResponse.researchArtifact()` 与 `listRuns(...).get(0).researchArtifact()`；
   - 旧代码先因 response 缺少 `researchArtifact()` 编译失败，随后补实现转绿。
3. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`14 tests, 0 failures, 0 errors, 0 skipped`
4. 本子目标的交付意义：
   - 这让 `Phase C2` 的“一个 run 对应一个正式研究成果物”在后端查询契约上成立；
   - 前端后续可以直接消费 `research_artifact` 展示最终报告卡片、文件入口和资料池回流状态；
   - 下一步可以继续沿 `Phase C2/C3` 推进“回流后的成果可作为 source_scope 被后续研究复用”的闭环验证。

## 180. Phase C3 执行启动：回流研究成果作为 source_scope 的 artifact 元数据复用（2026-07-06）
在 `research_artifact` 查询对象已经成立之后，当前继续推进 `Phase C3`。现有系统已经能把保存后的研究报告 source 显式选入下一次 Deep Research 的 `source_scope`，并且能保留 `generated_by / generated_ref_id`。但当前 `loadSourceScopeItem(...)` 仍把 source type 固定写成 `DOCUMENT_TEXT`，`source_metadata` 也始终为空，这会让下游 worker input 只能知道“它来自 research_agent”，却不能稳定知道“它是一个已回流的研究成果物”。

1. 当前确认的真实缺口：
   - 保存后的研究报告 source 在数据库中的 `source_type` 是 `GENERATED_RESEARCH_REPORT`；
   - 但 worker input / detail 的 `source_scope[].source_type` 仍被硬编码成 `DOCUMENT_TEXT`；
   - `source_scope[].source_metadata` 没有携带 `research_artifact` 摘要；
   - 这不利于后续 agent 在复用资料时区分普通文档和研究成果物。
2. 本次优先交付目标：
   - `source_scope` 使用数据库真实 `source_type`；
   - 当 source 来自 `research_agent` 且有 `generated_ref_id` 时，在 `source_metadata.research_artifact` 中透出基础 artifact 元数据；
   - worker input 与 detail 中的 source_scope 保持同源；
   - 继续保持已有 generated provenance 进入 downstream closed-loop state 的能力。
3. 本次实现方式继续坚持 TDD：
   - 在现有 `savedResearchReportShouldRemainIdentifiableInChatCitations` 场景中补断言；
   - 先要求 downstream worker input/detail 中可见 `source_type = GENERATED_RESEARCH_REPORT` 与 `source_metadata.research_artifact.artifact_id`；
   - 再补 `ResearchRunService.loadSourceScopeItem(...)` 实现；
   - 最后运行 focused backend tests 验证。
4. 本次完成标准：
   - downstream worker input 的 source_scope 能识别回流研究报告 source；
   - downstream detail 的 source_scope 能识别同一个 research artifact；
   - 现有 closed-loop provenance 断言继续通过；
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过。

## 181. 总交付文档再校准：从当前真实状态到全部完成交付的执行版路线（2026-07-06）
用户已明确指出，本轮重点不是继续写“这一轮做什么”，而是直接规划一份从当前情况到全部完成交付的执行文档。当前主文档虽然已经有总蓝图，但它仍保留了部分过时状态，例如把 `Phase C` 写成未开始；实际进度已经推进到 `Phase C2` 完成、`Phase C3` 启动且存在测试先行的红线。因此本轮先做主文档再校准，不继续推进新的代码实现。

1. 当前必须校准的真实状态：
   - `Phase A` worker 内核已完成第一版主骨架，只保留联调补丁；
   - `Phase B` 的主系统契约已基本收口，剩余主要是联调中暴露的契约缺口；
   - `Phase C1` 的正式 `report_file` 已完成；
   - `Phase C2` 的正式 `research_artifact` 查询对象已完成；
   - `Phase C3` 已启动，当前红线是回流研究报告作为 `source_scope` 复用时，`source_type / source_metadata.research_artifact` 还没有转绿；
   - `Phase D / E` 仍未完成，是最终可展示、可演示交付的主要缺口。
2. 本次文档升级目标：
   - 把主文档从“阶段说明”改成“剩余交付执行图”；
   - 明确当前不是 90% 完成，但也已经不是 Phase B 中段；
   - 把剩余执行队列压成 `C3 -> C4 -> D -> E`，并写清每段交付物、验收标准、测试入口和展示价值；
   - 继续保留用户要求的产品边界：Deep Research 是独立功能，输入来自用户显式问题，工作台只承载入口、产物、展示和资料回流；
   - 展示层放到后段，主界面重点展示报告和来源，详情第一层展示检索/读取过程，第二层展示 verifier / 反证分支 / checkpoint 审计。
3. 本次完成标准：
   - [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 明确反映当前已经推进到 `Phase C3`；
   - 文档能直接指导后续从当前红线到全部完成交付；
   - 后续继续实现时，每一刀都能挂靠到文档中的具体剩余交付项，而不是重新讨论优先级。

## 182. Phase C3 正式实现：source_scope 复用回流研究成果的 artifact 元数据（2026-07-06）
按总交付执行文档的“下一刀执行卡片”，本轮正式处理 `Phase C3` 红线。目标不是新增一套 artifact 平台，而是让已经保存回资料池的 Deep Research 报告，在下一次研究被显式选入 `source_scope` 时，仍然保留“它是研究成果物”的身份。

1. 当前红线：
   - `savedResearchReportShouldRemainIdentifiableInChatCitations` 已经要求 downstream worker input / detail 中可见 `source_scope[0].source_type = GENERATED_RESEARCH_REPORT`；
   - 同一 source_scope item 还必须携带 `source_metadata.research_artifact.artifact_id = 上游 researchRunId`；
   - 当前实现仍可能把 `source_type` 固定成 `DOCUMENT_TEXT`，并返回空 `source_metadata`。
2. 本轮实现点：
   - 修改 `ResearchRunService.loadSourceScopeItem(...)`；
   - 查询并使用数据库真实 `source.source_type`；
   - 当 `generated_by = research_agent` 且 `generated_ref_id` 不为空时，构造 `source_metadata.research_artifact`；
   - 保持 worker input 和 detail 的 source_scope 同源，因为二者都走同一个加载 helper。
3. 本轮验收标准：
   - downstream worker input 的 source_scope 能识别回流研究报告 source；
   - downstream detail 的 source_scope 能识别同一个 research artifact；
   - 既有 generated provenance 进入 report / closed-loop state 的断言继续通过；
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过。

## 183. Phase C3 完成记录：source_scope 复用回流研究成果的 artifact 元数据（2026-07-06）
这一刀已按 TDD 落地完成。当前保存回资料池的 Deep Research 报告，在被显式选入下一次研究任务的 `source_scope` 时，不再退化成普通 `DOCUMENT_TEXT`，而是会保留数据库真实 `source_type` 与基础 `research_artifact` 元数据。

1. 本次已完成的代码能力：
   - `ResearchRunService.loadSourceScopeItem(...)` 查询并使用 `source.source_type`；
   - `source_scope[].source_type` 已从硬编码 `DOCUMENT_TEXT` 改为数据库真实类型；
   - 当 `generated_by = research_agent` 且 `generated_ref_id` 不为空时，`source_scope[].source_metadata.research_artifact` 会返回：
     - `artifact_id`
     - `artifact_type = DEEP_RESEARCH_REPORT`
     - `generated_by`
     - `generated_ref_id`
   - worker input 与 detail 继续复用同一个 source scope 加载 helper，因此二者保持同源。
2. 本次 TDD 覆盖点：
   - downstream worker input 必须返回 `GENERATED_RESEARCH_REPORT`；
   - downstream worker input 必须返回 `source_metadata.research_artifact.artifact_id`；
   - downstream detail 必须返回同源 `source_type / generated_by / generated_ref_id / source_metadata.research_artifact`；
   - 既有 report / closed-loop state 中的 generated provenance 断言继续通过。
3. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`14 tests, 0 failures, 0 errors, 0 skipped`。
4. 本子目标的交付意义：
   - `Phase C3` 的“研究成果可作为 source_scope 被后续研究复用”已经在后端契约上闭合；
   - 后续 agent 可以区分普通资料与回流研究成果物，不需要靠标题或正文猜来源类型；
   - 下一步可以按总交付文档进入 `Phase C4`：整理搜索、读取、来源命中与审计摘要，为前端详情弹窗两层展示做稳定契约。

## 184. Phase C4 执行启动：研究过程展示契约正式对象化（2026-07-06）
按总交付执行文档的当前主线，本轮进入 `Phase C4`。目标不是把 `tool_traces` 原样暴露给前端，也不是去做过程回放播放器，而是先把现有 `loop_rounds / source_foundation / verified_findings / verifier_summary / checkpoint` 收成一组稳定、可展示、可测试的研究过程摘要对象。

1. 当前确认的真实缺口：
   - detail 虽然已经返回 `traces / report_structure / closed_loop_state / verifier_summary`，但前端如果要展示研究过程，仍然需要自己解析多处 raw payload；
   - history 层当前能看到成果物与闭环状态摘要，但还没有正式“过程摘要”对象；
   - 如果现在直接做前端详情弹窗，很容易退化成调试页，而不是“搜索 / 读取 / 来源 / 审计”双层展示。
2. 本轮优先交付目标：
   - 新增正式 `research_process_summary` 对象；
   - 在其中收口 `search_read_timeline / source_evidence_summary / audit_summary`；
   - detail API 透出 `research_process_summary`；
   - history API 透出同源 `research_process_summary`；
   - 所有字段都来自现有 worker trace、`report_structure`、`closed_loop_state`、`verifier_summary`，不引入新的 worker 依赖。
3. 本轮实现方式继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest`，要求 detail/history/service 级别可见 `research_process_summary`；
   - 再补 response record 与 `ResearchRunService` 构造逻辑；
   - 最后运行 focused backend tests 验证。
4. 本轮完成标准：
   - detail API 中可见 `research_process_summary.search_read_timeline / source_evidence_summary / audit_summary`；
   - history API 中可见同源 `research_process_summary`；
   - service 级调用可直接访问 typed `researchProcessSummary()`；
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过。

## 185. Phase C4 完成记录：研究过程展示契约正式对象化（2026-07-06）
这一刀已按 TDD 落地完成。当前前端不再需要直接解析散落的 `traces / loop_rounds / source_foundation / verifier_summary / checkpoint` 才能拼出研究过程，而是可以直接消费正式 `research_process_summary`。

1. 本次已完成的代码能力：
   - 新增正式 `ResearchProcessSummaryResponse`；
   - 新增正式 `ResearchSearchReadTimelineResponse / ResearchLoopRoundSummaryResponse`；
   - 新增正式 `ResearchSourceEvidenceSummaryResponse`；
   - 新增正式 `ResearchAuditSummaryResponse`；
   - `ResearchRunDetailResponse` 新增 `research_process_summary`；
   - `ResearchRunSummaryResponse` 新增同源 `research_process_summary`；
   - `ResearchRunService` 现已统一从 `closed_loop_state / report_structure / verifier_summary / research_artifact_candidate` 构造：
     - `search_read_timeline`
     - `source_evidence_summary`
     - `audit_summary`
2. 本次 TDD 覆盖点：
   - detail API 必须返回 `research_process_summary.search_read_timeline.*`；
   - detail API 必须返回 `research_process_summary.source_evidence_summary.*`；
   - detail API 必须返回 `research_process_summary.audit_summary.*`；
   - history API 必须返回同源 `research_process_summary`；
   - service 级调用可直接访问 `detailResponse.researchProcessSummary()` 与 `listRuns(...).get(0).researchProcessSummary()`。
3. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`14 tests, 0 failures, 0 errors, 0 skipped`。
4. 本子目标的交付意义：
   - `Phase C4` 的“研究过程对象可供展示层消费”已经在后端契约上成立；
   - 详情弹窗第一层现在有了稳定的 timeline / source evidence 摘要入口，第二层有了 audit summary 入口；
   - 下一步可以按总交付文档进入 `Phase D`：把独立入口、主结果面板和详情弹窗双层展示正式做出来。

## 186. Phase D 首刀执行：前端主结果面板接入 research_process_summary（2026-07-06）
在 `Phase C4` 后端契约已经成立之后，本轮继续推进 `Phase D`，但不重做前端结构，而是沿现有 `Research Workbench` 骨架先把正式 `research_process_summary` 接入主结果区和过程总览。目标不是立刻收完整个展示层，而是先让前端开始消费正式对象，而不是继续完全依赖 trace 推导。

1. 当前确认的真实切入点：
   - 前端已有 `Deep Research` 独立视图、run 历史、详情弹窗和过程层骨架；
   - 但主结果面板与过程总览主要仍依赖 `traces / loop_rounds / source_foundation` 的前端推导；
   - 如果不先接正式 `research_process_summary`，后续展示层仍会继续把过程 UI 做成调试页。
2. 本轮优先交付目标：
   - 前端类型补齐 `research_process_summary / search_read_timeline / source_evidence_summary / audit_summary`；
   - 主结果面板新增正式 `Research Harness Summary` 区块；
   - 现有过程总览优先消费 `research_process_summary`，读不到时再回退原有 trace 推导；
   - 保持现有 UI contract 文案与整体结构不回退。
3. 本轮验证标准：
   - `npm run build` 通过；
   - `node scripts/ui-contract-check.mjs` 通过；
   - 前端代码中已经显式消费 `research_process_summary`，而不是只停留在类型声明。

## 187. Phase D 首刀完成记录：前端主结果面板接入 research_process_summary（2026-07-06）
这一刀已落地完成。当前 `Research Workbench` 前端已经开始正式消费 `research_process_summary`，不再只靠 trace 推导研究过程总览。

1. 本次已完成的代码能力：
   - 前端类型新增 `ResearchProcessSummary / ResearchSearchReadTimeline / ResearchSourceEvidenceSummary / ResearchAuditSummary`；
   - `ResearchRunSummary` 与 `ResearchRunDetail` 已接入 `research_process_summary`；
   - 主结果面板新增 `Research Harness Summary` 区块，直接展示：
     - 搜索命中
     - 阅读窗口
     - 来源支撑
     - 审计闭环
   - 现有过程总览在非轮次聚焦视图下，已优先消费 `research_process_summary` 的 totals 和 labels。
2. 本次验证结果：
   - `npm run build` 通过；
   - `node scripts/ui-contract-check.mjs` 通过。
3. 本子目标的交付意义：
   - `Phase D` 已正式启动，而且不是空壳页面，而是已经开始消费 `Phase C4` 的正式过程契约；
   - 接下来可以继续沿 `D1 / D2 / D3 / D4` 把独立入口、主结果面板、详情弹窗第一层和第二层收得更稳；
   - 当前最合适的下一步，是继续把详情弹窗的过程层和审计层进一步从 trace 推导迁移到正式 summary object。

## 188. Phase D 继续执行：详情弹窗两层优先消费正式 summary object（2026-07-06）
在主结果面板已经开始消费 `research_process_summary` 之后，本轮继续推进 `Phase D`，聚焦详情弹窗的 `process / audit` 两层。目标不是砍掉现有 trace、checkpoint、verifier 视图，而是先在两层顶部增加正式 summary card，让用户进入详情后优先看到稳定过程摘要与闭环审计摘要，再向下钻取原始轨迹和回放内容。

1. 当前确认的真实缺口：
   - 详情弹窗虽然已经很完整，但入口阅读顺序仍偏向 trace / checkpoint / raw derivation；
   - `research_process_summary` 已经存在于前后端契约中，但详情两层还没有把它作为第一优先信息层；
   - 如果继续直接扩展 trace 视图，展示层仍容易维持“调试页味道”。
2. 本轮优先交付目标：
   - `process` 层顶部新增正式 summary card，优先展示 `search_read_timeline / source_evidence_summary`；
   - `audit` 层顶部新增正式 summary card，优先展示 `audit_summary`；
   - 现有 trace、loop round、checkpoint、verifier、resume diff 继续保留，但退居到 summary 之后；
   - 不引入新的后端字段，全部复用现有 `research_process_summary`。
3. 本轮验证标准：
   - `npm run build` 通过；
   - `node scripts/ui-contract-check.mjs` 通过；
   - 详情弹窗两层代码中已经显式消费 `research_process_summary` 的三个 summary 子对象。

## 189. Phase D 继续完成记录：详情弹窗两层优先消费正式 summary object（2026-07-06）
这一刀已落地完成。当前 `Research Detail` 弹窗的 `process / audit` 两层，在保留原有 trace、loop round、checkpoint、resume diff 的基础上，已经先把正式 summary object 放到阅读入口位置。

1. 本次已完成的代码能力：
   - `process` 层顶部新增 `Formal Process Summary`；
   - `audit` 层顶部新增 `Formal Audit Summary`；
   - 两层都会优先消费：
     - `research_process_summary.search_read_timeline`
     - `research_process_summary.source_evidence_summary`
     - `research_process_summary.audit_summary`
   - 原有 trace / checkpoint / verifier / resume diff 继续保留，但退居到 summary 之后。
2. 本次验证结果：
   - `npm run build` 通过；
   - `node scripts/ui-contract-check.mjs` 通过。
3. 本子目标的交付意义：
   - `Phase D` 不再只是主结果面板在消费正式契约，详情弹窗两层也已经开始优先展示正式 summary object；
   - 用户进入详情后的第一眼信息，已经更像“可展示产品”而不是“调试页”；
   - 下一步最合适的是继续沿 `Phase D` 收口独立入口表单、结果面板布局和详情层剩余 trace-heavy 区块。

## 190. Phase D 继续执行：独立入口与主结果面板产品化收口（2026-07-06）
在详情弹窗两层已经开始优先消费正式 summary object 之后，本轮继续推进 `Phase D1 / D2`。目标不是新增复杂交互，而是把现有独立入口和主结果区做得更像“可展示产品面”：左侧让用户一眼看懂这次要发起的研究合同，中间让用户一眼看懂这次研究的结果快照。

1. 当前确认的真实缺口：
   - 左侧虽然已有表单，但还缺一个更凝练的 launch contract 视图；
   - 中间主结果区虽然已有报告、来源和 harness summary，但首屏还可以更快表达“本次研究产出了什么、目前处于什么闭环状态”；
   - 如果不先收入口和结果快照，整体仍更像工程工作台而不是可展示产品。
2. 本轮优先交付目标：
   - 左侧独立入口新增 `Snapshot Research Question / Launch Contract` 摘要卡；
   - 主结果区新增结果快照卡，优先展示 final answer、source basis、process summary、audit summary；
   - 尽量复用现有 `research_process_summary / final_answer / source scope`，不引入新的后端字段；
   - 保持当前 workbench 结构不大改，只增强第一眼信息层。
3. 本轮验证标准：
   - `npm run build` 通过；
   - `node scripts/ui-contract-check.mjs` 通过；
   - 独立入口和主结果面板代码都已经显式使用这些新的 summary card。

## 191. 总交付执行手册重排完成：从当前状态直接规划到完整交付（2026-07-06）
用户已明确指出，这一轮的重点不是继续做某个局部实现，而是先产出一份“从当前情况到全部完成交付”的正式规划文档。因此本轮不推进新的代码实现，先把主文档从“阶段说明”升级为“完整交付执行手册”，并同步按照最新产品优先级重排后续路线。

1. 本次完成的文档级调整：
   - 重写了 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md)，使其不再只是记录当前阶段，而是直接覆盖“当前状态 -> 最终交付”的完整路线；
   - 明确冻结了产品边界：`Deep Research` 是工作台中的独立功能，任务只来自用户显式输入，工作台负责入口、展示、产物与资料回流；
   - 重新按用户最新要求调整了执行优先级：先做 `agent 内核 + 工具栈 + Research Harness`，再做主系统补丁，展示层放到后面集中收口；
   - 把 `Table-as-Search / DeepWideSearch / MiroFlow` 三个参考项目的长处重新映射到了 NoteWeave 自己的最终施工路线。

2. 主文档里已经明确的新主线：
   - `Phase 1`：Agent 内核与工具栈完形，重点补 `search / fetch / read`、evidence binding、来源锚定报告；
   - `Phase 2`：`verification-driven Research Harness` 收口，重点补 `Dual Verifier`、`反证分支`、checkpoint / resume / summary-first；
   - `Phase 3`：主系统契约与成果物闭环补丁；
   - `Phase 4`：工作台展示层最终收口；
   - `Phase 5`：端到端联调、demo、项目讲解与最终交付。

3. 这次重排的直接意义：
   - 后续实现不会再被“当前 D 阶段已经开始了”绑住，而是会按照用户要求把真正的项目亮点继续压回 agent 内部；
   - 展示层仍然会做，但会建立在完整可运行、可验证、可回流的研究 agent 之上，而不是先做一个前端壳子；
   - 从现在开始，每一刀都可以直接对照主文档里的阶段、交付物和验收标准推进。

## 192. Phase 1 执行启动：把 fetch 从 read 内部细节提升为正式研究工具层（2026-07-06）
按新的总交付执行手册，当前正式进入 `Phase 1`。第一刀不先碰展示层，而是先处理主文档里已经明确点出的真实缺口：外部 `fetch` 目前仍主要内嵌在 `UrlReadAdapter` 里，更多只是 `read` 的内部实现细节，还没有成为 worker 中显式、可追踪、可恢复的正式研究工具阶段。

1. 当前确认的真实缺口：
   - `ResearchToolbox` 当前仍是 `search -> read -> extract -> verify`，没有独立 `fetch` 阶段；
   - 外部 URL 快照抓取、provider / transport fallback 与读取窗口构造目前揉在 `read_adapters.py` 中，不利于形成清晰的 `search / fetch / read` 工具链；
   - 当前虽然 `ResearchReadWindow` 已携带较多 fetch 元数据，但 `fetch` 本身仍缺少独立 trace、独立恢复语义和更明确的阶段边界；
   - `resume / checkpoint` 也尚未显式承接 `fetch` 产物，因此恢复时仍主要依赖 `read_windows`。

2. 本轮优先交付目标：
   - 新增正式 `fetch` 阶段对象与 `fetch_adapters` 模块；
   - 让 `ResearchToolbox` 从 `search -> fetch -> read` 执行，而不是直接从 search 进入 read；
   - 让外部 transport / fallback / snapshot 先产出正式 fetch artifact，再由 read 阶段构造 `ResearchReadWindow`；
   - 在 `tool_traces` 中新增正式 `fetch` trace，并让 checkpoint / result payload 能显式保留 fetch artifacts，服务恢复与后续展示。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_fetch_adapters.py`，覆盖 workspace fetch、external fetch、transport fallback chain 与恢复优先级；
   - 再补 `test_loop_runtime.py`，要求 runtime tool traces 中存在正式 `TOOL_FETCH_*` 阶段；
   - 必要时补 `test_runner.py`，要求 result payload / checkpoint 中可见 fetch artifacts；
   - 最后再修改 worker 实现，通过 focused pytest 验证。

4. 本轮完成标准：
   - worker 中已经存在正式 `fetch` 工具层，而不是仅作为 `read` 内部细节；
   - `ResearchToolbox` 的执行链显式变为 `search -> fetch -> read -> extract -> verify`；
   - `tool_traces` 中可见 fetch 阶段，且能反映 transport fallback 与 fetch metadata；
   - `resume / checkpoint / result payload` 中能显式承接 fetch artifacts；
   - `pytest workers/research-worker/tests/test_fetch_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_runner.py` 通过。

## 193. Phase 1 完成记录：fetch 已提升为正式研究工具层（2026-07-07）
这一刀已按 TDD 落地完成。当前 `fetch` 不再只是 `read` 阶段里顺带发生的一段内部实现，而是已经成为 worker 中显式、可追踪、可恢复的正式研究工具层，并正式插入 `ResearchToolbox` 的主执行链。

1. 本次已完成的代码能力：
   - 新增 `ResearchFetchedDocument`，作为 `search -> fetch -> read` 链路中的正式中间对象；
   - 新增 `fetch_adapters.py`，正式承接：
     - workspace fetch
     - external url fetch
     - Jina / HTTP transport
     - composite transport fallback chain
   - `ResearchToolbox` 已从 `search -> read -> extract` 升级为 `search -> fetch -> read -> extract`；
   - `tool_traces` 已新增正式 `TOOL_FETCH_SOURCES` 阶段；
   - `read_adapters.py` 已改为消费 `ResearchFetchedDocument` 构造 `ResearchReadWindow`，而不再直接内嵌 fetch 逻辑；
   - `resume / checkpoint / result payload` 已新增 `fetched_documents`，旧 checkpoint 若没有该对象，也能从 `read_windows` 恢复出兼容的 fetched documents。

2. 本次 TDD 覆盖点：
   - 新增 `test_fetch_adapters.py`，覆盖 workspace fetch、external fetch、transport fallback chain 与 recovery ordering；
   - `test_read_adapters.py` 已改为验证 fetched document -> read window 的正式转换；
   - `test_loop_runtime.py` 已要求 runtime tool traces 中存在 `TOOL_FETCH_SOURCES`；
   - `test_runner.py` 已验证 `result_payload / checkpoint / resume summary` 可见 `fetched_documents` 与恢复态 fetch 统计。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_fetch_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_loop_runtime.py workers/research-worker/tests/test_runner.py` 通过；
   - 结果：`35 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`80 passed`。

4. 本子目标的交付意义：
   - `Phase 1` 的第一刀已经把主文档里“fetch 仍主要内嵌在 read 语义里”的真实缺口转绿；
   - 后续继续做 `verification-driven Research Harness` 时，已经有了更清晰的 `search / fetch / read` 工具链和更稳定的恢复对象；
   - 工作台后续若要展示检索过程，也可以更自然地区分“搜到了什么”“抓到了什么”“读到了什么”。

## 194. Phase 2 执行启动：把 harness_summary 升级为 verification-driven 控制闭环摘要（2026-07-07）
在 `Phase 1` 第一刀已经把 `fetch` 提升为正式工具层之后，当前进入 `Phase 2`。这一刀不先扩 Java 契约，而是先把 worker 里的 `harness_summary` 从“轻量 trace 统计”升级为真正的 `verification-driven Research Harness` 摘要，让 verifier、loop decision、counterfactual、resume 与 coverage 先在同源 worker 产物中收口。

1. 当前确认的真实缺口：
   - `ResearchHarness` 目前主要负责 planning trace，`harness_summary` 也只返回 `mode / phase_count / warning_count` 这类轻量统计；
   - `Dual Verifier`、`loop decision`、`recovery mode`、`branch decision`、`counterfactual summary`、`resume summary` 虽然已经散落在 result payload 中，但还没有被组织成一个统一的“Research Harness 控制闭环摘要”；
   - 当前如果要判断“这次研究为什么继续、为什么恢复、为什么收口、是否存在路径纠偏”，仍然需要人工跨多个字段拼接；
   - 这与主文档里 `Phase 2` 要求的 `verification-driven Research Harness` 还有明显距离。

2. 本轮优先交付目标：
   - 升级 `harness_summary`，让它正式包含：
     - `control_loop`
     - `verifier_gate`
     - `intent_contract`
     - `counterfactual_recovery`
     - `resume_checkpoint`
     - `evidence_coverage`
   - 让这些摘要直接来自当前同源对象：
     - `loop_result.final_decision`
     - `local/global verifier`
     - `state_ledger`
     - `counterfactual_summary`
     - `resume_context_summary`
   - 让 `harness_summary` 可以直接回答：
     - 当前是否收敛
     - 为什么还没收敛
     - 当前 verifier 在放行还是要求恢复
     - 当前是否走过 `反证分支`
     - 当前恢复基线是什么

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_harness.py`，要求 `result_payload.harness_summary` 返回正式控制闭环摘要，而不是只有 phase 统计；
   - 必要时补 `test_runner.py / test_loop_runtime.py`，要求 resume 和 counterfactual 场景下 harness summary 能反映恢复态与纠偏态；
   - 最后再修改 harness / runner 实现，通过 focused pytest 验证。

4. 本轮完成标准：
   - `harness_summary` 已成为 `verification-driven` 摘要，而不是单纯 trace 计数；
   - 能在一个对象里稳定看到 verifier、loop、counterfactual、resume、coverage 的关键信号；
   - summary 字段来自现有同源 runtime 对象，不额外引入虚构状态；
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过。

## 195. Phase 2 完成记录：harness_summary 已升级为 verification-driven 控制闭环摘要（2026-07-07）
这一刀已按 TDD 落地完成。当前 worker 里的 `harness_summary` 不再只是 `mode / phase_count / warning_count` 这类轻量 trace 统计，而是已经成为同源的 `verification-driven Research Harness` 摘要。

1. 本次已完成的代码能力：
   - `ResearchHarness` 新增正式执行摘要构造逻辑，能够基于当前 runtime 同源对象输出控制闭环摘要；
   - `result_payload.harness_summary` 当前已正式包含：
     - `control_loop`
     - `verifier_gate`
     - `intent_contract`
     - `counterfactual_recovery`
     - `resume_checkpoint`
     - `evidence_coverage`
   - 摘要直接来自现有同源对象：
     - `loop_result.final_decision`
     - `local/global verifier`
     - `state_ledger`
     - `counterfactual_summary`
     - `resume_context_summary`
     - `fetched_documents`
   - 因此当前已经可以在一个对象里直接回答：
     - 当前是否收敛
     - 为什么还没收敛
     - verifier 当前在放行还是要求恢复
     - 是否走过 `反证分支`
     - 当前恢复基线是什么

2. 本次 TDD 覆盖点：
   - `test_harness.py` 已要求 `harness_summary` 返回正式控制闭环摘要；
   - `test_runner.py` 已要求 resume 场景下 `harness_summary.resume_checkpoint` 返回恢复基线信息；
   - `test_runner.py` 已要求 counterfactual 场景下 `harness_summary.counterfactual_recovery / control_loop / verifier_gate` 反映纠偏状态；
   - `test_loop_runtime.py` 保持对 loop/runtime 主链的回归覆盖。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`27 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`81 passed`。

4. 本子目标的交付意义：
   - `Phase 2` 的第一刀已经把 `Research Harness` 从“trace 容器”继续推进成“控制闭环摘要出口”；
   - 后续无论是 Java 契约、前端 audit 层，还是项目讲解，都可以直接消费同源 `harness_summary`，而不必再跨多个 payload 字段手工拼接；
   - 下一步可以继续沿 `Phase 2` 推进，把 `Dual Verifier -> loop decision -> recovery mode -> branch decision` 的关系再压成更正式的控制对象或 query 契约。

## 196. Phase 2 继续执行：把 verifier/loop/branch 关系压成正式 harness control state（2026-07-07）
在 `harness_summary` 已经能够输出 verification-driven 摘要之后，当前继续沿 `Phase 2` 推进。目标不是继续给 summary 加更多散字段，而是把 `Dual Verifier -> loop decision -> recovery mode -> branch decision` 进一步压成一个正式的 worker 控制对象，让后续 checkpoint、Java query 契约和前端 audit 层都有稳定的同源入口。

1. 当前确认的真实缺口：
   - `harness_summary` 现在已经能读，但本质上仍是“摘要对象”，还不是 worker 产物中的正式控制状态对象；
   - `loop_decision / branch_decisions / local_verifier / global_verifier / counterfactual_summary / resume_context_summary` 仍分别散落在 result payload 和 checkpoint 中；
   - 如果后续 Java 或前端要正式消费“当前控制闭环状态”，仍需要自己跨多个字段聚合；
   - 这与 `Phase 2` 里“把关系收成正式控制闭环”的目标相比，还差一层稳定契约。

2. 本轮优先交付目标：
   - 新增正式 `harness_control_state` worker 对象；
   - 该对象至少稳定承接：
     - `control_loop`
     - `verifier_gate`
     - `branch_recovery`
     - `resume_checkpoint`
     - `evidence_coverage`
   - `harness_summary` 后续改为复用该正式对象，而不是单独再拼一份旁路摘要；
   - `result_payload` 与 `research_checkpoint_candidate` 都显式透出该对象。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_harness.py / test_runner.py`，要求 `result_payload.harness_control_state` 存在且结构稳定；
   - 再补 resume / counterfactual 场景断言，要求 checkpoint 中也存在同源 `harness_control_state`；
   - 最后修改 models / harness / runner 实现，通过 focused pytest 验证。

4. 本轮完成标准：
   - worker 中存在正式 `harness_control_state`，而不只是 `harness_summary`；
   - `harness_summary` 与 `harness_control_state` 保持同源；
   - `result_payload / checkpoint` 都能显式返回该对象；
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过。

## 197. Phase 2 完成记录：verifier/loop/branch 关系已压成正式 harness control state（2026-07-07）
这一刀已按 TDD 落地完成。当前 worker 中已经存在正式 `harness_control_state`，不再只依赖 `harness_summary` 作为控制闭环的阅读出口。

1. 本次已完成的代码能力：
   - 新增正式 `ResearchHarnessControlState` 及其子对象，稳定承接：
     - `control_loop`
     - `verifier_gate`
     - `branch_recovery`
     - `resume_checkpoint`
     - `evidence_coverage`
   - `ResearchHarness` 新增正式 `build_control_state(...)`，从同源 runtime 对象构造控制状态；
   - `harness_summary` 已改为复用该正式控制对象，而不是旁路再拼一套相近结构；
   - `result_payload` 已正式透出 `harness_control_state`；
   - `research_checkpoint_candidate` 已正式透出同源 `harness_control_state`。

2. 本次 TDD 覆盖点：
   - `test_harness.py` 已要求 `result_payload.harness_control_state` 存在，且与 `harness_summary` 保持同源关键字段；
   - `test_runner.py` 已要求 resume 场景下 `harness_control_state.resume_checkpoint` 正确承接恢复基线；
   - `test_runner.py` 已要求 counterfactual 场景下 `harness_control_state.branch_recovery` 正确承接分支、原因与 active branch；
   - `test_loop_runtime.py` 继续覆盖 loop/runtime 主链回归。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`28 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`82 passed`。

4. 本子目标的交付意义：
   - `Phase 2` 不再只是在摘要层补可读性，而是已经开始形成真正的 worker 控制契约；
   - 后续无论推进 Java query 契约、checkpoint summary 还是前端 audit 层，都可以直接挂靠 `harness_control_state`；
   - 下一步最自然的是沿 `Phase 2 -> Phase 3` 交界推进，把该正式控制对象继续接入 Java 主系统 detail / history / checkpoint 查询面。

## 198. 总交付执行文档二次重写：从当前真实状态直接规划到最终交付（2026-07-07）
这一轮不继续推进某一个局部实现，而是先把总文档真正收成“从当前状态到全部完成交付”的正式执行手册。用户已经明确强调，这次要的不是“下一刀做什么”，而是覆盖剩余全程的总规划文档，因此本轮先处理文档层收口，再据此决定后续逐刀执行。

1. 当前确认的文档缺口：
   - 现有 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 虽然已经完成过一轮重排，但仍偏“阶段描述”，还不够像真正的“剩余交付总表”；
   - 文档中对“现在已经绿了什么、还红着什么、从哪一刀开始继续、每一段如何验收、最后如何演示”还可以再压得更直白；
   - 最近已经落地的 `fetch` 工具层、`harness_summary` 和 `harness_control_state` 进展，需要被吸收到新的当前状态基线里；
   - 参考 `Table-as-Search / DeepWideSearch / MiroFlow` 的吸收策略，也需要从“理念映射”进一步落到“剩余执行动作映射”。

2. 本轮文档优先交付目标：
   - 把总文档改写成“当前真实状态 -> 剩余缺口 -> 阶段主线 -> 每段执行卡片 -> 验收入口 -> 最终交付定义”的结构；
   - 明确写清当前已经完成到哪里，尤其是 worker、Java 主系统、前端展示三层各自的真实完成度；
   - 把剩余工作压成有限几段主线，避免后续重新讨论优先级；
   - 让每一段都直接对应用户关心的亮点：`Research Harness`、`Closed-Loop Research`、`Table-as-State`、`Dual Verifier`、`反证分支`。

3. 本轮执行方式：
   - 不新增代码实现，先重写主文档；
   - 保持与阶段计划、参考项目和当前代码现状一致；
   - 文档写完后，再以它作为后续实现的正式挂靠蓝图。

4. 本轮完成标准：
   - [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 能直接回答“从现在开始做到最终交付，完整顺序是什么”；
   - 文档能明确区分“已完成能力”和“剩余主红线”；
   - 文档中的剩余阶段、执行顺序、验收命令和展示口径都足够具体，后续每一刀都能直接挂靠；
   - 本轮不引入新的实现承诺，重点先把总蓝图写准。

## 199. Phase 1 继续执行：把 execution_profile 变成真实搜索编排器（2026-07-07）
按总执行文档的当前推荐顺序，正式进入 `Phase 1 / P1-A`。这次不先碰前端，也不先做 Java 承接，而是先把 worker 里已经存在的 `execution_profile` 从“描述性元数据”继续推进成真正影响 `search` 工具层执行路径的控制对象。

1. 当前确认的真实缺口：
   - `planner.py` 已经能生成 `execution_profile`，并在 `query_set` 里注入 `deep focus / coverage gap / counterfactual` 等 query，但这些更多还只是“字符串拼出来了”；
   - `search_adapters.py` 当前默认仍是按 `plan.query_set` 粗粒度执行，缺少显式的 `query family / family budget / query budget / per-query orchestration`；
   - `WorkspaceSearchAdapter` 和 `ExternalSearchAdapter` 也还没有共用同一套“当前 round 应该优先跑哪些 query family”的正式搜索计划；
   - 这意味着总文档里要求的 `wide discovery / deep read / verified-evidence search / counterfactual recheck`，目前还主要停留在 query 文本约定和 `search_lane` 推断层，尚未成为更稳定的搜索编排层。

2. 本轮优先交付目标：
   - 为 `execution_profile` 补齐正式的 `query family budgets / search query budget / per-query result budget`；
   - 在 `search_adapters.py` 中增加显式“搜索计划编排”逻辑，把 query 先分到 `direct / intent / deliverable / constraints / source_scoped / deep_focus / triangulation / coverage_gap / verified_evidence / counterfactual` 等 family，再按执行预算选择；
   - 让 `WorkspaceSearchAdapter` 与 `ExternalSearchAdapter` 共享同一套 query selection 逻辑，而不是各自散落判断；
   - 让 `search` trace 能直接反映这轮实际跑了哪些 query family，而不只是输出 `query_samples`。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_runner.py`，要求 `execution_profile` 里存在正式 query-family 预算信息；
   - 再补 `test_search_adapters.py`，要求 search adapter 能按 depth/profile 选择 query family，并在默认模式下优先覆盖 direct / source-scoped / deep-focus / coverage-gap 等家族；
   - 必要时补 `test_loop_runtime.py`，要求 tool trace 里能看到新的 query-family 编排摘要；
   - 最后再修改 `planner.py / search_adapters.py / search.py / research_tools.py` 实现，通过 focused pytest 验证。

4. 本轮完成标准：
   - `execution_profile` 不再只是“生成了几个 query”，而是具备正式搜索编排预算；
   - `WorkspaceSearchAdapter` 与 `ExternalSearchAdapter` 都能消费同一套 query-family 选择逻辑；
   - `search` tool trace 能输出这轮实际搜索家族摘要；
   - `pytest workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过。

## 200. Phase 1 完成记录：execution_profile 已变成真实搜索编排器（2026-07-07）
这一刀已按 TDD 落地完成。当前 worker 中的 `execution_profile` 不再只是生成几个不同风格的 query，而是已经开始正式控制 `search` 工具层如何选择 query family、如何分配预算、以及如何把搜索家族摘要透出到 trace。

1. 本次已完成的代码能力：
   - `planner.py` 已为 `execution_profile` 增加正式搜索编排字段：
     - `search_query_budget`
     - `per_query_result_limit`
     - `query_family_budgets`
     - 更明确的 `query_family_order`
   - `search_adapters.py` 已新增正式 query planning 层，先把 query 分到：
     - `direct`
     - `source_scoped`
     - `intent`
     - `deliverable`
     - `time_range`
     - `constraints`
     - `deep_focus`
     - `triangulation`
     - `coverage_gap`
     - `verified_evidence`
     - `direct_evidence`
     - `counterfactual`
   - `WorkspaceSearchAdapter` 与 `ExternalSearchAdapter` 当前都已复用同一套 `build_search_query_plan(...)` 逻辑，而不是再各自散落选择 query；
   - `ExternalSearchAdapter` 当前已开始按 `per_query_result_limit` 分摊单 query 的结果预算，避免“第一条 query 吃满整轮预算”；
   - `ResearchToolbox.search_web` 当前已把本轮实际选中的 query family 摘要写入 tool trace，可供后续 process 展示与 audit 复用。

2. 本次 TDD 覆盖点：
   - `test_runner.py` 已要求 `execution_profile` 里存在正式 query-family 预算信息；
   - `test_search_adapters.py` 已要求默认模式下能够构造 budget-aware query plan，并在 recovery 模式下优先跑 target queries；
   - `test_loop_runtime.py` 已要求 `TOOL_SEARCH_WEB` trace 中存在 `selected_query_count / selected_query_family_summary`；
   - 旧有 search adapter 测试也继续覆盖 provider fallback chain 与 recovery-targeted external search。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_search_adapters.py workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`33 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`84 passed`。

4. 本子目标的交付意义：
   - `Phase 1 / P1-A` 不再只是“工具层已经拆成 search/fetch/read”，而是开始把 search 本身也做成 execution-profile 驱动的正式编排层；
   - 这让总文档里要求的 `wide discovery / deep read / verified-evidence search / counterfactual recheck` 开始从 query 文本约定走向稳定 runtime 行为；
   - 下一步最自然的是沿 `Phase 1` 继续推进 `P1-B`：把 requirement-first 检索与读取做得更显式，让 query family、row/cell 缺口和 read focus 进一步收成同源闭环。

## 201. Phase 1 继续执行：把 requirement-first 检索与读取接入主闭环（2026-07-07）
按总执行文档的当前执行顺序，正式进入 `Phase 1 / P1-B`。这一刀不做展示层，也不先碰 Java，而是继续把 worker 里的 `search -> read -> state` 主链改成真正围绕 requirement 推进，而不是只围绕“问题整体”泛搜泛读。

1. 当前确认的真实缺口：
   - `planner.py` 当前虽然已经有 `required_finding_contract`，但首轮 `query_set` 仍然主要由问题、目标、约束、source_scope 衍生，缺少更显式的 requirement-targeted query；
   - `read_adapters.py` 目前只在 recovery 模式下把 `recovery_target_requirement_labels / recovery_target_columns` 写进 `read_focus`，首轮正常研究时还没有把 requirement 焦点显式带到 read window；
   - `state.py` 当前对 row 的 requirement 绑定仍偏宽松，默认一行 evidence 往往会匹配全部 `ROW_EVIDENCE` requirement，而不是优先绑定到这次 query / read 真正瞄准的 requirement；
   - 这意味着总文档里要求的“按 requirement、row、cell 定向搜读”，当前更多还停留在 verifier / recovery 提示层，没有真正压回 `planner -> read -> ledger` 主链。

2. 本轮优先交付目标：
   - 让 `planner.py` 在首轮就能生成 requirement-targeted queries，至少覆盖 `GOAL_FINDING / CONSTRAINT_FINDING / CONFLICT_FINDING` 的显式检索入口；
   - 让 `read_adapters.py` 把 requirement ids、labels、target columns 和 query family 显式带进 `ResearchReadWindow`，而不是只保留自然语言 `read_focus`；
   - 让 `state.py` 在构建 ledger row / cell 时优先消费 read window 上的 requirement target，避免一行证据被宽泛绑定到所有 requirement；
   - 让 requirement-first 检索、读取、ledger 绑定三步形成同源链。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_runner.py`，要求 planner 在首轮 query_set 中显式包含 requirement-targeted query；
   - 再补 `test_read_adapters.py`，要求 read window 返回 requirement target metadata，并把 requirement/column 焦点写入 `read_focus`；
   - 必要时补 `test_llm_extract_verify.py`，要求 ledger 优先使用 read window requirement target 做更精确的 requirement 绑定；
   - 最后再修改 `models.py / planner.py / read_adapters.py / state.py` 实现，通过 focused pytest 验证。

4. 本轮完成标准：
   - 首轮 `query_set` 已显式包含 requirement-targeted query；
   - `ResearchReadWindow` 已正式携带 requirement target metadata；
   - `build_state_ledger(...)` 已优先消费 requirement-targeted read window，而不是宽泛匹配全部 requirement；
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py` 通过。

## 202. Phase 1 完成记录：requirement-first 检索与读取已接入主闭环（2026-07-07）
这一刀已按 TDD 落地完成。当前 worker 已经不再只是“恢复态才提 requirement”，而是把 requirement-targeted query、requirement-targeted read window 和更精确的 ledger 绑定一起压回了 `planner -> read -> state` 主链。

1. 本次已完成的代码能力：
   - `planner.py` 当前已在首轮 `query_set` 中显式产出 requirement-targeted query，至少覆盖：
     - `GOAL_FINDING -> direct answer with evidence`
     - `CONSTRAINT_FINDING -> verified evidence search`
     - `CONFLICT_FINDING -> counterfactual evidence check`
   - `ResearchReadWindow` 当前已新增正式 target metadata：
     - `query_family`
     - `target_requirement_ids`
     - `target_requirement_labels`
     - `target_columns`
   - `read_adapters.py` 当前会基于 query family 和 `required_finding_contract` 自动推导 requirement target，而不再只依赖 recovery-mode 的临时提示字段；
   - `state.py` 当前在构建 ledger row / cell 时，已经会优先消费 read window 上的 targeted requirement ids，避免单条 evidence 宽泛匹配全部 requirement。

2. 本次 TDD 覆盖点：
   - `test_runner.py` 已要求首轮 plan 直接包含 requirement-targeted query；
   - `test_read_adapters.py` 已要求 read window 返回 requirement target metadata，并在 `read_focus` 中反映 requirement/column 焦点；
   - `test_llm_extract_verify.py` 已要求 ledger 优先按 targeted requirement ids 绑定，而不是继续把一行 evidence 默认挂到所有 requirement；
   - 旧有 runner / read / extract 测试继续覆盖主链回归。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_llm_extract_verify.py` 通过；
   - 结果：`47 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`86 passed`。

4. 本子目标的交付意义：
   - `Phase 1 / P1-B` 已经把“按 requirement 定向搜读”从 verifier 提示层压回了主执行链；
   - 后续继续做 `P1-C` 时，row/cell/source/evidence 的来源锚定就有了更明确的 requirement-first 上下文，而不是只有模糊自然语言 `read_focus`；
   - 下一步最自然的是继续沿 `Phase 1` 推进 `P1-C`：把 evidence binding 与来源锚定报告做得更稳定，让报告、evidence、row/cell、source 真正形成同源闭环。

## 203. 总交付执行文档三次收口：把“从现在到全部完成交付”的路线压成正式执行手册（2026-07-07）
这一轮不直接继续某一个代码子卡，而是先把总文档再收一版，明确回答“以当前真实状态为起点，到 Deep Research 全量完成交付为止，完整顺序、每段目标、每段产出、每段验收是什么”。用户已经明确要求这次先给“全程交付蓝图”，而不是只写下一刀。

1. 当前确认的文档缺口：
   - 现有 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 已经具备总文档雏形，但还偏“阶段描述”，还不够像“从当前到 100% 交付”的正式执行手册；
   - 当前文档虽已写清阶段顺序，但对“当前已完成到哪里、下一步精确从哪张卡继续、每个阶段产出什么成品、何时算可交付”还可以再压得更直白；
   - 用户已经多次强调“先把功能搭起来”“重点是 agent 内部能力”“展示层最后做”，所以总文档需要进一步把顺序冻结成：先内核和 harness，再 Java 契约，再展示和 demo；
   - 参考 `Table-as-Search / DeepWideSearch / MiroFlow` 的吸收方式也需要从“理念映射”再收成“实现卡片映射”，方便后续每一刀直接落地。

2. 本轮文档交付目标：
   - 把总文档重写成“当前基线 -> 最终交付定义 -> 参考项目吸收映射 -> 分阶段执行 -> 当前推荐顺序 -> 统一验收入口”的正式手册；
   - 明确写出当前实际已完成能力，特别是 `fetch` 工具层、`harness_control_state`、`execution_profile` 搜索编排、requirement-first 检索读取这几项真实进展；
   - 明确写出当前离完整交付还差哪些关键卡片，尤其是 `P1-C / P2-A / P2-B / P3-* / Phase 4 / Phase 5`；
   - 让后续继续实现全部 agent 时，不再需要先讨论“先做哪块”，而是直接按文档执行。

3. 本轮执行方式：
   - 先更新阶段计划，再重写总执行文档；
   - 不在这一刀引入新的代码实现；
   - 文档内容必须与当前真实代码状态、用户冻结的产品边界、以及三份参考项目吸收策略保持一致。

4. 本轮完成标准：
   - [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 能直接回答“从现在开始做到全部完成交付，完整路线是什么”；
   - 文档显式给出当前进度判断、当前下一步、阶段卡片和验收口径；
   - 后续继续实现时，可以不再重排优先级，直接按该文档推进全部 agent。

## 204. 总交付执行文档完成记录：已收成“从当前状态到全部完成交付”的正式执行手册（2026-07-07）
这一轮文档收口已完成。本次没有继续推进代码子卡，而是先把总文档压成后续全部执行的统一蓝图，确保下一步开始实现时不再反复讨论优先级和目标边界。

1. 本次文档已完成的核心收口：
   - 已把 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 改写为正式执行手册，而不再只是“阶段说明文档”；
   - 已明确写清当前真实完成状态，尤其吸收了最近几刀的真实进展：
     - `fetch` 工具层
     - `harness_control_state`
     - `execution_profile` 搜索编排
     - requirement-first 检索与读取
   - 已明确冻结从当前到交付的严格顺序：
     - `P1-C`
     - `P2-A / P2-B / P2-C`
     - `P3-A / P3-B / P3-C`
     - `Phase 4`
     - `Phase 5`
   - 已把 `Table-as-Search / DeepWideSearch / MiroFlow` 的参考吸收方式收成“长处 -> NoteWeave 吸收目标 -> 主要落地区域 -> 当前状态”的映射表。

2. 本次文档相比上一版新增的交付价值：
   - 不再只说“还有哪些阶段”，而是明确写出“当前的精确下一步”与“严格执行顺序”；
   - 不再只说“参考了哪些项目”，而是明确写出参考长处具体落到哪些模块；
   - 不再只说“最后要做什么”，而是给出每个阶段的正式产出物清单和里程碑定义；
   - 后续继续实现全部 agent 时，可以直接按该文档推进，不需要再先做一轮路线重排。

3. 本轮完成后的执行意义：
   - 这份总文档现在已经可以作为后续全部开发、联调、展示与讲解的主蓝图；
   - 之后继续推进时，默认直接从 `Phase 1 / P1-C` 开始，先补 evidence binding 与来源锚定报告，再进入 Harness 收口；
   - 这也保证了后续实现顺序继续符合用户已经冻结的原则：先 agent 内核和验证闭环，再 Java 契约，再展示层。

## 205. Phase 1 继续执行：把 evidence binding 与来源锚定报告做成同源 provenance 闭环（2026-07-07）
按总交付执行文档冻结的顺序，正式进入 `Phase 1 / P1-C`。这一刀不扩前端，也不先碰 Java，而是继续把 worker 里的 `evidence -> row/cell -> source -> report -> artifact` 主链收实，避免报告仍停留在“摘要层引用”而不是“正式 provenance 对象”。

1. 当前确认的真实缺口：
   - `ResearchEvidenceCard` 虽然已经有 `window_id / source_id / source_title`，但报告层还没有把它稳定升级成正式 `source_ref / read_window / row / cell / requirement` provenance 对象；
   - `reporter.py` 里的 `verified_findings / evidence_ledger / source_foundation` 当前更偏摘要视图，缺少“同一条 finding 对应哪条 evidence、哪条 row、哪些 cell、哪个 read window、哪个 query family、哪些 requirement”的强绑定；
   - `research_artifact_candidate` 当前虽然已经承接 `report_structure`，但还没有显式承接一层适合 Java / 前端继续消费的 provenance-ready 绑定对象；
   - 这意味着 `P1-B` 已经打通的 requirement-first 搜读上下文，尚未真正收进正式报告结果物。

2. 本轮优先交付目标：
   - 让 `report_structure["verified_findings"]` 和 `report_structure["evidence_ledger"]` 显式带出 provenance 绑定；
   - 至少补齐以下锚点：
     - `source_ref`
     - `window_id`
     - `row_id`
     - `cell_ids`
     - `query_family`
     - `target_requirement_ids`
     - `target_columns`
   - 让 `source_foundation` 与 `research_artifact_candidate` 一并保留这层同源 provenance 信息；
   - 让 markdown、结构化报告、artifact 三者继续基于同一套 provenance 数据生成。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_runner.py`，要求 `report_structure` 与 `research_artifact_candidate` 显式透出 stronger provenance binding；
   - 再补 `test_llm_extract_verify.py`，要求 `verified_findings / evidence_ledger` 里的 provenance 对象与真实 row / cell / read window / evidence 同源；
   - 最后再修改 `reporter.py`，必要时补 `models.py / runner.py`，通过 focused pytest 验证。

4. 本轮完成标准：
   - `report_structure["verified_findings"]` 中存在正式 provenance 绑定对象；
   - `report_structure["evidence_ledger"]` 中存在同源 row / cell / window / requirement 锚点；
   - `research_artifact_candidate` 保留同源 provenance-ready 报告对象；
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_llm_extract_verify.py` 通过；
   - `pytest workers/research-worker/tests` 全量通过。

## 206. Phase 1 完成记录：evidence binding 与来源锚定报告已收成 provenance 闭环（2026-07-07）
这一刀已按 TDD 落地完成。当前 worker 的 `report_structure / report_markdown / research_artifact_candidate` 已不再只是“摘要层引用 evidence”，而是开始正式保留 `source -> window -> row/cell -> requirement -> evidence` 的同源 provenance 关系。

1. 本次已完成的代码能力：
   - `reporter.py` 当前在构建 `verified_findings / evidence_ledger` 时，已基于同源 runtime 对象补齐 provenance 绑定：
     - `source_ref`
     - `window_ref`
     - `search_ref`
     - `evidence_ref`
     - `row_ref`
     - `cell_refs`
     - `requirement_ref`
   - 每条 finding / evidence ledger 当前都已显式透出：
     - `window_id`
     - `cell_ids`
     - `query_family`
     - `target_requirement_ids`
     - `target_requirement_labels`
     - `target_columns`
     - `matched_requirement_ids`
     - `ready_requirement_ids`
   - `report_structure` 当前已新增正式 `provenance_bindings`，可供后续 Java 与前端继续同源消费；
   - `source_foundation` 当前已新增 `source_refs` 聚合视图，把来源、window、row、cell、evidence、query family、requirement target 收成一层来源锚点摘要；
   - `research_artifact_candidate` 当前已显式承接 `provenance_bindings`，不再只承接 markdown 和 report_structure；
   - markdown 中的 verified findings 当前也已补出基础来源锚点字段，能直接看到 `source_ref / window / query_family / requirements`。

2. 本次 TDD 覆盖点：
   - `test_runner.py` 已要求 `report_structure["verified_findings"]` 存在正式 provenance 绑定；
   - `test_runner.py` 已要求 `research_artifact_candidate["provenance_bindings"]` 保留同源 provenance；
   - `test_llm_extract_verify.py` 已要求 `evidence_ledger` 只绑定真实 evidence，且显式透出 `source_ref / window_ref / row_ref / cell_refs`；
   - `test_llm_extract_verify.py` 已要求 requirement-targeted read window 的 `query_family / target_requirement_ids / target_columns` 能继续穿透到最终报告 provenance。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_llm_extract_verify.py` 通过；
   - 结果：`41 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`87 passed`。

4. 本子目标的交付意义：
   - `Phase 1 / P1-C` 已经把 `P1-B` 的 requirement-first 搜读上下文正式压进报告结果物；
   - 当前 Deep Research 的第一层亮点已经从“有 search/fetch/read 和 verifier”进一步收成了“有可追溯 provenance 的正式研究报告”；
   - 下一步最自然的是沿总文档继续进入 `Phase 2 / P2-A`：开始建立 Research Harness 回归任务集，把冲突证据、恢复重开、反证纠偏和收口成文做成稳定验收口径。

## 207. Phase 2 继续执行：建立 Research Harness 回归任务集（2026-07-07）
按总交付执行文档冻结的顺序，正式进入 `Phase 2 / P2-A`。这一刀先不急着改 verifier 收口逻辑，而是先把 `Research Harness` 的代表性研究场景收成正式 regression suite，确保后续 `P2-B / P2-C` 有稳定、可复用、可扩展的验收基线。

1. 当前确认的真实缺口：
   - `harness_summary / harness_control_state` 已经存在，但当前仍主要依赖零散测试覆盖，缺少一层正式“回归任务集”来定义哪些场景必须稳定通过；
   - 冲突证据、范围扩张、恢复重开、反证纠偏、收口成文这些 Phase 2 关键场景虽然分别已有一些测试影子，但还没有收成统一的 scenario catalog 与通用评估方式；
   - 这会导致后续继续调整 verifier、loop decision、branch recovery 时，缺少一个面向 Deep Research 闭环本身的稳定验收入口。

2. 本轮优先交付目标：
   - 建立正式 `Research Harness Regression Suite`；
   - 至少覆盖以下场景：
     - `ready synthesis / guarded write`
     - `scope expansion`
     - `conflict counterfactual recheck`
     - `resume extract again`
     - `resume counterfactual recovery`
   - 让 suite 同时具备：
     - 场景定义
     - 通用评估函数
     - 集成测试入口
   - 让后续 `P2-B / P2-C`、甚至 Java / UI audit 层都能继续复用这层 suite 元数据与验收口径。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_harness.py`，要求存在正式 regression suite 与 scenario evaluator；
   - 必要时补 `test_loop_runtime.py`，要求关键 loop/runtime 场景可通过 suite 统一验收；
   - 最后再实现 `app` 侧 suite 定义与评估支撑代码，必要时把 suite 元数据透出到 harness summary 或 result payload。

4. 本轮完成标准：
   - worker 侧存在正式 `Research Harness Regression Suite`；
   - 至少 5 个代表性场景可通过统一 evaluator 验收；
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - `pytest workers/research-worker/tests` 全量通过。

## 208. Phase 2 完成记录：Research Harness 回归任务集已落成第一版 regression suite（2026-07-07）
这一刀已按 TDD 落地完成。当前 `Research Harness` 已不再只依赖零散测试覆盖，而是拥有第一版正式回归任务集，可用于稳定验收 Deep Research 闭环中的关键研究场景。

1. 本次已完成的代码能力：
   - 新增正式 [harness_regression.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/harness_regression.py)，提供：
     - `ResearchHarnessRegressionCase`
     - `build_harness_regression_suite()`
     - `build_harness_regression_suite_summary()`
     - `evaluate_harness_regression_case(...)`
   - 第一版 regression suite 当前已覆盖 5 个代表性场景：
     - `ready_synthesis`
     - `scope_expansion`
     - `conflict_counterfactual`
     - `resume_extract_again`
     - `resume_counterfactual`
   - `harness.py` 当前已把 suite 元数据透出到 `harness_summary["regression_suite"]`，为后续 audit/UI 层继续消费打下基础；
   - `test_harness.py` 当前已基于统一 evaluator 跑完整场景矩阵，不再只是单点字段断言。

2. 本次 TDD 覆盖点：
   - `test_harness.py` 已要求存在正式 regression suite 与固定 case key 顺序；
   - `test_harness.py` 已按参数化方式对 5 个代表性场景统一执行 evaluator 验收；
   - `test_harness.py` 已要求 `harness_summary` 显式透出 suite 元数据；
   - `test_loop_runtime.py` 继续覆盖 loop/runtime 主链，确保 suite 引入后不影响原有闭环行为。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`20 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`94 passed`。

4. 本子目标的交付意义：
   - `Phase 2 / P2-A` 已经把“回归任务集”从口头要求收成了正式 suite；
   - 后续继续推进 `P2-B / P2-C` 时，verifier-gated 收口逻辑和 summary-first 审计对象都可以直接挂到这套场景基线上；
   - 下一步最自然的是沿总文档继续进入 `Phase 2 / P2-B`：把什么时候继续搜、什么时候重开读、什么时候开反证分支、什么时候允许成文，收成稳定 verifier-gated 口径。

## 209. Phase 2 继续执行：把 verifier-gated 收口口径做成正式 gate policy 对象（2026-07-07）
按总交付执行文档冻结的顺序，正式进入 `Phase 2 / P2-B`。这一刀不再新增场景，而是把 loop decision、local/global verifier、branch recovery 之间已经存在的判断逻辑收成一个正式 `verifier_gate_policy` 对象，让系统能明确表达“为什么继续循环、为什么允许 guarded write、为什么允许 final write”。

1. 当前确认的真实缺口：
   - 当前 `evaluate_loop_decision(...)`、`run_local_verifier(...)`、`run_global_verifier(...)` 里已经分散存在收口逻辑，但缺少一层正式的、可直接消费的 gate policy 对象；
   - `harness_control_state.verifier_gate` 目前更偏状态摘要，还不能完整回答：
     - 什么时候继续搜
     - 什么时候重开读
     - 什么时候重做抽取
     - 什么时候开反证分支
     - 什么时候允许 guarded write
     - 什么时候允许 final write
   - 这会导致后续 Java/query object/UI audit 层若要展示 verifier-gated 决策，仍然需要拼凑 `loop_decision + global_decision + warnings + recovery_mode`。

2. 本轮优先交付目标：
   - 新增正式 `verifier_gate_policy` 对象；
   - 至少显式编码：
     - `loop_gate_action`
     - `report_gate_action`
     - `should_continue_loop`
     - `allows_final_write`
     - `allows_guarded_write`
     - `requires_source_expansion`
     - `requires_read_more`
     - `requires_extract_again`
     - `requires_counterfactual_recheck`
     - `blocking_reason_codes`
   - 让该对象进入：
     - `harness_control_state`
     - `harness_summary`
     - `result_payload`
     - 必要时 `checkpoint_candidate`
   - 让 suite/evaluator 开始验证 gate policy 与实际 loop/global verifier 的一致性。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_harness.py`，要求 runner 结果里存在正式 `verifier_gate_policy`；
   - 再补 `test_loop_runtime.py`，要求 gate policy 能覆盖 `READ_MORE / EXTRACT_AGAIN / COUNTERFACTUAL_RECHECK / EXPAND_SOURCE_SCOPE / final write` 等典型收口语义；
   - 最后再补 `models.py / harness.py`，必要时新增独立 policy builder 模块，并把对象接回 runner payload。

4. 本轮完成标准：
   - worker 侧存在正式 `verifier_gate_policy`；
   - gate policy 与 `final_decision / global_decision / recovery_mode / branch_decision` 保持一致；
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - `pytest workers/research-worker/tests` 全量通过。

## 210. Phase 2 完成记录：verifier-gated 收口口径已落成正式 gate policy 对象（2026-07-07）
这一刀已按 TDD 落地完成。当前 worker 已不再需要由上层系统自行拼接 `loop_decision + global_decision + recovery_mode + branch_decision` 来理解 verifier-gated 收口逻辑，而是已经存在正式 `verifier_gate_policy` 对象。

1. 本次已完成的代码能力：
   - 新增正式 [verifier_gate_policy.py](/D:/java-projects/NoteWeave-v2/workers/research-worker/app/verifier_gate_policy.py)，提供：
     - `report_gate_action_from_global_decision(...)`
     - `build_verifier_gate_policy_state(...)`
     - `infer_loop_gate_action(...)`
   - `models.py` 当前已新增正式 `ResearchHarnessVerifierGatePolicyState`，显式编码：
     - `loop_gate_action`
     - `final_loop_decision`
     - `report_gate_action`
     - `should_continue_loop`
     - `allows_final_write`
     - `allows_guarded_write`
     - `requires_source_expansion`
     - `requires_read_more`
     - `requires_extract_again`
     - `requires_counterfactual_recheck`
     - `blocking_reason_codes`
   - `harness.py` 当前已把该对象接入 `harness_control_state`，并通过 `harness_summary` 继续透出；
   - `runner.py` 当前已把 `verifier_gate_policy` 正式透出到：
     - `result_payload`
     - `research_checkpoint_candidate`
   - `harness_regression.py` 当前已开始要求 regression evaluator 校验 gate policy 与 control loop / global verifier 的一致性。

2. 本次 TDD 覆盖点：
   - `test_harness.py` 已要求 runner 结果里存在正式 `verifier_gate_policy`；
   - `test_harness.py` 已要求 `harness_control_state / harness_summary / result_payload` 三处的 gate policy 保持同源；
   - `test_harness.py` 已要求多个代表性场景的 gate policy 能正确表达：
     - `EXPAND_SOURCE_SCOPE`
     - `COUNTERFACTUAL_RECHECK`
     - `EXTRACT_AGAIN`
   - `test_loop_runtime.py` 已要求 gate policy 能正确表达 `READ_MORE` 的 verifier-gated 收口语义；
   - regression evaluator 当前也已开始校验 `final_loop_decision` 与 `report_gate_action`。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`26 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`100 passed`。

4. 本子目标的交付意义：
   - `Phase 2 / P2-B` 已经把“什么时候继续搜、什么时候重开读、什么时候重做抽取、什么时候开反证、什么时候允许 guarded/final write”收成了正式对象；
   - 后续 Java / query object / UI audit 层不再需要自行拼接这些判断；
   - 下一步最自然的是沿总文档继续进入 `Phase 2 / P2-C`：把 checkpoint / counterfactual / resume / evidence coverage 收成 summary-first 审计对象。

## 211. 总交付执行文档重构启动：从当前真实状态直接规划到全部完成交付（2026-07-07）
这一轮不推进单张实现卡，而是先把 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 升级成一份真正可执行的“从现在到全部完成交付”的总手册，避免后续继续在局部实现里反复重排顺序。

1. 本轮重构的直接原因：
   - 现有总文档虽然已经不是“这一轮要做什么”的短记，但仍然停留在 `P2-B / P2-C` 并列待做的旧状态，没有准确反映 `P2-B` 已完成、`P2-C` 才是当前第一优先级；
   - 现有路线里对“agent 内部还差哪些真正值得补厚的能力”写得还不够细，尤其是 `search / fetch / read / verify / branch / report` 这条研究主链还需要更明确地吸收 `Table-as-Search / DeepWideSearch / MiroFlow` 的长处；
   - 用户已经明确要求“不是只规划这一轮，而是直接给出从当前情况到最终全部交付的文档”，因此需要把剩余阶段拆到可执行卡片级别。

2. 本轮总文档必须补齐的内容：
   - 明确同步当前真实进度：`P1-C / P2-A / P2-B` 已完成，下一步是 `P2-C`；
   - 把剩余工作按“agent 内核 -> 工具栈 -> Java 契约 -> 前端展示 -> demo 交付”冻结为单向执行顺序；
   - 补强参考项目吸收策略，明确每个参考项目的优点到底落到哪张执行卡里；
   - 对每个剩余阶段写清楚目标、产出物、验收口径、依赖关系和完成定义。

3. 本轮文档重构完成标准：
   - 总文档可以直接作为后续全部实现的唯一主蓝图；
   - 不再出现“这一轮计划”和“总交付规划”混写；
   - 后续继续开发时，默认只需要更新阶段计划并按总文档执行，不再先重做路线讨论。

## 212. 总交付执行文档重构完成：总路线已同步到当前真实进度并冻结到最终交付（2026-07-07）
这一轮文档重构已完成。当前 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 已经从“阶段性路线说明”升级为“从当前状态到全部完成交付”的正式执行手册。

1. 本次重构后的核心变化：
   - 已同步当前真实进度：`P1-C / P2-A / P2-B` 标记为已完成，`P2-C` 明确为当前下一步；
   - 已把后续剩余工作冻结为面向最终交付的单向顺序，重点仍然先收 `Research Harness / Closed-Loop Research / Table-as-State / Dual Verifier / 反证分支`；
   - 已把 `Table-as-Search / DeepWideSearch / MiroFlow` 的吸收策略明确映射到后续 agent 内核、工具栈、Java 契约与展示层执行卡；
   - 已补齐“目标 / 产出物 / 验收 / 里程碑 / 不做什么 / 完成定义”，后续可以直接按文档推进全部实现。

2. 这次文档重构的实际交付意义：
   - 后续不再需要先争论“这一轮是不是该先做前端或先做别的”，因为顺序已经冻结；
   - 后续每一刀都可以直接挂靠到总文档中的某个阶段或卡片；
   - 这份文档本身已经能作为项目交付、联调、展示和简历亮点讲解时的总蓝图。

## 213. Phase 2 继续执行：补齐 summary-first 审计对象（2026-07-07）
按总交付执行文档冻结的顺序，正式进入 `Phase 2 / P2-C`。这一刀先不扩工具栈，也不碰前端，先把 `Research Harness` 对外暴露的过程/审计摘要从“零散字段 + trace 事后拼装”收成正式 `audit summaries` 对象。

1. 当前确认的真实缺口：
   - `harness_control_state` 里虽然已经有 `resume_checkpoint / evidence_coverage / branch_recovery / verifier_gate_policy`，但它更偏运行时控制状态，不是专门给 `process / audit` 层消费的 summary-first 对象；
   - `result_payload` 当前只有零散的 `counterfactual_summary`、`resume_context_summary`、`research_checkpoint_candidate`，还没有一层正式归拢的：
     - `checkpoint_summary`
     - `counterfactual_summary`
     - `resume_summary`
     - `evidence_coverage_summary`
   - `harness_summary` 里目前仍混用 `counterfactual_recovery / resume_checkpoint / evidence_coverage`，命名和语义还不够稳定，后续 Java / UI 若直接消费，仍然容易退回到“挑字段拼视图”。

2. 本轮优先交付目标：
   - 新增正式 `audit_summaries` 对象，至少包含：
     - `checkpoint_summary`
     - `counterfactual_summary`
     - `resume_summary`
     - `evidence_coverage_summary`
   - 让该对象进入：
     - `harness_summary`
     - `result_payload`
     - `research_checkpoint_candidate`
   - 保证这些对象和现有 `harness_control_state / verifier_gate_policy / counterfactual_summary / resume_context_summary` 同源，不新增一套相互漂移的“展示专用假数据”。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_harness.py`，要求 `audit_summaries` 正式出现，并校验四个 summary 对象的最小契约；
   - 必要时补 `test_runner.py`，要求 checkpoint candidate 与 top-level payload 的 `audit_summaries` 保持同源；
   - 最后再改 `models.py / harness.py / runner.py`，必要时新增独立 audit summary builder。

4. 本轮完成标准：
   - worker 侧存在正式 `audit_summaries`；
   - 四个 summary 对象都能被 `harness_summary / result_payload / research_checkpoint_candidate` 直接消费；
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - `pytest workers/research-worker/tests` 全量通过。

## 214. Phase 2 完成记录：summary-first 审计对象已落成正式 audit summaries（2026-07-07）
这一刀已按 TDD 落地完成。当前 worker 已不再只靠 `counterfactual_summary / resume_context_summary / harness_control_state` 零散字段去支撑后续 `process / audit` 消费，而是已经存在正式 `audit_summaries` 对象。

1. 本次已完成的代码能力：
   - `models.py` 当前已新增正式：
     - `ResearchHarnessCheckpointSummaryState`
     - `ResearchHarnessCounterfactualSummaryState`
     - `ResearchHarnessResumeSummaryState`
     - `ResearchHarnessEvidenceCoverageSummaryState`
     - `ResearchHarnessAuditSummaryState`
   - `harness.py` 当前已新增 `build_audit_summaries(...)`，把以下四类摘要收成同源正式对象：
     - `checkpoint_summary`
     - `counterfactual_summary`
     - `resume_summary`
     - `evidence_coverage_summary`
   - `runner.py` 当前已把 `audit_summaries` 正式接入：
     - `result_payload`
     - `research_checkpoint_candidate`
     - `harness_summary`
   - 这些摘要继续复用现有 `harness_control_state / verifier_gate_policy / counterfactual_summary / resume_context_summary / intent_completion_contract`，没有额外制造一套会漂移的展示专用状态。

2. 本次 TDD 覆盖点：
   - `test_harness.py` 已要求 `audit_summaries` 正式存在于 `result_payload / harness_summary / research_checkpoint_candidate`；
   - `test_harness.py` 已要求普通完成态能正确输出：
     - checkpoint 收口摘要
     - 非反证态摘要
     - 非恢复态摘要
     - evidence coverage 摘要
   - `test_harness.py` 已要求 `resume + counterfactual` 场景能正确输出：
     - `source_final_loop_decision`
     - `source_active_branch_id`
     - `counterfactual_branch_ids`
     - `target_evidence_ids`
     - `conflicted_row_count`
   - `test_runner.py / test_loop_runtime.py` 回归通过，确认新摘要对象接入后没有破坏主链闭环。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_harness.py` 通过；
   - 结果：`19 passed`；
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`24 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`102 passed`。

4. 本子目标的交付意义：
   - `Phase 2 / P2-C` 已经把“checkpoint / counterfactual / resume / evidence coverage”从零散状态字段收成了 summary-first 审计对象；
   - 后续 Java / query object / UI audit 层可以直接消费 `audit_summaries`，不必再从 trace 和散落字段里事后拼接；
   - 下一步最自然的是沿总文档继续进入 `Phase 2 / P2-D`：补厚 `Research Toolbox`，重点加强外部 `search / fetch / read` 的结构化编排、fallback 与查询可见性。

## 215. Phase 2 继续执行：补厚 Research Toolbox 的结构化状态与查询可见性（2026-07-07）
按总交付执行文档冻结的顺序，正式进入 `Phase 2 / P2-D`。这一刀先不去碰 Java 契约和前端，而是先把 worker 内部 `search / fetch / read` 工具层补成更稳定、可审计、可查询、可展示的正式 `Research Toolbox`。

1. 当前确认的真实缺口：
   - `tool_traces` 虽然已经能输出 `provider_resolution_summary / fetch_status_summary / read_strategy_summary`，但仍然更像调试 trace，不是一个正式、稳定、可直接给 `process` 层消费的工具摘要对象；
   - `ResearchFetchedDocument / ResearchReadWindow` 当前虽然保留了 `fetch_status / fetch_method / content_origin / fetch_error_reason / transport_resolution`，但缺少更稳定的结构化分类，比如：
     - 内容类型或文档类型
     - 失败分类 code
     - fallback 分类 code
   - 这会导致后续前端和 Java 如果要回答“抓到的是网页还是 fallback 摘要？这次失败是没 transport、空正文还是不支持的内容类型？哪些窗口是 requirement-targeted？”仍然需要重新解释自由文本字段。

2. 本轮优先交付目标：
   - 强化 `ResearchFetchedDocument / ResearchReadWindow` 的结构化工具状态，补齐：
     - 文档/内容类型分类
     - fetch failure code
     - transport fallback code
   - 新增正式 `toolbox_summary` 对象，至少包含：
     - `search_summary`
     - `fetch_summary`
     - `read_summary`
   - 让该对象进入：
     - `result_payload`
     - `research_checkpoint_candidate`
     - `harness_summary`
   - 保证它不是从 UI 角度拼文案，而是直接复用真实 `search_hits / fetched_documents / read_windows / plan / tool_traces` 计算出来的查询对象。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_fetch_adapters.py / test_read_adapters.py`，要求 fetch/read 模型显式保留文档类型与失败分类；
   - 再补 `test_harness.py`，要求正式输出 `toolbox_summary`，且 `search / fetch / read` 三层摘要与底层 runtime 对象同源；
   - 最后再改 `models.py / fetch_adapters.py / read_adapters.py / harness.py / runner.py`，必要时补独立 toolbox summary builder。

4. 本轮完成标准：
   - worker 侧存在正式 `toolbox_summary`；
   - `ResearchFetchedDocument / ResearchReadWindow` 存在稳定的结构化失败/回退分类；
   - `pytest workers/research-worker/tests/test_fetch_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_harness.py` 通过；
   - `pytest workers/research-worker/tests` 全量通过。

## 216. Phase 2 进展记录：Research Toolbox 首轮结构化状态与 toolbox summary 已落地（2026-07-07）
这一刀已按 TDD 落地完成，但它是 `P2-D` 的第一刀，不是整个 `P2-D` 的终点。当前 worker 的 `Research Toolbox` 已经开始从“只有 tool traces”升级为“既有底层对象，也有正式查询摘要”的状态。

1. 本次已完成的代码能力：
   - `ResearchFetchedDocument / ResearchReadWindow` 当前已新增正式结构化字段：
     - `content_type_label`
     - `fetch_failure_code`
     - `transport_fallback_code`
   - `fetch_adapters.py` 当前已把以下能力收进正式 fetch 状态：
     - workspace 文档类型标记
     - `NO_TRANSPORT` fallback 分类
     - `FETCH_RETRY_EXHAUSTED` 失败分类
     - `EMPTY_FETCH_TEXT` 失败分类
     - `UNSUPPORTED_CONTENT_TYPE` 失败分类
     - `WEBPAGE / PDF / TEXT / SEARCH_SNIPPET_FALLBACK / WORKSPACE_TEXT` 等内容类型标记
   - `read_adapters.py` 当前已把 fetch 层的结构化状态继续透传到 read window，不再在读窗口层丢失失败/回退语义；
   - `models.py / harness.py / runner.py` 当前已新增并接入正式 `toolbox_summary`，至少覆盖：
     - `search_summary`
     - `fetch_summary`
     - `read_summary`
   - `toolbox_summary` 当前已进入：
     - `result_payload`
     - `research_checkpoint_candidate`
     - `harness_summary`

2. 本次 TDD 覆盖点：
   - `test_fetch_adapters.py` 已要求 fetch 文档显式保留：
     - 内容类型
     - fetch failure code
     - transport fallback code
   - `test_fetch_adapters.py` 已覆盖：
     - workspace 文档
     - 无 transport 的 fallback
     - transport 直接成功
     - 组合 transport fallback 链
     - `UNSUPPORTED_CONTENT_TYPE` 的结构化失败保留
   - `test_read_adapters.py` 已要求 read window 继续保留上述结构化状态；
   - `test_harness.py` 已要求正式输出 `toolbox_summary`，并要求它在 `result_payload / harness_summary / research_checkpoint_candidate` 三处同源；
   - `test_harness.py` 已要求 `counterfactual` 恢复场景里，`toolbox_summary` 能显式给出：
     - `counterfactual` query family
     - `COUNTERFACTUAL_DEEP_READ`
     - `WORKSPACE_TEXT` 内容类型

3. 验证结果：
   - `pytest workers/research-worker/tests/test_fetch_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_harness.py` 通过；
   - 结果：`36 passed`；
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`24 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`106 passed`。

4. 本刀的交付意义与下一步：
   - 当前 `process` 层后续已经不必只读 `tool_traces`，而是可以直接消费正式 `toolbox_summary`；
   - fetch/read 失败与回退现在已经有稳定 code，可用于后续 Java 查询对象与前端筛选、统计、提示；
   - 下一步 `P2-D` 仍未结束，后续应继续补：
     - search 层更强的“为什么搜这些 query / 哪些 query 有效”摘要；
     - fetch/read 层更多真实外部 transport 状态覆盖；
     - 更适合 `process` 层直接消费的分层查询对象。

## 217. Phase 2 继续执行：补齐 search 层 query 选择理由与有效性摘要（2026-07-07）
继续沿 `P2-D` 推进，这一刀聚焦 search 层，不扩 fetch/read 范围。目标是把“为什么搜这些 query、这些 query 哪些真正带来了结果”从 trace 字段提升成正式 `toolbox_summary.search_summary` 子对象。

1. 当前确认的真实缺口：
   - `toolbox_summary.search_summary` 目前只有 `query_count / hit_count / query_family_counts / provider_resolution_counts` 等总量统计，还不能直接回答：
     - 这轮到底选中了哪些 query
     - 每个 query 属于哪个 family / lane / priority
     - 这些 query 是因为什么被选中的
     - 哪些 query 真正产生了命中，哪些没有
   - 现有 `build_search_query_plan(...)` 与 `execution_profile.query_family_budgets` 已经具备正式编排能力，但这些编排理由还没有沉淀成稳定查询对象。

2. 本轮优先交付目标：
   - 强化 `toolbox_summary.search_summary`，至少补齐：
     - 选中 query 列表
     - family budget 摘要
     - 每条 query 的命中有效性摘要
     - `effective_query_count / zero_hit_query_count`
   - 保证这些对象直接基于：
     - `plan.query_set`
     - `build_search_query_plan(...)`
     - `search_hits`
     计算出来，而不是从 trace 反推。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_harness.py`，要求 `toolbox_summary.search_summary` 输出正式 `selected_queries` 与 `query_effectiveness`；
   - 必要时补 `test_runner.py`，要求恢复态 `counterfactual` query 的选择理由与有效性可见；
   - 最后再改 `models.py / harness.py`，必要时新增 search summary builder 辅助对象。

4. 本轮完成标准：
   - `toolbox_summary.search_summary` 能正式解释 query 选择与效果；
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - `pytest workers/research-worker/tests` 全量通过。

## 218. Phase 2 进展记录：search 层 query 选择理由与有效性摘要已进入 toolbox summary（2026-07-07）
这一刀已按 TDD 落地完成，但仍然属于 `P2-D` 的中段推进，不代表整个 `Research Toolbox` 已经收口。当前 worker 已经不再只能通过 `query_set + selected_query_family_summary + hit_count` 间接理解 search 行为，而是已经存在更正式的 `search_summary` 查询对象。

1. 本次已完成的代码能力：
   - `toolbox_summary.search_summary` 当前已新增正式能力：
     - `selected_query_count`
     - `query_family_budget_counts`
     - `selected_queries`
     - `query_effectiveness`
     - `effective_query_count`
     - `zero_hit_query_count`
   - `selected_queries` 当前可直接回答：
     - 这轮选中了哪些 query
     - 每个 query 属于哪个 `family / lane / priority`
     - 每个 family 的预算上限是多少
     - 该 query 是为什么被选中的
   - `query_effectiveness` 当前可直接回答：
     - 每个 query 命中了多少结果
     - 每个 query 的 `max / avg coverage_score`
     - 命中的 `provider_resolution_counts`
     - 命中的代表性 `source_title`
   - 这些对象继续直接基于：
     - `build_search_query_plan(...)`
     - `plan.stop_contract.execution_profile.query_family_budgets`
     - `search_hits`
     计算，没有退回到 trace 反推。

2. 本次 TDD 覆盖点：
   - `test_harness.py` 已要求普通场景里 `search_summary` 正式存在：
     - `selected_query_count`
     - `query_family_budget_counts`
     - `selected_queries`
     - `query_effectiveness`
   - `test_harness.py` 已要求恢复态 `counterfactual` 场景里：
     - `selected_queries[0].family == counterfactual`
     - `query_effectiveness` 中存在 `counterfactual` family 的命中摘要
   - `test_harness.py` 已要求空 source scope 场景里：
     - `effective_query_count == 0`
     - `zero_hit_query_count == selected_query_count`
   - `test_runner.py / test_loop_runtime.py` 回归通过，确认新增 search 摘要后未影响主闭环。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_harness.py` 通过；
   - 结果：`22 passed`；
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`24 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`107 passed`。

4. 本刀的交付意义与下一步：
   - search 层现在已经能正式回答“为什么搜这些 query、哪些 query 真正有效”，后续 `process` 层不必再靠 trace 猜；
   - `P2-D` 还没结束，接下来更自然的延续有两条：
     - 继续补 fetch/read 的更多真实外部 transport 覆盖与失败分类样例；
     - 或者按总文档转入 `P3-A`，把 `harness_control_state / audit_summaries / toolbox_summary` 正式穿到 Java 查询契约。

## 219. 总交付文档升级启动：把“这一轮计划”收束为“从当前到全部完成交付”的冻结执行文档（2026-07-07）
当前按用户最新要求，不再只写“这一轮先做什么”，而是直接把 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 升级成一份从当前真实进度一路走到最终交付的总执行文档。目标不是增加抽象表述，而是把“现在在哪、下一段怎么走、做到什么算交付完成”冻结成可以直接照着执行的主文档。

1. 当前确认需要补强的文档缺口：
   - 现有总文档虽然已经说明了 `Phase 2 -> Phase 5` 的大方向，但还偏阶段说明，尚未把“当前站位、剩余执行卡、冻结顺序、每卡的最小交付物与验收口径”压得足够清晰；
   - `P2-D` 目前已经落地了前两刀，但总文档里还没有把 `P2-D` 剩余工作拆成更明确的收口卡片，这会让后续执行容易回到“边做边决定”的状态；
   - `Phase 3 / Phase 4 / Phase 5` 也需要从“阶段目标”进一步压成可直接挂靠的交付卡，保证后续实现、联调、展示和简历讲解都指向同一份真源文档。

2. 这一刀的文档升级目标：
   - 明确写出“当前真实站位 = `Phase 2 / P2-D`，且 `P2-D` 仅完成前两段补厚”；
   - 把从现在到最终交付的剩余工作改写为冻结执行卡序列，而不是宽泛阶段描述；
   - 为 `P2-D / P3-A / P3-B / P3-C / P4-A / P4-B / P4-C / P5-A / P5-B / P5-C` 逐张写清：
     - 目标
     - 最小交付物
     - 与参考项目长处的对应吸收点
     - 最低验收口径
   - 保证整份文档继续围绕：
     - `Research Harness`
     - `Closed-Loop Research`
     - `Table-as-State`
     - `Dual Verifier`
     - `反证分支`
     而不是退回成普通功能排期单。

3. 本刀执行约束：
   - 只升级文档与执行口径，不在这一刀里顺手扩实现范围；
   - 更新主文档时继续保持“Deep Research 是独立功能、结果与来源优先、过程与审计分层展示”的产品边界；
   - 新文档要能直接服务后续代码实现、联调验收、产品演示和项目亮点讲解四个场景。

## 220. 总交付文档升级完成：主执行文档已冻结为“从当前站位到最终交付”的单向执行路线（2026-07-07）
这一刀已完成。当前 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 不再只是“阶段目标说明”，而是已经升级为一份可直接挂靠后续全部实现的总执行文档。

1. 本次已完成的文档升级内容：
   - 在总文档中正式补入 `4.6 当前精确站位冻结`，明确当前真实起点就是：
     - `Phase 2 / P2-D`
     - 且 `P2-D` 只完成了前两段补厚
   - 把 `P2-D` 从宽泛描述改写成剩余三张可执行卡：
     - `P2-D-3` process-first 分层工具查询对象
     - `P2-D-4` 外部 fetch/read transport 补厚与归档预留
     - `P2-D-5` verifier-consumable toolbox signals 与真实样例闭环
   - 把 `Phase 3 / Phase 4 / Phase 5` 继续压成更明确的执行卡，并为每张卡补上：
     - 最小交付物
     - 对参考项目长处的吸收点
     - 最低验收口径
   - 重写了冻结执行顺序、剩余产出物清单、里程碑定义和统一验收顺序，使后续实现不再需要反复重排。

2. 这次文档升级后的直接价值：
   - 后续每一刀都可以明确挂靠到一张剩余执行卡，不再落回“边做边决定顺序”；
   - 当前最关键的主线被重新锁定为：先收 `Research Harness / Research Toolbox / Closed-Loop Research`，再穿 Java 契约，再做工作台展示和最终 demo；
   - 这份总文档现在可以同时服务：
     - 代码实现推进
     - 联调与验收
     - 产品演示准备
     - 项目亮点与简历讲解

3. 接下来的真实第一刀也已经被文档冻结为：
   - `P2-D-3` process-first 分层工具查询对象
   - 不应跳过它直接去做前端或讲解包装

## 221. Phase 2 继续执行：启动 `P2-D-3` process-first 分层工具查询对象（2026-07-07）
按刚冻结的总交付执行文档，当前继续停留在 `Phase 2 / P2-D`，并正式进入 `P2-D-3`。这一刀不扩 Java 契约、不动前端，而是先把 worker 侧 `Research Toolbox` 从“只有 summary 可看”继续推进到“process 层可直接消费的分层对象”。

1. 当前确认的真实缺口：
   - `toolbox_summary` 目前已经有 `search_summary / fetch_summary / read_summary`，但它们仍偏聚合摘要，前端或主系统若想直接展示“本轮搜了什么、抓了什么、读了什么”，仍然需要重新从 `search_hits / fetched_documents / read_windows / tool_traces` 里做二次拼装；
   - 总文档已经把 `P2-D-3` 冻结为必须补齐 `search_items / fetch_items / read_items` 或等价分层对象，否则 `process` 层仍然不是真正的正式 query object；
   - 当前最需要避免的是：继续堆新的 summary 计数字段，却不把“工具执行对象本身”沉淀成稳定契约。

2. 本轮优先交付目标：
   - 在 `toolbox_summary` 中新增面向 `process` 层的正式分层对象，至少覆盖：
     - `search_items`
     - `fetch_items`
     - `read_items`
   - `search_items` 至少能直接表达：
     - `query / family / lane / selection_reason`
     - 代表性 `top_hits`
   - `fetch_items` 至少能直接表达：
     - `fetch_id / source_title / query`
     - `fetch_status / fetch_method / content_type_label`
     - `fetch_failure_code / transport_resolution / transport_fallback_code`
   - `read_items` 至少能直接表达：
     - `window_id / source_title / query_family`
     - `read_strategy / fetch_status / content_type_label`
     - `target_requirement_labels`
     - 与 evidence/来源展示相关的稳定锚点字段

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_harness.py`，要求 `toolbox_summary` 正式输出上述三层对象，且三层对象与 runtime 底层对象同源；
   - 必要时补 `test_runner.py` 或已有 harness 场景测试，要求恢复态/反证态下这些对象仍能正确表达 `counterfactual`、fallback 与 targeted read；
   - 最后再改 `models.py / harness.py / runner.py`，必要时增加正式 item model，而不是继续塞匿名 `dict`。

4. 本轮完成标准：
   - worker 侧正式存在 `toolbox_summary.search_items / fetch_items / read_items` 或等价正式字段；
   - `process` 层后续可以直接消费这些对象，而不必从 raw trace 二次拼装；
   - `pytest workers/research-worker/tests/test_harness.py workers/research-worker/tests/test_runner.py` 通过；
   - 如无回归问题，再跑 `pytest workers/research-worker/tests` 全量通过。

## 222. Phase 2 进展记录：`P2-D-3` process-first 分层工具查询对象已落地（2026-07-07）
这一刀已按 TDD 落地完成。当前 `Research Toolbox` 不再只有聚合型 `search_summary / fetch_summary / read_summary`，而是已经具备 `process` 层可以直接消费的正式分层对象。

1. 本次已完成的代码能力：
   - `models.py` 当前已新增正式 item model：
     - `ResearchToolboxSearchTopHit`
     - `ResearchToolboxSearchItem`
     - `ResearchToolboxFetchItem`
     - `ResearchToolboxReadItem`
   - `ResearchToolboxSummary` 当前已新增：
     - `search_items`
     - `fetch_items`
     - `read_items`
   - `harness.py` 当前已把这些对象接入 `build_toolbox_summary(...)`：
     - `search_items` 直接绑定 `query_plan + search_hits`
     - `fetch_items` 直接绑定 `fetched_documents`
     - `read_items` 直接绑定 `read_windows`
   - 这意味着后续 `process` 层已经可以直接读取：
     - 本轮搜了哪些 query、各自命中了哪些代表性结果
     - 本轮抓了哪些文档、状态是什么、失败/回退落到了哪一层
     - 本轮读了哪些窗口、是否带 requirement target、采用了什么 read strategy

2. 本次 TDD 覆盖点：
   - `test_harness.py` 已正式要求 `toolbox_summary` 输出：
     - `search_items`
     - `fetch_items`
     - `read_items`
   - 普通场景下已验证：
     - `search_items` 能表达 `query / family / lane / selection_reason / top_hits`
     - `fetch_items` 能表达 `fetch_status / fetch_method / content_type_label / transport_resolution / transport_chain`
     - `read_items` 能表达 `query_family / read_strategy / target_requirement_labels`
   - 恢复态 / 反证态下已验证：
     - `search_items` 可见 `counterfactual`
     - `read_items` 可见 `COUNTERFACTUAL_DEEP_READ`

3. 验证结果：
   - `pytest workers/research-worker/tests/test_harness.py` 通过；
   - 结果：`22 passed`；
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`24 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`107 passed`。

4. 本刀的交付意义与下一步：
   - `process` 层后续已经不必从 `search_hits / fetched_documents / read_windows / tool_traces` 二次拼装“检索过程”；
   - `Research Toolbox` 已经从“只有摘要”继续升级为“摘要 + 正式过程对象”双层结构，更贴近 `MiroFlow` 风格的可查询工具链；
   - `P2-D` 仍未收口，下一步应继续进入：
     - `P2-D-4` 外部 `fetch / read` transport 补厚与归档预留；
     - 然后再做 `P2-D-5`，把 toolbox signals 更正式喂给 verifier 与真实样例闭环。

## 223. Phase 2 继续执行：启动 `P2-D-4` 外部 fetch/read transport 补厚与归档预留（2026-07-07）
按冻结后的总交付执行文档，当前继续停留在 `Phase 2 / P2-D`，并正式进入 `P2-D-4`。这一刀不先喂 Java 契约，也不做前端展示，而是继续把 worker 侧外部 `fetch / read` 的 transport 状态做厚，并把“后续是否可归档/可挂 checkpoint 回放”的元数据提前收成稳定对象。

1. 当前确认的真实缺口：
   - 现有 `ResearchFetchedDocument / ResearchReadWindow` 虽然已经有：
     - `snapshot_status`
     - `snapshot_key`
     - `fetch_failure_code`
     - `transport_chain`
     - `transport_resolution`
     但还不能直接回答：
     - 这条外部抓取链一共尝试了几层 transport
     - 当前拿到的快照是否已经具备后续归档/回放准备条件
   - `CompositeUrlSnapshotTransport` 已经能输出 fallback chain，但这层“退化链复杂度”和“归档就绪度”还没有沉淀成稳定字段；
   - 当前 fetch/read 测试对 `NO_TRANSPORT / FETCH_RETRY_EXHAUSTED / EMPTY_FETCH_TEXT / UNSUPPORTED_CONTENT_TYPE` 已有覆盖，但对“transport 尝试次数”和“快照归档就绪信号”的验收还没有形成正式口径。

2. 本轮优先交付目标：
   - 强化 `ResearchFetchedDocument / ResearchReadWindow`，至少补齐：
     - `transport_attempt_count`
     - `snapshot_archive_ready`
   - 让这两个信号进入：
     - fetch/read 正式模型
     - `toolbox_summary.fetch_summary / read_summary`
     - `toolbox_summary.fetch_items / read_items`
   - 归档就绪的最低口径先冻结为：
     - 有真实抓取文本
     - `snapshot_key` 非空
     - 非 fallback 伪快照
     - 后续可直接服务 checkpoint / archive / replay

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_fetch_adapters.py / test_read_adapters.py`，要求：
     - workspace / no transport / fetched / composite fallback / unsupported content 场景下
       `transport_attempt_count` 与 `snapshot_archive_ready` 都有明确行为；
   - 再补 `test_harness.py`，要求 `toolbox_summary` 把这层状态稳定透出到 summary 和 items；
   - 最后再改 `models.py / fetch_adapters.py / read_adapters.py / harness.py`。

4. 本轮完成标准：
   - worker 侧正式存在可查询的 `transport_attempt_count / snapshot_archive_ready`；
   - 外部 fetch/read 失败和成功都能稳定表达 transport 复杂度与归档就绪度；
   - `pytest workers/research-worker/tests/test_fetch_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_harness.py` 通过；
   - 如无回归问题，再跑 `pytest workers/research-worker/tests` 全量通过。

## 224. Phase 2 进展记录：`P2-D-4` transport 复杂度与归档预留元数据已落地（2026-07-07）
这一刀已按 TDD 落地完成。当前外部 `fetch / read` 已不再只有 `failure code + transport_chain`，而是已经把“尝试过几层 transport”和“当前快照是否具备后续归档/回放准备条件”收成正式字段，并一路透出到 `toolbox_summary`。

1. 本次已完成的代码能力：
   - `ResearchFetchedDocument / ResearchReadWindow` 当前已新增正式字段：
     - `transport_attempt_count`
     - `snapshot_archive_ready`
   - `fetch_adapters.py` 当前已对外部 URL 抓取正式计算：
     - transport 尝试层数
     - snapshot 是否满足 archive-ready 条件
   - `read_adapters.py` 当前已在打开 read window 时基于当前 fetch 状态重新计算上述字段，避免后续状态变更时沿用过期布尔值；
   - `ResearchToolboxFetchSummary / ResearchToolboxReadSummary` 当前已新增正式计数：
     - `snapshot_archive_ready_count`
     - `multi_transport_document_count`
     - `multi_transport_window_count`
   - `toolbox_summary.fetch_items / read_items` 当前也已直接透出：
     - `transport_attempt_count`
     - `snapshot_archive_ready`

2. 本次 TDD 覆盖点：
   - `test_fetch_adapters.py` 已覆盖：
     - workspace 文档
     - no transport fallback
     - 单 transport 成功抓取
     - composite transport fallback 链成功恢复
     - unsupported content fallback
   - 上述场景现在都要求显式校验：
     - `transport_attempt_count`
     - `snapshot_archive_ready`
   - `test_read_adapters.py` 已要求 read window 正式保留并在必要时重算这两个字段；
   - `test_harness.py` 已要求 `toolbox_summary` 的 summary 与 items 两层都透出这层状态。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_fetch_adapters.py workers/research-worker/tests/test_read_adapters.py workers/research-worker/tests/test_harness.py` 通过；
   - 结果：`37 passed`；
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`24 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`107 passed`。

4. 本刀的交付意义与下一步：
   - transport 退化链现在已经不只是“失败原因说明”，而是已经具备：
     - 复杂度信号
     - archive-ready 信号
     这更适合后续 checkpoint / replay / verifier 消费；
   - `P2-D` 仍未收口，下一步应继续进入 `P2-D-5`，把这层 toolbox signals 更正式喂给：
     - `Dual Verifier`
     - `report_structure`
     - 真实外部研究样例闭环验收。

## 225. Phase 2 继续执行：启动 `P2-D-5`，把 toolbox signals 正式喂给 `Dual Verifier` 与 `report_structure`（2026-07-07）
按冻结后的总交付执行文档，当前继续停留在 `Phase 2 / P2-D`，并正式进入 `P2-D-5`。这一刀先不追求“大而全”的真实外部 demo，而是优先把前两刀刚落地的 toolbox signals 从“可展示字段”升级为“会真正影响 verifier 决策和报告基础层”的正式质量信号。

1. 当前确认的真实缺口：
   - `transport_attempt_count / snapshot_archive_ready` 已经进入：
     - `ResearchFetchedDocument`
     - `ResearchReadWindow`
     - `toolbox_summary`
     但 `Dual Verifier` 目前仍主要依赖旧的 `fetch_status / provider_attempts / transport_chain` 做判断；
   - `reporter.py` 当前虽能输出 `source_foundation` 的 fetch/provider/transport 结构摘要，但尚未正式吸收：
     - archive-ready 覆盖度
     - multi-transport 复杂度
   - 这意味着 toolbox 新信号目前更像“可读元数据”，还没有回到 `Closed-Loop Research` 的核心质量闭环。

2. 本轮优先交付目标：
   - 把 `transport_attempt_count / snapshot_archive_ready` 正式接入 `Dual Verifier`：
     - 至少让 local/global verifier 能基于这两个信号补充 warning / recovery / completion_score 惩罚
   - 把这两个信号正式接入 `report_structure.source_foundation`：
     - 至少补齐稳定计数与标签，让报告基础层能解释 external foundation 的 archive-ready 与 transport complexity
   - 保证新信号继续同源服务：
     - `Research Harness`
     - `Closed-Loop Research`
     - `Table-as-State`
     - `Dual Verifier`
     而不是只做展示字段堆砌。

3. 本轮实现方式继续坚持 TDD：
   - 先补 `test_llm_extract_verify.py` 或等价 verifier/report 测试，要求：
     - verifier 正式消费 toolbox signals
     - report structure 正式输出 archive/transport 基础信号
   - 必要时补 `test_runner.py`，要求这些信号最终进入实际 run 产物；
   - 最后再改 `verifier.py / reporter.py / runner.py`。

4. 本轮完成标准：
   - `Dual Verifier` 正式消费 `transport_attempt_count / snapshot_archive_ready`；
   - `report_structure.source_foundation` 正式透出 archive-ready 与 transport complexity 摘要；
   - `pytest workers/research-worker/tests/test_llm_extract_verify.py workers/research-worker/tests/test_runner.py` 通过；
   - 如无回归问题，再跑 `pytest workers/research-worker/tests` 全量通过。

## 226. Phase 2 完成记录：`P2-D-5` 已把 toolbox signals 正式接入 `Dual Verifier` 与 `report_structure`（2026-07-07）
这一刀已按 TDD 落地完成。当前 `transport_attempt_count / snapshot_archive_ready` 不再只是 `Research Toolbox` 的可读元数据，而是已经回到 `Closed-Loop Research` 的质量闭环，正式影响 verifier 判断与报告基础层。

1. 本次已完成的代码能力：
   - `verifier.py` 当前已正式消费 toolbox 新信号：
     - `transport_attempt_count`
     - `snapshot_archive_ready`
   - 当前 local verifier 新增了针对 fetched external windows 的正式 warning / recovery：
     - `EXTERNAL_SNAPSHOT_ARCHIVE_NOT_READY`
   - 当前 global verifier 已把这层信号纳入 `completion_score` 惩罚，不再只看 fallback chain；
   - `reporter.py` 当前已把这层信号正式接入 `source_foundation`，新增：
     - `archive_ready_window_count`
     - `archive_unready_window_count`
     - `multi_transport_window_count`
     - `archive_readiness_label`
     - `transport_complexity_label`
   - 报告 markdown 的 `Source Basis` 区块当前也已增加：
     - `Archive readiness`
     - `Transport complexity`
     - `Archive-ready external windows`
     - `Multi-transport external windows`

2. 本次 TDD 覆盖点：
   - `test_llm_extract_verify.py` 当前已新增并通过：
     - fetched external windows 非 archive-ready 时，local verifier 会显式 warning
     - archive-ready 与 not-ready 两种外部 fetched 窗口会导致不同 global completion score
     - `report_structure.source_foundation` 正式输出 archive/transport complexity 摘要
   - 现有 fallback-heavy、provider-fallback、transport-fallback 相关 verifier/report 测试也全部继续通过。

3. 验证结果：
   - `pytest workers/research-worker/tests/test_llm_extract_verify.py` 通过；
   - 结果：`29 passed`；
   - `pytest workers/research-worker/tests/test_runner.py workers/research-worker/tests/test_loop_runtime.py` 通过；
   - 结果：`24 passed`；
   - `pytest workers/research-worker/tests` 全量通过；
   - 结果：`110 passed`。

4. 本刀的交付意义与下一步：
   - `Research Toolbox` 新信号现在已经真正回到：
     - `Dual Verifier`
     - `report_structure`
     - `Closed-Loop Research`
     主链路，而不只是停留在 process 展示层；
   - 到这里，`P2-D-3 / P2-D-4 / P2-D-5` 三刀都已完成，`Phase 2 / P2-D` 可以视为正式收口；
   - 下一步应按总执行文档进入 `P3-A`：Java 正式承接 `harness_control_state / audit_summaries / toolbox_summary`。

## 227. 总交付文档再冻结：把主执行文档进一步收束为“从当前状态到全部完成交付”的最终蓝图（2026-07-07）
这一刀不是继续做某个实现卡，而是先把总执行文档再压实一层，确保它表达的是“从当前真实进度到最终交付”的单向路线，而不是“这一轮做什么”的阶段备注。重点修正三类问题：

1. 状态口径统一：
   - 前文已经明确 `Phase 2 / P2-D` 收口完成、当前站位在 `Phase 3 / P3-A` 前；
   - 但总文档后半段仍残留 `Phase 2` 为“进行中、当前第一优先级”的旧表述；
   - 本刀要求把这类前后冲突全部清掉，冻结成单一站位。

2. 交付蓝图化：
   - 总文档需要更明确地区分：
     - 当前真实状态
     - 冻结后的严格执行顺序
     - 全部完成交付的最终判定标准
   - 避免继续混入“本轮推进重点”式语言，导致路线再次散开。

3. 最终完成判定：
   - 需要把 `Phase 3 / Phase 4 / Phase 5` 的完成判定写成显式清单；
   - 让后续每一刀都能对照“距离最终交付还差什么”；
   - 也让这份文档能直接服务后续联调、验收、展示与项目讲解。

## 228. 总交付文档再冻结完成：主执行文档现已明确为“从当前状态到全部完成交付”的最终判定蓝图（2026-07-07）
本刀已完成，总执行文档已进一步收紧为真正面向最终交付的冻结版蓝图，而不是阶段性说明。当前同步结果如下：

1. 已修正的口径冲突：
   - 总文档已把“未闭合主线”从误写的“五条”修正为实际的“四条”；
   - `Phase 2` 状态已统一为：
     - `已完成`
     - `作为 Phase 3 - Phase 5 的稳定基座`
   - `Phase 3` 已明确升级为：
     - `当前第一优先级`
     - `从已有后端主链继续向正式查询契约收口`

2. 已新增的交付判定结构：
   - 增加了“从当前状态到全部完成交付的最终判定清单”；
   - 其中分别列出了：
     - `Phase 3` 完成判定
     - `Phase 4` 完成判定
     - `Phase 5` 完成判定
     - `Deep Research` 最终交付定义

3. 这次文档冻结后的实际意义：
   - 后续执行可以直接围绕这份文档判断“当前离最终交付还差什么”；
   - 后续实现不需要再回到“是不是先补这一轮计划”的语境；
   - 当前主线已经进一步冻结为：
     - 先做 `P3-A / P3-B / P3-C`
     - 再做 `P4-A / P4-B / P4-C`
     - 最后做 `P5-A / P5-B / P5-C`

## 229. Phase 3 执行启动：进入 `P3-A`，Java 正式承接 `harness_control_state / audit_summaries / toolbox_summary`（2026-07-07）
这一刀开始正式进入总执行文档的 `P3-A`。目标不是再补 worker 内部逻辑，而是把已经在 worker 侧稳定存在的闭环对象，正式穿透到 Java 查询契约，避免它们继续只停留在 `result_payload / trace` 附件层。

1. 本刀锁定的最小交付物：
   - `Research Run detail` 正式返回：
     - `harness_control_state`
     - `audit_summaries`
     - `toolbox_summary`
   - `Research Checkpoint` 正式返回：
     - `harness_control_state`
     - `audit_summaries`
     - `toolbox_summary`
   - `Research Closed Loop State` 正式返回：
     - `harness_control_state`
     - `audit_summaries`
     - `toolbox_summary`

2. 本刀实现原则：
   - 第一阶段优先做正式命名字段承接，不急着深度 typed 化；
   - 命名与语义保持与 worker 同源，避免 Java 侧再发明一套平行命名；
   - 前端后续 `process / audit` 层直接消费这些 query object，而不是继续拼 trace。

3. 本刀继续坚持 TDD：
   - 先补 `Phase6ResearchArtifactContractTest` 的 detail / checkpoint / closed-loop 断言；
   - 先让测试表达“Java 已把这些对象正式承接出来”；
   - 再改 `ResearchRunService` 与相关 response record。

## 230. Phase 3 完成记录：`P3-A` 已把 `harness_control_state / audit_summaries / toolbox_summary` 正式承接到 Java 查询契约（2026-07-07）
这一刀已按 TDD 落地完成。当前这三个 worker 闭环对象不再只停留在 `result_payload / checkpoint payload / raw trace`，而是已经进入 Java 正式查询面，成为后续 `process / audit` 展示可以直接消费的 query object。

1. 本次已完成的契约收口：
   - `Research Run detail` 当前已正式返回：
     - `harness_control_state`
     - `audit_summaries`
     - `toolbox_summary`
   - `Research Checkpoint` 当前已正式返回：
     - `harness_control_state`
     - `audit_summaries`
     - `toolbox_summary`
   - `Research Closed Loop State` 当前已正式返回：
     - `harness_control_state`
     - `audit_summaries`
     - `toolbox_summary`
   - `Research Run history / listRuns` 当前也已补齐同名字段，和总执行文档中的 `detail / history / checkpoint` 收口口径保持一致。

2. 本次代码层交付物：
   - `ResearchRunDetailResponse`
   - `ResearchCheckpointResponse`
   - `ResearchClosedLoopStateResponse`
   - `ResearchRunSummaryResponse`
   上述 response record 当前都已正式承接对应字段。
   - `ResearchRunService` 当前已补齐：
     - structured trace 承接
     - checkpoint payload 承接
     - detail / history / checkpoint / closed-loop 映射
   - worker 回调落库时，当前还会额外写入：
     - `HARNESS_CONTROL_STATE`
     - `AUDIT_SUMMARIES`
     - `TOOLBOX_SUMMARY`
     三类结构化 trace，方便后续 `P3-C` 继续做过程层与审计层 query object 收口。

3. 本次 TDD 覆盖点：
   - `Phase6ResearchArtifactContractTest` 当前已新增并通过断言：
     - detail 顶层存在这三个对象
     - closed-loop state 内存在这三个对象
     - checkpoint response 顶层存在这三个对象
     - run history/listRuns 中也存在这三个对象
   - 同时保留并继续通过原有：
     - report structure
     - checkpoint summary
     - artifact / report file / saved source
     等既有契约断言，证明这刀没有把现有主链打坏。

4. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`。

5. 本刀完成后的站位与下一步：
   - `P3-A` 现在可以视为完成；
   - worker 关键闭环对象已经正式穿透到了 Java 查询契约；
   - 下一步应按总执行文档进入 `P3-B`：
     - `checkpoint / artifact / writeback` 同源收口。

## 231. Phase 3 执行启动：进入 `P3-B`，收口 `checkpoint / artifact / writeback` 同源关系（2026-07-07）
这一刀正式进入总执行文档的 `P3-B`。重点不是继续补更多 worker 字段，而是把当前已经分散存在于后端各处的 `report / artifact / saved source / resume source` 关系，收成查询面可见、可追溯、可讲清的正式同源链路。

1. 本刀锁定的最小交付物：
   - `report file`
   - `research artifact`
   - `saved report source`
   - `resume checkpoint / resume source`
   在查询面保持同源、互相可追溯；
   - `checkpoint` 查询面不再只看状态快照，也能看见与最终研究产物之间的正式关系；
   - `run -> artifact -> save-as-source -> source_scope reuse` 这条链在 detail / history / checkpoint / resume 相关查询面都能讲清楚。

2. 本刀优先要回答的问题：
   - 当前 run 生成的 `md report` 与 `research artifact` 是否能明确对应？
   - 当前 artifact 与 `saved_report_source` 是否能明确对应？
   - resumed run 的 `resume_checkpoint` 是否能看见来源 run 的正式产物关系？
   - downstream `source_scope reuse` 是否能在查询面反映“这个 source 来自哪个 research run 的产物”？

3. 本刀继续坚持 TDD：
   - 先在 `Phase6ResearchArtifactContractTest` 里扩现有：
     - `save-report-as-source`
     - resumed run
     - downstream source_scope reuse
     三组样例；
   - 先让测试表达“同源关系已在查询面可见”；
   - 再补 `ResearchRunService` 与相关 response object。

## 232. Phase 3 完成记录：`P3-B` 已把 `checkpoint / artifact / writeback` 收成正式同源查询链路（2026-07-07）
这一刀已按 TDD 落地完成。当前 `report file / research artifact / saved report source / resume source` 不再只是散落在不同接口里的可推断关系，而是已经正式进入查询契约，能够在 `detail / history / checkpoint / resume` 相关查询面直接看见并互相追溯。

1. 本次已完成的同源收口：
   - `Research Checkpoint` 当前已正式返回：
     - `report_file`
     - `research_artifact`
     - `saved_report_source`
   - `Research Resume Checkpoint Summary` 当前已正式返回来源 run 的：
     - `report_file`
     - `research_artifact`
     - `saved_report_source`
   - 因此当前可以在查询面直接串起：
     - 当前 run 的 `md report -> research artifact -> saved report source`
     - resumed run 的 `resume checkpoint -> source run report/artifact/saved source`

2. 本次代码层交付物：
   - `ResearchCheckpointResponse`
   - `ResearchResumeCheckpointSummaryResponse`
   - `ResearchRunService`
   当前都已正式补齐上述同源关系字段与组装逻辑。
   - 为减少重复拼装，`ResearchRunService` 新增了基于 run 读取：
     - `reportFile`
     - `savedReportSource`
     - `researchArtifact`
     的统一承接逻辑，并在 checkpoint 与 resume query 链路中复用。

3. 本次 TDD 覆盖点：
   - 在 `save-report-as-source` 样例中，当前已新增并通过断言：
     - checkpoint 查询可直接看到 `report_file / research_artifact / saved_report_source`
   - 在 `resume-from-checkpoint` 样例中，当前已新增并通过断言：
     - resumed run 的 `resume_checkpoint` 可直接看到来源 run 的 `report_file / research_artifact / saved_report_source`
   - 现有 `source_scope reuse` 样例继续通过，说明：
     - `run -> artifact -> save-as-source -> source_scope reuse`
       主链没有回归

4. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`。

5. 本刀完成后的站位与下一步：
   - `P3-B` 现在可以视为完成；
   - 当前 Java 查询面已经能正式讲清：
     - 当前研究产物链路
     - 写回资料链路
     - resume 来源链路
   - 下一步应按总执行文档进入 `P3-C`：
     - 把 `process / audit` 进一步收成正式 query object。

## 233. Phase 3 执行启动：进入 `P3-C`，把 `process / audit` 收成 checkpoint 可直接消费的正式 query object（2026-07-07）
这一刀开始正式进入总执行文档的 `P3-C`。当前 `detail / history` 已经具备 `research_process_summary`，但 `checkpoint` 查询面还主要停留在：

- `summary`
- `payload`
- 以及若干结构化 map

这意味着前端如果要基于 checkpoint 详情展示 `process / audit`，仍然容易回到“看 raw payload / raw map”的路径。

1. 本刀锁定的最小交付物：
   - `Research Checkpoint` 正式返回 `research_process_summary`；
   - 其中继续复用现有正式对象：
     - `ResearchSearchReadTimelineResponse`
     - `ResearchSourceEvidenceSummaryResponse`
     - `ResearchAuditSummaryResponse`
   - 让 `detail / history / checkpoint` 三个查询面都能直接返回 formal `process / audit` 对象。

2. 本刀优先要回答的问题：
   - checkpoint 查询时，前端能否不看 raw `payload.loop_rounds` 就拿到正式 process timeline？
   - checkpoint 查询时，前端能否不自己数 verifier / counterfactual / blocked rows，就拿到正式 audit summary？
   - 这层对象是否能与 detail / history 现有 `research_process_summary` 保持同名、同义、同消费方式？

3. 本刀继续坚持 TDD：
   - 先在 `Phase6ResearchArtifactContractTest` 里补 checkpoint 对 `research_process_summary` 的断言；
   - 先让测试表达“checkpoint 也有 formal process/audit object”；
   - 再补 `ResearchCheckpointResponse` 与 `ResearchRunService`。

## 234. Phase 3 完成记录：`P3-C` 已把 checkpoint 查询面也收进正式 `process / audit` 契约（2026-07-07）
这一刀已按 TDD 落地完成。当前 `detail / history / checkpoint` 三个查询面都已经可以直接返回同一套 formal `process / audit` 对象，前端不需要再为了 checkpoint 详情去读 `payload.loop_rounds`、`payload.loop_decision` 或其他 raw map 才能拼出核心过程信息。

1. 本次已完成的查询契约收口：
   - `Research Checkpoint` 当前已正式返回 `research_process_summary`；
   - 其中继续复用了已有正式对象：
     - `ResearchSearchReadTimelineResponse`
     - `ResearchSourceEvidenceSummaryResponse`
     - `ResearchAuditSummaryResponse`
   - 这意味着现在：
     - `detail` 有 formal process/audit
     - `history` 有 formal process/audit
     - `checkpoint` 也有 formal process/audit

2. 本次代码层交付物：
   - `ResearchCheckpointResponse` 当前已新增：
     - `researchProcessSummary`
   - `ResearchRunService` 当前已新增 checkpoint 场景下的正式组装逻辑：
     - checkpoint search/read timeline 组装
     - checkpoint source evidence summary 组装
     - checkpoint audit summary 组装
     - checkpoint source scope 数量估算
   - 这些逻辑保持与现有 `ResearchProcessSummaryResponse` 同名、同义、同消费方式，而不是再单独造一套 checkpoint 私有结构。

3. 本次 TDD 覆盖点：
   - `Phase6ResearchArtifactContractTest` 当前已新增并通过断言：
     - checkpoint API 响应中存在 `research_process_summary`
     - 其中 `search_read_timeline` 可直接读取轮次与读窗数量
     - 其中 `source_evidence_summary` 可直接读取来源质量
     - 其中 `audit_summary` 可直接读取 verifier 与 checkpoint 数
   - 同时保留并继续通过：
     - `P3-A` 的 `harness_control_state / audit_summaries / toolbox_summary`
     - `P3-B` 的 `report / artifact / saved source / resume source`
     契约断言，说明这刀没有把前两刀打散。

4. 验证结果：
   - `./mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test` 通过；
   - 结果：`Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`。

5. 本刀完成后的站位与下一步：
   - `P3-C` 现在可以视为完成；
   - 到这里，`P3-A / P3-B / P3-C` 三刀都已完成，`Phase 3` 可以视为正式收口；
   - 下一步应按总执行文档进入 `P4-A`：
     - 结果优先主界面收口。

## 235. Phase 4 执行启动：进入 `P4-A`，收口结果优先主界面（2026-07-07）
这一刀正式进入总执行文档的 `P4-A`。当前前端已经有独立 Deep Research 工作台和详情弹窗两层骨架，但主结果区仍混入了较多过程指标、Harness 摘要和写回说明，和“结果、报告、来源优先”的最终交付口径还不完全一致。

1. 本刀锁定的最小交付物：
   - 独立入口表单继续保留；
   - 主结果区优先展示：
     - `Result Snapshot`
     - 正式 `Research Report`
     - `Sources`
   - 主结果区补齐：
     - `导出 Markdown`
     - `保存为资料`
   - 过程和审计字段尽量退回详情弹窗，不在主界面堆 verifier / trace 调试信息。

2. 本刀优先要回答的问题：
   - 用户进入主结果区时，是否先看到“结论、报告、来源”而不是“搜索命中、阅读窗口、checkpoint 数”？
   - 正式报告是否能被直接导出为 `md`？
   - 写回资料入口是否仍然清晰可见，但不抢占主结果区焦点？

3. 本刀继续坚持 TDD / 契约验证：
   - 先补前端静态契约或已有 `ui-contract-check` 所需文案与入口；
   - 再改主结果区结构；
   - 最后跑 `npm run build` 与 `node scripts/ui-contract-check.mjs`。

## 236. 本轮执行纠偏：不继续拆当前单刀实现，先冻结“从当前到全部完成交付”的总执行文档（2026-07-07）
这一轮先不直接往下推进 `P4-A` 的代码实现，而是按用户最新要求，把 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 升级成一份真正面向“当前真实状态 -> 全部完成交付”的正式路线图。目标不是再补一层阶段描述，而是把后续剩余工作、收口顺序、验收门槛、参考项目吸收策略一次性冻结下来，避免后续边做边改主路线。

1. 本轮文档重构必须回答清楚四件事：
   - 当前真实站位到底在哪里，哪些卡已经完成，哪些卡还没做；
   - 从现在开始到 `100%` 交付，应该按什么顺序推进，而不是继续混着做；
   - 哪些属于真正要交付的核心能力，哪些属于明确不做或延后做的复杂度；
   - 三个参考项目：
     - `Table-as-Search`
     - `DeepWideSearch`
     - `MiroFlow`
     的长处具体吸收到哪一段实现里。

2. 本轮文档必须继续锁定用户已经反复强调的边界：
   - `Deep Research` 是工作台内独立功能；
   - 任务入口只来自用户显式输入，不依赖聊天 / Note / Wiki 上下文；
   - 主界面优先展示最终报告与来源；
   - 详情弹窗才分 `process` 与 `audit` 两层；
   - 不做主界面的 verifier 原始字段堆砌，也不做过程回放播放器。

3. 本轮文档必须把后续路线写成可执行卡片，而不是泛泛阶段说明：
   - 当前默认主线应明确冻结为：
     - `P4-A`
     - `P4-B`
     - `P4-C`
     - `P5-A`
     - `P5-B`
     - `P5-C`
   - 同时要显式写清：如果真实外部研究样例暴露 `search / fetch / read / verifier / 反证分支` 仍有硬缺口，应先插入 agent hardening 卡，再继续做展示和最终 demo。

4. 本轮输出完成后，后续所有实现都应默认挂靠新总文档执行，不再每轮重新讨论“是不是先做前端”或“是不是先补工具层”。

## 237. 本轮完成记录：总执行文档已改写为“从当前站位到完整交付”的正式路线图（2026-07-07）
这一轮文档重构已完成。当前 [深度研究智能体全量交付执行文档.md](/D:/java-projects/NoteWeave-v2/docs/深度研究智能体全量交付执行文档.md) 不再主要承担“阶段说明”角色，而是已经改写为一份可以直接指导后续全部交付的正式执行手册。

1. 本次文档重构完成后的核心变化：
   - 重新冻结了当前真实站位：
     - `Phase 1 / Phase 2 / Phase 3` 核心主线已完成；
     - 当前默认下一步为 `P4-A`；
     - `Deep Research` 当前仍不能按 `90%` 计，而应按“核心闭环已成型、产品交付仍未收口”判断。
   - 重新冻结了产品边界：
     - 独立入口
     - 用户显式输入
     - 报告 / 来源优先
     - 详情 `process / audit` 分层
   - 重新冻结了三类剩余工作：
     - 产品化展示收口
     - agent 真样例硬化
     - 最终 demo / 验收 / 讲解交付

2. 本次文档重构后，后续执行不再是“零散补点”，而是显式按顺序收口：
   - 先做 `P4-A / P4-B / P4-C`
   - 再跑真实外部研究 smoke gate
   - 如有暴露问题，插入 hardening pack 收工具栈与 verifier 闭环
   - 最后做 `P5-A / P5-B / P5-C`

3. 本次文档重构也把三个参考项目的吸收方式进一步落清：
   - 从 `Table-as-Search` 学状态表驱动和结构化更新；
   - 从 `DeepWideSearch` 学宽搜 / 深读 / 覆盖率与评测口径；
   - 从 `MiroFlow` 学工具编排、fallback、可恢复执行与演示感；
   - 但不照搬重型多 agent 平台复杂度。

4. 本次文档输出后的执行约束：
   - 后续每次新的实质实现前，仍先更新本阶段计划文档；
   - 每张卡继续按 TDD 执行；
   - 所有代码、联调、验收、展示都默认挂靠新总文档，不再回退到旧阶段叙事。

## 238. Phase 4 继续执行：按新总文档正式落地 `P4-A` 结果优先主界面（2026-07-07）
这一刀按新冻结的总执行文档，正式继续推进 `P4-A`。当前前端主结果区虽然已经有独立 Deep Research 视图与详情弹窗骨架，但主面板仍混入较多过程指标、Harness 摘要和 writeback 说明，离“结果、报告、来源优先”的最终展示口径还有明显距离。

1. 本刀锁定的最小交付物：
   - 保留独立入口表单与 run 历史面板；
   - 主结果区优先收成：
     - `Result Snapshot`
     - 正式 `Research Report`
     - `Sources`
   - 主结果区显式补齐：
     - `导出 Markdown`
     - `保存为资料`
   - 过程与审计相关内容尽量退回详情弹窗，不在主面板继续堆 `Harness Summary`、大段 process metrics 或过重的 writeback 解释。

2. 本刀优先要回答的问题：
   - 用户打开一个 run 后，第一眼看到的是否已经是结论、正式报告与来源，而不是搜索命中数和 checkpoint 数？
   - 报告是否可以直接导出为 `.md`，并与保存为资料动作并列成为主结果区显式出口？
   - 写回资料入口是否依旧清晰，但已经不再抢占主结果区叙事重心？

3. 本刀继续按 TDD / 契约方式推进：
   - 先检查 `ui-contract-check.mjs` 当前要求，避免误删必要展示文案；
   - 再改 `App.tsx` 主结果区结构；
   - 最后跑：
     - `npm run build`
     - `node scripts/ui-contract-check.mjs`

## 239. Phase 4 完成记录：`P4-A` 已把主结果区收成“结果 / 报告 / 来源优先”的正式主面板（2026-07-07）
这一刀已按总执行文档要求落地完成。当前 Deep Research 主结果区已经不再用大块 process metrics 和 `Research Harness Summary` 作为默认首屏，而是正式收成“结果、报告、来源优先”的主面板，过程与审计内容则继续下沉到详情弹窗承接。

1. 本次主结果区完成的结构性调整：
   - 顶部动作栏当前已显式补齐：
     - `刷新运行状态`
     - `导出 Markdown`
     - `保存为资料`
     - `打开研究详情`
   - 顶部原先大块 `research-process-overview` 指标卡已从主结果区移除；
   - 主面板当前已按顺序收成：
     - `Result Snapshot`
     - `Research Report`
     - `Workspace Sources`
     - `Source-Backed Findings`
     - `Source Provenance`
     - `Writeback Receipt`

2. 本次具体产品口径变化：
   - `Result Snapshot` 当前直接承接最终调研结论与 executive summary，而不再混入搜索命中数、阅读窗口数、checkpoint 数等过程指标；
   - `Research Report` 当前直接承接研究问题、研究意图摘要、关键结论与正式 markdown 正文预览，并成为主结果区的核心正文；
   - `保存为资料` 取代了偏实现口径的“报告写回资料池”，更贴近正式产品动作；
   - `Writeback Receipt` 仍保留在主面板，但已退到结果、报告、来源之后，作为成果出口凭证而不是首屏焦点。

3. 本次主动控制的复杂度：
   - 没有去动详情弹窗 `process / audit` 两层主骨架；
   - 没有删掉 `Writeback Receipt` 等现有契约敏感区块；
   - 没有继续在主结果区堆 `Research Harness Summary` 或原始 verifier 字段；
   - 这意味着本刀只收主面板叙事顺序，不提前把 `P4-B / P4-C` 混进来。

4. 本次验证结果：
   - `npm run build` 通过；
   - `node scripts/ui-contract-check.mjs` 通过；
   - 说明本刀在收主界面的同时，没有打散当前前端 Deep Research 相关展示契约。

5. 本刀完成后的站位与下一步：
   - `P4-A` 现在可以视为完成；
   - 下一步应按总执行文档继续进入 `P4-B`：
     - 把详情第一层正式收成可展示的 `process` 阅读面。

## 240. Phase 4 继续执行：进入 `P4-B`，把详情第一层收成正式 process 阅读面（2026-07-07）
这一刀正式进入总执行文档的 `P4-B`。当前详情弹窗第一层已经有不少过程内容，包括轮次回看、网页与工具轨迹、工作台回流等，但整体仍偏“信息很多、阅读主线不够强”，尤其 `search / fetch / read / source evidence` 还没有被压成一眼就能讲清的正式过程阅读面。

1. 本刀锁定的最小交付物：
   - 在 `process` 层顶部形成正式四段：
     - `Search Timeline`
     - `Fetch Timeline`
     - `Read Timeline`
     - `Source Evidence Summary`
   - 这四段优先直接消费正式 query object 与已收口的过程摘要对象，而不是要求用户先读 trace。
   - 原有更细的轮次回看、网页与工具轨迹等内容可以保留，但退为第二层过程细节，而不是 process 首屏叙事。

2. 本刀优先要回答的问题：
   - 用户打开 `调研过程` 后，能否先直接讲清“搜了什么、抓到了什么、读了什么、最后证据基础是什么”？
   - fetch/read 的 fallback、content type、read focus、evidence linkage 是否已经能通过第一层过程卡片讲出来，而不需要先翻 raw trace？
   - 过程层是否已经比现在更像正式阅读面，而不是一块混合式调试视图？

3. 本刀继续按 TDD / 契约方式推进：
   - 先检查现有 `ui-contract-check.mjs` 要求，避免误删既有 process 入口文案；
   - 再改 `App.tsx` 中 `researchDetailLayer === "process"` 的主结构；
   - 最后跑：
     - `npm run build`
     - `node scripts/ui-contract-check.mjs`

## 241. Phase 4 完成记录：`P4-B` 已把详情第一层收成正式 process 阅读面（2026-07-07）
这一刀已按总执行文档要求落地完成。当前 `调研过程` 第一层已经不再只依赖“轮次回看 + 网页轨迹”来承载全部过程叙事，而是先形成一组正式的过程摘要卡片，把 `search / fetch / read / source evidence` 直接压成首屏阅读面，下面再保留更细的轮次与轨迹细节。

1. 本次 process 层完成的结构性调整：
   - 详情第一层顶部当前已形成正式四段：
     - `Search Timeline`
     - `Fetch Timeline`
     - `Read Timeline`
     - `Source Evidence Summary`
   - 这四段当前优先消费：
     - `research_process_summary.search_read_timeline`
     - `research_process_summary.source_evidence_summary`
     - 以及必要的 task event / trace 回退信息
   - 原有：
     - `最近几轮研究推进`
     - `网页与工具轨迹`
     - 回流工作台过程卡
     仍然保留，但已经退到正式过程摘要之后，成为细节层而不是首屏主叙事。

2. 本次具体产品口径变化：
   - `Search Timeline` 当前直接回答“搜了什么”，包括 query samples、搜索命中与轮次推进；
   - `Fetch Timeline` 当前直接回答“抓到了什么”，包括 fetch foundation、orchestration foundation 与来源质量摘要；
   - `Read Timeline` 当前直接回答“读了什么”，包括 read focus、read strategy mix 与当前轮读窗信息；
   - `Source Evidence Summary` 当前直接回答“最后证据基础是什么”，包括 source basis、verified findings、citations 与 source scope。

3. 本次主动控制的复杂度：
   - 没有删掉原有 `Research Flow`、轮次回看和 `网页与工具轨迹` 这些已存在的细节视图；
   - 没有提前把 `P4-C` 的 verifier / 反证分支 / checkpoint 审计混进 process 首屏；
   - 没有为了做 process 摘要而重新依赖 raw JSON 展示，而是优先复用已收口的正式过程对象。

4. 本次验证结果：
   - `npm run build` 通过；
   - `node scripts/ui-contract-check.mjs` 通过；
   - 说明本刀在强化 process 首屏阅读面的同时，没有打散当前 Deep Research 前端既有入口和契约要求。

5. 本刀完成后的站位与下一步：
   - `P4-B` 现在可以视为完成；
   - 下一步应按总执行文档继续进入 `P4-C`：
     - 把详情第二层正式收成可展示的 audit 阅读面。

## 242. Phase 4 继续执行：进入 `P4-C`，把详情第二层收成正式 audit 阅读面（2026-07-07）
这一刀正式进入总执行文档的 `P4-C`。当前 `闭环审计` 第二层虽然已经有大量能力，包括 verifier decisions、checkpoint diff、resume diff、counterfactual branch diff、run history drift 等，但整体首屏仍偏“已经很强、但主线不够固定”，还没有先把用户最该看的四件事压成正式 audit 阅读面：

- verifier gate / final loop decision
- counterfactual branch summary
- checkpoint / resume summary
- harness control state

1. 本刀锁定的最小交付物：
   - 在 audit 层顶部形成正式四段：
     - `Verifier Gate / Final Loop Decision`
     - `Counterfactual Branch Summary`
     - `Checkpoint / Resume Summary`
     - `Harness Control State`
   - 这四段优先直接消费已收口的正式对象与当前 run summary，而不是要求用户先滚到下方 diff 区域才理解系统为什么继续搜、为什么纠偏、为什么允许写报告。
   - 原有更细的：
     - checkpoint 回放
     - resume recovery diff
     - counterfactual branch diff
     - run history drift
     等细节继续保留，但退为第二层审计细节。

2. 本刀优先要回答的问题：
   - 用户打开 `闭环审计` 后，能否第一眼看清 verifier 是怎么 gate 的？
   - 能否第一眼看清这次 run 有没有进入反证分支、当前 recovery mode 是什么？
   - 能否第一眼看清当前 run 与 checkpoint / resume 的关系，以及当前 harness control state 已推进到什么状态？

3. 本刀继续按 TDD / 契约方式推进：
   - 先检查 `ui-contract-check.mjs` 当前要求，避免误删既有 audit 展示入口；
   - 再改 `App.tsx` 中 `researchDetailLayer === "audit"` 的首屏结构；
   - 最后跑：
     - `npm run build`
     - `node scripts/ui-contract-check.mjs`

## 243. Phase 4 完成记录：`P4-C` 已把详情第二层收成正式 audit 阅读面（2026-07-07）
这一刀已按总执行文档要求落地完成。当前 `闭环审计` 第二层已经不再要求用户先读下方 diff 区或 checkpoint 回放，才能理解 verifier、反证分支、resume 与当前闭环状态，而是先形成一组正式 audit 摘要卡片，把最关键的四件事压成首屏阅读面，下面再继续承接更细的审计细节。

1. 本次 audit 层完成的结构性调整：
   - 详情第二层顶部当前已形成正式四段：
     - `Verifier Gate / Final Loop Decision`
     - `Counterfactual Branch Summary`
     - `Checkpoint / Resume Summary`
     - `Harness Control State`
   - 这四段当前优先消费：
     - `research_process_summary.audit_summary`
     - `counterfactual_summary`
     - `current run summary`
     - `closed_loop_state`
     - `resume source / recovery targets`
   - 原有：
     - `Formal Audit Summary`
     - `Closed-Loop State`
     - `Verifier-Gated Rows`
     - checkpoint / resume / counterfactual 各类 diff
     仍然保留，但已经退到首屏摘要之后，成为第二层审计细节而不是首屏主叙事。

2. 本次具体产品口径变化：
   - `Verifier Gate / Final Loop Decision` 当前直接回答“系统为什么允许继续、恢复或写报告”，并显式带出 `local_reason / global_reason / loop_reason`；
   - `Counterfactual Branch Summary` 当前直接回答“有没有走反证分支”，并显式带出 `recovery_mode / conflicted rows / recovery targets`；
   - `Checkpoint / Resume Summary` 当前直接回答“当前 run 与 checkpoint / resume 的关系是什么”，把 resume source 与 checkpoint 数直接拉到首屏；
   - `Harness Control State` 当前直接回答“当前闭环控制状态长什么样”，把 `branch / loop / rounds / rows / branches / decisions / checkpoints` 收成一眼可读的控制面。

3. 本次主动控制的复杂度：
   - 没有删掉原有 audit diff 能力；
   - 没有把 process 层的 `search / fetch / read` 摘要重新混回 audit 首屏；
   - 没有再加新的复杂播放器或调试模式；
   - 这意味着本刀只固定 audit 首屏读法，不去重写整套高级审计视图。

4. 本次验证结果：
   - `npm run build` 通过；
   - `node scripts/ui-contract-check.mjs` 通过；
   - 说明本刀在强化 audit 首屏阅读面的同时，没有打散当前 Deep Research 前端既有入口和契约要求。

5. 本刀完成后的站位与下一步：
   - `P4-C` 现在可以视为完成；
   - 到这里，`P4-A / P4-B / P4-C` 三刀都已完成；
   - 下一步应按总执行文档进入 `Gate 1`：
     - 跑 `2 - 3` 个真实研究样例，验证结果层、process 层、audit 层与资料回流是否已经能稳定讲清。

## 244. Gate 1 执行启动：建立可复跑的真实研究样例 smoke harness（2026-07-07）
这一刀正式进入总执行文档的 `Gate 1`。当前 `P4-A / P4-B / P4-C` 已完成，前端结果层、process 层、audit 层都已经具备正式展示面；但要判断系统是否真的进入“可演示、可复跑、可验收”的状态，还必须把 `2 - 3` 个真实研究样例收成可执行的 smoke gate，而不是停留在口头约定。

1. 本刀锁定的最小交付物：
   - 建立正式 `Gate 1` 样例清单，至少覆盖：
     - 宽搜 + 深读结合的研究
     - 带冲突证据与反证分支的研究
     - 带外部网页抓取与读取 fallback 的研究
   - 建立可复跑的 smoke harness：
     - 样例定义文件
     - 执行脚本
     - 最小结果校验口径
   - 尽量复用现有：
     - `POST /api/v2/workspaces/{workspaceId}/research-runs`
     - `save-report-as-source`
     - worker / dispatcher 启动脚本
     - 现有 worker regression / backend contract test

2. 本刀优先要回答的问题：
   - Gate 1 之后，团队是否有一套固定入口能重复跑真实 Deep Research 样例，而不是靠手工点界面？
   - 每个样例的“为什么选它、主要看什么、跑完算通过什么”是否已经写清？
   - 如果当前环境不适合完整跑通外部研究，是否至少已经把 harness、脚本、样例和最小校验路径收成正式工件，为后续真跑提供稳定入口？

3. 本刀继续按 TDD / 契约方式推进：
   - 先审查现有 research run 创建接口、dispatcher、worker 启动脚本和测试入口；
   - 再补 `Gate 1` 样例定义与 smoke harness；
   - 然后尽量跑：
     - 现有 worker / backend 相关测试
     - 或最小可验证链路
   - 最后把结果与未验证项明确写回阶段计划。

## 245. 文档路线冻结：总执行文档改写为“当前站位 -> 完整交付”的单向收口手册（2026-07-07）
这一刀不继续做新的代码实现，而是先把总执行文档彻底改成“从当前情况到全部完成交付”的冻结手册，避免后续仍按“这一轮做什么”的口径分散推进。改写后的总文档已经明确：

1. 当前真实站位：
   - `P4-A / P4-B / P4-C` 视为已完成；
   - 当前不再回到 `P1 / P2 / P3 / P4` 重排；
   - 当前剩余主线聚焦在 `Gate 1 -> 必要硬化 -> P5-A -> P5-B -> P5-C`。
2. 交付主轴：
   - 先把 `Gate 1` 真实研究样例、执行入口、smoke 校验做成正式工件；
   - 再用真实样例反查 agent 内部是否需要 `H1 / H2 / H3`；
   - 最后收口报告导出、资料回流、demo 套件和项目讲解口径。
3. 这次文档特别强化的口径：
   - `Deep Research` 是独立功能，任务入口只来自用户显式输入；
   - 交付亮点必须收束到：
     - `Research Harness`
     - `Closed-Loop Research`
     - `Table-as-State`
     - `Dual Verifier`
     - `反证分支`
   - 参考 `Table-as-Search / DeepWideSearch / MiroFlow` 的长处，但不照搬重型复杂度。
4. 从本条之后，默认执行规则不变：
   - 后续每次新的实质实现前，仍先更新本阶段计划；
   - 但执行路线一律按新总文档的冻结顺序推进，不再回到散点式补字段思路。

## 246. Gate 1 继续执行：先把真实研究样例 smoke harness 做成正式测试契约与执行入口（2026-07-07）
这一刀正式继续落实总执行文档中的 `Gate 1`。当前 worker 侧已经有：

- `test_harness.py` 里的命名样例构造器
- `harness_regression.py` 里的回归样例评估器
- `run_research_task(...)` 的正式执行主链

但现在仍缺三个正式工件：

1. 独立于 `test_harness.py` 的 `Gate 1` 样例定义模块
2. 面向 `Gate 1` 的 smoke test 与通过口径
3. 一个稳定的脚本入口，用来重复跑 `Gate 1`

本刀锁定的最小交付物：

1. 先补 `Gate 1` 样例 smoke 测试，覆盖三类正式样例：
   - wide + deep
   - conflict + `反证分支`
   - external fetch/read fallback
2. 再补正式样例定义模块，避免 Gate 1 继续依赖测试文件私有 builder。
3. 再补执行脚本，把 `Gate 1` 跑法固定下来。
4. 最后跑最小测试链路，把结果写回阶段计划。

这一刀继续按 TDD 推进：

- 先写失败测试
- 再补实现
- 最后执行相关 pytest 或脚本校验

## 247. Gate 1 完成记录：正式样例定义、smoke 测试与执行脚本已落地（2026-07-07）
这一刀已经按 `Gate 1` 目标完成第一批正式工件，worker 侧现在不再只是“有回归样例素材”，而是已经有一套可复跑、可输出结果摘要的正式 smoke harness。

本次新增的正式工件：

1. `workers/research-worker/app/gate1_smoke.py`
   - 建立了 3 个正式 `Gate 1` 样例：
     - `wide_deep_report`
     - `conflict_counterfactual`
     - `external_fetch_fallback`
   - 提供了：
     - `build_gate1_smoke_suite()`
     - `run_gate1_smoke_case(...)`
     - `run_gate1_smoke_suite(...)`
     - `evaluate_gate1_smoke_case(...)`
   - 其中前两个样例还与现有 `harness_regression` 做了对齐校验，避免 Gate 1 和既有 Harness 回归口径脱节。

2. `workers/research-worker/tests/test_gate1_smoke.py`
   - 先按 TDD 补了失败测试，再补实现。
   - 当前覆盖：
     - 样例清单固定为三类正式 Gate 1 case
     - 单样例 wide + deep 验收
     - 单样例 conflict + `反证分支` 验收
     - 单样例 external fetch/read fallback 验收
     - 整套 Gate 1 suite 汇总结果验收

3. `workers/scripts/run-gate1-smoke.ps1`
   - 固定了 `Gate 1` 脚本入口，后续可直接复跑。

4. `workers/README.md`
   - 已补 Gate 1 smoke 命令入口说明。

本次 `Gate 1` 通过口径已经明确固化到评估器里：

1. 必须产出正式报告
2. 必须具备可消费的 `process` 基础对象
   - 当前用 `toolbox_summary.search/fetch/read` 作为 worker 侧过程层凭证
3. 必须具备可消费的 `audit` 基础对象
   - 当前用 `audit_summaries + verifier_gate_policy` 作为 worker 侧审计层凭证
4. conflict 样例必须能显式证明 `反证分支` 存在
5. external 样例必须能显式证明 fetch/read fallback 存在

本次实际验证结果：

1. `conda run -n noteweave-workers python -m pytest workers/research-worker/tests/test_gate1_smoke.py`
   - `5 passed`
2. `powershell -ExecutionPolicy Bypass -File workers/scripts/run-gate1-smoke.ps1`
   - `case_count = 3`
   - `passed_count = 3`
   - `failed_case_keys = []`
   - wide + deep：通过
   - conflict + `反证分支`：通过
   - external fetch/read fallback：通过

当前站位更新：

1. `Gate 1` 的第一批正式工件已经建立完成。
2. 下一步不再是“先补入口”，而是要根据这套 Gate 1 结果继续判断是否需要插入：
   - `H1` 工具栈硬化
   - `H2` verifier / 反证分支硬化
   - `H3` checkpoint / resume 硬化
3. 如果暂未暴露新的硬缺口，则继续进入：
   - `P5-A` 报告导出 / 资料回流闭环收口

## 248. P5-A 执行启动：把导出 md / 保存为资料 / 资料复用链路收成正式验证入口（2026-07-07）
这一刀正式进入总执行文档中的 `P5-A`。当前从代码与既有测试证据看：

1. 后端主链已经具备：
   - `final_report_markdown`
   - `report_file`
   - `save-report-as-source`
   - downstream `source_scope reuse`
2. `Phase6ResearchArtifactContractTest` 里已经有：
   - completed report save-as-source
   - saved report downstream reuse
   - checkpoint / detail / history 中的成果物同源断言
3. 前端已经有：
   - `exportResearchReportMarkdown()`
   - `saveResearchReportAsSource()`
   - 主界面显式 `导出 Markdown / 保存为资料`

但当前还缺两类 `P5-A` 正式交付工件：

1. 前端 `export md` 还没有独立契约测试，仍只是 UI 动作存在；
2. `P5-A` 还没有一套固定、清晰、可重复执行的正式验证入口，来证明
   - `run -> export md -> save as source -> source_scope reuse`
   这条链已经被系统化验收。

本刀锁定的最小交付物：

1. 把前端导出逻辑抽成可测试 helper，并补单测；
2. 补一条 `P5-A` 固定验证脚本，统一串起前端导出契约与后端回流 / reuse 契约；
3. 把运行方式和用途补进文档，作为正式交付入口的一部分。

这一刀继续按 TDD 推进：

- 先补前端测试或验证契约
- 再补实现
- 最后跑相关前端测试与 `P5-A` 验证脚本

## 249. P5-A 完成记录：导出契约、写回 / 复用契约与固定验证脚本已落地（2026-07-07）
这一刀已经把 `P5-A` 的第一批正式交付工件补齐。当前 `run -> export md -> save as source -> source_scope reuse` 这条链不再只是“功能存在”，而是已经具备固定验证入口和清晰的验证边界。

本次新增与调整的工件：

1. 前端导出 helper：
   - `frontend/src/researchReportDelivery.ts`
   - 把 `App.tsx` 里的导出逻辑抽成正式 helper：
     - `buildResearchReportExportArtifact(...)`
     - `buildResearchReportExportStatusMessage(...)`
   - 目的：
     - 让 `export md` 从 UI 动作提升为可单测契约
     - 固定 `title / question / default name` 的回退与文件名清洗规则

2. 前端导出单测：
   - `frontend/src/researchReportDelivery.test.ts`
   - 当前覆盖：
     - 正常导出 artifact 生成
     - 标题为空白时回退到 `question`
     - 标题和问题都不可用时回退到默认文件名
     - 无 markdown 内容时不生成导出对象
     - 导出状态文案稳定

3. `App.tsx` 已接入导出 helper：
   - `exportResearchReportMarkdown()` 当前不再在函数内部零散拼接文件名规则；
   - 直接复用 `researchReportDelivery.ts` 的正式导出契约。

4. `P5-A` 固定验证脚本：
   - `scripts/verify-p5a-research-delivery.ps1`
   - 串起两段正式验证：
     - 前端 export md 契约
     - 后端 save-as-source + downstream source_scope reuse 契约

5. `P5-A` 验证说明文档：
   - `docs/DeepResearch-P5A验证说明.md`
   - 固定：
     - 当前验证入口
     - 前端验证覆盖范围
     - 后端验证覆盖范围
     - 跑通后可证明的交付结论

本次 TDD 过程里还顺手修复了一个真实边界缺口：

1. 初版前端测试暴露：
   - 当 `final_report_title` 只是空白字符串时，导出文件名没有回退到 `question`
2. 已修复为：
   - 先按 `title -> question -> default` 做 `trim` 后回退
   - 再统一做安全字符清洗与文件名归一化

本次验证结果：

1. `npm --prefix frontend run test -- src/researchReportDelivery.test.ts`
   - `5 passed`
2. `.\mvnw.cmd -f backend/pom.xml "-Dtest=Phase6ResearchArtifactContractTest#completedResearchReportShouldBeSavedAsWorkspaceSource+savedResearchReportShouldRemainIdentifiableInChatCitations" test`
   - `Tests run: 2, Failures: 0, Errors: 0`
3. `powershell -ExecutionPolicy Bypass -File scripts/verify-p5a-research-delivery.ps1`
   - 前端导出契约：通过
   - 后端写回 / 复用契约：通过
4. `npm --prefix frontend run build`
   - 构建通过

当前站位更新：

1. `P5-A` 的正式验证入口已经建立。
2. 当前已经能更有把握地证明：
   - `export md`
   - `save as source`
   - `source_scope reuse`
   这三段都不是口头能力，而是有固定测试或脚本支持的正式交付链。
3. 下一步可继续进入：
   - `P5-B` demo 套件与固定验收命令收口

## 250. P5-B 执行启动：把 demo 套件和固定验收命令做成正式工件（2026-07-07）
这一刀正式进入总执行文档中的 `P5-B`。当前 `Gate 1` 和 `P5-A` 都已经有正式脚本入口，但 `Deep Research` 还没有一套完整的“演示包”视角工件：

1. 还缺一份固定 demo 套件清单，明确：
   - 选哪 2 - 3 个样例
   - 每个样例的预期亮点
   - 每个样例看哪些来源展示点、process 展示点、audit 展示点
2. 还缺一组真正的一键固定验收命令，来统一串起：
   - worker smoke / regression
   - backend contract test
   - frontend build / ui contract

本刀锁定的最小交付物：

1. 一份正式 demo suite manifest
2. 一份面向人阅读的 demo 套件说明文档
3. 一条固定 `P5-B` 验收脚本
4. 至少一个自动校验入口，确保 demo suite manifest 结构稳定

这一刀继续按 TDD 推进：

- 先补 demo suite 结构校验
- 再补 manifest 与脚本
- 最后跑固定验收命令

## 251. P5-B 完成记录：demo suite manifest、说明文档与一键验收脚本已落地（2026-07-07）
这一刀已经把 `P5-B` 的第一批正式工件补齐。当前 `Deep Research` 不再只是“有几个可以演示的样例”，而是已经有：

1. 固定 demo 样例清单
2. 固定样例说明
3. 固定验收命令
4. 一键总验收脚本

本次新增工件：

1. `docs/DeepResearch-P5B-demo-suite.json`
   - 作为结构化 demo suite manifest
   - 当前固定 3 个 demo case：
     - `wide_deep_report`
     - `conflict_counterfactual`
     - `external_fetch_fallback`
   - 每个 case 都明确了：
     - `objective`
     - `expected_highlights`
     - `source_display_points`
     - `process_display_points`
     - `audit_display_points`
   - 同时固定了 `P5-B` 验收命令清单

2. `scripts/check-p5b-demo-suite.mjs`
   - 作为 manifest 结构校验入口
   - 当前会强制检查：
     - `suite_key`
     - `demo_cases` 数量必须为 `2 - 3`
     - 每个 case 的关键字段齐全
     - 固定验收命令至少包含：
       - `gate1_smoke`
       - `backend_contract`
       - `frontend_build`
       - `frontend_ui_contract`

3. `docs/DeepResearch-P5B-demo-suite.md`
   - 作为面向人阅读的正式 demo 套件说明
   - 把三类样例的目标、亮点、来源展示点、process 展示点、audit 展示点固定下来

4. `scripts/verify-p5b-demo-suite.ps1`
   - 作为 `P5-B` 一键验收脚本
   - 当前串起：
     - `node scripts/check-p5b-demo-suite.mjs`
     - `powershell -ExecutionPolicy Bypass -File workers/scripts/run-gate1-smoke.ps1`
     - `conda run -n noteweave-workers python -m pytest workers/research-worker/tests/test_harness.py`
     - `.\mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test`
     - `npm --prefix frontend run build`
     - `npm --prefix frontend run ui:check`

本次 TDD 过程里还补了一个真实边界修复：

1. `scripts/check-p5b-demo-suite.mjs` 初版在 Windows 下路径解析出现双盘符
2. 已修复为使用 `fileURLToPath(import.meta.url)` 构造脚本路径

本次验证结果：

1. `node scripts/check-p5b-demo-suite.mjs`
   - `demo suite manifest OK: 3 demo cases, 6 commands`
2. `conda run -n noteweave-workers python -m pytest workers/research-worker/tests/test_harness.py`
   - `22 passed`
3. `npm --prefix frontend run ui:check`
   - `UI contract check passed`
4. `powershell -ExecutionPolicy Bypass -File scripts/verify-p5b-demo-suite.ps1`
   - Gate 1 smoke：通过
   - worker regression：通过
   - backend `Phase6ResearchArtifactContractTest`：`19 tests` 全部通过
   - frontend build：通过
   - frontend ui contract：通过

当前站位更新：

1. `P5-B` 的 demo 套件和固定验收命令已经形成正式工件。
2. 当前已经能更有把握地证明：
   - 不是只有功能链路能跑
   - 而是有一套固定 demo 和固定命令可供复跑、验收、展示
3. 下一步可继续进入：
   - `P5-C` 项目讲解与简历亮点收口

## 252. P5-C 执行启动：把项目讲解口径、demo 顺序和亮点证据映射收成正式工件（2026-07-07）
这一刀正式进入总执行文档中的 `P5-C`。当前已经有：

1. `Gate 1` 样例说明
2. `P5-A` 验证说明
3. `P5-B` demo suite
4. 一些简历草案和架构文档

但还缺一套真正面向最终交付的 `P5-C` 工件，来解决两个问题：

1. 现在虽然“有很多文档”，但还没有一套固定讲解口径，能把
   - `Research Harness`
   - `Closed-Loop Research`
   - `Table-as-State`
   - `Dual Verifier`
   - `反证分支`
   顺序稳定讲清
2. 现在虽然“知道有哪些亮点”，但还没有把亮点直接绑定到真实代码、真实脚本、真实验证入口，容易讲成概念口号

本刀锁定的最小交付物：

1. 一份固定项目讲解稿
2. 一份固定 demo 顺序说明
3. 一份“亮点 -> 真实对象证据”映射稿
4. 一个校验入口，确保上述工件结构稳定、关键词齐全

这一刀继续按 TDD 推进：

- 先补讲解工件结构校验
- 再补正式讲解稿和亮点映射稿
- 最后跑校验脚本

## 253. P5-C 完成记录：固定讲解稿、亮点证据映射与讲解校验入口已落地（2026-07-07）
这一刀已经把 `P5-C` 的第一批正式工件补齐。当前 `Deep Research` 不再只是“有亮点可以讲”，而是已经有一套固定讲法，并且能把亮点直接映射到真实代码、真实脚本和真实验证入口。

本次新增工件：

1. `docs/DeepResearch-P5C-项目讲解稿.md`
   - 固定了：
     - 为什么这不是普通问答
     - 固定 demo 顺序
     - 五个核心关键词怎么讲
     - 简历亮点短版
   - 当前讲解稿强制覆盖：
     - `Research Harness`
     - `Closed-Loop Research`
     - `Table-as-State`
     - `Dual Verifier`
     - `反证分支`

2. `docs/DeepResearch-P5C-亮点证据映射.json`
   - 把每个核心关键词直接映射到真实对象：
     - worker 代码
     - Gate 1 样例
     - P5-A / P5-B 验证脚本
     - 前端展示对象
   - 同时固定了 demo flow 的顺序和每一步对应证据

3. `scripts/check-p5c-delivery-pack.mjs`
   - 作为 `P5-C` 结构校验器
   - 当前会强制检查：
     - 项目讲解稿存在
     - 亮点证据映射存在
     - 讲解稿必须包含 5 个固定关键词
     - 讲解稿必须包含固定章节：
       - `为什么不是普通问答`
       - `固定 Demo 顺序`
       - `五个核心关键词怎么讲`
       - `简历亮点短版`
     - 证据映射必须包含 5 个核心关键词和 demo flow

4. `scripts/verify-p5c-delivery-pack.ps1`
   - 作为 `P5-C` 验证入口
   - 当前用于固定讲解稿和亮点映射的结构校验

本次验证结果：

1. `node scripts/check-p5c-delivery-pack.mjs`
   - `delivery pack OK: 5 keywords, 5 demo flow steps`
2. `powershell -ExecutionPolicy Bypass -File scripts/verify-p5c-delivery-pack.ps1`
   - 通过

当前站位更新：

1. `P5-C` 的固定讲解稿和亮点证据映射已经形成正式工件。
2. 当前已经能更有把握地证明：
   - 项目亮点不是概念包装
   - 每个亮点都能落到真实代码、真实脚本和真实验证入口
3. 下一步应继续做最终交付审计：
   - 按总执行文档的最终验收矩阵跑全量验证

## 254. 总执行文档收口重写：从“阶段路线图”切换为“当前状态到最终交付”的冻结手册（2026-07-07）
这一刀不继续扩实现，先把总执行文档按当前真实站位重写。原因很明确：`Gate 1 / P5-A / P5-B / P5-C` 已经都有正式工件，如果总文档还停留在“准备去做这些卡”的口径，就会和当前代码状态脱节，后续执行容易重新横跳回阶段路线，而不是聚焦最后收口。

本次重写的目标不是新增功能，而是统一后续执行语义：

1. 明确哪些部分已经完成并冻结：
   - `Research Harness`
   - `Closed-Loop Research`
   - `Table-as-State`
   - `Dual Verifier`
   - `反证分支`
   - `Gate 1 / P5-A / P5-B / P5-C` 正式工件
2. 明确从当前到全部完成真正还剩什么：
   - `F1` 全量验收解阻
   - `F2` 最终缺口清理
   - 条件触发 `H1 / H2 / H3`
   - `F3` 全量证据补齐
   - `F4` 最终交付冻结
3. 明确 `H1 / H2 / H3` 不再是默认主线，而是只在真实验收暴露问题时才触发的条件硬化包。
4. 明确最终完成判定必须包含：
   - Worker
   - Backend
   - Frontend
   - E2E
   - Demo / Delivery Pack
   - Final Aggregate
   六层证据闭环，而不只是“功能感觉差不多”。

本次落地工件：

1. `docs/深度研究智能体全量交付执行文档.md`
   - 已重写为“当前状态 -> 最终交付”的冻结手册
   - 不再把 `Gate 1 / P5-A / P5-B / P5-C` 写成未完成主线
   - 新主线固定为：
     - `F1`
     - `F2`
     - 条件触发 `H1 / H2 / H3`
     - `F3`
     - `F4`

当前站位更新：

1. 后续实现和修复都必须先服从新的总执行文档口径。
2. 从现在开始，默认优先级不再是“继续规划能力”，而是“把最终聚合验收跑通并收口证据”。
3. 下一步应继续进入：
   - `F1` 全量验收解阻
   - 优先处理当前 backend 合同测试阻塞，再回到全量验收脚本

## 255. F1 执行启动：先验证 backend 阻塞是否仍然成立，再回到聚合验收链（2026-07-07）
这一刀正式进入总执行文档中的 `F1`。按照新的最终收口手册，当前默认主线已经不再是继续铺 `Gate 1 / P5-A / P5-B / P5-C`，而是先把最终聚合验收真正跑通。

本刀先做了最小现实核验，而不是直接假设“backend 还坏着”：

1. 重新检查了 [Phase6ResearchArtifactContractTest.java](/D:/java-projects/NoteWeave-v2/backend/src/test/java/com/noteweave/Phase6ResearchArtifactContractTest.java) 先前报错附近的代码片段。
2. 直接重跑了 targeted backend 合同测试：
   - `.\mvnw.cmd -f backend/pom.xml "-Dtest=Phase6ResearchArtifactContractTest#completedResearchReportShouldBeSavedAsWorkspaceSource+savedResearchReportShouldRemainIdentifiableInChatCitations" test`
3. 当前结果表明：
   - 这条 targeted backend test 在当前 worktree 中已经可以直接通过
   - 因而 `F1` 的真实任务从“先修一个必现 compile fail”，收敛成“继续重跑更宽的 backend / aggregate 验收，确认真正剩余阻塞是什么”

当前站位更新：

1. 之前记录过的 backend compile 阻塞，在当前工作树里没有立即复现。
2. 下一步不应凭记忆继续修假问题，而应直接进入更高一层回归：
   - `.\mvnw.cmd -f backend/pom.xml "-Dtest=Phase6ResearchArtifactContractTest" test`
   - `powershell -ExecutionPolicy Bypass -File scripts/verify-p5a-research-delivery.ps1`
   - `powershell -ExecutionPolicy Bypass -File scripts/verify-p5b-demo-suite.ps1`
   - `powershell -ExecutionPolicy Bypass -File scripts/verify-p5c-delivery-pack.ps1`
   - `powershell -ExecutionPolicy Bypass -File scripts/verify-deepresearch-final-delivery.ps1`
3. 只有这些更宽的验收真的再次失败，才进入 `F2` 或条件触发 `H1 / H2 / H3`。

## 256. F2 执行启动：修复 frontend 契约漂移与验收脚本失败透传缺口（2026-07-07）
在 `F1` 回归里，backend 合同测试与 `P5-A / P5-C` 都已经通过，但 `FINAL -> P5-B -> frontend build` 暴露了两个真实缺口：

1. frontend 若干测试夹具仍在使用 `provider_job_status` 字段：
   - `frontend/src/artifactRuntimeTrace.test.ts`
   - `frontend/src/artifactRuntimeTraceDetails.test.ts`
   - `frontend/src/runStatus.test.ts`
   但当前类型定义已经不再包含该字段，导致 `npm --prefix frontend run build` 失败。
2. `scripts/verify-p5b-demo-suite.ps1` 与 `scripts/verify-deepresearch-final-delivery.ps1` 虽然设置了 `$ErrorActionPreference = "Stop"`，但对外部命令退出码没有做显式检查，导致 frontend build 失败后脚本仍继续执行，验收结果不够可信。

这一刀的修复目标固定为最小闭环：

1. 对齐 frontend 测试夹具与当前类型契约，不再引用已删除字段。
2. 让 `P5-B / FINAL` PowerShell 验收脚本在任一外部命令非零退出时立即失败。
3. 修复后只回归：
   - `npm --prefix frontend run build`
   - `powershell -ExecutionPolicy Bypass -File scripts/verify-p5b-demo-suite.ps1`
   - `powershell -ExecutionPolicy Bypass -File scripts/verify-deepresearch-final-delivery.ps1`
4. 不借机扩展 artifact runtime 展示能力，也不改 Deep Research 主链语义。

## 257. F2 完成记录：frontend 契约漂移与验收脚本假绿问题已修复（2026-07-07）
这一刀完成了 `F2` 的最小修复，而且修的是两类真正影响最终交付可信度的问题：

1. frontend 契约漂移
   - `artifactRuntimeTrace / artifactRuntimeTraceDetails / runStatus` 补回了对 `provider_job_status` 的兼容读取
   - 对应类型定义也补齐了：
     - `ArtifactAcquisitionCallbackReceiptTrace`
     - `ArtifactAcquisitionOperationTrace`
     - `WaitProviderJob`
   - 同时保留现有 `callback_status / status` 路径，避免把修复做成单点硬编码
2. 验收脚本假绿
   - `verify-p5a-research-delivery.ps1`
   - `verify-p5b-demo-suite.ps1`
   - `verify-p5c-delivery-pack.ps1`
   - `verify-deepresearch-final-delivery.ps1`
   现在都补了显式 `LASTEXITCODE` 检查，任一外部命令失败都会立即退出，不再出现 build 失败但脚本继续向下跑的假通过

这刀修复里顺手暴露并收掉了一个更底层的 backend 契约缺口：

1. `P5-B` 重新回归后，fail-fast 脚本把真正失败定位到了 backend `Phase6ResearchArtifactContractTest`
2. 真实缺口是 `provider_job_status` 没有稳定穿透到：
   - `WaitProviderJobResponse`
   - artifact runtime trace response
   - artifact acquisition ack response
3. 同时补齐了 test stub 构造参数和 artifact trace response 的显式字段映射，确保：
   - wait_context
   - acquisition ack response
   - runtime_trace.acquisition_callback_trace
   三处合同重新一致

本次验证结果：

1. `npm --prefix frontend run test -- src/artifactRuntimeTrace.test.ts src/artifactRuntimeTraceDetails.test.ts src/runStatus.test.ts`
   - `3 files, 8 tests` 全部通过
2. `npm --prefix frontend run build`
   - 通过
3. `.\mvnw.cmd -f backend/pom.xml "-Dtest=Phase6ResearchArtifactContractTest#artifactWorkerResumeControlShouldResumeWaitingTaskAndPersistVersion+artifactAcquisitionAckControlShouldForwardToWorkerAndCompleteWaitingJob" test`
   - 通过
4. `.\mvnw.cmd -f backend/pom.xml "-Dtest=Phase6ResearchArtifactContractTest" test`
   - `19 tests` 全部通过

当前站位更新：

1. `F2` 的真实缺口已经收掉。
2. 现在聚合验收再次具备可信性，因为脚本已经不再掩盖失败。
3. 下一步进入最终收口验证：
   - `verify-p5b-demo-suite.ps1`
   - `verify-deepresearch-final-delivery.ps1`

## 258. F1-F4 最终收口记录：P5-B 与 FINAL 聚合验收链已真实通过（2026-07-07）
在 `F2` 修复后，本轮继续把系统放回最终收口链验证，当前已经拿到完整通过证据：

1. backend 合同：
   - `.\mvnw.cmd -f backend/pom.xml "-Dtest=Phase6ResearchArtifactContractTest" test`
   - `19 tests, 0 failures`
2. frontend 受影响合同：
   - `npm --prefix frontend run test -- src/artifactRuntimeTrace.test.ts src/artifactRuntimeTraceDetails.test.ts src/runStatus.test.ts`
   - `8 tests, 0 failures`
   - `npm --prefix frontend run build`
   - 通过
3. `P5-B`：
   - `powershell -ExecutionPolicy Bypass -File scripts/verify-p5b-demo-suite.ps1`
   - 当前已真实通过，而且如果其中任一步失败会立即中断
4. `FINAL`：
   - `powershell -ExecutionPolicy Bypass -File scripts/verify-deepresearch-final-delivery.ps1`
   - 当前已真实通过

这意味着总执行文档里的最终验收矩阵已经被重新证明：

1. Worker / Agent
   - Gate 1 smoke 通过
   - research worker regression 通过
2. Backend
   - `Phase6ResearchArtifactContractTest` 全量通过
3. Frontend
   - build 通过
   - UI contract 通过
   - 关键 runtime / wait helpers 单测通过
4. End-to-End
   - `P5-A` 导出 / 写回 / 复用链已包含在 FINAL 中再次通过
5. Demo / Delivery Pack
   - `P5-B` 与 `P5-C` 都在 FINAL 中再次通过
6. Final Aggregate
   - `verify-deepresearch-final-delivery.ps1` 已通过

当前站位更新：

1. `F1` 全量验收解阻：完成
2. `F2` 最终缺口清理：完成
3. `F3` 全量证据补齐：完成
4. `F4` 最终交付冻结：已具备证据基础

如果后续不再发现新的真实回归，这一轮已经把 `Deep Research` 从“接近完成”推进到“具备完整交付证据”。

## 259. 架构文档补齐：沉淀一份属于 Research Agent 的正式架构文档，补清价格模型、设计边界与亮点对齐（2026-07-07）
这一刀不再扩功能，而是把已经落地的 `Deep Research` 主链抽成一份可以直接用于讲解、评审、简历对齐和后续迭代约束的正式架构文档。原因很直接：当前虽然已经有

1. 阶段计划
2. 总执行文档
3. P5-C 讲解稿
4. 亮点证据映射

但这些工件分别偏向：

1. 过程记录
2. 交付冻结
3. Demo 讲法
4. 证据索引

还缺一份专门回答下面三个问题的 `Research Agent` 架构文档：

1. 这个 agent 到底怎么设计，模块边界和闭环主链是什么
2. 这套系统一次研究任务的成本和价格应该怎么理解、怎么控制
3. 它和简历亮点、项目亮点、五个关键词到底怎么一一对齐

本轮文档口径固定为：

1. `Deep Research` 是与工作台绑定的独立功能，不是 `Ask / Note / Wiki` 升级态
2. 任务唯一入口是用户显式输入研究问题，`source scope` 只是可选控制包
3. 架构中心仍然固定为：
   - `Research Harness`
   - `Closed-Loop Research`
   - `Table-as-State`
   - `Dual Verifier`
   - `反证分支`
4. 展示层口径仍然固定为：
   - 主界面先看研究结果、报告、来源
   - 详情弹窗再看 `process`
   - 第二层再看 `audit`
5. 价格部分不写成依赖某一家模型实时单价的脆弱表，而是写成：
   - 内部运行成本模型
   - 预算控制策略
   - 档位化产品定价建议

本轮新增正式工件：

1. `docs/DeepResearch-ResearchAgent架构文档.md`
   - 面向 `Research Agent` 本体，而不是泛工作台总览
   - 详细说明：
     - 产品边界
     - 主链路
     - 工具层
     - 状态层
     - 报告层
     - 展示层
     - 成本 / 价格
     - 亮点对齐
     - 参考项目取舍
     - 当前落地证据

当前站位更新：

1. `Deep Research` 的“实现文档 / 交付文档 / 讲解稿 / 证据映射 / 架构文档”五件套开始齐套
2. 后续无论是继续迭代 agent 内核、补充展示层，还是写简历和项目介绍，都应优先服从这份正式架构文档的口径

## 260. 面试回答手册补齐：围绕亮点知识点，把“为什么这么做、具体怎么做、取舍是什么”讲透（2026-07-07）
这一刀继续补文档资产，但目标从“正式架构说明”切到“面试可直接回答”。原因是当前虽然已经有：

1. 正式架构文档
2. P5-C 项目讲解稿
3. 亮点证据映射

但这些文档更偏：

1. 架构说明
2. demo 讲法
3. 证据索引

还缺一份专门面向面试问题的回答手册，来回答下面这些高频追问：

1. 你这个 Deep Research 和普通 RAG / 普通 Agent 有什么本质区别
2. 为什么要设计 `Research Harness`
3. `Table-as-State` 到底解决了什么问题，和 prompt 记忆相比好在哪里
4. `Dual Verifier` 具体怎么工作，为什么不是单 verifier
5. `反证分支` 怎么做，为什么它不是“多开几条链路”这么简单
6. 工具层为什么要做 `search / fetch / read` 分层
7. 成本为什么能控，价格为什么应该按档位卖
8. 为什么最后交付的是报告，不是一个答案

本轮文档目标固定为：

1. 每个亮点都必须讲清：
   - 问题背景
   - 核心设计
   - 具体实现
   - 技术取舍
   - 怎么证明它真的落地了
2. 口径继续围绕五个关键词：
   - `Research Harness`
   - `Closed-Loop Research`
   - `Table-as-State`
   - `Dual Verifier`
   - `反证分支`
3. 额外补充面试常问知识点：
   - RAG vs Deep Research
   - query family / wide-to-deep 检索编排
   - fetch / fallback / snapshot
   - checkpoint / resume
   - report artifact / save as source
   - 成本与定价

本轮新增正式工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 面向面试追问
   - 强调“怎么做的”
   - 适合口头回答和项目讲解

当前站位更新：

1. `Deep Research` 的文档资产从“能说明系统”推进到“能支撑面试和答辩”
2. 后续如果再补简历亮点或项目讲稿，应优先复用这份面试回答手册里的问题口径

## 261. 面试回答手册深化：从“会讲亮点”继续补到“能回答追问、能讲实现细节、能讲取舍”（2026-07-07）
这一刀继续深化 `Research Agent` 面试回答手册，目标不再只是让文档“有问题有回答”，而是让它能够覆盖真实技术面中的二轮追问。因为面试里最容易卡住的不是第一句亮点，而是后面的这些问题：

1. 你说 `Dual Verifier`，那它具体判定什么、怎么决定继续还是收口
2. 你说 `Table-as-State`，那状态结构长什么样、和 requirement progress 怎么对齐
3. 你说 `反证分支`，那 recovery target 怎么生成、counterfactual query 怎么打
4. 你说 fetch/fallback，为什么不是简单抓正文失败就放弃
5. 你说成本可控，那预算到底落在哪些字段上
6. 你怎么证明这套系统真的可靠，不是概念包装

本轮深化目标固定为：

1. 每个亮点再往下补一层“实现细节”
2. 增加：
   - 典型运行样例讲法
   - 判定逻辑讲法
   - 可靠性与测试讲法
   - 取舍与反问讲法
3. 让面试手册具备三层回答能力：
   - 开场亮点
   - 技术追问
   - 深入实现与取舍

本轮继续更新工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 新增更细的追问回答
   - 新增实现级知识点讲法
   - 新增可靠性、测试、失败模式与取舍

当前站位更新：

1. 面试回答文档开始从“讲项目”推进到“扛技术追问”
2. 后续如果还要继续深化，优先围绕真实面试追问，而不是继续扩概念词汇

## 262. 面试手册再深化：把 Table 详细机制改写成口语化话术，并补齐刁钻追问题库（2026-07-07）
这一刀继续深化 `Research Agent` 面试手册，目标是把已经梳理清楚的 `Table-as-State` 内部机制，从“技术分析”改写成“面试能直接说”的话术，并补上更刁钻、更细的追问题库。因为真实面试里，面试官很容易在听到亮点词之后继续往下追：

1. 你说 Table-as-State，那这个 table 到底存了什么
2. row 和 cell 为什么要分开
3. requirement progress 是怎么和 row 对齐的
4. verifier 到底是怎么消费 table 的
5. branch 开了以后 table 会怎么变
6. 为什么不直接用图结构或 JSON，而要用 table
7. table 会不会很重、很啰嗦、很浪费
8. 没有 table 的话系统具体会坏在哪
9. report、checkpoint、resume 为什么都要经过这张表
10. 这套机制的边界和缺点是什么

本轮目标固定为：

1. 把 `Table-as-State` 的详细机制改写成面试口语版
2. 增加：
   - 3 分钟回答
   - 8 分钟深挖回答
   - 刁钻问题与标准回答
3. 问题组织方式从“我想讲什么”切到“面试官会怎么追”

本轮继续更新工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 新增 `Table-as-State` 口语版详细讲法
   - 新增一组高强度追问与标准回答

当前站位更新：

1. 面试手册开始从“能讲亮点”推进到“能讲机制、能抗细问”
2. 后续如果还要继续补，优先补更多真实追问场景，而不是再重复亮点总结

## 263. 面试手册 trade-off 强化：围绕简历亮点，补“为什么不用别的方法、各种方案优缺点、最后为什么这样选”（2026-07-07）
这一刀继续深化 `Research Agent` 面试手册，但重点从“题库覆盖”转到“设计取舍”。原因是技术面越往后，面试官越不满足于听亮点和机制，往往会继续问：

1. 你为什么不用普通 RAG
2. 你为什么不是纯 ReAct
3. 你为什么不用 ToT / Self-Refine / Reflexion 这类循环方式
4. 你为什么不用图结构或 JSON，而要用 table
5. 你为什么不用单 verifier
6. 你为什么不把 search / fetch / read 合成一个网页工具
7. 你为什么不做更重的多 agent 协作

本轮目标固定为：

1. 围绕简历亮点的每个关键词，补上可替代方案对比
2. 每个回答都尽量包含：
   - 可选方案
   - 各自优点
   - 各自缺点
   - 最后为什么选择当前方案
3. 调研来源分两类：
   - 本地参考项目：`Table-as-Search`、`DeepWideSearch`、`MiroFlow`
   - 外部主流方法：`ReAct`、`Plan-and-Solve`、`Tree of Thoughts`、`Self-Refine`、`Reflexion`、`GraphRAG`

本轮继续更新工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 新增 trade-off 口语回答
   - 新增“替代方案对比”问法
   - 新增“最后为什么这样选”的标准说法

当前站位更新：

1. 面试手册开始从“知道自己做了什么”推进到“知道为什么不做别的”
2. 后续如果继续深化，应优先补更真实的反驳题和架构取舍题

## 264. 文档顺序整理：合并重复内容，统一成“自己的方案 + 调研时对比的外部方法”口径（2026-07-07）
这一刀不新增新亮点，而是对现有 `Research Agent` 文档做一次结构整理。原因是当前面试手册已经覆盖了很多问题，但随着不断补充，后半部分开始出现：

1. 相似问题分散在不同章节
2. `Table-as-State`、`Dual Verifier`、`trade-off` 等内容多次重复
3. 先讲亮点、后讲追问、再讲取舍，复习时切换成本偏高
4. 一些地方还在显式说“参考了某些项目/对象”，不利于统一成自己的设计口径

本轮整理目标固定为：

1. 把重复内容合并到统一章节
2. 调整面试手册顺序，让阅读顺序更贴近真实面试顺序：
   - 开场
   - 核心亮点
   - 机制深挖
   - trade-off
   - 简历逐条追问
   - 拷打题
3. 把“参考项目”措辞改成：
   - 自己采取的方式
   - 调研时对比过的外部方法
   - 为什么最后不那样选
4. 架构文档和面试手册口径继续统一，不再显式点名本地参考项目

本轮继续更新工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 结构整理
   - 合并重复
   - 统一 trade-off 口径
2. `docs/DeepResearch-ResearchAgent架构文档.md`
   - 改写“参考项目吸收”部分
   - 统一成外部方法对比口径

当前站位更新：

1. 文档开始从“不断加料”推进到“便于复习、便于面试、便于统一口径”
2. 后续如果继续补，优先保持结构稳定，不再重复拆散新增章节

## 265. 面试手册继续深化：以 `Table-as-State` 深挖为模板，补齐其他核心方法与思想的同级深挖（2026-07-07）
这一刀继续完善 `Research Agent` 面试手册，但不再扩散新主题，而是做结构对齐。原因是当前手册里：

1. `Table-as-State` 已经有相对完整的深挖层次
2. 其他核心点虽然已经有标准讲法和 trade-off
3. 但还没有达到和 `Table-as-State` 一样的“3 分钟版本 / 8 分钟版本 / 高频追问 / 刁钻问题”完整度

这会导致一个问题：面试官如果顺着 `Research Harness`、`Closed-Loop Research`、`Dual Verifier`、`反证分支` 往下追，当前文档虽然能答，但深度层次还不够统一。

本轮目标固定为：

1. 以 `Table-as-State` 深挖那一节为模板
2. 对以下核心方法补齐同级深挖：
   - `Research Harness`
   - `Closed-Loop Research`
   - `Dual Verifier`
   - `反证分支`
   - `Search / Fetch / Read`
   - `Research Report / Save as Source`
3. 每一块尽量包含：
   - 3 分钟回答
   - 更深一层的展开
   - 高频追问
   - 为什么这么选

本轮继续更新工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 新增多个“深挖”子章节
   - 让五个关键词和关键交付链的深度更一致

当前站位更新：

1. 面试手册开始从“有一个重点深挖点”推进到“核心主线都能同级深挖”
2. 后续如果继续补，优先做口语压缩版，而不是再无限扩新问题

## 266. 面试手册继续扩到“项目提问的深度与宽度”：不仅补系统机制深挖，还整理面试官围绕项目会如何往深了追、往宽了扩（2026-07-07）

这一轮继续完善 `Research Agent` 文档，但重点从“机制本身怎么深挖”再推进一步到“围绕项目会被怎么问”。原因是当前手册虽然已经能把 `Research Harness`、`Closed-Loop Research`、`Table-as-State`、`Dual Verifier`、`反证分支` 讲清楚，但还缺一层更贴近真实面试现场的组织方式：

1. 面试官不一定按系统模块顺序追问
2. 更常见的是沿着一条主线连续深挖，或者跨产品、架构、成本、展示、交付做横向扩问
3. 所以除了“机制解释”，还需要补“问题链视角”和“项目广度视角”

本轮目标固定为：

1. 在面试手册里新增“项目提问的深度与宽度”主线
2. 把“深度”定义成：
   - 为什么这样设计
   - 内部怎么运转
   - 失败时怎么恢复
   - 取舍为什么成立
3. 把“宽度”定义成：
   - 产品边界
   - 工具栈
   - 状态设计
   - 验证闭环
   - 成本与定价
   - 展示与交付
4. 同时把架构文档补上“研究深度 / 研究宽度”双轴设计解释

本轮继续更新工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 新增项目提问深问链与宽问面
   - 强化真实面试追问路径组织
2. `docs/DeepResearch-ResearchAgent架构文档.md`
   - 新增研究深度 / 研究宽度双轴说明

当前站位更新：

1. 文档开始从“能解释系统”推进到“能应对围绕项目的深问和宽问”
2. 后续如果继续补，优先补高频追问链和压缩口语版，而不是再散着加概念

## 267. 面试手册继续重构：不再把“项目提问的深度与宽度”孤立成单独章节，而是融入每个亮点的问答链里（2026-07-07）

这一轮继续完善 `Research Agent` 面试手册，重点不是再增加一个“深度 / 宽度”说明章节，而是把这种面试思路渗透回具体问题与回答。原因是当前手册虽然已经补出了“深问链”和“宽问面”的意识，但形式上还是有些“元说明”味道，离真实面试使用习惯还差一步：

1. 面试时不会先问“你的深问链是什么”
2. 面试官会直接围绕某个亮点、某个设计、某个 trade-off 连续追问
3. 所以手册更应该让每个模块都天然具备“可深问、可宽问、可追问到落地”的回答结构

本轮目标固定为：

1. 压缩或弱化独立的“深度 / 宽度”说明段
2. 把“深问”和“宽问”的思想拆进：
   - 简历亮点标准讲法
   - 关键机制深挖
   - 简历逐条追问题库
   - 压力面问答
3. 为每个核心亮点补更多真实追问
4. 让回答不仅有结论，还包含：
   - 内部机制
   - 失败恢复
   - trade-off 思考
   - 产品边界
   - 落地证据

本轮继续更新工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 重写相关章节结构
   - 增加更多追问与更深层回答

当前站位更新：

1. 手册开始从“告诉你面试官会怎么问”推进到“直接把回答写成能承受这种追问的形式”
2. 后续如果继续补，优先补更刁钻的连环追问，而不是再抽象定义“深度”和“宽度”

## 268. 面试手册继续补硬能力：把工程能力、运行契约、交付闭环、控费和落地证据全部补成可讲、可追问的内容（2026-07-07）

这一轮继续完善 `Research Agent` 面试手册，重点不再是主架构理念，而是把目前架构文档里已经出现、但面试手册里还没有真正展开讲清的硬能力全部补齐。原因是当前手册已经能较好地回答：

1. 为什么不是普通 RAG
2. 为什么需要 Harness / Table-as-State / Dual Verifier / 反证分支
3. 为什么最后交付的是报告

但还存在一批更工程化、更能体现“这不是概念稿”的关键点，提过却没真正写成可用回答：

1. `LoopDecision` 与 `WRITE_WITH_GUARDRAILS`
2. `Extract / Verify`
3. `Research Run / checkpoint / resume / save-report-as-source`
4. `report_structure / provenance / artifact`
5. 成本模型、档位、控费逻辑
6. 当前落地证据与怎么证明它真能跑

本轮目标固定为：

1. 把以上六类能力补进面试手册
2. 让手册不仅能讲“理念”，还能讲：
   - 工程能力
   - 运行契约
   - 交付闭环
   - 成本控制
   - 落地证据
3. 每块尽量继续保持：
   - 三分钟讲法
   - 深一层讲法
   - 高频追问
   - trade-off 或落地证明

本轮继续更新工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 新增多组硬能力问答
   - 强化 run system / artifact / pricing / landed evidence 讲法

当前站位更新：

1. 手册开始从“能讲主架构”推进到“能讲完整工程交付系统”
2. 后续如果继续补，优先补更细的落地实现与证据映射，而不是重复扩写概念定义

## 269. 继续吸收外部关于 Research Agent 的高频讨论：把安全、记忆评测、复现性、可靠性等外部问题继续转成针对当前项目的面试题（2026-07-08）

这一轮继续完善 `Research Agent` 面试手册，但重点从“内部架构与工程交付怎么讲”进一步扩展到“外部社区和论文最常讨论什么”。原因是当前手册虽然已经把：

1. 主架构
2. 工程能力
3. 运行契约
4. 交付闭环
5. 成本控制
6. 落地证据

补得比较完整，但如果目标是让手册更接近真实研究型 Agent 面试，仍然需要继续吸收外部常见视角，包括：

1. agent safety / prompt injection / tool misuse
2. multi-session memory / memory policy / stale memory
3. live web research 的可复现性
4. evaluation taxonomy / process metrics / cost-efficiency
5. reliability / calibration / uncertainty expression

本轮目标固定为：

1. 再搜索一轮外部关于 `Research Agent` 的高频知识点和争议点
2. 只保留最值得转译成面试题的部分
3. 继续把这些内容翻译成“针对当前项目”的问答，而不是泛泛讨论外部方法

本轮继续更新工件：

1. `docs/DeepResearch-ResearchAgent面试回答手册.md`
   - 新增外部高频问题转译题
   - 重点强化安全、记忆、复现性、可靠性相关问答

当前站位更新：

1. 手册开始从“讲清自己的设计”推进到“能接住外部研究与社区语境里的常见追问”
2. 后续如果继续补，优先补外部更刁钻但能落回本项目的真实追问，而不是重复内部概念
