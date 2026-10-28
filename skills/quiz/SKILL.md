---
name: quiz
description: 测验：根据知识点分布设计单选题，给出选项、唯一答案和有素材依据的解析。
kind: QUIZ
version: 1
aliases:
- quiz_pack
contract:
  quiz_single_answer: true
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

根据知识点分布设计单选题，给出选项、唯一答案和有素材依据的解析。

遵守用户要求的长度和数量。所有事实性陈述必须提供素材句子编号 anchors；不使用素材以外的事实。
