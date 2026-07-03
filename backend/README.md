# NoteWeave v2 Backend

后端采用 `Java 17 + Spring Boot 3.3.x`，是阶段1/2/3的业务真源与主协调层。

## 本阶段已覆盖

1. `V001-V007` Flyway 迁移
2. 创建工作台
3. 创建上传事务、上传分片、完成上传
4. 同步解析文本资料并生成 `source_chunk`
5. 创建会话、发送 QA 消息、生成 citation
6. `NOTE` 链路：参考 Marginalia 的结构化阅读漏斗，先定位候选资料，再打开原文窗口生成摘录卡片和结构化笔记
7. `WIKI` 链路：参考 WeKnora 的 Wiki-first 形态，优先读取已沉淀 Wiki 页面，并提供默认 Wiki 工作台入口
8. 查询任务状态与聊天 SSE 回放

## 本地运行

```bash
./mvnw spring-boot:run
```

默认连接：

- MySQL：`localhost:3306/noteweave`
- Redis：`localhost:6379`
- Kafka：`localhost:9092`
- MinIO：`localhost:9000`
- Elasticsearch：`localhost:9200`

## 测试

```bash
./mvnw test
```

测试使用 H2 + Flyway，先保证阶段1/2/3契约和纵向闭环可验证。
