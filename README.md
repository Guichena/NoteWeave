<div align="center">

# NoteWeave

**面向个人资料研究与长期知识沉淀的 AI 工作台**

上传资料、带引用问答、验证驱动的深度研究、从资料和视频生成学习产物，再把对话里的偏好沉淀为长期记忆。

![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3-6DB33F?logo=springboot&logoColor=white)
![Python](https://img.shields.io/badge/Python-3.12-3776AB?logo=python&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![Kafka](https://img.shields.io/badge/Kafka-Outbox-231F20?logo=apachekafka&logoColor=white)
![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.15-005571?logo=elasticsearch&logoColor=white)
![MCP](https://img.shields.io/badge/MCP-Python_SDK-6E56CF)
![OpenAI SDK](https://img.shields.io/badge/OpenAI_SDK-Java_%2B_Python-412991?logo=openai&logoColor=white)

![深度研究报告](./docs/images/screenshots/research-report.png)

</div>

## 目录

- [核心能力](#核心能力)
- [运行效果](#运行效果)
- [系统架构](#系统架构)
- [关键设计](#关键设计)
- [技术栈与项目规模](#技术栈与项目规模)
- [快速开始](#快速开始)
- [本地开发与测试](#本地开发与测试)
- [目录结构](#目录结构)
- [延伸阅读](#延伸阅读)

## 核心能力

| 能力 | 做了什么 |
| --- | --- |
| **深度研究** | 把研究过程表示为研究表（Table-as-State）：行是研究对象，列是待验证字段，每个格子独立追踪候选、证据与判定。子任务经 Kafka 并行执行，Verifier 校验后，缺口和冲突触发局部重规划与受限反证；每一波写入检查点，中断后可恢复并复用已验证的格子；报告附逐条引用审计。 |
| **问答 / 精读 / Wiki** | 问答走 BM25 + 向量召回 + RRF 融合 + Rerank；精读按资料召回并读取连续原文窗口；Wiki 维护页面关系、反链与来源回链。三条链路的回答都逐句标注引用，可回溯到原文。 |
| **资料异步流水线** | 分片上传后，解析、切片、向量化、索引拆成四个 Kafka 阶段，各阶段幂等，失败进入死信并回写状态，索引失败自动退避重试；单文件支持 128 MB，音视频资料自动转写并由大模型校对。 |
| **产物框架** | 产物类型由 Skill 目录声明，Java Host 负责编排、校验和版本管理，Python Worker 负责生成与导出。通过系统 MCP 接入 B站字幕、抓帧、画面 OCR 与语音转写，可生成报告、测验、思维导图、音频纪要、视频摘要、知识博客、面试问答、PPTX 和 PDF 讲义。 |
| **上下文与记忆** | 长对话按话题切分窗口并做增量摘要；从对话中提取记忆候选，经来源可信度、预期效用、风险、冲突四项门控后晋升。记忆只影响表达方式，事实只来自资料证据。 |

## 运行效果

截图使用前端开发模式自带的演示数据（`frontend/src/dev`，在 `frontend/.env.local` 设置 `VITE_NOTEWEAVE_MOCK=1` 开启），界面与真实环境一致。

<table>
  <tr>
    <td width="50%"><img src="./docs/images/screenshots/chat.png" alt="带引用的问答与精读"><br><sub><b>问答与精读</b>：回答逐句标注引用，可展开资料定位、阅读窗口和摘录证据</sub></td>
    <td width="50%"><img src="./docs/images/screenshots/research-table.png" alt="研究表"><br><sub><b>研究表</b>：逐格展示已验证、冲突、待修复和缺证据</sub></td>
  </tr>
  <tr>
    <td><img src="./docs/images/screenshots/library.png" alt="资料库"><br><sub><b>资料库</b>：每份资料的解析、切片、向量化、索引进度与失败重试</sub></td>
    <td><img src="./docs/images/screenshots/wiki.png" alt="知识库"><br><sub><b>知识库</b>：知识页分类、关系图谱与来源引用统计</sub></td>
  </tr>
  <tr>
    <td><img src="./docs/images/screenshots/studio.png" alt="产物工作室"><br><sub><b>产物工作室</b>：按输入类型分组的产物、视频学习入口与生成进度</sub></td>
    <td><img src="./docs/images/screenshots/memory.png" alt="记忆"><br><sub><b>记忆</b>：待确认候选与四项门控检查结果</sub></td>
  </tr>
</table>

## 系统架构

![NoteWeave 系统架构](./docs/images/architecture-overview.svg)

| 组件 | 职责 |
| --- | --- |
| **Backend**（Java） | 唯一的业务真源：Workspace 隔离与权限、资料版本、研究表与全部判定、产物版本、记忆门控、配额与限流。所有跨进程任务先写 Outbox，再投递到 Kafka。 |
| **Research Worker**（Python） | 无状态执行面：领取研究子任务，完成联网检索、网页快照、原文抽取和本地校验，只提交候选与证据，不直接写业务表。多个消费进程按分区并行。 |
| **Artifact Worker**（Python） | 产物生成与导出：按 Skill 目录生成结构化内容，渲染 PPTX、PDF；内置系统 MCP 服务端，负责字幕、抓帧、OCR 与转写。 |
| **Frontend**（React） | 三栏工作台：左侧是工作台导航与对话列表，中间是对话、研究和知识库，右侧是来源与产物面板；通过 SSE 接收回答流和任务事件。 |
| **存储与中间件** | MySQL 保存真源和审计账本；Elasticsearch 承担 BM25 与向量检索；MinIO 存放大文件和产物；Redis 负责配额限流、热点缓存和回答流的跨实例转发；Kafka 承载各阶段任务。 |

### 资料异步流水线

![资料异步处理流水线](./docs/images/source-pipeline.svg)

上传接口只负责接收分片、校验格式和摘要，随后立即返回。解析、切片、向量化和索引各自消费一个 topic，阶段之间只传递资料快照 ID，大对象通过 MinIO 交接，大文件处理不会占用业务请求线程。音视频资料在解析阶段交给 Artifact Worker 转写，回调后继续后续阶段。

### 深度研究闭环

![Deep Research 研究闭环](./docs/images/deep-research-loop.svg)

规划研究表、分发子任务、合并候选、验证、判定完成和写检查点都在 Backend 完成，Research Worker 只领取子任务并提交候选与证据。研究子任务按任务 ID 分布到 Kafka 分区，Worker 容器内的多个消费进程并行处理（`NOTEWEAVE_RESEARCH_AGENT_MAX_CONCURRENCY`）。

### 视频学习

![视频学习流程](./docs/images/video-learning.svg)

所有视频产物基于同一份冻结素材生成。知识规划由大模型给出大纲和原文引句，再由代码组装成完整结构，引句不在字幕原文中的观点直接丢弃；Backend 发布版本前复核产物与冻结素材是否一致。

## 关键设计

**1. Backend 是唯一真源，Worker 无状态**
研究状态、产物版本和记忆都只由 Java 侧写入，Worker 通过带令牌和签名的内部接口回调结果。Worker 崩溃或重启不会留下不一致的业务数据，任务靠租约和心跳自动重投。

**2. Outbox + Kafka 分发任务**
业务状态和待发送的消息在同一个数据库事务里写入，再由投递器发往 Kafka，不会出现状态改了消息没发出去的情况。资料流水线和研究子任务共用这套机制，每个阶段都按幂等键去重。

**3. 用研究表表示 Agent 状态**
研究不是一段越写越长的对话，而是一张表：每个格子有独立的候选、证据和判定。重规划只针对未通过的格子，反证检索会排除已用过的来源，每格的修复次数有上限，研究一定会收敛。

**4. 区分基础设施失败和证据不足**
完成判定有四种终态。只有模型或检索服务不可用才算失败；找不到证据属于业务结果，系统会输出一份列出已核验事实和未解决原因的报告，而不是编造结论。

**5. 检查点可恢复**
每一波子任务结束后写入研究账本快照，并带内容摘要。恢复时先校验摘要，再把已验证的格子带入新任务，只重做缺口。

**6. 编排自研，模型与工具用官方 SDK**
Agent 编排没有使用框架，由状态机驱动，模型在每一步只产出结构化 JSON，便于验证、审计和恢复。模型调用统一使用 OpenAI 官方 SDK（Java 与 Python），可接入任意兼容 OpenAI 协议的服务；系统 MCP 的服务端和调用端都基于官方 MCP Python SDK，工具参数按 inputSchema 校验。

**7. 产物只引用冻结素材**
视频产物生成前先冻结字幕、画面和 OCR，并计算内容摘要。生成结果里的每个观点都必须引用素材原文，Backend 在 Java 侧重新渲染一遍，与 Worker 的输出逐字节比对后才发布版本。

**8. 外部调用的安全边界**
联网抓取和模型调用只连接公网地址、拒绝重定向，密钥不会被转发到其他主机；MCP 工具只能读写沙箱目录；后台任务以发起人身份执行，并受工作量配额与并发上限约束。

## 技术栈与项目规模

| 组件 | 实现 |
| --- | --- |
| Backend | Java 17、Spring Boot 3.3、Spring JDBC、Flyway、OpenAI Java SDK |
| Frontend | React 19、TypeScript 5、Vite 5，Nginx 托管 |
| Research Worker | Python 3.12、Pydantic、kafka-python、OpenAI Python SDK |
| Artifact Worker | Python 3.12、FastAPI、OpenAI Python SDK、MCP Python SDK、faster-whisper、yt-dlp、XeLaTeX、python-pptx |
| 存储与中间件 | MySQL 8.4、Redis 7.2、Kafka、MinIO、Elasticsearch 8.15 |
| 外部服务 | OpenAI 兼容大模型、Embedding、Rerank、Serper 联网检索 |

| 模块 | 源码规模 | 自动化测试 |
| --- | --- | --- |
| Backend | 约 7.5 万行 Java，113 个 Flyway 迁移 | 1200 个测试，约 5 万行 |
| Research Worker | 约 2.6 万行 Python | 672 个测试 |
| Artifact Worker | 约 2.1 万行 Python | 387 个测试 |
| Frontend | 约 4.4 万行 TypeScript | 290 个组件与单元测试，另有真实环境 Playwright E2E |

## 快速开始

需要 Docker 与 Docker Compose。在仓库根目录复制环境变量示例：

```powershell
Copy-Item .env.example .env
```

至少替换 `.env` 中的数据库、Redis、Kafka、Elasticsearch、MinIO 密码、内部服务令牌和 Bootstrap 账号密码，再按需填写大模型与检索服务配置。然后启动完整应用：

```powershell
docker compose --profile app up --build -d
docker compose --profile app ps
```

浏览器打开 `http://localhost:3000`，用 Bootstrap 账号登录。前端由 Nginx 托管，同源 `/api` 请求转发到 Backend（容器端口 `8081`）。不带 `--profile app` 时只启动 MySQL、Redis、Kafka、MinIO 和 Elasticsearch。

### Provider 配置

聊天问答、深度研究和产物生成分别读取各自的大模型配置，可以接入不同的服务：

| 用途 | 主要变量 |
| --- | --- |
| 聊天问答 | `NOTEWEAVE_LLM_ENDPOINT`、`NOTEWEAVE_LLM_API_KEY`、`NOTEWEAVE_LLM_MODEL` |
| 深度研究 | `NOTEWEAVE_RESEARCH_LLM_BASE_URL`、`NOTEWEAVE_RESEARCH_LLM_API_KEY`、`NOTEWEAVE_RESEARCH_LLM_MODEL` |
| 产物生成 | `NOTEWEAVE_ARTIFACT_LLM_BASE_URL`、`NOTEWEAVE_ARTIFACT_LLM_API_KEY`、`NOTEWEAVE_ARTIFACT_LLM_MODEL` |
| 向量与重排 | `NOTEWEAVE_EMBEDDING_*`、`NOTEWEAVE_RERANK_*` |
| 联网检索 | `NOTEWEAVE_RESEARCH_SEARCH_*` |

密钥为空时应用仍能启动，相关功能会明确失败或降级。其他常用开关：

- `NOTEWEAVE_RESEARCH_AGENT_MAX_CONCURRENCY`：研究 Worker 并行消费进程数。
- `NOTEWEAVE_RESEARCH_CHECKPOINT_HYDRATION_V2`：开启后，研究可从检查点恢复并复用已验证的格子。
- `NOTEWEAVE_VIDEO_LEARNING_ENABLED`：开启 B站视频学习入口。

完整演示前可以检查配置是否齐全：

```powershell
.\scripts\smoke-compose-provider-readiness.ps1 -EnvFile .env -RequireFullDemo
```

只想看界面时，不需要启动后端：在 `frontend/.env.local` 写入 `VITE_NOTEWEAVE_MOCK=1`，再运行 `pnpm dev`，前端会使用内置演示数据。

## 本地开发与测试

Backend：

```powershell
.\mvnw.cmd -f backend\pom.xml spring-boot:run
```

Frontend：

```powershell
Set-Location frontend
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

Python Worker 的环境与启动方式见 [workers/README.md](./workers/README.md)。修改产物目录时只改 `reference/artifact-skill-catalog-v2.json`，再运行 `python scripts/sync-artifact-skill-catalog.py` 同步到 Backend 和 Worker。

测试：

```powershell
# Backend 全量测试
.\mvnw.cmd -f backend\pom.xml test

# Frontend 单元测试、类型检查与构建
Set-Location frontend
pnpm test
pnpm build

# Worker 测试
.\workers\scripts\test-workers.ps1
```

真实浏览器 E2E 需要先启动完整 Compose 环境，不使用 Mock，会创建真实 Workspace、上传资料并观察任务事件：

```powershell
Set-Location frontend
corepack pnpm e2e
```

## 目录结构

```text
NoteWeave
├── backend                  Java 主系统、Flyway 迁移和后端测试
├── frontend                 React 工作台、演示数据、组件测试和 Playwright E2E
├── workers
│   ├── research-worker      深度研究执行面
│   └── artifact-worker      产物生成与导出
│       └── mcp_servers      系统 MCP 服务端（字幕、抓帧、OCR、转写、PDF 渲染）
├── reference                产物 Skill 目录等跨语言共享定义
├── scripts                  运行、评测、Smoke 和同步脚本
└── docs                     设计文档与架构图
```

## 延伸阅读

完整文档入口见 [文档中心](./docs/README.md)，主要设计文档：

| 主题 | 文档 |
| --- | --- |
| 领域模型、系统拓扑与全链路 | [系统架构设计](./docs/系统架构设计.md) |
| 各能力的演化过程与技术取舍 | [系统设计演化与技术取舍](./docs/系统设计演化与技术取舍.md) |
| 资料上传、解析与检索投影 | [资料基础设施详细设计](./docs/资料基础设施详细设计.md) |
| 问答检索链路 | [问答 RAG 链路设计](./docs/问答RAG链路设计.md) |
| 精读与 Wiki | [Note 链路设计](./docs/Note链路设计.md)、[Wiki 模式设计](./docs/Wiki模式设计.md) |
| 深度研究 | [Research Agent 架构](./docs/DeepResearch-ResearchAgent架构文档.md) |
| 产物框架 | [Artifact Skill 执行架构](./docs/Artifact-Skill执行架构.md) |
| 记忆 | [Memory 机制详细设计](./docs/Memory机制详细设计.md) |
| 接口与事件 | [API 与事件契约](./docs/API与事件契约-v2.md) |
