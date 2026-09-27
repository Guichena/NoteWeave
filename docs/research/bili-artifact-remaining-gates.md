# B站 Artifact 与 Context 剩余门禁（2026-09-27）

本清单按已运行的合同与实际环境记录，详细结果见 `bili-artifact-baseline.md`。计划中的目标设计不等于已交付能力。当前生产入口 `noteweave.video-learning.enabled`、Context v2 的全局 ACTIVE、Research ACTIVE 与 Artifact ACTIVE 均默认关闭。

2026-09-27 用户要求当前批次先跳过依赖真实模型输出的质量门禁，继续前后端功能接入。视频素材 Bundle 仍作为内部复用对象；用户界面以四种独立产物和各自状态、重试、Version 为主。模型效果门禁仅延期，不记为通过。

| 工作包 | 已有可验证能力 | 剩余门禁 |
| --- | --- | --- |
| P0 | 合成样本回放；用户提供的单分集 BV1MZYT6pEzy 完成真实 ASR、8 张画面采集及质量抽检 | 冻结许可状态；补齐真实短视频、无字幕视频、多分集视频的完整样本与耗时/质量记录 |
| P1 A1/A2 | Host 预留 Version、Candidate 幂等提交、内容与文件先验、READY/DEGRADED、按角色恢复文件并读回摘要、回滚；旧 Skill、历史 Version 与 PDF 下载兼容 | 真实对象存储、Worker 文件过期及跨进程崩溃后的暂存物对账与恢复 |
| P1.5 A3 | 受控 Source Snapshot 窗口分页、预算、来源引用、旧 `sample_text` 兼容；仓库真实长文档的后段事实进入 Worker Citation | 用户实际长资料下的 Host→Worker 全链回放、人工质量及线上 Provider 效果 |
| P2 | 独立视频 Bundle、字幕/画面观察、文件摘要、知识 Plan、父资料 Task 与复用分支；用户提供 BV 的 ASR + 8 张原画面已通过本地冻结 Bundle/证据 Plan 校验；生产回调已接通配置模型的语义规划 | 已配置模型的真实视频规划请求仍超时，需缩短/分段处理并完成语义质量验收；该视频 OCR/Host 发布全链路、字幕错听修订及多分集回放 |
| P3 | 博客、问答的独立 Skill、IR、证据 Verifier、Markdown/Version、局部 Repair 合同；真实 Bundle + 测试语义 Plan 的博客 8 节、问答 8 条通过下游校验 | 真实模型 Plan 下的结论一致性、文章和问答质量及实际模型 Repair 样本 |
| P4 | 原画面 PPTX、逐页文件清单、Host 校验与目录、按需加载的逐页预览界面；真实 Bundle + 测试语义 Plan 的 8 页 PPTX 在本机 PowerPoint 打开并导出 8/8 页 | 排除推广等非教学画面、真实模型 Plan 下的内容可读性、容器 LibreOffice 转换及逐页预览与 PPTX 一致性 |
| P5 | 尚未开放 | P4 稳定后才试点动画、第二模板和原生图形版，并逐页人工核对 |
| P6 | 四选 UI、独立父子状态/重试/取消/历史版本下载、Workspace 与 Skill Version 开关、子项对账补建；新 Version 显式持有素材 Bundle，历史 Version 仅在冻结引用闭合时回填；Chrome 验证前端 Blob 保存动作；WSL 隔离栈登录、四 Skill 入口、四选父任务创建、Kafka 消费、失败回执 200 及重复回执幂等终态实测 | 真实视频 Bundle 成功发布、Docker/Kafka/对象存储跨进程中断恢复、带真实后端的 Version 页面文件保存、无法自动回填的旧 Version 审计与 Bundle 清理、灰度样本及回滚演练 |
| C0–C2 | Context v2 Schema、主题回跳与约束、摘要修订、预算选择、合成标注样本 | 扩大真实会话的人工标注与误选统计 |
| C3 | Answer、Research、Artifact 分别消费冻结 v2 投影；Research/Artifact 独立全局门禁，Memory 撤销与输入读取阻断；派生 Research Source 目录、后续 Research、QA/NOTE、Artifact 输入与带冻结输入的已发布 Artifact Version 列表/详情/文件清单/下载/写回过滤撤销来源；Wiki 当前/历史版本、首页链接、索引标题、Citation ID 提取与新 Knowledge 写入过滤已撤销引用；答案生成前拒绝撤销的 Research Citation 文本 | 已发布 Research 正文及文件、无冻结输入的旧 Artifact Version、Wiki 索引聚合计数和既有答案正文/引用展示等读取路径和删除策略；搜索索引清理、真实 Worker 缓存及跨进程撤销传播 |
| C4 | Workspace 开关、Answer v1/v2 引用差异与最近 Run 汇总、OFF→SHADOW 旧会话下一轮回填、v1 回退记录 | 真实标注会话的误选/漏约束指标、灰度阈值和生产回滚演练；批量历史迁移不在现有合同中 |

环境核对：本机 PowerPoint 可打开并导出合成与真实画面 PPTX；Ubuntu WSL2 Docker Engine 可用，本分支四个应用镜像已构建，隔离栈 Backend 健康，Worker 的 LibreOffice 将最小 PPTX 转成 1 页 PDF。完整 Kafka/MinIO/Host/前端下载链路仍待验证。用户已提供真实 B站 BV 样本供本地测试，其进一步分发或复用许可尚未核实。一次广泛清空 Research 问题、报告、Trace 与任务状态的改动被自动审批拒绝，未执行；现有实现只精确脱敏冻结投影并阻断所覆盖的消费和读取入口，不能声称完成所有已发布内容的删除传播。
