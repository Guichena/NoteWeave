# 产物生成 Agent 二次全面审查与简历验收

## 1. 审查目标

本轮以 `项目亮点与简历草案.md` 的产物 Agent 表述作为反向验收清单，逐项核对公开产品契约、Java 业务真源、Python Runtime、System MCP、对象存储、前端入口、恢复机制、安全边界和自动化测试。

结论：简历“受控式异步产物生成 Agent”主叙事已经具备真实代码和测试证据。本轮发现并修复了双向服务认证、dead-letter/redrive 原子性、lease 丢失误报成功、PDF 归档瞬时失败导致正文版本回滚四个缺口。未发现仍需要从简历删除的 Artifact 核心能力。

## 2. 简历声明—实现证据矩阵

| 简历声明 | 代码/数据证据 | 验收结论 |
| --- | --- | --- |
| 报告、FAQ、测验、学习指南、Wiki、Note、课程讲义 PDF | Java/Python 共享 catalog 包含 `report_draft / faq_draft / quiz_pack / study_guide / wiki_page / structured_note / bilibili_course_note_pdf`；跨语言 fixture 为 `reference/artifact-skill-catalog-v1.json` | 已实现 |
| schema-driven Skill | Java `/api/v2/skills` 返回 input schema；Java 创建前校验；Python runtime 二次校验；前端 `artifactStudio` 按 schema 构造输入 | 已实现 |
| Artifact Job / Version 稳定契约 | `artifact_job / artifact_version / artifact_job_run`，Java Controller 提供创建、查询、版本、再生成、比较、回滚 | 已实现 |
| ExecutionSpec + Skill Graph + Artifact Runtime | Python `compiler.py / intent_compiler.py / skill_graph.py / runner.py` 负责编译和执行，Java 不编译 graph | 已实现 |
| Schema-Gated Skill Graph Runtime | 每个节点执行前后做 contract/schema 校验，结果包含 node trace 与 output contract trace | 已实现 |
| Capability Union Policy | `capability_resolver.py / policy.py` 对系统 capability 组合、审批和 provider 状态进行决策并输出 typed trace | 已实现 |
| System MCP/provider 编排 | Bilibili Skill 使用 builtin server，形成 dispatch、provider execution、host ack、resume、complete | 已实现 |
| owner-scoped outbox lease | `task_outbox.lease_owner / lease_until`，claim/finalize 均校验 owner，支持过期租约回收 | 已实现 |
| dead-letter / redrive / 指标告警 | 五次失败进入 `DEAD_LETTER`；Task/Job 同步失败；人工重驱原子恢复三者；提供 Micrometer counters、内部 metrics 与错误级告警 | 已实现 |
| waiting / resume 与可恢复性 | acquisition、provider receipt、waiting queue 落盘；启动时恢复 system MCP operation | 已实现 |
| Verifier / Repair | 通用 output/evidence verifier、节点级 verifier、局部 repair；简历 Skill 还有六节点专用 verifier/repair graph | 已实现 |
| 终态幂等 | Java callback 阻止重复 complete 生成重复版本，也阻止迟到 progress/fail 让终态倒退 | 已实现 |
| 同 Job 追加版本 | 再生成创建新 Task 和 run snapshot；比较返回结构化差异；回滚追加副本而非覆盖历史 | 已实现 |
| typed runtime trace | Java DTO 与前端 audit view 显式消费 generation、node、verification、repair、waiting、provider、export trace | 已实现 |
| Markdown/PDF 对象存储与 checksum | `artifact_file` 保存 backend、bucket、object key、size、SHA-256、status/error；下载读取 ObjectStorage | 已实现 |
| 资料、Note/Wiki Host writeback | Java 显式执行 save-as-source 与 Knowledge NOTE/WIKI 写回；Worker 只给 intent/preview | 已实现 |
| 服务间认证边界 | Java `/internal/*` 和 Artifact Worker `/tasks/* /callbacks/* /internal/* /debug/*` 双向校验共享 token；两个 Worker callback 与 Java Worker client 自动带 header | 已实现 |

## 3. 本轮发现并修复的问题

### F1 双向认证缺口

修复前只有 Java `/internal/*` 校验 token，Artifact Worker 的正式 run/resume/ack/export 面仍可无认证访问。

修复后 Worker 在配置 token 时保护所有正式控制面；Java outbox publisher、control client 和 PDF acquisition client 自动携带 `X-NoteWeave-Internal-Token`。`/health` 保持公开用于容器健康检查。

### F2 dead-letter/redrive 原子性缺口

修复前 outbox、Task、Job 更新分成多个自动提交语句，不能支撑文档中的“原子恢复”。

修复后 dead-letter transition 通过 `TransactionTemplate` 在同一事务中完成；redrive 使用 `@Transactional` 同时恢复 outbox、Task、Job 并记录 `TASK_REDRIVEN`。只有 owner 匹配且状态转换成功才增加指标。

### F3 lease 丢失仍计成功

修复前 provider 调用成功后，即使 `SENT` 更新因 lease owner 不匹配而更新 0 行，代码仍增加 dispatched counter。

修复后只有 owner-scoped `SENT` transition 实际更新一行才计成功；lease 丢失会输出告警。

### F4 文件归档失败回滚正文版本

修复前 complete callback 内的 PDF 拉取或对象存储写入异常会回滚整个 ArtifactVersion，Worker 随后把任务标成失败。

修复后 `artifact_file` 支持 `READY / FAILED + error_message`。正文版本和 Task 可以正常完成；文件归档失败保留可审计状态，下载/补偿路径会使用同一 object key 重试并把 metadata 更新为 READY。

## 4. 简历安全边界

以下表述可以使用：

- “实现了 Skill-first 受控式异步产物生成 Agent 的完整工程闭环”。
- “实现 owner-scoped lease、dead-letter/redrive、typed runtime trace 和对象存储文件元数据”。
- “实现 System MCP only 的 Bilibili 讲义 PDF 异步链路”。
- “实现共享令牌形式的双向服务认证”。

以下内容不应扩大描述：

- 当前服务认证是部署级共享 secret，不是 mTLS、OAuth2 或零信任服务网格。
- 当前告警是 Micrometer counters、内部 metrics 和日志错误告警，不是完整 PagerDuty/短信告警平台。
- 版本比较是确定性的结构化行差异，不是语义级文档 diff。
- 未配置模型时使用明确标记的 deterministic fallback，不应宣称所有环境都由线上 LLM 生成。
- 产品只支持系统内置 MCP；custom registry 只保留在默认关闭的 debug/internal 兼容面。

## 5. 最终验收口径

产物 Agent 的简历主叙事可以按“已实现的工程原型”使用。是否称为生产级系统，仍取决于真实部署中的 secret 管理、MinIO/数据库高可用、外部告警渠道、容量压测和真实 Bilibili 网络成功率，这些属于部署与 SRE 验收，不应由单机自动化测试替代。

## 6. 自动化证据

- Artifact Worker：`192 passed`。
- Research Worker：`83 passed / 61 skipped`；跳过项为依赖可选外部环境的测试。
- Java Backend：`80 passed`，无 failure/error/skip。
- Frontend：`40 passed`，production build 通过。
- Docker Compose 配置与 Backend/Artifact Worker/Research Worker 镜像构建纳入最终交付检查。
