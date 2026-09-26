# DeepResearch 能力落地 · 交接文档

> 生成时间：2026-09-19
> 权威计划：[`DeepResearch-简历能力落地执行计划.md`](./DeepResearch-简历能力落地执行计划.md)
> 执行台账（**续做前必读**）：[`../experiments/deep-research/PLAN-STATUS.md`](../experiments/deep-research/PLAN-STATUS.md)
> 本文是**索引与交接**，不替代台账。台账里有每项的事实依据、裁决编号与命令输出。

---

## 0. 当前状态一句话

**M0 / M1 已完成；M2 的策略模块和 legacy loop 已完成，但 MA4 真实路径的局部修复仍待证明；M3 主体已实现，真实冲突与受限反证仍待取证；M4 任务级恢复进入真实验证；M5 / M6 未开始。**
Docker Desktop 已恢复，开发容器正常。backend 已以当前源码重建并健康运行，开发 MySQL 已迁移至 V109。

---

## 1. 已完成

### M0 基线冻结（3/3）
| ID | 内容 | 产物 |
|---|---|---|
| DR-000 | Provider/开关/版本快照 | `experiments/deep-research/manifest/experiment-manifest.json`、`scripts/deepresearch/build_experiment_manifest.py` |
| DR-001 | 三个代表性失败 Run 的状态与库导出 | `experiments/deep-research/baseline/runs/<runId>/`、`scripts/deepresearch/export_baseline_run.py` |
| DR-002 | 固定评测集（24 例，四类各 6） | `datasets/research-eval-v1.json`、`scripts/deepresearch/check_eval_dataset.py` |

### M1 抽取诊断闭环（4/4）
DR-101 / DR-102 / DR-103 / DR-104 全部 DONE。要点：结构化 `ExtractionResult` + 15 个拒绝原因码；诊断沿 `research-agent-completion.v2-extraction` 形态落库（`V105`，Python/Java 双侧校验）；24 例契约测试；6 个脱敏 replay fixture。
**真实链路已验证**（见 §4）。

### M2 Cell 修复与可审计 Replan（4/4）
DR-201 / DR-202 / DR-203 / DR-204 全部 DONE（`V106` + 服务 + 测试；`app/loop_stop_guard.py` 14 例故障注入）。
衍生立案项：DR-105（窗口作用域收敛）DONE、DR-106（跨语言词表守卫）DONE、DR-108（恢复规则 Locality 收敛）DONE、DR-109（死保护）DONE、DR-110（Replan 审计接线）DONE。

### M3 Verifier 与诚实终态（8/8）
DR-301 / DR-302 / DR-303 / DR-304 / DR-305 全部 DONE，另 DR-108 / DR-109 / DR-110 DONE。
**权威回归：`backend` research 包 = 334 run / 0 failures / 0 errors / 1 skipped**（主对话独占复跑）。
关键能力：Candidate 提升硬门禁（Java 独立重算）；冲突跨来源显式化 + 冲突轨迹；受限反证 + 硬上界 + 收敛/limitation；业务终态唯一权威（四态 + `V107` 五列）。

### M4 Checkpoint 与真实恢复（部分）
| 项 | 状态 |
|---|---|
| DR-401 Schema 设计 | DONE（设计文档，见 §7） |
| **M4-A** 与链路无关的 hydration 正确性（6 项） | **DONE**（`V108` + 7 例） |
| **M4-A2** 消除「0 task 且无可调度」livelock | **DONE**（3 例） |
| MA4 任务级恢复（第一阶段） | **执行中**（`b-task-recovery`） |
| DR-402/403/404/405 的 loop 级部分 | **冻结**（D-39，待 legacy loop 状态明确后重估） |

### 真实环境（G-1 / G-3）
- **G-1**：真实 MySQL 已从 V104 迁到 **head（V107）**，V105/V106/V107 三支迁移在真实引擎上 DDL 与索引均实检通过。
- **G-3**：终态列真实样本已取得（详见 §4）。

---

## 2. 未完成（按续做优先级）

| 优先级 | 项 | 说明与依赖 |
|---|---|---|
| **DONE** | 恢复 Docker Desktop | 2026-09-19 已复核 Engine 与主要开发容器健康 |
| **P0** | MA4 任务级恢复（第一阶段）收口 | Worker 定向 7 passed、全量 631 passed，V109 已迁移；仍需真实 Task 重领与账本取证 |
| **P1** | **D-33 真实引擎验证** | 2026-09-19 首次 `tick` 场景尝试发现既有 task-insert 闸门不能形成预期等待图，试验用例已撤回。需为 `adjudicateRun` 的 run-to-cell 顺序增加专用 cell-update 闸门后再跑 MySQL 8.4 |
| **P1** | M4 第二阶段（loop 级） | DR-402/403/404/405 的 Worker 侧；**冻结中**，取决于「legacy loop 是否仍受支持」 |
| **P1** | **DR-111** | BLOCKED，与上一行同一范围问题（D-35/D-36/D-39） |
| **P2** | **G-2** Replan 审计行真实样本 | `research_agent_replan_audit` 真实落库样本仍缺，需一次触发 Discovery 修订的 Run |
| **P2** | 冲突能力端到端真实取证 | DR-303 typed-fact 谓词按 D-30 是**窄召回**，建议放到 M6 用 24 题评测集一次跑完，**不要赌单次 Run** |
| **P2** | M5 引用审计与报告（DR-501/502/503） | 未开始。注意 **DR-503 有硬前置**：必须为 `COMPLETED_WITH_LIMITATIONS` / `INSUFFICIENT_EVIDENCE` 渲染限制性报告，且断言「markdown 为空时绝不生成 report_artifact」 |
| **P3** | M6 固定评测与简历证据（DR-601~604） | 未开始，依赖 M4、M5 |
| **P3** | DR-115 测试基建地雷 | 低优先，已有绕过方式（§7） |
| **P3** | DR-105 | 已 DONE（风险表那行未同步，可忽略） |

---

## 3. 关键裁决索引（续做前必须知道）

台账共记录 **D-1 ~ D-39**。以下是**会改变后续实施方式**的那些：

| 编号 | 一句话结论 | 为什么重要 |
|---|---|---|
| **D-14** | Candidate 提升采用「**Java 独立重算为硬门禁**，Worker verdict 仅作补充信号」 | 任何新增校验都应落在 Java canonical ledger，不能让 Worker 间接获得业务写权限 |
| **D-26 / D-35 / D-36 / D-39** | **生产链路是 MA4（`deep_cell_executor`，单任务内跑完 search→fetch→read→extract，不跑 loop）**，legacy loop 只被 tests/smoke/benchmark 调用；M4 已按此分两阶段 | **同一结构问题出现过三次**（DR-303/304、DR-111、M4）。凡计划里标注「WK」且需要 Worker 跑多阶段的能力，都要先问「真实 Run 走不走这条路」 |
| **D-29** | 跨来源互斥**不得复用** `ResearchTypedClaimValidator.validate()`（它是**包含性谓词**）；但**抽取逻辑必须共用**（`facts()` 提为包内可见） | 判据：这是不是两层共用的语义原语 |
| **D-30** | 冲突判定接受**窄召回**（精确骨架相等），召回由 `CONFLICTS_RELATION_WITH_SUPPORT` 承担 | 误报会把正确 cell 永久降级；漏报只是少一个标记，代价不对称 |
| **D-34** | 事实域锚（`skeleton` + 日期上下文）**必须单一实现**，不得镜像 | 「不得修改 X」≠「不得让 X 暴露原语」 |
| **D-33** | tick 必须 **run→cell** 加锁顺序（run 锁在 `adjudicateRun` 第一条语句），与 `ResearchAgentCompletionCommitter` 一致 | 曾经构成 ABBA 死锁；**不接受把自己引入的死锁当已知风险长期挂着** |
| **D-27 / D-38** | 终态**不得**新增枚举；`taskCount()==0` 且引导无效时**不得遮蔽后续可终结判定** | 不允许存在「既没做事、又不终结」的 outcome |
| **D-31** | 规格里的**断言**不得驱动数据模型变更；**D-38 附加**：规格里的**前提**也必须先核实 | 两条都犯过（列级断言一次、事实性前提一次） |
| **D-18（未决）** | Worker→Backend 的 Operation Receipt 通道 R1/R2 | 随 D-39 第一阶段处理 |

---

## 4. 真实 Run 采集结果（已取得的证据）

环境：前端 nginx `127.0.0.1:3000` 代理 `/api/v2/**`（backend 自身端口**未映射宿主**）。
两个 Run：`1978c185-…`、`4c51a20f-…`，均 `COMPLETED`。

| 能力 | 真实证据 |
|---|---|
| **DR-305 诚实终态** | `completion_terminal_state=INSUFFICIENT_EVIDENCE`；`completion_unresolved_cells_json` 逐 cell 带 reason_code（`REQUIRED_CELL_UNRESOLVED` / `OPTIONAL_CELL_UNRESOLVED`）。未跑满却**诚实完成**，而非落入不透明 barrier |
| **DR-102 抽取诊断**（M1 遗留项，本轮补上） | 真实模型 `glm-5.3-flash` 返回 4 张卡：**2 张因 `NON_EXACT_QUOTE` 被拒、2 张被接受**，结构化落库且不含模型原文 |
| **DR-405 预算守恒** | `research_budget_reservation` **十个键全部守恒**（如 `evidence_cards` 12 = consumed 1 + released 11） |

**同时暴露的两个真实缺陷（单元测试看不见）**：
1. **Worker 一度跑旧镜像**（提交 `completion.v1`）→ 诊断列恒为 NULL。已重建 Worker 镜像解决。
2. **一次完整跑完的 Run，checkpoint / hydration 快照 / 阶段屏障行全部为 0** → DR-402 的退出条件在真实链路上**不成立**。

**采集环境里已写入真实库**：用户 `dr-g2g3-probe`、工作台 `c805f003-331a-438c-bab4-e5801614e490`、2 个 Run。**清理：删除该 workspace 即可级联。**

---

## 5. 环境现状与恢复（P0）

### 历史故障
为跑锁矩阵 IT 拉起六服务隔离栈（mysql+redis+kafka+backend+research-worker+lock-matrix-test），
**Docker Desktop 引擎开始对所有请求返回 HTTP 500**（`docker ps` / `docker version` / `compose down` 全部失败）。
失败码 `exit=125` 是 `docker` 命令本身失败，**不是测试失败**。

### 已执行的恢复结果
Docker Engine 与开发容器已经恢复；backend 当前镜像健康。以下步骤保留为故障复发时的恢复手册。

### 恢复步骤
1. 重启 Docker Desktop（必要时重启整机）。
2. 确认开发容器完好：`docker ps --format '{{.Names}} {{.Status}}'`，检查 `noteweave-v2-mysql/backend/frontend/research-worker-consumer/redis/kafka`。
3. 清理残留隔离项目：
   `docker compose --project-name noteweave-ma4g-lockmatrix-local -f scripts/ma4g/docker-compose.yml -f scripts/ma4g/docker-compose.lockmatrix.yml down --volumes --remove-orphans`
4. 确认真实库仍在 head：截至 2026-09-19，`flyway_schema_history` 最高版本应为 **V109**。

### 再次跑锁矩阵的教训
- 隔离栈太重。**只起 mysql + lock-matrix-test 两个服务**，不要带 backend/worker/kafka/redis；或先加大 Docker Desktop 内存/CPU 配额。
- 锁矩阵 IT 断言 MySQL **8.4** + `performance_schema=1`（开发库是 `mysql:8.4`，版本匹配）。
- 即便跑通 13 个存量用例也**验不了 D-33**，须先补 `TICK` 场景。

---

## 6. 常用命令（续做直接复用）

```powershell
# Java 权威回归（基线 334 / 0 / 0 / 1）
.\mvnw.cmd -f backend/pom.xml -o -Dtest='com.noteweave.research.*Test' -DfailIfNoTests=false test

# Worker 侧（基线 624 passed）
cd workers/research-worker; python -m pytest tests/ -q

# 真实库取证
$p = (python -c "import io;print([l.split('=',1)[1].strip() for l in io.open('.env',encoding='utf-8').read().splitlines() if l.startswith('MYSQL_PASSWORD')][0])")
docker exec -i noteweave-v2-mysql env MYSQL_PWD="$p" mysql -unoteweave -D noteweave --batch -e "<SQL>"

# 真实 Run 采集（curl 不可用，见 §7）
Invoke-RestMethod -Uri 'http://127.0.0.1:3000/api/v2/auth/register' -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress)
```

**并发纪律（重要）**：整包回归由主对话**独占**执行；执行体在检测到其它 java/maven 进程时**只许跑自己的测试类**。
本波次并发度降为 1 后**全程零污染**；上一波次 4 执行体并行时产生了两次假缺陷和一次 75 errors 瞬时红灯。

---

## 7. 已知陷阱

1. **curl 在本环境不可用**：PowerShell 会吃掉引号导致 `MALFORMED_REQUEST`。用 `Invoke-RestMethod`（PowerShell 原生）。
2. **DR-115（H2 json 地雷）**：`createRun` 用绑定 VARCHAR 写 `research_run.agent_feature_flags_json`（json 列），H2(MySQL 模式) 会存成 JSON **字符串标量**；真实 MySQL 正常。任何「打开某全局 flag + 用 `createRun` 造 Run」的测试会在回读时失败。
   **绕过**：测试内用 `json_object(...)` 重写该列（参考 `ResearchAgentCheckpointHydrationCorrectnessTest`）。
3. **迁移号**：`V105`=DR-102、`V106`=DR-203、`V107`=DR-305、`V108`=M4-A、`V109`=MA4 Task 级工具授权账本。DR-401 设计文档里的编号已过期（它写于 V104 时代，`:246/:251/:265/:322/:328/:356` 均需按当前实际对照）。
4. **预算键集合被双重锁死**：Worker `agent_task_client.py:457-459`（精确集合校验）+ 锁矩阵 IT `:927-929`。DR-405 的 Run 级账本**必须新表/新字段**，不能改现有键集合。
5. **终态字面量 9 处硬编码、cell 状态 `in(...)` 过滤 4 处**：新增 Run/cell 状态必须同步，否则「静默解除终态化」或漏调度。
6. **`research_agent_checkpoint` 加列必须 nullable/有默认值**：`ResearchAgentReplanAuditDiscoveryWiringTest:327-337` 按显式列清单插入。
7. **hydration 是双开关且默认关**（全局属性 + 源 Run 快照 flag）：任何恢复 E2E 必须显式打开两层开关，否则永远只跑到 `CONTEXT_RESTART`。

---

## 8. 产物索引

| 类别 | 路径 |
|---|---|
| 执行计划 | `docs/DeepResearch-简历能力落地执行计划.md` |
| 架构文档 | `docs/DeepResearch-ResearchAgent架构文档.md` |
| Checkpoint Schema 设计（DR-401） | `docs/research/Research-Checkpoint-Schema设计.md`（**编号已过期**，见 §7.3） |
| **执行台账** | `experiments/deep-research/PLAN-STATUS.md` |
| 基线证据 | `experiments/deep-research/baseline/`、`manifest/`、`datasets/` |
| 负例报告（DR-302） | `experiments/deep-research/reports/DR-302-negative-cases.md` |
| replay fixture | `experiments/deep-research/replay-fixtures/` |
| 迁移 | `backend/src/main/resources/db/migration/V105…V108` |
| 锁矩阵 | `scripts/ma4g/`（`docker-compose.lockmatrix.yml`、`run-round.ps1`、`README.md`） |
