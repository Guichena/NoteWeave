# 产物生成 Agent 需求审计与收尾改造

## 1. 文档定位

本文档是产物生成 Agent 的现行需求基线、代码审计结论和收尾施工清单，审计基线日期为 `2026-07-10`。

当产物相关文档发生冲突时，优先级固定为：

1. 本文档的冻结需求与验收标准。
2. `产物生成Skill优先重构设计.md`。
3. `产物生成Agent独立模块施工文档.md`。
4. 当前自动化测试和可运行代码事实。
5. 其他总体、阶段或历史设计文档。

`受控式异步产物生成Agent编排升级设计.md`、`阶段计划/阶段4-产物生成Agent.md` 和 `Research与Artifact任务骨架施工设计.md` 中仍以 `Production Action / action_key / 用户自定义 MCP` 为产品主语的内容，均视为历史设计输入，不再覆盖本文件的冻结口径。

## 2. 最终冻结需求

### R1 产品主语

- 对外只暴露 `Skill / Artifact Job / Artifact Version`。
- 创建请求固定为 `skillKey + userRequirement + inputs`。
- `Production Action / Style Profile / Skill Graph / Prompt Recipe / MCP binding` 仅属于 Python runtime 内部对象。

### R2 产品形态

- 中间保持聊天主界面，最右侧是系统内置 Skill 列表。
- 表单完全由 `inputSchema` 驱动。
- 用户不编辑 action、graph、node 或 MCP 组合。

### R3 Java 与 Python 边界

- Java 是业务真源和任务控制面，负责 workspace 校验、Job/Version、source scope 固化、outbox、状态机、callback 和 typed outward contract。
- Python 是唯一 runtime brain，负责 Intent Compiler、ExecutionSpec、RuntimePlan、Skill Graph、Capability Policy、Verifier/Repair 和 provider 编排。
- Java 不编译 RuntimePlan，也不决定 graph/capability 路径。

### R4 异步任务闭环

- 前端创建 Job 后，系统必须自动投递，不依赖人工调用 debug/internal dispatch。
- outbox 至少提供单记录 claim、失败回退、有限退避和陈旧 claim 回收。
- worker callback 必须具备终态幂等性，重复投递不能生成重复 ArtifactVersion，也不能让完成态倒退为 running/waiting/failed。

### R5 Source Scope

- 创建 Job 时冻结本次任务允许读取的 source ID 集合。
- worker 拉输入时只能读取被冻结的集合，不能重新纳入创建后新增的资料。
- Memory Control Pack 只控制风格、结构、受众、禁用路径和证据边界，不充当事实来源。

### R6 Skill 与输入契约

- Java `/api/v2/skills` 是前端配置来源，Java 在落库前执行 schema gate。
- Python 再做同口径运行时校验，防止绕过 Java。
- Java 与 Python 的内置 Skill key、公开 schema、默认值和 required 语义必须有跨语言契约测试，避免双份 catalog 漂移。
- URL alias 只能作为 Python 历史兼容，不重新进入公开 schema。

### R7 真实生成能力

- 未配置模型时允许使用确定性 fallback，以保证本地测试和可演示性。
- 配置 OpenAI-compatible 模型后，正文生成必须真实消费 source content、ExecutionSpec、Style Profile、输出 contract 和引用信息。
- fallback 必须明确记录 runtime mode，不能把硬编码模板包装成“LLM 已生成”。

### R8 System MCP only

- 产品只使用 system-managed MCP/provider registry。
- `bilibili-render-pdf` 的正式 server/provider id 必须是 system 口径，不能依赖用户注册 `custom-*` server 后才可运行。
- B 站长任务必须形成 `dispatch -> provider execution -> host ack -> worker resume -> Java complete` 的自动闭环。
- 施工期 custom registry 如继续保留，只能作为 internal/debug 兼容设施，不能进入产品 API、默认路由或简历完成态叙事。

### R9 Verifier / Repair 与审计

- 结果必须经过 output contract、evidence、node-level verifier 和 local repair。
- `ArtifactVersion.runtime_trace` 是唯一稳定的 outward runtime 审计出口。
- waiting、resume、provider delivery、repair、complete 必须形成 typed trace；公开视图不得回流 action-first 字段。

### R10 版本、导出与回写

- Java `ArtifactVersion` 是正式版本真源；worker 自有 repository 只能承担运行时快照/调试，不得成为第二业务真源。
- Markdown 结果必须可回看。
- `保存为资料`、Note/Wiki 回写和文件导出必须由 Java host 显式执行；worker 只产生 writeback intent/preview，不直接写业务真源。
- B 站 PDF 只有在 Java 可追踪到正式文件 locator/object metadata 时才可宣称完成 PDF 交付。

### R11 可恢复性与安全边界

- waiting/provider receipt 的关键恢复状态不能只存在进程内内存；进程重启后必须能发现或明确失败任务。
- debug mutation 路由不得作为默认公网产品接口。
- 外部输入、provider payload 和模型输出都必须经过大小限制与 schema 校验。

### R12 测试与文档

- Worker、Java contract、frontend unit/build 必须全绿。
- 同一公开字段不能同时存在“必须出现”和“必须隐藏”的相反测试。
- 文档必须区分 `已实现 / 部分实现 / 未实现 / 历史方案`，简历不得把部分实现写成完全生产化。

## 3. 2026-07-10 施工前审计结果（历史基线）

> 本节保留修复前的差距证据，用于说明本轮改造为何发生，不代表 2026-07-11 的当前状态；当前状态以第 7 节完成记录为准。

### 3.1 已实现

1. 前端已使用 `skillKey + userRequirement + inputs` 创建任务。
2. `/api/v2/skills`、schema-driven 右栏和输入校验已落地。
3. Java 已具备 ArtifactJob 创建、列表、详情、版本列表和版本详情。
4. Java 到 worker 的正式 run/resume/acquisition-ack 控制接口已存在。
5. worker 已具备 Intent Compiler、ExecutionSpec、RuntimePlan、Skill Graph、Capability Resolver、Verifier/Repair 骨架。
6. `resume_highlight_v1` 已是显式六节点图，并有 node trace/repair trace。
7. waiting、provider delivery attempts、callback receipt、runtime trace 已有 typed Java/前端消费面。
8. 前端已能展示当前任务、历史版本、runtime audit、waiting/retry/provider signals。
9. Java Artifact 合约测试、前端 40 个测试和前端 production build 在审计基线通过。

### 3.2 部分实现

1. outbox 有正式 HTTP dispatcher，但没有自动调度，且缺少可靠 claim/retry。
2. source ID 在创建时保存，但 worker input 重新读取当前全部 READY source，快照语义失效。
3. callback/resume 可跑通，但 Java 终态没有幂等防护，重复或迟到 callback 可生成重复版本或回退状态。
4. Skill catalog 在 Java/Python 各维护一份，当前缺少跨语言一致性测试；`course_notes` 的 URL required 语义已经漂移。
5. Skill Graph/Verifier/Repair 结构真实存在，但正文主要由确定性硬编码模板生成，尚无 Artifact Worker 模型生成路径。
6. B 站链路能进入 waiting，也有 MCP server 脚本，但默认 system provider 没有自动接到该执行器；可执行路径仍依赖 custom MCP 注册模型。
7. acquisition/wait/writeback runtime 主要使用进程内状态，重启恢复不足。
8. worker 有 writeback preview/runtime，但 Java 没有 Artifact 保存为资料/Note/Wiki 的正式 host endpoint。
9. worker 有文件型 runtime repository，但 Java ArtifactVersion 才是正式真源，两者的职责说明仍不够清晰。

### 3.3 未实现或不能按完成态宣称

1. `POST ArtifactJob` 后无需人工操作的自动端到端运行闭环。
2. 配置模型后的真实 source-grounded Artifact 内容生成。
3. system-managed `bilibili-render-pdf` 自动执行、回调、恢复和正式 PDF 文件交付。
4. Artifact 显式保存为资料以及 Note/Wiki host writeback。
5. provider waiting 状态的进程重启恢复。

### 3.4 测试基线

- `workers/artifact-worker`: `174 passed / 3 failed`。3 个失败来自旧测试仍要求公开 `supported_actions / action_type / allowed_actions`，与同文件集的新 skill-first 隐藏契约直接冲突。
- `frontend`: `40 passed`。
- `frontend build`: 通过。
- Java `Phase6ResearchArtifactContractTest + ArtifactWorkerInputPayloadTest`: 通过。

## 4. 文档冲突清单

| 文档 | 冲突 | 处理决定 |
| --- | --- | --- |
| `受控式异步产物生成Agent编排升级设计.md` | 用户选择 Production Action、Style Profile、自定义 MCP；Execution Plan 在进入 ArtifactJob 前生成 | 标记为历史研究稿；现行链路是先创建 Job，Python 再编译计划；产品 system MCP only |
| `项目亮点与简历草案.md` | 宣称管理“用户自定义 MCP”组合风险 | 改为“系统内置 capability 组合风险”；真实模型生成和 PDF 未完成前使用 prototype 表述 |
| `系统总体设计与功能总表.md` | 用户新增产物形式、用户自有 MCP、Action-first 右栏 | 收口为系统内置 Skill；自定义能力不属于 V1 产品面 |
| `系统架构设计.md` | 右栏按钮本质是 Production Action | 改为右栏选择 Skill，Action 仅 Python 内部绑定 |
| `技术架构详细设计.md` | Java Artifact 模块管理 Production Action/Prompt Recipe；主链路先 Resolve Action | Java 只维护 Skill catalog outward view 与 Job/Version；Action/Recipe 收回 Python |
| `最小接口契约.md` | Artifact 请求仍是 `action_key/style_profile_key/context_snapshot_id`，且称 artifact publisher 未接 | 更新为 skill-first 请求和 HTTP outbox dispatch/wait/resume contract |
| `数据库迁移与建表顺序.md` | 迁移编号、artifact 表和配置表与真实 V012/V018/V019/V021 不符 | 标为目标模型而非已执行迁移，并补真实迁移索引 |
| `Research与Artifact任务骨架施工设计.md` | 称 Artifact 未接 Skill/MCP、仍 action-first | 标记旧基线并链接本文 |
| `阶段计划/阶段4-产物生成Agent.md` | action-first 且“再决定是否接 Skill/MCP” | 更新为当前 skill-first 阶段与剩余 P0/P1 |
| `workers/README.md` | 称 Artifact Worker 只是占位 loop | 更新为实际 run/resume/callback/provider/runtime 状态 |

## 5. 收尾施工优先级

### P0：必须修复后才能认为 V1 主闭环成立

1. 修复 Worker 内相反的公开字段测试。
2. 冻结 Artifact source scope，并补“创建后新增资料不会进入旧 Job”的 Java 合约测试。
3. 增加 Artifact outbox 自动调度、claim、失败退避与陈旧 claim 回收。
4. 增加 callback 终态幂等，阻止重复版本和状态倒退。
5. 对齐 Java/Python Skill schema，并增加 catalog contract fixture/test。
6. 接入 Artifact Worker 可选 LLM generation，保留明确标识的 deterministic fallback。
7. 把 B 站 provider 收口为 system MCP，并打通自动 host callback/resume；如果运行依赖不可用，必须失败而不是永久 WAITING。
8. 更新所有会误导施工的主文档和 README。

### P1：完整产品闭环

1. Java ArtifactVersion 保存为资料。
2. Note/Wiki 显式 host writeback。
3. Markdown/PDF 文件进入对象存储并在版本详情返回 typed file metadata。
4. acquisition/wait/provider receipt 持久化与重启恢复。
5. 版本重生成、比较与回滚的 Java 产品接口。

### P2：生产化增强

1. 多实例 dispatcher 的数据库锁/租约与指标告警。
2. debug/internal 路由鉴权和默认关闭。
3. provider 超时扫描、dead-letter、人工重驱与审计后台。
4. 大上下文预算、prompt injection 防护、模型成本/延迟指标。

### 5.3 本轮落地状态

- P0 1-8 已全部实施并有自动化证据。
- P1 的“ArtifactVersion 保存为资料”和“acquisition/wait/provider receipt 重启恢复”已完成。
- P1 已全部完成：Markdown/PDF 会归档到统一 ObjectStorage，并以 `artifact_file` typed metadata 返回；Note/Wiki host writeback、重启恢复也已落地。
- 版本语义冻结为“同一 Artifact Job 追加版本”：`artifact_job_run` 保存每次 Task 输入快照；再生成创建新 Task，比较返回结构化行差异，回滚通过追加副本实现，不覆盖历史版本。
- P2 已完成数据库 lease owner/expiry、多实例安全 claim、五次失败 dead-letter、指标查询、人工重驱，以及可配置的 `/internal/*` 共享令牌认证；`/debug/*` 继续默认关闭。
- 仍可继续增强的是 provider 超时扫描的独立运维 UI、告警渠道、大上下文预算、prompt injection 防护和模型成本/延迟指标，不再属于本轮四项缺口。

## 6. V1 验收标准

V1 完成必须同时满足：

1. 创建 `resume_highlight` 后无需人工 dispatch 即可完成并产生且只产生一个 Java ArtifactVersion。
2. 创建后新增的 source 不会进入旧任务 worker input。
3. 重复 outbox 投递、重复 complete callback、完成后的迟到 progress/fail 不会改变终态或新增版本。
4. Java/Python 对内置 Skill key、公开 input schema、默认值和 required 语义一致。
5. 无模型配置时 fallback 可运行且 trace 标记为 fallback；有模型配置时真实消费 source content 生成结构化 sections。
6. `resume_highlight` 保持六节点 graph、output contract、Verifier/Repair 与 runtime trace。
7. `bilibili_course_note_pdf` 使用 system provider；成功时自动 ack/resume，失败时进入 FAILED 并给出可审计错误，不得永久卡死。
8. frontend tests/build、Artifact Worker 全量 pytest、Java Artifact 合约测试全部通过。
9. 主文档、接口文档、阶段文档、README 与代码口径一致。

## 7. 完成记录

本节只记录本轮实际完成并验证的改动；未验证事项不得写入。

1. 修正 3 个仍要求暴露 legacy action 字段的 Worker 反向测试，公开契约统一为 Skill-first。
2. `artifact_job.source_scope_json` 成为 worker input 的冻结读取范围，并增加“创建后新增资料不进入旧任务”的 Java 合约测试。
3. 新增 `V022` outbox 可靠性迁移、默认自动调度器、claim、指数退避和陈旧 claim 回收。
4. callback 对 `COMPLETED / FAILED / CANCELLED` 终态幂等，重复 complete 和迟到 progress/fail 不再新增版本或回退状态。
5. 新增 `reference/artifact-skill-catalog-v1.json`，Java/Python 以同一 fixture 校验公开 Skill key/schema/default/required。
6. Worker 开始真实消费 `sample_text`；可选 OpenAI-compatible LLM 生成消费 source content、ExecutionSpec 与 contract，缺少配置或响应不合格时显式回退并记录 `generation_trace`。
7. Bilibili provider 收口为 system MCP，打通异步 provider execution、Java host ack 和自动 resume；部署运行时提供 `yt-dlp + faster-whisper`，PDF 优先使用 XeLaTeX、不可用时由 ReportLab + 嵌入式中文字体生成真实 A4 PDF，结果含 typed `export_trace`。
8. Worker 获取运行态与等待队列持久化；`/debug/*` 默认 404，仅正式 run/resume/callback/export 面默认开放。
9. 新增 Artifact Version 保存为工作台资料的 Java host endpoint，完成对象存储、source/snapshot、parse/index 与幂等映射；前端提供“保存为资料”。
10. 新增 PDF Worker 下载端点、Java 主系统代理下载端点和前端“下载 PDF”入口；仅 `export_trace.status=COMPILED` 时可下载。
11. 所有主要冲突文档均增加现行优先级或历史基线标记；阶段 4、最小接口契约、Worker README 和简历表述已改成当前事实。
12. 验证结果：Artifact Worker `192 passed`；Research Worker `83 passed / 61 skipped`；Java Backend 全量 `80 passed`；Frontend `40 passed` 且 production build 通过。
13. 新增 Artifact Version 到 `NOTE / WIKI` 的 Java host writeback endpoint 和前端显式入口；正文通过既有 `KnowledgeService` 进入知识条目与版本真源，Wiki 同名写回沿用追加版本语义。
14. 新增 `V023`：`artifact_job_run / artifact_file / origin_task_id / lease_owner / lease_until / dead_lettered_at`，完成运行快照、文件真源和多实例 lease 数据模型。
15. Worker complete callback 会将 Markdown 以及已编译 PDF 归档到统一 ObjectStorage，记录 backend、bucket、object key、size 和 SHA-256；版本详情与前端展示 typed file metadata，下载只读对象存储，不再依赖 Worker 临时文件。
16. 产品版本语义冻结为同 Job 追加版本；新增再生成、上一版比较和追加式回滚 Java/前端入口，旧版本通过 `origin_task_id` 保持文件归属。
17. Artifact outbox 增加 owner-scoped lease、最大五次投递、`DEAD_LETTER`、Micrometer counters、内部 metrics、死信变化错误级告警、dead-letter 列表和人工 redrive；进入死信时 Task/Job 同步失败，重驱时三者一起恢复，避免任务永久停在假 QUEUED 状态。
18. `/internal/*` 增加恒定时间共享令牌校验；Artifact/Research Worker 与 system MCP host ack 自动携带 `X-NoteWeave-Internal-Token`，Compose 默认注入同一开发令牌。
19. 二次简历反向验收补齐双向服务认证：Artifact Worker 的 run/resume/provider ack/export/internal/debug 控制面在配置 token 时同样拒绝未认证请求，Java publisher/control/export client 自动带 header。
20. dead-letter transition 改为显式事务，redrive 同事务恢复 outbox/Task/Job；owner 丢失时不再误计 dispatch success。
21. `artifact_file` 增加 `READY / FAILED + error_message` 补偿语义；PDF/ObjectStorage 暂时失败不再回滚已经生成的 Markdown 版本，后续访问可重试归档。
22. 逐项简历声明、代码证据、修复项和安全表述见 `产物生成Agent二次全面审查与简历验收.md`。
