# NoteWeave v2 Frontend

前端采用 `React 19 + TypeScript 5 + Vite 5`，阶段1/2/3提供最小研究工作台闭环。

当前页面包含：

1. 创建研究工作台与默认会话
2. 上传文本资料并触发解析切片
3. `问答 RAG / Note / Wiki` 三种链路入口，并通过 `answer_mode` 调用同一聊天接口
4. 聊天回答正文与 citation 展示
5. Deep Research 独立按钮
6. 右侧产物栏入口
7. 默认 Wiki 工作台入口，可查看 Wiki Index、页面摘要、版本信息和页面链接关系

当前模式口径：

1. `问答 RAG`：基于当前研究工作台资料回答，并返回引用
2. `Note`：参考 Marginalia 式结构化阅读漏斗，展示候选资料、原文窗口、摘录卡片和结构化笔记
3. `Wiki`：参考 WeKnora 式 Wiki-first 链路，优先读取 Wiki 页面，并保留默认 Wiki 工作台入口

## 本地运行

```bash
npm install
npm run dev
```

默认请求同源 `/api/v2`。如果后端运行在 `http://localhost:8081`，可以设置：

```bash
$env:VITE_API_BASE_URL="http://localhost:8081"
npm run dev
```

## 构建

```bash
npm run build
```
