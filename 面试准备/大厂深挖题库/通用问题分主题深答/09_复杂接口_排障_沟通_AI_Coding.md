# 文件：09_复杂接口_排障_沟通_AI_Coding.md

## 1. 本主题覆盖的通用问题

- 挑一个复杂接口详细讲一下。
- 出现坏回答怎么排查？
- 组内业务交流时，怎么把复杂方案讲给业务方或产品？
- 推进方案遇到分歧怎么对齐？
- 平时怎么用 AI Coding？
- AI 生成代码不符合预期怎么办？
- 遇到开放题或压力题怎么回答？

## 2. 复杂接口推荐一：团队 RAG 问答接口

### 完整链路

```text
POST /chat/sessions/{sessionId}/messages
-> get active session
-> validate TEAM_CHAT + FORMAL
-> requireAskQuestion
-> persist USER message
-> ContextReadRouter resolve read plan
-> resolve KnowledgeBase scope
-> HybridRetriever retrieve
-> EvidencePostProcessor process
-> persist RetrievalTrace
-> no-evidence fallback OR build prompt
-> ObservedLlmGateway chat
-> persist ASSISTANT message
-> CitationService saveForAssistantMessage
-> MemoryWritebackService writeAfterRound
-> return answer + citations
```

### 为什么复杂

- 权限：session、space、knowledgeBase、citation 都要校验。
- 检索：BM25、Vector、Wiki recall 和 RRF。
- 证据：去重、合并、限流、裁剪。
- 生成：prompt version、LLM log、token、latency。
- 持久化：message、citation、trace、memory。
- 兜底：无证据不生成幻觉答案。

### 可直接复述

我可以讲团队 RAG 问答接口。它不是简单调 LLM。请求进来后先校验 session 是 TEAM_CHAT FORMAL，再校验用户对 space 有提问权限。然后保存用户消息，解析 session scope 得到可见知识库。检索层会用 HybridRetriever 做 BM25、向量和 Wiki recall，再用 weighted RRF 融合。融合结果进入 EvidencePostProcessor，做 chunk 去重、相邻合并、同文档限流和上下文裁剪。系统先保存 RetrievalTrace；如果没有 evidence，就返回明确兜底；如果有 evidence，就构造 grounded prompt 调 LLM。最后保存 assistant message、Citation 和 message_citation，并在 FORMAL 会话里触发 memory writeback。这个接口体现了权限、检索、生成、证据和记忆的完整闭环。

## 3. 复杂接口推荐二：文档上传 merge 接口

### 完整链路

```text
POST /document-uploads/{uploadId}/merge
-> load upload for update
-> require upload permission
-> idempotent check existing documentId/taskId
-> validate chunk count
-> validate Redis bitmap
-> validate MinIO chunk objects
-> compute server-side SHA-256
-> merge chunk objects
-> find/create FileObject(spaceId + contentHash)
-> create Document
-> increment refCount
-> create DOCUMENT_PROCESS Task
-> write outbox
-> cleanup temp chunks/bitmap
-> return documentId/taskId
```

### 为什么复杂

- 分片完整性不能只信 Redis。
- contentHash 必须服务端计算。
- FileObject 复用必须限制在同一 space。
- merge 可能重试，需要幂等返回。
- 解析索引是异步任务，不能同步阻塞。

### 可直接复述

文档 merge 接口复杂在它连接了上传可靠性、对象存储、权限和异步任务。服务端会先锁定 upload，校验上传权限和状态；如果已经 merge 过，就幂等返回已有 documentId 和 taskId。否则会检查 UploadChunk 数量、Redis bitmap 和 MinIO chunk object，确认所有分片完整，再计算服务端 SHA-256 并合并最终对象。FileObject 只在同一 space 内按 contentHash 复用，避免跨租户推断。之后创建 Document，增加 refCount，并创建 DOCUMENT_PROCESS Task 进入异步解析索引链路。

## 4. 坏回答排障链路

### 排查步骤

```text
messageId / feedback
-> ChatSession / ChatMessage
-> RetrievalTrace
-> RetrievalTraceItem
-> Citation / message_citation
-> DocumentChunk / Source / WikiPageVersion
-> LLMCallLog / PromptVersion
-> classify root cause
```

### 归因类型

- 召回不足：没有召回到正确证据。
- 召回噪声：召回了错误 chunk。
- 融合问题：RRF 权重或 rank 不合理。
- 后处理问题：合并、截断、同文档限流导致证据不完整。
- 生成问题：模型没有遵循证据。
- Citation 问题：引用绑定错误。
- 权限问题：scope/filter/二次校验错误。

### 可直接复述

坏回答我不会直接改 prompt，而是沿链路排查。先根据 feedback 或 messageId 找到 session 和 message；再看 RetrievalTrace，判断 BM25、向量、Wiki 各召回了多少，是否 fallback；再看 trace item 的 rank、score、sourceVersion；然后查最终 Citation，看 answer 绑定了哪些 source、chunk 和 snapshot；如果证据没问题，再查 LLMCallLog 和 PromptVersion，看 prompt、模型、token 和错误信息。这样能定位是召回问题、证据处理问题、citation 问题还是生成问题。

## 5. 技术方案沟通

### 给产品讲 Citation

不要说“我要建 citation 表和 relation 表”。要说：

用户看到 AI 回答后，需要知道依据来自哪里；管理员遇到坏回答时，需要反查是资料问题、检索问题还是模型问题；权限变化后，不能让用户继续看到无权资料。所以引用必须独立保存、可校验、可追踪。

### 给业务讲 Task/Outbox

不要说“用了 outbox pattern”。要说：

上传成功不代表解析立刻完成。解析、索引、生成都可能耗时或失败，所以系统会返回 taskId，让用户看进度；失败可以重试，取消可以安全停止，管理员可以查原因。

### 处理分歧

把方案拆成：

- 当前收益。
- 实现成本。
- 风险。
- 可回滚性。
- 是否符合当前阶段。

例如是否微服务：当前单体更快打通闭环，未来 Worker/Search 成为瓶颈再拆。

## 6. AI Coding 回答

### 合理口径

AI Coding 用来做：

- 代码阅读。
- 方案草稿。
- 测试 case 枚举。
- 文档整理。
- 小范围重构建议。

关键业务代码仍要自己 review：

- 权限。
- 事务。
- 幂等。
- 异常处理。
- 安全。
- 数据一致性。

### AI 生成不符合预期怎么办

处理方式：

1. 缩小上下文。
2. 明确输入输出和错误日志。
3. 要求小步 patch。
4. 看 diff。
5. 跑测试。
6. 不接受大段不可解释改动。

### 可直接复述

我会用 AI Coding 做代码阅读、测试补全、方案对比和文档整理，但涉及权限、一致性、事务、异常处理和安全的代码一定自己 review。AI 生成不符合预期时，我不会让它无限大改，而是缩小上下文，给明确接口契约、错误日志和期望行为，让它做小步 patch，然后看 diff、跑测试。这个思路和 NoteWeave 对 AI 输出的处理一致：模型可以辅助生成，但最终要靠证据、权限、版本和测试约束。

## 7. 压力题通用回答模板

```text
这个问题我先区分当前事实和未来扩展。
当前项目已经实现的是 xxx。
还没有生产数据/还不是主链路的是 xxx，我不会夸大。
如果未来要做，我会从 xxx 层接入，并补 xxx 安全/观测/评测。
验证上，我会看 xxx 指标，而不是只凭感觉。
```

### 示例：问 MCP

当前 MCP 不是 NoteWeave 主链路。未来可以接在 Skill/tool 层或 Source import 层，但要补工具权限、沙箱、调用日志、超时、预算和失败回滚。

### 示例：问分库分表

当前没有分库分表，因为没有数据规模证据。未来如果 trace、citation、llm log 成为大表，先做归档、保留期、分区，再考虑分库分表。

### 示例：问生产 QPS

当前没有生产 QPS/P99，我不会编造。上线前会用上传压测、检索压测、WebSocket 压测和 RAG Eval 验证。

