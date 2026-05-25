# NoteWeave 架构与功能说明

## 总体架构

NoteWeave 是一个 Spring Boot 驱动的知识工作台，前端采用静态 HTML + 原生 ESM JavaScript，后端通过 REST API、WebSocket、Kafka、Redis、MySQL、MinIO 和 Elasticsearch 组合完成“采集 - 检索 - 生成 - 沉淀 - 运维”的闭环。

## 分层结构

### 前端层

- 入口：`src/main/resources/static/index.html`
- 逻辑：`src/main/resources/static/js/app.js`
- API：`src/main/resources/static/js/api.js`
- 形态：单页工作台风格，包含 Global Rail、Context Rail、Main Canvas、Inspector 四区

### 应用层

- 认证与权限：`auth`、`user`、`space`、`permission`
- 团队知识：`team`、`chat`、`citation`、`wiki`
- 个人研究：`personal`、`memory`
- 成果生成：`artifact`、`studio`
- 运维与评测：`admin`
- 异步任务：`task`、`outbox`、`worker`

### 基础设施层

- MySQL：业务数据与长期状态
- Redis：会话票据、运行态、短期状态
- Kafka：异步任务分发
- MinIO：文件、解析结果、快照
- Elasticsearch：文档检索与知识图谱检索

## 核心功能

### 1. 登录与空间

- 用户注册、登录、刷新令牌、退出登录
- 空间选择与切换
- Team Space 与 Personal Space 双模型

### 2. 团队知识闭环

- 文档上传、分片续传、合并、取消、清理
- 文档解析、切片、索引
- 团队 Chat RAG 问答
- Citation 可追溯引用
- Wiki 草稿、发布、版本、索引、关系图

### 3. 个人研究闭环

- ResearchProject 管理
- Source 导入（文本、文件、URL）
- ArticleCard / ConceptCard / SynthesisCard
- MethodologyCard 匹配
- 个人 Artifact 生成
- Artifact 沉淀到个人 Wiki

### 4. 记忆系统

- SessionSummary：会话摘要
- SpaceMemory：空间记忆
- UserMemory：用户记忆
- 写入开关、TTL、pin、重要性分数

### 5. 运行与运维

- WebSocket 实时问答
- Task / Outbox / Kafka Worker 异步执行
- Admin 任务、健康检查、评测、日志、清理

## 关键数据流

```text
Upload -> MinIO -> Kafka Task -> Document Parse -> ES Index -> Chat RAG -> Citation -> Artifact / Wiki / Memory
```

## 关键接口

- `/api/v1/auth/*`
- `/api/v1/spaces/*`
- `/api/v1/team/*`
- `/api/v1/personal/*`
- `/api/v1/chat/*`
- `/api/v1/artifacts/*`
- `/api/v1/admin/*`
- `/ws/chat/{ticket}`

## 启动路径

1. 启动 Docker 中间件和应用
2. 加载 `dev` 种子数据
3. 登录 `admin` / `alice` / `bob`
4. 进入工作台完成问答、研究、记忆、成果与运维操作

## 已实现重点

- 统一工作台前端
- 团队知识问答与引用
- 个人研究与成果生成
- 长期记忆
- 管理后台与可观测性
- Docker 一键本地启动
