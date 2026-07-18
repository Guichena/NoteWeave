# 阶段 0A：Docker 全容器运行与交付基线

> 目标：让开发、集成测试和演示使用同一套可重复的容器拓扑，并明确它与生产部署的边界。  
> 当前入口：`docker compose --profile app up --build -d`。

## 实施状态（2026-07-13）

阶段 0A 开发/演示基线已落地并通过自动化全链路 Smoke。当前脚本 `scripts/smoke-phase0a-docker.ps1` 不依赖临时下载测试镜像，直接复用既有容器验证真实运行拓扑。

已验证：

- 9 个长期容器全部进入 `healthy`；
- Frontend/Nginx 与 Backend readiness 正常；阶段 0A 首次验收时 Flyway 为 V026，阶段 1 ACL/审计及阶段 4 AnswerRun 首批改造后已推进到 V030；
- Redis PING、MinIO PUT/GET/delete、Kafka produce/consume/delete、Elasticsearch index/search/delete 均成功；
- Worker 内部接口拒绝匿名并接受配置的内部令牌；
- Workspace 创建、Markdown 分片上传、合并、异步解析、Kafka 投递、Task `COMPLETED`、Source `READY` 全链路成功；
- Conversation 与 AnswerRun 创建成功；canonical SSE 经 Nginx 收到 `answer.delta/answer.completed`，最终 revision 为 `FINAL`，旧 `chat.delta/chat.completed` 回放仍兼容。

验收证据见 `验收记录/阶段0A-Docker全链路Smoke-20260713.md`。生产 Compose 拆分、供应链扫描及 CI 临时卷执行属于部署工程后续工作，不阻塞当前简历项目的最终本地 Docker 架构。

## 1. 当前拓扑

```text
Browser -> frontend(Nginx :3000)
              -> /api -> backend:8081
backend -> mysql:3306
        -> redis:6379
        -> kafka:9092
        -> minio:9000
        -> elasticsearch:9200
        -> artifact-worker-api:18092
research-worker-consumer -> kafka + backend callback
research-worker-api      -> backend
artifact-worker-api      -> backend
```

Compose service name 是容器内 DNS。容器之间禁止使用 `localhost` 访问其他服务；`localhost` 只用于容器访问自身。宿主机模型服务可通过 `host.docker.internal`，生产环境必须改为正式 provider endpoint。

## 2. 已落实的开发基线

- Frontend 使用多阶段 Node build + Nginx 静态运行；
- Nginx 同源代理 `/api` 到 Backend，并关闭代理缓冲以支持 SSE；
- Backend 显式使用 `minio:9000` 和 `elasticsearch:9200`；
- MySQL、Redis、Kafka、MinIO、ES、Backend、Frontend 和 Worker API 具备健康检查或完成门禁；
- Worker 等待 Backend/Kafka healthy，而不是只等待进程启动；
- Kafka 镜像由浮动 `latest` 改为 digest 固定；
- 每个 build context 有独立 `.dockerignore`；
- `.env.example` 记录本地配置，不提交真实 `.env` 和密钥。

## 3. 开发与生产必须分开

当前 `docker-compose.yml` 是单机开发/演示配置，允许宿主机访问基础设施端口并使用默认开发配置。生产不得直接照搬，后续拆分：

```text
compose.yml       通用服务、网络、健康检查
compose.dev.yml   build、宿主机端口、开发模型地址、调试开关
compose.prod.yml  registry image、secret、资源、内部网络、安全配置
```

生产要求：

- MySQL/Redis/Kafka/ES/MinIO 不暴露公网端口；
- 密钥来自 Docker Secret、Kubernetes Secret 或部署平台，不设可工作的默认值；
- Kafka、ES、MinIO 启用认证/TLS；
- 移除 `container_name`，允许副本扩展和多环境并存；
- Backend/Worker 使用非 root 用户、只读 root filesystem（runtime volume 除外）；
- 配置 CPU/memory、JVM container memory、nofile、日志轮转和优雅停机；
- 数据服务使用备份、恢复演练和明确的持久卷策略；
- 单节点 Kafka/MySQL/ES 只用于开发，生产可使用托管服务或独立高可用部署。

## 4. 健康检查语义

健康状态分为 liveness、readiness 和 dependency diagnostics。Backend liveness 不应因短暂 ES/Redis 故障重启进程；readiness 根据必需依赖决定是否接流量。MinIO/ES/Kafka 的错误还要进入业务降级指标，不能只依赖 Compose 启动检查。

Worker `/health` 后续拆成 liveness/readiness：API 进程存活不代表 callback token、Backend 或 Kafka 可用。Consumer 通过 lag、last poll、last successful callback 暴露运行状态。

## 5. 镜像和供应链

- 基础镜像固定明确版本；关键基础设施可固定 digest；
- Python Worker 使用 `pip install .` 或锁文件作为唯一依赖源，Dockerfile 不重复维护一套手写依赖；
- Java build 使用 Maven dependency cache，最终镜像只含 JRE/JAR/必要探针；
- Frontend 使用固定 pnpm 版本和 `pnpm install --frozen-lockfile`；`pnpm-lock.yaml` 是当前容器构建的唯一锁文件；
- CI 生成 SBOM，执行依赖和镜像漏洞扫描；
- 镜像标记 `git sha + semantic version`，禁止生产部署 `latest`；
- build 阶段运行测试，发布镜像前运行容器 smoke，不用 `-DskipTests` 充当质量门禁。

## 6. 启动与验证

```powershell
Copy-Item .env.example .env
docker compose --profile app up --build -d
docker compose --profile app ps
docker compose --profile app logs --tail 200 backend frontend research-worker-consumer
```

性能基线命令：

```powershell
.\scripts\measure-phase0-performance.ps1 -WarmSamples 30 -EsSamples 20 -FailOnThreshold
```

该脚本会重启 Elasticsearch 与 Backend、等待 healthy、测量首个/热 API 和 ES 查询，并生成 JSON/Markdown 报告。LLM 未启用时明确记为 skipped，不生成伪造延迟。

自动 smoke 至少验证：Frontend 与 Backend readiness、Flyway、MinIO PUT/GET、Kafka round trip、Redis、ES index/search/delete、上传到回答主链路、Worker 内部认证，以及 SSE 经 Nginx 后仍增量到达。

执行命令：

```powershell
.\scripts\smoke-phase0a-docker.ps1
```

脚本每次在 `target/docker-smoke/phase0a-smoke-*.json` 输出机器可读报告，任一检查失败即返回非零退出码。

## 7. CI 门禁

```text
static/unit tests
  -> build immutable images
  -> compose up --wait
  -> migration/contract smoke
  -> fixed-fixture business smoke
  -> cold/warm latency benchmark
  -> collect logs/metrics/test report
  -> compose down -v (CI only)
```

本地不得默认 `down -v`，避免删除用户数据。CI 使用独立 project name 和临时 volume，任何 smoke 失败都保留容器日志作为交付证据。

## 8. 验收

- 新机器只需要 Docker、Compose、`.env` 即可启动完整 UI 和主流程；
- 所有长期服务进入 healthy，init 容器成功退出；
- Frontend 通过同源 `/api` 访问 Backend，SSE 不缓冲；
- 容器内不存在将 MinIO/ES/Kafka/Backend 错写为 localhost 的配置；
- 重启任意无状态容器不丢业务数据；
- CI 可从空 volume 完成迁移、smoke 和固定样本验证；
- 生产差异项全部进入独立部署清单，不以开发 Compose 冒充生产架构。
