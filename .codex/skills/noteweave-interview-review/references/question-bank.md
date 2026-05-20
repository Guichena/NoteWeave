# NoteWeave Interview Question Bank

## Use This File

- Use these as the cleaned NoteWeave-specific questions.
- Prefer these over the raw `面试准备` files.
- If the user wants mock interview, ask one question at a time and follow with 1 to 3 deeper追问.
- Unless the user explicitly asks for a short version, answer every question in 2-minute-depth form by default.

## A. Project Overview

1. 你先用 1 分钟介绍一下 NoteWeave 这个项目。
2. 这个项目解决的核心问题是什么，为什么要同时做 `TEAM` 和 `PERSONAL` 两种空间？
3. 你在这个项目里负责了哪些模块，哪些是你最有 ownership 的部分？
4. 这个项目最有技术含量的地方不是“接大模型”，而是什么？
5. 如果面试官只让你讲一个亮点，你会选哪个模块，为什么？
6. 这个项目现在的完成度如何，哪些是已经实现的，哪些还是规划中的？
7. 如果面试官质疑“这是不是一个 demo 项目”，你怎么回应？
8. 这个项目和普通 RAG 问答项目相比，最大的差异点是什么？
9. 如果你的简历里写了 `AI Skills / MCP / 长期记忆 / Hybrid RAG`，你会怎么把这些词解释成和当前仓库一致的真实工程能力？

## B. 架构和边界

1. 为什么要把 `PERSONAL` 和 `TEAM` 空间做成一级业务边界？
2. 为什么 `systemRole` 和 `space role` 要分开，而不是混成一套权限模型？
3. 资源访问为什么要走统一权限服务，而不是 controller 里直接按 id 查？
4. 如果某个 citation 指向一个用户没有权限看的文档，系统怎么避免泄露？
5. 为什么 Artifact 和 Wiki 要分离？
6. 为什么团队知识和个人研究不直接复用同一套内容沉淀模型？

## C. Task / Outbox / Kafka 主链路

1. 为什么这个项目要统一成 `Task + TaskAttempt + TaskEvent + TaskOutbox`？
2. 为什么不在业务事务里直接发 Kafka，而要多一层 outbox？
3. 这个项目里的幂等是怎么做的？`idempotencyKey` 在哪些地方起作用？
4. 任务取消是怎么设计的？为什么是 `cancel_requested` 而不是强杀？
5. 如果消息已经发到 Kafka，但业务状态还没准备好，会不会出问题？
6. 如果 Worker 重复消费同一个任务，怎么保证不会重复执行副作用？
7. 你为什么认为统一异步骨架比每个模块自己写后台任务更好？

## D. 团队知识库上传与处理

1. 团队文档上传链路是怎么走的？从 init upload 到最终可检索，中间经历了哪些阶段？
2. 为什么上传要支持分片、断点续传和 cancel？
3. 为什么 `FileObject` 复用要限制在同一个 `spaceId` 内？
4. 为什么文档删除用 soft delete，而不是立刻物理删？
5. 为什么当前阶段删除文档时不立刻减少 `refCount`？
6. 文档重建索引时，怎么避免新索引失败把旧索引也搞坏？
7. 解析完成后为什么还要保存 parsed text object，而不是只保留 chunk？

## E. 检索、RAG、Citation

1. 为什么团队问答要做成 evidence-first，而不是把用户问题直接扔给 LLM？
2. 为什么用 Elasticsearch 做检索中枢，而不是只用 MySQL 或只用向量库？
3. BM25、vector、wiki recall 和 RRF 融合分别解决什么问题？
4. 如果向量召回失败了，系统怎么降级？
5. `EvidencePostProcessor` 这层存在的意义是什么？
6. 为什么 citation 不存在 message JSON 里，而要落正式关系表？
7. 检索 trace 和 citation trace 各自回答什么问题？
8. 没有证据时，为什么系统要明确返回兜底，而不是让模型自由发挥？
9. 如果面试官问“你这里的 Hybrid RAG 只是把几种召回拼起来吗”，你会怎么解释真正的工程难点？

## F. WebSocket Runtime 和 Memory

1. HTTP 问答已经能用了，为什么还要做 WebSocket runtime？
2. `DRAFT` 和 `FORMAL` 会话为什么要区分？
3. 为什么 runtime 状态放在 Redis，而不是完全落 MySQL？
4. stop / resume 是怎么设计的，为什么不能只靠前端自己拼接流式内容？
5. `ContextReadRouter` 为什么要做分层读取，而不是把所有历史都喂给模型？
6. 长期记忆为什么分成 session summary、space memory、user memory 三层？
7. 哪些内容不应该写进 memory？为什么？
8. 为什么 DRAFT 不写长期 memory？
9. 你简历里如果提到了 Redis runtime state，面试时应该怎么讲，才能既体现设计思考又不夸大现状？

## G. 个人研究主线

1. 个人研究项目的主链路是什么？从 `Source` 到卡片再到 Artifact，怎么串起来？
2. 为什么 URL 导入要单独做安全限制？具体防了哪些风险？
3. 为什么 `importStatus=READY` 的 Source 必须保证有可读文本？
4. `WikiCompilerService` 做的事情不只是“抽卡片”，它还补了哪些关键能力？
5. 概念卡片是怎么去重和合并的？为什么要做 alias 和 relation？
6. 为什么要做 evidence backtrace，而不是只信任 LLM 输出的 quote？

## H. Artifact、Methodology、Synthesis

1. 为什么 Artifact 要有自己的版本体系，而不是直接覆盖内容？
2. 方法论卡片在这个项目里起什么作用？为什么不把输出结构直接写死在 prompt 里？
3. 方法论卡片为什么要有 `PROJECT / SPACE / PRESET` 三级作用域？
4. 个人生成为什么要只注入精选 context，而不是把 Source 原文整包塞进 prompt？
5. 为什么个人 Artifact 沉淀到个人 Wiki 要显式确认？
6. 为什么先沉淀成 `SynthesisCard`，而不是直接改写已有 `ConceptCard`？
7. distillation 里为什么要绑定具体的 `artifactVersionId`？
8. 你简历里写的 `Skill 执行` 在这个项目里到底是什么，不是什么？
9. 为什么说它更像“可控编排的生成流水线”，而不是“完全自治的 Agent 平台”？

## I. Observability、Eval、Admin、Ops

1. 这个项目怎么做 prompt version 管理？为什么这件事重要？
2. 一次错误回答出现后，管理员能沿着哪些日志或 trace 反查？
3. RAG eval 跑的是哪条链路？它为什么不能污染正式聊天会话？
4. 你们怎么验证 recall@k、MRR、citation coverage 这类指标？
5. System health 为什么拆成 MySQL、Redis、MinIO、Kafka、Elasticsearch、LLM provider 六类？
6. cleanup scan/execute 为什么要拆成两步，而不是直接清？
7. Audit log 在这个项目里的价值是什么？

## J. 大厂面试官会补的挑战题

1. 如果未来把 NoteWeave 拆成多服务，你会先从哪条边界拆，为什么？
2. 如果文档量上升一个数量级，当前索引与回溯设计最先会遇到什么瓶颈？
3. 如果 citation 被用户质疑不准确，你会先查哪几层？
4. 如果 memory 写入越来越多，怎么控制噪声累积和召回污染？
5. 如果一篇文档被反复更新，你怎么证明用户总能看到最新可用版本，而不是旧证据？
6. 如果要支持更多文档类型，比如 DOCX 或扫描 PDF，你会先补哪里？
7. 如果要把个人研究成果共享给团队，你会怎么做权限和沉淀边界设计？
8. 如果要给这个系统做更严肃的线上质量门禁，你会把哪些指标加入发布前检查？
9. 如果简历里保留了 `MCP`，但面试官追问“你在项目里具体怎么落的”，你会怎么把它答成“扩展方向”而不是“当前强实现”？
10. 如果简历里保留了 `Bibtex`，但仓库主链路还没做，你会怎么解释这个功能为什么容易补齐，以及会补在哪一层？

## K. 不建议保留的原题方向

下面这些方向在原题库里偏多，但不适合作为 NoteWeave 主答题：

- RabbitMQ vs RocketMQ 选型细节
- 分库分表、分片键、订单链路、订单 ID
- 秒杀、高并发券发放、优惠券服务
- 本地缓存 + Redis 双层缓存
- 真实线上 token/day、QPS、P99 数字
- RL、训练数据规模、GraphRAG 主链路、多 Agent 编排

处理方式：

- 如果用户坚持练这些题，把它们改写成“站在 NoteWeave 上，如果以后扩展到这个规模/场景，你会怎么设计”。
- 不要把它们包装成当前仓库已经实现的事实。
