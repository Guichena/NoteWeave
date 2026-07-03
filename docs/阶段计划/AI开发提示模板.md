# NoteWeave v2 AI开发提示模板

## 1. 用途

后续让 AI 执行某个子阶段时，直接复制下面模板，并填入阶段编号即可。

## 2. 通用提示模板

```text
请按 NoteWeave v2 的阶段计划执行开发。

当前目标子阶段：
{阶段编号与名称}

必须先阅读：
1. docs/阶段计划/README.md
2. docs/阶段计划/TDD与迁移总则.md
3. docs/阶段计划/{当前阶段文档}.md
4. docs/具体设计与落地索引.md
5. 与本子阶段相关的专项设计文档

开发要求：
1. 先检查是否有旧代码或参考代码可以迁移，能迁移就迁移
2. 先写失败测试，再写最小实现
3. 一次只完成当前子阶段，不跨阶段铺功能
4. 测试通过后再做小范围重构
5. 完成后更新必要文档
6. 提交并推送当前分支

输出要求：
1. 说明迁移了什么
2. 说明新增了什么测试
3. 说明当前子阶段完成到哪里
4. 说明下一个合理子阶段是什么
```

## 3. 阶段1示例

```text
当前目标子阶段：
1.2 数据库与中间件底座

请先阅读：
1. docs/阶段计划/阶段1-工程底座与迁移建仓.md
2. docs/数据库迁移与建表顺序.md
3. docs/技术栈与数据库设计.md

请按 TDD 方式完成 docker compose、Flyway V001-V006 和基础 repository 集成测试。
```

## 4. 阶段2示例

```text
当前目标子阶段：
2.1 上传与建档

请先阅读：
1. docs/阶段计划/阶段2-资料上传与问答RAG.md
2. docs/资料基础设施详细设计.md
3. docs/最小接口契约.md

请先迁移旧版上传能力中可复用的部分，再用 TDD 完成上传事务、分片上传、完成上传和 source 建档。
```

## 5. 阶段3示例

```text
当前目标子阶段：
3.1 Knowledge 模型迁移

请先阅读：
1. docs/阶段计划/阶段3-Note与Wiki链路.md
2. docs/Note链路设计.md
3. docs/Wiki模式设计.md

请优先迁移旧版 Wiki 的版本、引用、存储和检索能力，并收敛到 knowledge_item / knowledge_version / knowledge_version_citation。
```

## 6. 阶段4示例

```text
当前目标子阶段：
4.2 Worker 执行与版本化

请先阅读：
1. docs/阶段计划/阶段4-产物生成Agent.md
2. docs/受控式异步产物生成Agent编排升级设计.md
3. docs/最小接口契约.md

请用 TDD 完成 Artifact Worker 的输入契约、输出 schema、完成回传和失败回传。
```

## 7. 阶段5示例

```text
当前目标子阶段：
5.2 验证驱动研究闭环

请先阅读：
1. docs/阶段计划/阶段5-DeepResearch与Memory.md
2. docs/深度研究智能体编排升级设计.md
3. docs/深度研究智能体工程落地设计.md

请用 TDD 完成 Research Table state、Verifier 输出、报告 schema 和失败恢复测试，再补 Research Worker 最小 loop。
```
