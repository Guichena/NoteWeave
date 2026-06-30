# NoteWeave v2 后端模块与 MySQL 落地设计

## 1. 目标

这份文档只回答两个实现问题：

1. 后端代码按什么模块落地最合理
2. 核心亮点对应的 MySQL 表如何先落一版可用 DDL

范围明确控制在当前最重要的五条主线：

- `Deep Research`
- `Artifact Runtime`
- `RAG / Wiki / Note`
- `Graduated Memory`
- `文件上传 / Kafka / MinIO`

不追求一开始把所有能力做成平台，而是先把主链路打通。

## 2. 落地原则

### 2.1 模块化单体

后端推荐继续采用：

`Spring Boot API App + Spring Boot Worker + MySQL + Kafka + MinIO + Elasticsearch`

其中：

- `api-app` 负责接口、轻编排、状态查询、短链路同步处理
- `worker` 负责解析、索引、Deep Research、Artifact Job、Memory Promotion

### 2.2 一条主链路只保留一个主对象

避免一个功能落成多套相近对象：

- Deep Research 以 `research_run` 为主对象
- 产物生成以 `artifact_job` 为主对象
- 记忆晋升以 `memory_candidate` 为主对象

### 2.3 先把配置对象做成“注册表”，不做图形化平台

当前只需要：

- `production_action`
- `style_profile`
- `prompt_recipe`
- `skill_graph_template`
- `mcp_capability_binding`
- `capability_union_policy`

不需要先做可视化编排器。

## 3. 后端模块划分

推荐包结构：

```text
com.noteweave
  ├── common
  ├── auth
  ├── workspace
  ├── source
  ├── retrieval
  ├── knowledge
  ├── research
  ├── artifact
  ├── memory
  ├── task
  ├── capability
  └── observability
```

### 3.1 `source`

职责：

- 文件上传
- URL / 文本导入
- 原始文件落 MinIO
- 解析文本生成
- chunk 生成与索引
- SourceWindow 回源

核心类建议：

- `SourceApplicationService`
- `UploadSessionService`
- `SourceParserWorker`
- `ChunkIndexWorker`
- `SourceWindowQueryService`

### 3.2 `retrieval`

职责：

- Ask 检索
- Note / Wiki / Artifact 检索
- metadata 检索
- citation 回源

核心类建议：

- `HybridRetrievalService`
- `ArtifactRetrievalService`
- `MetadataSearchService`
- `CitationResolver`

### 3.3 `knowledge`

职责：

- Note / Wiki / Investigation Note 管理
- 版本管理
- 显式回写

核心类建议：

- `KnowledgeItemService`
- `KnowledgeVersionService`
- `WikiPublishService`

### 3.4 `research`

职责：

- `Research Run` 生命周期
- `Research Table` 状态
- `Stop Contract`
- `Branch / Checkpoint`
- 研究报告生成

核心类建议：

- `ResearchRunService`
- `ResearchHarnessService`
- `ResearchTableService`
- `ResearchVerifier`
- `ResearchTraceService`
- `ResearchReportService`

### 3.5 `artifact`

职责：

- `Artifact Job` 生命周期
- `Production Action` 解析
- `Style Profile` 加载
- `Skill Graph Template` 展开
- `Prompt Recipe` 运行
- `Artifact Version` 提交与回流

核心类建议：

- `ArtifactJobService`
- `ProductionActionResolver`
- `StyleProfileService`
- `SkillGraphPlanner`
- `PromptRecipeRunner`
- `ArtifactCommitService`

### 3.6 `memory`

职责：

- `Context Signal` 抽取
- `Memory Candidate` 生成
- 双门控校验
- `Memory Object` 晋升与回读

核心类建议：

- `ContextSignalExtractor`
- `MemoryCandidateService`
- `NoveltyGate`
- `TaskNeighborhoodValidator`
- `MemoryPromotionService`

### 3.7 `task`

职责：

- 统一异步任务调度
- 外盒投递
- 重试 / 取消 / 幂等

核心类建议：

- `TaskDispatcher`
- `TaskOutboxPublisher`
- `TaskWorkerExecutor`
- `TaskRetryPolicy`

### 3.8 `capability`

职责：

- MCP 能力注册
- capability binding 解析
- `Capability Union Policy` 门控
- 外部工具适配

核心类建议：

- `McpCapabilityRegistry`
- `CapabilityBindingService`
- `CapabilityUnionPolicyService`
- `ExternalToolGateway`

## 4. 核心运行链路

### 4.1 Deep Research

```text
POST /api/v2/research/runs
  -> ResearchRunService.createRun()
  -> TaskDispatcher.dispatch(RESEARCH_RUN)
  -> worker 执行 ResearchHarnessService
  -> 更新 research_row / research_cell / research_trace
  -> 生成 artifact + artifact_version
```

### 4.2 Artifact Runtime

```text
POST /api/v2/artifacts/jobs
  -> ArtifactJobService.createJob()
  -> ProductionActionResolver.resolve()
  -> StyleProfileService.load()
  -> TaskDispatcher.dispatch(ARTIFACT_JOB)
  -> worker 执行 SkillGraphPlanner + PromptRecipeRunner
  -> ArtifactCommitService.commit()
```

### 4.3 Graduated Memory

```text
Artifact confirmed / Research completed / Conversation summarized
  -> ContextSignalExtractor
  -> MemoryCandidateService.createCandidate()
  -> NoveltyGate
  -> TaskNeighborhoodValidator
  -> MemoryPromotionService.promote()
```

## 5. MySQL 设计范围

当前 DDL 只覆盖一版最小可用核心表：

- 工作区与资料
- Deep Research
- Artifact Runtime
- Graduated Memory
- Task / Outbox

不在这一版展开：

- 全量权限细节表
- 复杂评估表
- 可视化编排器配置表
- 多租户计费表

## 6. MySQL DDL 草案

说明：

- 默认 `MySQL 8.4`
- 字符集统一 `utf8mb4`
- 时间统一 `datetime(3)`
- JSON 字段只承接配置、快照和运行态，不作为主检索字段
- 对于 `artifact.latest_version_id`、`research_run.latest_artifact_id` 这类容易形成循环依赖的字段，首版可先保留普通列和应用层约束，不强上外键

### 6.1 工作区与资料

```sql
create table workspace (
  id bigint primary key auto_increment,
  workspace_key varchar(64) not null,
  name varchar(255) not null,
  status varchar(32) not null,
  created_by bigint not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_workspace_key (workspace_key)
) engine=InnoDB default charset=utf8mb4;

create table topic_scope (
  id bigint primary key auto_increment,
  workspace_id bigint not null,
  scope_key varchar(64) not null,
  title varchar(255) not null,
  scope_type varchar(32) not null,
  status varchar(32) not null,
  created_by bigint not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_topic_scope_key (workspace_id, scope_key),
  key idx_topic_scope_workspace (workspace_id, updated_at),
  constraint fk_topic_scope_workspace foreign key (workspace_id) references workspace(id)
) engine=InnoDB default charset=utf8mb4;

create table source (
  id bigint primary key auto_increment,
  workspace_id bigint not null,
  topic_scope_id bigint null,
  source_type varchar(32) not null,
  title varchar(255) not null,
  raw_object_key varchar(512) null,
  parsed_text_object_key varchar(512) null,
  metadata_json json null,
  import_status varchar(32) not null,
  parse_status varchar(32) not null,
  created_by bigint not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_source_workspace (workspace_id, created_at),
  key idx_source_scope (topic_scope_id, created_at),
  constraint fk_source_workspace foreign key (workspace_id) references workspace(id),
  constraint fk_source_scope foreign key (topic_scope_id) references topic_scope(id)
) engine=InnoDB default charset=utf8mb4;
```

### 6.2 Deep Research

```sql
create table research_run (
  id bigint primary key auto_increment,
  workspace_id bigint not null,
  topic_scope_id bigint null,
  run_title varchar(255) not null,
  research_question varchar(2000) not null,
  run_status varchar(32) not null,
  budget_policy_json json null,
  research_plan_json json null,
  stop_contract_json json null,
  replan_policy_json json null,
  current_branch_id bigint null,
  latest_artifact_id bigint null,
  created_by bigint not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_research_run_workspace (workspace_id, run_status, created_at),
  key idx_research_run_scope (topic_scope_id, created_at),
  constraint fk_research_run_workspace foreign key (workspace_id) references workspace(id),
  constraint fk_research_run_scope foreign key (topic_scope_id) references topic_scope(id)
) engine=InnoDB default charset=utf8mb4;

create table research_branch (
  id bigint primary key auto_increment,
  research_run_id bigint not null,
  parent_branch_id bigint null,
  branch_name varchar(255) not null,
  branch_reason varchar(255) null,
  branch_status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_research_branch_run (research_run_id, created_at),
  constraint fk_research_branch_run foreign key (research_run_id) references research_run(id),
  constraint fk_research_branch_parent foreign key (parent_branch_id) references research_branch(id)
) engine=InnoDB default charset=utf8mb4;

create table research_row (
  id bigint primary key auto_increment,
  research_run_id bigint not null,
  branch_id bigint null,
  row_key varchar(128) not null,
  row_title varchar(255) not null,
  entity_type varchar(64) null,
  row_status varchar(32) not null,
  confidence_score decimal(5,4) null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_research_row_key (research_run_id, row_key),
  key idx_research_row_branch (branch_id, updated_at),
  constraint fk_research_row_run foreign key (research_run_id) references research_run(id),
  constraint fk_research_row_branch foreign key (branch_id) references research_branch(id)
) engine=InnoDB default charset=utf8mb4;

create table research_cell (
  id bigint primary key auto_increment,
  research_row_id bigint not null,
  column_key varchar(128) not null,
  cell_value text null,
  cell_status varchar(32) not null,
  confidence_score decimal(5,4) null,
  evidence_count int not null default 0,
  verifier_result varchar(32) null,
  conflict_status varchar(32) null,
  retry_count int not null default 0,
  active_subgoal varchar(255) null,
  last_updated_at datetime(3) not null default current_timestamp(3),
  unique key uk_research_cell (research_row_id, column_key),
  key idx_research_cell_status (cell_status, last_updated_at),
  constraint fk_research_cell_row foreign key (research_row_id) references research_row(id)
) engine=InnoDB default charset=utf8mb4;

create table research_trace (
  id bigint primary key auto_increment,
  research_run_id bigint not null,
  branch_id bigint null,
  trace_type varchar(64) not null,
  payload_json json not null,
  created_at datetime(3) not null default current_timestamp(3),
  key idx_research_trace_run (research_run_id, created_at, id),
  key idx_research_trace_branch (branch_id, created_at),
  constraint fk_research_trace_run foreign key (research_run_id) references research_run(id),
  constraint fk_research_trace_branch foreign key (branch_id) references research_branch(id)
) engine=InnoDB default charset=utf8mb4;
```

### 6.3 Artifact Runtime

```sql
create table style_profile (
  id bigint primary key auto_increment,
  profile_key varchar(64) not null,
  profile_name varchar(255) not null,
  audience_level varchar(64) null,
  tone varchar(64) null,
  length_target varchar(64) null,
  structure_preference_json json null,
  citation_density varchar(32) null,
  language_policy varchar(32) null,
  formatting_policy_json json null,
  status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_style_profile_key (profile_key)
) engine=InnoDB default charset=utf8mb4;

create table prompt_recipe (
  id bigint primary key auto_increment,
  recipe_key varchar(64) not null,
  recipe_name varchar(255) not null,
  recipe_version int not null,
  scenario_type varchar(64) not null,
  template_markdown mediumtext not null,
  output_contract_json json null,
  status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_prompt_recipe_ver (recipe_key, recipe_version)
) engine=InnoDB default charset=utf8mb4;

create table skill_graph_template (
  id bigint primary key auto_increment,
  graph_key varchar(64) not null,
  graph_name varchar(255) not null,
  graph_version int not null,
  scenario_type varchar(64) not null,
  node_schema_json json not null,
  edge_schema_json json not null,
  fallback_policy_json json null,
  allowed_capability_set_json json null,
  status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_skill_graph_ver (graph_key, graph_version)
) engine=InnoDB default charset=utf8mb4;

create table production_action (
  id bigint primary key auto_increment,
  action_key varchar(64) not null,
  display_name varchar(255) not null,
  group_name varchar(64) not null,
  input_type varchar(64) not null,
  artifact_type varchar(64) not null,
  acquisition_strategy varchar(64) not null,
  default_style_profile_id bigint null,
  default_prompt_recipe_id bigint null,
  default_skill_graph_template_id bigint null,
  output_contract_json json null,
  allow_external_capability tinyint(1) not null default 0,
  allow_retrieval_writeback tinyint(1) not null default 0,
  status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_production_action_key (action_key),
  constraint fk_action_style_profile foreign key (default_style_profile_id) references style_profile(id),
  constraint fk_action_prompt_recipe foreign key (default_prompt_recipe_id) references prompt_recipe(id),
  constraint fk_action_skill_graph foreign key (default_skill_graph_template_id) references skill_graph_template(id)
) engine=InnoDB default charset=utf8mb4;

create table artifact (
  id bigint primary key auto_increment,
  workspace_id bigint not null,
  topic_scope_id bigint null,
  artifact_type varchar(64) not null,
  title varchar(255) not null,
  status varchar(32) not null,
  latest_version_no int not null default 0,
  latest_version_id bigint null,
  created_from_type varchar(64) null,
  created_from_id bigint null,
  created_by bigint not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_artifact_workspace (workspace_id, updated_at),
  key idx_artifact_scope (topic_scope_id, updated_at),
  constraint fk_artifact_workspace foreign key (workspace_id) references workspace(id),
  constraint fk_artifact_scope foreign key (topic_scope_id) references topic_scope(id)
) engine=InnoDB default charset=utf8mb4;

create table artifact_job (
  id bigint primary key auto_increment,
  workspace_id bigint not null,
  topic_scope_id bigint null,
  context_snapshot_id bigint null,
  production_action_id bigint not null,
  style_profile_id bigint null,
  prompt_recipe_id bigint null,
  skill_graph_template_id bigint null,
  status varchar(32) not null,
  input_type varchar(64) not null,
  input_ref_json json null,
  execution_plan_json json null,
  result_artifact_id bigint null,
  error_message varchar(1000) null,
  started_at datetime(3) null,
  finished_at datetime(3) null,
  created_by bigint not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_artifact_job_workspace (workspace_id, status, created_at),
  key idx_artifact_job_scope (topic_scope_id, created_at),
  constraint fk_artifact_job_workspace foreign key (workspace_id) references workspace(id),
  constraint fk_artifact_job_scope foreign key (topic_scope_id) references topic_scope(id),
  constraint fk_artifact_job_action foreign key (production_action_id) references production_action(id),
  constraint fk_artifact_job_style foreign key (style_profile_id) references style_profile(id),
  constraint fk_artifact_job_recipe foreign key (prompt_recipe_id) references prompt_recipe(id),
  constraint fk_artifact_job_graph foreign key (skill_graph_template_id) references skill_graph_template(id),
  constraint fk_artifact_job_result foreign key (result_artifact_id) references artifact(id)
) engine=InnoDB default charset=utf8mb4;

create table artifact_version (
  id bigint primary key auto_increment,
  artifact_id bigint not null,
  version_no int not null,
  artifact_job_id bigint null,
  title varchar(255) not null,
  content_markdown mediumtext not null,
  change_note varchar(255) null,
  input_snapshot_json json null,
  cco_snapshot_ref varchar(512) null,
  created_by bigint not null,
  created_at datetime(3) not null default current_timestamp(3),
  unique key uk_artifact_version (artifact_id, version_no),
  key idx_artifact_version_job (artifact_job_id),
  constraint fk_artifact_version_artifact foreign key (artifact_id) references artifact(id),
  constraint fk_artifact_version_job foreign key (artifact_job_id) references artifact_job(id)
) engine=InnoDB default charset=utf8mb4;

create table mcp_capability_binding (
  id bigint primary key auto_increment,
  binding_scope_type varchar(32) not null,
  binding_scope_id bigint not null,
  capability_name varchar(128) not null,
  provider varchar(64) not null,
  input_contract_json json null,
  output_contract_json json null,
  permission_scope varchar(64) not null,
  created_at datetime(3) not null default current_timestamp(3),
  key idx_mcp_binding_scope (binding_scope_type, binding_scope_id)
) engine=InnoDB default charset=utf8mb4;

create table capability_union_policy (
  id bigint primary key auto_increment,
  policy_key varchar(64) not null,
  binding_scope_type varchar(32) not null,
  binding_scope_id bigint not null,
  risk_level varchar(32) not null,
  allow_cross_source_read tinyint(1) not null default 0,
  allow_external_write tinyint(1) not null default 0,
  allow_network_side_effect tinyint(1) not null default 0,
  approval_rule_json json null,
  status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_capability_policy_key (policy_key),
  key idx_capability_policy_scope (binding_scope_type, binding_scope_id)
) engine=InnoDB default charset=utf8mb4;
```

### 6.4 Graduated Memory

```sql
create table context_signal (
  id bigint primary key auto_increment,
  workspace_id bigint not null,
  topic_scope_id bigint null,
  signal_source_type varchar(32) not null,
  signal_source_id bigint not null,
  signal_kind varchar(64) not null,
  signal_content text not null,
  confidence_score decimal(5,4) null,
  decay_policy_json json null,
  status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_context_signal_workspace (workspace_id, signal_kind, created_at),
  constraint fk_context_signal_workspace foreign key (workspace_id) references workspace(id),
  constraint fk_context_signal_scope foreign key (topic_scope_id) references topic_scope(id)
) engine=InnoDB default charset=utf8mb4;

create table memory_candidate (
  id bigint primary key auto_increment,
  workspace_id bigint not null,
  topic_scope_id bigint null,
  candidate_type varchar(64) not null,
  source_lineage_json json null,
  normalized_statement text not null,
  evidence_set_json json null,
  novelty_score decimal(5,4) null,
  neighborhood_validation_status varchar(32) not null,
  review_status varchar(32) not null,
  status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_memory_candidate_workspace (workspace_id, status, created_at),
  key idx_memory_candidate_validation (neighborhood_validation_status, created_at),
  constraint fk_memory_candidate_workspace foreign key (workspace_id) references workspace(id),
  constraint fk_memory_candidate_scope foreign key (topic_scope_id) references topic_scope(id)
) engine=InnoDB default charset=utf8mb4;

create table memory_object (
  id bigint primary key auto_increment,
  workspace_id bigint not null,
  topic_scope_id bigint null,
  memory_type varchar(64) not null,
  memory_scope varchar(64) not null,
  canonical_content text not null,
  supporting_evidence_json json null,
  source_lineage_json json null,
  promotion_record_json json null,
  freshness_score decimal(5,4) null,
  retrieval_policy_json json null,
  status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_memory_object_workspace (workspace_id, memory_type, status),
  key idx_memory_object_updated (workspace_id, status, updated_at),
  constraint fk_memory_object_workspace foreign key (workspace_id) references workspace(id),
  constraint fk_memory_object_scope foreign key (topic_scope_id) references topic_scope(id)
) engine=InnoDB default charset=utf8mb4;
```

### 6.5 Task / Outbox

```sql
create table task (
  id bigint primary key auto_increment,
  workspace_id bigint not null,
  topic_scope_id bigint null,
  task_type varchar(64) not null,
  target_type varchar(64) not null,
  target_id bigint not null,
  task_status varchar(32) not null,
  idempotency_key varchar(128) not null,
  input_json json null,
  output_json json null,
  error_message varchar(1000) null,
  cancel_requested tinyint(1) not null default 0,
  retry_count int not null default 0,
  max_retry_count int not null default 3,
  started_at datetime(3) null,
  finished_at datetime(3) null,
  created_by bigint not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_task_idempotency (task_type, idempotency_key),
  key idx_task_status (task_status, created_at),
  key idx_task_target (target_type, target_id),
  constraint fk_task_workspace foreign key (workspace_id) references workspace(id),
  constraint fk_task_scope foreign key (topic_scope_id) references topic_scope(id)
) engine=InnoDB default charset=utf8mb4;

create table task_outbox (
  id bigint primary key auto_increment,
  aggregate_type varchar(64) not null,
  aggregate_id bigint not null,
  event_type varchar(64) not null,
  payload_json json not null,
  publish_status varchar(32) not null,
  retry_count int not null default 0,
  next_retry_at datetime(3) null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_task_outbox_publish (publish_status, next_retry_at, created_at),
  key idx_task_outbox_aggregate (aggregate_type, aggregate_id)
) engine=InnoDB default charset=utf8mb4;
```

## 7. 首批 Flyway 迁移建议

推荐拆成下面几批：

1. `V1__workspace_and_source.sql`
2. `V2__research_core.sql`
3. `V3__artifact_runtime_core.sql`
4. `V4__memory_core.sql`
5. `V5__task_and_outbox.sql`

不要把全部表塞进一份超大 SQL。

## 8. 实现优先级

### P0

- `workspace`
- `topic_scope`
- `source`
- `research_run`
- `research_row`
- `research_cell`
- `artifact`
- `artifact_job`
- `artifact_version`
- `production_action`
- `style_profile`
- `prompt_recipe`
- `task`
- `task_outbox`

### P1

- `research_branch`
- `research_trace`
- `skill_graph_template`
- `mcp_capability_binding`
- `capability_union_policy`
- `context_signal`
- `memory_candidate`
- `memory_object`

## 9. 设计取舍

当前这份设计有意做了三个收缩：

- 没把 `Skill Graph` 做成通用流程引擎，只保留模板化注册
- 没把 Memory 做成独立向量知识平台，只保留门控晋升主链路
- 没把 Deep Research 做成多智能体群聊系统，而是坚持 `Research Harness + Table-as-State`

这是为了保证亮点成立，同时实现成本可控。
