# 阶段 5：QA 显式降级与 Fallback 可观测性验收

- 日期：2026-07-14
- 范围：QA 主检索失败、无可接纳命中、ownership 拒绝与 MySQL lexical fallback
- 算法约束：保留原 MySQL 查询、关键词评分、排序和来源多样性选择结果

## 原缺口

阶段计划明确禁止把 MySQL LIKE fallback 当作与 Elasticsearch 等价的静默降级。但原 `QaPassageRetriever` 只返回 `List<RetrievedChunk>`：

- ES 关闭、无命中、client 缺失、请求异常或 ownership 全部拒绝后都会进入 MySQL；
- `QaPassageEvidenceRetriever` 始终返回 `EvidenceRetrievalResult.success`；
- AnswerRun、trace 和 Micrometer 无法区分主检索结果与 fallback 结果；
- ES adapter 会把已启用状态下的 client 缺失和请求异常吞成空列表。

## 已完成实现

### Retrieval diagnostics

`QaPassageRetriever.retrieveWithDiagnostics` 在不改变结果列表的前提下返回：

- `degraded`；
- 稳定原因码：`qa_primary_no_scoped_hits`、`qa_primary_search_error`、`qa_primary_ownership_rejected`、`qa_mysql_fallback`；
- measurements：`primary_hit_count`、`scoped_primary_hit_count`、`ownership_rejected_count`、`mysql_fallback_used`、`mysql_candidate_count`、`selected_count`。

旧 `retrieve` 方法继续返回相同 `chunks`，保证调用兼容与算法不变。

### 统一传播

- `QaPassageEvidenceRetriever` 把 diagnostics 写入 `EvidenceRetrievalResult`；
- `RetrievalOrchestrator` 复用既有机制写入 EvidenceBundle、step trace 和低基数 Micrometer 指标；
- `retrieval.summary` 顶层新增 `degraded` 与 `degradation_reasons`，step measurements 同时保留数值路径；
- 原因码不拼接异常消息、query、workspace/source/chunk ID。

### ES 读适配器边界

- ES 显式关闭或 `topK<=0`：保持返回空候选；
- ES 已启用但 client 不存在：抛出稳定 `IllegalStateException`；
- ES 请求失败：抛出稳定包装异常；
- QA 策略捕获上述异常后继续执行原 MySQL fallback，并记录 `qa_primary_search_error`；离线 CLI/benchmark 不再把基础设施失败伪装成正常空结果。

## 自动化证据

- `QaPassageRetrieverTest`：主通道部分 ownership 拒绝、全部拒绝、空命中 fallback、显式主检索异常 fallback；原 keyword score 与 source diversity 顺序保持不变。
- `QaPassageEvidenceRetrieverTest`：degraded/reasons/measurements 进入统一检索结果。
- `ElasticsearchChunkSearchAdapterTest`：显式关闭返回空；client 缺失和请求异常向调用策略暴露。
- `RetrievalOrchestratorTest`：degraded result 进入 bundle、trace、`noteweave.retrieval.step.latency{degraded=true}` 与 `noteweave.retrieval.bundle.degraded`。
- `Phase1And2ContractTest`：测试 Profile 关闭 ES 时仍生成原 QA 回答，同时 `retrieval.summary.degraded=true`、包含 `qa_mysql_fallback`，step measurement 的 `mysql_fallback_used=1`。
- 完整命令 `./mvnw.cmd -f backend/pom.xml test`：`213/213` 通过，0 failure、0 error、0 skipped；Flyway 32 个迁移通过；`ArchitectureBoundaryTest` 13/13。
- `git diff --check` 与本轮 Java trailing whitespace 检查通过。

## 数据边界

该验收证明 fallback 不再静默、事件和指标可区分执行路径，不证明 MySQL fallback 与 ES 质量等价。真实质量门禁仍需基于人工 REVIEWED 数据与多轮 shadow 校准。
