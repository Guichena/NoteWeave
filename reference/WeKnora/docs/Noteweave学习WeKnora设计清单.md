# Noteweave 学习 WeKnora 设计清单

本文不是项目叙事，而是一份“可直接迁移到 Noteweave 的设计清单”。目标是把 WeKnora 的产品思路、系统设计和模块拆解成可吸收的能力点，尽量落到具体模块、接口和函数层面。

## 先学什么

如果 Noteweave 想做成一个面向面试、面向研究、面向长期使用的 AI 资料工作台，最值得直接吸收的不是某个单独算法，而是下面这几条主线：

1. **双链路产品结构**：WeKnora 不是单一 RAG 工具，而是把普通问答、Agent 推理和 Wiki 沉淀拆成不同链路。
2. **分层知识管线**：上传、解析、切分、索引、检索、回答、沉淀是分开的。
3. **可替换后端**：向量库、解析器、Web 搜索、模型、存储都能替换。
4. **可追溯答案**：答案必须能回溯到 chunk、章节、页码、来源或图谱关系。
5. **异步化和任务化**：大文件、同步导入、Wiki 构建、索引重建都不应阻塞主交互。
6. **可维护知识结构**：Wiki 和知识图谱不是展示物，而是持续维护的知识资产。

## 1. 产品层：先拆能力，不要把所有事情压在一个聊天框里

### 可学的设计

WeKnora 的核心不是“聊天”，而是把能力拆成三种模式：

- **Quick Q&A**：适合日常查找。
- **ReAct Agent**：适合多步任务、MCP 工具调用、Web 搜索和复杂推理。
- **Wiki Mode**：适合把原始文档沉淀成互联知识页和知识图谱。

### Noteweave 可吸收的点

- 不要把“问答、整理、写作、复盘、研究”都塞到一个统一 prompt 里。
- 应该把产品拆成：
  - 查询模式
  - 研究模式
  - 沉淀模式
  - 复用模式
  - 协作模式

### 值得借鉴的模块

- `internal/handler/custom_agent.go`
- `internal/application/service/agent_service.go`
- `docs/api/agent.md`
- `docs/wiki/核心功能/Wiki浏览器`、`Wiki知识图谱`

### 适合 Noteweave 的映射

- 查询模式 = 个人笔记/文档问答
- 研究模式 = 多资料分析、对比、总结、生成草稿
- 沉淀模式 = 生成知识页、主题页、FAQ、概念卡
- 复用模式 = 输出可引用的笔记、Wiki、摘要、研究结论

## 2. Agent 层：学它的“可控 Agent”，不要学失控 Agent

### 关键设计

WeKnora 的 Agent 不是完全自治，而是“有边界的推理 + 工具编排”。它把模型能力、MCP、Skills、知识库检索和 Web 搜索组合起来，但仍然保留配置、权限和范围控制。

### 可学模块

- `internal/application/service/agent_service.go`
  - `CreateAgentEngine`
  - `registerMCPTools`
  - `resolveKBAndDocInfos`
  - `initializeSkillsManager`
  - `registerTools`
  - `ValidateConfig`
  - `getKnowledgeBaseInfos`
  - `getSelectedDocumentInfos`
- `internal/handler/custom_agent.go`
  - `CreateAgent`
  - `UpdateAgent`
  - `CopyAgent`
  - `GetSuggestedQuestions`
- `docs/api/agent.md`

### 值得吸收的思路

1. **Agent 配置和执行分离**  
   Noteweave 可以把“创建研究工作流”和“执行研究任务”分开，避免每次都临时拼 prompt。

2. **工具注册是显式的**  
   `registerTools`、`registerMCPTools` 这种模式适合 Noteweave 的技能系统和外部工具接入。

3. **知识范围可控**  
   `resolveKBAndDocInfos` 这种设计说明 Agent 不应该看全库，而应该按当前项目、空间、文档范围裁剪。

4. **技能管理器独立**  
   `initializeSkillsManager` 说明 Skills 应该是独立模块，不应散落在 prompt 里。

### Noteweave 可以照着做的东西

- 研究 Agent 模板
- 资料问答 Agent 模板
- 复盘 Agent 模板
- 写作 Agent 模板
- 数据整理 Agent 模板

## 3. 检索层：学 Hybrid RAG，而不是单一向量召回

### 关键设计

WeKnora 的检索不是只做 embedding。它把关键词、向量、权限过滤、rerank、GraphRAG 和多引擎支持组合在一起。

### 可学模块

- `internal/application/repository/retriever/elasticsearch/v7/repository.go`
  - `Retrieve`
  - `VectorRetrieve`
  - `KeywordsRetrieve`
  - `buildVectorSearchQuery`
  - `executeVectorSearch`
  - `buildKeywordSearchQuery`
  - `executeKeywordSearch`
  - `processSearchResponse`
  - `CopyIndices`
- `internal/application/repository/retriever/elasticsearch/v8/repository.go`
  - `createIndexIfNotExists`
  - `Retrieve`
  - `VectorRetrieve`
  - `KeywordsRetrieve`
  - `getBaseConds`
- `internal/application/repository/retriever/sqlite/repository.go`
  - `Retrieve`
  - `keywordsRetrieve`
  - `vectorRetrieve`
- `internal/application/repository/retriever/postgres/repository.go`
  - `Retrieve`
  - `KeywordsRetrieve`
  - `VectorRetrieve`
- `internal/application/repository/retriever/milvus/repository.go`
  - `Retrieve`
  - `VectorRetrieve`
  - `KeywordsRetrieve`
  - `searchByFilter`
  - `getBaseFilterForQuery`
- `internal/application/repository/retriever/qdrant/repository.go`
  - `Retrieve`
  - `VectorRetrieve`
  - `KeywordsRetrieve`
- `internal/application/repository/retriever/tencentvectordb/repository.go`
  - `Retrieve`
  - `VectorRetrieve`
  - `KeywordsRetrieve`
- `internal/application/repository/retriever/weaviate/repository.go`
  - `Retrieve`
  - `VectorRetrieve`
  - `KeywordsRetrieve`

### 可吸收的设计点

1. **关键词和向量并存**  
   Noteweave 的资料研究场景会同时存在标题词、术语、项目名、章节名和语义问法，BM25 + 向量是基本盘。

2. **不同后端统一接口**  
   WeKnora 把多种向量库封成统一检索接口。Noteweave 也应该保留存储和检索解耦。

3. **权限过滤是检索的一部分**  
   不要把权限当成检索后处理。检索阶段就要做 scope 约束。

4. **索引复制和迁移要内建**  
   `CopyIndices` 说明索引迁移是基础能力，不是额外脚本。

### Noteweave 的 tradeoff

- **Pure Vector vs Hybrid**
  - 个人资料多、表达杂，Hybrid 更稳。
- **单引擎 vs 多引擎**
  - 早期可以单引擎，设计上要留扩展点。
- **召回更多 vs 回答更准**
  - 面试场景更看重证据链和稳定性，不要盲目加大 TopK。

## 4. 分块层：学它的自适应切分，不要只看 chunk size

### 关键设计

WeKnora 的 chunking 不是固定切片，而是根据文档结构、标题密度、启发式标记和 breadcrumb 上下文做自适应分层切分。

### 可学模块

- `internal/infrastructure/chunker/splitter.go`
  - `Chunk.EmbeddingContent`
- `internal/infrastructure/chunker/strategy.go`
  - `TierRejection`
  - `Diagnostics`
- `internal/infrastructure/chunker/heading_hierarchy.go`
  - `Observe`
  - `Breadcrumb`
  - `BreadcrumbWithHashes`
  - `Depth`
  - `Reset`
- `internal/infrastructure/chunker/profiler.go`
  - `HeadingDensity`
  - `DominantHeadingLevel`
  - `HeuristicMarkerTotal`
- `internal/infrastructure/chunker/header_tracker.go`
  - `update`
  - `getHeaders`

### 可吸收的设计点

1. **结构比长度更重要**  
   分块不只是按字数切，而是按标题层级和文档结构切。

2. **上下文头要随 chunk 一起进 embedding**  
   `Breadcrumb` 这种 breadcrumb 上下文，特别适合面试复盘、技术方案、长笔记。

3. **chunk 过程要可诊断**  
   `Diagnostics` 的存在很重要，说明切分不是黑盒。

4. **保留层级信息**  
   Noteweave 的个人 Wiki 和研究库都适合保留章节层级、项目路径和上位主题。

### Noteweave 可以直接吸收的功能

- Markdown / Markdown-like 结构切分
- 章节 breadcrumb 注入
- 长文档结构感知摘要
- 分块预览
- 分块诊断面板

## 5. 文档解析层：学它的多引擎解析和图片处理

### 关键设计

WeKnora 的解析层不是单个 parser，而是按文件类型、可用性、部署方式和远端能力选择 engine。

### 可学模块

- `internal/infrastructure/docparser/engine_registry.go`
  - `Name`
  - `Description`
  - `FileTypes`
  - `CheckAvailable`
- `internal/infrastructure/docparser/grpc_parser.go`
  - `connect`
  - `Reconnect`
  - `IsConnected`
  - `Read`
  - `ListEngines`
- `internal/infrastructure/docparser/http_parser.go`
  - `Reconnect`
  - `Read`
  - `ListEngines`
- `internal/infrastructure/docparser/weknoracloud_http_reader.go`
  - `Read`
  - `pollTaskResult`
  - `newSignedRequest`
- `internal/infrastructure/docparser/image_resolver.go`
  - `ResolveAndStore`
  - `ResolveHTMLDataURIImages`
  - `ResolveRelativeHTMLImages`
  - `ResolveBareBase64Content`
  - `ResolveRemoteImages`

### 可吸收的设计点

1. **解析引擎可插拔**  
   Noteweave 未来如果支持 PDF、网页、图片、音频转写、外部 OCR，都应该走同一种 engine registry。

2. **远端/本地都可用**  
   解析器不应和部署形态绑定死。

3. **图片不是附件，是知识的一部分**  
   `image_resolver` 这种设计值得学。截图、图表、表格里的图片链接都应该进入知识链路。

4. **任务式读取更稳**  
   `Read` / `pollTaskResult` 这种模式适合耗时解析，Noteweave 可直接借用。

### Noteweave 的 tradeoff

- **本地解析 vs 云端解析**
  - 本地更可控，云端更省心。
- **同步处理 vs 任务化处理**
  - 大文件必须任务化。

## 6. 知识图谱层：学它的“关系补上下文”，不要学成纯展示图

### 关键设计

WeKnora 的图谱不是独立卖点，而是把文档里的实体、关系、引用和权重抽出来，补齐检索和 Wiki 的结构上下文。

### 可学模块

- `internal/application/service/graph.go`
  - `renderGraphExtractionPrompt`
  - `extractEntities`
  - `extractRelationships`
  - `findRelationChunkIDs`
  - `mergeChunkContents`
  - `BuildGraph`
  - `calculateWeights`
  - `calculateDegrees`
  - `buildChunkGraph`
  - `GetRelationChunks`
  - `GetIndirectRelationChunks`
  - `generateKnowledgeGraphDiagram`
- `internal/application/repository/retriever/neo4j/repository.go`
  - `AddGraph`
  - `DelGraph`
  - `SearchNode`

### 可吸收的设计点

1. **图谱先服务问答，再服务展示**
2. **关系抽取要有 chunk 级证据**
3. **权重和度数能帮助判断核心节点**
4. **直接图谱搜索和间接关系搜索都要有**

### Noteweave 可学的部分

- 主题图谱
- 项目图谱
- 人物/概念/文档关系图
- 证据链回溯
- 关联推荐

### 不要直接照搬的部分

- 一开始就做重型全图谱
- 图谱只给工程师看
- 图谱结果没有编辑入口

## 7. Wiki 层：学它的“页面即知识资产”

### 关键设计

WeKnora 的 Wiki 不是静态文档集合，而是由 Agent 生成、可修复、可重建链接、可 lint 的知识资产。

### 可学模块

- `internal/handler/wiki_page.go`
  - `ListPages`
  - `CreatePage`
  - `GetPage`
  - `UpdatePage`
  - `DeletePage`
  - `GetIndex`
  - `GetLog`
  - `GetGraph`
  - `GetStats`
  - `ListIssues`
  - `UpdateIssueStatus`
  - `SearchPages`
  - `RebuildLinks`
  - `Lint`
  - `AutoFix`

### 可吸收的设计点

1. **Wiki 不是结果页，是工作流**
2. **页面、索引、图、日志、问题、修复要分开**
3. **支持自动修复和人工修复并存**
4. **链接重建是持续维护能力**

### Noteweave 的落地方式

- 个人研究页
- 项目专题页
- 术语卡片页
- 复盘页
- 面试题库页

## 8. 上传和同步：学它的异步化与可恢复

### 关键设计

WeKnora 把数据源同步、文件解析、索引构建和状态追踪都做成了可恢复流程，不把主线程压死。

### 可学模块

- `internal/application/service/datasource_service.go`
  - `CreateDataSource`
  - `ValidateConnection`
  - `ValidateCredentials`
  - `ManualSync`
  - `PauseDataSource`
  - `ResumeDataSource`
  - `ProcessSync`
  - `ingestItem`
- `internal/handler/datasource.go`
  - `CreateDataSource`
  - `ValidateConnection`
  - `ManualSync`
  - `PauseDataSource`
  - `ResumeDataSource`
  - `GetSyncLogs`
- `internal/handler/session/attachment_processor.go`
  - `ProcessAttachment`
  - `processTextFile`
  - `processWithDocParser`
  - `processAudioFile`
  - `processWithDocumentReader`
  - `applyLineTruncation`

### 可吸收的设计点

1. **上传和解析分离**
2. **同步任务有日志**
3. **连接验证先行**
4. **可暂停、可恢复**
5. **附件处理按类型分流**

### Noteweave 的适配建议

- 资料导入必须有任务状态
- 大文件要支持断点式处理
- 文档、网页、图片、音频要分流
- 解析失败要能重试

## 9. 会话层：学它的“可继续、可中断、可切换”

### 关键设计

WeKnora 的对话不是一次性请求，而是可持续会话，支持流式输出、中断、上下文恢复和会话管理。

### 可学模块

- `internal/handler/session/stream.go`
  - `ContinueStream`
  - `StopSession`
  - `handleAgentEventsForSSE`
- `internal/handler/session/handler.go`
  - `CreateSession`
  - `GetSession`
  - `GetSessionsByTenant`
  - `UpdateSession`
  - `DeleteSession`
  - `ClearSessionMessages`
  - `BatchDeleteSessions`
  - `PinSession`
  - `UnpinSession`

### 可吸收的设计点

1. **流式输出是默认交互形态**
2. **中断和恢复是必需能力**
3. **会话可以切换、置顶、清空、批量删除**
4. **SSE 事件处理要独立**

### Noteweave 的适配建议

- 研究任务流式输出
- 资料总结中断续写
- 多个主题会话并行
- 研究草稿可恢复

## 10. Skills 与工具：学它的“可控执行”

### 关键设计

WeKnora 的 Skills 不是简单 prompt 片段，而是按需加载的任务能力。MCP 则作为外部工具接入层。

### 可学模块

- `internal/handler/skill_handler.go`
  - `ListSkills`
- `docs/agent-skills.md`
- `docs/api/skill.md`
- `internal/application/service/agent_service.go`
  - `initializeSkillsManager`
  - `registerMCPTools`
  - `registerTools`

### 可吸收的设计点

1. **Skills 是明确的能力单元**
2. **只在需要时加载**
3. **工具调用必须可审计**
4. **技能目录和白名单要可配置**

### Noteweave 的具体化

可以做成：

- `research-skill`
- `paper-summarize-skill`
- `meeting-notes-skill`
- `project-retro-skill`
- `code-reading-skill`
- `interview-prep-skill`

## 11. 权限和租户：学它的“资源级回溯授权”

### 关键设计

WeKnora 的 RBAC 不是只看用户能不能登录，而是看能否访问租户、KB、chunk、agent、share、IM 通道等资源。

### 可学模块

- `internal/middleware/rbac.go`
- `internal/middleware/kb_access.go`
- `internal/handler/rbac_lookups.go`
- `internal/application/repository/kbshare.go`
- `internal/application/repository/agent_share.go`
- `internal/application/repository/knowledgebase.go`
- `internal/application/repository/knowledge.go`

### 可吸收的设计点

1. **资源权限链条要明确**
2. **共享不是复制，应该是受控分发**
3. **pin / share / disable 都是产品能力**
4. **权限过滤要贯穿检索和展示**

### Noteweave 的适配建议

- 个人空间 / 团队空间分离
- 项目级共享
- 研究页共享
- 只读引用分享
- 权限级检索范围控制

## 12. 观察和调试：学它的可观测性

### 关键设计

WeKnora 对 Langfuse、审核、日志、任务状态都有比较完整的支持。

### 可学模块

- `internal/tracing/langfuse/*`
- `internal/application/service/audit_log.go`
- `internal/application/service/audit_log_retention.go`
- `docs/Langfuse集成.md`
- `docs/日志配置.md`

### 可吸收的设计点

1. **Agent 行为要可追踪**
2. **检索过程要可回放**
3. **任务要有审计日志**
4. **错误要能追到具体阶段**

### Noteweave 的适配建议

- 研究流程 tracing
- 资料导入日志
- 知识页生成日志
- 工具调用日志
- 审计记录

## 13. 对 Noteweave 最值得直接照着做的 10 个点

1. **双模式产品结构**：查询 + 沉淀 + 研究 + Agent。
2. **Hybrid RAG**：BM25 + 向量 + 权限过滤 + rerank。
3. **技能系统**：把任务能力拆成可加载模块。
4. **异步导入**：文件、网页、图片、音频分流入库。
5. **结构感知 chunking**：标题层级和 breadcrumb 要保留。
6. **Wiki 化沉淀**：页面、索引、图、问题、修复分离。
7. **知识图谱补结构**：别只做展示图。
8. **会话可继续**：流式、中断、恢复、切换。
9. **权限可回溯**：资源级、范围级、共享级。
10. **可观测和可审计**：每一步都能解释。

## 14. Noteweave 可以形成的产品叙事

不要把 Noteweave 讲成“AI 帮我做笔记”。

更好的叙事是：

> Noteweave 让散乱资料变成可追溯、可编织、可复用的知识网络。

如果再往下说一点：

> 它把文档、网页、会议、项目、代码和研究材料编织成一个可以持续生长的工作台，既能回答问题，也能沉淀知识，还能驱动下一步行动。

