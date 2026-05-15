# Phase 14 Prompt: Evaluation / Observability

你是 NoteWeave 项目的编码代理。请执行 Phase 14：评测与可观测性。

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

## Subagent 分工模板

本节描述 AI 编码代理执行本 Phase 时允许使用的 subagent 协作方式。`subagent` 只表示编码执行分工，不是 NoteWeave 产品运行态 Agent/Skill 设计。

通用规则：

- 主代理是本 Phase 的 owner，负责最终集成、测试、文档更新和交付结论。
- subagent 必须先按本文档的“必读文档”顺序读取上下文，再开始自己的子任务。
- 每个 subagent 必须有明确 ownership，限定可修改的模块、文件或测试范围。
- 不允许多个 subagent 同时修改同一文件或同一模块；需要交叉修改时由主代理统一合并。
- 不允许 subagent 扩大当前 Phase 范围，遇到范围外问题只记录为遗留风险。
- 所有实现仍必须遵守 TDD：先写失败测试，再写最小实现，再重构和回归。
- subagent 产出必须由主代理 review 后合入，主代理不能直接信任未验证结果。

推荐分工：

```text
lead-agent:
- 读取全部必读文档，拆分任务，维护 Phase 边界。
- 负责最终集成、运行回归测试、更新 PROJECT_STATUS。

test-agent:
- 先写 PromptVersion、LLMCallLog、RetrievalTrace、RAG Eval、权限脱敏测试。
- 覆盖 Eval Run 不写正式会话和 Memory。

prompt-version-agent:
- 负责 PromptVersion 管理、激活、历史引用。
- 非管理员不能维护 PromptVersion。

llm-log-agent:
- 负责 LLMCallLog 统一包装、token/latency/error 记录和脱敏。
- 不向前端暴露完整敏感 Prompt。

trace-agent:
- 负责 RetrievalTrace、RetrievalTraceItem、最终 evidence 标记。
- 与 Team Chat / Studio / Personal Generation 链路集成。

feedback-agent:
- 负责 AnswerFeedback 管理查询和质量标签。
- 不实现复杂 A/B 实验平台。

eval-agent:
- 负责 RagEvalCase、RagEvalRun、RagEvalResult 和指标计算。
- Eval Run 必须隔离执行，不写正式会话和 Memory。

security-review-agent:
- 只做安全/隐私 review，不直接改代码。
- 重点检查日志脱敏、访问边界、Prompt/Memory/私有原文泄露。
```
## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_9_retrieval_enhancement_rrf.md
docs/features/phase_14_evaluation_observability.md
```

## 目标

实现评测和可观测性：

```text
LLMCallLog
RetrievalTrace
RAG Eval
Citation quality
UserFeedback
Task observability
Admin 可查看基础日志
```

## 严格边界

不要实现：

```text
题库测评
Quiz 打分
复杂 BI 大屏
商业化计费
```

## 必须遵守

- LLM 日志必须脱敏。
- 不直接保存完整私有原文和敏感 Prompt。
- RetrievalTrace 要能解释 BM25 / Vector / Wiki / RRF。
- 评测不能绕过权限读取用户无权资源。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
记录哪些日志
如何脱敏
如何关联 Task / Retrieval / Citation
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

