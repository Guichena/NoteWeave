# 阶段4：产物生成Agent

## 1. 阶段目标

阶段 4 要让右侧产物栏真正可用：

1. 用户可点击动作
2. Java 创建任务
3. Artifact Worker 执行
4. 生成版本化产物
5. 结果可保存为资料

## 2. 子阶段树

```text
4.1 Artifact 任务与动作
4.2 Worker 执行与版本化
4.3 保存为资料与导出
```

## 3. 子阶段 4.1 Artifact 任务与动作

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

## 4. 子阶段 4.2 Worker 执行与版本化

### 目标

让 Python Artifact Worker 能消费任务并回传 `artifact_version`。

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

## 5. 子阶段 4.3 保存为资料与导出

### 目标

让 Artifact 成为工作台正式资料的一部分。

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

## 6. 本阶段禁止项

1. 不做可视化 Skill 编排平台
2. 不做完整 MCP 自定义后台
