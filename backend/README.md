# NoteWeave v2 Backend

后端采用 `Java 21 + Spring Boot 3.5.x`，是整个系统的业务真源与主协调层。

当前后端负责：

- 研究工作台、资料、会话、任务、结果回写
- 三种聊天回答链路的请求编排与引用返回
- Deep Research 与右侧产物生成任务的创建、状态同步与结果入库
- `MySQL / MinIO / Kafka / Elasticsearch / Redis` 的主业务接入

首批建议模块：

- `workspace`
- `source`
- `conversation`
- `retrieval`
- `citation`
- `note`
- `wiki`
- `research`
- `artifact`
- `memory`
- `task`
- `storage`
- `search`
- `mcp`
- `common`
