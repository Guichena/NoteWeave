# 参考源码索引

这个目录用于集中存放 NoteWeave v2 的参考源码仓库，方便后续做架构借鉴、代码对照和设计拆解。

## 当前参考仓库

- `WeKnora`
  重点看多模式知识工作台、Agent / Tool 组织方式、Wiki / 检索 / MCP 能力接法。

- `Marco-DeepResearch`
  重点看 Deep Research、Table-as-Search、Verifier、UMEM 相关设计。

- `A-mem`
  重点看长期记忆对象组织、记忆演化、记忆检索与管理方式。

- `PlugMem`
  重点看如何把原始交互抽象成可复用知识单元，而不是直接堆积聊天历史。

- `sample-amazon-bedrock-agentcore-memory-mcp-server`
  重点看 Memory namespace、项目级隔离、MCP 风格接入与工程落地方式。

## 建议借鉴分工

- Deep Research：优先看 `Marco-DeepResearch`
- 右侧产物生成与能力编排：优先看 `WeKnora`
- Graduated Memory：优先看 `A-mem`、`PlugMem`、`sample-amazon-bedrock-agentcore-memory-mcp-server`
