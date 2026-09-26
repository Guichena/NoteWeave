# NoteWeave v2 Backend

后端采用 `Java 17 + Spring Boot 3.3.x`，是 NoteWeave 的业务真源与主协调层。

## 当前能力

1. Flyway `V001` 到 `V104`（版本号存在跳号，以迁移目录为准；具体环境版本以 `flyway_schema_history` 为准）
2. 创建工作台
3. 创建上传事务、上传分片、完成上传
4. 解析资料并生成 `source_chunk`，解析和投影状态由任务/数据库记录推进
5. 创建会话、发送 QA 消息、生成 citation
6. `NOTE` 链路：参考 Marginalia 的资料级检索方式，先定位候选资料，再打开原文窗口生成摘录证据和带引用回答；`source-draft` 生成中性 Markdown 草稿，用户确认后 `save-as-source` 写入资料池
7. `WIKI` 链路：参考 WebKonra / WeKnora 的全量 Wiki 形态，支持工作台级 wiki 构建开关、资料变更 ingest、页面版本、链接/反链、来源回链、搜索、图谱、统计、日志、lint、rebuild links 和 auto-fix
8. 查询任务状态与聊天 SSE 回放
9. Deep Research Job、checkpoint/evidence、报告回存资料与 Worker 编排
10. Skill-first Artifact Job/Version、冻结 source scope、自动 HTTP outbox、终态幂等 callback、typed runtime trace
11. Artifact Version 保存为资料/Note/Wiki、同 Job 再生成/比较/追加式回滚、Bilibili PDF 导出与统一 ObjectStorage file metadata
12. owner-scoped Artifact outbox lease、dead-letter/redrive、Micrometer/内部指标与错误级告警
13. Java `/internal/*` 与 Artifact Worker 正式控制面的双向共享令牌认证

## 本地运行

在仓库根目录执行：

```powershell
.\mvnw.cmd -f backend\pom.xml spring-boot:run
```

macOS / Linux：

```bash
./mvnw -f backend/pom.xml spring-boot:run
```

默认连接：

- MySQL：`localhost:3306/noteweave`
- Redis：`localhost:6379`
- Kafka：`localhost:9092`
- MinIO：`localhost:9000`
- Elasticsearch：`localhost:9200`

## QA 检索评测 CLI

评测 CLI 使用专用 `retrieval-evaluation-cli` Profile，只启动 Elasticsearch 读侧与脱敏/编译组件，不加载 Web、JDBC、Flyway、Kafka listener 或后台调度。在线 QA 与 CLI 共用 `ElasticsearchChunkSearchAdapter`，写侧索引仍由 `ElasticsearchIndexer` 独立负责。

在仓库根目录的 PowerShell 中设置 Elasticsearch 连接和至少 16 字符的导出盐：

```powershell
$env:NOTEWEAVE_RETRIEVAL_EXPORT_SALT='replace-with-a-private-random-salt'
$env:NOTEWEAVE_ES_HOST='localhost'
$env:NOTEWEAVE_ES_PORT='9200'
```

如果本地 Elasticsearch 只有 smoke 数据，可先通过真实 Workspace/Upload/Parse/Index 链路导入仓库设计文档，并在 `backend/target` 生成 raw annotation request：

```powershell
.\scripts\prepare-stage5-qa-annotation.ps1
```

该脚本只创建待捕获的 request，不会生成 `REVIEWED` 标签、gold set 或质量门禁结论。输出中的 Workspace、Source 和 query 仍是 raw 本地评测输入，不应提交到仓库或复制到外部系统。

捕获待人工标注的脱敏草稿：

```powershell
.\mvnw.cmd -f backend\pom.xml `
  "-Dexec.mainClass=com.noteweave.retrieval.eval.RetrievalEvaluationCli" `
  "-Dexec.args=capture-draft <request.json> <draft.json>" `
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

人工只修改草稿中的审核状态、拒答判断、相关 evidence、期望 citation 和 notes；完成审核后编译 sanitized gold/shadow：

```powershell
.\mvnw.cmd -f backend\pom.xml `
  "-Dexec.mainClass=com.noteweave.retrieval.eval.RetrievalEvaluationCli" `
  "-Dexec.args=compile-reviewed <request.json> <reviewed.json> <gold.json> <shadow.json> <snapshot-version>" `
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

安全边界：

- raw request 可能包含真实 workspace、query 和 Source ID，只保存在受控本地目录，不提交仓库；
- 盐只通过环境变量提供，不写入 request、draft、gold、shadow 或日志；
- draft、reviewed、gold 与 shadow 均只应包含假名化 ID 和脱敏文本；
- `compile-reviewed` 会使用相同 request/salt 重新捕获当前候选，候选集合、顺序、score、snapshot 或正文漂移时拒绝编译；
- 使用不存在 fixture workspace 得到空候选的 CLI smoke，只能证明启动、ES 连接、脱敏与落盘链路，不代表真实检索质量。

`backend/target` 是临时构建目录，`mvn clean` 会删除其中的评测输入和输出。需要长期保留时，应先把受控 raw 输入移到受限且 Git ignored 的备份目录，再从 sanitized gold/shadow 生成下述无正文 receipt；不要把 raw request 或 HMAC 盐提交到仓库。

## QA AnswerRun Shadow 导出

线上导出器只读取已经完成并持久化的 QA AnswerRun，不重新执行检索或生成。输入 run map 必须使用 `qa-answer-run-shadow-export-request-v2`，且唯一支持的 `strategyProfile` 为 `qa-retrieval-v2`。生产执行还必须使用数据库层真正只授予必要 `SELECT` 的账号，Spring/Hikari 的 read-only 标记不能替代数据库权限。

```powershell
$env:NOTEWEAVE_RETRIEVAL_EXPORT_SALT='replace-with-a-private-random-salt'
$env:SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/noteweave'
$env:SPRING_DATASOURCE_USERNAME='noteweave_retrieval_reader'
$env:SPRING_DATASOURCE_PASSWORD='replace-with-read-only-password'

.\scripts\export-stage5-qa-answer-run-shadow.ps1 `
  -AnnotationRequestPath <annotation-request.json> `
  -RunMapPath <run-map-v2.json> `
  -OutputPath <shadow.json>
```

导出器在 read-only `REPEATABLE_READ` 事务中固定执行三次批量查询，并对 AnswerRun、plan、profile、scope、EvidenceBundle、trace、预算和 citation tuple 做 fail-closed 校验。该代码能力不等于已经取得真实 online AnswerRun 分布或 production quality policy。

## QA 评测 Evidence Receipt

receipt CLI 消费一份 sanitized gold 与一至多份 sanitized shadow，不读取 raw request，也不需要 HMAC 盐：

```powershell
.\mvnw.cmd -f backend\pom.xml `
  "-Dexec.mainClass=com.noteweave.retrieval.eval.RetrievalEvaluationEvidenceReceiptCli" `
  "-Dexec.args=<gold.json> <receipt.json> <shadow-1.json> [shadow-2.json ...]" `
  org.codehaus.mojo:exec-maven-plugin:3.5.0:java
```

`retrieval-evaluation-evidence-receipt-v1` 只持久化 artifact byte size/SHA-256、安全版本标识、策略 profile、聚合质量/延迟和完整 ranking signature。它不写文件路径、query、正文或 case/evidence/source/citation 标识。`rankingsStable=true` 只表示传入的多份 artifact 排名签名一致，不证明这些文件来自相互独立的执行，也不是数字签名、外部见证或 online AnswerRun 证明。

## Docker 运行

默认 `docker compose up -d` 只启动基础设施，适合 Java / Python 本地开发。

如果要把 Java 主系统和 Python workers 也放进 Docker：

```bash
docker compose --profile app up --build
```

完整容器模式还会启动 Nginx Frontend，入口为 `http://localhost:3000`。Backend 在容器内显式连接 `minio:9000` 与 `elasticsearch:9200`，不能使用 application.yml 的 localhost 开发默认值。

容器内服务互联使用 Compose service name：

- Backend -> MySQL：`mysql:3306`
- Backend -> Kafka：`kafka:9092`
- Backend -> Redis：`redis:6379`
- Backend -> MinIO：`http://minio:9000`
- Backend -> Elasticsearch：`http://elasticsearch:9200`
- Research Worker -> Backend：`http://backend:8081`
- Research Worker -> Kafka：`kafka:9092`
- Backend -> Artifact Worker：`http://artifact-worker-api:18092`

## 测试

```bash
./mvnw test
```

测试使用 H2 + Flyway，覆盖基础业务、Research 与 Artifact 纵向契约。
