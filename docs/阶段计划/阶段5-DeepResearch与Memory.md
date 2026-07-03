# 阶段5：DeepResearch与Memory

## 1. 阶段目标

阶段 5 是亮点收口阶段，目标是：

1. 打通 Deep Research
2. 打通研究报告回写
3. 接入 Memory Control Pack

## 2. 子阶段树

```text
5.1 Research Run 骨架
5.2 验证驱动研究闭环
5.3 Memory Control Pack
```

## 3. 子阶段 5.1 Research Run 骨架

### 目标

建立 `research_run`、Research Worker 和任务状态闭环。

### 优先迁移

1. 旧版任务重试、超时与失败恢复基础设施
2. 旧版搜索 / 读取工具封装

### TDD 要求

先写：

1. `research_run` 创建测试
2. Worker 输入接口测试
3. 进度回传测试

再写：

1. research controller
2. research task service
3. worker input API

### 完成定义

1. 用户能启动 Research
2. 前端能看到任务进度

## 4. 子阶段 5.2 验证驱动研究闭环

### 目标

第一批只实现最小 Research Harness：

1. Search
2. Read
3. Extract
4. Verify
5. Write Report

### TDD 要求

先写：

1. table state 更新测试
2. verifier 结果测试
3. 报告输出 schema 测试
4. 失败恢复测试

再写：

1. planner
2. reader
3. verifier
4. report writer

### 完成定义

1. Research Worker 能输出研究报告
2. `research_row / research_cell / research_trace` 能稳定落地

## 5. 子阶段 5.3 Memory Control Pack

### 目标

把 Memory 接到运行时控制层，而不是接到事实检索层。

### 核心要求

1. 有 `memory_signal`
2. 有 `memory_candidate`
3. 有 `memory_object`
4. 生成 `Chat / Artifact / Research Control Pack`

### TDD 要求

先写：

1. memory 对象读写测试
2. candidate 晋升测试
3. control pack 编译测试
4. control pack 注入测试

再写：

1. memory signal extractor
2. promotion service
3. compiler service
4. runtime injector

### 完成定义

1. Memory 能影响风格、结构、禁用路径和交互策略
2. Memory 不进入 citation，不参与 chunk 召回排序

## 6. 本阶段禁止项

1. 不做通用 Agent 平台
2. 不做复杂分支工作台 UI
3. 不做 Memory 事实检索化
