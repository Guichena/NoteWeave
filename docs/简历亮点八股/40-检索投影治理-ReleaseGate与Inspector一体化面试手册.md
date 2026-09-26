# 检索投影治理、Release Gate 与 Inspector 一体化面试手册

> 定位：这篇不重复 BM25、向量、RRF 和 Rerank 算法。面试推荐使用共享或租户分桶的 Generation Index、Catalog Watermark、Change Log Catch-up 和强制 Release Gate；每 Workspace Alias 与 Catalog 变化后全量重试只作为学校小规模实现背景。

## 1. 60 秒主回答

场景化 RAG 上线后，检索质量不只取决于算法。Embedding 模型、维度、Chunk Schema 或 Mapping 变化，都需要重建索引；直接原地覆盖会让用户在回填期间读到半新半旧的数据，也无法快速回滚。

我把 MySQL 的 Source Snapshot 和 Retrieval Projection 作为可审计真源，Elasticsearch 使用带 Schema、Embedding Version 和 Generation 的共享或租户分桶索引。重建时固定 Catalog Watermark，完成基线回填后消费 Change Log 追平期间发生的上传、更新和删除，再进入语义评测与发布。Release Manifest 记录 QA 与 Note 的 Generation 和兼容关系，只有必须保持同代语义时才成组切换。离线侧用版本化 Gold Set、Shadow Snapshot、消融矩阵和阈值策略形成受信 Quality Receipt，Runtime Inspector 展示某次 Run 实际使用的输入快照、配置和索引 Generation。

当前实现的结构覆盖门禁已经接到重建路径，语义质量 Release Gate 仍未硬接到 Alias 切换前，Receipt 也尚未验证报告文件和签名。推荐设计把 `PREPARE -> CATCH_UP -> EVALUATE -> PROMOTE -> OBSERVE` 作为主流程，当前路径作为迁移起点。

## 2. 真源、投影和发布对象

```text
Source
  -> immutable SourceSnapshot
  -> Chunk / Window
  -> retrieval_projection in MySQL
  -> versioned Elasticsearch physical index
  -> environment or tenant-bucket alias
  -> RetrievalPlan / EvidenceBundle
  -> AnswerRun / RunInputSnapshot

Gold Set + Shadow Snapshot + Policy
  -> Comparison Report
  -> Quality Gate Result
  -> Quality Receipt
  -> Release Gate
```

| 对象 | 保存什么 | 关键身份 |
| --- | --- | --- |
| Source Snapshot | 某版资料的不可变内容与解析状态 | Source ID、Snapshot ID、Version No |
| Retrieval Projection | 投影实体和构建状态 | Workspace、Snapshot、Projection Type、Entity ID |
| Index Build | 一次物理索引构建过程 | Target Index、Schema、Embedding Version、Expected Count |
| Elasticsearch Alias | 当前环境或租户桶的 Generation 查询入口 | Projection Type 加 Tenant Bucket |
| Shadow Snapshot | 某策略在固定 Case 上的实际排名 | Dataset、Snapshot、Strategy Profile |
| Quality Receipt | 一次评测结果摘要 | Plan、Dataset、Report、SHA-256 |
| Run Input Snapshot | 一次回答实际看到的上下文 | Execution Kind、Run ID、配置和引用身份 |

## 3. 为什么使用双投影

QA 的检索单位是 Chunk，需要保存正文、标题、Chunk No 和内容向量；Note 的第一阶段检索单位是 Source，需要保存摘要、标签、目录、章节描述、Chunk/Window 数量和 Source 向量。两者的文档数、Mapping、更新频率和召回目标不同。

把它们塞进同一个 ES 文档会让一个 Source 的聚合字段在每个 Chunk 重复，更新 Source 摘要时需要改大量文档；只保留 Source 文档又无法做精细 Passage Ranking。因此 `QA_CHUNK` 与 `NOTE_SOURCE` 分开构建。Release Manifest 声明两者是否必须兼容：Chunk Schema 与 Source Summary 可以独立升级时分别发布；Embedding、Catalog 或 Prompt Contract 要求同代时才成组切换，避免一条非关键投影失败拖垮另一条健康链路。

代价是写放大、两套覆盖计数和兼容矩阵。数据规模很小时可以只保留 QA Chunk，并在 MySQL 先做 Source 筛选；规模扩大后也不为每个 Workspace 创建长期 Alias，而是把 `workspace_id` 作为强制过滤字段，命中后继续做业务真源 Ownership 校验。

## 4. 一次索引重建怎样执行

`[目标设计]` 推荐重建主路径是：

1. 读取当前 Catalog Watermark，并开始记录水位后的 Change Log。
2. 选择每个未删除 Source 的最新已解析 Snapshot，并统计 QA Chunk 和 Note Source 期望数。
3. 将 Embedding Model、Dimensions、Schema Version 和随机 Generation 编入物理索引名。
4. 创建 QA 与 Note 两条 Index Build，状态从 CREATED 进入 BACKFILLING。
5. 为每个 Snapshot 向两个目标索引写投影，并在 MySQL 记录 READY。
6. 基线回填完成后消费 Watermark 之后的上传、更新和删除，直到增量 Lag 进入发布窗口。
7. 比较 Ready Count、Expected Count 和 Failed Count，运行结构与语义 Quality Gate。
8. 根据 Release Manifest 的兼容组切换对应 Alias，并记录 Operation ID。
9. 进入 OBSERVE，异常按上一份 Manifest 回切，稳定后将 Build 标记 COMPLETED。

推荐 Build 状态为：

```text
CREATED -> BACKFILLING -> CATCHING_UP -> VERIFYING -> SWITCHING -> OBSERVING -> COMPLETED
    |            |              |              |             |             |
    +------------+--------------+--------------+-------------+-------------+-> FAILED
```

每一步使用带前置状态的条件更新。`startAliasSwitch` 还要求 `ready_count = expected_count` 且 `failed_count = 0`，避免只改状态不检查覆盖。

## 5. Catalog Version 为什么是必要门禁

假设回填开始时 Catalog Watermark 对应 100 个 Source。构建到第 80 个时，用户上传第 101 个 Source；若仍按最初目标切 Alias，新索引在构建记录上可能 100/100 完整，对发布时资料集合却不完整。删除和更新也会产生同类问题。

当前 `SourceCatalogVersionService` 在资料集合改变时推进版本，切换前版本不同就拒绝并全量重试。它适合资料少且变化低的学校演示，但活跃 Workspace 可能一直重建不完。

推荐方案在开始时记录 Backfill Watermark，把水位后的增量通过 Change Log 补到新索引，再做最终短暂停顿校验。Change Log 必须包含 Source、Snapshot、Operation、Catalog Sequence 和幂等身份；Lag 长期不收敛时拒绝发布，但不丢弃已经完成的基线回填。当前全量重试只作为兼容路径。

## 6. Alias 切换能保证什么

Elasticsearch `updateAliases` 可以在一次 Cluster State 更新中移除旧 Alias 指向并添加新索引。推荐 Alias 按环境、索引族或租户桶管理，不按每个 Workspace 无限增加；查询由认证上下文强制加入 `workspace_id`，命中后再回 MySQL 校验 Ownership。QA 与 Note 只有属于同一兼容组时才在一个 Alias 请求中切换。

Alias 原子性只覆盖 Elasticsearch 内部，不覆盖 MySQL Build 状态。当前顺序是先切 Alias，再将 QA Build 和 Note Build 分别标记 COMPLETED。若 Alias 已成功，进程在第一个数据库 Completion 前退出，查询已经读新索引，MySQL 可能仍显示 SWITCHING 或后续被标记 FAILED。这是跨存储的结果未知窗口，不能声称发布记录与 Alias 强一致。

这里不能把 Alias 更新称为通用原生 CAS。Elasticsearch `updateAliases` 可以原子提交多条 Alias 动作，推荐请求同时删除 expected old index 上的 Alias 并添加 target index；在目标 ES 版本已经验证语义时，为删除动作启用 `must_exist`，让预期旧关联不存在时整次发布失败。发布服务再用数据库单写协调器或分区发布队列串行化同一索引族，提交前重新读取 Alias 当前指向。Redis Lease 只能减少并发，不能单独承担正确性，因为租约过期后的旧持有者仍可能调用 ES，而 ES 不认识它的 Fencing Token。最终由条件动作、Release Manifest、单调 Generation、读回校验和 Reconciler 共同收敛。

生产补全需要 Reconciler 读取 Alias 实际指向和 Build Target：Alias 已指向 Target 且覆盖摘要匹配时，幂等补写 COMPLETED；Alias 仍指向旧索引时，把 Build 标记失败或重新执行切换；QA 与 Note 指向不同 Generation 时立即阻止发布并回切到上一组 Release Manifest。Release Manifest 应保存两条物理索引、Schema、Embedding、Catalog Version 和前一版本。

## 7. 结构门禁与语义门禁

结构门禁回答“数据是否构建完整”：Source Ready Coverage、QA Projection Coverage、Note Projection Coverage、Expected/Ready/Failed Count、Provider 是否可用。它能发现漏索引、构建失败和依赖关闭，不能证明排序更好。

语义门禁回答“新策略是否值得发布”：

| 指标 | 计算 | 代表意义 |
| --- | --- | --- |
| Recall@K | `TopK 命中的 Gold Evidence / Gold Evidence` | 候选里有没有正确证据 |
| MRR | 首个正确 Evidence 排名倒数的平均 | 正确结果出现得够不够靠前 |
| NDCG@K | `DCG@K / IDCG@K` | 多级相关性下排序质量 |
| Citation Accuracy | 真正支持绑定 Claim 的 Citation / 已判定 Citation | 引用是否支撑对应结论 |
| Citation Completeness | 至少绑定一个有效 Citation 的应引用 Claim / 全部应引用 Claim | 回答是否漏掉证据责任 |
| Claim-Evidence Coverage | 被完整 Evidence 支持的原子 Claim / 全部可核验原子 Claim | 防止附了链接却只支持部分结论 |
| Refusal Recall | 正确拒答的应拒答 Case / 全部应拒答 Case | 是否漏掉必须拒答的问题；当前 `Refusal Accuracy` 字段兼容映射到此语义 |
| Refusal Precision | 正确拒答 Case / 全部被拒答 Case | 是否把可回答问题也拒掉 |
| Answer Coverage / Selective Accuracy | 非拒答 Case / 全部合格 Case；正确且有依据的回答 / 全部非拒答 Case | 防止靠扩大拒答范围换取表面正确率 |
| Scope Violation | 超出 Workspace 或允许 Snapshot 的证据数 | 安全不变量 |
| P95 Latency | 排序后的第 95 百分位运行时间 | 尾延迟代价 |

结构完整是语义评测的前提。若 Shadow Case 使用错 Snapshot 或跨 Workspace，Recall 再高也不能发布。

## 8. Shadow Comparison 和消融矩阵

`RetrievalShadowComparator` 要求 Shadow Case ID 与 Gold Set 完全一致，Evidence ID 唯一，Candidate Count 不小于返回结果数。它分别对 Baseline 与 Shadow 做 Benchmark Replay，再计算 TopK Overlap、Position Agreement、Top1 Changed、各质量指标 Delta、范围违规、P50/P95 和候选数量。

Quality Gate Policy 冻结 Dataset Version、Strategy Profile、Minimum Case Count 和每个指标阈值。绝对阈值防止两个很差的方案只比较相对提升，相对 Delta 防止新方案在关键指标回退。范围违规属于零容忍约束，延迟是最大阈值。

Quality Receipt 还要求固定的消融集合。QA 包含 Keyword Only、Vector Only、Hybrid RRF、Rerank 和完整 Evidence Budget；Note 包含 Metadata、Journal、Semantic、Relations、Rerank 与 Quota 等组合。完整消融能说明提升来自哪一层，也能发现 Rerank 提升 NDCG 却损害范围或延迟。

## 9. 当前 Quality Receipt 能证明到哪里

`RetrievalQualityReceiptService` 当前会校验 Plan 是否属于最终计划、Dataset/Report Version 是否存在、SHA-256 是否符合格式、Case Count 和错误计数是否合法、消融名称是否完整，以及每个指标是否在有效范围。Release Eligible 还要求 Passed、Case Count 大于零、范围/快照/引用归属/降级错误全为零。

它还不能独立证明报告真实执行过。接口接受 Owner 提交的 `passed`、指标和 `reportSha256`，当前没有从对象存储读取报告重算 SHA，也没有验证 CI 签名、脚本版本或 Gate Result 内容。因此它是审计收据的数据模型，不是防伪发布凭证。

生产闭环应由 CI 或受信评测服务生成报告，保存不可变 Report Artifact，服务端根据对象内容计算 SHA-256，验证 Dataset、Policy、Commit、Index Generation 和执行环境，最后用服务身份签名 Receipt。人工 Owner 可以发起评测或批准例外，不能直接填写一个 Passed 绕过门禁。

## 10. 当前 Release Gate 的真实边界

`RetrievalReleaseGateService` 会检查 Elasticsearch、Embedding、Rerank Provider，Source/QA/Note 覆盖，是否存在完整 Completed Build，最终策略是否产生 Degraded Run，以及 QA、Note 最新 Quality Receipt 是否 Release Eligible。

但 `rebuildWorkspace` 当前在结构覆盖完整后直接切 Alias，没有在切换前调用 `releaseGateService.evaluate`。也就是说，Release Gate 是可查询的发布检查，还不是 Alias 切换的强制前置条件。面试时可以讲“实现了门禁计算与收据模型，下一步将其接入 Prepare、Catch-up、Evaluate、Promote、Observe 五阶段发布”，不能讲“评测失败一定无法切索引”。

推荐流程是：

```text
PREPARE
  构建新索引，不切生产 Alias
CATCH_UP
  从 Catalog Watermark 追平增量变更
EVALUATE
  用新索引跑 Gold、Shadow、消融，生成受信 Receipt
PROMOTE
  校验 Catalog Version、Receipt、Provider 和 Degraded Guard
  由单写发布协调器串行化，确认 expected old 关联后原子切 Alias，写 Release Manifest
OBSERVE
  小流量或 Shadow 观察，异常回切上一 Manifest
```

## 11. Inspector 怎样用于定位

当前 Inspector 是分开的两组能力。Retrieval Management API 返回 Workspace 级 Coverage、Build 和 Projection Count，并提供 Rebuild、Release Gate 与 Quality Receipt；`RuntimeInspectorController` 返回某个 Answer 或 Research Run 的 Input Snapshot、Retrieval Config、Segment Summary Ref、Recent Message Ref、Memory Revision Ref 和 Replay Availability，另有 Memory Inspector。

定位一条错误回答时可以按下面的证据链：

1. Runtime Inspector 确认 Run 使用的 Retrieval Config、Source Scope 和 Snapshot Identity。
2. Evidence Bundle 确认召回了哪些 Entity、排名和降级标记。
3. Retrieval Status 检查对应 Workspace 的 QA/Note Coverage 和 Build Generation。
4. Shadow Report 判断该 Case 在 Baseline 与当前策略中的排名差异。
5. 若 Evidence 正确但回答错误，再查 Prompt、Context 和生成；若 Evidence 就错，继续查 Projection、Recall、Rerank 或 Scope Guard。

当前还没有一个页面把 Run、Evidence、Projection Build、Alias Generation 和 Quality Receipt 串成统一 Trace。目标 Inspector 应以 Run ID 为入口，展示“当时配置、命中证据、索引发布版本、Gold Case 相似项、降级原因”，并对敏感 Source 内容做权限和脱敏。

## 12. 删除、更新和 Current Snapshot

在线投影完成后，Coordinator 先确保 Alias 存在，将新 Snapshot 在目标索引标记为 Current，再由 Finalizer 在 MySQL 中提交 READY、更新 Source Catalog 和 Task；旧 Snapshot 随后在 ES 标记为 Not Current。异常发生在 Finalize 之前时，会尽力将新 Snapshot 反激活。

这仍是跨存储最终一致。查询需要同时过滤 Workspace、Source Ownership 和 `is_current_snapshot`，结果进入 Prompt 前再次做 Evidence Scope/Ownership Guard。Source 删除先在 MySQL 提交删除事实，再异步撤回 ES 投影；ES 短暂残留不能因此重新成为合法 Evidence。

对账任务应比较 Source 最新 Snapshot、MySQL READY Projection、ES Current 文档和 Alias Generation。任何一层不一致都生成可修复清单，而不是让 ES 反向改写 Source 真源。

## 13. 学校场景演练

课程资料从旧版 Embedding 升级到新版模型，同时修改 Chunk Schema：

1. 固定当前课程 Workspace 的 Source Catalog Version，选择所有最新已解析 Snapshot。
2. 创建带新 Schema、Embedding Version 和 Generation 的 QA/Note 物理索引。
3. 回填期间旧 Alias 继续服务，学生不会读到半成品。
4. 模拟中途上传新资料，Change Log 记录水位后的增量，新索引追平后再进入发布门禁。
5. 在新物理索引上运行课程 Gold Set 和完整消融，生成 Shadow Report。
6. 结构覆盖、范围违规、Citation Ownership 和质量阈值通过后，按 Release Manifest 的兼容组 Promote Alias。
7. 观察 Degraded Run、P95 和 Top1 Changed，异常则回切上一 Release Manifest。

第 5 到 7 步中的强制 Promote 与签名 Receipt 属于目标发布流程。当前源码能分别执行重建、Quality Gate 计算和 Release Gate 查询，尚未把它们锁成一个不可绕过的命令。

## 14. 指标和计算方式

| 指标 | 公式 | 用途 |
| --- | --- | --- |
| Projection Coverage | `READY Entity / Expected Entity` | 判断漏构建 |
| Source Ready Coverage | `QA 与 Note 都完整的 Source / 当前 Source` | 判断源级完整性 |
| Rebuild Throughput | `成功投影 Entity / 分钟` | 估算重建窗口 |
| Catalog Drift Abort Rate | `因 Catalog 变化终止 Build / Build` | 高频更新是否阻碍全量重建 |
| Alias Unknown Window | Alias 成功到 Build/Manifest 确认的时长 | 跨存储结果未知风险 |
| Shadow TopK Overlap | `交集大小 / 并集大小` | 新旧排名变化幅度 |
| Top1 Change Rate | `Top1 变化 Case / Case` | 需要重点人工抽样的变化 |
| Quality Gate Pass Rate | `通过 Gate 的 Candidate Release / 候选 Release` | 发布质量稳定性 |
| Receipt Freshness | 当前时间减最新合格 Receipt 时间 | 门禁证据是否过期 |
| Degraded Final Plan Rate | `降级完成 Run / 最终策略完成 Run` | Provider 或投影异常是否被静默接受 |
| Rollback Time | 触发回滚到 Alias 恢复上一 Manifest | 发布恢复能力 |
| Inspector Localization Time | 错误发现到定位责任层的时间 | 可解释与排障效率 |

Rebuild ETA 可用 `remainingEntities / recentThroughput` 粗估，但应按 QA 与 Note 分开，并把 Embedding Provider 限流、失败重试和 Catalog Drift 计入。平均吞吐不能代表最后少量慢 Source 的尾部时间。

## 15. 4 到 5 分钟标准主回答

场景化 RAG 做到后面，算法只是一个部分。Embedding 模型、维度、Chunk Schema 和 Elasticsearch Mapping 都会变化，如果直接原地更新索引，回填期间用户会读到一部分新文档和一部分旧文档，出现问题也很难回滚。所以我把 Source Snapshot 保留为不可变真源，把检索看成可重建投影。Elasticsearch 每次升级创建新的 Generation Index，生产查询走环境级或租户分桶 Alias，并强制下推 Workspace Scope，不为每个 Workspace 无限增加 Alias。

一次重建先记录 Catalog Watermark，选每个 Source 最新的已解析 Snapshot，计算 QA Chunk 和 Note Source 的期望数量。QA 与 Note 拆成两类投影，因为一个面向 Passage Ranking，一个面向 Source Recall。基线回填后消费 Watermark 之后的 Change Log，追平上传、更新和删除，再比较 Ready、Expected、Failed Count 和增量 Lag。Release Manifest 声明两类投影是否必须同代，只有存在 Schema、Embedding 或 Prompt 兼容约束时才成组切换。

Catalog Watermark 解决回填期间资料集合变化。比如开始时一百个 Source，构建到一半又上传一个，100/100 对旧集合完整，对发布时集合并不完整。当前全量重试适合学校小规模实现；推荐方案保留已完成基线，通过 Change Log 追赶第 101 个 Source，只有增量长期无法收敛才拒绝发布。

覆盖完整只能说明索引没漏，不能说明检索变好。离线侧我把 Gold Set、Shadow Snapshot、Strategy Profile 和 Policy 都版本化。对相同 Case 比较 Baseline 与新策略，报告 Recall、MRR、NDCG、Citation Accuracy、Citation Completeness、Claim-Evidence Coverage、Refusal Recall/Precision、Answer Coverage、Selective Accuracy、Scope Violation、P95、TopK Overlap 和 Top1 Change。Quality Receipt 要包含完整消融矩阵，避免只报最终组合，看不出提升来自 Hybrid、Rerank 还是 Evidence Budget。

这里我会主动说明两个当前边界。第一，重建路径现在通过结构覆盖后就切 Alias，独立的 Release Gate 会检查 Provider、Coverage、Completed Build、Degraded Run 和最新合格 Receipt，但还没有硬接到切换前。第二，Receipt 服务会校验字段、错误计数、指标范围、消融集合和 SHA 格式，却没有从不可变报告重算 SHA 或验证 CI 签名，Owner 仍可以提交 Passed。它现在是收据模型和审计接口，还不是不可伪造的生产门禁。下一步会拆 Prepare、Evaluate、Promote，只有受信评测服务生成的 Receipt 才能 Promote。

还有一个跨存储窗口：Alias 切换在 Elasticsearch 完成后，MySQL Build 才标记 COMPLETED。进程在中间退出时，查询可能已经读新索引，数据库仍显示 SWITCHING。生产补全需要 Release Manifest 和 Reconciler，以 Alias 实际指向为准幂等补写或回切，不能靠重复重建猜结果。

排障时从 Run ID 向后追。Runtime Inspector 查看当次 Input Snapshot、Retrieval Config、Source Scope、Summary 和 Memory 引用；Evidence Bundle 查看实际证据与降级；Retrieval Management 查看 Coverage、Build 和 Projection；Shadow Report 查看这个 Case 的排名变化。证据正确而生成错误，就查 Prompt 和 Context；证据本身错误，再查 Projection、Recall、Rerank 和 Scope Guard。

当前源码能证明版本化物理索引、双投影、Catalog Gate、覆盖计数、原子多 Alias 请求、Shadow Comparator、Quality Gate、Receipt 和 Inspector。共享或租户分桶 Alias、Change Log Catch-up、强制语义门禁、签名 Receipt、兼容组 Release Manifest 和跨存储 Reconcile 是面试推荐方案，仍需迁移实现和规模验证。

## 16. 重点知识点：索引重建为什么需要 Prepare、Catch-up、Evaluate、Promote、Observe

### 3 分钟回答

原地改索引把构建和发布混成一个动作。只要回填持续一段时间，查询就可能读到不同版本；失败后也没有明确的上一版可以恢复。版本化物理索引把写入目标与生产读取分开，Alias 只在候选已经完成时切换。

Prepare 阶段冻结 Catalog Watermark、Schema、Embedding、Mapping 和目标 Generation，只构建候选索引。Catch-up 阶段消费 Watermark 后的 Change Log，直到增量 Lag 进入发布窗口。Evaluate 阶段强制所有 Shadow Case 查询候选物理索引，结果绑定 Dataset、Policy、Commit、Generation 和执行环境。Promote 检查 Catch-up 水位、Receipt、Provider 和范围违规，并由 Release Manifest 描述 QA/Note 的目标及兼容组。

Alias 更新本身只对 ES 原子，业务发布还要处理 MySQL。Promote 需要稳定 Operation ID，先写 PREPARED Manifest，再由单写发布协调器串行化同一索引族，重新读取并确认当前指向仍是 expected old generation，然后执行一个“删除 expected old 关联并添加 target”的 `updateAliases` 请求。目标 ES 版本支持且已验证时，删除动作使用 `must_exist` 阻止旧关联已经变化后的覆盖。成功后写 PROMOTED。这里不是通用版本 CAS，单纯“先读后写”也有竞态，因此不能只依赖 Redis Lease。HTTP 超时后先读 Alias 和 Manifest 判断，不能盲目再切。Reconciler 定期修复 Alias 与 Manifest 不一致。Rollback 使用上一 Manifest 的两条物理索引，不能只回 QA 留 Note 在新版本。

候选索引不能切完马上删除旧索引。保留时间至少覆盖观察窗口、回滚时限和合规要求，并限制最多保留 Generation 数。新索引接收在线增量时，还要明确双写起点和 Watermark，否则离线评测通过的候选在 Promote 时已经落后于当前 Source。

## 17. 二阶追问

### Alias 已切成功，但接口超时，能不能直接重试

不能先假定失败。使用同一个 Operation ID 查询 Alias 当前指向、QA/Note Generation 和 Manifest 状态。已经指向目标时补写数据库完成；仍指向旧版本时才重试 Promote；两条 Alias 分裂时进入故障处理并回切完整上一 Manifest。无条件重试虽然 ES Alias 操作大多可重复，仍可能覆盖其间发生的合法新发布。

### 为什么不在回填期间禁止上传

停写实现简单，但学校课程更新和资料上传不应因索引维护长期不可用。推荐从构建开始记录 Catalog Watermark，写入继续进入 Change Log，基线完成后追增量。只有严格维护窗口或数据很小的后台系统，停写或检测漂移后全量重试才可能是更低成本选择。

### 质量指标都提升，为什么仍可能拒绝发布

范围违规、引用归属错误、Current Snapshot 错误属于安全约束，不能用平均 Recall 提升抵消。还要看关键切片、Refusal、P95、成本和 Top1 变化。总体 NDCG 提升可能来自简单 Case，某门课程或中文长文切片反而明显回退。发布策略应包含绝对阈值、相对 Delta、零容忍约束和人工抽样。

### Latest Receipt 会不会被旧数据误用

会，若只按 Plan 取最新 Receipt，却不绑定当前 Generation、Commit、Policy 和过期时间。当前 Receipt 有 Dataset、Report Version 和 SHA，但 Release Gate 还没有校验它是否针对正在发布的物理索引。目标 Receipt 必须绑定 Release Candidate Identity，并设置 Freshness；任何 Embedding、Chunk、Mapping、Prompt 或 Rerank 版本变化都使旧 Receipt 失效。

### Owner 伪造 Passed Receipt 怎么防

评测服务从受控任务读取不可变 Gold、候选索引和 Policy，生成报告 Artifact；服务端自己计算内容 SHA，记录代码提交、容器摘要和执行身份，再签名 Receipt。Promote 只接受受信 Audience 和签名。人工例外走独立 Override，写原因、审批人、到期时间和风险，不能修改原评测结果。

### Catalog Version 高频变化导致永远重建不完怎么办

全量构建记录开始 Watermark，Source 变更继续写 Change Log。全量完成后消费 Watermark 之后的增量，直到 Lag 低于阈值；短暂进入 Final Catch-up，冻结 Alias Promote 而非冻结全部上传；Promote 后正常增量只写新 Alias。还要限制追赶次数，超过阈值转为持续双写或安排维护窗口。

### 为什么 Degraded Run 也会阻止 Release

最终计划若经常因为 Embedding 或 Rerank 不可用而走降级，离线合格 Receipt 不能代表用户实际路径。Release Gate 统计已完成但 Evidence Bundle 标记 Degraded 的最终策略 Run，提示当前 Provider 或配置不稳定。当前实现按 JSON 文本模式查询降级标志，生产上应改成结构化列或生成列索引，避免 JSON 格式差异和全表扫描。

### Inspector 会不会泄露其他 Workspace 的证据

Inspector 必须先按 Run 反查真实 Workspace，再校验成员权限，不能信任 URL。展示 Evidence 时按当前权限再次过滤，敏感正文可只显示 Snapshot ID、Locator 和摘要。历史 Run 即使当时合法，成员或资料已撤销后也要应用 Replay Redaction。运维跨租户查看需要独立权限和审计。

### 旧索引什么时候删除

至少等观察窗口结束、上一 Release 不再需要快速回滚、所有活跃 Run 的 Snapshot/Generation 引用可解释，并确认没有 Legal Hold。删除前检查 Alias 不再指向、Manifest 不再标记可回滚、对象和 MySQL Projection 可重建。物理索引数量还应有上限和容量告警，不能无限保留。

## 18. 项目八股映射

| 面试知识点 | 项目落点 | 继续追问 |
| --- | --- | --- |
| Elasticsearch | Mapping、Dense Vector、Alias、物理索引 | Alias 原子性边界 |
| 数据迁移 | Backfill、Watermark、双写、切换 | 如何处理迁移期间新增数据 |
| 状态机 | Index Build 条件迁移 | FAILED 后如何重试 |
| 分布式一致性 | ES Alias 与 MySQL Manifest | 结果未知怎样对账 |
| 乐观并发 | Catalog Version Gate | 为什么不锁住全部 Source |
| 信息检索评测 | Recall、MRR、NDCG、Overlap | 指标各自遗漏什么 |
| 实验设计 | Gold、Shadow、消融、Policy | 数据集污染怎样避免 |
| 发布工程 | Prepare、Evaluate、Promote、Rollback | Receipt 如何绑定候选版本 |
| 安全 | Scope Violation、Ownership Guard、Inspector ACL | 平均质量为何不能抵消越权 |
| 可观测性 | Coverage、Degraded、Receipt Freshness、Localization | 怎样定位责任层 |

## 19. 源码与测试导航

| 主题 | 入口 |
| --- | --- |
| 管理接口 | `RetrievalManagementController` |
| 全量重建 | `RetrievalBackfillService`、`RetrievalIndexBuildRepository` |
| Alias 与 Mapping | `RetrievalIndexManager`、`RetrievalIndexNames` |
| 在线投影 | `SourceRetrievalProjectionService`、`SourceRetrievalProjectionCoordinator` |
| 最终提交 | `SourceRetrievalProjectionFinalizer`、`RetrievalProjectionRepository` |
| Release Gate | `RetrievalReleaseGateService` |
| Quality Receipt | `RetrievalQualityReceiptService` |
| Shadow 对比 | `RetrievalShadowComparator`、`RetrievalExecutionShadowExporter` |
| 离线门禁 | `RetrievalQualityGate`、`RetrievalQualityGatePolicy` |
| 真实 Answer 导出 | `QaAnswerRunShadowExportService`、`QaAnswerRunShadowArtifactValidator` |
| Runtime Inspector | `RuntimeInspectorController`、`RunInputSnapshotService` |
| 关键测试 | `RetrievalBackfillServiceCatalogGateTest`、`RetrievalReleaseGateServiceTest`、`RetrievalQualityGateTest`、`RetrievalShadowComparatorTest` |

## 20. 当前边界与面试口径

`[当前实现]` 可以讲版本化物理索引、QA/Note 双投影、Catalog Version Gate、Build 覆盖状态、一次多 Alias 更新、Shadow Comparator、Quality Gate、消融 Receipt、Release Gate 查询、Context/Memory Inspector 和测试入口。

`[当前缺口]` 语义 Release Gate 没有强制接入 Alias 切换，Receipt 没有从报告重算 SHA 或验签，Receipt 未严格绑定当前候选 Generation，Alias 与 MySQL Build 缺少 Reconciler，Degraded Run 使用 JSON 文本匹配，统一 Run 到 Release Trace 尚未形成。

`[目标设计]` 可以讲 Prepare/Evaluate/Promote、受信评测服务、签名 Receipt、Release Manifest、单写发布协调器、expected old 条件动作、增量追赶、统一 Inspector 和自动回滚。

`[生产待验证]` 包括真实索引规模、重建窗口、ES Cluster State 压力、Embedding 限额、生产 Gold 代表性、长期质量漂移、回滚时间和多 Workspace 隔离演练。

## 21. 面试前自检

1. 能解释 Source Snapshot、MySQL Projection、物理索引和 Alias 的真源关系。
2. 能讲 QA/Note 为什么拆投影，以及什么兼容条件下才一起切换。
3. 能构造 Catalog Drift 反例，并说明 Watermark 与 Change Log 如何增量追赶。
4. 能区分结构覆盖门禁和语义质量门禁。
5. 能解释 Recall、MRR、NDCG、Citation、Refusal 和 Scope Violation。
6. 能主动说出 Release Gate、Receipt 和 Alias 当前没有形成强制闭环。
7. 能回答 Alias 已切但数据库未知时如何 Reconcile。
8. 能从错误 Answer 追到 Snapshot、Evidence、Build、Generation 和 Shadow Report。

## 22. 重点知识点：Alias 切换结果未知与发布对账

### 3 分钟回答

Elasticsearch Alias 更新可以在一个请求里原子切换多条 Alias，但它与 MySQL Build 状态不是同一个事务。Promote 请求发送后，ES 可能已经切换，Java 进程却在收到响应前退出。MySQL 仍是 `SWITCHING`，查询却已经走新索引。若恢复器把超时直接当失败并重新执行，期间可能已经有另一版合法发布，旧操作就会覆盖新版本。

解决思路是把发布建模为有身份的操作。Prepare 创建不可变 Release Manifest，记录 Operation ID、Release Scope、Catalog Watermark、兼容组中的目标物理索引、上一 Manifest、Quality Receipt 和期望 Alias 指向。推荐架构的 Release Scope 是环境、索引族或租户桶；当前 Workspace 重建可把 Workspace 作为迁移期 Scope，不能让目标 Manifest 永久绑定单个 Workspace。Promote 由同一 Release Scope 的单写发布协调器执行，再读取当前 Alias，确认仍等于 Manifest 的预期旧版本。QA 与 Note 只有声明为同一个兼容组时才进入同一次原子 Alias 请求，互不要求同代时分别发布。请求同时删除 expected old 关联和添加 target；目标 ES 版本验证通过时，删除动作使用 `must_exist`。这不是通用版本 CAS，所以响应后仍要读回。响应成功后把 Manifest 标记为 PROMOTED；响应未知时不创建新操作，而是用同一 Operation ID 进入 RECONCILING。

Reconciler 以外部真实状态为证据。两条 Alias 都指向目标，说明 ES 已成功，只需幂等补写 MySQL；两条都指向旧版本，说明尚未切换，可以在 Catalog、Receipt 和预期旧指向仍成立时重试；一条新一条旧属于分裂状态，立即停止后续发布，根据完整上一 Manifest 回切或人工处理；Alias 已指向更新 Generation，说明当前操作被后来发布取代，只能标记 Superseded，不能回切别人已经发布的版本。

Operation ID 防止同一发布被当成多个动作，Expected Alias 处理并发发布，Manifest 保证同一兼容组作为一个版本单元，Receipt 证明候选通过了哪些门禁。它们仍不能让 MySQL 与 ES 变成强事务，只是把结果未知变成可查询、可重试和可对账的状态。旧索引需要保留到观察窗口结束，避免 Reconcile 或 Rollback 找不到上一版本。

验证至少注入四个崩溃点：写 Manifest 后、调用 ES 前；ES 执行前连接失败；ES 成功后响应丢失；Alias 成功后 MySQL 提交前退出。再加入并发发布、协调器换主、旧持有者晚到和 QA/Note 分裂故障，断言条件动作拒绝已经变化的 expected old 关联，Reconciler 不会覆盖更高 Generation，并能恢复完整双投影。当前一次多 Alias 更新已经存在，统一 Manifest、单写协调器、条件动作和 Reconciler 仍是 `[目标设计]`。

### 二阶追问：为什么 Alias API 原子，还会出现 QA 和 Note 分裂

同一次正确的多 Alias 请求在 ES 内部不会只切一半。分裂可能来自历史版本曾分别切换、人工操作、两次不同请求、恢复逻辑错误或集群外的管理脚本。因此 Reconciler 仍要把双 Alias 当成一个发布不变量，不能因 API 理论原子就省掉读回检查。

### 二阶追问：回滚为什么也需要新 Operation ID

回滚是一次新的业务发布，目标是上一 Manifest，但发生时间、审批和当前预期指向都不同。复用旧 Promote ID 会混淆审计和并发仲裁。Rollback Manifest 应引用被回滚版本和原因，先验证当前 Alias 仍指向故障版本，再原子切回两条索引。
