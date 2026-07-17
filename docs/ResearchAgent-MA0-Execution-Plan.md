# Research Agent MA0：协议与并发真源执行方案

> 文档状态：CURRENT  
> 阶段：MA0  
> 最后核对日期：2026-07-15  
> 上位设计：`docs/ResearchAgent-受控多Agent并行架构与改造方案.md`  
> 当前基线：Research Worker `210 passed`  
> 本阶段不启用多线程或多进程 Agent；目标是先建立并行执行所依赖的不可绕过契约。

## 1. 阶段目标

1. 定义强类型 `CellTaskBundle / AgentExecutionResult / CellCandidate / MergeDecision`。
2. 给 Worker 内 canonical `ResearchStateCell` 增加版本、plan/entity-set 绑定与最后合并标识。
3. 建立确定性 `MergeGate`：Agent 只能提交 candidate，不能直接把 cell 写成 VERIFIED。
4. 校验 schema、entity/cell 绑定、plan revision、entity-set version、cell version、lease epoch、fencing token、evidence refs 和 verifier verdict。
5. 支持重复 candidate 的幂等接受；拒绝过期、冲突和无证据候选。
6. 新增执行模式开关，默认保持 `SEQUENTIAL_V1`，不改变当前生产主循环。

## 2. 非目标

- 不创建线程池、异步 scheduler、Kafka Agent topic、lease reaper 或 Redis 分布式限流。
- 不修改 Backend 最终快照 delete/rebuild 路径。
- 不把新协议接入现有 `run_research_loop`。
- 不宣称多 Agent 已实现。

这些内容属于 MA1–MA4。MA0 的退出条件是“并发结果能被安全描述和合并”，不是“已经并发执行”。

## 3. TDD 顺序

### Red 1：协议约束

- bundle 不能为空、不能跨 entity、目标 cell 不能重复；
- candidate 必须绑定 task/execution/cell/base version；
- usage 不允许负数；
- fencing token 和 lease epoch 必须为正数；
- terminal result 必须有 termination reason。

### Red 2：Merge Gate 拒绝路径

- 拒绝过期 plan/entity-set/cell version；
- 拒绝过期 fencing token/lease epoch；
- 拒绝 task 未包含的 cell 与 entity/column 错配；
- 拒绝不存在或错误绑定的 evidence；
- 拒绝非 `SUPPORTS` verdict；
- 拒绝覆盖 `FROZEN` cell。

### Red 3：接受与幂等

- 合法 candidate 合并后 `version + 1`；
- 只采用 verifier 接受的 evidence IDs；
- 写入 `last_merge_id`；
- 相同 merge 重放不再次加版本；
- 同 base version 的第二个不同候选被判定为 stale；
- VERIFIED cell 不允许被不同值静默覆盖。

### Green 与 Refactor

依次实现 `agent_contracts.py`、`merge_gate.py`、扩展 `ResearchStateCell` 和 Settings。Merge Gate 保持纯函数，不访问网络、不调用 LLM、不修改传入对象；reason code 使用枚举。

## 4. 核心不变量

1. Agent result 永远不是 canonical state。
2. 只有 Merge Gate 可以产生新版本 canonical cell。
3. `SUPPORTS` 只是必要条件；版本、fencing、schema 和 provenance 必须同时通过。
4. 任意拒绝都不得部分更新 cell。
5. 相同输入重复执行必须得到相同决策和状态。
6. MA0 新能力默认关闭，现有 `SEQUENTIAL_V1` 行为不变。

## 5. 文件清单

新增：

- `workers/research-worker/app/agent_contracts.py`
- `workers/research-worker/app/merge_gate.py`
- `workers/research-worker/tests/test_ma0_agent_contracts.py`
- `workers/research-worker/tests/test_ma0_merge_gate.py`

修改：

- `workers/research-worker/app/models.py`
- `workers/research-worker/app/config.py`
- 本文档执行记录

## 6. 验收命令

```powershell
python -m pytest workers/research-worker/tests/test_ma0_agent_contracts.py -q
python -m pytest workers/research-worker/tests/test_ma0_merge_gate.py -q
python -m pytest workers/research-worker/tests -q
python -m py_compile workers/research-worker/app/agent_contracts.py workers/research-worker/app/merge_gate.py
git diff --check
```

## 7. 退出门槛

- Red 场景全部转绿，保留正例和对抗负例。
- Research Worker 全量测试无回退。
- 默认执行模式仍是 `SEQUENTIAL_V1`。
- 新模块不被当前主循环隐式调用。
- MA0 完成不等于多 Agent 并行完成。

## 8. 执行记录

2026-07-15 已完成：

- Red：新增 `test_ma0_agent_contracts.py` 与 `test_ma0_merge_gate.py`，在实现前因 `app.agent_contracts` 与 `app.merge_gate` 缺失而收集失败。
- Green：新增强类型 Agent task/candidate/execution 契约与纯函数 Merge Gate；扩展 `ResearchStateCell` 的版本绑定字段；默认执行模式固定为 `SEQUENTIAL_V1`。
- 覆盖：任务 target 唯一性、预算/fencing 输入约束、task/execution 绑定、plan/entity/cell stale、lease/fencing mismatch、task scope、证据精确绑定、非 SUPPORTS、FROZEN、幂等重放和 VERIFIED 值冲突。
- 验证：MA0 定向测试 `10 passed`；Research Worker 全量 `220 passed`；`py_compile` 与 `git diff --check` 通过（仅有既存 LF/CRLF 提示）。

MA0 完成后仍未启用 agent 并发。下一阶段 MA1 将把当前顺序 round 拆为 `CellTaskBundle -> Candidate -> Verifier -> Merge` 的顺序执行路径，先证明语义等价，再允许提高并发度。
