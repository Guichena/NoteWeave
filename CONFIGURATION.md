# NoteWeave 配置说明

## 启动方式

- `start-noteweave.ps1`：一键启动本地 Docker 全栈
- `docker compose up -d --build`：手动构建并启动
- `docker compose down`：停止整套服务

## 主要配置文件

- `.env`：本机运行时实际使用的环境变量
- `.env.example`：Docker 本地默认配置样例
- `.env.embedding.example`：单独的 embedding 配置样例
- `docker-compose.yml`：本地中间件、端口与应用编排
- `Dockerfile`：应用容器构建方式

## 常用环境变量

- `SERVER_PORT`：应用端口，默认 `18082`
- `MYSQL_PORT`：MySQL 映射端口，默认 `13307`
- `REDIS_PORT`：Redis 映射端口，默认 `6380`
- `MINIO_PORT`：MinIO API 端口，默认 `19100`
- `ES_PORT`：Elasticsearch 端口，默认 `19200`
- `KAFKA_PORT`：Kafka 端口，默认 `19092`
- `SPRING_PROFILES_ACTIVE`：默认 `dev`
- `NOTEWEAVE_LLM_STUB_ENABLED`：默认启用本地 LLM stub
- `EMBEDDING_ENABLED`：默认 `false`
- `EMBEDDING_STUB_ENABLED`：默认 `true`

## 本地开发建议

- 只想跑应用：直接执行 `start-noteweave.ps1`
- 只想看日志：`start-noteweave.ps1 -Logs`
- 需要重新构建：`start-noteweave.ps1 -Build`
- 需要改模型或外部服务：优先修改 `.env`，再重启容器

## 端口速查

- `18082`：NoteWeave Web / API
- `13307`：MySQL
- `6380`：Redis
- `19100`：MinIO API
- `19101`：MinIO Console
- `19200`：Elasticsearch
- `19092`：Kafka

## 说明

- 当前仓库已配置为本地可直接启动。
- Docker 构建默认使用 Maven 镜像源加速，避免依赖下载不稳定。
- 开发种子数据来自 `dev` profile，适合本地演示与联调。
