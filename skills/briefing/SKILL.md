---
name: briefing
description: 简报：围绕资料主题给出摘要、核心发现和行动建议；区分原文事实与建议。
kind: DOC
version: 1
aliases:
- report_draft
- structured_note
- reading_digest
- video_summary
- audio_minutes
contract:
  sections:
    min: 3
    max: 8
  required_headings:
  - 摘要
  - 核心发现
  - 行动建议
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

围绕资料主题给出摘要、核心发现和行动建议；区分原文事实与建议。

遵守用户要求的长度和数量。所有事实性陈述必须提供素材句子编号 anchors；不使用素材以外的事实。
