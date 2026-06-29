---
title: Skills 与 MCP
tags: [Skills, MCP, 工具调用, 沙箱, 安全]
aliases: [Agent Skills, MCP工具]
---

# Skills 与 MCP

## 总体思路

WeKnora 把 Agent 的扩展分成两种机制：

- **Skills**：通过 prompt 和文件系统加载技能说明，适合可控、局部、可复用能力。
- **MCP**：通过协议接外部工具，适合标准化工具生态和外部能力接入。

## 关键模块

- `internal/handler/skill_handler.go`
  - `ListSkills()`
- `docs/agent-skills.md`
- `docs/api/skill.md`
- `internal/application/service/agent_service.go`
  - `initializeSkillsManager()`
  - `registerMCPTools()`
  - `registerTools()`
- `docs/wiki/核心功能/MCP功能使用说明.md`

## 为什么分成两套

Skills 更像“任务说明书”，MCP 更像“标准工具协议”。前者适合控制模型怎么做，后者适合控制模型能调什么工具。

## tradeoff

- **Skills**：轻、快、便于约束，但能力边界靠文档维护。
- **MCP**：标准化强、扩展性高，但接入和治理更复杂。
- **沙箱脚本**：能力强，但安全治理成本高。

## 对 Noteweave 的建议

Noteweave 可以把常用研究任务做成 Skills，把外部能力接成 MCP，例如：

- 网页抓取
- PDF 解析
- 引用生成
- 代码仓库搜索
- 网盘/文档平台同步

## 面试追问

### 1. 为什么 Skills 要做渐进式披露？

因为 Agent 一次性拿太多说明书会把上下文撑满，而且很多能力在当前任务里根本用不上。渐进式披露让模型先知道有这个技能，再在需要时展开细节，这是控制 Token 和降低噪声的好办法。

### 2. 为什么技能要支持脚本？

因为复杂任务里，纯自然语言描述不够稳定。脚本可以承载重复逻辑、字段抽取、格式转换和轻量验证，比每次都让模型“现想”更可靠。

### 3. MCP 和直接调用 HTTP 接口有什么区别？

MCP 不是简单的 HTTP 包装，而是把工具发现、参数结构、权限和调用语义标准化。这样 Agent 可以以统一方式接入外部能力，而不用针对每个服务写一套特殊逻辑。

### 4. 沙箱为什么重要？

因为技能一旦带脚本，就会涉及文件、命令和网络访问。没有沙箱，就等于把不确定执行直接暴露给系统。生产系统里这是很危险的。
