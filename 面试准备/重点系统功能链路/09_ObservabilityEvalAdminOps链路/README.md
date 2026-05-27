# 09 Observability Eval Admin Ops 链路

## 0. 本篇定位

这条链路回答：AI 系统出错时如何定位，如何做评测、健康检查、资源清理和审计。

核心链路：

```text
PromptVersion
-> LLMCallLog
-> RetrievalTrace
-> AnswerFeedback
-> RagEvalCase / RagEvalRun / RagEvalResult
-> Admin task / health / cleanup / audit
```

## 面试先说版

这条链路我会从“AI 系统出错怎么定位”讲。传统系统很多错误是接口失败、异常日志或数据不一致，但 AI 系统更常见的问题是回答看起来很完整，其实证据不对、召回漏了、Prompt 版本不合适，或者模型输出漂了。

所以 NoteWeave 把可观测拆成几层：PromptVersion 管提示词版本，LLMCallLog 管模型调用，RetrievalTrace 管检索过程，Citation 管证据关系，AnswerFeedback 管用户反馈，RAG Eval 管离线 case 和指标。Admin/Ops 再负责任务查询、组件健康、cleanup 和审计。

面试时我会强调：可观测不是“多打日志”，而是能把坏回答归因到召回、证据选择、Prompt、模型、权限或数据本身。能带出的八股包括 trace-driven debugging、RAG Eval 指标、健康检查、资源清理 scan/execute、审计日志和线上问题闭环。

## Q1：为什么 AI 项目还要做 Observability 和 Eval？

**答：**

因为 AI 系统的失败不一定是接口报错，更多时候是回答不准、证据不对、召回缺失、Prompt 版本不合适、模型输出不稳定。如果没有可观测能力，很难定位问题。

NoteWeave 的 Observability 包括 PromptVersion、LLMCallLog、RetrievalTrace、AnswerFeedback 和 RAG Eval。PromptVersion 让 Prompt 变更可管理；LLMCallLog 记录模型、token、latency、promptHash、success 等；RetrievalTrace 记录召回和证据选择；AnswerFeedback 收集用户反馈；RAG Eval 用 case 跑 recall@k、MRR、citationCoverage、latency 等指标。

## Q2：线上出现一条坏回答，怎么排查？

**答：**

第一，看 ChatMessage 和 AnswerFeedback，确认用户问题、回答内容和反馈原因。第二，看 RetrievalTrace，确认实际检索了哪些 chunk、分数、是否 selectedAsEvidence、是否命中正确文档。第三，看 Citation，确认回答引用是否真实存在、是否越权、是否引用旧版本。第四，看 LLMCallLog，确认 PromptVersion、模型、token、latency、是否调用成功。第五，如果是系统性问题，可以用 RagEvalCase 复现并跑 Eval。

这样可以把坏回答拆成召回问题、证据后处理问题、Prompt 问题、模型输出问题、权限问题或数据本身问题。

## Q3：Eval 会不会污染正式聊天历史？

**答：**

不会。RAG Eval 走独立 task 和 scene，记录自己的 trace 和 llm log，不写正式 chat_session、chat_message 或 long-term memory。

被追问实现时可以补充：Eval run 会产生自己的 trace 和 llm log，但不会创建正式聊天会话和消息。

## Q4：Admin / Ops 做了哪些系统能力？

**答：**

Admin / Ops 主要解决系统上线后的管理、排障和资源治理。

AdminManagement 能查询用户、空间、任务和任务事件，支持用户 disable/enable、任务 retry/cancel/mark failed、空间管理等。AdminOps 支持 cleanup scan/execute、cleanup job 查询、系统健康检查、dashboard summary、audit logs。SystemHealth 会拆分 MySQL、Redis、MinIO、Kafka、Elasticsearch、LLM Provider 等组件，帮助定位依赖问题。

## Q5：清理任务为什么要先 scan 再 execute？

**答：**

因为资源清理有误删风险。比如上传残留分片、过期 upload、对象存储文件都可能被任务或文档引用。先 scan 能让系统记录候选项和原因，再 execute 执行实际清理，并写 AuditLog。

这比直接删除安全，也更容易审计和回溯。

## 指标口径

不要编线上数字。可以讲已经有指标采集和验证入口：

- 任务指标：成功率、失败率、重试率、平均耗时、卡在 PENDING/RUNNING 的数量。
- 检索指标：recall@k、MRR、citationCoverage、无证据率。
- LLM 指标：latency、token、失败率、模型错误类型。
- 运行态指标：stop/resume 成功率、断线恢复率。
- 运维指标：组件健康、cleanup 数量、审计事件。

## 实现兜底锚点

- `AdminObservabilityController`
- `AdminRagEvalController`
- `AdminManagementController`
- `AdminOpsController`
- `SystemHealthService`
- `ResourceCleanupService`
- `AuditLogService`
- `Phase14ObservabilityEvaluationIntegrationTest`
- `Phase15AdminOpsIntegrationTest`

## 3 到 5 分钟深答模板

> AI 系统真正难排查的地方，不一定是接口报错，而是回答不准、证据不对、召回缺失、Prompt 版本变化或模型输出不稳定。所以 NoteWeave 单独补了一条 Observability / Eval / Admin / Ops 链路。PromptVersion 负责管理提示词变更，LLMCallLog 记录模型调用，RetrievalTrace 记录召回与证据选择，AnswerFeedback 收集用户反馈，RagEvalCase / Run / Result 用来做离线评测，Admin / Ops 再把任务查询、组件健康、cleanup scan/execute 和 AuditLog 串起来。这样一条坏回答出现后，可以从消息和反馈一路追到检索、Citation、Prompt、模型调用和评测结果，而不是只说“模型不稳定”。这也是为什么我认为可观测和评测不是附加功能，而是 AI 系统可治理的前提。

## 常见追问继续怎么接

- 如果继续追问“先做功能还是先做观测”，可以答：最小功能能跑通后，要尽快补观测，否则后面回答质量问题根本解释不清。
- 如果继续追问“没有线上数据怎么讲指标”，可以答：讲指标口径和评测入口，不报没有根据的生产数字。
- 如果继续追问“Admin/Ops 和业务价值有什么关系”，可以答：它让任务补偿、问题排查、健康巡检和清理审计可执行，不然系统只能靠人肉猜。

## 边界和不能说满的地方

- 可以坚定讲：PromptVersion、LLMCallLog、RetrievalTrace、RagEvalRun、SystemHealth、Cleanup、AuditLog。
- 不要讲成：已经有真实线上准确率、P99 或 token/day；日志就等于评测；Eval 会直接污染正式聊天和 Memory。
