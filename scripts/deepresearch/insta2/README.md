# insta2 隔离评测脚本

Research 质量评测在 insta2 上的独立部署（compose 项目 `nw-research`）里运行，这些脚本是当时使用的版本，路径按 insta2 写死：部署目录 `/data/workspace/noteweave-research-deploy/`，评测目录 `/data/workspace/noteweave-research-eval/`，源码工作区 `/data/workspace/noteweave-research-dev2`。密钥只在部署目录的 `.env` 与评测目录的 `eval.env` 里，脚本不读取也不输出它们的值。

| 脚本 | 放置位置 | 用途 |
|---|---|---|
| `build_images.sh <标签>` | 部署目录 | 用 dev2 工作区构建 Worker 与后端镜像，构建后清空构建缓存（根分区只有 16G） |
| `build_worker_only.sh <新标签> <后端沿用的标签>` | 部署目录 | 只有 Worker 改动时只构建 Worker，后端镜像打新标签沿用，并生成对应的 compose 覆盖文件 |
| `deploy.sh <覆盖文件>` | 部署目录 | 叠加 compose 文件，只重建后端与研究 Worker；重建前把容器日志存档到 `logs/` |
| `compose.research.json`、`compose.baseline.json`、`compose.example.json` | 部署目录 | 端口与镜像覆盖；baseline 用 `083b299f` 的 dev 镜像 |
| `start_eval.sh <标签> [源码目录] [数据集]` | 评测目录 | 题目按奇偶分两路并行；`EXCLUDE="题目..."` 用于中断后续跑 |
| `wait_eval.sh <标签>` | 评测目录 | 等待评测结束；磁盘低于 1GB 或 Worker 容器退出时提前返回 |
| `score_eval.sh <标签>` | 评测目录 | 合并两路结果并评分（3 次取多数，结果缓存） |
| `compare_eval.py <标签...>` | 评测目录 | 通过、单元格、各维度均分、耗时、调用量与逐题对照 |
| `gap_causes.py <标签>` | 评测目录 | 缺口单元格成因统计 |
| `plan_coverage.py <标签> <数据集>` | 评测目录 | 用评审模型判断规划是否覆盖必答要点 |
| `case_dump.py <标签> <题目> [--report]` | 评测目录 | 某题的列定义、单元格结论、评分理由，可选打印最终报告 |
| `cell_clusters.py <标签> <题目>` | 评测目录 | 按取证任务打印每个单元格的候选组和最终选中的值，判断答案是没找到还是没选上 |
| `retrieval_stats.py <标签...> [-v]` | 评测目录 | 各轮取证任务的搜索命中、抓取篇数、可读窗口与拒收原因 |
| `fetch_probe.py`、`search_fetch_probe.py` | 评测目录，从标准输入交给 Worker 容器运行 | 用默认抓取链路逐个读取网址，或先搜索再逐个抓取，查看失败原因与站点分布 |
| `point_trace.py <标签> <数据集> [题目...]` | 评测目录 | 未通过题缺的必答要点，对照规划的专家要点与各列定义 |
| `evidence_grep.py <标签> <题目> <正则>` | 评测目录 | 在某题提交过的全部证据与最终报告里查关键词，判断要点缺在取证还是成文 |
| `excerpt_input.py`、`excerpt_replay.py` | 前者在评测目录生成输入，后者交给 Worker 容器运行 | 重放某一列在各存档窗口里实际交给抽取模型的原文段落，检查关键词是否被截掉 |
| `replay_planning.py`、`replay_narrative.py` | 评测目录，复制进 Worker 容器运行 | 用数据库里的任务输入离线重放规划与成文 |
| `gather_synth.py <输出文件> <标签...>` | 评测目录 | 取各题最后一次成文任务的输入快照，供下面三个脚本使用 |
| `diag_omit.py <输入> [并发]` | Worker 镜像起的临时容器 | 按线上提示重跑成文，统计正文漏写的已核验单元格是模型没写还是被逐句核对删掉，以及删掉的原因 |
| `compare_omit.py <输入> <新版 narrative_report.py> [并发]` | Worker 镜像起的临时容器 | 同一次模型输出分别用线上与新版整理逻辑处理，比较漏写单元格、正文字数与对比表行数；新模块单独加载 |
| `replay_ab.py <输入> <新版 narrative_report.py> <输出> [并发] [旧版模块]` | Worker 镜像起的临时容器 | 同一批成文输入，旧版与新版各出一份报告。不给旧版模块时旧版用镜像里的代码，第一次模型输出两边共用，只比较整理逻辑；给了旧版模块时两版各自调用模型，用于比较提示改动。输出带两版成文结构，可再用后端校验逐份核对 |
| `judge_ab.py <replay_ab 输出> <明细输出> [并发]` | 评测目录，经 `run_with_env.py` 运行 | 新旧两份报告按后端格式补上引用核对后，用评测的评分模型各评 3 次，配对比较必答要点覆盖与通过数；任一边评分失败的题目不计入合计 |
| `[RUN=名称] [OLD=旧版模块] run_replay_ab.sh <镜像标签> <新版模块> <输入...>` | 评测目录的 exp 子目录 | 用 Worker 镜像起临时容器（内存上限 1GB，环境变量取自评测 Worker，不落盘）跑 `replay_ab.py`，再在宿主机跑 `judge_ab.py`；`RUN` 区分同时进行的多组对照的输出文件，`OLD` 传给 `replay_ab.py` 作旧版模块 |
| `ReplayedNarrativeValidationTest.java` | 本地临时复制到 `backend/src/test/java/com/noteweave/research/` | 用后端的成文校验逐份核对 `replay_ab.py` 输出里新旧两版成文，`-Dreplay.file=<路径>` 运行，打印未通过的题目与原因；用完删除，不提交到测试目录 |
| `point_evidence.py <输入...>` | 评测目录，经 `run_with_env.py` 运行 | 用评分模型逐条判断缺失的必答要点在交给成文的原文里有没有答案，区分损失在成文还是在检索、抽取 |
| `page_evidence.py <point_evidence 输出> <明细输出> [并发]` | 评测目录，经 `run_with_env.py` 运行 | 成文原文里没有答案的要点，按模型给出的关键词从该次运行抓到的网页全文里挑段落，再判断能否回答，区分损失在抽取还是检索；关键词预筛可能漏掉措辞不同的段落 |
| `[WAIT_FOR=状态文件] [OVERRIDE=覆盖文件] confirm_chain.sh <镜像标签> <轮次后缀>` | 评测目录 | 不构建，在同一镜像上把开发集与留出集再评测一轮，用于确认最终版本；`WAIT_FOR` 先等另一条链路结束，`OVERRIDE` 先用该覆盖文件重新部署（例如同一镜像只改配置开关） |
| `rerun_dev.sh <镜像标签> <轮次后缀> [等待的状态文件]` | 评测目录 | 等另一条链路结束后只重跑开发集，评分两次并与同一镜像首轮对照 |
| `[RESUME=1] version_chain.sh <新标签> <上一版标签>` | 评测目录 | 等上一版两路评测跑完后同步 dev2、构建两个镜像，先关闭补查评测开发集与留出集并与上一版对照，把 `:dev` 标签指向新镜像并删掉上一版镜像，再在同一镜像上打开补查评测一轮；中途停下后用 `RESUME=1` 从评测步骤接着跑 |
| `eval_chain.sh <镜像标签>` | 评测目录 | 构建完成后部署并依次评测开发集与留出集，最后评分并与基线、`PREV` 指定的上一版对照；进度写入状态文件 |
| `q.sh`、`qraw.sh` | 评测目录 | 在 MySQL 容器内执行只读查询，`qraw.sh` 不转义输出 |
| `run_with_env.py` | 评测目录 | 加载部署配置与评测账号后执行评测脚本 |

长时间任务用 `setsid nohup` 脱离 SSH 会话执行，本地只轮询状态文件；SSH 网关偶尔断开。

离线实验不要在评测用的 Worker 容器里 `docker exec` 运行：该容器内存上限 512MB，空闲时已占约 400MB，实验进程挤占内存会让容器重启，评测清单记录的容器启动时间随之改变，整轮结果被判为不可比较（2026-10-27 的 fix17 关闭补查对照轮开发集因此作废）。

同一份代码两次独立调用模型成文，40 题配对评分的通过数相差 2 到 3 题、覆盖要点相差 2 到 3 条，属于评分与成文的随机波动；配对对照的差异小于这个幅度时不作为改动有效的依据。
