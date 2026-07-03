# NoteWeave v2

NoteWeave v2 是一个参考 NotebookLM 产品形态重构的研究工作台。

当前冻结口径：

- 基本单位：`研究工作台`
- 三种聊天回答链路：`问答 RAG / Note / Wiki`
- 两个独立任务能力：`Deep Research / 右侧产物栏`
- 一个共享资料底座：`工作台资料池`
- 一套门控式长期沉淀机制：`Graduated Memory + Task-Neighborhood Memory Compiler`
- 一套实现分工：`Java 主系统 + Python Research Worker + Python Artifact Worker`

## 当前最重要的文档

真正开始落代码时，按下面顺序阅读：

1. [具体设计与落地索引](D:/java-projects/NoteWeave-v2/docs/具体设计与落地索引.md)
2. [代码落地路线与开工清单](D:/java-projects/NoteWeave-v2/docs/代码落地路线与开工清单.md)
3. [数据库迁移与建表顺序](D:/java-projects/NoteWeave-v2/docs/数据库迁移与建表顺序.md)
4. [最小接口契约](D:/java-projects/NoteWeave-v2/docs/最小接口契约.md)
5. [阶段计划总入口](D:/java-projects/NoteWeave-v2/docs/阶段计划/README.md)
6. [函数级开发清单](D:/java-projects/NoteWeave-v2/docs/阶段计划/函数级开发清单.md)
7. [中间件配置基线](D:/java-projects/NoteWeave-v2/docs/阶段计划/中间件配置基线.md)

## 开发阶段

当前已按树级方式拆成 5 个阶段：

1. [阶段1-工程底座与迁移建仓](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段1-工程底座与迁移建仓.md)
2. [阶段2-资料上传与问答RAG](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段2-资料上传与问答RAG.md)
3. [阶段3-Note与Wiki链路](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段3-Note与Wiki链路.md)
4. [阶段4-产物生成Agent](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段4-产物生成Agent.md)
5. [阶段5-DeepResearch与Memory](D:/java-projects/NoteWeave-v2/docs/阶段计划/阶段5-DeepResearch与Memory.md)

这些文档默认要求：

- `TDD` 优先
- 能迁移就迁移
- 先打通纵向闭环，不先横向铺满

## 项目结构

- `docs`：当前正式设计、技术方案、阶段计划与落代码要求
- `改造计划`：设计推演源文档
- `reference`：参考源码与研究资料
- `backend`：Java 主系统
- `frontend`：React 前端

## 设计源输入

以下三份文档仍然是最高优先级源设计输入：

1. `改造计划/三模式知识工作台重构设计.md`
2. `改造计划/深度研究智能体功能与架构设计.md`
3. `改造计划/受控式异步产物生成智能体架构设计.md`
