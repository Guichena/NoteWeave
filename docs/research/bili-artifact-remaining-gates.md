# B站 Artifact 与 Context 剩余门禁（2026-09-27）

2026-09-28 追加：真实 BV 已经 Kafka/Worker/Host 保存 Bundle 与证据 Plan，并建成四个独立子 Job；四项均受语义 Plan 或 PDF LLM 输出阻断，没有 READY Version。导出文件认证问题已修复；修复后 Backend 全量 **1133 项、0 failed、0 error、11 skipped**，详见 `bili-artifact-baseline.md`。

同日继续：真实 Bundle 的 **12 个** MinIO PNG 对象与数据库大小、SHA-256 清单一致；单独重启隔离 MinIO 后仍 **12/12** 一致。文件对账、回滚和 Candidate 定向合同 **9/9 通过**。随后完成 Markdown Version 缺文件下载及重启读回回放；中途崩溃和浏览器保存仍待验收。

继续验收：仓库真实 24,953 字节设计文档经上传、解析为 76 个窗口；后段两个 Workflow History 窗口同时进入实际 `study_guide` Version Citation。该 Version 公开文件下载 4,902 字节、摘要正确；单对象暂时缺失时下载 409 `ARTIFACT_FILE_DEGRADED`，恢复后 200；公开回滚得到 Version 2，源/回滚文件摘要一致；隔离 Backend、MinIO 分别重启后两个版本仍可下载。仍未完成中途崩溃和 Worker 文件过期后的恢复、浏览器端真实保存。

本清单按已运行的合同与实际环境记录，详细结果见 `bili-artifact-baseline.md`。计划中的目标设计不等于已交付能力。当前生产入口 `noteweave.video-learning.enabled`、Context v2 的全局 ACTIVE、Research ACTIVE 与 Artifact ACTIVE 均默认关闭。

2026-09-27 用户要求当前批次先跳过依赖真实模型输出的质量门禁，继续前后端功能接入。视频素材 Bundle 仍作为内部复用对象；用户界面以四种独立产物和各自状态、重试、Version 为主。模型效果门禁仅延期，不记为通过。

| 工作包 | 已有可验证能力 | 剩余门禁 |
| --- | --- | --- |
| P0 | 合成样本回放；用户提供的单分集 BV1MZYT6pEzy 完成真实 ASR、8 张画面采集及质量抽检 | 冻结许可状态；补齐真实短视频、无字幕视频、多分集视频的完整样本与耗时/质量记录 |
| P1 A1/A2 | Host 预留 Version、Candidate 幂等提交、内容与文件先验、READY/DEGRADED、按角色恢复文件并读回摘要、回滚；旧 Skill、历史 Version 与 PDF 下载兼容；真实素材 12 个 MinIO 对象重启后摘要一致；真实 Markdown Version 下载、单对象缺失拒绝、公开回滚和两个 Version 跨 Backend/MinIO 重启读回通过 | 提交中途崩溃、Worker 文件过期后的非 Markdown 暂存物对账与恢复，以及真实 PDF/PPTX Version 的故障回放 |
| P1.5 A3 | 受控 Source Snapshot 窗口分页、预算、来源引用、旧 `sample_text` 兼容；仓库真实 24,953 字节长文档经 Host 上传和冻结，76 个窗口，后段两窗口进入 Worker 生成 Version 的 Citation | 更多用户长资料的人工质量与线上 Provider 效果抽检 |
| P2 | 独立视频 Bundle、字幕/画面观察、文件摘要、知识 Plan、父资料 Task 与复用分支；用户提供 BV 在 WSL Docker 中经 ASR、画面观察、Kafka 与 Host 发布 Bundle 和证据 Plan，父资料 Task 到 READY；生产回调已接通配置模型的语义规划 | 已配置模型的真实视频语义规划仍未成功，当前回退证据 Plan 缺语义主张；字幕错听修订、OCR 人工质量与多分集回放 |
| P3 | 博客、问答的独立 Skill、IR、证据 Verifier、Markdown/Version、局部 Repair 合同；真实 Bundle + 测试语义 Plan 的博客 8 节、问答 8 条通过下游校验 | 真实模型 Plan 下的结论一致性、文章和问答质量及实际模型 Repair 样本 |
| P4 | 原画面 PPTX、逐页文件清单、Host 校验与目录、按需加载的逐页预览界面；真实 Bundle + 测试语义 Plan 的 8 页 PPTX 在本机 PowerPoint 打开并导出 8/8 页，且在 WSL Docker LibreOffice 转出 8 页 PDF | 排除推广等非教学画面、真实模型 Plan 下的内容可读性、逐页预览与 PPTX 一致性 |
| P5 | 尚未开放 | P4 稳定后才试点动画、第二模板和原生图形版，并逐页人工核对 |
| P6 | 四选 UI、独立父子状态/重试/取消/历史版本下载、Workspace 与 Skill Version 开关、子项对账补建；新 Version 显式持有素材 Bundle，历史 Version 仅在冻结引用闭合时回填；Chrome 验证前端 Blob 保存动作；WSL 隔离栈登录、四 Skill 入口、四选父任务创建、Kafka 消费、真实 BV Bundle/Plan 发布与四个独立子 Job 建立、各子项失败隔离及错误展示、失败回执重复投递幂等终态实测；另一个真实 Source Artifact 的 Version 1/回滚 Version 2 公开 API 下载及 Backend/MinIO 重启读回通过 | 四种视频子产物在真实模型规划失败后均无 READY Version；Kafka 中断与提交中途崩溃恢复、带真实后端的浏览器 Version 文件保存、无法自动回填的旧 Version 审计与 Bundle 清理、灰度样本及回滚演练 |
| C0–C2 | Context v2 Schema、主题回跳与约束、摘要修订、预算选择、合成标注样本 | 扩大真实会话的人工标注与误选统计 |
| C3 | Answer、Research、Artifact 分别消费冻结 v2 投影；Research/Artifact 独立全局门禁，Memory 撤销与输入读取阻断；冻结 Context 的已发布 Research Markdown 正文在撤销后由详情 409、列表隐藏，前端无法再从详情取得导出正文；派生 Research Source 目录、后续 Research、QA/NOTE、Artifact 输入与带冻结输入的已发布 Artifact Version 列表/详情/文件清单/下载/写回过滤撤销来源；Wiki 当前/历史版本、首页链接、索引标题、Citation ID 提取与新 Knowledge 写入过滤已撤销引用；答案生成前拒绝撤销的 Research Citation 文本 | 历史无快照 Research Run 与独立文件路径、无冻结输入的旧 Artifact Version、Wiki 索引聚合计数和既有答案正文/引用展示等读取路径和删除策略；搜索索引清理、真实 Worker 缓存及跨进程撤销传播 |
| C4 | Workspace 开关、Answer v1/v2 引用差异与最近 Run 汇总、OFF→SHADOW 旧会话下一轮回填、v1 回退记录 | 真实标注会话的误选/漏约束指标、灰度阈值和生产回滚演练；批量历史迁移不在现有合同中 |

环境核对：本机 PowerPoint 可打开并导出合成与真实画面 PPTX；Ubuntu WSL2 Docker Engine 可用，本分支四个应用镜像已构建，隔离栈 Backend 健康，Worker 的 LibreOffice 将最小 PPTX 转成 1 页 PDF，实际原画面 PPTX 转成 8 页 PDF。Frontend 256 项、Worker 367 项、Backend 1132 项全量单元/合同测试通过（Backend 11 skipped）。完整 Kafka/MinIO/Host/前端下载成功链路仍待验证。用户已提供真实 B站 BV 样本供本地测试，其进一步分发或复用许可尚未核实。一次广泛清空 Research 问题、报告、Trace 与任务状态的改动被自动审批拒绝，未执行；现有实现只精确脱敏冻结投影并阻断所覆盖的消费和读取入口，不能声称完成所有已发布内容的删除传播。
