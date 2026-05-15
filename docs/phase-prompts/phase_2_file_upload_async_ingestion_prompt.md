# Phase 2 Prompt: File Upload / Async Ingestion

你是 NoteWeave 项目的编码代理。请执行 Phase 2：KnowledgeBase、文件存储、分片上传与异步任务投递。

## 测试驱动执行规则

本阶段必须采用测试驱动开发：

```text
1. 先根据本阶段目标和验收标准写测试
2. 运行测试，确认关键测试失败且失败原因符合预期
3. 再写最小实现让测试通过
4. 重构和补齐边界处理
5. 最后运行当前阶段相关测试和必要回归测试
```

## Docker 中间件执行规则

本阶段涉及的所有中间件必须通过 Docker Compose 或 Testcontainers 提供：

```text
MySQL
Redis
MinIO
Elasticsearch
Kafka
```

要求：

- 不允许依赖本机散装安装的中间件。
- 本地开发中间件统一维护在根目录 `docker-compose.yml`。
- 集成测试中间件统一使用 Testcontainers。
- 当前 Phase 新增中间件、bucket、topic、index 或测试路径时，必须同步更新 `docs/DOCKER_MIDDLEWARE.md`。
- 测试临时路径统一使用 `target/noteweave-test/{phase}/`，不能写用户机器绝对路径。

## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_0_1_bootstrap_auth_space.md
docs/features/phase_1_5_task_outbox_worker.md
docs/features/phase_2_file_upload_async_ingestion.md
docs/features/file_upload_async_pipeline.md
```

## 目标

完成团队文档进入系统的可靠上传链路：

```text
KnowledgeBase
DocumentUpload
UploadChunk
FileObject
Document
MinIO 分片上传
Redis Bitmap 断点续传
merge
cancel
expiresAt 过期清理
DOCUMENT_PROCESS Task 创建
task_outbox 投递
```

## 严格边界

不要实现：

```text
真实文档解析
DocumentChunk
Embedding
Elasticsearch
RAG 问答
个人 Source
Artifact
Quiz
```

## 必须遵守

- 文件对象复用不能穿透 Space 权限。
- `file_object` 必须按 `space_id + content_hash` 唯一。
- 秒传只能复用对象内容，不能复用 Document 权限。
- 分片 key 使用 `uploads/{uploadId}/chunks/{chunkIndex}` 业务后缀，并按 `docs/DOCKER_MIDDLEWARE.md` 增加 dev/test 前缀。
- merge 后创建 Document、FileObject 引用、Task 和 TaskOutbox。
- VIEWER 不能上传。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
上传状态流转
对象存储 key 设计
Task/Outbox 如何创建
权限如何校验
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```
