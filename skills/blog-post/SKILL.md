---
name: blog-post
description: 博客文章：依据资料写面向读者的博客文章，以清晰的问题引入，用原文事实和例子展开，最后归纳结论。
kind: DOC
version: 1
aliases:
- knowledge_blog
contract:
  sections:
    min: 3
    max: 8
options:
  length:
    choices:
    - SHORT
    - STANDARD
    - LONG
    default: STANDARD
renderers:
- MARKDOWN
---

依据资料写面向读者的博客文章，以清晰的问题引入，用原文事实和例子展开，最后归纳结论。

遵守用户要求的长度和数量。所有事实性陈述必须提供素材句子编号 anchors；不使用素材以外的事实。
