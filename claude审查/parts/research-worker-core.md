## Research Worker · 编排与执行核心

### loop_runtime.py

### [高] 恢复(resume)路径重新触发网络抓取，无预算/取消保护
- 位置：`workers/research-worker/app/loop_runtime.py:901-902`
- 问题：`_restore_resume_context` 在 checkpoint 缺少 `fetched_documents` 且没有 `read_windows` 时，直接调用 `run_research_fetch(task_input, restored_plan, search_hits)`。这是一次真实的网络抓取（可能对每个 search hit 发起外部请求），发生在循环开始、墙钟预算检查(`_wall_clock_budget_exhausted`)之前，且不受 `cancellation_checker` 保护。
- 证据：`if not fetched_documents and search_hits: fetched_documents = run_research_fetch(...)`；该调用在 `run_research_loop` 主循环的墙钟/取消检查逻辑之外执行。
- 影响：恢复一个旧 checkpoint 可能在预算判定之前发起大量外部抓取，绕过 `max_wall_clock_seconds` 与取消信号；也可能触发 SSRF/配额消耗。
- 建议：恢复期的抓取应纳入墙钟预算与取消检查，或仅从 checkpoint 快照重建 document，不发起新网络请求。

### [中] 冲突证据在分支预算耗尽时被静默吞掉，不触发 handoff
- 位置：`workers/research-worker/app/loop_runtime.py:168-176, 427-436`
- 问题：传入 `evaluate_loop_decision` 的 `has_conflict` 被定义为「存在 CONFLICTS 卡片 且 存在 ACTIVE 的 COUNTERFACTUAL_RECHECK 分支」。当 `branch.py` 因分支预算耗尽返回 `NO_BRANCH`(MAINLINE) 时，即便存在冲突卡片，`has_conflict=False`，循环决策既不会进入 COUNTERFACTUAL_RECHECK，也不会在结束时设置 `handoff_required`。
- 证据：`has_conflict = has_conflict_cards and any(decision.decision == "COUNTERFACTUAL_RECHECK" and decision.branch_status in {"ACTIVE","ACTIVE_BRANCH"} ...)`。
- 影响：未解决的冲突可能直接走向 `SYNTHESIZE_REPORT`（`STOP_CONTRACT_SATISFIED`），既不 guarded 也不 handoff，违背 stop_contract 中 `COUNTERFACTUAL_CONFLICT_UNRESOLVED_AFTER_BUDGET` 的人工交接意图。
- 建议：在 `has_conflict_cards=True` 但分支预算耗尽时，仍应走 `WRITE_WITH_GUARDRAILS` + `handoff_required`，而非把冲突信号丢弃。

### [中] `_record_wall_clock_consumption` 就地修改共享 plan 的 stop_contract 字典
- 位置：`workers/research-worker/app/loop_runtime.py:318-329`
- 问题：该函数用 `plan.stop_contract["wall_clock_seconds_consumed"] = ...` 就地写入 Pydantic 模型内部的 dict，而不是 `model_copy`。传入的 `plan`（首轮为 `active_plan`，其初值即调用方 `runner.py` 传入的 `plan`）被直接修改，产生跨对象副作用。
- 证据：`plan.stop_contract["wall_clock_seconds_consumed"] = round(...)`；而其余处均通过 `model_copy(update=...)` 更新 plan。
- 影响：调用方持有的 plan 状态被隐式改写；若未来 plan 被复用或缓存，墙钟计数会污染其它读取者，破坏「不可变快照」约定。
- 建议：改为通过 `_augment_plan_for_next_round` 一致地用 `model_copy` 更新，或明确将 stop_contract 视为可变运行态并集中管理。

### [中] 墙钟耗尽的两处判定/记录顺序导致「最后一轮结果」可能被丢弃
- 位置：`workers/research-worker/app/loop_runtime.py:133-155`
- 问题：主循环开头即检查墙钟预算，若耗尽且 `latest_artifacts is not None` 就 `break`——不会执行本轮 `execute_round`。但该检查基于「进入循环时」的耗时，若上一轮执行使耗时刚好越界，则本轮直接放弃，`latest_decision` 用 `_wall_clock_exhausted_decision`。这与 `deadline_policy=FINISH_CURRENT_ROUND_THEN_GUARDED_OUTPUT`（完成当前轮再输出）存在语义偏差：实际是「跳过下一轮」，但被标为已完成轮的 guarded 输出。
- 证据：`deadline_policy` 在 planner.py:276 声明为 FINISH_CURRENT_ROUND；loop 中却在轮首 break。
- 影响：边界情况下丢弃一整轮的检索/抽取工作，或在语义上与文档承诺的截止策略不一致。
- 建议：明确「完成当前轮」的定义，并保证进入某轮后至少完成该轮再判定截止。

### [低] `evaluate_premature_commitment_guard` 每轮 + 收尾重复计算
- 位置：`workers/research-worker/app/loop_runtime.py:391`（每轮）与 `runner.py:354`（收尾）
- 问题：premature commitment guard 在 `evaluate_loop_decision` 内每轮计算一次，`run_research_task` 又对最终 ledger 再算一次；两次逻辑相同但入参 ledger 可能不同（收尾用合并后的 ledger）。
- 影响：轻微重复计算；更重要的是两处结果口径可能不一致，导致 result_payload 中的 guard 与循环内决策依据不同源。
- 建议：由循环把最终 guard 结果透传给收尾层，避免重复与口径漂移。

### [低] `_augment_plan_for_next_round` 无界扩张 query_set / notes
- 位置：`workers/research-worker/app/loop_runtime.py:603-621, 733-758`
- 问题：每轮把新查询、requirement 目标查询、多条 notes 追加进 `query_set`/`notes`，`replan_history` 也逐轮增长。虽被 `max_loop_rounds` 间接限制，但 DEEP 档 6 轮下 query_set 可累积数十条，全部进入 checkpoint 与 result_payload 序列化。
- 影响：payload 膨胀、后续检索规划面临越来越大的查询集；无显式去重上限（仅去重不截断）。
- 建议：为 query_set/notes 设定每轮增量上限或总量截断。

### [优化] `_wall_clock_exhausted_decision` 与 LOOP_BUDGET 分支代码高度重复
- 位置：`workers/research-worker/app/loop_runtime.py:332-364` 与 `407-425`
- 问题：两处都在构造 terminal_disposition（HUMAN_HANDOFF / ABANDON / GUARDED_COMPLETE）+ handoff/abandon reason，逻辑近乎一致。
- 建议：抽取公共 helper 统一构造终态决策，减少分叉维护成本。

### runner.py

### [中] 单个研究任务在同步 FastAPI 端点内全程阻塞
- 位置：`workers/research-worker/app/main.py:32-34` → `runner.run_research_task`
- 问题：`/tasks/{task_id}/run` 与 `/debug/run-task` 均为同步端点，内部执行完整研究循环（DEEP 档墙钟预算达 480s）。同步端点会长时间占用 uvicorn 工作线程。
- 影响：并发多个任务时线程池耗尽、请求排队；长任务无法及时响应取消。
- 建议：改为异步/后台任务队列（Kafka 消费路径已具备），或至少将 CPU/IO 密集执行放入线程池并支持超时。

### [中] `run_research_task` 函数过长、职责过载（约 460 行）
- 位置：`workers/research-worker/app/runner.py:303-767`
- 问题：单函数完成 plan、loop、report、citation、refiner、checkpoint、control_state、result_payload 组装等十余项职责，局部变量极多（如 `harness_control_state_state`）。
- 影响：可读性/可测试性差，任何一处改动都需理解全局；异常发生时难定位。
- 建议：拆分为 build_artifacts / build_report / build_checkpoint / build_result 等子函数。

### [低] 命名混乱：`harness_control_state_state`
- 位置：`workers/research-worker/app/runner.py:560, 572, 586-593`
- 问题：变量名 `harness_control_state_state`（双 state）与 `harness_control_state`（dump 后的 dict）并存，极易混淆。
- 建议：重命名为 `control_state`（模型）与 `control_state_payload`（dict）。

### [低] `cancellation_checker` 类型标注与实际语义矛盾
- 位置：`workers/research-worker/app/runner.py:307` 与 `loop_runtime.py:61`
- 问题：签名标注为 `Callable[[], None]`，但实际以「抛异常表示取消」的方式使用（`if cancellation_checker is not None: cancellation_checker()`）。而 `local_parallel_scheduler.py` 用的是布尔返回的取消契约。两套取消语义（异常式 vs 布尔式）并存且未在类型上区分。
- 影响：调用方易误用；混淆异常式与布尔式取消，可能导致取消信号失效。
- 建议：统一取消契约或在类型/文档中显式区分两种 checker。

### [低] LLM 用量兜底 `getattr(llm_client, "calls", [])` 依赖鸭子属性
- 位置：`workers/research-worker/app/runner.py:608-616`
- 问题：当 `usage_summary` 不可调用时，回退读取 `llm_client.calls`，属于对具体实现的隐式耦合；若 client 无该属性则恒为 0。
- 建议：在 `LlmClient` 协议上正式声明 usage 接口，去除 getattr 猜测。

### main.py

### [低] debug 端点无鉴权、可触发外部检索
- 位置：`workers/research-worker/app/main.py:23-29`
- 问题：`/debug/run-task` 接收任意 `ResearchTaskInput` 并执行完整研究（含外部搜索/抓取），无任何鉴权或开关。
- 影响：若该服务暴露到内网外，可被用于代理外部请求（SSRF）或消耗 LLM/检索配额。
- 建议：以配置开关控制 debug 路由启用，并要求内部鉴权 token。

### execution_control.py

### [高] LeaseKeeper 用 JSON 字符串相等判定租约有效，序列化差异会误判 STALE_LEASE
- 位置：`workers/research-worker/app/execution_control.py:197-199`
- 问题：`tick` 通过 `if updated != self._claim: self._control.stop("STALE_LEASE")` 判断租约是否被抢占。`AgentTaskClaim` 是 frozen dataclass，含 `target_cells_json` / `budget_json` / `task_snapshot_json` 三个原始 JSON 字符串字段。心跳成功后后端只要以不同键序、空白或数值格式重新序列化这些字段，`updated != self._claim` 即为 True，导致把一次正常续租误判为 STALE_LEASE 而永久停机。
- 证据：`agent_task_client.py:103-110` 用后端返回的 payload 重建 claim；相等性依赖字符串逐字节一致。
- 影响：正常续租被误杀，执行被无端 stop，任务失败率上升且难以定位（表现为随机 STALE_LEASE）。
- 建议：仅比较 `agent_task_id/lease_epoch/fencing_token`（租约身份三元组），而非整个含 JSON 文本的对象。

### [中] `stop_current_execution` 无 control 绑定时直接抛异常，语义不一致
- 位置：`workers/research-worker/app/execution_control.py:96-102`
- 问题：`require_execution_active` 在无 control 时是 no-op（兼容 legacy），但同模块的 `stop_current_execution` 在无 control 时却直接 `raise ExecutionStopped`。两个「当前执行控制」入口对「未绑定」的处理相反。
- 影响：远程 stop 信号在 legacy/未绑定上下文中会以异常形式冒泡，可能击穿未预期的调用栈。
- 建议：统一未绑定时的行为（要么都 no-op，要么都抛），或在文档中显式说明差异及各自使用边界。

### [中] LeaseKeeper `_tick_safely` 吞掉所有异常仅记 HEARTBEAT_FAILED
- 位置：`workers/research-worker/app/execution_control.py:185-190`
- 问题：`_tick_safely` 用 `except Exception` 捕获一切，统一 `stop("HEARTBEAT_FAILED")` 并返回 False，不记录原始异常类型/堆栈。
- 影响：心跳线程内的编程错误（如 AttributeError）会被伪装成租约失败，掩盖真实缺陷，难以排障。
- 建议：至少记录原始异常（logging.exception），或对可预期的网络异常与非预期异常分别处理。

### [低] `HeartbeatClient` Protocol 与实际实现存在耦合假设
- 位置：`workers/research-worker/app/execution_control.py:24-26, 192-215`
- 问题：`tick` 依赖 heartbeat 返回值与传入 claim 的相等语义（见上条高危），但 Protocol 未对「续租应返回等价 claim」作任何约定说明。
- 建议：在 Protocol docstring 中明确契约：成功续租须返回与身份三元组一致的 claim。

### [优化] 幻数散落：lease_seconds<4、interval<lease/3、budget=interval*2
- 位置：`workers/research-worker/app/execution_control.py:124-130`
- 问题：多个租约时序约束以裸数字硬编码，缺少常量命名与出处说明。
- 建议：提取为具名常量并注释其时序推导依据。

### local_parallel_scheduler.py

### [中] `cancellation_checker()` 若抛异常会逃逸出 `_run_one`，绕过失败封装
- 位置：`workers/research-worker/app/local_parallel_scheduler.py:132-138, 154-155`
- 问题：`_is_cancellation_requested` 在 `_run_one` 的 try 之外先行调用 `checker()`；`_run_one` 只对 `execute_bundle` 做了 `except Exception` 封装。若 checker 违反布尔契约抛异常，异常会从线程池 future 冒泡到 `future.result()`（line 112），导致整个调度崩溃而非产生 CANCELLED 结果。
- 证据：`if _is_cancellation_requested(cancellation_checker): return _cancelled_result(bundle)` 在 `tracker.start()` 之前、try 之外。
- 影响：不守约的 checker 会使并行波次整体失败，且 in-flight 计数不平衡。
- 建议：把 checker 调用纳入 try/except，或在契约上强制 checker 不得抛异常并加以校验。

### [低] `futures` 使用 `dict[object, int]`，键类型过宽
- 位置：`workers/research-worker/app/local_parallel_scheduler.py:94, 110-112`
- 问题：`futures: dict[object, int]` 把 Future 当作 object 键，丢失类型信息。
- 建议：标注为 `dict[Future[AgentExecutionResult], int]`。

### [低] 单并发路径与多并发路径重复构造 summary
- 位置：`workers/research-worker/app/local_parallel_scheduler.py:84-92, 114-122`
- 问题：`effective==1` 与并行分支各自完整构造一份 `LocalParallelExecutionSummary`（含相同的 failure/cancelled 计数聚合），逻辑重复。
- 建议：合并出口，统一在末尾构造 summary。

### merge_gate.py

### [中] 幂等重放判定先于陈旧性判定，陈旧重放会被当作成功幂等
- 位置：`workers/research-worker/app/merge_gate.py:65-66`
- 问题：`if cell.last_merge_id == merge_id: return IDEMPOTENT_REPLAY` 位于所有 plan_revision/entity_set_version/fencing/lease/version 校验之前。若一个陈旧 merge_id 恰好等于 cell 上次的 merge_id（跨 revision 复用 execution_id+candidate_id 组合），会在版本已推进的情况下仍返回 IDEMPOTENT_REPLAY 而非 STALE。
- 证据：merge_id 由 `execution_id:candidate_id` 构成（research_tools.py:942），二者均由 task/lease/version 派生，正常不冲突，但幂等短路绕过了权威校验。
- 影响：极端情况下把过期结果误判为已成功幂等，掩盖版本冲突。
- 建议：先做 authority/version 校验，再判定幂等重放；或将 revision/version 纳入幂等键。

### [低] `_validated_evidence_ids` 空证据返回 None 与「校验失败」共用同一出口
- 位置：`workers/research-worker/app/merge_gate.py:123-137`
- 问题：函数在「有非法证据」与「证据为空(accepted 为空)」两种情况都返回 `None`，上层统一映射为 `EVIDENCE_BINDING_INVALID`。两种根因被合并，难以区分「未提供证据」与「证据绑定错误」。
- 建议：为两种情况区分 reason code，便于诊断与恢复策略选择。

### sequential_candidate_executor.py

### [中] 候选 `source_diversity` 恒为 0，削弱下游多源判定
- 位置：`workers/research-worker/app/sequential_candidate_executor.py:48`
- 问题：从 ledger 生成候选时 `source_diversity=0` 硬编码。设计文档（受控多Agent方案）强调多源三角验证/来源多样性是合并与置信的重要依据，而此处始终为 0。
- 影响：任何依赖 candidate.source_diversity 的下游逻辑都得不到真实来源多样性，可能低估证据强度或错误触发扩展。
- 建议：由 evidence_refs 对应的 evidence_cards 计算 distinct source_id 数量填入。

### [低] `execute_sequential_bundle` 静默跳过不匹配 cell，无审计
- 位置：`workers/research-worker/app/sequential_candidate_executor.py:24-32`
- 问题：当 cell 的 entity/column/version 不匹配、或无候选值/无证据时用 `continue` 静默跳过，最终仅以 `PARTIAL_CANDIDATE_PROPOSAL`/`NO_CANDIDATE_VALUE_OR_EVIDENCE` 汇总，丢失了「因何跳过」的粒度信息。
- 建议：记录每个 target 的跳过原因用于审计与调试。

### cell_task_planner.py

### [低] `max_cells_per_bundle` 被静默夹到 [1,3]，配置>3 无提示
- 位置：`workers/research-worker/app/cell_task_planner.py:26`
- 问题：`bundle_size = max(1, min(3, max_cells_per_bundle))`，与设计文档「1–3 个 cell」一致，但当运行配置 `research_agent_bundle_max_cells` 设为 >3 时被静默截断，无告警。
- 建议：对越界配置记录一次告警，避免运维误以为生效。

### [低] bundle 分组键不含 `entity_set_version`，与文档指纹口径不完全一致
- 位置：`workers/research-worker/app/cell_task_planner.py:33-36, 52-60`
- 问题：设计文档（多Agent实施手册 §、受控方案）要求 bundle 的稳定指纹含 `research_run_id/entity_id/branch_id/plan_revision/entity_set_version`。`_stable_key` 已含 `entity_set_version`（line 56），但 `grouped` 的分组键仅用 `(entity_id, branch_id)`，未把 entity_set_version 纳入分组维度（同一次规划内一致，风险低）。
- 影响：单次规划内 entity_set_version 恒定，实际无碰撞；但分组维度与指纹维度不对齐，属实现口径与文档描述的细微偏差。
- 建议：注释说明分组内 entity_set_version 恒定，或将其显式纳入分组键以自证一致。

### deep_cell_executor.py

### [高] `_build_task_scope` 用 `cell_id.partition(":")` 解析列名，列名含冒号即错位
- 位置：`workers/research-worker/app/deep_cell_executor.py:193-198`
- 问题：`entity, separator, column = target.cell_id.partition(":")` 假设 cell_id 恰为 `entity:column` 两段。但 entity_id 形如 `entity-<hex>`（entity_identity.py 无冒号，尚安全），而 column 由 schema key 派生——若未来任何 entity_id 或列 key 含冒号，`partition` 只在首个冒号切分会导致 entity/column 解析错误并抛「outside entity scope」。
- 证据：`entity, separator, column = target.cell_id.partition(":")`，随后 `if not separator or entity != snapshot.entity_id`。
- 影响：cell_id 命名一旦引入冒号即整批 DEEP_CELL 任务失败；契约脆弱。
- 建议：不要从字符串反解 cell_id，改为在 snapshot.target_cells 上携带显式 entity_id/column_key 字段。

### [中] `allow_external` 要求 search 与 fetch 双开，但 read 阶段沿用同一标志
- 位置：`workers/research-worker/app/deep_cell_executor.py:81-83, 214-217`
- 问题：`allow_external = allow_external_search AND allow_external_fetch`；随后 read 阶段也用同一 `allow_external` 选择外部/工作区适配器。策略里没有独立的 read 外部许可，read 的外部性被 search/fetch 许可绑架。
- 影响：无法表达「允许外部抓取但只在工作区内读」等组合；越权或过度限制二者皆可能。
- 建议：为 read 引入独立许可位，或在文档中明确 read 复用 fetch 许可的理由。

### [中] `_build_execution_llm` 每次执行都 `load_settings()` 并新建 LLM 客户端
- 位置：`workers/research-worker/app/deep_cell_executor.py:112, 220-232`
- 问题：每个 DEEP_CELL 命令执行都 `load_settings()` 且 new 一个 `OpenAICompatibleLlmClient`，无复用/连接池；高并发下重复初始化开销与配置读取。
- 建议：将 settings 与 client 构造上移到 executor 初始化或注入，按 worker 复用。

### [低] `getattr(card, ...)`/`getattr(window, ...)` 大量弱类型访问
- 位置：`workers/research-worker/app/deep_cell_executor.py:239-341`
- 问题：`_workspace_evidence_payload`/`_candidate_payload`/`_scoped_evidence_key` 全程用 `getattr(card, "xxx", "")` 访问字段，丢失类型检查，字段改名不会被静态发现。
- 建议：以明确的模型类型标注 cards/windows，去除 getattr 兜底。

### [低] `__call__` 与 `_build_task_scope` 之间缺少空行（PEP8）且函数偏长
- 位置：`workers/research-worker/app/deep_cell_executor.py:185-186`
- 问题：`__call__` 方法体末尾 `return build_completion_envelope({...})` 后紧接 `def _build_task_scope`，无空行分隔；`__call__` 约 85 行，串行执行 search/fetch/read/extract 与 payload 组装职责集中。
- 建议：补空行；将 trace/budget/telemetry 组装抽为 helper。

### [优化] search→fetch→read→extract 四段 `require_active()`+`toolbox.require`+`permit` 样板重复
- 位置：`workers/research-worker/app/deep_cell_executor.py:114-141`
- 问题：四个工具阶段结构完全一致（require_active → toolbox.require → permit → require_active → 调用 → usage.add），大量重复样板。
- 建议：用一个按阶段配置驱动的循环或 helper 收敛。

### role_executor.py

### [低] 角色→工具映射为硬编码常量，与文档角色集需人工对齐
- 位置：`workers/research-worker/app/role_executor.py:47-53`
- 问题：`_ROLE_TOOLS` 硬编码五种角色的工具集。SYNTHESIS 允许工具集为空 `frozenset()`，若 SYNTHESIS 角色实际需要 read（审计既往证据）则会 PermissionError。需与设计文档角色权限表核对。
- 建议：将角色-工具矩阵集中到配置/契约层，并加测试断言其与文档一致。

### [低] `create` 不校验 execution_id 唯一性，仅校验非空
- 位置：`workers/research-worker/app/role_executor.py:59-60`
- 问题：仅 `if not execution_id.strip(): raise`，不保证同一 worker 内 execution_id 不重复。
- 建议：若上层依赖 execution_id 唯一，应在此或调用点保证。

### branch.py

### [中] 冲突分组键 fallback 到 `claim_text`，长文本作键易碎且分组不稳定
- 位置：`workers/research-worker/app/branch.py:79-84, 102-105`
- 问题：`conflict_groups`/`target_keys` 以 `(entity_id or source_id, column_key or claim_text)` 为键。当 column_key 缺失时用整段 `claim_text` 作键，claim_text 通常为自由文本，任何细微差异都会被分到不同组，导致同一冲突被拆散或无法匹配到 prior。
- 影响：counterfactual 分组与「独立结果」匹配（line 108-116）不稳定，可能漏判独立证据、反复开分支或错误标记已解决。
- 建议：以稳定标识（entity_id+column_key，缺失时用 evidence_id 或规范化后的短哈希）作键，避免用长文本。

### [中] `plan_branch_recovery` 顺序短路：无 hits/windows/cards 时立即返回，忽略并存冲突
- 位置：`workers/research-worker/app/branch.py:24-64`
- 问题：函数按 search_hits → read_windows → evidence_cards 依次早退。只要前者缺失就返回单条恢复决策，即使此时 evidence_cards 中已有 CONFLICTS 也不会进入 counterfactual 分支逻辑。
- 影响：在部分恢复/异常数据下，冲突处理被前置的「缺失恢复」掩盖。
- 建议：先扫描是否存在冲突卡片再决定恢复优先级，或在文档中明确这些早退是刻意的阶段门。

### [低] 深层嵌套列表推导，可读性差、难以维护
- 位置：`workers/research-worker/app/branch.py:175-198`
- 问题：返回值使用双重嵌套 for（`for index, cards in enumerate(...) for card in cards[:1]`）+ 内部条件构造 `ResearchBranchDecision`，逻辑密度极高。
- 建议：改为显式 for 循环，提升可读性并便于加日志。

### [低] `session_id` 由 `branch_id.replace("branch-","session-",1)` 反推
- 位置：`workers/research-worker/app/branch.py:178`
- 问题：session_id 通过字符串替换从 branch_id 派生，隐式约定 branch_id 必以 `branch-` 开头。若命名规则改变则 session_id 生成错误且无校验。
- 建议：显式生成/传入 session_id，避免字符串耦合。

### [优化] 前三个早退分支构造 `ResearchBranchDecision` 结构高度重复
- 位置：`workers/research-worker/app/branch.py:24-64`
- 问题：NO_SEARCH_HITS / NO_READ_WINDOWS / NO_EVIDENCE_CARDS 三段除文案外结构一致。
- 建议：用数据表 + 单一构造器收敛。

### state.py

### [高] `_aggregate_row_status` 用抽取阶段 support_score 直接判 VERIFIED，先于 CellVerifier
- 位置：`workers/research-worker/app/state.py:754-769, 286-293`
- 问题：build_state_ledger 阶段（CellVerifier 尚未运行、所有 verdict=NOT_ENOUGH_INFO）就用 `avg_support >= 0.7` 把 row 判为 `VERIFIED`，并据此计算 `verified_row_count` 与 `coverage_score`。设计（文档 §6.2/premature-commitment）要求 SUPPORTS 应由 CellVerifier 4-way 判定产出。
- 证据：line 239 注释「等 CellVerifier 调用」，但 line 765 `if avg_support >= 0.7: return "VERIFIED"` 已提前给出 VERIFIED；`verified_row_count = row_status_summary.get("VERIFIED", 0)`。
- 影响：单张高分卡片（甚至单源）即可让实体过早 VERIFIED，抬高 coverage_score、误导 premature_commitment_guard 与循环停机判定，可能导致过早合成报告。
- 建议：build 阶段 row_status 用中性态（如 CANDIDATE_READY/NEED_MORE_EVIDENCE），VERIFIED 仅由 verifier/merge 后写回。

### [中] `finalize_state_ledger` 的 generic_recovery 对全部非终态 cell 无差别 +repair_count
- 位置：`workers/research-worker/app/state.py:653-695`
- 问题：只要存在任一 EXPAND_SOURCE_SCOPE/REOPEN/REEXTRACT 分支，`generic_recovery=True`，则每轮把所有非 VERIFIED/FROZEN/CONFLICTED 的 cell 全部标 NEED_MORE_EVIDENCE 且 `repair_count+1`——不区分该 cell 是否真的是恢复目标。配合 loop_runtime `_freeze_overspent_cells`（QUICK 档 max_retry=1），一轮通用恢复即可冻结全部未完成 cell。
- 影响：与「targeted 更新，不动无关 cell」的 P0-2 设计意图相悖；无关 cell 被计入 retry 预算并被过早 FROZEN，永久放弃。
- 建议：repair_count 只对真正被恢复目标命中的 cell 递增；通用恢复不应无差别消耗全场 retry 预算。

### [中] EntityResolver 直接就地修改共享实体对象（非不可变）
- 位置：`workers/research-worker/app/state.py:79-83, 106-112, 128-134`
- 问题：`_upsert_from_hit`/`_upsert_from_card`/`_enrich_from_window` 直接对已存入 `self._entities` 的 Pydantic 模型赋值（`entity.source_ids = ...`、`entity.source_quality = ...`）。Pydantic v2 默认允许属性赋值但这是就地变异，与工程中普遍的 model_copy 不一致。
- 影响：若同一 entity 对象被别处引用，会被意外改写；也使 resolver 非幂等/难以并行。
- 建议：统一用 model_copy(update=...) 或明确 resolver 独占所有权。

### [中] `_upsert_from_hit` 与 `_upsert_from_card` 用不同 canonical id 口径，可能产生实体重复
- 位置：`workers/research-worker/app/state.py:73-78, 99-105` + `entity_identity.py:21-25`
- 问题：hit 走 `canonical_entity_id(source_title, source_id, url)`（无 entity_hint），card 走 `card.entity_id or canonical_entity_id(entity_hint=card.entity_name, ...)`。`canonical_entity_id` 优先用 entity_hint 再 source_title。同一实体在 hit 阶段（用 title）与 card 阶段（用 entity_name）可能得到不同 id，导致同一实体分裂为两行。
- 影响：实体去重不稳定，行数虚增，cell 绑定分散。
- 建议：统一实体解析口径；build_state_ledger 已优先 cards（line 162），但缺失 title 或 hint 时仍有分裂风险，需测试覆盖。

### [中] `search_query`/`read_focus` 用 `next(... window.source_id == entity.source_id)` 线性扫描，O(rows×windows)
- 位置：`workers/research-worker/app/state.py:200-208`
- 问题：为每个 entity 行分别对 read_windows 做三次 `next(...)` 线性查找（query/read_focus/read_strategy 各一次）。行数×窗口数×3 的重复扫描。
- 影响：大规模检索下 O(N×M) 性能退化。
- 建议：预先按 source_id 建 window 索引字典，一次 O(1) 取用。

### [中] `_apply_requirement_bindings` 对每个 contract × 每行 × updated_cells 全量重建，O(C×R×Cells)
- 位置：`workers/research-worker/app/state.py:445-463`
- 问题：内层对每个匹配 row 都遍历 `updated_cells` 全表重建一份 `next_cells`（line 446-463），外层再套 contract 与 row 两层循环。复杂度约 O(contracts × rows × total_cells)。
- 影响：实体/列/需求数增大时呈立方级增长。
- 建议：按 (row_id) 预索引 cells，只对目标 row 的 cell 子集做 model_copy。

### [中] `coverage_score` 分母用 min_sources，但分子是 verified_row_count，量纲不一致
- 位置：`workers/research-worker/app/state.py:290-293`
- 问题：`coverage_score = min(1.0, verified_row_count / min_sources)`。分子是「已验证实体行数」，分母是「最少来源数」，两者语义不同（行 vs 源）。当实体数远多于 min_sources 时，只要少数行 VERIFIED 即可达 1.0。
- 影响：覆盖度评分失真，可能掩盖大量未验证实体。
- 建议：明确 coverage 定义（按需求覆盖率或已验证/总行数），修正分母口径。

### [低] `_aggregate_cell_value` 冲突占位符写入 candidate_value
- 位置：`workers/research-worker/app/state.py:845-846`
- 问题：仅有冲突证据时返回 `f"[conflict-only] {entity.display_name}"` 作为 candidate_value，把占位串当作单元值持久化。
- 影响：下游若直接展示/引用 candidate_value 会把占位符当真实值；merge 时 SUPPORTS 判定也可能受污染。
- 建议：冲突态单独用字段标记，candidate_value 保持空并由 verdict/conflict note 表达。

### [低] `finalize_state_ledger` 同时维护 `active_branch_id` 与 `active_branch_ids`，一致性靠约定
- 位置：`workers/research-worker/app/state.py:606-620`
- 问题：`active_branch_id = active_branch_ids[0] if ... else "branch-main"`，两个字段并存且需手动保持同步；`active_branch_ids` 去重后取首个作单值，语义（哪个才是「当前」分支）不明确。
- 建议：以单一来源派生，或文档化二者关系。

### [低] `_branch_verifier_decisions` 用 `recovery_actions[:2]` join，隐式截断
- 位置：`workers/research-worker/app/state.py:924`
- 问题：`action="; ".join(branch_decision.recovery_actions[:2])` 静默丢弃第 3 条及以后的恢复动作。
- 建议：完整保留或显式说明只取前两条的理由。

### [低] `import re` 顶部导入但 `urlparse` 在函数内延迟导入，风格不一致
- 位置：`workers/research-worker/app/state.py:3, 880`
- 问题：`re` 顶层导入，而 `_derive_domain` 内 `from urllib.parse import urlparse` 局部导入，风格不统一。
- 建议：统一到模块顶部导入。

### [优化] `_row_verification_status`/`_row_support_level`/`_row_verifier_note`/`_row_repair_hint` 均基于 row_status 的平行 switch
- 位置：`workers/research-worker/app/state.py:772-814`
- 问题：四个函数针对同一 row_status 分别做 if 链映射，可合并为单一状态→属性表。
- 建议：用 dict 表驱动，减少分散的字符串常量。

### planner.py

### [中] `stop_contract["min_sources"]` 计算被 source_scope 长度压制，DEEP 档难达 min_sources_cap
- 位置：`workers/research-worker/app/planner.py:264`
- 问题：`min_sources = min(depth_config["min_sources_cap"], max(1, len(source_scope) or 1))`。若用户未提供 source_scope（纯外部检索），`len(source_scope)=0` → `max(1,1)=1`，于是 min_sources 恒为 1，即便 DEEP 档 cap=5。这使 DEEP 档的多源要求形同虚设。
- 证据：DEEP `min_sources_cap=5`（line 43），但无 scope 时 min_sources=1。
- 影响：premature_commitment_guard 的 MIN_SOURCE_COVERAGE 与 coverage_score 门槛被显著放宽，可能单源即通过。
- 建议：对外部检索场景，min_sources 应取 cap 与「期望外部源数」的组合，而非被 scope 长度封顶到 1。

### [低] `_resolve_depth_config` 的 default 分支与 STANDARD 完全重复
- 位置：`workers/research-worker/app/planner.py:71-88`
- 问题：`.get(depth, {...})` 的兜底 dict 与 STANDARD 配置逐字段重复。
- 建议：`return {...}.get(depth, config["STANDARD"])`，消除重复常量。

### [低] `_build_report_sections` 未知 research_type 兜底到 PRODUCT_COMPARISON
- 位置：`workers/research-worker/app/planner.py:394`
- 问题：未匹配类型时静默用 PRODUCT_COMPARISON 的章节结构，可能与实际研究类型不符。
- 建议：兜底时记录告警或提供中性通用章节模板。

### [低] `_detect_research_type` 关键词计分对多语种/子串误命中
- 位置：`workers/research-worker/app/planner.py:521-532`
- 问题：以子串 `kw.lower() in blob` 计分，短关键词（如 "code"、"vs"、"review"）易在无关文本中误命中；分数相同取 `max` 时结果依赖 dict 顺序，非确定优先级。
- 影响：research_type 误分类，直接影响 schema 列集与报告结构。
- 建议：用词边界匹配、加权，并对同分定义确定的 tie-break 顺序。

### [优化] 幻数散落于 depth_config（各类预算数字）
- 位置：`workers/research-worker/app/planner.py:12-88`
- 问题：搜索/读取/循环/墙钟等大量预算裸数字集中硬编码，无常量或来源注释（部分有 P0-6 注释）。
- 建议：抽为具名常量或配置，附带调参依据。

### harness.py

### [中] `build_control_state` 中 `latest_branch_decision` 选择逻辑与其它模块不一致
- 位置：`workers/research-worker/app/harness.py:184-187`
- 问题：此处取「最后一个 decision != NO_BRANCH，否则取最后一个」；而 runner.py `_build_verifier_focus`（line 180）直接取 `branch_decisions[-1]`。两处「最新分支决策」口径不同，导致 control_state 与 verifier_focus 可能指向不同分支。
- 影响：报告/审计中「最新分支」信息前后不一致。
- 建议：统一「latest branch」选择函数供两处复用。

### [中] `build_toolbox_summary` 中冗余 `dict(dict(...))` 与重复调用 `_selection_reason`
- 位置：`workers/research-worker/app/harness.py:313-319, 326, 366`
- 问题：`dict(dict(plan.stop_contract.get("execution_profile", {})).get(...))` 双重包裹无意义；`_selection_reason(item.family, plan)` 在 selected_queries 与 search_items 两处对同一 item 各算一次。
- 影响：轻微冗余计算与可读性问题。
- 建议：去除多余 dict 包裹；对每个 query 只算一次 selection_reason 并复用。

### [低] `build_step_trace` trace_id/event_id 仅由 phase 派生，同 phase 多次调用 id 相同
- 位置：`workers/research-worker/app/harness.py:604-605`
- 问题：`trace_id=f"trace-{phase.lower()}"`，同一 phase 的多条 trace（如多轮 SEARCHING）产生相同 trace_id；虽然 runner.py 后续用 target_id 前缀重写 event_id，但 trace_id 仍可能重复。
- 影响：trace 唯一性依赖后处理，若某路径未重写则 id 冲突。
- 建议：生成时即加入 round_no/序号或 uuid 保证唯一。

### [低] `summarize_execution` 内重复构造 control_state 与 audit_summaries
- 位置：`workers/research-worker/app/harness.py:111-132`（与 runner.py:560-601 各构造一次）
- 问题：runner 已单独调用 build_control_state/build_audit_summaries，summarize_execution 内又各构造一次，重复计算同一批状态。
- 建议：允许注入已构造的 control_state，避免重复。

### harness_regression.py

### [低] 回归套件为纯静态断言集，未由 CI 实际执行驱动
- 位置：`workers/research-worker/app/harness_regression.py:20-76, 89-194`
- 问题：定义了 5 个回归 case 与 `evaluate_harness_regression_case`，但本模块只提供数据与纯函数评估，是否被真实端到端结果驱动取决于外部 test。若无对应 test，`suite_version="v1"` 等只作展示。
- 建议：确认存在把 `run_research_task` 真实产物喂给 `evaluate_harness_regression_case` 的测试；否则回归保证名不副实。

### [低] `evaluate_harness_regression_case` 大量 `dict(payload.get(...))` 防御式转换
- 位置：`workers/research-worker/app/harness_regression.py:93-107`
- 问题：连续十余处 `dict(result_payload.get(k, {}))`/`list(...)`，用于容错但掩盖了 payload 结构缺失；一旦上游字段名变更，评估会静默判失败而非报错。
- 建议：对关键字段缺失区分「结构错误」与「断言失败」。

### recovery_targets.py

### [低] `build_recovery_targets` 每次调用重复解析同一 stop_contract
- 位置：`workers/research-worker/app/recovery_targets.py:6-24` + `runner.py:181,286-287`
- 问题：runner 在 `_build_verifier_focus` 与 `_build_phase_payload`(VERIFYING/WRITING) 中多次调用 `build_recovery_targets(plan)`，每次都重新 `_string_list` 解析六个字段。
- 建议：单次计算后复用。

### evidence_horizon.py

### [中] `plan_evidence_horizon` 的 target_window_count 用 `source_diversity+1+retry_pressure`，含多个隐式常量
- 位置：`workers/research-worker/app/evidence_horizon.py:20-27`
- 问题：EXPAND/EXPAND_COUNTERFACTUAL 的目标窗口数 `min(5, max(2, source_diversity + 1 + retry_pressure))`，其中 5、2、retry_pressure=`min(2,max(0,repair_count))` 等均为裸幻数，缺乏出处说明；与文档 evidence horizon 策略的对应关系未标注。
- 影响：窗口预算调参不透明，行为难以对齐设计文档。
- 建议：抽为具名常量并注释与设计文档的映射。

### [低] uncertainty 用 `1 - max(verdict_confidence, confidence)`，两置信来源语义混用
- 位置：`workers/research-worker/app/evidence_horizon.py:18`
- 问题：取 `verdict_confidence` 与 `confidence`（抽取聚合置信）的较大值反推不确定性。二者口径不同（验证置信 vs 抽取置信），取 max 可能人为压低不确定性。
- 建议：明确以 verdict_confidence 为准，confidence 仅在无 verdict 时兜底。

### [低] 决策结果为 list[dict] 而非类型化模型
- 位置：`workers/research-worker/app/evidence_horizon.py:11, 28-39`
- 问题：返回 `list[dict[str, object]]`，字段(horizon_key/action/target_window_count 等)无 schema 约束，下游 loop_runtime 以 `item.get(...)` 弱访问。
- 建议：定义 Pydantic 模型承载 horizon 决策。

### entity_identity.py

### [中] `canonical_entity_id` 仅取 sha256 前 16 位十六进制，存在碰撞面
- 位置：`workers/research-worker/app/entity_identity.py:24-25`
- 问题：`hashlib.sha256(candidate).hexdigest()[:16]` 只保留 64 bit。虽碰撞概率低，但在实体识别这一「系统拥有的持久标识」上截断哈希，规范化后不同实体名映射到同 id 会静默合并两个实体。
- 影响：极少数情况下不同实体被合并为同一 entity_id，证据错绑。
- 建议：评估是否需要更长摘要，或在冲突时以完整摘要/来源二次校验。

### [中] `_normalize_name` 正则丢弃除中文与 \w 外全部字符，跨语言实体易塌缩
- 位置：`workers/research-worker/app/entity_identity.py:32-33`
- 问题：`re.sub(r"[^\w㐀-鿿]+", " ", ...)` 把非 \w、非 CJK 统一表意区（且区间 `㐀-鿿` 未覆盖扩展 B 区及部分符号）的字符全部替换为空格并小写。日文假名、韩文、含变音符的拉丁名等会被大幅塌缩，导致不同实体规范化后相同。
- 影响：多语种实体去重错误（既可能误合并也可能因 \w 对 unicode 的处理而不稳定）。
- 建议：使用 unicodedata 归一化(NFC/casefold)并保留更广的字母类别，或对实体名采用更稳健的规范化。

### [低] `canonical_entity_name` 截断到 200 字符无省略标记
- 位置：`workers/research-worker/app/entity_identity.py:28-29`
- 问题：`[:200]` 硬截断，长名被静默截短。
- 建议：可接受，但建议记录或加省略号以示截断。

### loop_runtime.py（恢复/重建补充）

### [中] `_normalize_restored_read_window` 逻辑极长且分支密集，缺测试保护
- 位置：`workers/research-worker/app/loop_runtime.py:1066-1160`
- 问题：约 95 行的多级 if/else 用于从 legacy checkpoint 反推 fetch_status/content_origin/fetch_method，含大量字符串状态常量（"HTTP_FETCHED"/"JINA_FETCHED"/"FALLBACK" 等）与「非 WORKSPACE 才用」这类微妙条件。可读性差、易回归。
- 影响：恢复路径的元数据重建一旦出错会污染报告的来源/抓取统计，且难以定位。
- 建议：拆分为小函数并补充针对各类 legacy 快照形态的单元测试。

### [低] `_restore_resume_context` 对缺失 fetched_documents 有两级回退（从 window 重建 / 重新 fetch）
- 位置：`workers/research-worker/app/loop_runtime.py:896-902`
- 问题：先尝试从 read_windows 反推 document，再在仍为空时真实 fetch。两级回退语义叠加，且第二级触发网络（见前述高危项）。
- 建议：明确回退优先级与边界，禁止在恢复期发起新网络抓取。

### 测试覆盖缺口

### [中] `entity_identity.py` 无任何专属单元测试
- 位置：`workers/research-worker/tests/`（grep `entity_identity` 命中 0）
- 问题：canonical_entity_id/canonical_entity_name/_normalize_name 承担「系统拥有的实体标识」这一核心不变量，却无直接测试覆盖（尤其哈希截断碰撞、多语种规范化）。
- 建议：补充实体 id 稳定性、去重、跨语言规范化的单元测试。

### [低] loop_runtime 恢复路径/墙钟耗尽/premature-guard 边界测试需确认充分性
- 位置：`workers/research-worker/tests/test_loop_runtime.py`
- 问题：loop_runtime 逻辑分支极多（墙钟耗尽的两处、resume 早退、冲突但预算耗尽、通用恢复冻结全场 cell 等），需确认这些边界均有断言覆盖。
- 建议：针对上述具体分支补齐用例。
