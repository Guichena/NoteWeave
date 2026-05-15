# Docker Middleware Contract

本文档定义 NoteWeave 的本地开发和测试中间件契约。所有编程阶段都必须遵守：中间件一律通过 Docker 或 Testcontainers 启动，不依赖开发者本机散装安装的服务。

---

## 1. 全局规则

所有中间件都必须容器化：

```text
MySQL
Redis
MinIO
Elasticsearch
Kafka
```

本地开发使用根目录：

```text
docker-compose.yml
```

测试使用：

```text
Testcontainers
```

禁止在代码、测试或文档中假设本机已经安装并启动了 MySQL、Redis、MinIO、Elasticsearch 或 Kafka。

---

## 2. 本地 Docker Compose

启动：

```bash
docker compose up -d
```

停止：

```bash
docker compose down
```

清理本地中间件数据：

```bash
docker compose down -v
```

默认服务：

| 服务 | 容器名 | 宿主机端口 | 容器端口 |
|---|---|---:|---:|
| MySQL | `noteweave-mysql` | `3307` | `3306` |
| Redis | `noteweave-redis` | `6380` | `6379` |
| MinIO API | `noteweave-minio` | `9000` | `9000` |
| MinIO Console | `noteweave-minio` | `9001` | `9001` |
| Elasticsearch | `noteweave-elasticsearch` | `9200` | `9200` |
| Kafka | `noteweave-kafka` | `9092` | `9094` |

默认环境变量见：

```text
.env.example
```

---

## 3. 本地连接配置

Spring dev profile 默认应能连接 Docker Compose：

```text
DB_URL=jdbc:mysql://localhost:3307/noteweave?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
DB_USERNAME=noteweave
DB_PASSWORD=noteweave
REDIS_HOST=localhost
REDIS_PORT=6380
MINIO_ENDPOINT=http://localhost:9000
MINIO_BUCKET=noteweave-dev
ES_URIS=http://localhost:9200
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

非 dev profile 不能使用弱默认密钥或默认密码启动。

---

## 4. 测试中间件

集成测试必须使用 Testcontainers。

当前已使用：

```text
MySQLContainer
Redis GenericContainer
MinIO container
KafkaContainer
ElasticsearchContainer
```

当前测试基线按需使用以下资源：

```text
noteweave-test bucket
test.noteweave.task.{testRunId}
test.noteweave.document.{testRunId}
noteweave-test-{testRunId}-document-chunk
```

测试不得依赖 `docker compose up -d` 已经执行。测试应自行启动所需 Testcontainers，并通过 `DynamicPropertySource` 注入连接信息。

---

## 5. 测试路径与对象命名

测试不能使用用户机器上的绝对路径作为业务数据路径。

允许的本地临时路径：

```text
target/noteweave-test/{phase}/
```

示例：

```text
target/noteweave-test/phase-2/uploads/
target/noteweave-test/phase-3/parsed-text/
target/noteweave-test/phase-8/artifacts/
```

MinIO 测试 bucket：

```text
noteweave-test
```

MinIO 测试对象 key 必须带 `test/` 前缀：

```text
test/{testRunId}/uploads/{uploadId}/chunks/{chunkIndex}
test/{testRunId}/objects/{contentHash}
test/{testRunId}/parsed-text/document/{documentId}/{indexVersion}.txt
test/citations/{citationId}/snapshot.txt
test/artifacts/{artifactId}/exports/{fileName}
```

本地开发 bucket：

```text
noteweave-dev
```

本地开发对象 key 使用：

```text
dev/uploads/{uploadId}/chunks/{chunkIndex}
dev/objects/{contentHash}
dev/parsed-text/{sourceType}/{sourceId}/{version}.txt
dev/citations/{citationId}/snapshot.txt
dev/artifacts/{artifactId}/exports/{fileName}
```

---

## 6. Kafka Topic

本地 Docker Compose 默认创建：

```text
noteweave.task
noteweave.document
noteweave.index
```

测试 topic 必须使用测试前缀或随机后缀：

```text
test.noteweave.task.{testRunId}
test.noteweave.document.{testRunId}
test.noteweave.index.{testRunId}
```

---

## 7. Elasticsearch Index

本地开发索引前缀：

```text
noteweave-dev-
```

测试索引前缀：

```text
noteweave-test-{testRunId}-
```

测试结束后必须清理测试索引。

当前集成测试使用 Testcontainers 隔离 Elasticsearch 容器，容器销毁即清理对应测试索引；若在共享 ES 上运行测试，必须显式删除 `noteweave-test-{testRunId}-*`。

---

## 8. Phase 要求

每个 Phase 如果引入新的中间件依赖，必须同步更新：

```text
docker-compose.yml
.env.example
docs/DOCKER_MIDDLEWARE.md
对应 Testcontainers 基类或测试配置
```

阶段不能假设“以后再补 Docker”。如果当前阶段用到了中间件，就必须在当前阶段把 Docker、本地配置和测试配置一起补齐。

---

## 9. Phase 2 runtime/testing note (2026-05-15)

Current integration test baseline now includes:

```text
MySQLContainer
Redis GenericContainer
MinIO container
KafkaContainer
```

Elasticsearch Testcontainer remained deferred in Phase 2 and was not required by Phase 2 tests.

---

## 10. Phase 3 runtime/testing note (2026-05-15)

Phase 3 integration tests now use the full containerized middleware baseline:

```text
MySQLContainer
Redis GenericContainer
MinIO container
KafkaContainer
ElasticsearchContainer
```

Elasticsearch:

```text
Local index prefix: noteweave-dev-
Test index prefix: noteweave-test-{testRunId}-
Phase 3 document chunk index suffix: document-chunk
Full test index name example: noteweave-test-{testRunId}-document-chunk
```

MinIO parsed text objects:

```text
dev/parsed-text/document/{documentId}/{indexVersion}.txt
test/{testRunId}/parsed-text/document/{documentId}/{indexVersion}.txt
```

Kafka:

```text
DOCUMENT_PROCESS uses noteweave.document locally.
Integration tests use test.noteweave.document.{testRunId}.
The consumer payload is treated as taskId-only; worker execution loads task/document state from MySQL.
```

