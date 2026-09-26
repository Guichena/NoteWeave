# MySQL 索引、事务与 SQL 专项

> 阅读说明：第 1 到 13 节是高频追问速查，第 14 到 19 节是单主题长回答，第 21 节把旧速查内容组合成 3 到 5 分钟母题。准备时先练母题，再用前面的短节定位具体术语。

## 1. B+Tree 为什么适合数据库索引

B+Tree 是多路平衡树，非叶节点保存导航键，叶节点保存索引记录并按顺序连接。高扇出让树高较低，适合磁盘页读取；叶节点有序又支持范围扫描。InnoDB 聚簇索引的叶节点保存整行，二级索引叶节点保存二级 Key 和主键。

二级索引查询若需要索引中没有的列，要根据主键回到聚簇索引，称为回表。查询需要的列都在索引中时可以使用覆盖索引，减少随机 I/O，但索引列越多，写放大、空间和维护成本越高。

## 2. 联合索引与最左前缀

联合索引 `(a,b,c)` 按 a、b、c 排序，通常支持 a、`a+b`、`a+b+c` 的前缀，以及 a 等值后的 b 范围。跳过 a 直接按 b 查询通常不能高效使用完整索引。范围条件后的列可能不能继续用于定位，但可能用于 Index Condition Pushdown 或覆盖。

NoteWeave 的 Outbox 索引：

```sql
create index idx_task_outbox_dispatch
on task_outbox(topic, status, next_attempt_at, created_at);
```

Dispatcher 按 Topic、Status、到期时间和创建时间扫描，这个顺序匹配等值过滤加范围/排序。若查询不带 Topic，索引收益会明显下降。真实判断要看 `EXPLAIN ANALYZE`，不能只凭索引名字。

## 3. EXPLAIN 看什么

重点看访问类型、可能索引、实际索引、估算行数、过滤比例和 Extra。常见访问从好到差包括 const、ref、range、index、ALL，但不能机械排名，扫描小表时 ALL 可能合理。

`Using filesort` 表示排序不能直接由索引顺序完成，不一定写磁盘；`Using temporary` 表示需要临时结果；`Using index` 常表示覆盖索引；估算行数与实际差异大可能是统计信息或数据倾斜。MySQL 8 的 `EXPLAIN ANALYZE` 能看到实际时间、Loops 和 Rows，更适合验证优化。

## 4. ACID 与隔离级别

原子性依赖 Undo 与事务管理，一致性由数据库约束和业务不变量共同保证，隔离性由锁与 MVCC 实现，持久性依赖 Redo、刷盘和复制策略。ACID 不自动保证跨 Kafka、MinIO 和 ES 的原子性。

隔离级别：Read Uncommitted 允许脏读；Read Committed 每次一致性读创建新 Read View，可能不可重复读；Repeatable Read 通常在事务内复用 Read View，并配合 Next-key Lock 处理当前读；Serializable 进一步限制并发。InnoDB 默认 Repeatable Read，但具体行为还取决于快照读、当前读与 SQL 形态。

## 5. MVCC、Undo 与 Read View

InnoDB 行版本包含事务身份和回滚指针。快照读根据 Read View 判断哪个版本可见，通过 Undo 找到旧版本。MVCC 减少读写互斥，但长事务会阻止旧版本清理，增加 Undo 与存储压力。

`select ... for update` 是当前读，读取最新已提交版本并加锁，不走普通快照语义。Outbox Claim 和任务状态迁移需要当前读或条件更新，因为它们要竞争执行权，不只是展示历史一致视图。

## 6. 行锁、间隙锁与 Next-key Lock

Record Lock 锁索引记录，Gap Lock 锁索引间隙，Next-key Lock 是两者组合。没有合适索引时，扫描并加锁的范围可能远大于预期。唯一索引等值命中通常能缩小为记录锁，范围查询在 Repeatable Read 下可能锁住区间。

因此 Dispatcher 的 Claim Query 必须有匹配索引、小批量和短事务。事务内执行 Kafka 调用会让锁持有时间变成网络时长，扩大阻塞和死锁概率。

## 7. 死锁如何处理

死锁来自事务以不同顺序等待彼此资源。数据库会选择一个事务回滚。减少死锁的方法包括统一锁顺序、缩短事务、缩小锁范围、建立合适索引和避免一次更新过多行。

应用必须把 Deadlock/Lock Timeout 当作有限可重试错误，重试整个事务而不是从中间语句继续。记录 Deadlock Graph、SQL、索引和事务身份，不能只增加超时。

NoteWeave 的 Source Parse Finalizer 修复曾先锁 Task，再完成或取消，统一与其他终态路径的顺序，降低并发完成和死信终态化产生 Split-brain 的风险。

## 8. 幂等为什么需要唯一约束

“先查有没有，再插入”在并发下会有两个线程同时看到不存在。唯一约束把竞争交给数据库：

```text
request idempotency key -> unique constraint
answer assistant_request_id -> unique constraint
snapshot + chunk_no -> unique constraint
callback receipt key -> unique constraint
```

插入冲突后要读取原结果并比较 Payload Digest。相同 Key、相同 Payload 返回原 Receipt；相同 Key、不同 Payload 是冲突，不能默默复用。

## 9. 乐观锁与悲观锁怎么选

乐观锁适合冲突较少、计算发生在事务外的场景，常用 `where version=?`；悲观锁适合必须读取最新状态并立即做短更新的高价值临界区。乐观锁冲突后需要重读或失败，悲观锁会阻塞并可能死锁。

项目大量使用条件更新与 Fencing，避免长时间持锁。对 Source/Task 并发终态化这类必须统一顺序的路径，会使用 `for update` 锁定关键行。

## 10. N+1 与批量 Hydration

N+1 指先查询一批对象，再为每个对象单独查关联数据，SQL 次数随 N 增长。RAG TopK 增大时，如果每个 Passage 都查一次 Snapshot/Source Ownership，会把检索延迟转移到数据库。

项目对 Passage、Knowledge Version 做批量 Hydration，测试覆盖 100 个 Hit 仍保持常数级 Ownership Query。批量查询要控制 IN 列表大小，并保持结果按 ID 映射，不能依赖数据库返回顺序。

## 11. 分页

`limit offset,size` 在深分页时需要扫描并丢弃前面的记录。Seek Pagination 使用稳定排序键：

```sql
where (created_at, id) < (?, ?)
order by created_at desc, id desc
limit ?;
```

排序键必须唯一稳定，时间相同用 ID 打破平局。后台全表扫描任务更适合按主键或状态索引游标推进，避免 Offset 随数据变化跳行。

## 12. 数据增长后的演进

先通过索引、批量、归档和冷热数据解决问题，再考虑读写分离、分区或分库分表。读写分离会引入复制延迟，刚写后读需要主库、版本或会话一致性策略。分库分表还会破坏跨分片事务、全局唯一约束、分页和聚合，不应只因表行数听起来大就引入。

Outbox 已完成记录、历史 Trace 和旧 Snapshot 可以按审计与恢复需求归档。ES 是可重建投影，扩展策略与 MySQL 真源不同。

## 13. 面试中的索引设计回答

先给查询模式，包括等值条件、范围、排序、返回列和频率；再设计联合索引顺序；随后说明写放大和空间代价；最后用 `EXPLAIN ANALYZE`、慢查询和真实分布验证。不要背“区分度高的列一定放前面”，查询前缀、范围和排序同样决定顺序。

## 14. Redo Log、Undo Log 与 Binlog

Redo Log 是 InnoDB 的物理或物理逻辑重做记录，用于崩溃恢复。修改数据页时先在 Buffer Pool 中更新并写 Redo，满足持久化策略后事务即可提交，脏页可以稍后刷盘，这就是 WAL。Undo Log 保存事务修改前的逻辑版本，用于回滚和 MVCC 一致性读；长事务会阻止旧版本清理，使 Undo 膨胀。Binlog 位于 MySQL Server 层，记录逻辑变更或行变更，用于复制、审计和时间点恢复。

事务提交需要协调 Redo 与 Binlog，MySQL 通过两阶段提交降低“Redo 有而 Binlog 无”或相反导致的数据与复制不一致。Prepare 后崩溃时，恢复过程会依据 Binlog 是否完整决定提交或回滚。这里的两阶段提交是单机内部日志协调，不等于业务跨 MySQL、Kafka、MinIO 的分布式事务。Outbox 仍然需要本地事务保存业务状态与发布意图，再由 Dispatcher 异步投递。

面试官继续追问时，应能区分 `innodb_flush_log_at_trx_commit` 与 `sync_binlog` 对持久性和吞吐的影响，并说明复制不自动等于备份。误删数据会随 Binlog 复制到其他节点，恢复目标还需要完整备份、连续日志和演练数据支撑。NoteWeave 当前文档以迁移与测试证据为准，不虚构恢复指标。

Undo 还会引出 Purge 与长事务问题。活跃 Read View 需要旧版本时，Purge 不能删除相应 Undo，History List 可能持续增长。排查应查看长事务、事务开始时间和业务调用栈，避免只清理表空间。面试官问 Redo 是否保证不丢时，要把刷盘策略、操作系统缓存、磁盘故障和双日志提交条件一起说明。

## 15. Buffer Pool、脏页与数据库为何会抖动

InnoDB 以页为单位读写，Buffer Pool 缓存数据页和索引页，查询命中内存可以避免随机磁盘 I/O。修改先产生 Redo 并把内存页标记为脏页，后台线程再刷盘。Buffer Pool 命中率高通常是好事，但它不能单独证明查询快，扫描大量无用页也可能获得很高命中率。要同时观察读取页数、查询 P95、磁盘 IOPS、脏页比例和检查点压力。

当 Redo 空间紧张、脏页积累过多或突发写入超过刷盘能力时，前台事务会被迫参与刷脏或等待，延迟出现尖峰。大范围 Update、无索引删除、批量回填和索引构建都可能制造这种压力。Change Buffer 能延迟部分非唯一二级索引页的修改合并，但唯一索引需要立即判断冲突，不能同样处理。Adaptive Hash Index 是否有益取决于访问模式，不应当作固定优化项。

NoteWeave 的 Snapshot 回填、Outbox 清理和历史记录归档应小批量提交，并观察连接池等待、Redo 生成、脏页和复制延迟。批次太小增加提交开销，太大则形成长事务、持锁时间和 Undo 压力。合理批量必须以真实行宽、索引数和机器 I/O 压测，不能从一个固定行数模板得出。若面试官问“数据库 CPU 不高为什么接口慢”，还要检查锁等待、磁盘 Flush、连接池 Pending 和网络，而不是继续加索引。

Buffer Pool 的淘汰并非简单 LRU，InnoDB 会区分新旧区域，降低一次全表扫描把热点页全部挤出的风险。预热、重启和数据增长都会改变命中分布。容量评估要看活跃工作集是否能放入内存，而不是拿表总大小直接比较 Buffer Pool。只为提升命中率盲目增大内存，还会挤压操作系统和其他进程。

## 16. 主从复制、读写分离与一致性

主库提交后产生 Binlog，从库 I/O 线程拉取日志并写 Relay Log，再由应用线程重放。异步复制允许主库提交时不等待从库，因此存在复制延迟；半同步复制只保证至少一个从库收到日志，通常不保证已经应用并可读。并行复制可以提高回放吞吐，但热点事务、DDL 或依赖关系仍会形成 Lag。

读写分离适合可接受短暂陈旧的查询。用户刚更新 ACL、文档状态或任务终态后立即从延迟从库读取，可能看到旧权限或旧状态。解决方法包括写后一定时间读主、携带版本等待从库追平、关键路径固定读主，或让接口明确接受最终一致。安全校验不应依赖可能落后的副本，否则撤权窗口会扩大。

NoteWeave 当前主要讨论单 MySQL 真源与外部投影，不能把未来的读副本当成已落地能力。若演进到读写分离，Source Catalog 这类允许短时陈旧且有版本化缓存的读可以先迁移；Refresh Token Rotation、Outbox Claim、ACL 撤权和任务终态仍应落在主库。继续追问时要谈复制拓扑、故障切换、脑裂防护、GTID、延迟监控和数据校验，也要承认读副本会增加运维与一致性复杂度。

故障切换后还可能出现旧主恢复并接受写入的脑裂，需要代理、编排或外部 Fencing 保证只有新主可写。GTID 能帮助识别事务集合与切换位置，但不自动判断业务数据正确。读副本出现 Lag 时，可以摘除、回主或按版本等待，不能继续返回过期 ACL。面试回答应把高可用、读扩展和备份分成三件事。

## 17. 连接池、长事务与数据库容量

连接池复用数据库连接，避免每个请求重复建连和认证，但池越大不代表吞吐越高。数据库 CPU、锁、磁盘和线程调度都有上限，过多连接会增加上下文切换并放大争用。Little 定律可用于粗估连接需求：平均每秒 100 个请求，每个请求占用连接 50 ms，平均在用连接约为 5；还需为长尾和后台任务留余量。这个估算不能替代压测，因为一个请求可能多次获取连接，事务时间也受锁等待影响。

获取连接超时、查询超时、事务超时和接口 Deadline 要形成递减预算。若接口只允许 2 秒，连接池等待 3 秒没有意义。项目 Hikari 最大连接数 20 是配置保护值，不是“系统只能 20 并发”或“可以支撑 400 QPS”的实测结论。Answer、Artifact Dispatcher、Research 和回调都可能共享数据库，容量评估必须包含后台工作负载。

长事务会长期占连接、持锁并保留 MVCC 旧版本。常见诱因是事务中执行 HTTP、Kafka、MinIO 或模型调用，或者一次扫描更新过多记录。NoteWeave 的 Claim 模式把短事务抢占与事务外执行分开，再通过 Token 或版本条件确认，代价是必须处理重复和旧 Owner 晚到。面试官问连接池打满怎么排查时，应从 Pending、Active、获取耗时定位，再查慢 SQL、锁等待、事务年龄和调用栈，不能直接把池调大。

连接池还要检测失效连接和泄漏，但 Leak Detection 阈值过短会产生噪声。连接生命周期应短于数据库或网络设备强制断开时间，验证查询会增加开销。后台 Dispatcher 与在线请求最好有隔离或明确配额，避免积压恢复占满全部连接。池大小变更必须同时观察数据库并发、P99 和超时，而不是只看 Pending 降低。

## 18. 分区、分库分表与全局问题

MySQL 分区在同一个逻辑表内按规则把数据放入不同分区，可能帮助按时间裁剪和快速清理，但查询不包含分区键时仍会扫描多个分区，唯一索引也受到分区键约束。它没有消除单实例 CPU、连接和故障域限制。分库分表把数据放到多个实例，扩展容量和写吞吐，却引入路由、跨分片事务、全局唯一 ID、排序分页、聚合、扩容迁移和热点分片问题。

按 Workspace 分片能把大部分租户内操作局部化，但超大租户会形成热点，跨 Workspace 管理查询变难。按 Hash 分布较均匀，却不利于按租户整体迁移。全局 ID 可用数据库段、雪花算法或中心服务，各自需要处理时钟、趋势写热点和可用性。广播表、冗余字段和异步汇总能减少跨分片 Join，但会增加一致性和对账成本。

NoteWeave 目前没有证据证明需要分库分表。应先看数据量、增长率、工作集、慢查询来源、单实例资源和租户偏斜，优先采用合适索引、批量查询、归档、可重建投影和读扩展。只有容量测试表明单库资源接近上限，且业务访问能被稳定分片键约束时才进入分片设计。面试时主动解释“不现在做”的依据，通常比直接画分片架构更有说服力。

分片后的扩容通常需要双读、双写或迁移状态机。迁移期间要避免同一 Key 在新旧分片同时被修改，可按租户冻结、版本路由或变更日志追平。全局分页若从每个分片取 TopK 再归并，深页成本会迅速增加。面试官问“分片键怎么选”时，应给出访问局部性、均匀性、可迁移性和合规删除四个维度。

## 19. DDL、Online DDL 与 Expand/Contract

DDL 可能持有 Metadata Lock。即使某种 ALTER 支持 Inplace 或 Instant，也可能在开始或提交阶段等待正在运行的事务，长查询会让变更排队并阻塞后续访问。上线前要确认 MySQL 版本、具体 ALTER 算法、锁模式、表大小、磁盘余量和复制延迟，不能把“Online DDL”理解为完全无锁。大表变更还可使用影子表工具，但触发器、外键、额外写放大和切换锁需要评估。

应用与数据库演进采用 Expand/Contract：先增加可空列、新表或新枚举，使旧代码仍能运行；发布能读新旧结构并逐步回填的新代码；验证覆盖率和一致性后再切换写入；最后删除旧字段与兼容分支。新增非空且无默认值的字段、直接重命名列或删除旧枚举，会让滚动发布中的旧实例失败。Flyway 脚本一旦进入共享环境通常追加修复，不重写历史版本。

当前迁移目录已包含到 V104；具体环境是否已经执行到最高版本必须以该环境的 `flyway_schema_history` 为准，不能把仓库文件存在当成共享库已升级。面试官问如何证明迁移可上线时，可以回答空库建库、从上一版本升级、旧新代码兼容、数据校验、耗时与锁观察、备份恢复和回滚演练。迁移脚本能执行只证明语法与基本数据路径正确，不证明大表时间、线上锁影响和生产回滚目标已经满足。

DDL 失败后的清理也要预案。影子表、临时触发器、未完成索引和磁盘增长都可能残留，重复执行脚本必须知道哪些步骤幂等。部署流水线应给每个迁移设置可观察的超时和人工门禁，不能在应用所有实例启动时同时抢 DDL 锁。对于不可在线完成的变更，应安排维护窗口或后台渐进回填。

## 20. MySQL 高频题的三分钟组织方式

数据库题可以按“访问模式、存储机制、一致性、容量证据”组织。问索引时，从 SQL 条件和排序出发，讲 B+Tree、联合索引与回表，再说写放大和 `EXPLAIN ANALYZE`；问事务时，从业务不变量出发，讲 MVCC、锁与隔离级别，再枚举并发窗口；问日志时，把 Redo、Undo、Binlog 的职责分清；问扩展时，先量化单库瓶颈，再讨论副本、分区或分片。

项目映射可选 Outbox Claim、Refresh Token Rotation、Snapshot Version、批量 Ownership Hydration 和 Cleanup Task。每个例子都要说明数据库约束是什么、应用层如何处理冲突、崩溃后如何恢复、测试如何构造竞态。不要把唯一索引说成完整幂等，也不要把事务隔离级别说成能解决所有业务一致性。三分钟主回答讲完整因果，面试官继续追问时再进入页结构、锁范围、日志刷盘、复制延迟或迁移锁等待。

训练时可以随机给一个 SQL，先写查询模式和正确性要求，再讨论索引，不要先猜答案。遇到“数据量一亿怎么办”先追问行宽、增长、冷热、查询分布和硬件，数据量本身不是方案。能用 `EXPLAIN ANALYZE`、慢查询、锁等待与真实分布验证，比背“最左匹配”更接近工程判断。

## 21. MySQL 基础母题长回答

### 21.1 B+Tree、联合索引和 EXPLAIN 怎样完整回答

B+Tree 的非叶节点主要保存 Key 与子页指针，分支因子高，树高较低，叶子按顺序连接，适合磁盘页访问、范围查询和排序。InnoDB 聚簇索引叶子保存整行，普通二级索引叶子保存索引列与主键；查询字段不在二级索引中时需要回表。主键过长会被复制到每个二级索引，随机主键还可能增加页分裂，因此主键选择同时影响空间和写入局部性。

联合索引遵循可利用的左侧前缀，但答案不能停在口诀。等值条件通常能连续缩小范围，遇到范围后，后续列仍可能用于 Index Condition Pushdown 或覆盖，却未必继续缩小扫描区间；排序能否利用索引取决于过滤、方向和列顺序。索引覆盖减少回表，代价是索引更宽、写放大和缓存占用。低区分度列与租户列的顺序要结合查询前缀、范围、排序和数据偏斜，不是固定把区分度最高放最前。

`EXPLAIN ANALYZE` 需要看实际行数、循环次数、访问方式、使用索引、排序、临时表和估算偏差。`type=ALL` 不一定错误，小表全扫可能最便宜；显示用了索引也可能扫描大量条目。NoteWeave 的 Outbox 查询按状态、可用时间和 ID 扫描，可用联合索引支撑 Claim；RAG Hydration 要批量按 ID 查询 Ownership，避免 TopK 形成 N+1。验证应使用真实数据分布、慢查询和并发负载，不能只在空表看计划。

索引失效还要检查隐式类型转换、函数包裹索引列、前导模糊匹配、排序方向和统计信息过旧。优化器根据成本选择计划，强制索引只适合作为充分验证后的临时措施。上线新索引前评估构建锁、磁盘空间和写入放大，发布后比较扫描行数、P99、Buffer Pool 与写吞吐，避免只优化一条 SQL 却拖慢整体写路径。

### 21.2 ACID、MVCC、隔离级别和锁怎样串起来

原子性通过 Undo 与事务管理实现，持久性依赖 Redo 与刷盘策略，一致性是数据库约束和业务不变量共同结果，隔离性控制并发事务相互可见。Read Uncommitted 允许脏读；Read Committed 每次一致性读生成新的 Read View；Repeatable Read 通常在事务内复用视图，并配合 Next-key Lock 处理当前读范围；Serializable 通过更强的读写约束降低并发。隔离级别越高不代表自动更正确，业务仍需唯一约束和条件更新。

MVCC 使用隐藏事务信息、Undo 版本链和 Read View 判断哪个版本可见。一致性快照读通常不加普通行锁，`select ... for update` 是当前读并参与锁竞争。InnoDB 行锁实际锁索引记录，缺少合适索引的更新可能扫描并锁住更大范围。Record Lock 锁记录，Gap Lock 锁间隙，Next-key 是两者组合。死锁来自不同事务形成等待环，数据库会选择牺牲者回滚，应用需要对短事务做有界重试。

NoteWeave 的 Refresh Token Rotation 使用条件更新保证只有当前 Token 可换新，Outbox Claim 和任务终态通过 Token/Version 防止旧 Owner 写入。对必须统一状态顺序的短临界区可用 `for update`，外部 I/O 不放在持锁事务中。并发测试应固定两个事务的执行顺序，覆盖重复 Claim、旧 Owner 晚到、终态竞争和死锁重试，并检查最终业务不变量，不只断言没有异常。

死锁分析应保存 InnoDB Deadlock 信息，查看双方 SQL、持有锁、等待锁、索引和事务顺序。统一加锁顺序、增加合适索引和缩短事务通常比提高隔离级别有效。重试要有上限与 Jitter，且整个业务操作必须幂等。若事务已完成外部副作用后才因数据库死锁回滚，简单重试会重复副作用，这也是外部 I/O 应移出事务并受 Receipt 管理的原因。

### 21.3 幂等、唯一约束、乐观锁和悲观锁怎么选

幂等表示重复执行同一业务操作，结果与执行一次一致。客户端或调用方提供 Idempotency Key，服务端将它与 Actor、Workspace 和 Operation Scope 组成唯一约束，并保存 Payload Digest、状态和 Receipt。相同 Key、相同 Payload 返回原结果；相同 Key、不同 Payload 必须冲突。只用先查再插存在并发窗口，唯一约束才负责最终仲裁，但唯一约束本身只拒绝重复，应用还要读取原结果并比较请求语义。

乐观锁通过 `where id=? and version=?` 条件更新，适合冲突较少、计算在事务外的状态变更；失败后重读、合并或返回冲突。悲观锁通过当前读阻塞其他修改，适合必须读取最新状态并立即完成的短临界区，会占连接并可能死锁。租约处理长任务时不能一直持数据库锁，应短事务 Claim，事务外执行，最后带 Claim Token 或 Epoch 确认。

NoteWeave 的 Callback、Cleanup Task、Writeback 与 Consumer 都有不同幂等范围。外部 Provider 成功但响应丢失时，数据库唯一键只能防止本地重复记录，无法撤销第二次外部副作用，还需 Provider 请求键、Receipt 查询或 Host 统一提交。测试要并发发送相同 Key 的相同和不同 Payload，注入提交前后崩溃，并验证过期清理不会过早重新开放重复窗口。

乐观锁冲突率升高时，持续重试会浪费计算并扩大数据库压力，可以改为串行化热点 Key、短悲观锁或把操作排队。悲观锁也不能跨长任务持有，否则连接与 Undo 成本不可接受。面试官问选型时，应给出冲突概率、临界区长度、失败代价和可重算性，而不是笼统说“读多写少用乐观锁”。

### 21.4 N+1、分页和数据增长如何治理

N+1 先查询 N 个主体，再为每个主体单独查询关联，SQL 次数随结果数增长。解决方式包括 Join、批量 IN、Entity Graph 或预聚合，但 Join 可能放大行数，超大 IN 会增加解析和计划成本，预聚合带来一致性。NoteWeave 对 Passage 与 Knowledge Version 做批量 Hydration，100 个 Hit 仍保持常数级 Ownership Query；结果按 ID 映射，不能依赖数据库返回顺序。

Offset 分页越深，需要扫描并丢弃的行越多，数据并发变化还会造成重复或跳过。Seek Pagination 使用稳定且唯一的排序游标，如 `(created_at, id)`，适合连续翻页；它不方便任意跳到第 1000 页。后台任务按主键或状态索引游标推进，并把每批完成位置持久化。总数统计昂贵时，可提供估算、异步统计或弱化产品需求。

数据增长后先量化表大小、工作集、增长率、索引写放大、慢查询与租户偏斜，再考虑归档、冷热分层、读副本、分区和分片。ES 是可重建投影，MySQL 是业务真源，两者扩展方法不同。分库分表会引入路由、跨分片事务、全局分页和扩容迁移，只有稳定分片键与容量证据出现时才值得。回答“一亿行怎么办”必须先追问行宽、查询模式与硬件。

批量 Hydration 还需要控制单批参数数和返回内存，必要时按固定大小分批，并对重复 ID 去重。Seek Cursor 应编码排序值、唯一 ID 和过滤条件版本，防止调用方篡改或在不同查询间复用。数据归档后要说明审计、删除与恢复需求，不能只把旧数据移动到另一个无人维护的表。

验证时记录 SQL 次数、扫描行数、返回行数、数据库 CPU、连接占用和端到端 P99。优化 N+1 后若一次 Join 返回笛卡尔放大结果，应用内存与网络可能更差，因此需要在真实 TopK 和关联基数下比较。

## 22. MySQL 八股中的项目真实设置

Backend 默认 Hikari Maximum Pool Size 20、Minimum Idle 4、Connection Timeout 30 秒、Validation Timeout 5 秒、Max Lifetime 30 分钟。这个池同时服务在线 API、Outbox、Cleanup、Research Coordinator 和其他后台任务。Answer I/O 最多 16 个线程、SSE Dispatch 最多 8 个线程，但它们不一定每个都持有连接，因此不能把线程数与连接数一一对应。

Outbox 默认每 1 秒调度一次、每批最多 50，Claim Lease 1 分钟，最多 5 次 Attempt；当前 Artifact Callback Lease 为 70 分钟。它是旧长消费协议的真实配置，不是推荐参数。推荐在短事务中按 Command ID 创建 Durable Execution，提交后确认 Kafka；Scheduler 再用独立 Execution Lease 管理 Worker。两种路径都要求 Kafka 和 Worker I/O 在数据库事务外执行，并通过唯一键、条件更新和 Fencing 接受重复投递与最终一致。

文档上传上限 128 MB，Chunk Size 900、Overlap 120。大文件会放大 Source、Chunk、Outbox、Embedding 和 Projection 数据量，数据库容量测试必须按真实文件分布计算，不能只按 Source 行数。迁移是否已在某个环境执行到 V104，要以该环境的 Flyway History 和校验结果为准。

面试时可以用 Hikari 20 做 Little 定律的演示，但结论必须标记为理想估算。完整参数来源见[项目真实配置参数与容量口径](15-项目真实配置参数与容量口径.md)。

## 23. MySQL 知识点的生产 SQL 与测试入口

| MySQL 题目 | 项目生产入口 | SQL 或事务方法 | 验证证据 |
|---|---|---|---|
| 条件更新与乐观并发 | `ResearchAgentTaskService.claimTask()/heartbeat()` | `where task_id + worker_instance_id + lease_epoch + fencing_token`，影响行数为零表示所有权已变化 | `ResearchAgentTaskServiceTest`、`ResearchAgentMySqlLockMatrixIT` |
| Outbox Claim | `DurableOutboxDispatcher.claim()` | `id + attempt_count` 或 `delivery_no + attempt_count` 条件更新，确认时再匹配 `lease_owner` | `DurableOutboxDispatcherTest` |
| Refresh Rotation | `AuthService.refresh()` | 数据库只保存 Token Hash，条件更新旧 Refresh Hash 并生成新会话 Token | Auth Service 与 Replay Test |
| 事务代理 | `WikiIngestTransactionExecutor.execute()` | 独立 Bean 的 `@Transactional` 包住数据库步骤，外部 I/O 留在事务外 | `WikiIngestFailureTransactionTest` |
| 批量 Ownership Hydration | `JdbcEvidenceOwnershipAdapter`、`RetrievalHydrator` | TopK Chunk Id 批量查询并映射归属，避免逐条 N+1 | `JdbcEvidenceOwnershipAdapterTest` |
| Seek 与后台扫描 | Outbox、Cleanup、Coordinator Scanner | 按状态、时间、ID 和 Lease 条件分批扫描，不做深 Offset | Dispatcher、Cleanup、Coordinator Test |
| Schema 迁移 | `backend/src/main/resources/db/migration` | Flyway 版本化 DDL，复杂迁移在隔离 MySQL 验证 | Flyway Migration Test；共享开发库当前版本边界必须单独说明 |
| 连接池容量 | Hikari 配置与全部 JDBC Service | Max 20、Min Idle 4、Connection Timeout 30 秒 | 需要连接池指标与压测；配置值本身不能证明 QPS |

面试时应把 SQL 形状讲到索引列顺序、锁范围和影响行数，再说明真实 `EXPLAIN ANALYZE`、慢查询与行数分布尚需运行环境证据。文档中的建议索引不自动等于已经在生产验证。

## 当前 Schema 核对

主库使用 MySQL 8.4，Spring JDBC + Flyway 仓库迁移文件当前到 `V104`。状态、事件、Outbox、Evidence、Snapshot 和 Workspace ACL 是主要事务边界；DAO 更新后必须检查受影响行数，避免把 Lease、版本冲突或资源不存在误报成成功。H2 兼容测试只能补充回归，锁和索引行为仍需 MySQL 验证。

## 24. MySQL 如何串成项目链路的 4 到 5 分钟回答

在 NoteWeave 中，MySQL 不是“存几张表的后台”，而是 Workspace、任务状态、版本、Evidence、Outbox 和回调收据的业务仲裁层。用户上传、提交回答或创建 Artifact 时，Java Service 在事务里写入状态、幂等键和下一步任务；Worker 的完成回调再通过带状态、版本、Lease 或 Fencing 的条件更新推进状态。检索 Projection、MinIO 对象和 Kafka 消息都可能暂时落后，但最终业务结论仍以 MySQL 中的可查询状态为准。回答索引题时，可以先讲 B+Tree，再说明为什么这些索引和唯一键直接决定上传去重、Workspace 隔离、Outbox Claim 和版本回滚的正确性。

B+Tree 适合范围和有序扫描，联合索引的列顺序要从实际过滤、排序和选择性出发，不能看到某个字段常用就单独建索引。Workspace 查询通常需要把 `workspace_id` 放进过滤路径，Outbox 需要覆盖 Ready 状态、时间、Lease Owner 和 Attempt，事件回放需要按 Run 和单调序号读取。`EXPLAIN` 要看访问类型、候选索引、实际扫描行、回表和排序，而不是只看“用了索引”。索引越多，写入、页分裂、Buffer Pool 和迁移成本越高，因此要结合真实 SQL、数据分布和慢查询验证。

事务方面，ACID 解决的是一个本地数据库边界内的原子性和约束，不等于 MySQL、Kafka、MinIO、ES 之间有全局强一致。上传状态和 Outbox 可以在同一事务里提交，Kafka 发送和 ES 写入必须靠幂等、版本和补偿收敛。InnoDB 的 MVCC、Undo 和 Read View 让普通读不必阻塞写，条件更新和行锁则承担任务 Claim、Lease 和版本仲裁；高并发下要警惕锁顺序不一致、范围更新触发 Gap Lock、长事务阻塞 Purge 和连接池耗尽。死锁不是简单把事务重试无限次，必须固定访问顺序、缩短事务、限制重试，并记录冲突 SQL 和业务键。

项目选择 Spring JDBC 和 Flyway，而不是让 JPA 自动建表，是因为关键 SQL 需要显式表达受影响行数、批量 Hydration、JSON 快照和迁移兼容。Expand/Contract 发布先增加可兼容字段，再让新旧代码同时可读写，回填和索引创建拆成可观测步骤，最后才删除旧语义；Flyway 成功只代表 SQL 执行完成，不代表旧应用、Worker 和 Projection 已兼容。PostgreSQL、分库分表、读写分离和分区表都有适用场景，但当前数据量和跨租户约束更需要单库内的关系完整性与可解释事务。未来如果写入规模、锁等待或备份窗口成为瓶颈，升级依据应是慢查询、连接池、复制延迟和数据增长曲线，而不是先把数据库拆开。

面试收尾可以说：MySQL 在系统里承载的是“谁有权推进哪个状态”，索引决定查询和 Claim 的成本，事务决定本地状态是否完整，版本和唯一键决定重试是否产生重复业务结果。当前仓库迁移文件到 V104、测试 H2 兼容模式只能作为辅助验证，不能替代 MySQL 8.4 的锁、索引和 SQL 计划检查；没有生产数据、备份恢复和 Online DDL 演练时，也不能夸大为已经完成了高可用数据库治理。

## 25. 必须独立讲三分钟的 MySQL 知识点

| B 档知识点 | 三分钟主回答入口 | 项目落点 | 二阶追问 |
| --- | --- | --- | --- |
| 联合索引与 Claim 查询 | 1 到 3、21.1、22 | Outbox、Task、Cleanup 扫描 | 等值、范围和排序如何决定索引，为什么最老任务年龄比 Count 更重要 |
| MVCC、隔离与锁 | 4 到 7、21.2 | 状态读取、Claim、Refresh Rotation | 快照读与当前读有何区别，Next-key Lock 何时出现，如何从死锁日志确定加锁顺序 |
| 唯一约束、幂等与 CAS | 8、9、21.3 | Completion、Callback、Cell Version | 为什么先查再插仍会重复，影响行数为零是冲突还是成功，唯一键冲突后怎样返回原 Receipt |
| 事务与 Outbox | 14、19、24 | Source/Task 与消息意图同事务 | Redo、Binlog 和提交顺序解决什么，为什么 Kafka 发送不能加入本地事务 |
| 连接池、长事务与容量 | 15 到 17 | Hikari、批量 Hydration、后台任务 | 连接数为何不能无限加，长事务如何拖累 Undo 与清理，线程池为什么不能大于所有下游容量之和 |
| DDL 与 Expand/Contract | 19、22 | Flyway、事件字段与多版本兼容 | Online DDL 是否零影响，旧 Worker 如何兼容新 Schema，Contract 何时可以执行 |

普通 SQL 语法、单表 CRUD 和字段类型问题属于 C 档。面试官继续问执行计划、并发冲突、锁、事务或数据增长时，再切换到 B 档，完整回答必须包含具体查询条件、索引顺序、数据分布和验证方式。

### 二阶回答示例：唯一约束已经幂等，为什么还需要状态机

唯一约束只能阻止同一个业务键重复创建记录，不能判断一次状态迁移是否合法。Callback 可能使用同一个幂等键却携带不同 Payload，旧 Worker 也可能针对同一 Task 提交过期结果。系统先用唯一键定位同一语义操作，再比较 Payload Digest、From Status、Attempt 和 Token。完全相同的重放返回原 Receipt，键相同但内容不同返回冲突，状态或代际不匹配拒绝更新。幂等身份、内容一致和状态合法是三个不同条件。
