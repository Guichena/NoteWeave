---
name: lecture-notes-pdf
description: 视频讲义：按视频内容整理章节讲义，每章选择相关关键帧并写带引用的图注，解释概念和推导过程，保留来源时间区间。
kind: DOC
version: 1
aliases:
- bilibili_course_note_pdf
contract:
  sections:
    min: 3
    max: 8
  figure_required: true
options:
  length:
    choices:
    - SHORT
    - STANDARD
    - LONG
    default: STANDARD
renderers:
- MARKDOWN
- PDF
---

按视频内容整理章节讲义，每章选择相关关键帧并写带引用的图注，解释概念和推导过程，保留来源时间区间。

所有事实性陈述与图注必须提供素材句子编号 anchors；不使用素材以外的事实。
