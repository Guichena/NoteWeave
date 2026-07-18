# 阶段 6：Knowledge Graph 拆分验收

- 日期：2026-07-14
- 范围：Wiki graph overview/ego read model 与 traversal
- 状态：`KnowledgeGraphService` 已接管 `/wiki-graph` 真实 API 路径
- 兼容约束：保持原 graph 节点、边、meta 和过滤/遍历语义

## 原问题

查询边界第一批完成后，`KnowledgeService` 仍包含完整 graph orchestration：

- Workspace 存在性校验；
- Wiki page 聚合行转 graph node；
- relation edge SQL；
- page kind filter；
- overview 排序与截断；
- ego 无向邻接构造、BFS、depth/limit；
- invalid center fallback 与 selected edge 裁剪。

这些逻辑与 Knowledge CRUD、version、governance 和 link mutation 混在同一 Service，无法独立增加 traversal budget、缓存或 graph 指标。

## 已完成实现

### KnowledgeGraphService

新增独立 `KnowledgeGraphService`，依赖：

- `WorkspaceQueryPort`：保持 `WORKSPACE_NOT_FOUND` 边界；
- `KnowledgeWikiSearchEngine`：复用 Query/API 的同一 Wiki read rows；
- `JdbcTemplate`：只读取 workspace relation edges。

`KnowledgeController` 现在：

- `/wiki-search` 直接调用 `KnowledgeQueryService`；
- `/wiki-graph` 直接调用 `KnowledgeGraphService`；
- 其他尚未迁移的 CRUD、治理和统计继续调用旧 `KnowledgeService`。

旧 facade 已移除 `searchWikiPages/getWikiGraph`，避免 Controller 迁移后仍保留第二入口。

### 保持的 Graph 语义

- 非 `ego` mode 统一为 `overview`；
- depth 限制为 1..3；
- limit 限制为 4..120；
- page kind 大小写不敏感、去空白、去重；
- ego 模式即使 kind filter 不包含 center kind，也保留显式 center；
- overview 继续使用 degree、citation count、version、title 的既有 comparator；
- ego 把具有非空 target item 的 edge 视为无向邻接并按 BFS 扩展；
- 同层邻居继续按 degree 降序、title 升序；
- 达到 limit 立即停止，不读取更深节点；
- selected edges 只保留两端均在 selected nodes 内的边；
- center 缺失或无效且仍有过滤后节点时，回退 overview、清空 center、depth 归一为 1；
- `truncated` 继续比较 selected node 与 filtered node 数量。

## 结构变化

- `KnowledgeService`：1717 行进一步降至 1496 行；相对拆分前 1928 行累计减少 432 行；
- graph SQL、node mapping、filter、overview/ego selection 与 `GraphHop` 已全部移出旧 Service；
- 没有修改 Flyway、数据库表、HTTP path、JSON 字段或前端调用契约。

## 自动化证据

- `KnowledgeGraphServiceTest`：3/3；
  - overview kind filter、depth/limit normalization 与 truncated；
  - ego depth 1/2 BFS、邻居顺序和 edge cropping；
  - invalid center fallback 与 missing Workspace 拒绝；
- `KnowledgeQueryServiceTest`：2/2；
- `Phase3NoteWikiContractTest`：27/27；
- `ArchitectureBoundaryTest`：15/15，新增 GraphService 不得依赖旧 facade 的规则；
- 定向回归：47/47；
- Backend 全量：223/223，0 failures、0 errors、0 skipped；
- Surefire 报告：62 份。

## 后续边界

- 当前 graph traversal 仍是数据库 read model，没有引入 Redis cache；正确性继续来自 Knowledge latest version 与 relation 表；
- Answer Wiki graph budget 与 UI graph traversal 是两个独立预算域，不应复用同一个配置对象；
- 下一步迁移 `KnowledgeVersionService` 时必须保持 version immutable、append 事务和 latest pointer 原子切换；
- link rebuild、auto-fix 与 relation mutation 后续归入独立 graph command/projection 边界，不应重新塞回 read service。
