# 阶段4：产物生成 Agent（现行状态）

> 更新于 2026-07-11。现行冻结需求、冲突审计和剩余事项以 `docs/产物生成Agent需求审计与收尾改造.md` 为准。本文件不再使用 action-first 或用户自定义 MCP 作为产品口径。

## 1. 阶段目标

右侧产物栏对外只暴露系统内置 Skill。用户以 `skillKey + userRequirement + inputs` 创建异步 Artifact Job；Java 维护业务真源与任务状态，Python Worker 编译并执行内部 RuntimePlan，最终形成可审计的 Artifact Version。

## 2. 已完成主闭环

1. Java 已提供 Skill catalog、Artifact Job/Version、冻结 source scope、worker input、终态幂等 callback 和版本详情。
2. Artifact outbox 已具备自动调度、单记录 claim、失败退避和陈旧 claim 回收，不再要求人工 dispatch。
3. Python 已具备 Intent Compiler、ExecutionSpec、Skill Graph、Capability Policy、source acquisition、Verifier/Repair、可选 OpenAI-compatible LLM 生成与显式 deterministic fallback。
4. waiting、provider delivery、callback receipt 和 resume 具备 typed trace；获取运行态与等待队列已落盘，可在重启后恢复。
5. Bilibili 专用 Skill 使用系统 MCP，自动执行字幕获取、host ack 与 resume；PDF 优先走 XeLaTeX，并具备嵌入中文字体的 ReportLab 真实 PDF fallback。
6. Artifact Version 可显式保存为工作台资料或由 Java host 写回 Note/Wiki；PDF 成功编译后可经 Java 主系统代理下载。
7. 前端支持 schema-driven Skill 表单、任务状态、历史版本、runtime audit、保存为资料、Note/Wiki 写回和 PDF 下载。
8. Markdown/PDF 已统一归档到 ObjectStorage，版本详情返回 `artifact_file` typed metadata；PDF 下载不再以 Worker 临时目录作为真源。
9. 同一 Job 支持新 Task 再生成、版本比较与追加式回滚，并以 `artifact_job_run` 保存每次运行输入快照。
10. outbox 具备 owner-scoped lease、dead-letter、metrics 和人工 redrive；`/internal/*` 支持部署级共享令牌认证。

## 3. 关键约束

1. `Production Action / Style Profile / Skill Graph / Prompt Recipe / MCP binding` 只属于 Python 内部运行时。
2. 产品不开放用户自定义 MCP；遗留 custom registry 仅限内部兼容/调试，并且 `/debug/*` 默认关闭。
3. Java `ArtifactVersion` 是正式业务真源；worker 文件 repository 只承担运行态快照与二进制导出暂存。
4. Memory Control Pack 只约束风格、结构、证据和禁用边界，不替代 source facts。
5. 未配置模型时必须显式记录 fallback；配置模型时正文必须消费冻结 source content。

## 4. 当前验证基线

- Artifact Worker：`192 passed`。
- Research Worker：`83 passed / 61 skipped`（验证共享 internal auth header 未破坏 Research 回调）。
- Java Backend：全量 `80 passed`。
- Frontend：`40 passed`，production build 通过。

## 5. 后续增强

本轮指定的生产化事项已经闭环。后续可按运行规模继续增加 provider 超时运维 UI、外部告警渠道、模型成本/延迟指标和 prompt injection 防护。
