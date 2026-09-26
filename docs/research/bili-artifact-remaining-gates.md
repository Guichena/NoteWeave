# B站 Artifact 与 Context 剩余门禁（2026-09-27）

本清单按已运行的合同与实际环境记录，详细结果见 `bili-artifact-baseline.md`。计划中的目标设计不等于已交付能力。当前生产入口 `noteweave.video-learning.enabled`、Context v2 的全局 ACTIVE、Research ACTIVE 与 Artifact ACTIVE 均默认关闭。

| 工作包 | 已有可验证能力 | 剩余门禁 |
| --- | --- | --- |
| P0 | 合成字幕、ASR 回退、PDF 与多分集素材的固定离线回放 | 冻结有使用许可的真实短视频、无字幕视频、多分集视频，记录采集时间、字幕/画面质量与文件样本 |
| P1 A1/A2 | Host 预留 Version、Candidate 幂等提交、内容与文件先验、READY/DEGRADED、回滚；旧 Skill、历史 Version 与 PDF 下载兼容 | 真实对象存储及跨进程崩溃后的暂存物对账与恢复 |
| P1.5 A3 | 受控 Source Snapshot 窗口分页、预算、来源引用、旧 `sample_text` 兼容；仓库真实长文档的后段事实进入 Worker Citation | 用户实际长资料下的 Host→Worker 全链回放、人工质量及线上 Provider 效果 |
| P2 | 独立视频 Bundle、字幕/画面观察、文件摘要、知识 Plan、父资料 Task 与复用分支；离线 Provider 合同 | 有许可真实视频的完整采集、OCR/观察质量、无字幕 ASR 与分集画面回放 |
| P3 | 博客、问答的独立 Skill、IR、证据 Verifier、Markdown/Version、局部 Repair 合同 | 真实资料下的结论一致性、文章质量及实际模型 Repair 样本 |
| P4 | 原画面 PPTX、逐页文件清单、Host 校验与目录；PowerPoint 桌面端合成双页打开及版面压力回放 | 真实画面与真实多页、容器 LibreOffice 转换、逐页预览与 PPTX 视觉一致性、人工可读性验收 |
| P5 | 尚未开放 | P4 稳定后才试点动画、第二模板和原生图形版，并逐页人工核对 |
| P6 | 四选 UI、独立父子状态/重试/取消/历史版本下载、Workspace 与 Skill Version 开关、子项对账补建；新 Version 显式持有素材 Bundle，历史 Version 仅在冻结引用闭合时回填 | Docker/Kafka/对象存储跨进程中断恢复，实际文件浏览器保存，无法自动回填的旧 Version 审计与可证明安全的 Bundle 清理，灰度样本及回滚演练 |
| C0–C2 | Context v2 Schema、主题回跳与约束、摘要修订、预算选择、合成标注样本 | 扩大真实会话的人工标注与误选统计 |
| C3 | Answer、Research、Artifact 分别消费冻结 v2 投影；Research/Artifact 独立全局门禁，Memory 撤销与输入读取阻断；派生 Research Source 目录、后续 Research、QA/NOTE、Artifact 输入与带冻结输入的已发布 Artifact Version 读取/写回过滤撤销来源；Wiki 当前/历史版本、首页链接、索引标题及 Citation ID 提取过滤已撤销引用；答案生成前拒绝撤销的 Research Citation 文本 | 已发布 Research 正文及文件、无冻结输入的旧 Artifact Version、Wiki 索引聚合计数和既有答案正文/引用展示等读取路径和删除策略；搜索索引清理、真实 Worker 缓存及跨进程撤销传播 |
| C4 | Workspace 开关、Answer v1/v2 引用差异与最近 Run 汇总、OFF→SHADOW 旧会话下一轮回填、v1 回退记录 | 真实标注会话的误选/漏约束指标、灰度阈值和生产回滚演练；批量历史迁移不在现有合同中 |

环境核对：本机 PowerPoint 可打开并导出合成 PPTX；`docker info` 无法连接 Docker Desktop Linux Engine，未检测到 LibreOffice。真实 B站 BV 样本与许可状态尚未提供。一次广泛清空 Research 问题、报告、Trace 与任务状态的改动被自动审批拒绝，未执行；现有实现只精确脱敏冻结投影并阻断所覆盖的消费和读取入口，不能声称完成所有已发布内容的删除传播。
