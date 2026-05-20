# Team Knowledge Override

本页覆盖 `MASTER.md`。

## 1. Page Intent

这里是团队资料的“运营台”，不是文件浏览器。

页面必须让用户快速回答四个问题：

- 这个知识库现在是否健康
- 哪些文档已经可检索
- 哪些文档还在处理
- 我下一步该上传、排障还是测试检索

## 2. Layout

知识库列表页：

```text
Header
Metric Row
Filter / Action Toolbar
Knowledge Base Grid or Table
```

知识库详情页：

```text
Header
Status Summary
Document Table
Upload / Processing Panel
Search Debug / Retrieval Test
```

## 3. Visual Rules

- 列表页以表格或紧凑卡片为主
- 文档状态必须极醒目
- 上传面板允许比常规卡片更强对比，以突出当前任务
- 任务状态与文档状态分层展示，不要混成一个 badge

## 4. Important Components

- Upload progress stack
- Task status timeline
- Document row with latest processed time
- Search debug result preview

## 5. Empty And Error States

- 空知识库时直接展示上传入口
- 处理失败时要显示失败原因和“重试/重新上传/查看任务”
- 上传暂停时要有明确 paused 样式，不能看起来像卡死

## 6. Avoid

- 不要把上传流程埋在二级弹窗里
- 不要只展示百分比，不展示具体阶段
- 不要把检索测试区隐藏太深
