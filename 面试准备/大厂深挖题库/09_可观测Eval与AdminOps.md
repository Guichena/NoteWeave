# 文件：09_可观测Eval与AdminOps.md

## 0. 本篇定位

这篇负责 Observability、Eval、Admin 和 Ops 的深挖。它适合回答一次坏回答怎么排查、为什么日志不等于评测、Eval 如何避免污染正式会话、健康检查为什么按依赖拆开，以及 cleanup 和审计如何做成可治理链路。

## 1. 本主题面试官想考什么

这个主题考察“AI 系统如何上线可运维”。很多候选人只会讲模型效果，但大厂面试官会追问坏回答怎么排查、评测怎么做、日志怎么脱敏、后台如何重试任务、健康检查如何覆盖依赖、清理如何避免误删。

## 2. 高频问题清单

### 基础问题

- 项目有哪些可观测数据？
- RetrievalTrace、LLMCallLog、AnswerFeedback 分别是什么？
- Admin/Ops 提供哪些能力？
- System health 检查哪些组件？

### 进阶问题

- 一次坏回答出现后怎么反查？
- RAG Eval 为什么不能污染正式会话？
- PromptVersion 为什么重要？
- cleanup 为什么要 scan/execute 两步？
- AuditLog 的价值是什么？

### 深挖追问

- recall@k、MRR、citation coverage 怎么评估？
- LLM 日志如何脱敏？
- Admin 用户能否看所有敏感内容？
- 健康检查为什么拆成 MySQL、Redis、MinIO、Kafka、ES、LLM provider？

### 压力追问

- 如果线上出现大量 bad answer，第一步看什么？
- 如果 Kafka 或 ES 挂了，系统怎么降级？
- 如果 cleanup 误删对象，如何防止？
- 如果普通用户访问 Admin API，如何拦截？

## 3. 问答与讲解

### Q1：一次错误回答出现后，管理员如何排查？

#### 面试官为什么问

这是 AI 可运维核心题。面试官想知道你有没有把 RAG 做成可诊断系统。

#### 回答思路

从用户反馈或 messageId 出发，依次查 session、message、retrieval trace、trace items、citation、LLM call log、prompt version、task/document/index 状态。

#### 结合我的项目怎么答

项目有 RetrievalTrace、RetrievalTraceItem、LLMCallLog、AnswerFeedback、PromptVersion、Citation，以及 AdminObservabilityController/AdminRagEvalController。坏回答可以先看 answer feedback，再看对应 message 的 RetrievalTrace，确认 bm25/vector/wiki/fusion 数量、fallbackUsed、traceJson、最终 evidence；再查 Citation 指向的 source/chunk/version；最后查 LLM 调用和 prompt version。

#### 技术原理 / 链路设计讲解

坏回答通常来自几类问题：

- 没召回：query rewrite、索引、embedding、filter 范围问题。
- 召回错：chunk 质量、RRF 权重、低分过滤、同文档限流问题。
- 证据好但生成错：prompt、模型、上下文截断问题。
- 引用错：citation 保存、sourceVersion、snapshot 问题。
- 权限错：space filter 或 citation 二次校验问题。

可观测体系的价值就是把这些层拆开定位。

#### 技术栈特点与选型理由

MySQL 保存 trace/log/feedback，便于后台查询；LLM 调用通过 `ObservedLlmGateway` 统一记录；PromptVersion 让后续回答能知道当时使用的提示词版本。

#### 可直接复述的面试回答

如果出现一次坏回答，我不会只看最终 answer，而是沿链路拆开查。先根据 messageId 或 feedback 找到 session 和 assistant message；再查 RetrievalTrace，看当时 BM25、向量、Wiki 各召回了多少，是否 fallback，fusion 后有哪些 trace item；然后查 Citation，看最终引用的是哪个 source、chunk、sourceVersion 和 snapshot；如果证据本身没问题，再看 LLMCallLog 和 PromptVersion，判断是不是 prompt 或模型生成问题。这样可以把问题分成召回失败、召回噪声、证据处理问题、生成问题或 Citation 持久化问题，而不是凭感觉改 prompt。

#### 常见追问

- 如果 trace 里没有证据，说明什么？
- 如果 citation 有证据但答案不遵循证据，怎么改？
- feedback 如何进入 Eval？

#### 常见坑

不要回答“看日志”。要说具体看哪些日志、trace 字段和判断路径。

---

### Q2：RAG Eval 跑的是什么链路？为什么不能污染正式会话？

#### 面试官为什么问

评测是 AI 工程成熟度指标。面试官想看你是否区分线上用户数据和离线评测。

#### 回答思路

说明 Eval case/run/result 用于离线或管理场景，指标包括 recall@k、MRR、citation coverage、latency、错误；Eval 不能写正式 ChatSession、Memory 或用户可见 Artifact。

#### 结合我的项目怎么答

项目包含 `rageval` 包、Phase14ObservabilityEvaluationIntegrationTest，以及 Admin RAG Eval 控制器。评测链路会记录 eval run 和 result，用于验证检索和引用质量，不应该触发正式会话的 memory writeback，也不应该把 eval 问题写进用户聊天历史。

#### 技术原理 / 链路设计讲解

RAG Eval 的价值是可重复比较：同一批 case 在不同 prompt、retriever mode、RRF 权重或 embedding 配置下跑，比较 recall@k、MRR、citation coverage、latency。它不是用户对话，所以不能污染正式上下文。

#### 技术栈特点与选型理由

用 MySQL 保存 eval case/run/result 可以做历史对比；Admin API 控制执行；Trace 能复用检索诊断能力。

#### 可直接复述的面试回答

RAG Eval 我会把它当成隔离的评测链路，而不是普通聊天。它会基于一组 eval case 跑检索和回答，记录 EvalRun 和 EvalResult，关注 recall@k、MRR、citation coverage、latency 和错误信息。它不能污染正式会话，因为评测问题不是用户真实聊天，也不应该触发 Memory writeback，更不能生成用户可见的正式 Message 或 Artifact。隔离以后，才能安全地比较不同 prompt、retriever mode、RRF 权重和索引版本的效果。

#### 常见追问

- 没有人工标注答案怎么做 Eval？
- citation coverage 代表答案一定正确吗？
- Eval 指标和用户反馈怎么结合？

#### 常见坑

不要把 Eval 说成“跑几个测试看看”。要强调 case/run/result、指标、隔离和可重复比较。

---

### Q3：System health 为什么要拆多个组件检查？

#### 面试官为什么问

这是运维视角题。面试官想看你是否能判断 AI 系统依赖链路的故障边界。

#### 回答思路

说明不同组件故障影响不同：MySQL 影响业务状态，Redis 影响 runtime，MinIO 影响对象，Kafka 影响异步任务，ES 影响检索，LLM Provider 影响生成。

#### 结合我的项目怎么答

`SystemHealthService` 分别检查 MySQL、Redis、MinIO、Kafka、Elasticsearch 和 LLM_PROVIDER，并保存 health snapshot。MySQL 用 `select 1`，Redis 写读临时 key，MinIO 检查 bucket 和 object count，Kafka describe topic，ES cluster health 和 vector alias，LLM provider 检查 stub 或 `/models`。

#### 技术原理 / 链路设计讲解

拆组件健康检查的意义是定位故障和判断降级：

- MySQL down：大部分业务不可用。
- Redis down：WebSocket resume/ticket/runtime 受影响。
- MinIO down：上传、解析、snapshot、导出受影响。
- Kafka down：新长任务无法分发，但已落库任务可等待补偿。
- ES down：RAG 检索受影响，可返回降级或无证据。
- LLM down：检索可用但生成不可用。

#### 技术栈特点与选型理由

保存 snapshot 可以看到健康趋势，而不是只看当前瞬时状态。Admin 接口必须受系统角色保护，避免暴露内部配置。

#### 可直接复述的面试回答

我把 health check 拆成 MySQL、Redis、MinIO、Kafka、Elasticsearch 和 LLM Provider，是因为它们影响的故障面不同。MySQL 影响业务状态，Redis 主要影响 WebSocket ticket、runtime 和 resume，MinIO 影响文件、parsed text 和 citation snapshot，Kafka 影响异步任务分发，ES 影响检索召回，LLM Provider 影响生成。拆开检查后，管理员能快速判断是上传链路、检索链路、生成链路还是运行态链路出问题，并决定是否降级或暂停某些功能。

#### 常见追问

- Kafka 挂了会不会丢任务？
- ES yellow 算不算不可用？
- health detail 会不会泄露配置？

#### 常见坑

不要只说“Spring Actuator 已经有 health”。项目级 health 要能表达业务依赖和降级影响。

---

### Q4：cleanup 为什么要 scan/execute 两步？

#### 面试官为什么问

这是运维安全题。清理任务很容易误删，面试官会看你是否有防御性设计。

#### 回答思路

说明 scan 是生成候选和预览，execute 才执行；中间可审计、可确认、可重试、可记录 cleanup item。

#### 结合我的项目怎么答

Admin/Ops 中有 ResourceCleanupService、CleanupResourceTaskWorker、ops_cleanup_job、ops_cleanup_item。清理默认先 scan 出候选，如临时分片、过期对象、孤儿资源，再由管理员 execute。操作写 AuditLog，避免一键直接删不可恢复资源。

#### 技术原理 / 链路设计讲解

清理任务涉及 MinIO 对象、DB 记录、ES 索引等多资源。直接删除风险很高。scan/execute 两步可以把“发现候选”和“执行副作用”分离，便于人工复核和失败恢复。

#### 技术栈特点与选型理由

MySQL 保存 cleanup job/item 状态；Task Worker 异步执行；AuditLog 保存管理员操作。这样清理任务也复用统一异步骨架。

#### 可直接复述的面试回答

cleanup 我不会做成一个按钮直接删。因为它涉及 MinIO、DB、ES 这些资源，一旦判断错就是数据事故。当前设计是 scan/execute 两步：scan 只识别候选资源并生成 cleanup job/item，让管理员能看到会清什么；execute 再异步执行真正清理，并记录状态和 AuditLog。这样可以复核、审计、失败重试，也能避免误删造成不可恢复影响。

#### 常见追问

- scan 后资源状态变化怎么办？
- execute 失败如何重试？
- 谁有权限执行 cleanup？

#### 常见坑

不要把清理说成“定时删过期数据”。真实系统里清理比新增更危险。

---

## 4. 本主题总结

可观测主题要讲清：坏回答能按 trace/citation/log/prompt/version 排查，Eval 隔离正式会话，health 按组件拆分，cleanup 先 scan 后 execute，Admin 操作要审计和权限保护。

## 5. 面试前自查清单

- 我是否能说清坏回答排查路径？
- 我是否能解释 RetrievalTrace 与 Citation 的区别？
- 我是否能讲出 RAG Eval 指标和隔离原因？
- 我是否能说明 health check 每个组件影响什么？
- 我是否能解释 cleanup scan/execute 的安全价值？

## 6. 整条链路深讲：一次坏回答如何被定位

第一步是从用户反馈或 messageId 入手。AnswerFeedback 能告诉我们用户对哪条 assistant message 不满意。

第二步查 ChatSession 和 ChatMessage。确认这是哪个 space、哪个 session、哪个 user、FORMAL 还是 DRAFT、messageSeq 是多少。

第三步查 RetrievalTrace。看 queryText、retrieverType、topK、latencyMs、retrievalMode、bm25Count、vectorCount、fusionCount、fallbackUsed 和 traceJson。这里判断问题是不是召回层：比如没有召回、向量失败、fusion 后数量异常。

第四步查 RetrievalTraceItem。看每条候选来自哪个 retriever、rank、score、chunkId、documentId、sourceType、sourceVersion。这里判断是不是排序、RRF 权重、低分噪声或同文档挤占问题。

第五步查 Citation。看最终答案使用了哪些 citation，citation 指向的 chunk/source/page/offset/snapshot/sourceVersion 是否存在，用户当前是否仍有权限查看。

第六步查源资料状态。回到 Document、DocumentChunk、KnowledgeBase、WikiPageVersion 或 Source/Card，确认资源是否被删除、是否 active、indexVersion 是否是当前 active。

第七步查 LLMCallLog 和 PromptVersion。如果证据正确但回答错误，就看当时 prompt version、system prompt、evidence 编号、模型、token、latency 和错误信息。注意日志需要脱敏，不应对普通用户暴露完整私有 prompt。

第八步归因。把问题归到召回不足、召回噪声、后处理错误、citation 持久化错误、prompt 约束不够、模型未遵循证据、权限过滤问题或资料本身质量问题。

## 7. 原理与设计原因速查

- 为什么 Trace 和 Citation 都需要：Trace 看召回过程，Citation 看最终答案使用了什么证据。
- 为什么 PromptVersion 重要：没有版本就无法复现“当时为什么这么答”。
- 为什么 Eval 要隔离正式会话：评测问题不是用户真实聊天，不能写 Message、Artifact 或 Memory。
- 为什么 health 分组件：AI 系统依赖链长，MySQL、Redis、MinIO、Kafka、ES、LLM 挂掉的影响范围不同。
- 为什么 cleanup 要两阶段：先 scan 生成候选，execute 才做副作用，降低误删风险。
- 为什么 Admin 操作要 AuditLog：重试、mark failed、cleanup 都是高风险动作，需要可追责。

## 8. 3 到 5 分钟深答模板

如果线上出现坏回答，我会按观测链路排查，而不是直接改 prompt。先根据 feedback 或 messageId 找到对应 ChatSession 和 ChatMessage，确认空间、用户和会话类型。然后查 RetrievalTrace，看当时使用什么 retrieval mode、BM25/向量/Wiki 各召回了多少、是否 fallback、融合后数量和延迟如何。接着看 RetrievalTraceItem，判断候选 evidence 的 rank、score、sourceVersion 和 retriever 来源。如果召回没问题，就查最终 Citation，看 assistant message 绑定了哪些 source、chunk、snapshot 和 version，并做权限复核。再回到 DocumentChunk、WikiPageVersion 或 Source/Card 看资源状态是否 still active。如果证据正确但回答错，再查 LLMCallLog 和 PromptVersion，看 prompt 约束、模型输出和 token 截断。这样能把问题定位到召回、排序、证据后处理、citation、prompt、模型或权限中的某一层。Eval 则用于把这些问题沉淀为可重复 case，用 recall@k、MRR、citation coverage 和 latency 做回归，而不是只靠人工感觉。
