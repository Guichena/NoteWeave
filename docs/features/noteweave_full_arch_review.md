# NoteWeave 全阶段架构修改建议汇总

本文档整合 Phase 0~16 的架构隐患和缺失设计，针对每个问题提供问题描述、影响、建议和具体修改方案，便于一次性梳理和落地修正。

---

## Phase 0/1: 工程骨架、Auth/User/Space/Permission

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P0-1 | 分页、统一响应、错误码未完全实现 | 列表接口调用可能异常，前端处理复杂 | 明确统一响应结构，所有列表 API 支持分页/排序/筛选 | 更新 Controller、ApiResponse 类，添加统一 PageResponse 泛型类，覆盖全局 API | 
| P0-2 | JWT 配置弱默认值 | 非 dev 环境存在安全风险 | 强制 prod 配置 JWT secret | 配置 profile 中添加强随机密钥或从环境变量读取，不允许默认值启动 | 
| P0-3 | 用户注册后 PERSONAL Space 自动创建逻辑缺失 | 可能导致个人资源无法归属 | 在注册 Service 中调用 SpaceService.createPersonalSpace | 修改 UserService.register 方法，调用 createPersonalSpace 并添加 OWNER SpaceMember | 

---

## Phase 1.5: Task / Outbox / Worker 基础设施

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P1.5-1 | Task 幂等和 cancelRequested 未落地 | 异步任务重复执行或无法取消 | TaskAttempt/TaskOutbox 记录执行和错误，Worker 检查 cancelRequested | 修改 Worker 服务，在安全点检查 cancelRequested 并终止任务，Outbox 投递保证最终一致性 | 
| P1.5-2 | Kafka 消息投递未与 DB 事务绑定 | 消息可能丢失或重复 | DB 提交后投递，确保幂等键 taskId | 修改 OutboxDispatcher，实现事务提交后投递，Kafka key = taskId | 

---

## Phase 2: KnowledgeBase/上传/Document

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P2-1 | 上传分片 cancel/过期清理未落地 | MinIO 残留对象，Redis Bitmap 残留 | 支持 cancel/expiresAt，定期清理 | 增加 UploadCleanupService，扫描过期对象并清理 MinIO 和 DB | 
| P2-2 | FileObject 引用计数未校验 | 不同 Space 文档可能共享对象，造成权限混淆 | FileObject 按 content_hash/ref_count 管理，Space 隔离 | 在 DocumentMergeService 中增加 ref_count 更新逻辑，按 Space 分区 | 

---

## Phase 3: Document Parsing / Chunk / ES

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P3-1 | ES indexVersion / activeIndexVersion 未明确 | 重复 Kafka 消息可能覆盖旧索引 | document.chunk 增加 indexVersion，activeIndexVersion 幂等写入 | 已创建文档 `phase_3_index_version_fix.md`，在 ChunkService 中检查存在性再写入【6a05cb83460c8191995f9582e89eda24】 | 
| P3-2 | 重复 Kafka 消息处理不安全 | 可能重复创建 Chunk | Worker 幂等检查 documentId + indexVersion | Worker 消费消息前先查询已存在 Chunk，跳过已处理 | 

---

## Phase 4: Team RAG / Citation

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P4-1 | 权限过滤不在检索前 | 用户可能看到无权限文档 | HybridRetriever 内嵌 metadata filter | 检索前使用 SpacePermissionService.canViewSpace 与 KB 权限过滤 | 
| P4-2 | EvidencePostProcessor 限流/合并边界不明确 | Chunk 去重或证据不足 | 明确每文档最大 evidence block，合并相邻 Chunk | 配置去重规则和 Top-K 限流，文档中明确参数 | 

---

## Phase 5: WebSocket Chat Runtime

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P5-1 | DRAFT 生命周期未落地 | 刷新恢复可能异常 | 定义 DRAFT_ACTIVE / DRAFT_EXPIRED / CONVERTED / DISCARDED | 在 ChatRuntimeStateStore 中添加状态机和 TTL 检查 | 
| P5-2 | Redis stream/short-term/runtime 未完全验证 | 消息丢失或流式输出中断 | 对所有事件写入 Redis，并确保刷新重连可恢复 | 增加 WebSocketTicketService 测试用例，验证流式和中断恢复 | 

---

## Phase 6: Personal Research / Source Import

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P6-1 | Source import 标记 READY 但未生成 raw/parsed text | 后续生成和证据回溯失败 | 文件/URL/Text 必须产出 rawTextObjectKey 或 parsedTextObjectKey | SourceImportService 增加解析逻辑和状态更新，失败标记 FAILED | 

---

## Phase 7: Wiki Compiler / ArticleCard / ConceptCard

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P7-1 | ConceptCard 融合规则不明确 | 可能出现重复或缺失概念 | 高置信度合并已有卡片，低置信度新建 | ArticleConceptRelation + ConceptMergeService 校验 | 
| P7-2 | Evidence 回溯未保证完整 | 证据不可追溯 | 所有 ArticleCard/ConceptCard 都必须关联 Source 原文 | 增加 evidence_quotes_json 与 source_id 绑定 | 

---

## Phase 8: Studio / Artifact

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P8-1 | Artifact 多会话下归属不明确 | 覆盖或丢失 | ArtifactVersion 必须绑定 taskId，保留历史 | ArtifactService + SkillExecutionLog 更新绑定 taskId | 

---

## Phase 9: Embedding / Vector / Weighted RRF

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P9-1 | Embedding 维度变化未处理 | 旧索引无法使用 | 新维度创建新索引版本或 alias，向量回填失败可降级 BM25 | VectorIndexerService 增加 alias 管理，失败降级逻辑 | 

---

## Phase 10: Team Wiki Draft / Publish / Index

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P10-1 | Wiki Index 异步入索引失败 | 发布内容无法被 RAG 检索 | WIKI_INDEX Task 可重试，索引失败回滚 | WikiIndexService 增加 Task retry 逻辑 | 

---

## Phase 11: Personal Wiki-based Generation

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P11-1 | Artifact 来源回溯不完整 | 生成结果不可溯源 | PersonalGenerationService 回溯 ArticleCard/ConceptCard/Source | 在 ArtifactSource 表中记录来源ID | 

---

## Phase 12: Long-term Memory

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P12-1 | Memory TTL / pin / importance 未明确 | 用户偏好可能不一致 | 明确 expiresAt、importanceScore、pin、confidenceScore | MemoryService 增加字段校验与过期定时任务 | 

---

## Phase 13: MethodologyCard

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P13-1 | 高级编辑功能暂后置 | 用户自定义方法论有限 | Status/version/edit 后置实现 | MethodologyCardService 增加 API 支持编辑和归档 | 

---

## Phase 14: Evaluation / Observability

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P14-1 | LLMCallLog / RetrievalTrace 埋点不一致 | 排查与评测不完整 | 所有 Chat/RAG/Studio/Personal Generation 链路埋点 | 扩展 LLMCallLogService 与 RetrievalTraceService 覆盖全链路 | 

---

## Phase 15: Admin / Ops

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P15-1 | 清理、重试边界未验证 | 可能残留孤儿对象或任务 | OpsCleanupJob + Task 重试边界校验 | ResourceCleanupService 扫描生成 item 后执行逐项清理，AdminTaskService 重试检查状态 | 

---

## Phase 16: Frontend Workspace

| 编号 | 问题 | 影响 | 建议 | 修改方案 |
|---|---|---|---|---|
| P16-1 | 前端薄片集成未完全覆盖 | 页面功能可能缺失 | 对应每个 API 验证、逐步上线 | 前端页面与 API 对齐，保证 Workspace/Team/Personal/Chat/Studio/Artifact/Wiki/Memory/Admin 页面可用 |