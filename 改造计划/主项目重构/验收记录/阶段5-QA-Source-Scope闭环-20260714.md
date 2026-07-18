# 阶段 5 QA Source Scope 闭环验收

- 日期：2026-07-14
- 后端测试：172/172
- 状态：QA 显式 Source scope 已贯通请求、RetrievalPlan、ES/MySQL 检索、EvidenceBundle 与 citation 持久化

## 问题与边界

- `AnswerContext` 已定义 `sourceScope`，但此前 `ChatService` 固定传入空集合；
- `QaAnswerModeStrategy` 的 plan 只声明 Workspace 和 active snapshot，没有声明 Source 集合；
- `QaPassageEvidenceRetriever/QaPassageRetriever` 未消费 `sourceScope`；
- 因此旧链路只保证 Workspace 隔离，无法表达“仅使用用户选择的几份资料”；
- citation assembler 虽只持久化 bundle 内证据，但如果检索阶段未过滤 scope，citation 仍可能来自未选择 Source。

## 完成内容

- `SendMessageRequest` 新增可选 `source_scope_source_ids`，最多 50 个、单个 ID 最长 36 字符，并去重、去除首尾空白；
- 未传或传空列表时保持原 Workspace 全量检索行为；
- `ChatService` 将请求集合写入 `AnswerContext.sourceScope`；
- 当前显式 scope 只支持 QA，NOTE/WIKI 携带时返回 `ANSWER_SOURCE_SCOPE_UNSUPPORTED`，避免静默忽略；
- `QaAnswerModeStrategy` 在 scope 非空时写入稳定排序的 `RetrievalPlan.filters.source_ids`；
- QA 可见回答明确显示“用户显式选择的 N 份资料”，无 scope 时保留原提示；
- `QaPassageEvidenceRetriever` 将 scope 传入专用 Retriever；
- Elasticsearch 命中在 provenance hydrate 和来源多样性选择前过滤，只 hydrate 允许 Source；
- MySQL fallback 使用参数化 `s.id in (...)` 下推过滤，并对返回候选再次执行内存防御过滤；
- 最终 EvidenceBundle、可见回答和 citation 持久化均只能包含允许 Source。

## 验证

- `QaAnswerModeStrategyTest`：固定 plan 中排序后的 Source IDs 和 scope 可见提示；
- `QaPassageEvidenceRetrieverTest`：固定 `AnswerContext.sourceScope` 向专用 Retriever 透传；
- `QaPassageRetrieverTest`：固定 ES 命中先过滤后 hydrate，以及 MySQL 条件下推与二次过滤；
- `Phase1And2ContractTest`：上传允许/未允许两份资料，验证 SSE 正文不包含未允许证据，且 `message_citation` 只关联允许 Source；
- 同一端到端测试验证 NOTE 携带 scope 返回 `ANSWER_SOURCE_SCOPE_UNSUPPORTED`；
- QA scope 定向回归：17/17，0 failures，0 errors，0 skipped；
- `mvnw.cmd -f backend/pom.xml clean test`：48 份 Surefire 报告，172/172，0 failures，0 errors，0 skipped；
- `ArchitectureBoundaryTest`：13/13；
- `git diff --check` 与相关新增代码 trailing whitespace 检查：通过。

## 兼容性与后续

- 当前前端未发送该可选字段，因此现有 UI 和历史客户端行为不变；
- 本轮没有更换 QA 排序、来源多样性、回答模板主体或 citation assembler 算法，也未提升 `qa-passage-v1`；
- 后续若 Note/Wiki 需要显式 Source scope，应分别定义模式语义和测试后再解除明确拒绝；
- 真实 QA gold 草稿和 Elasticsearch shadow capture 应包含 scope case，门禁必须保持 scope violation 为 0。
