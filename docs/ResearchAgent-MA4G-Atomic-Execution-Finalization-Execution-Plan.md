# Research Agent MA4G：原子 Execution Completion 与结果收口执行方案

> 状态：IMPLEMENTED / VERIFIED（2026-07-16；证明范围为隔离的 deterministic fake-provider、真实 MySQL 8.4/Kafka；不等同于真实 provider 或生产灰度）  
> 前置：MA4E 已闭合 Run→task/reservation/outbox，MA4F 已验证受控 fake-provider 单/双 Worker、claim-before-write 崩溃恢复与 transport replay；MA4F 的证明边界不包含 CAS-after-write 崩溃。  
> 目标：用一个不可变 Completion Envelope 和一个 Backend 数据库事务，原子提交 evidence、candidate、merge/CAS、execution、预算结算、task terminal、outbox 收口与 cell binding 释放，彻底关闭“canonical cell 已更新，但 execution 尚未 submit”的事务窗口。

## 1. 结论与本阶段边界

MA4G 的核心改造不是给现有三个 HTTP 请求增加更多重试，而是移除分段持久化语义。Worker 完成 Search→Fetch→Read→Extract 后只构造一个 immutable completion envelope；Backend 是唯一提交者，并在同一 MySQL transaction 中完成所有结果写入。Kafka 仍只是触发器，不参与数据库事务。

本阶段只解决 **一次 Agent execution 的数据库原子收口与重放一致性**：

- evidence、candidate、merge/CAS、execution、budget、task/outbox/cell binding 要么全部提交，要么全部回滚；
- 同一 completion 的响应丢失后可安全重放，返回同一 canonical receipt；
- 同一幂等键绑定不同内容时必须稳定报冲突，不能伪装成 replay；
- 成功 completion 同时记录实际消耗并释放 reservation 中未使用的份额；
- snapshot-ready `INCREMENTAL_V1/DEEP_CELL` task 不再允许走旧的 evidence batch、candidate batch、submit 三段写路径；历史非 snapshot task 即使暂时保留 route mapping，也不属于 MA4G execution path。

MA4G 明确不做：

- 不实现运行中的 lease heartbeat，也不据此放宽当前短 lease；
- 不启用真实 LLM、Search、Fetch provider，Compose 证明仍限定 deterministic fake provider；
- 不自动创建下一 wave、写 coordinator checkpoint、推进或 finalize ResearchRun、生成最终报告；
- 不引入 Wide/Counterfactual/Audit/Synthesis 角色，不实现 quorum/speculative execution；
- 不承诺外部 provider 调用 exactly-once。Provider permit/rate-limit 发生在 completion 之前，数据库事务不能回滚外部调用。

建议后续阶段固定为：MA4H 处理 heartbeat、drain、trusted permit 与长调用 lease 安全；MA4I 处理 Run/wave advancement、checkpoint 和 repair；MA4J 处理 SYNTHESIS、报告持久化以及 Run/父任务原子终态。真实 provider 的归档证据、质量/延迟/成本基准属于 MA5，生产扩量与自动回退属于 MA6。

## 2. 当前缺口与必须修复的失败序列

当前 `DeepCellExecutor` 的持久化顺序是：

```text
appendWorkspaceEvidence()     # transaction A，已提交 source_evidence
appendAndVerify()             # transaction B，写 candidate + merge，并 CAS research_cell
submitExecution()             # transaction C，写 execution、结算预算、terminal task
```

这三个 service 虽各自有 `@Transactional`，但跨 HTTP 请求不存在共同事务。MA4F R3 只在任何 evidence/candidate/CAS 写入前杀死 Worker，因此能由新 epoch 重试；它没有覆盖下面的关键序列：

```text
Worker epoch=1
  ├─ evidence commit
  ├─ candidate + cell CAS commit：cell v0 -> v1
  ├─ process/network failure
  └─ execution submit 未发生

reaper
  ├─ task -> RETRY_WAIT/EXPIRED
  └─ 释放 active_task_id

Worker epoch=2
  └─ snapshot 仍绑定 cell v0，cell 已是 v1 -> 永久 STALE_CELL_VERSION
```

此外，现实现有以下幂等与账本缺口必须在同一阶段处理：

1. candidate replay 只比较 candidate id、cell key、base version，没有比较 task/execution、plan/entity-set、epoch/fence、value、evidence、confidence 的完整内容。
2. merge replay 只按 merge key 返回 `IDEMPOTENT_REPLAY`，没有验证该 key 是否仍绑定同一 candidate、verdict 和结果内容。
3. evidence、candidate 和 execution 分别使用不同幂等边界，无法证明它们属于同一不可变 execution result。
4. `submitExecution` 将 reservation 置为 `SETTLED`，但成功路径没有同时把 `reserved - consumed` 写入 released ledger。
5. HTTP commit 成功、response 丢失时，调用方无法用一个完整 payload 查询和证明最终提交结果。
6. 多 cell task 中，即便每个 cell CAS 单独正确，也缺少“第一个 cell 已更新、第二个 cell 失败时全部回滚”的聚合级故障证明。

## 3. 必须保持的系统不变量

实现和测试以以下不变量为最高约束：

### 3.1 单一提交不变量

一个 `research_agent_task` 最多存在一个 committed completion。成功提交后必须同时满足：

```text
task.status = SUBMITTED
exactly one execution for task
exactly one completion anchor for task
all completion evidence/candidates/merges reference that anchor
each accepted candidate has deterministic research_cell_evidence links to its in-envelope evidence
every accepted target cell has exactly one version increment
all task cell bindings are released
READY retry outbox rows are CANCELLED
budget.reserved = budget.consumed + budget.released
budget.state = SETTLED
canonical envelope_json and receipt_json are both immutable and digest-bound
```

### 3.2 全有或全无不变量

若 completion 在任意数据库步骤失败，则 task、execution、completion、evidence、candidate、merge、cell version/value/status、budget、outbox 和 binding 的提交前摘要必须完全不变。不能把“已 append evidence，但没有 execution”视为可接受的部分成功。

### 3.3 fencing 不变量

首次 commit 必须匹配当前 task 的 `worker_instance_id + lease_epoch + fencing_token + unexpired lease`，且 Run 必须仍是非终态 `INCREMENTAL_V1`。旧 epoch/fence 不能提交新 completion。

只有一个例外：若 completion 已经 committed，携带完全相同 identity 与 envelope digest 的请求可在 lease 过期、task 已 SUBMITTED、Run 后续进入终态后读取原 receipt；这是只读 replay，不是再次授权写入。

### 3.4 canonical authority 不变量

Worker 只能提出 evidence/candidate，不能提供最终 verdict、result cell version、budget released 值或 task terminal 状态。Backend 从 server-persisted task snapshot、source scope、cell、reservation 和验证规则计算这些结果。

`budget_usage` 也遵守这条边界：Worker 只报告它能直接观察的 `llm_calls/search_calls/fetch_calls/read_calls/extract_calls/evidence_cards/candidates_submitted`；`evidence_appended/candidate_merges_accepted/candidate_merges_rejected` 必须由 Backend 根据本次事务实际写入和 verdict 派生，Worker 传入这些 server-owned dimension（即使值为 0）也必须拒绝。

### 3.5 预算终态不可逆不变量

`RESERVED` 可由成功 completion 原子变成 `SETTLED`，也可由未执行取消/重试耗尽变成 `RELEASED`；`SETTLED` 已与 completion 绑定，是不可逆审计终态。cancel、reaper、旧 `release()` helper 或 Run 清理不得再把 `SETTLED` 改成 `RELEASED`、清空 consumed/released 或重复记账。取消已含 submitted task 的 Run 时，只能保留该 task 的 completion 与 `SETTLED` ledger。

## 4. Immutable Completion Envelope

新增严格版本化 contract `research-agent-completion.v1`。Worker 在执行期间只在本地内存构造它；除 rate-limit permit 外，不提前调用任何结果写接口。

建议请求形态：

```json
{
  "schema_version": "research-agent-completion.v1",
  "task_id": "task-id",
  "worker_instance_id": "ma4g-worker-a",
  "lease_epoch": 1,
  "fencing_token": 1,
  "execution_key": "deep-cell:task-id:1:1",
  "task_snapshot_digest": "sha256:...",
  "termination_reason": "CANDIDATES_PROPOSED",
  "budget_usage": {
    "llm_calls": 1,
    "search_calls": 1,
    "fetch_calls": 1,
    "read_calls": 1,
    "extract_calls": 1,
    "evidence_cards": 1,
    "candidates_submitted": 1
  },
  "telemetry": {
    "search_hits": 1,
    "documents": 1,
    "windows": 1
  },
  "trace_digest": "sha256:...",
  "evidence": [
    {
      "evidence_key": "evidence-key",
      "window_id": "window-id",
      "source_id": "source-id",
      "source_title": "title",
      "search_query": "query",
      "read_focus": "focus",
      "quote_text": "trusted exact quote",
      "claim_text": "candidate value",
      "relation_type": "SUPPORTS",
      "support_score_ppm": 900000,
      "conflict_score_ppm": 0,
      "snapshot_status": "WORKSPACE"
    }
  ],
  "candidates": [
    {
      "candidate_key": "candidate-key",
      "cell_key": "entity:column",
      "base_cell_version": 0,
      "candidate_value": "candidate value",
      "evidence_keys": ["evidence-key"],
      "confidence_ppm": 900000
    }
  ],
  "envelope_digest": "sha256:..."
}
```

### 4.1 Contract 约束

- path task id 与 body `task_id` 必须相等；Run/workspace/provider/plan/entity-set 不允许由 Worker 重复传入，全部从 task snapshot 读取。
- `execution_key` 在同一 task 内唯一，并绑定整包内容；建议继续由 `task + epoch + fence` 稳定生成。
- `task_snapshot_digest` 必须与 claim 返回并保存在 task row 的 digest 一致，防止 Worker 用旧/篡改 snapshot 构造结果。
- evidence/candidate 可为空，但必须与 termination reason 一致。允许值首期限定为 `CANDIDATES_PROPOSED`、`EVIDENCE_ONLY`、`NO_SUPPORTED_CANDIDATE`。
- `evidence_key`、`candidate_key` 以及 execution/completion stable key 统一限制为已 trim 的 lowercase ASCII 安全字符集 `^[a-z0-9][a-z0-9._:-]*$`，并分别受物理列长度约束；不能接受大小写或重音字符不同但会被 MySQL `utf8mb4_unicode_ci` 唯一索引判为相等、而 canonical bytes 又不同的 key。
- evidence key、candidate key 在包内不得重复；每个 candidate 的 evidence keys 必须非空、去重，并且是同一 envelope evidence 集合的子集。
- 每个 candidate 必须精确绑定 snapshot target cell/base version；首期每个 target 最多一个 candidate，candidate 总数不得超过 target 数。
- 首期只接收 `WORKSPACE` evidence，quote 必须能在 Backend 保存的 trusted source sample 中精确定位；不接受 Worker 提交 URL/provider/snapshot authority。
- score 使用 `0..1_000_000` 整数 ppm，budget usage/telemetry 使用有上界的非负整数，禁止 float/NaN/Infinity 带来的跨语言 canonicalization 歧义；原子路径不得先换算为 decimal/double 再持久化。
- 设置总 request bytes、evidence 数、单 quote/claim 长度、telemetry key 数等硬上限；超限在开启事务前拒绝。
- Pydantic/Jackson 均拒绝 unknown fields；不能悄悄忽略未来字段后仍计算相同 digest。
- 字符串长度统一按 Unicode scalar/code point 计数（Python `len`、Java `codePointCount`），不按 Java UTF-16 code unit；总请求上限另按原始 UTF-8 byte 数计。这样 emoji 等 non-BMP 字符不会造成跨语言边界分歧。

### 4.2 原始字节级 strict JSON 入口

Controller 必须先接收有界 raw bytes，再交给专用 strict parser；禁止先绑定普通 Jackson DTO，因为普通绑定会在业务校验前折叠 duplicate key、执行数字/布尔 coercion 或用 replacement character 吞掉非法 UTF-8。入口固定执行：

1. 在 decode 前按 raw body bytes 拒绝空包和超过 `256 KiB` 的请求；只接受 JSON content type。
2. 使用 `CodingErrorAction.REPORT` 的严格 UTF-8 decoder；拒绝非法/截断 UTF-8、BOM、lone surrogate，以及不能形成 Unicode scalar 的 escape。
3. 打开 raw object duplicate detection、`FAIL_ON_TRAILING_TOKENS` 和 unknown-field fail-closed；递归拒绝 NFC 后相撞的 object keys，例如 composed/decomposed 同时出现。
4. 数字先作为精确整数词法读取并检查范围；拒绝 decimal、exponent、NaN/Infinity、boolean、null、字符串数字及溢出，不能依赖 Jackson 宽松 coercion。
5. 完成 raw parse 后才构造 strict contract、做交叉引用校验和 canonical digest；所有失败稳定映射为不泄露正文的 4xx `RESEARCH_AGENT_COMPLETION_INVALID`，digest 不同则为专用 mismatch 错误。

HTTP 测试必须直接发送 raw bytes，而不只是实例化 DTO；否则无法证明 duplicate key、非法 UTF-8 和 trailing token 真正 fail-closed。

### 4.3 Canonical digest

`envelope_digest` 不信任客户端值。Backend 必须按同一规则重算并 constant-time 比较：

1. 排除 `envelope_digest` 自身；
2. 标识符只接受已 trim 的输入，正文不做隐式 trim；所有字符串要求合法 UTF-8，并固定 Unicode NFC；
3. object key 按字典序，evidence 按 `evidence_key`，candidate 按 `candidate_key`，candidate 内 evidence keys 按字典序；
4. 禁止重复 key/重复 list identity，数字只允许十进制整数；
5. 对 canonical UTF-8 JSON 计算 SHA-256，格式为 `sha256:<64 lowercase hex>`。

Python 与 Java 各提供同一份 golden vector，至少覆盖中文、NFC composed/decomposed、emoji/non-BMP（包含 literal 与合法 surrogate-pair escape）、literal/escaped `U+2028` 与 `U+2029`、反斜杠/引号/换行、列表乱序、map 乱序和边界整数。语义相同但输入表达或顺序不同必须得到相同 digest；任何实质字段变化必须得到不同 digest。negative vectors 至少包含 raw 非法 UTF-8、unpaired surrogate、NFC key collision、duplicate key、trailing token、float/exponent/bool/null/整数溢出。

## 5. Backend API 与返回语义

新增唯一结果入口：

```text
POST /internal/research-agent-tasks/{taskId}/complete
```

该 endpoint 的 controller 签名接收 raw body bytes，并在任何事务或查询前调用 4.2 的 strict parser；不能让 Spring/Jackson 的默认 DTO binding 成为第一解析层。path `taskId` 必须是数据库中 canonical task id，且与 envelope 完全相等。

成功 receipt 示例：

```json
{
  "completion_id": "...",
  "execution_id": "...",
  "task_id": "...",
  "completion_digest": "sha256:...",
  "receipt_digest": "sha256:...",
  "idempotent_replay": false,
  "evidence_appended": 1,
  "candidate_count": 1,
  "accepted_merges": [
    {"cell_key": "entity:column", "from_version": 0, "to_version": 1}
  ],
  "rejected_merges": [],
  "budget": {
    "state": "SETTLED",
    "consumed": {"search_calls": 1},
    "released": {"search_calls": 0}
  }
}
```

receipt 的 canonical 内容持久化；`idempotent_replay` 是响应元信息，不进入 receipt digest。完全相同的 replay 返回相同 completion/execution/result/receipt digest，只把该布尔值设为 true。

稳定错误码至少包括：

- `RESEARCH_AGENT_COMPLETION_INVALID`：schema、范围、重复 key 或交叉引用非法；
- `RESEARCH_AGENT_COMPLETION_DIGEST_MISMATCH`：客户端 digest 与服务端 canonical digest 不同；
- `RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT`：同 task/execution key 已绑定不同 envelope；
- `RESEARCH_AGENT_COMPLETION_ALREADY_COMMITTED`：task 已由另一个 execution key 完成；
- `RESEARCH_AGENT_TASK_STALE_LEASE`：首次 commit 的 owner/epoch/fence/expiry 不匹配；
- `RESEARCH_AGENT_TASK_SNAPSHOT_STALE`：snapshot digest、plan/entity-set 或 target version 不匹配；
- `RESEARCH_AGENT_COMPLETION_EVIDENCE_CONFLICT`、`...CANDIDATE_CONFLICT`、`...MERGE_CONFLICT`：行级 stable key 被不同完整内容占用；
- `RESEARCH_AGENT_COMPLETION_BUDGET_EXCEEDED`：任一实际用量超过 reservation 或出现未知 budget dimension。

invalid/stale/conflict 使用 4xx 且不重试；网络异常与 5xx 才允许携带同一序列化 envelope 重试。错误响应不得回显 quote、candidate value、内部 SQL 或 provider secret。

## 6. 数据模型与 Flyway 迁移草案

实施前确认当前最新 migration 仍为 V044；若无编号冲突，新增 `V045__add_research_agent_atomic_completion.sql`。迁移只做 expand，不删除旧列，使新 schema 对旧 binary 仍可读。

### 6.1 Completion aggregate root

新增 `research_agent_completion`：

```text
id                       varchar(36) PK
research_agent_task_id   varchar(36) FK, UNIQUE
execution_id             varchar(36) FK, UNIQUE
completion_key           varchar(160) NOT NULL
schema_version           varchar(64) NOT NULL
envelope_digest          varchar(71) NOT NULL
envelope_json            longtext NOT NULL
envelope_size_bytes      int NOT NULL
snapshot_digest          varchar(71) NOT NULL
worker_instance_id       varchar(128) NOT NULL
lease_epoch              int NOT NULL
fencing_token            bigint NOT NULL
receipt_json             longtext NOT NULL
receipt_digest           varchar(71) NOT NULL
committed_at             timestamp NOT NULL
UNIQUE(research_agent_task_id, completion_key)
```

一 task 一 completion 的 unique constraint 是最终并发裁决；`completion_key` 固定等于 envelope 的 validated `execution_key`，`(research_agent_task_id, completion_key)` 用于明确 replay identity。不要把全局 envelope digest 设为 unique，不同 task 即使产生相同业务文本也不是同一 execution。`envelope_json` 保存 Backend 重新 canonicalize 后的完整 UTF-8 JSON（包含已验证的 `envelope_digest`），不保存客户端原始排版；exact replay 必须同时比较 identity、digest 与 canonical envelope bytes，不能只凭 SHA-256 相等返回 replay。`envelope_size_bytes` 是 canonical UTF-8 byte 长度，用于审计和上限检查。

### 6.2 精确 ppm 物理列与长度/排序规则

V045 必须增加下列 nullable expand 列，同时保留 legacy decimal 列供旧历史读取：

```text
source_evidence.support_score_ppm                 int null
source_evidence.conflict_score_ppm                int null
research_agent_candidate.confidence_score_ppm     int null
research_cell.confidence_score_ppm                int null
```

MA4G 新写的 evidence/candidate 行和 ACCEPT 后 canonical cell 对应 ppm 列必须非 null 且在 `0..1_000_000`；Backend 直接写 envelope 整数，禁止经过 `double/BigDecimal/DECIMAL(5,4)` round-trip。legacy 行保持 ppm null，不做有损猜测回填。migration 用真实 MySQL 8 验证范围约束/应用校验、读写和 rollback；receipt 与 row digest 均取物理 ppm 整数。

Contract 上限不得大于当前物理列：`task_id` 是 canonical lowercase UUID、固定 36 字符；`evidence_key <= 64`、`window_id/source_id <= 64`、`source_title <= 300`、`cell_key/candidate_key/execution_key/completion_key <= 160`、`worker_instance_id <= 128`、schema version `<= 64`、digest 固定 `71`。`search_query/read_focus <= 4096`、`quote_text/claim_text/candidate_value <= 16384` code points，落 LONGTEXT；同时仍受 envelope UTF-8 byte 总上限约束。

数据库 fixture 固定 `utf8mb4/utf8mb4_unicode_ci`。进入 unique/idempotency index 的 evidence/candidate/execution/completion/merge stable key 必须使用上节 lowercase ASCII 规则，从而让 DB equality 与 canonical equality 一致。`cell_key` 是 server snapshot 的业务 key，可能含 Unicode：Worker 不能创造它；Backend 按 snapshot 精确匹配，并以 `ORDER BY CAST(cell_key AS BINARY), id` 锁定，不能依赖 `unicode_ci` 的排序相等关系作为锁次序。`envelope_json` 也不能用 collation-aware SQL `=` 比较；必须读回 canonical UTF-8 bytes 后 constant-time/byte-for-byte 比较 digest和正文。

### 6.3 Provenance、cell-evidence lineage 与完整内容 digest

为新原子路径增加 nullable、可回滚兼容的 provenance 字段：

- `source_evidence.agent_completion_id`、`content_digest`；
- `research_agent_candidate.agent_completion_id`、`content_digest`；
- `research_cell_merge.agent_completion_id`、`content_digest`；
- `research_cell_evidence.agent_completion_id`、`content_digest`；
- `research_budget_reservation.agent_completion_id`、`settlement_key`、`finalized_at`。

历史行保持 null 并视为 `LEGACY_UNBOUND`，不得伪造 completion 或回填猜测 digest。新 completion 写入的每一行必须带 completion id 和由 Backend 计算的完整 row digest。保留现有 `(run,evidence_key)`、`(run,candidate idempotency)`、`(run,merge_key)` unique index作为最后防线。

candidate 的 `execution_id` 在新路径写 Backend 生成的真实 `research_agent_execution.id`，而不是 Worker 自报的 execution key；实施时评估增加 nullable FK，历史非 UUID 值不做强制回填。

每个 ACCEPT candidate 必须在同一 transaction 内为其 cell 与每个引用的 envelope evidence 写一条 `research_cell_evidence`，并绑定同一 completion；REJECTED candidate 只保留 candidate/merge audit，不把证据冒充 canonical cell provenance。该关联的 stable identity 为 server cell id + source evidence id，完整 digest 还覆盖 completion/candidate/merge/evidence key；任何冲突整包回滚。仅写 `research_cell.evidence_refs_json` 或 merge 的 accepted ids 不能替代关系表。

### 6.4 数据库不变量

- 新 completion row 不允许没有 receipt。MySQL FK 不可 deferred，因此 service 必须在锁内先确定所有 server id、verdict、预期 version/budget结果并构造 canonical receipt，先 insert execution，再一次 insert带完整 envelope/receipt的 completion root，随后 child rows才能引用它；禁止先插缺 receipt的占位 root或在事务后补写。
- `envelope_json`、`receipt_json` 和 child rows 写入后不可更新；唯一允许变化的是 replay metric，不写数据库。
- reservation 成功终态保持 `SETTLED`，并满足逐 dimension `reserved = consumed + released`。
- 所有 lifecycle release SQL 只能命中 `state = 'RESERVED'`；`SETTLED` 行不得再次 release，且 `agent_completion_id/settlement_key/finalized_at` 一旦写入不可变。
- migration 增加索引前先用真实 MySQL explain/fixture 检查 task/completion、completion child 和 replay 查询路径。

## 7. 单事务 Commit 算法

新增 `ResearchAgentCompletionService.complete(command)`。Controller 只做 content-type/raw byte上限和 strict parser调用，不使用默认 DTO binding；所有业务写入均由该 service 的一个外层 `@Transactional` 管理。内部 evidence/candidate/merge/budget helper 使用 package-private commit primitive 或 `MANDATORY` propagation，禁止 `REQUIRES_NEW`、异步事件和事务内网络 I/O。

### 7.1 事务前

1. 严格解析 schema，执行大小、数量、字符和整数范围校验。
2. canonicalize envelope，重算并比较 digest。
3. 可先无锁查询 immutable completion：若 `(task, completion_key)` 已存在，完整比较 identity + digest + canonical envelope bytes 后返回 receipt；不一致立即 conflict。该 fast path 只能读 committed immutable row。

### 7.2 固定锁顺序

首次提交统一按以下**全局**顺序加锁；它不是 completion service 的局部约定，而是 coordinator、claim、heartbeat、`TaskService.expireLeases`、lifecycle reaper/cancel/redrive、budget release 与 outbox 收口共同遵守的顺序：

```text
research_run FOR UPDATE
  -> research_agent_task FOR UPDATE                 (多 task 按 task id)
  -> research_agent_completion / execution          (按 completion_key/id，锁内 replay recheck)
  -> research_budget_reservation FOR UPDATE          (按 reservation id)
  -> research_cell FOR UPDATE                        (ORDER BY CAST(cell_key AS BINARY), id)
  -> stable child rows                               (表序 + lowercase ASCII stable key + id)
       source_evidence -> candidate -> merge -> cell_evidence -> task outbox/delivery
```

不存在的 completion/child row 由上述父行锁、目标 unique index 和 insert/duplicate-key 后的锁内 re-read 共同裁决；不得用无索引范围扫描冒充稳定 key 锁。先锁 Run 是为了与 cancel/后续 advancement 建立统一线性化点。多行必须在 SQL 中显式 `ORDER BY` 后 `FOR UPDATE`，不能依赖 Java collection、`IN (...)` 返回顺序或 `unicode_ci` 隐式排序。

MA4G 必须同步消除当前的反序路径：

- claim/heartbeat 先无锁读取 task 的不可变 run id，再锁 Run、再锁 task 并重验；禁止“先 UPDATE task，再由 exists 子查询读 Run”。
- `TaskService.expireLeases` 与 lifecycle reaper 先无锁发现 bounded candidate ids，再按 run id/task id 排序，逐 Run 先锁 Run、再锁 tasks 并重验 expiry；二者最好收敛到同一 primitive，不能各保留一套不同锁序。
- cancel 先锁 Run，再按 id 锁全部受影响 tasks，之后依次处理 completion/execution、仍为 RESERVED 的 reservation、按 binary key 排序的 cells，最后处理 outbox；不能维持当前“outbox 后再 budget/cell”的反序。
- coordinator、redrive 和旧 release helper 只要会触碰这些表，也必须调用同一 lock-order helper 或在测试中证明其严格子序；禁止某条路径先锁 reservation/cell/task 再回头锁 Run。

并发证明必须运行在真实 MySQL、两个独立物理 JDBC connection/transaction 上，用 barrier 精确交错，而不是依赖同一 Spring test transaction。至少覆盖 complete↔cancel、complete↔reaper/expire、claim/heartbeat↔cancel、coordinator↔cancel，以及相同/不同 completion 双提交；断言无 deadlock/lock-timeout、只有一个合法线性化结果且全表不变量成立。

### 7.3 事务内步骤

1. 在锁内再次检查 exact replay；并发相同请求只能有一个首次提交者。
2. 校验 Run 是非终态 `INCREMENTAL_V1`；task 是 CLAIMED/RUNNING，owner/epoch/fence 精确匹配且 DB 时钟下 lease 未过期。
3. 校验 task snapshot digest、plan revision、entity-set version、所有 target binding 与当前 cell 一致；先锁定并验证全部 target，再修改任何一个 cell。
4. 校验 trusted source scope和整包 evidence；检查 task-scoped stable key 是否被其他 completion 占用。相同 key 只有在同一 completion exact replay 时才可复用，首次提交不“借用”历史 evidence。
5. 为 execution/completion 和 child rows生成 server ids、merge keys、row digests；structural verifier 只读取锁内/包内数据，计算 SUPPORTS/NOT_ENOUGH_INFO 等 verdict，并据锁定版本/预算预先构造 canonical receipt。invalid candidate在任何 anchor写入前使整包失败。
6. 依次写 execution anchor、带完整 canonical envelope/receipt的 completion root，再写 evidence、candidate 和 merge audit，使后续 child provenance FK立即有效；结构合法但证据不足的 candidate可记录REJECTED merge，不更新canonical cell。
7. 对需 ACCEPT 的 candidate执行带 `cell_version + plan_revision + entity_set_version + active_task_id + lease_epoch + fencing_token` 的 CAS，并写 `research_cell_evidence`。任一预期 CAS/关联 insert 影响行数不是 1，抛异常并回滚此前所有 child/CAS；禁止把部分 target 当作整包成功。
8. 从 reservation/snapshot 派生完整 budget dimensions：先验证 Worker-owned usage，Backend 再计算 `evidence_appended` 与 accepted/rejected merge 数；逐维原子写 `consumed`、`released = reserved - consumed`、`state = SETTLED` 和 completion settlement identity。snapshot budget 与 reservation 必须完全一致；未知 key 即使为 0 也拒绝。
9. 将 task 置 `SUBMITTED`，写 terminal timestamp；取消该 task 仍为 READY 的 retry outbox；释放所有 `active_task_id = task` 的 cell binding。
10. 提交前按已写 child/CAS/budget/task结果复核 receipt计数与版本、逐维守恒及所有 lineage；任何偏差抛异常并回滚。transaction commit后才返回 HTTP 200，completion/envelope/receipt不再更新。

任何步骤抛出异常都必须回滚。事务内不调用 Kafka、Redis、LLM、Search、Fetch 或对象存储；outbox 只更新本地行状态。

## 8. 完整幂等与冲突判定矩阵

| 场景 | 结果 | 是否写数据库 |
|---|---|---:|
| 同 task + execution key + identity + envelope digest + canonical envelope bytes | 返回原 receipt，`idempotent_replay=true` | 否 |
| 同 task + execution key，但任一 envelope 字段不同 | `COMPLETION_IDEMPOTENCY_CONFLICT` | 否 |
| task 已有 completion，新的 execution key 到达 | `COMPLETION_ALREADY_COMMITTED` | 否 |
| 同 evidence key，不同 content digest 或不同 completion owner | `EVIDENCE_CONFLICT` | 否，整包回滚 |
| 同 candidate key/id，但 task、execution、cell、base version、plan/entity-set、epoch/fence、value、evidence、confidence 任一不同 | `CANDIDATE_CONFLICT` | 否，整包回滚 |
| 同 merge key，但 candidate、cell、verdict、decision 或 version result 不同 | `MERGE_CONFLICT` | 否，整包回滚 |
| exact replay 时 lease 已过期或 Run 后续已终态 | 返回原 receipt | 否 |
| 非 replay 首次提交时 lease 过期/Run terminal | stale/terminal | 否 |
| 两个不同 envelope 并发争同一 task | 一个 commit；另一个读取 winner 后 conflict | winner 一次 |
| Kafka 已完成消息从 earliest 重放 | claim 返回 NOT_CLAIMABLE 并 ACK；数据库摘要不变 | 否 |

所有“duplicate key exception”处理必须在全局锁序内 re-read，并逐字段、row digest 与持久化 canonical envelope bytes 比较，不能见到唯一键或 SHA-256 冲突就直接返回 replay。特别是现有 `submitCandidate` 和 `mergeCandidate` 的宽松 replay 分支不能被新原子路径复用。

## 9. Budget conservation 与 unused release

成功 completion 的预算公式逐 dimension 成立：

```text
0 <= consumed[k] <= reserved[k]
released[k] = reserved[k] - consumed[k]
reserved[k] = consumed[k] + released[k]
```

实现要求：

- `budget_usage` 与普通 telemetry 分离；Worker schema 只含七个 worker-observable keys，不是任意 map。
- coordinator 生成的完整 DEEP_CELL reservation 同时作为 task snapshot budget；completion 锁内必须证明 `budget_json == reserved_json` 的 canonical map，防止 snapshot 与账本漂移。
- Backend 以本事务事实追加 server-derived `evidence_appended/candidate_merges_accepted/candidate_merges_rejected`，再对完整 reservation 逐维结算；Worker 不得自报这些值。
- 七个 Worker keys 均要求显式出现（未发生写 0）；三项 server-derived keys 只能由 Backend补齐。DEEP_CELL reservation/snapshot 必须恰好是这十项 role allowlist，缺失或额外/未知 key 即使值为 0也不是 telemetry fallback，而是 contract error；合法但未使用的 dimension 消耗为 0并全额释放。
- settlement 与 completion digest/execution id 绑定；exact replay 不重复 settle/release。
- 成功路径使用 `SETTLED` 表示“已记账且剩余额度已释放”；取消/未执行仍使用既有 `RELEASED` 语义，二者不能混淆。
- lifecycle 查询和 `release(id)` 的 SQL predicate 只能接受 `RESERVED`；遇到 completion-bound `SETTLED` 必须保持原样并返回 no-op/明确终态，不能二次 release。并发 complete↔cancel/reaper测试必须证明 winner 为 completion 时 ledger 永远保持 `SETTLED`。
- Redis permit token、已发生的 provider 成本不能由 MySQL rollback 退回。MA4G fake-provider E2E 只证明 reservation ledger conservation，不证明真实 provider 消耗 exactly-once。

## 10. Worker 改造

1. 新增严格 `ResearchAgentCompletionEnvelope` Pydantic contract 与 canonical digest helper。
2. `DeepCellExecutor` 不再调用 `append_workspace_evidence`/`append_candidate_batch`；它返回 evidence、candidate、usage、telemetry、trace 组成的 completion envelope。
3. `JavaResearchAgentTaskClient` 新增 `complete(envelope)`，在响应超时/连接重置等“提交结果未知”场景中保留完全相同的序列化 bytes，直接重试 `/complete`，不得重新生成 key、排序或 score。
4. consumer 的 identity guard 扩展到 snapshot digest 与 envelope digest；收到 receipt 后才 ACK Kafka。
5. 若进程在 DB commit 后、Kafka ACK 前崩溃，新消费者收到同 command 时 claim 得到 NOT_CLAIMABLE，可安全 ACK；若原进程只丢 HTTP response，same-envelope complete replay返回原 receipt。
6. deterministic fake toolchain 必须为**每个 target**生成一张独立、可在 trusted sample 精确定位的 evidence card 和一个 candidate；`evidence_key/candidate_key` 由 task id + target cell + source/window 的 SHA-256 派生为 lowercase ASCII，长度不超过物理列，禁止跨 task 复用固定 `evidence-1`。
7. 双-target fixture 的 trusted source sample 必须包含两段不同 exact quote；两张 evidence 分别落 `source_evidence`，ACCEPT 后分别落 `research_cell_evidence`，从而真实覆盖多 target lineage 和第一 CAS 后回滚，而不是让两 target 共享一张伪证据。
8. MA4G cutover 后删除 Worker 的三个旧结果写 client method和旧 execution-only submission contract。Backend 在 `/internal/research-agent/workspace-evidence-batches`、`/internal/research-agent/candidate-batches`、`/internal/research-agent-tasks/{taskId}/submit` 的共同入口先读取 server task scope；凡 snapshot-ready `INCREMENTAL_V1/DEEP_CELL` 必须在任何结果写入前稳定返回 `RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED`，不能触达旧 service primitive。非 snapshot/legacy mapping若为迁移兼容暂留，必须与该 guard及测试隔离，不能成为新 Worker fallback；claim/heartbeat等 lifecycle route不受影响。

permit 仍在每次工具调用前独立执行，不纳入 completion transaction。由于本阶段不做 heartbeat，fake fixture 的 lease 必须大于受控执行最坏时间，并继续明确禁止真实长 provider 调用。

## 11. TDD：先建立红灯

实现顺序必须以失败测试开始，禁止先改 production code 再补 happy-path 测试。

### MA4G-1：Contract 与 digest red tests

Python/Java golden vectors先失败：

1. map/list 输入顺序不同得到相同 digest；中文、反斜杠、引号、emoji/non-BMP、literal/escaped `U+2028/U+2029` 跨语言一致。
2. 修改 identity、snapshot digest、evidence 任一文本、candidate evidence set、usage 或 trace 任一字段都会改变 digest。
3. raw HTTP negative vectors 对 duplicate/NFC-colliding key、unknown field、trailing token、float/exponent、bool/null、负数/整数溢出、非法 UTF-8、unpaired surrogate、BOM、空/超长 payload、candidate 越出 target 均稳定 4xx 且零查询/零写入。
4. client response-loss retry发送 byte-for-byte 相同 body 和 idempotency header。
5. 边界长度按 code point 跨语言一致：64-char evidence/window/source key、160-char cell/candidate/execution key、300-char title以及含 emoji 的上下界分别有 accept/reject vector。
6. Flyway 红灯先证明 V044 schema 没有四个 ppm 物理列、completion aggregate 和 provenance；随后用 V045 migration让 legacy-upgrade/fresh-schema 两条路径变绿。
7. 真实 MySQL `utf8mb4_unicode_ci` probe证明大小写/重音可能被索引视为相等；HTTP contract在入库前拒绝 uppercase/non-ASCII stable key，而合法 lowercase ASCII key的边界长度可持久化且 replay equality与canonical bytes一致。

### MA4G-2：Backend atomic service red tests

新增 `ResearchAgentCompletionServiceTest`，至少覆盖：

1. 单 cell成功：execution/completion/evidence/candidate/accepted merge/cell-evidence link 各一，cell只升一版，task SUBMITTED，binding 释放，READY retry outbox CANCELLED；canonical `envelope_json/receipt_json` 可回读并验 digest。
2. 无 candidate但合法 termination：可提交 evidence-only/no-result execution，不更新 cell version，仍正确结算并释放 binding。
3. 多 cell task成功：所有目标按稳定顺序锁定并完成；receipt 顺序稳定。
4. stale owner/epoch/fence/expiry、snapshot digest、plan/entity-set、target version、Run mode/status 任一不匹配时零写入。
5. evidence未 grounding、candidate引用包外 evidence、重复 candidate cell、budget 超额/未知 dimension时零写入。
6. exact replay返回同 receipt；Run terminal/lease过期后的 exact replay仍只读成功。
7. 同 execution key 改动 envelope 任一字段得到 conflict；同 task 新 execution key得到 already committed。
8. evidence/candidate/merge stable key与历史或其他 completion冲突时完整字段检测并整包回滚。
9. 两线程 identical request竞争只有一个 completion；两线程不同 request竞争只有一个 winner且 loser conflict。
10. reservation/snapshot 恰好含十项 role dimensions；Worker自报 server dimension、任一未知 key（含 0）、snapshot/reservation漂移、任一超额均零写入。Backend派生三项计数后，最终逐 key满足 conservation，unused 非零时确实写 released；replay不二次改变 ledger。
11. 新原子行的 evidence/candidate/accepted cell 四个 ppm 列写精确整数且 legacy decimal 不参与 round-trip；REJECTED candidate 不写 cell-evidence/cell confidence。
12. exact replay完整比较持久化 canonical envelope bytes；只修改正文但伪造相同 digest的测试不得被识别为 replay。
13. cancel/reaper/release 对 completion-bound `SETTLED` 行不得改态或二次记账；只有仍为 `RESERVED` 的未执行任务能进入 `RELEASED`。
14. 真实 MySQL 两连接 barrier 测试覆盖全局锁序中的 complete/cancel/reaper/expire/claim/heartbeat/coordinator 竞争，断言无 deadlock/timeout并验证唯一合法 winner。

测试必须断言所有相关表的 before/after 摘要，而不只断言异常类型。

### MA4G-3：事务故障注入 red tests

提供仅在 test profile 可装配的 `ResearchAgentCompletionFaultInjector`，生产默认实现为空且不存在运行时开关。注入点至少包括：

```text
AFTER_LOCKS
AFTER_EXECUTION_ANCHOR
AFTER_COMPLETION_ANCHOR
AFTER_NTH_EVIDENCE
AFTER_NTH_CANDIDATE
AFTER_FIRST_CELL_CAS
AFTER_NTH_CELL_EVIDENCE
AFTER_BUDGET_FINALIZE
AFTER_TASK_TERMINAL_UPDATE
AFTER_COMMIT_BEFORE_HTTP_RESPONSE
```

除最后一个点外，每个异常都必须得到全量零差异 rollback；`AFTER_FIRST_CELL_CAS` 必须使用至少两个 target，证明第一个版本增量也被回滚。最后一个点模拟 commit 已成功但 response 丢失，随后同包 replay必须返回原 receipt且摘要不变。

### MA4G-4：Worker 与隔离 E2E red tests

1. executor只组装 envelope，不调用旧 evidence/candidate API。
2. Backend complete失败时 Kafka offset不提交；exact replay成功后只提交一次。
3. cutover 后以 snapshot-ready atomic task调用三条旧分段 endpoint，均在共同scope guard返回 `RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED`，旧service spy零调用、全表 state digest零变化；静态/架构测试同时证明Worker没有旧client method和fallback调用点。若未来删除legacy mapping，404同样满足，但不能只测不存在的task id。
4. fake executor对 task-scoped、双 target fixture产出两张不同 hashed-key evidence与两个 candidate，Backend写两组 cell-evidence lineage。
5. 真实 MySQL 8 隔离 Compose 使用新镜像依次验证 V044 legacy seed→V045 migration、baseline、多 cell CAS 中途连接/进程 kill、post-commit response loss、双 Worker/offset replay；H2/MockMvc 不能替代这些退出证据。

## 12. 隔离 Compose 验证轮次

复用 MA4F 的 project-scoped、无宿主端口、internal-only network 原则，创建独立 `noteweave-ma4g-*` project；不得复用用户当前服务或旧镜像。所有轮次必须使用真实 MySQL 8（显式记录版本、`utf8mb4_unicode_ci`、transaction isolation）和当前源码构建的 image digest。

在 G1 前先执行 migration gate：空库从 V001→V045 全量迁移；另一库只迁到 V044，插入含旧 decimal score、null provenance/ppm 的 legacy seed，再迁 V045。两条路径均查询 `flyway_schema_history` 与 `information_schema`，证明 completion 表、`envelope_json/receipt_json`、四个 ppm 列、FK/unique/index/长度/nullable/collation符合方案，legacy seed 不被改写；还要对 completion/replay/child lookup 保存真实 `EXPLAIN`。仅在 H2 或已经是 V045 的库启动成功不算 migration 证明。

### G1：原子基线

- 使用同 entity 的双 target task；fake provider为每 target生成 task-scoped ASCII hashed evidence/candidate key与不同 exact quote；
- 一 task、一 completion、一 execution，持久化完整 canonical envelope/receipt且 digest/byte length复算一致；
- 两组 evidence/candidate/merge/research_cell_evidence lineage 全部指向同 completion；
- 两个 cell 均 `VERIFIED v1`，candidate/cell/evidence ppm 物理列为预期精确整数；
- budget十项维度均 `SETTLED` 且 reserved=consumed+released，三项 server-derived计数与实际行数/verdict一致；
- outbox SENT/重试 READY 行按预期收口；Kafka lag=0、DLQ=0。

### G2：CAS 后、事务提交前崩溃

用同一双 target task，在第一个 cell CAS 与 cell-evidence insert 后触发 Backend transaction failure；另一个变体从独立连接杀死持有事务的 Backend connection/进程，证明由 MySQL rollback而非测试 mock清理。重启后以全表 state digest断言两个 cell 都保持原版本、无 evidence/candidate/cell-evidence/merge/execution/completion、budget仍 RESERVED、task可按 lifecycle 重试。新 epoch完成后每个 cell只增加一次且所有 ppm/lineage正确。

这才是对 MA4F R3 未覆盖窗口的直接证明；不能用 claim-before-write crash 结果替代。

### G3：commit 后 response 丢失

在 transaction commit后、HTTP response写出前主动断开连接，Worker重发 byte-for-byte相同 raw envelope。断言持久化 envelope bytes、receipt identity/digest一致、`idempotent_replay=true`、所有表行数和 cell/budget/ppm/lineage state digest不变、offset最终提交、lag=0、DLQ=0。该故障点必须是 test-only wiring或网络代理，生产无运行时开关。

### G4：双 Worker与 transport replay

两个 Worker同 group消费，多 partition、多 task并发，并保留两个 active consumer member的证据；完成后停 Worker、保存全库稳定 digest，将同 group offset reset到 earliest再重放。断言 replay前后 digest完全一致、每 task唯一 completion/execution、每 cell最多一次版本增量、每 ACCEPT均有cell-evidence、ppm精确、budget conservation、lag=0、DLQ=0。

每轮必须保存可复查文件而不是口头结论：Git/worktree标识、Backend/Worker image digest、`docker compose config`、MySQL版本/charset/collation/isolation、Flyway history、V044 legacy seed→V045输出、`information_schema`列/index/FK与 EXPLAIN、两独立 connection id/barrier时序、task/lease、canonical envelope/receipt JSON及digest、execution、evidence/candidate/cell-evidence/merge lineage、四个 ppm物理列、cell before/after、十维 budget、outbox、Kafka topic/partition/group/offset/lag/DLQ。每条 verifier命令必须退出 0，并用稳定 SQL（明确排序、null编码、SHA-256）比较关键表 state digest；诊断 SQL失败也视为该轮未通过。

## 13. 迁移、切换与回滚

当前 distributed Agent仍是默认关闭 canary，因此采用无存量并发写的安全切换，不为旧三段协议设计长期兼容。

### 13.1 Expand 与预检查

1. 在 fresh schema 与 V044 legacy seed 两条真实 MySQL 路径执行 additive V045 Flyway migration；历史 provenance/ppm列保持 null，旧 decimal值不改写。
2. 部署前查询所有 `CLAIMED/RUNNING/RETRY_WAIT/EXPIRED` Agent task。MA4G canary切换要求集合为空；不得让已发生旧式 partial CAS的 task进入新协议。
3. 若存在旧 active task，先停 dispatcher/consumer，按既有 lifecycle取消或在隔离环境销毁 fixture；不能通过手改 cell version“修复”。

### 13.2 Backend-first / Worker-second

1. coordinator/consumer保持关闭，Backend先部署 strict-raw `/complete`、V045 schema reader/writer和全局锁序重构。
2. 跑 raw contract、atomic/fault、真实 MySQL migration与双连接并发测试；未通过不能启动 canary。
3. 部署只会提交 completion envelope的新 Worker并删除其旧 client/contract；Backend三条旧结果 mapping若为非snapshot legacy迁移暂留，必须先装配统一 atomic-scope guard。因为预检要求无 active Agent task，不需要为新任务保留dual-protocol窗口。
4. 用真实 snapshot-ready task的HTTP/service-spy/state-digest测试证明三条旧分段路由在atomic scope不可达、零写入；仅在精确 workspace/run allowlist启动 deterministic fake canary。
5. 完成 G1–G4 后才扩大 `INCREMENTAL_V1` snapshot-ready canary；不存在把失败请求回退到旧 evidence→candidate→submit的开关、compatibility handler或长期 dual-write。

暂留的非snapshot legacy mappings只是可观测的迁移债务：标记 deprecated，按 route/task schema计数并保持生产 Deep Worker调用数为 0；当 legacy inventory与调用指标连续为 0 后物理删除 controller/service contract及测试 fixture。该清理不新增或改排阶段，后续主线仍严格是 MA4H→MA4I→MA4J→MA5→MA6，也不能把 legacy mapping 的存在计入 MA4G 能力。

### 13.3 回滚策略

- schema migration只增表/nullable列，旧 binary可忽略；不执行 down migration，不删除新 completion审计数据。
- 在首次 canary completion之前可直接回滚 application artifact。
- 一旦出现已提交 completion，回滚应用时必须同时关闭 coordinator dispatcher与 Agent consumer；不能回到已知有 CAS-after-write窗口的旧协议继续运行。
- committed completion是真源，不回写/拆分成旧 evidence→candidate→submit请求；恢复服务后由新版本 exact replay读取 receipt。
- 若 migration失败，在无业务写入前修复 forward migration；禁止手工删除 Flyway history或 drop用户表。

## 14. 可观测性、安全与性能门槛

新增 metrics：

```text
research.agent.completion.commit.total
research.agent.completion.replay.total
research.agent.completion.conflict.total{type}
research.agent.completion.rollback.total{stage}
research.agent.completion.latency
research.agent.completion.payload.bytes
research.agent.completion.cells
research.agent.budget.released{dimension}
```

日志只记录 run/task/completion/execution id、epoch/fence、digest、计数、稳定错误码；不记录 quote、candidate正文、LLM key、内部 token或完整 request body。冲突日志也只记录 expected/actual digest。

事务只包含 bounded DB/pure validation工作。首期 target≤3、evidence/candidate有严格上限；锁 cell按稳定顺序，记录 lock/transaction p95。若 payload验证昂贵，canonicalization在事务前完成，锁内只重验服务端 scope与关键 digest；不能把网络 source验证放进事务。

## 15. 实施切片与每片退出条件

### MA4G-1：Contract、digest 与 migration

- 红灯 raw-byte/golden vectors、strict parser/contract、V045、full envelope/receipt completion repository；
- fresh 与 V044 legacy seed→V045真实 MySQL migration、四个 ppm列、物理长度/collation/FK/index与旧数据兼容测试通过；
- 尚不接 Worker，不宣称窗口已关闭。

### MA4G-2：Backend atomic commit

- completion service/controller、全局锁序重构、cell-evidence lineage、完整 envelope conflict detection、server-derived budget与SETTLED终态保护；
- service/failure injection以及真实 MySQL独立连接 concurrency tests全绿；
- 旧路径仍未切断时只能标记 Backend READY。

### MA4G-3：Worker cutover

- executor产出整包、client complete/response-loss retry、consumer ACK边界更新；
- task-scoped fake evidence与双-target行为通过；Worker旧client已删除，三条旧结果路由对atomic task由统一scope guard fail-closed，HTTP/service-spy/静态测试证明不可达旧primitive且零写；
- Backend与Worker定向回归全绿。

### MA4G-4：隔离 E2E与清理

- G1–G4使用当前新镜像、真实 MySQL/Kafka全部通过，migration/并发/state digest/lag/DLQ证据文件完整；
- 删除临时兼容分支与生产可触发的 fault injector；
- 更新 MA4F状态边界、总架构和 capability coverage文档。

## 16. MA4G 最终退出门槛

只有同时满足以下条件才可把 MA4G 标记 `IMPLEMENTED / VERIFIED`：

1. Java/Python completion contract与digest golden vectors一致；emoji/non-BMP、`U+2028/U+2029`一致，raw duplicate/NFC collision、float/exponent/bool/null/overflow、非法UTF-8/unpaired surrogate/unknown/trailing payload全部在DTO绑定前fail-closed。
2. 一个 Backend transaction原子覆盖 canonical envelope+receipt、execution/completion/evidence/candidate/research_cell_evidence/merge/CAS/四个ppm物理列/budget/task/outbox/binding。
3. 每个故障注入点均有全表摘要证据；尤其第一个 cell CAS后失败能完整回滚。
4. post-commit response loss可用同一 envelope得到同一 receipt，且无第二次 cell增版、预算结算或 child row。
5. full `envelope_json` 与 receipt持久化且exact replay逐字节比较；完整幂等冲突矩阵通过，不能只凭digest/duplicate key判replay。
6. run→task→completion/execution→reservation→binary-sorted cells→stable children全局锁序已覆盖claim/heartbeat/expire/reaper/cancel/coordinator；真实 MySQL两连接barrier并发测试无deadlock/timeout且结果可线性化。
7. Worker七维+Backend三维形成完整十维结算；unused真实写released、逐维conservation成立，`SETTLED`在cancel/reaper/release竞争中不可再release。
8. fresh与V044 legacy seed→V045 migration门通过；DB长度、`utf8mb4_unicode_ci`下lowercase ASCII stable key、四个exact ppm列、FK/index/EXPLAIN均有真实MySQL证据。
9. 新镜像隔离 G1–G4通过，双-target task-scoped evidence/cell-evidence lineage成立，state digest稳定，Kafka lag=0、DLQ=0；未触碰用户现有 Compose project。
10. Worker旧分段client/fallback已删除；三条旧结果路由对真实 snapshot-ready `INCREMENTAL_V1/DEEP_CELL` task在首层scope guard稳定拒绝，路由/service-spy/state-digest测试证明旧primitive不可达且零写。非snapshot legacy mapping不得被据此表述为MA4G路径，并已登记调用归零后的物理删除门槛。
11. 文档准确保留 fake-provider、无 heartbeat、无 Run advancement、无真实 provider benchmark的边界，且后续顺序仍为 MA4H→MA4I→MA4J→MA5→MA6。

MA4G完成后可安全表述为：

> 已验证受控 fake-provider 多 Worker中，单次 Agent completion 的 evidence、candidate、canonical cell CAS、execution 与预算/任务收口在一个 Backend transaction 内原子提交；CAS-after-write故障可完整回滚，commit后响应丢失可按不可变 envelope幂等重放。

仍不得表述为：真实 provider 已上线、长调用 lease已安全、Run会自动收口、质量或延迟已优于 Table-as-Search/DeepWideSearch/MarcoAgentSearch。

### 16.1 2026-07-16 实际退出证据与声明边界

本节把本方案从设计目标更新为已完成的 MA4G 实施记录。以下轮次均使用新建的 `noteweave-ma4g-*` Compose project、internal network、零宿主端口；runner 退出为 0、manifest 为 `VERIFIED`，并在结束后核对该 project 的 container/volume/network 均为 0：

| 门槛 | 实际项目与 evidence | 已证明事实 |
| --- | --- | --- |
| LockMatrix | `noteweave-ma4g-lockmatrix-20260716t1630g` / `scripts/ma4g/evidence/noteweave-ma4g-lockmatrix-20260716t1630g-20260716T082549Z` | MySQL 8.4、READ COMMITTED、13 个 A–L/L1/L2 lock matrix case；三条物理连接的 `follower → leader → gate-holder` 等待链、无 deadlock/timeout、fixture trigger 清理。 |
| Migration | `noteweave-ma4g-migration-20260716t1640j` / `scripts/ma4g/evidence/noteweave-ma4g-migration-20260716t1640j-20260716T084018Z` | fresh 迁移允许 V046 但要求 V045 成功；legacy 严格 V044→V045，decimal、ppm/provenance、merge/cell-evidence/budget seed 保真。 |
| G1 | `noteweave-ma4g-g1-20260716t1652l` / `scripts/ma4g/evidence/noteweave-ma4g-g1-20260716t1652l-20260716T092212Z` | 单 Worker 原子 completion、receipt/envelope/child digest 重算、budget/outbox/Kafka 与全状态 digest。 |
| G2 | `noteweave-ma4g-g2-20260716t1845r` / `scripts/ma4g/evidence/noteweave-ma4g-g2-20260716t1845r-20260716T101056Z` | second-cell CAS trigger 的 named lock 精确锚定同一连接；root `KILL CONNECTION` 时已有 9 行未提交修改，pre/post-kill digest 不变，DB-clock expiry 后 epoch/fence=2 恢复。 |
| G3 | `noteweave-ma4g-g3-20260716t1900s` / `scripts/ma4g/evidence/noteweave-ma4g-g3-20260716t1900s-20260716T101537Z` | 服务端首次 commit 后代理丢响应；重试 body/idempotency byte digest 相同，replay 无新增状态。 |
| G4 | `noteweave-ma4g-g4-20260716t1930u` / `scripts/ma4g/evidence/noteweave-ma4g-g4-20260716t1930u-20260716T120713Z` | 两个 worker、16 task、32 cell、多 partition 与 offset reset 后重放；aggregate/verifier、lag/DLQ、全状态不变量通过。 |

实际 child 写入顺序是 `source_evidence → candidate → merge → cell_evidence`：completion root 已先携带完整 receipt，merge 先获得 stable server id，随后 lineage 可引用同一 completion/candidate/merge。这与上面的全局锁序一致；exact replay 由 completion anchor 直接返回，不能以先写 cell-evidence 取代 merge 审计。

补充回归：`workers/research-worker/tests` 为 `320 passed`；`com.noteweave.research.*Test` 为 `133 tests, 0 failure, 0 error, 1 environment-conditioned skip`；MA4G static gate（PowerShell/Python/Shell/Compose）通过。这些代码回归与上述真实 MySQL/Kafka 退出门相互补充，但仍不替代真实 provider、长期 lease、Run advancement 或生产灰度证明。

## 17. 后续阶段建议

### MA4H：Heartbeat、drain 与长执行安全

- 独立 heartbeat loop，周期小于 lease/3；
- provider调用中的 cancel/lease-loss传播；
- graceful drain与 shutdown，不在失去 fence后继续构造 completion；
- 短 lease、慢 fake provider、Worker kill和Backend暂时不可用的故障矩阵。

### MA4I：Run/Wave advancement、Checkpoint 与 Repair

- all task terminal barrier、下一轮 deterministic taskization；
- checkpoint high-water、budget/run summary、停止条件；
- outcome-aware repair task、停止条件与显式 PARTIAL/FAILED 收口；
- coordinator多实例lease/CAS和重启恢复。

### MA4J：SYNTHESIS、报告持久化与终态收口

- all-cell terminal barrier 后只创建一个幂等 SYNTHESIS task；
- citation/global verifier gate 通过后原子持久化 final report、`research_run.COMPLETED` 与父 `task.COMPLETED`；
- response loss、重复 command 和 coordinator 重启不得生成第二份报告或重复终态事件。

真实 Search/Fetch/LLM provider、外部快照可信入库和固定 gold set A/B 延后到 MA5；5%→20% 生产灰度、SLO 与自动回退延后到 MA6。MA4G–J 均不得用 deterministic fake fixture 代替这些外部证明。
