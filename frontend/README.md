# NoteWeave v2 Frontend

前端采用 `React 19 + TypeScript 5 + Vite 5`，阶段1/2/3先提供最小研究工作台壳。

当前页面包含：

1. 研究工作台概览
2. `问答 RAG / Note / Wiki` 三种链路入口
3. Deep Research 独立按钮
4. 右侧产物栏入口
5. Wiki 工作台跳转入口

当前模式口径：

1. `问答 RAG`：基于当前研究工作台资料回答，并返回引用
2. `Note`：参考 Marginalia 式结构化阅读漏斗，展示候选资料、原文窗口、摘录卡片和结构化笔记
3. `Wiki`：参考 WeKnora 式 Wiki-first 链路，优先读取 Wiki 页面，并保留默认 Wiki 工作台入口

## 本地运行

```bash
npm install
npm run dev
```

## 构建

```bash
npm run build
```
