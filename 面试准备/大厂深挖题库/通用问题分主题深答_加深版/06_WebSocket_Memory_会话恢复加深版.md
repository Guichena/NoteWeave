# 文件：06_WebSocket_Memory_会话恢复加深版.md

## 0. 本篇定位

这篇是 `06_Memory_WebSocket_会话恢复.md` 的加深版，只补 ack/resume 语义、运行态与长期事实的边界、Memory 污染控制和上下文读取策略。

因此这里不再重复保存标准版里的 WebSocket 与 Memory 主答。普通版 `06` 负责把 runtime、stop/resume、writeback/read plan 讲顺；这篇只负责继续深挖时的增量理解。

## 1. 这篇只补哪些深度

普通版 `06` 已经覆盖：

- 为什么要 WebSocket。
- stop、resume、partialContent 的主链路。
- DRAFT 不写长期 Memory。
- recent history / summary / space memory / user memory 的读取分层。

这篇额外补的是：

- ack 到底解决了什么恢复问题。
- 为什么 runtime 恢复不是“强一致消息系统”。
- Memory 写回为什么更像筛选和蒸馏，而不是全量存档。
- 为什么上下文工程的核心不是“塞得越多越好”。

## 2. ack / resume 真正解决的是什么

页面刷新或短暂断线后，系统最怕的问题不是“要不要重连”，而是：

```text
客户端到底已经收到哪里了
```

没有 ack，服务端只能：

- 全量重发最近事件，造成重复展示；
- 或者直接放弃恢复，用户只能看到截断答案。

有 ack 之后，恢复语义就会清晰很多：

```text
客户端告诉服务端“我已收到第 N 条事件”
-> 服务端只重放 N 之后的 buffer
```

这比“重新生成一遍”或“盲目全量重放”都稳。

## 3. 为什么 runtime 恢复不是强一致消息系统

这点很重要，因为很多人一讲 ack 就容易讲飘。

当前恢复的是：

- 近期 delta 事件。
- partialContent。
- 当前 runtimeStatus。

它不是：

- 永久消息日志。
- 跨很长时间的完整回放系统。
- 任意时刻都绝对不丢事件的金融级消息链路。

也就是说，Redis 里的 event buffer 更像短期恢复窗口，而不是正式事实源。正式答案、正式 Citation、正式 Memory 还是要看 MySQL。

## 4. 为什么长期 Memory 不能按“全量留档”做

如果把每轮对话、每句偏好、每个试探问题都直接写入长期 Memory，短期看似“记得更多”，长期一定会脏。

它会带来：

- token 膨胀。
- 低价值信息越积越多。
- 探索态内容污染正式结论。
- 用户隐私和敏感信息风险上升。
- 历史错误被反复强化。

所以 MemoryWriteback 的真正定位不是“存历史”，而是“挑稳定、长期有价值、可复用的信息写回”。

## 5. 为什么 DRAFT 不写长期 Memory 是个关键边界

DRAFT 的本质是探索、试问、临时组织思路。它常常具有这些特征：

- 用户自己都还没想好。
- 问法噪声大。
- 可能只是测试系统。
- 内容未必想长期保留。

如果 DRAFT 也大量进入长期 Memory，后面 FORMAL 会话反而会被探索态噪声带偏。

所以 `DRAFT 不写长期 Memory` 不是一个小规则，而是防污染的一级边界。

## 6. 上下文工程真正考的不是“塞多少”，而是“读什么”

很多候选人谈上下文工程时只会说：

```text
把历史聊天记录拼进 prompt
```

这不够。更成熟的口径是：

- recent history 解决最近轮次连贯性。
- session summary 解决长会话主线压缩。
- space memory 解决跨会话空间背景。
- user memory 解决稳定偏好或长期个人偏好。
- retrieval evidence 解决当前问题的事实支撑。

所以 `ContextReadRouter` 的核心价值是“控制读什么”，而不是把所有东西都塞进去。

## 7. 隐私和共享边界怎么讲

记忆系统一旦被追问，面试官通常也在看你有没有隐私意识。

当前更稳的说法是：

- user memory 是个人私有长期上下文。
- space memory 当前阶段也不是团队成员共享的“全员人格记忆”，而是更受控的 user + space 上下文。
- 敏感信息、低价值内容、探索态内容不应轻易写回长期 Memory。

这样回答会比抽象讲“我们有记忆功能”更成熟。

## 8. 边界、不能说满和扩展方向

- 这篇只补恢复语义和 Memory 污染控制，不再重复标准版主链路。
- 当前可以坚定讲：WebSocket 双向 runtime、ack/resume、DRAFT/FORMAL 边界、分层 Memory 读取。
- 当前不要讲成：恢复链路是永久消息系统；长期 Memory 保存所有历史；space memory 已是团队共享智能体记忆。
- 扩展方向可以讲：后续可继续完善 summary、Memory TTL、用户管理、bad case 清洗和读取策略评估。

## 9. 继续追问怎么接

如果面试官继续往下压，这一题最稳的承接顺序是：

```text
先讲 ack 解决恢复窗口问题
-> 再讲 runtime 不是正式事实源
-> 再讲 DRAFT/FORMAL 和 Memory 污染控制
-> 最后讲上下文读取策略而不是全量拼历史
```

这样回答既有实时系统味道，也能守住 AI 记忆边界。
