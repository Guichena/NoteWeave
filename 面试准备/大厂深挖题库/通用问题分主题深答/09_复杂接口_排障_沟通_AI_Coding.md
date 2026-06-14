# 复杂接口、排障、沟通与 AI Coding

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 通用问题如何转成项目深答
先把通用八股问题落到 NoteWeave 的真实模块，再回答场景、方案、收益、权衡、故障和指标。下面是本主题的项目化深答。

## 1. 本篇定位
大厂追问通常从“为什么这样设计”转到“失败时怎么办”。

## 2. 面试先说版
可靠性我会按五个故障域讲。第一是任务一致性，业务事实先落 MySQL，Outbox 再投 Kafka，Worker 回查 Task 状态执行，失败写 attempt 和 event。第二是索引一致性，文档 reindex 用版本切换，避免新索引失败破坏旧可用结果。第三是证据一致性，Citation 和 RetrievalTrace 能定位 bad answer 是召回问题、证据截断问题还是 LLM 生成问题。第四是运行态恢复，WebSocket partial 和事件状态放 Redis，最终消息仍落 MySQL。第五是清理和运维，cleanup 先 scan 生成 item，再 execute 并写 audit，避免盲删。

## 3. 当前真实口径
NoteWeave 的可靠性主轴是权限不串、任务可恢复、索引可回滚、证据可追溯、运行态可恢复、清理可审计。

### 已实现
- TaskAttempt/TaskEvent/AdminTaskService 提供失败和重试可见性。
- Document reindex、embedding backfill、Wiki index、RAG eval、cleanup 均走 TaskType。
- RetrievalTrace、LLMCallLog、AnswerFeedback、AuditLog 可辅助定位。
- ResourceCleanupService、OpsCleanupJob、OpsCleanupItem 支持 scan-first 清理。

### 设计目标
- 用统一 Task 状态机处理重试和取消，用 indexVersion/activeIndexVersion 处理索引切换，用 Citation/Trace 处理证据排查，用 Redis runtime 和 MySQL 最终消息处理断线恢复，用 scan-first cleanup 降低误删风险。
- 回答压力题时不靠泛泛而谈，可以按链路定位到 DB、Kafka、ES、Redis、MinIO、LLM 每一层。

### 后续可扩展
- 你会先看日志、DB、还是 trace？
- 哪些地方是最终一致，不是强一致？
- 如果要扩展到更大规模，先拆哪条边界？

## 4. 代码和测试锚点
- src/main/java/com/noteweave/admin/service/AdminTaskService.java
- src/main/java/com/noteweave/admin/service/ResourceCleanupService.java
- src/main/java/com/noteweave/team/document/service/DocumentProcessingService.java
- src/main/java/com/noteweave/chat/service/RetrievalTraceService.java
- src/test/java/com/noteweave/admin/Phase15AdminOpsIntegrationTest.java

## 5. 必会问题与答题骨架

### Q1: 如果 Kafka 堆积怎么办？

回答时按四步走：
1. 先说场景：大厂追问通常从“为什么这样设计”转到“失败时怎么办”。
2. 再说方案：用统一 Task 状态机处理重试和取消，用 indexVersion/activeIndexVersion 处理索引切换，用 Citation/Trace 处理证据排查，用 Redis runtime 和 MySQL 最终消息处理断线恢复，用 scan-first cleanup 降低误删风险。
3. 再说收益：回答压力题时不靠泛泛而谈，可以按链路定位到 DB、Kafka、ES、Redis、MinIO、LLM 每一层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可靠性我会按五个故障域讲。第一是任务一致性，业务事实先落 MySQL，Outbox 再投 Kafka，Worker 回查 Task 状态执行，失败写 attempt 和 event。第二是索引一致性，文档 reindex 用版本切换，避免新索引失败破坏旧可用结果。第三是证据一致性，Citation 和 RetrievalTrace 能定位 bad answer 是召回问题、证据截断问题还是 LLM 生成问题。第四是运行态恢复，WebSocket partial 和事件状态放 Redis，最终消息仍落 MySQL。第五是清理和运维，cleanup 先 scan 生成 item，再 execute 并写 audit，避免盲删。

常见追问：
- 你会先看日志、DB、还是 trace？
- 哪些地方是最终一致，不是强一致？
- 如果要扩展到更大规模，先拆哪条边界？

### Q2: 如果 ES 和 MySQL 状态不一致怎么办？

回答时按四步走：
1. 先说场景：大厂追问通常从“为什么这样设计”转到“失败时怎么办”。
2. 再说方案：用统一 Task 状态机处理重试和取消，用 indexVersion/activeIndexVersion 处理索引切换，用 Citation/Trace 处理证据排查，用 Redis runtime 和 MySQL 最终消息处理断线恢复，用 scan-first cleanup 降低误删风险。
3. 再说收益：回答压力题时不靠泛泛而谈，可以按链路定位到 DB、Kafka、ES、Redis、MinIO、LLM 每一层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可靠性我会按五个故障域讲。第一是任务一致性，业务事实先落 MySQL，Outbox 再投 Kafka，Worker 回查 Task 状态执行，失败写 attempt 和 event。第二是索引一致性，文档 reindex 用版本切换，避免新索引失败破坏旧可用结果。第三是证据一致性，Citation 和 RetrievalTrace 能定位 bad answer 是召回问题、证据截断问题还是 LLM 生成问题。第四是运行态恢复，WebSocket partial 和事件状态放 Redis，最终消息仍落 MySQL。第五是清理和运维，cleanup 先 scan 生成 item，再 execute 并写 audit，避免盲删。

常见追问：
- 你会先看日志、DB、还是 trace？
- 哪些地方是最终一致，不是强一致？
- 如果要扩展到更大规模，先拆哪条边界？

### Q3: 如果 WebSocket 中途断开怎么办？

回答时按四步走：
1. 先说场景：大厂追问通常从“为什么这样设计”转到“失败时怎么办”。
2. 再说方案：用统一 Task 状态机处理重试和取消，用 indexVersion/activeIndexVersion 处理索引切换，用 Citation/Trace 处理证据排查，用 Redis runtime 和 MySQL 最终消息处理断线恢复，用 scan-first cleanup 降低误删风险。
3. 再说收益：回答压力题时不靠泛泛而谈，可以按链路定位到 DB、Kafka、ES、Redis、MinIO、LLM 每一层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可靠性我会按五个故障域讲。第一是任务一致性，业务事实先落 MySQL，Outbox 再投 Kafka，Worker 回查 Task 状态执行，失败写 attempt 和 event。第二是索引一致性，文档 reindex 用版本切换，避免新索引失败破坏旧可用结果。第三是证据一致性，Citation 和 RetrievalTrace 能定位 bad answer 是召回问题、证据截断问题还是 LLM 生成问题。第四是运行态恢复，WebSocket partial 和事件状态放 Redis，最终消息仍落 MySQL。第五是清理和运维，cleanup 先 scan 生成 item，再 execute 并写 audit，避免盲删。

常见追问：
- 你会先看日志、DB、还是 trace？
- 哪些地方是最终一致，不是强一致？
- 如果要扩展到更大规模，先拆哪条边界？

### Q4: 如果 MinIO 有残留对象怎么清？

回答时按四步走：
1. 先说场景：大厂追问通常从“为什么这样设计”转到“失败时怎么办”。
2. 再说方案：用统一 Task 状态机处理重试和取消，用 indexVersion/activeIndexVersion 处理索引切换，用 Citation/Trace 处理证据排查，用 Redis runtime 和 MySQL 最终消息处理断线恢复，用 scan-first cleanup 降低误删风险。
3. 再说收益：回答压力题时不靠泛泛而谈，可以按链路定位到 DB、Kafka、ES、Redis、MinIO、LLM 每一层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可靠性我会按五个故障域讲。第一是任务一致性，业务事实先落 MySQL，Outbox 再投 Kafka，Worker 回查 Task 状态执行，失败写 attempt 和 event。第二是索引一致性，文档 reindex 用版本切换，避免新索引失败破坏旧可用结果。第三是证据一致性，Citation 和 RetrievalTrace 能定位 bad answer 是召回问题、证据截断问题还是 LLM 生成问题。第四是运行态恢复，WebSocket partial 和事件状态放 Redis，最终消息仍落 MySQL。第五是清理和运维，cleanup 先 scan 生成 item，再 execute 并写 audit，避免盲删。

常见追问：
- 你会先看日志、DB、还是 trace？
- 哪些地方是最终一致，不是强一致？
- 如果要扩展到更大规模，先拆哪条边界？

### Q5: 如果用户看不到资料，怎么排查？

回答时按四步走：
1. 先说场景：大厂追问通常从“为什么这样设计”转到“失败时怎么办”。
2. 再说方案：用统一 Task 状态机处理重试和取消，用 indexVersion/activeIndexVersion 处理索引切换，用 Citation/Trace 处理证据排查，用 Redis runtime 和 MySQL 最终消息处理断线恢复，用 scan-first cleanup 降低误删风险。
3. 再说收益：回答压力题时不靠泛泛而谈，可以按链路定位到 DB、Kafka、ES、Redis、MinIO、LLM 每一层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 可靠性我会按五个故障域讲。第一是任务一致性，业务事实先落 MySQL，Outbox 再投 Kafka，Worker 回查 Task 状态执行，失败写 attempt 和 event。第二是索引一致性，文档 reindex 用版本切换，避免新索引失败破坏旧可用结果。第三是证据一致性，Citation 和 RetrievalTrace 能定位 bad answer 是召回问题、证据截断问题还是 LLM 生成问题。第四是运行态恢复，WebSocket partial 和事件状态放 Redis，最终消息仍落 MySQL。第五是清理和运维，cleanup 先 scan 生成 item，再 execute 并写 audit，避免盲删。

常见追问：
- 你会先看日志、DB、还是 trace？
- 哪些地方是最终一致，不是强一致？
- 如果要扩展到更大规模，先拆哪条边界？

## 7. 不能说满的地方
- 不要说所有失败都能自动恢复。
- 不要说系统已经有真实压测数据。
- 不要把当前单体项目包装成微服务已落地。

## 8. 零基础记忆法
记住一句话：先讲“为什么需要这个模块”，再讲“请求从哪里来、状态落在哪里、失败怎么恢复、证据怎么追踪、权限怎么兜底”。按这个顺序答，大多数追问都能接住。
