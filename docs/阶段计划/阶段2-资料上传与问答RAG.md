# 阶段2：资料上传与问答RAG

## 1. 阶段目标

阶段 2 要交付 v2 的第一条真实用户闭环：

`工作台 -> 上传资料 -> 完成解析索引 -> 发起问答 -> 返回带引用答案`

## 2. 子阶段树

```text
2.1 上传与建档
2.2 解析、切片、索引
2.3 问答与引用闭环
```

## 3. 子阶段 2.1 上传与建档

### 目标

打通上传事务、分片、合并、建档。

### 优先迁移

1. 旧版文件上传与分片合并逻辑
2. 旧版文件去重与对象存储映射逻辑

### TDD 要求

先写：

1. 创建上传事务测试
2. 上传分片测试
3. 完成上传集成测试

再写：

1. `document_upload`
2. `upload_chunk`
3. `file_object / source / source_snapshot`

### 完成定义

1. 用户能完成一次上传
2. 系统能创建 `source` 及快照

## 4. 子阶段 2.2 解析、切片、索引

### 目标

把上传后的资料转成可检索 `source_chunk`。

### 优先迁移

1. 旧版解析器
2. 旧版 chunker
3. 旧版 ES 索引写入逻辑

### TDD 要求

先写：

1. 解析任务测试
2. chunk 生成测试
3. ES 索引写入测试

再写：

1. parser
2. chunker
3. indexer
4. outbox + consumer

### 完成定义

1. `parse_status / index_status` 能正确变化
2. chunk 能按工作台维度检索

## 5. 子阶段 2.3 问答与引用闭环

### 目标

打通 `answer_mode = QA` 的统一聊天链路。

### 优先迁移

1. 旧版 RAG 检索逻辑
2. 旧版 citation 回源逻辑
3. 旧版流式输出逻辑

### TDD 要求

先写：

1. 会话创建测试
2. 发送消息测试
3. citation 落库测试
4. SSE 输出测试

再写：

1. retrieval service
2. citation service
3. chat orchestrator
4. SSE endpoint

### 完成定义

1. 用户能在 QA 模式提问
2. 系统返回答案与 citation
3. `conversation_message` 与 `message_citation` 正常落库

## 6. 本阶段禁止项

1. 不扩展到 Note / Wiki
2. 不接入 Artifact
3. 不接入 Deep Research
