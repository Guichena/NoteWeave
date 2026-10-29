---
name: flashcards
description: 闪卡：每张卡片考查一个知识点，正面提出问题，背面给出简洁、可核实的答案；正面不要泄露答案。
kind: CARDS
version: 1
aliases: []
contract:
  card_no_leak: true
options:
  count:
    choices:
    - '5'
    - '10'
    - '15'
    default: '10'
  difficulty:
    choices:
    - EASY
    - STANDARD
    - HARD
    default: STANDARD
renderers:
- MARKDOWN
---

每张卡片考查一个知识点，正面提出问题，背面给出简洁、可核实的答案；正面不要泄露答案。

遵守用户要求的长度和数量。所有事实性陈述必须提供素材句子编号 anchors；不使用素材以外的事实。
