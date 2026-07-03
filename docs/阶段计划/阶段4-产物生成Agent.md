# 阶段4：产物生成Agent

## 1. 阶段目标

阶段 4 要让右侧产物栏真正可用：

1. 用户可点击动作
2. Java 创建任务
3. Artifact Worker 执行
4. 生成版本化产物
5. 结果可保存为资料

## 2. 与原始 NoteWeave Phase 的关系

当前阶段 4 主要对应原始 `Phase 4：轻量流转`，但在 v2 中把它从“轻量结果流转”升级成一个真正可执行的右侧产物系统。

## 3. 子阶段树

```text
4.1 Artifact 任务与动作
4.2 Worker 执行与版本化
4.3 保存为资料与导出
```

## 4. 子阶段 4.1 Artifact 任务与动作

### 目标

打通 `artifact_job + production_action` 的创建与查询。

### 第一批动作

1. 报告
2. FAQ
3. 测验

### 优先迁移

1. 旧版任务执行基础设施
2. 旧版产物模板
3. 旧版导出配置

### 推荐函数与类

1. `ArtifactJobController.createArtifactJob(req)`
2. `ArtifactJobService.createJob(cmd)`
3. `ProductionActionResolver.resolve(actionKey)`
4. `ArtifactTaskPublisher.publishArtifactJob(taskId, artifactJobId)`

### 中间件接入

MySQL：

1. `artifact_job`
2. `production_action`
3. `style_profile`
4. `prompt_recipe`

Kafka：

1. topic：`noteweave.artifact.job`
2. consumer group：`noteweave-artifact-worker`

### TDD 要求

先写：

1. `artifact_job` 创建测试
2. action 解析测试
3. 任务查询测试

再写：

1. artifact job controller
2. action resolver
3. task binding

### 完成定义

1. 前端能创建产物任务
2. 至少 3 个动作可被识别

## 5. 子阶段 4.2 Worker 执行与版本化

### 目标

让 Python Artifact Worker 能消费任务并回传 `artifact_version`。

### 推荐函数与类

Java：

1. `ArtifactWorkerInputController.getTaskInput(taskId)`
2. `ArtifactWorkerCallbackController.reportProgress(taskId, req)`
3. `ArtifactWorkerCallbackController.complete(taskId, req)`
4. `ArtifactVersionService.createVersionFromWorkerResult(jobId, result)`

Python：

1. `load_artifact_task_input(task_id)`
2. `run_artifact_task(task_input)`
3. `resolve_action(task_input)`
4. `compile_source_bundle(task_input)`
5. `verify_artifact_output(output)`
6. `submit_artifact_result(task_id, result)`
7. `submit_artifact_failure(task_id, error)`

### 中间件接入

Kafka：

1. topic：`noteweave.artifact.job`
2. ack：`manual`

Redis / SSE：

1. `stream:task:{taskId}`
2. 事件：
   `task.status`
   `task.progress`
   `task.completed`
   `task.failed`

MinIO：

1. 结果快照可先写 `noteweave-derived`

### TDD 要求

先写：

1. Worker 输入契约测试
2. 输出 schema 测试
3. 完成回传测试
4. 失败回传测试

再写：

1. worker runner
2. action compiler
3. result submitter
4. artifact version creator

### 完成定义

1. 任务能从创建走到完成
2. 产物有版本

## 6. 子阶段 4.3 保存为资料与导出

### 目标

让 Artifact 成为工作台正式资料的一部分。

### 推荐函数与类

1. `ArtifactExportService.exportMarkdown(artifactVersionId)`
2. `ArtifactExportService.exportPdf(artifactVersionId)`
3. `ArtifactSaveAsSourceService.saveAsSource(artifactId, versionNo)`
4. `GeneratedSourceIngestService.ingestArtifactVersion(artifactVersionId)`

### 中间件接入

MinIO：

1. 导出路径：
   `workspace/{workspaceId}/artifact/{artifactId}/version/{versionNo}/export/{format}/{fileName}`

MySQL：

1. `artifact`
2. `artifact_version`
3. `source`
4. `source_snapshot`

Kafka：

1. 保存为资料后继续走 `noteweave.source.parse`
2. 后续索引继续走 `noteweave.source.index`

### TDD 要求

先写：

1. `save-as-source` 集成测试
2. 导出文件生成测试
3. 回写后再检索测试

再写：

1. artifact exporter
2. save-as-source service
3. source ingest bridge

### 完成定义

1. Artifact 可导出
2. Artifact 可保存为资料
3. 回写资料后可再次被主链路消费

## 7. 本阶段禁止项

1. 不做可视化 Skill 编排平台
2. 不做完整 MCP 自定义后台
