# Artifact Viewer Override

本页覆盖 `MASTER.md`。

## 1. Page Intent

Artifact 页面本质上是“可阅读的工作成果”，不是后台详情页。

它需要同时支持：

- 阅读
- 轻编辑
- 导出
- 回看来源
- 再生成或沉淀

## 2. Layout

```text
Artifact Header
Main Reading Surface
Right Action / Citation Sidebar
Optional Version Strip
```

桌面端推荐：

- 阅读面宽度控制在 `72ch` 左右
- 编辑态不超过双栏
- 右侧边栏用于操作，不用于堆所有元数据

## 3. Typography

- Artifact 标题用 `Newsreader`
- 正文使用 `Public Sans`
- 引用块、代码块、版本号使用 `JetBrains Mono`

## 4. Content Hierarchy

1. 标题与版本状态
2. 正文
3. Citation / 来源映射
4. 导出 / 再生成 / 沉淀
5. 次级元数据

## 5. Editing Rules

- MVP 只做轻编辑，不做复杂富文本
- 编辑态与阅读态切换要明确
- 保存反馈必须稳定且可感知

## 6. Avoid

- 不要把 Artifact 做成单纯 textarea
- 不要让导出、沉淀、再生成三个动作抢同一个主按钮
- 不要让右侧边栏比正文更显眼
