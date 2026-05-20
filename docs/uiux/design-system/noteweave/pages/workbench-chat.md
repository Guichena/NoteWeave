# Workbench Chat Override

本页覆盖 `MASTER.md`。

## 1. Page Intent

这是 NoteWeave 最核心的工作台页面，目标不是“对话漂亮”，而是：

- 快速进入会话
- 稳定看到流式回答
- 随时检查引用证据
- 不中断地产出 Artifact

## 2. Layout

```text
+----------------+------------------------------+----------------------+
| Session Rail   | Message Stream               | Evidence / Context   |
| 280px          | minmax(0, 1fr)              | 360px                |
+----------------+------------------------------+----------------------+
```

规则：

- 左侧会话列固定宽度，保持可扫描
- 中间消息区优先阅读体验
- 右侧抽屉默认展示 Citation / Context / Artifact
- 输入框固定在底部，不被流式内容推走

## 3. Hierarchy

优先级顺序：

1. 当前会话标题与状态
2. 最新 assistant 输出
3. Citation 触发点
4. 输入区与停止按钮
5. 历史会话

## 4. Message Design

- 用户消息更紧凑，靠右
- assistant 消息更宽，承担正文阅读
- assistant 消息内部可嵌 citation chips
- 流式生成时显示进行中状态，而不是全局 loading

## 5. Composer

- 高度不低于 `104px`
- 左侧放 scope/模式提示
- 右侧主按钮区保留 `发送` 与 `停止`
- 停止按钮在 streaming 时优先级高于次要工具

## 6. Citation Behavior

- Citation chip 颜色使用 `--signal-blue`
- 点击后右侧抽屉直接定位证据
- 抽屉首屏信息顺序：
- 来源名
- 文档位置 / 页码 / offset
- 引用原文
- 相关上下文

## 7. Empty State

空状态不做插画墙，使用任务导向结构：

- 一句当前空间说明
- 三个可立即点击的问题模板
- 最近 Artifact 或最近知识库范围提示

## 8. Avoid

- 不要把会话列表做得像 IM 联系人
- 不要让 Citation 打开后遮挡输入区
- 不要把右侧抽屉做成纯 JSON dump
