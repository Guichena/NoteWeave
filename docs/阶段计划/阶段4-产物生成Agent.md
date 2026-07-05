# 阶段4：产物生成 Agent

## 1. 阶段目标

这一阶段的目标是把右侧产物栏真正打通，让用户可以在工作台内发起异步产物生成任务，并拿到可保存、可回写、可继续扩展的结果。

当前第一批已聚焦三个目标：

1. Java 侧任务创建与状态流转闭环。
2. Python Artifact Worker 运行时骨架闭环。
3. 版本化结果落库闭环。

## 2. 当前已经完成的部分

### 2.1 Java 主链路

已经具备：

1. `artifact_job`
2. `artifact_version`
3. `POST /api/v2/workspaces/{workspaceId}/artifact-jobs`
4. `GET /internal/worker/artifact-tasks/{taskId}/input`
5. `POST /internal/worker/tasks/{taskId}/progress`
6. `POST /internal/worker/tasks/{taskId}/complete`
7. `POST /internal/worker/tasks/{taskId}/fail`

### 2.2 Python Worker 当前内部设计

当前 Artifact Worker 采用的是轻量但受控的执行链：

```text
resolve action
  -> compile execution plan
  -> schema gate
  -> compose sections
  -> local repair
  -> verify output
  -> export markdown
```

当前的实现重点不是“多智能体炫技”，而是“先把产物生成做成可控系统”。

### 2.3 当前支持的工程语义

1. 先按 `action_key` 生成执行计划，而不是直接一把梭写结果。
2. 计划内显式声明 `required_capabilities` 和 `schema_gate_rules`。
3. 结果按 section 组织，便于后续做模板化、局部修复和导出。
4. 本地修复阶段负责补齐缺失 section、处理禁用表达。
5. 最终校验阶段负责确认结构完整、标题完整、章节可用。

## 3. 当前关键文件

1. [workers/artifact-worker/app/models.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/models.py)
2. [workers/artifact-worker/app/compiler.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/compiler.py)
3. [workers/artifact-worker/app/composer.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/composer.py)
4. [workers/artifact-worker/app/repair.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/repair.py)
5. [workers/artifact-worker/app/verifier.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/verifier.py)
6. [workers/artifact-worker/app/runner.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/app/runner.py)

## 4. TDD 要求

### 4.1 已落地测试

Java：

1. [backend/src/test/java/com/noteweave/Phase6ResearchArtifactContractTest.java](/D:/java-projects/NoteWeave-v2/backend/src/test/java/com/noteweave/Phase6ResearchArtifactContractTest.java)

Python：

1. [workers/artifact-worker/tests/test_runner.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/tests/test_runner.py)
2. [workers/artifact-worker/tests/test_imports.py](/D:/java-projects/NoteWeave-v2/workers/artifact-worker/tests/test_imports.py)

### 4.2 当前测试覆盖点

1. 创建任务后可以拿到 worker 输入。
2. worker 可以完成完整 phase sequence。
3. 输出结果包含 `execution_plan`、`sections`、`verification`。
4. 回调完成后可以落 `artifact_version`。

## 5. 下一步施工重点

下一轮建议按这个顺序继续：

1. 把 `REPORT / FAQ / QUIZ / STUDY_GUIDE / WIKI / NOTE` 的结构模板独立配置化。
2. 给 `style_profile` 和 `control_pack` 增加更细的 prompt 组装层。
3. 接入正式导出链路，把 markdown / pdf 落到对象存储。
4. 再决定是否把默认 skill / 默认 MCP 注入进去。

## 6. 当前完成定义

这一阶段当前可以认为已经完成了第一批目标：

1. 前端可以发起 Artifact 异步任务。
2. Java 侧任务、回调、版本落库链路已经打通。
3. Python Worker 已经不是空壳，而是具备受控编排的内部运行骨架。
4. 全链路回归没有打坏已有 QA / Note / Wiki / Memory 功能。
