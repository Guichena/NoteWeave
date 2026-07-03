# NoteWeave v2 Frontend

前端采用 `React 19 + TypeScript 5 + Vite 8`，第一阶段直接实现 NotebookLM 风格的研究工作台。

首批界面重点：

- 左侧资料区：上传、查看、管理工作台资料
- 中间聊天区：承载三种聊天回答链路 `问答 RAG / Note / Wiki`
- Deep Research 入口：发起独立研究任务并查看进度
- 右侧产物栏：生成报告、FAQ、测验、学习指南、Wiki 页面、Note 文档
- 任务状态与结果回写入口：把系统生成结果保存回工作台资料池

建议前端按 `features` 组织：

- `workspace`
- `sources`
- `chat`
- `rag`
- `note`
- `wiki`
- `research`
- `artifacts`
- `memory`
- `shared`
