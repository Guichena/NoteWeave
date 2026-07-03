# NoteWeave v2 Frontend

前端采用 `React 19 + TypeScript 5 + Vite 5`，阶段1/2/3提供研究工作台与三条检索型聊天链路。

当前页面包含：

1. 创建研究工作台与默认会话
2. 上传文本资料并触发解析切片
3. 上传后展示任务状态和任务事件流
4. `问答 RAG / Note / Wiki` 三种链路入口，并通过 `answer_mode` 调用同一聊天接口
5. 聊天回答正文与 citation 展示
6. Deep Research 独立按钮
7. 保存最新回答为 Note
8. 创建 Wiki 页面，并可在 Wiki 工作台中追加新版本、重命名和软删除
9. 右侧产物栏入口
10. 默认 Wiki 工作台入口，可查看 Wiki Index、页面正文、版本信息、来源引用、页面链接关系、图谱、问题和日志

当前模式口径：

1. `问答 RAG`：基于当前研究工作台资料回答，展示证据选择、来源覆盖和 citation
2. `Note`：参考 Marginalia 式结构化检索漏斗，展示 Journal 信号、候选资料、关系扩展、原文窗口、摘录证据和带引用回答，结构化笔记只是可选保存能力
3. `Wiki`：参考 WebKonra / WeKnora 式全量 Wiki 检索，支持 wiki 构建开关、页面 ingest、页面版本、链接/反链、来源回链、搜索、图谱、统计、日志、lint、rebuild links、auto-fix、重命名和软删除；Wiki 知识网络绑定研究工作台，不绑定单次会话

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
