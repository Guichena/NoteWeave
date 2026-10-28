---
name: video-deck
description: 视频演示文稿：依据视频图文资料编排幻灯片。每页选择相关原始关键帧，禁止裁剪或生成替代图片；要点引用对应素材句子。
kind: DECK
version: 1
aliases:
- video_learning_deck
contract:
  deck_bullets:
    min: 1
    max: 6
  figure_required: true
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

依据视频图文资料编排幻灯片。每页选择相关原始关键帧，禁止裁剪或生成替代图片；要点引用对应素材句子。

所有事实性陈述与图注必须提供素材句子编号 anchors；不使用素材以外的事实。
