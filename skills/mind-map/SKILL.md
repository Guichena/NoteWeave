---
name: mind-map
description: 思维导图：以单一主题组织概念层级，每个节点标签简短，说明有原文依据。
kind: TREE
version: 1
aliases:
- mindmap_from_workspace
contract:
  tree_depth:
    min: 2
    max: 4
    min_children: 1
    max_children: 7
options: {}
renderers:
- MARKDOWN
---

以单一主题组织概念层级，每个节点标签简短，说明有原文依据。

遵守用户要求的长度和数量。所有事实性陈述必须提供素材句子编号 anchors；不使用素材以外的事实。
