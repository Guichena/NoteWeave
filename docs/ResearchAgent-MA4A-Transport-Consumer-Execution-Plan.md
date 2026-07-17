# Research Agent MA4A：Agent Command Consumer 收口执行方案

> 状态：IMPLEMENTED / VERIFIED（2026-07-15）  
> 阶段：MA4A（Transport contracts 与 Worker client 收口）  
> 前置：MA3A–D、MA4A command contract / outbox / task client 已存在  
> 非目标：真实 Search→Fetch→Read→Extract executor、lease reaper、Redis 限流、生产灰度

## 1. 问题与当前边界

Backend 已经可为 `INCREMENTAL_V1` 的 ready task 写入 `research_agent_outbox` 并向 `noteweave.research.agent.command` 发布最小 identity command。Worker 已有严格 command/result Pydantic contract、HTTP claim/heartbeat/submit client，以及单条 `handle_research_agent_command` 驱动。

但它尚不能作为独立可靠 consumer 运行：没有专属 topic/group/DLQ 配置，没有批量消费/offset 语义，没有针对 poison message 的持久化 DLQ 接线，也没有 Compose 服务隔离。直接把它接到 legacy `app.kafka_consumer` 会让顶层 research-run 命令和 agent-task 命令混用 group、topic 和失败语义，违反模式隔离。

本阶段只建立可靠的**命令传输外壳**。默认仍不启动 agent consumer；没有 role executor 时，任何部署配置都不得偷偷领取真实 agent task。

## 2. 目标与不变量

1. Agent command 使用独立 topic、consumer group、DLQ topic 和 feature flag，默认关闭。
2. 原始 Kafka value 先以 bytes/string 读取；JSON/Pydantic 失败发生在 claim 前，成功写 DLQ 后才 commit offset。
3. 一条 command 的处理顺序固定为 `validate → claim → executor → identity verify → submit → commit`。
4. `submit` 成功或成功 DLQ 后才提交 offset；DLQ 发布失败时抛出，停止在未确认 offset 前，不能越过 poison 消息。
5. 可重试的网络/临时 provider 失败在本地有限重试后才 DLQ；contract/identity failure 不重试。
6. 发生 claim race、任务已完成、已取消或 stale lease 时不提交伪造 execution；需要有显式“正常跳过”分类并安全 commit。
7. production loop 只接受显式注入 executor；默认 factory 抛出安全错误，避免把 MA4A 演练结果误当研究结果。
8. 复用 legacy `KafkaDeadLetterSink`、错误脱敏和 broker ack 语义，不复制第二份不一致的实现。

## 3. 拟改动

| 文件 | 改动 |
| --- | --- |
| `app/config.py` | 新增 `kafka_research_agent_topic`、`kafka_research_agent_dlq_topic`、`kafka_research_agent_group_id`、`research_agent_consumer_enabled`、worker instance/lease/retry 配置；默认 disabled |
| `app/agent_kafka_consumer.py` | 新增 iterable consumer、摘要、retry 分类、DLQ、Kafka factories 和显式 disabled main；保持单条 handler API |
| `app/agent_task_client.py` | 仅在必要时将不可 claim/不可提交 HTTP 响应分类为稳定异常；不传递敏感响应正文 |
| `.env.example` | 文档化独立 agent topic/group/DLQ 与默认关闭开关 |
| `docker-compose.yml` | 增加**不默认启动**的 `research-agent-worker-consumer` profile/service，使用独立 env；不改 legacy consumer |
| `tests/test_ma4_agent_kafka_consumer.py` | 先增加红灯测试：schema poison、DLQ 失败不 commit、retry、正常 skip、settings/factory/main guard |
| `ResearchAgent-MA4-Distributed-Execution-Plan.md` | 记录 MA4A 收口完成证据与仍未完成事项 |

## 4. TDD 测试清单

### 红灯测试

1. `extra field` command 触发 Pydantic failure，`claims == []`，DLQ 成功后 commit 一次。
2. DLQ publisher 失败时抛 `DeadLetterPublishError`，不 commit，也不处理下一消息。
3. `TimeoutError` 类 executor 失败按 max attempts 重试，耗尽后 DLQ，之后才 commit。
4. 明确的 `AgentTaskNotClaimable`（已完成/被其他 worker 领取/取消）不 submit、不 DLQ，作为 skipped 后 commit。
5. executor identity 不匹配是不可重试的 contract failure，DLQ 后 commit。
6. Kafka consumer factory 使用 agent topic/group 且关闭 auto commit；DLQ factory 使用 agent DLQ topic。
7. `run_research_agent_kafka_consumer_forever` 在开关关闭时 fail-fast，不创建 consumer；开关开启但未显式提供 executor factory 时 fail-fast。

### 绿灯与回归

* 原有 `test_ma4_agent_command_contract.py`、`test_ma4_agent_task_client.py`、legacy `test_kafka_consumer.py` 全绿；
* 新 agent consumer 测试全绿；
* `docker compose --profile app config --quiet` 成功，且默认 app profile 不会启动 agent consumer；
* `git diff --check` 通过。

## 5. 错误分类

| 类型 | 例子 | retry | DLQ | commit |
| --- | --- | --- | --- | --- |
| Contract poison | JSON、schema、extra field | 否 | 是 | 仅 DLQ ack 后 |
| Identity violation | executor task/worker/epoch/fence 不匹配 | 否 | 是 | 仅 DLQ ack 后 |
| 正常 claim 竞态 | 已完成、取消、其他 worker 有 lease | 否 | 否 | 是，记录 skipped |
| 临时失败 | timeout、connection、5xx、429 | 有限 | 耗尽后 | 成功或 DLQ ack 后 |
| DLQ publish failure | broker 不可用 | 否 | 未成功 | 否，停止消费 |

本阶段不把任意 `RuntimeError` 一概当作可重试。稳定业务错误应通过专用异常或可验证状态码分类，避免无效重试或把正常 claim 竞态污染到 DLQ。

## 6. 回滚与发布

所有新增配置默认关闭；新 Compose 服务只使用 `agent` profile，不改变 `research-worker-consumer`。若发现 DLQ、offset 或 claim 语义异常：停掉 agent profile，保留 outbox/task/execution 审计；默认 `SEQUENTIAL_V1` 和 existing `LOCAL_PARALLEL` 不受影响。

MA4A 完成后仍只能表述为“可配置、可测试的受控 agent command consumer transport”，不得称为分布式 Research 执行；MA4D 的真实 executor 通过后才可领取真实研究任务。

## 7. 实施记录（2026-07-15）

已完成：

1. `app.agent_kafka_consumer` 已提供独立 iterable consumer、专属 Kafka factory/DLQ factory、严格 validate→claim→executor→identity check→submit→commit 顺序，以及 schema poison、DLQ ack、临时失败、claim race 的分类。
2. `app.agent_task_client` 把服务端 `RESEARCH_AGENT_TASK_NOT_CLAIMABLE` 分类为稳定异常，只保留 API code，避免把内部 response body 写进 Worker 错误消息。
3. Backend `ResearchAgentTaskService.claimTask` 对同 worker、未过期 `CLAIMED/RUNNING` lease 做幂等 replay，不增加 attempt/epoch/fencing；不同 worker 仍返回不可领取。
4. Settings、`.env.example` 和 Compose 的两个现有 Research Worker 服务均显式携带 agent topic/DLQ/group/lease/feature-flag 配置，默认 consumer 关闭。

验证证据：Worker 定向测试 `31 passed`；Backend `ResearchAgentTaskServiceTest` 在 `-Dspring.kafka.listener.auto-startup=false` 下通过；`mvnw -DskipTests test-compile`、`docker compose --profile app config --quiet` 与 `git diff --check` 通过。

有意保留的边界：没有真实 executor 时未新增可运行的 agent-consumer Compose service，防止一个 deployment 配置误领取真实 task 后提交空/伪造结果。该服务将在 MA4D 的 executor factory 与 result contract 一同加入。
