---
name: slide-deck
description: 幻灯片：按主题编排幻灯片，每页讲清一个要点。用要点和可选讲稿提供可编辑文本。普通资料没有图片时使用文字版式。
kind: DECK
version: 1
aliases: []
contract:
  deck_bullets:
    min: 1
    max: 6
options:
  count:
    choices:
    - '5'
    - '10'
    - '15'
    default: '5'
renderers:
- MARKDOWN
- PPTX
---

按主题编排幻灯片，每页讲清一个要点。用要点和可选讲稿提供可编辑文本。普通资料没有图片时使用文字版式。

所有事实性陈述与图注必须提供素材句子编号 anchors；不使用素材以外的事实。
