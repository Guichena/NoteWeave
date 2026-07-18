# 阶段 0A Docker 全链路 Smoke 验收

- 验收时间：2026-07-13 02:14（Asia/Shanghai）
- 执行命令：`.\scripts\smoke-phase0a-docker.ps1`
- 结果：通过
- 原始报告：`target/docker-smoke/phase0a-smoke-20260713021347-7352.json`
- Run ID：`20260713021347-7352`

## 验收范围

| 检查 | 结果 | 证据摘要 |
| --- | --- | --- |
| 容器健康 | 通过 | MySQL、Redis、Kafka、MinIO、Elasticsearch、Backend、Frontend、Research Worker API、Artifact Worker API 全部 healthy |
| Frontend readiness | 通过 | `http://localhost:3000/healthz` 返回 200 |
| Backend readiness | 通过 | `/actuator/health` 返回 UP |
| 数据库迁移 | 通过 | Flyway 最新版本 V026 |
| Redis | 通过 | PING 返回 PONG |
| MinIO | 通过 | `noteweave-source` 中完成 PUT、GET 与清理 |
| Kafka | 通过 | 临时 topic 创建、生产、消费与删除成功 |
| Elasticsearch | 通过 | 临时索引写入、检索命中与删除成功 |
| 内部服务鉴权 | 通过 | Artifact Outbox metrics 使用内部令牌访问成功 |
| 上传与异步处理 | 通过 | Markdown 分片上传及 complete 成功，Task 最终为 COMPLETED |
| Source 投影 | 通过 | Source 最终为 READY |
| 实时会话 | 通过 | SSE 经 Nginx 收到 `chat.delta` 与 `chat.completed` |

## 本次修正

首次执行暴露的是验收脚本缺陷而非服务缺陷：脚本尝试临时下载 `minio/mc:latest`，受到外网 TLS 超时影响。现已改为复用固定版本 MinIO 容器内自带的 `mc`，使验收不依赖额外镜像下载；同时补齐 ES JSON 序列化、MD5 资源释放、Source READY 轮询和 Chat `client_request_id`。

## 结论

当前 Docker 开发/演示拓扑具备可重复启动和主链路自动验收能力。该结论只覆盖阶段 0A 定义的单机全容器基线，不等同于生产高可用部署认证。
