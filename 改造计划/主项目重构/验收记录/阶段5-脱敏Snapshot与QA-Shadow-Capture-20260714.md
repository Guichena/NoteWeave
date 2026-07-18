# 阶段 5 脱敏 Snapshot 与 QA Shadow Capture 验收

- 日期：2026-07-14
- 后端测试：165/165
- Gold schema：`retrieval-gold-v1`，新增 workspace-aware case 输入
- Shadow schema：`retrieval-shadow-v1`
- 状态：脱敏导出和 QA `ChunkSearchPort` capture 链路已建立；真实 Elasticsearch capture 尚待执行

## 完成内容

- 新增 `RetrievalSnapshotSanitizer`，对 case、workspace、evidence、source 和 citation ID 使用带盐 HMAC-SHA256 稳定假名化；
- 假名化保留 gold、ranking、scope 和 citation 之间的关联，可继续运行 replay 与 shadow comparator；
- 对 query、title、content 和 source type 中的 email、URL、UUID、IPv4、手机号、Windows 路径以及 token/API key/secret 做模式脱敏；
- 导出盐要求至少 16 个字符，CLI 从 `NOTEWEAVE_RETRIEVAL_EXPORT_SALT` 读取，不写入输出文件；
- sanitizer 可输出 pretty JSON 和各类敏感值命中计数，便于审计导出是否发生脱敏；
- `RetrievalGoldSet.GoldCase` 增加 `workspaceId`，fixture 与 replay 测试已同步；
- 新增 `QaChunkSearchShadowCapture`，只选择 QA cases，并通过 `ChunkSearchPort.search(workspaceId, query, 12)` 获取候选；
- capture 记录 evidence/source 假名、原始 score、调用 latency、candidate count，并同时写出 QA-only sanitized gold 与 shadow JSON；
- capture 的 Spring 主构造器显式标记 `@Autowired`，已通过 ApplicationContext 加载测试。

## 边界说明

- capture 依赖真实生产 Port 契约，不复制 Elasticsearch 查询或排序实现；
- 本轮 `QaChunkSearchShadowCaptureTest` 使用 mock `ChunkSearchPort`，验证的是 Port 参数、排名映射、越权检测、脱敏和文件写出；
- 尚未在真实启用的 Elasticsearch 上生成 snapshot，因此本记录不提供线上 Recall、MRR、nDCG、citation 或 p95 质量结论；
- fixture 中的 scope violation 和质量数值是受控测试输入，不代表生产表现；
- 本轮没有修改 `qa-passage-v1`、QA 排序、回答模板、citation 持久化算法或 `retrieval_plan_version`。

## 验证

- `RetrievalSnapshotSanitizerTest`：固定同盐确定性、异盐隔离、敏感字段脱敏、关联一致性和 sanitized replay；
- `QaChunkSearchShadowCaptureTest`：固定 workspace/query/topK 参数、QA-only 输出、排名假名化、JSON 无原 ID 和 comparator 对接；
- `NoteWeaveApplicationTests,QaChunkSearchShadowCaptureTest`：2/2，Spring ApplicationContext 正常加载；
- eval 定向回归：6/6，0 failures，0 errors，0 skipped；
- `mvnw.cmd -f backend/pom.xml clean test`：47 份 Surefire 报告，165/165，0 failures，0 errors，0 skipped；
- `git diff --check`：通过；
- eval 新建源文件、测试和 fixture 的 trailing whitespace 检查：通过。

## 后续

- 使用真实启用的 Elasticsearch/`ChunkSearchPort` 和可审计原始 gold 输入生成第一份脱敏 QA shadow snapshot；
- 对导出文件执行人工敏感信息抽查，并记录数据集版本、索引版本和 capture 环境；
- 在多轮真实样本稳定后，为 Recall/MRR/nDCG、citation、scope、refusal 和 p95 建立版本化阈值；
- 只有 baseline 与门禁稳定后，再进入 vector/RRF/rerank 的 shadow 对照。
