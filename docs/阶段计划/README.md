# NoteWeave v2 阶段计划

## 1. 目的

这组文档用于把 NoteWeave v2 的实现过程收敛成一棵可执行开发树。

顶层仍然是 5 个阶段，但现在每个阶段文档内部已经直接写入：

1. 与原始 NoteWeave `Phase` 的对应关系
2. 子阶段拆分
3. 推荐类 / 函数 / 方法
4. 该阶段涉及的中间件接入
5. TDD 顺序与完成定义

后续不再维护平行的“函数级清单”和“中间件基线”文档，避免重复与冲突。

## 2. 阅读顺序

开始写代码前，建议按下面顺序阅读：

1. [TDD与迁移总则](D:/java-projects/NoteWeave-v2/docs/阶段计划/TDD与迁移总则.md)
2. [AI开发提示模板](D:/java-projects/NoteWeave-v2/docs/阶段计划/AI开发提示模板.md)
3. [阶段1-工程底座与迁移建仓](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段1-工程底座与迁移建仓.md)
4. [阶段2-资料上传与问答RAG](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段2-资料上传与问答RAG.md)
5. [阶段3-Note与Wiki链路](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段3-Note与Wiki链路.md)
6. [阶段4-产物生成Agent](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段4-产物生成Agent.md)
7. [阶段5-DeepResearch与Memory](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段5-DeepResearch与Memory.md)

## 3. 阶段树

```text
阶段1 工程底座与迁移建仓
  ├── 1.1 工程初始化
  ├── 1.2 数据库与中间件底座
  └── 1.3 最小接口壳子

阶段2 资料上传与问答 RAG
  ├── 2.1 上传与建档
  ├── 2.2 解析、切片、索引
  └── 2.3 问答与引用闭环

阶段3 Note / Wiki 双链路
  ├── 3.1 Knowledge 模型迁移
  ├── 3.2 Note 链路
  └── 3.3 Wiki 链路

阶段4 右侧产物栏与 Artifact Agent
  ├── 4.1 Artifact 任务与动作
  ├── 4.2 Worker 执行与版本化
  └── 4.3 保存为资料与导出

阶段5 Deep Research 与 Memory
  ├── 5.1 Research Run 骨架
  ├── 5.2 验证驱动研究闭环
  └── 5.3 Memory Control Pack
```

## 4. 与原始 NoteWeave Phase 的关系

为了和 `改造计划/三模式知识工作台重构设计.md` 里的老 Phase 讲法保持一致，当前阶段树和原始 Phase 的关系如下：

1. `阶段1`
   技术预备阶段，服务原始 `Phase 1-4`
2. `阶段2`
   主要对应原始 `Phase 1：问答模式`
3. `阶段3`
   主要对应原始 `Phase 1` 中的 Note 沉淀，以及原始 `Phase 2：Wiki 模式`
4. `阶段4`
   主要对应原始 `Phase 4：轻量流转`，并把右侧产物栏升级成独立执行系统
5. `阶段5`
   承接原始设计里单独存在的 `Deep Research / Graduated Memory`

## 5. 统一要求

后续 AI 开发必须默认遵守：

1. `能迁移就迁移`
2. `先写失败测试，再写实现`
3. `一次只推进一个子阶段`
4. `先打通纵向闭环，不先横向铺满`

## 6. 阶段完成顺序

必须按阶段顺序推进，不建议跨阶段并行大开发。

真正合理的顺序是：

1. 先能启动
2. 再能建表
3. 再能上传
4. 再能问答
5. 再能保存 Note / Wiki
6. 再能生成 Artifact
7. 再能执行 Research
8. 最后让 Memory 以 Control Pack 方式接入
