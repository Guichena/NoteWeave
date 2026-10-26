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
| `eval_chain.sh <镜像标签>` | 评测目录 | 构建完成后部署并依次评测开发集与留出集，最后评分并与基线、`PREV` 指定的上一版对照；进度写入状态文件 |
| `q.sh`、`qraw.sh` | 评测目录 | 在 MySQL 容器内执行只读查询，`qraw.sh` 不转义输出 |
| `run_with_env.py` | 评测目录 | 加载部署配置与评测账号后执行评测脚本 |

长时间任务用 `setsid nohup` 脱离 SSH 会话执行，本地只轮询状态文件；SSH 网关偶尔断开。
