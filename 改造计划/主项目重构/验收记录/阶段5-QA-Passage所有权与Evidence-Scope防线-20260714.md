# 阶段 5 QA Passage 所有权与 Evidence Scope 防线验收

- 日期：2026-07-14
- 后端测试：199/199
- 错误码：`EVIDENCE_SCOPE_VIOLATION`
- 指标：`noteweave.retrieval.scope_violation{mode,channel}`
- 状态：QA ES hit ownership 与统一 Evidence scope 已 fail closed；原召回、评分和 fallback 算法未改

## 原问题

QA 主检索链路从 workspace 专属 Elasticsearch index 读取 hit 后，会调用 `hydrateSourceProvenance(workspaceId, sourceIds)`：

- SQL 确实按 workspace 过滤 Source；
- 但 hydrate 缺失时，旧代码使用空 `generatedBy/generatedRefId` 的默认 provenance，仍把 hit 接纳为 evidence；
- 因此损坏、错误回填或跨 workspace 的 ES 文档可能绕过第二次数据库所有权复核；
- 旧复核只验证 Source ID，没有校验 hit 的 chunk ID、source snapshot ID 是否真的属于该 Source/workspace；
- `EvidenceBundle.accessScope` 由各 retriever 自行填写，Orchestrator 没有统一校验；
- 显式 QA Source scope 主要依赖 `QaPassageRetriever` 过滤，缺少对未来 retriever/adapter 的最后一道通用防线。

## Passage Ownership 完成内容

- `RetrievalHydrator.hydrateSourceProvenance` 收敛为 `hydratePassageOwnership`；
- 输入从 Source IDs 改为 ES hit chunk IDs；
- 单次 SQL join：
  - `source_chunk`；
  - `source`，并要求 source/workspace 一致；
  - `source_snapshot`，并要求 snapshot/source 一致；
- SQL 必须同时满足：
  - `c.workspace_id = requested workspace`；
  - `s.status = READY`；
  - `s.index_status = INDEXED`；
  - `ss.index_status = INDEXED`；
  - `c.projection_status = PROJECTED`；
- 返回以 chunk ID 为 key 的 `PassageOwnership`，包含数据库真源中的 source ID、snapshot ID 与生成来源 provenance；
- `QaPassageRetriever` 只接纳：
  - chunk ownership 存在；
  - hit source ID 与数据库 source ID 一致；
  - hit snapshot ID 与数据库 snapshot ID 一致；
- 被拒绝的 hit 不进入来源多样性选择；日志只输出拒绝数量；
- 若仍存在合法 ES hit，继续保持原 BM25 score、source diversity 与最多 6 条 evidence 行为；
- 若所有 ES hit 被拒绝，继续进入原 MySQL fallback，其 SQL 已按 workspace、READY 和显式 Source scope 约束。

## Evidence Scope Guard

新增 `EvidenceScopeGuard`，由 `RetrievalOrchestrator` 在 channel candidate limit 后、全局 evidence budget 前执行：

- `PASSAGE` evidence 必须具有非空 source ID、snapshot ID 与 passage ID；
- Passage access scope 必须精确等于 `workspace-source:<sourceId>`；
- QA 有显式 Source scope 时，Passage source ID 必须属于该 scope；
- `KNOWLEDGE_VERSION` evidence 必须具有非空 item ID 与 version ID；
- Knowledge access scope 必须精确等于 `workspace-knowledge:<itemId>`；
- 未知 evidence kind、空 evidence 或 identity/scope 不一致均明确失败；
- 非 QA plan 携带 Source scope 时明确失败，避免其他模式静默解释 QA scope；
- 失败使用 `EVIDENCE_SCOPE_VIOLATION`，不允许异常 evidence 继续进入 Prompt 或 citation assembler；
- 同时增加 `noteweave.retrieval.scope_violation` counter，tag 仅包含 mode/channel。

## 验证

- `QaPassageRetrieverTest`：6/6；
  - 显式 Source scope 在 hydrate 前过滤；
  - 100 个 ES hit 仍只进行一次 ownership hydrate；
  - 不属于 workspace 的 chunk 被拒绝并进入安全 fallback；
  - source ID 不一致、snapshot ID 不一致的 hit 被拒绝；
  - 合法 hit 仍保持原分数和来源多样性行为；
  - MySQL fallback 的 scope SQL 下推与内存二次过滤保持通过；
- `RetrievalHydratorTest`：3/3；
  - 100 个 chunk ID ownership hydrate 固定为一次 SQL；
  - ID 去重和 Note 两类 hydrate 常数查询保持通过；
- `RetrievalOrchestratorTest`：6/6；
  - 显式 Source scope 越权 evidence 触发固定错误码；
  - identity 与 accessScope 不一致触发固定错误码；
  - scope violation counter 精确增加；
  - 原候选/字符预算、trace 和 missing channel 降级测试保持通过；
- `QaPassageEvidenceRetrieverTest`：1/1；
- `Phase1And2ContractTest`：10/10；
- `ArchitectureBoundaryTest`：13/13；
- 定向回归：39/39；
- `mvnw.cmd -f backend/pom.xml clean test`：55 份 Surefire 报告，199/199，0 failures，0 errors，0 skipped。

## 安全与后续边界

- Elasticsearch index-by-workspace 仍是第一层 tenant boundary；JDBC exact ownership 是第二层；EvidenceScopeGuard 是进入 bundle 前的第三层；
- 当前复核不逐 hit 读取正文，仍保持一次批量 SQL，不重新引入 N+1；
- ES content/title 的一致性继续由可靠索引投影负责；本切片验证数据库身份与状态，不把 ES 退化成逐条 MySQL 正文读取；
- 后续 vector 或 rerank channel 必须返回相同 Passage identity/accessScope，并自动经过同一 Guard；
- 真实 gold/shadow 数据仍需独立人工标注，安全闭环不能替代质量评测。
