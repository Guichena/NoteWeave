# 可观测 Eval Admin Ops

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 本篇定位
AI 系统面试最能拉开差距的是坏答案怎么查、效果怎么评、失败任务怎么恢复、资源残留怎么清。

## 1. 面试先说版
可观测和运维我会讲成 NoteWeave 的成熟度。一次坏答案出来后，管理员不应该只看用户反馈，而是能沿着 PromptVersion、LLMCallLog、RetrievalTrace、Citation 和 AnswerFeedback 去反查：当时用的 prompt 版本是什么，召回了哪些 chunk，证据是否被截断，LLM 调用是否失败。RAG Eval 通过 rag_eval_case、run、result 和 RAG_EVAL_RUN 任务跑隔离评测，不污染正式会话和 Memory。Admin/Ops 还覆盖用户禁用、空间查询、任务取消重试、cleanup scan/execute、组件健康、audit log。面试里重点不是说已有生产指标，而是说这些埋点和管理入口让后续指标验证有地方落。

## 2. 当前真实口径
NoteWeave 已经有 PromptVersion、LLMCallLog、RetrievalTrace、AnswerFeedback、RagEval、Admin Task、cleanup、health、audit 等运营面能力。

### 已实现
- AdminObservabilityController 提供 prompt versions、LLM logs、retrieval traces。
- AdminRagEvalController 提供 eval cases、eval runs、results。
- AdminManagementController 提供用户、空间、任务、事件、retry/cancel/mark-failed。
- AdminOpsController 提供 cleanup scan/execute/jobs、health、dashboard、audit logs。
- Phase14ObservabilityEvaluationIntegrationTest 和 Phase15AdminOpsIntegrationTest 覆盖核心场景。

### 设计目标
- AdminObservabilityController 管 prompt/log/trace，AdminRagEvalController 管 eval case/run/result，AdminManagementController 管用户、空间和任务，AdminOpsController 管 cleanup、health、dashboard、audit。
- 它能把 AI 应用从功能演示推进到可排障、可验收、可管理的工程系统。

### 后续可扩展
- 如果用户说引用不准，先看 trace 还是 citation？
- 如果任务重试一直失败，Admin 怎么介入？
- 如果没有生产指标，怎么诚实说明效果？

## 3. 代码和测试锚点
- src/main/java/com/noteweave/admin/controller/AdminObservabilityController.java
- src/main/java/com/noteweave/admin/controller/AdminRagEvalController.java
- src/main/java/com/noteweave/admin/controller/AdminManagementController.java
- src/main/java/com/noteweave/admin/controller/AdminOpsController.java
- src/main/java/com/noteweave/rageval/service/RagEvaluationService.java
- src/test/java/com/noteweave/admin/Phase14ObservabilityEvaluationIntegrationTest.java

## 4. 必会问题与答题骨架

### Q1: 一次坏答案如何排查？

回答时按四步走：
1. 先说场景：AI 系统面试最能拉开差距的是坏答案怎么查、效果怎么评、失败任务怎么恢复、资源残留怎么清。
2. 再说方案：AdminObservabilityController 管 prompt/log/trace，AdminRagEvalController 管 eval case/run/result，AdminManagementController 管用户、空间和任务，AdminOpsController 管 cleanup、health、dashboard、audit。
3. 再说收益：它能把 AI 应用从功能演示推进到可排障、可验收、可管理的工程系统。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可观测和运维我会讲成 NoteWeave 的成熟度。一次坏答案出来后，管理员不应该只看用户反馈，而是能沿着 PromptVersion、LLMCallLog、RetrievalTrace、Citation 和 AnswerFeedback 去反查：当时用的 prompt 版本是什么，召回了哪些 chunk，证据是否被截断，LLM 调用是否失败。RAG Eval 通过 rag_eval_case、run、result 和 RAG_EVAL_RUN 任务跑隔离评测，不污染正式会话和 Memory。Admin/Ops 还覆盖用户禁用、空间查询、任务取消重试、cleanup scan/execute、组件健康、audit log。面试里重点不是说已有生产指标，而是说这些埋点和管理入口让后续指标验证有地方落。

常见追问：
- 如果用户说引用不准，先看 trace 还是 citation？
- 如果任务重试一直失败，Admin 怎么介入？
- 如果没有生产指标，怎么诚实说明效果？

### Q2: PromptVersion 为什么重要？

回答时按四步走：
1. 先说场景：AI 系统面试最能拉开差距的是坏答案怎么查、效果怎么评、失败任务怎么恢复、资源残留怎么清。
2. 再说方案：AdminObservabilityController 管 prompt/log/trace，AdminRagEvalController 管 eval case/run/result，AdminManagementController 管用户、空间和任务，AdminOpsController 管 cleanup、health、dashboard、audit。
3. 再说收益：它能把 AI 应用从功能演示推进到可排障、可验收、可管理的工程系统。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可观测和运维我会讲成 NoteWeave 的成熟度。一次坏答案出来后，管理员不应该只看用户反馈，而是能沿着 PromptVersion、LLMCallLog、RetrievalTrace、Citation 和 AnswerFeedback 去反查：当时用的 prompt 版本是什么，召回了哪些 chunk，证据是否被截断，LLM 调用是否失败。RAG Eval 通过 rag_eval_case、run、result 和 RAG_EVAL_RUN 任务跑隔离评测，不污染正式会话和 Memory。Admin/Ops 还覆盖用户禁用、空间查询、任务取消重试、cleanup scan/execute、组件健康、audit log。面试里重点不是说已有生产指标，而是说这些埋点和管理入口让后续指标验证有地方落。

常见追问：
- 如果用户说引用不准，先看 trace 还是 citation？
- 如果任务重试一直失败，Admin 怎么介入？
- 如果没有生产指标，怎么诚实说明效果？

### Q3: Eval run 为什么不能污染正式 ChatSession？

回答时按四步走：
1. 先说场景：AI 系统面试最能拉开差距的是坏答案怎么查、效果怎么评、失败任务怎么恢复、资源残留怎么清。
2. 再说方案：AdminObservabilityController 管 prompt/log/trace，AdminRagEvalController 管 eval case/run/result，AdminManagementController 管用户、空间和任务，AdminOpsController 管 cleanup、health、dashboard、audit。
3. 再说收益：它能把 AI 应用从功能演示推进到可排障、可验收、可管理的工程系统。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可观测和运维我会讲成 NoteWeave 的成熟度。一次坏答案出来后，管理员不应该只看用户反馈，而是能沿着 PromptVersion、LLMCallLog、RetrievalTrace、Citation 和 AnswerFeedback 去反查：当时用的 prompt 版本是什么，召回了哪些 chunk，证据是否被截断，LLM 调用是否失败。RAG Eval 通过 rag_eval_case、run、result 和 RAG_EVAL_RUN 任务跑隔离评测，不污染正式会话和 Memory。Admin/Ops 还覆盖用户禁用、空间查询、任务取消重试、cleanup scan/execute、组件健康、audit log。面试里重点不是说已有生产指标，而是说这些埋点和管理入口让后续指标验证有地方落。

常见追问：
- 如果用户说引用不准，先看 trace 还是 citation？
- 如果任务重试一直失败，Admin 怎么介入？
- 如果没有生产指标，怎么诚实说明效果？

### Q4: cleanup scan 和 execute 为什么拆开？

回答时按四步走：
1. 先说场景：AI 系统面试最能拉开差距的是坏答案怎么查、效果怎么评、失败任务怎么恢复、资源残留怎么清。
2. 再说方案：AdminObservabilityController 管 prompt/log/trace，AdminRagEvalController 管 eval case/run/result，AdminManagementController 管用户、空间和任务，AdminOpsController 管 cleanup、health、dashboard、audit。
3. 再说收益：它能把 AI 应用从功能演示推进到可排障、可验收、可管理的工程系统。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可观测和运维我会讲成 NoteWeave 的成熟度。一次坏答案出来后，管理员不应该只看用户反馈，而是能沿着 PromptVersion、LLMCallLog、RetrievalTrace、Citation 和 AnswerFeedback 去反查：当时用的 prompt 版本是什么，召回了哪些 chunk，证据是否被截断，LLM 调用是否失败。RAG Eval 通过 rag_eval_case、run、result 和 RAG_EVAL_RUN 任务跑隔离评测，不污染正式会话和 Memory。Admin/Ops 还覆盖用户禁用、空间查询、任务取消重试、cleanup scan/execute、组件健康、audit log。面试里重点不是说已有生产指标，而是说这些埋点和管理入口让后续指标验证有地方落。

常见追问：
- 如果用户说引用不准，先看 trace 还是 citation？
- 如果任务重试一直失败，Admin 怎么介入？
- 如果没有生产指标，怎么诚实说明效果？

### Q5: health 为什么要按 MySQL/Redis/MinIO/Kafka/ES/LLM 拆？

回答时按四步走：
1. 先说场景：AI 系统面试最能拉开差距的是坏答案怎么查、效果怎么评、失败任务怎么恢复、资源残留怎么清。
2. 再说方案：AdminObservabilityController 管 prompt/log/trace，AdminRagEvalController 管 eval case/run/result，AdminManagementController 管用户、空间和任务，AdminOpsController 管 cleanup、health、dashboard、audit。
3. 再说收益：它能把 AI 应用从功能演示推进到可排障、可验收、可管理的工程系统。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可观测和运维我会讲成 NoteWeave 的成熟度。一次坏答案出来后，管理员不应该只看用户反馈，而是能沿着 PromptVersion、LLMCallLog、RetrievalTrace、Citation 和 AnswerFeedback 去反查：当时用的 prompt 版本是什么，召回了哪些 chunk，证据是否被截断，LLM 调用是否失败。RAG Eval 通过 rag_eval_case、run、result 和 RAG_EVAL_RUN 任务跑隔离评测，不污染正式会话和 Memory。Admin/Ops 还覆盖用户禁用、空间查询、任务取消重试、cleanup scan/execute、组件健康、audit log。面试里重点不是说已有生产指标，而是说这些埋点和管理入口让后续指标验证有地方落。

常见追问：
- 如果用户说引用不准，先看 trace 还是 citation？
- 如果任务重试一直失败，Admin 怎么介入？
- 如果没有生产指标，怎么诚实说明效果？

## 5. 大厂深挖追问路径
1. 先问你做了什么。
2. 再问为什么这样设计，不用更简单方案。
3. 再问失败、重试、越权、删除、断线、重建索引时会发生什么。
4. 最后问如何量化效果和下一步演进。

把答案往下压一层：
- 业务层：AI 系统面试最能拉开差距的是坏答案怎么查、效果怎么评、失败任务怎么恢复、资源残留怎么清。
- 架构层：AdminObservabilityController 管 prompt/log/trace，AdminRagEvalController 管 eval case/run/result，AdminManagementController 管用户、空间和任务，AdminOpsController 管 cleanup、health、dashboard、audit。
- 数据层：引用 MySQL、Redis、MinIO、ES、Kafka 或 Citation/Trace 的真实职责。
- 测试层：能说出对应 IntegrationTest 或 ServiceTest。
- 边界层：明确哪些是后续扩展，不冒充已落地。

## 6. 不能说满的地方
- 不要编造生产准确率。
- 不要说 Eval 等价于线上 A/B。
- 不要让普通用户访问全局运维日志。

## 7. 零基础记忆法
记住一句话：先讲“为什么需要这个模块”，再讲“请求从哪里来、状态落在哪里、失败怎么恢复、证据怎么追踪、权限怎么兜底”。按这个顺序答，大多数追问都能接住。
