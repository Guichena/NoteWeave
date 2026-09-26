# 亮点 4：Schema 驱动的产物生成与受控 Agent 执行

## 1. 简历亮点对应的面试回答

报告、测验、课程笔记看起来只是不同 Prompt，真正落地后却有不同的输入资料、输出结构、工具权限、校验规则和文件交付流程。我的做法是把产物抽象成 Skill，而不是为每种产物复制一套 Prompt。Skill 定义输入 Schema、输出 Schema、资料要求、能力白名单、执行图和验证规则。Java Host 负责任务、权限、版本和最终提交，Python Worker 负责模型和工具执行。模型只能产出 Candidate，经过 Verifier 和局部 Repair 后才能生成用户可见 Version。

## 2. Skill、Schema 与 Graph

### 2.1 Skill Catalog

```text
SkillDefinition
├─ skill_key / version
├─ input_schema
├─ output_schema
├─ required_sources
├─ capability_policy
├─ execution_nodes
├─ verifier_rules
└─ delivery_policy
```

用户选择“PDF 课程笔记”或“测验”，而不是直接编辑 Prompt。Catalog 版本化后，运行时冻结 `skill_key + skill_version`，防止任务执行中规则漂移。

### 2.2 Skill Graph 编译

Compiler 根据 Skill、输入和可用能力生成 `ExecutionSpec`：加载上下文、获取资料、生成章节、校验、修复、渲染和归档。编译时检查节点类型、Schema、能力白名单、环依赖和资源预算。当前重点是受控图和状态机，不需要把所有自由 Agent 行为开放给模型。

### 2.3 Java Host 与 Python Worker

```text
Java Host
  ├─ 创建 ArtifactJob / Task
  ├─ 冻结 InputSnapshot / SkillVersion
  ├─ 校验 Workspace 与 Capability
  ├─ 接收 Candidate / Progress
  └─ 创建 ArtifactVersion 并提交

Python Worker
  ├─ 执行 ExecutionSpec
  ├─ 调用 LLM、Embedding、MCP
  ├─ 生成候选章节和中间结果
  ├─ 运行局部 Verifier / Repair
  └─ 回调 Host，不直接写业务版本
```

语言拆分的核心不是“Java 和 Python 都会”，而是把频繁变化的模型策略隔离在执行面，把事务、权限和版本提交留在稳定控制面。

## 3. 一次 PDF 讲义生成如何执行

1. Host 校验用户、Workspace、资料范围和输入 Schema。
2. 创建 `ArtifactJob`、唯一 `operation_id` 和冻结的 `InputSnapshot`。
3. Compiler 生成并持久化 `ExecutionSpec`，避免重试时重新解释用户输入。
4. Scheduler 根据 Workspace 配额申请速率令牌和并发租约。
5. Worker 按图执行资料加载、章节规划、内容生成、引用绑定和版式渲染。
6. 每个节点写入 Checkpoint 和外部调用 Receipt；失败只重试当前节点或其下游节点。
7. Verifier 检查 JSON / Markdown / PDF 结构、必填章节、引用定位、文件 Manifest 和安全策略。
8. 不合格结果生成结构化问题，Repair 只修复受影响节点，不能无限重做。
9. Host 创建内部 Artifact Version，等待必需文件全部 READY 后才提升为用户可下载状态。

## 4. MCP 的具体边界

MCP 用来统一音视频转写、视频元数据、内容理解等外部能力的发现、参数 Schema 和结果格式。生产只启用系统注册的 MCP，能力经过 Host 编译进 Capability Policy；用户不能通过 Prompt 临时添加任意工具。每次调用携带 Workspace、Source Snapshot、operation_id 和超时预算，结果写入 Receipt，便于重试和对账。

MCP 不替代权限系统，也不代表工具调用一次就一定成功。它只是执行面协议，最终是否能被引用、写入 Artifact 或晋升为版本，仍由 Host 和 Verifier 决定。

## 5. Quota、租约与状态

速率和并发是两个不同约束：令牌桶控制单位时间内提交多少任务，并发租约控制同时运行多少个长任务。Redis Lua 在一次脚本中完成补令牌、扣减和租约清理，避免多个 Host 读改写竞争。Worker 活跃时续租，宕机后租约过期；释放时携带 Token，防止旧任务误删新租约。Redis 不可用时，生成类高成本任务默认 Fail Closed，避免配额失控。

Job 状态和配额不是同一个生命周期：Job 可以长期排队，Active Permit 只在真正执行时占用。任务终态写入 MySQL 后，即使 Redis 释放失败，也可由对账任务回收过期租约。

## 6. 三种“成功”必须拆开

```text
内容成功       = Schema / 引用 / 质量校验通过
投递成功       = 文件上传和 Manifest 记录成功
交付成功       = 所有必需文件 READY，版本可见
```

模型返回一段文本不等于产物成功；Worker 回调 200 也不等于文件可下载。把三者拆开，才能处理“内容已通过但 PDF 上传失败”“Callback 超时但文件已经存在”等未知结果。

## 7. 关键 Trade-off

- **受控 Skill Graph，而不是自由 Agent：** 保留模型在内容生成和局部决策上的灵活性，但把能力、Schema、权限和完成条件交给程序；代价是图编译和状态管理更复杂。
- **MCP，而不是每个能力一个 HTTP Adapter：** 统一工具协议和 Schema；代价是多一个协议边界，单个简单能力不一定值得引入。
- **局部 Repair，而不是整份重生成：** 降低成本和结构漂移；代价是需要维护节点依赖和问题定位。
- **Redis 限流 + MySQL 任务状态：** Redis 适合短期原子协调，MySQL 适合审计和恢复；代价是需要处理双系统对账。
- **Host 提交版本，而不是 Worker 直写：** 保护权限和版本不变量；代价是多一次回调和提交协议。

## 8. 面试官高频追问

**问：这和固定 Workflow 有什么区别？**  
节点类型和安全边界是固定的，但 Graph 可以按 Skill、输入和可用能力编译；模型只能在受控节点内选择内容和局部修复路径。因此它是受控 Agentic Workflow，不是任意自由调用。

**问：为什么 Worker 不能直接写 Artifact 表？**  
Worker 可能重复、超时或晚到，且不应该自行决定 Workspace 权限和版本可见性。Host 通过幂等键、Lease、Fencing 和 Schema 门禁统一提交。

**问：MCP 调用超时怎么办？**  
按 operation_id 对账。已确认成功则复用 Receipt，确认失败才重试，无法确认时进入 UNKNOWN_OUTCOME，不能简单把 HTTP 超时当作没有副作用。

