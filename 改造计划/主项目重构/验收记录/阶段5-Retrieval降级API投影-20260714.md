# 阶段 5：Retrieval 降级 API 投影验收

- 日期：2026-07-14
- 范围：发送消息响应、AnswerRun get/list snapshot、前端响应类型
- 真源：本轮内存 EvidenceBundle 与持久化 `answer_run.evidence_bundle_json`

## 原缺口

QA fallback 已进入 `retrieval.summary`、step trace 和 Micrometer，但普通调用方仍需额外订阅事件才能知道本轮是否降级：

- `SendMessageResponse` 没有 degraded 字段；
- `AnswerRunResponse` 没有持久化 degraded 投影；
- 前端发送响应类型无法消费该状态。

这不满足阶段计划“响应/指标必须标记”的完整边界。

## 已完成实现

- `SendMessageResponse` 新增 `retrievalDegraded` 与 `retrievalDegradationReasons`，直接来自已经完成策略校验的 EvidenceBundle。
- `AnswerRunResponse` 新增同名字段；get/list SQL 读取 `evidence_bundle_json`，统一反序列化 `evidence-bundle-snapshot-v1` 后投影。
- 未新增数据库列或第二套状态；旧 AnswerRun 没有 snapshot 时返回 `retrievalDegraded=null`、原因数组为空。
- snapshot JSON 损坏时 fail fast，不把不可读审计数据静默解释为“未降级”。
- React 发送响应类型同步增加 snake_case 字段，避免客户端契约漂移。

## 自动化证据

- `Phase1And2ContractTest`：测试 Profile 显式关闭 ES，发送 QA 消息仍按原 MySQL fallback 生成答案；创建响应与 AnswerRun GET 均返回 `retrieval_degraded=true`，原因包含 `qa_mysql_fallback`。
- `Phase3NoteWikiContractTest`：Note/Wiki 纵向链路继续通过，新增字段未改变三模式正文、citation 或版本行为。
- 针对性 Backend：38/38 通过。
- 完整 Backend：`213/213` 通过，0 failure、0 error、0 skipped；Flyway 32 个迁移；`ArchitectureBoundaryTest` 13/13。
- Frontend：Vitest 12 files / 45 tests 通过；`npm run ui:check` 通过；`npm run build` 通过。
- `git diff --check` 与本轮 Java/TypeScript trailing whitespace 检查通过。

## 数据与兼容边界

- 字段为 additive contract；旧客户端可忽略。
- 降级原因只允许稳定原因码，不允许正文、query、异常消息或资源标识符。
- 该字段说明执行路径发生降级，不声称 fallback 质量达到 Elasticsearch 等价水平。
