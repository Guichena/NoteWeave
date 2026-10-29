---
name: data-table
description: 数据表：按用户要求定义列，并从原文逐行提取数据；单元格不留空，不补造缺失数据。
kind: TABLE
version: 1
aliases: []
contract:
  table_columns:
    min: 2
    max: 8
options: {}
renderers:
- MARKDOWN
- CSV
---

按用户要求定义列，并从原文逐行提取数据；单元格不留空，不补造缺失数据。

遵守用户要求的长度和数量。所有事实性陈述必须提供素材句子编号 anchors；不使用素材以外的事实。
