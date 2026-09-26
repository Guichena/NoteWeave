# Java、JVM、并发与网络基础下钻

> 阅读说明：第 2 到 11 节保留为打断式速查，第 12 到 17 节是单知识点长回答，第 19 节提供覆盖旧速查内容的 3 到 5 分钟母题。面试准备以长回答为主，速查用于面试官只追问一个术语时快速定位。

## 1. 从项目切入基础题

大厂面试官不会满足于“用了线程池和 CAS”。常见路径是：为什么拆线程池，队列满了怎么办，任务提交和变量修改之间有什么可见性，GC 停顿为何影响 Lease，HTTP 超时如何分层。回答要先讲 Java 原理，再落到 NoteWeave。

## 2. Java 内存模型、volatile 与 CAS

Java Memory Model 规定线程之间何时能够看到写入。`volatile` 保证对变量写入对后续读取可见，并限制相关指令重排，但不让复合操作自动原子。`count++` 包含读、加、写，多个线程仍会丢更新。

CAS 比较内存中的当前值与期望值，相等才写入新值。Java 的 `AtomicInteger` 用它实现无锁原子更新。数据库中的条件更新也有相似思想：

```sql
update task_outbox
set status = 'SENT'
where id = ?
  and status = 'PROCESSING'
  and lease_owner = ?
  and attempt_count = ?;
```

受影响行数为 0 表示当前代际已变化。数据库 CAS 依赖事务与行锁，不等同于 CPU CAS，但都防止先查后改竞争。

ABA 指值从 A 变成 B 又回到 A，单看值无法发现中间变化。NoteWeave 的 Claim Token、Attempt Count、Lease Epoch 和 Fencing Token 相当于版本号，旧 Owner 即使看到相同任务 ID，也不能确认新一代 Claim。

在项目里，这个知识点对应两条真实链路。`ResearchAgentTaskService.claimTask()` 领取研究任务时会推进 `lease_epoch` 和 `fencing_token`，`heartbeat()` 的更新条件同时带上 `task_id`、`worker_instance_id`、`lease_epoch` 与 `fencing_token`。Worker 只知道任务 ID 不够，必须证明自己仍是当前代际的 Owner。Outbox 则由 `DurableOutboxDispatcher.claim()` 使用 `id + attempt_count` 做条件更新，发送成功后的 `acknowledgeTaskMessage()` 还要匹配 `status = PROCESSING` 与 `lease_owner`。这相当于把 CAS 的“期望值”扩展成任务状态、尝试次数和 Owner Token 的组合。面试时可以直接说：项目没有用 `volatile` 解决跨实例一致性，JVM 内可见性由同步器负责，跨实例竞争交给 MySQL 条件更新或 Redis Lua。验证证据包括研究任务旧 Token 续租失败测试、Outbox 重复 Claim 测试，以及检查更新影响行数是否为 1。

## 3. synchronized、Lock 与 AQS

`synchronized` 提供互斥和 Happens-before，退出监视器的写入对随后获得同一监视器的线程可见。`ReentrantLock` 支持可中断获取、超时、公平策略和多个 Condition。AQS 用一个同步状态、CAS 和等待队列支撑 ReentrantLock、Semaphore、CountDownLatch 等同步器。

进程内锁不能保护多实例。Answer Event Mux 的本地 Channel 可以用同步块保护队列，但 Outbox Claim、配额和任务状态必须依赖 MySQL 或 Redis 原子操作。把 `synchronized` 加在 Service 方法上，只能防同一个 JVM 中的线程。

具体实现位于 `SessionEventMux`。它用 `ConcurrentHashMap<String, RunChannel>` 管理不同 Run 的 Channel，用 `synchronized (channel)` 保护同一 Run 的序列号、Replay 队列和订阅者列表，用 `synchronized (subscriber)` 保护每个订阅者的 Pending 队列与 Drain 状态。锁没有覆盖 Redis XREAD、SSE 写出等慢 I/O，`scheduleDrain()` 会把消费交给 `sseDispatchExecutor`，避免持锁调用用户侧 Consumer。多实例之间则通过 `AnswerRealtimeBridge` 衔接，不能依赖本地锁。另一个真实例子是 `WorkloadQuotaService.localRateDecision()`：只有开发环境允许本地降级时，才对单个 `LocalBucket` 使用 `synchronized` 完成令牌补充和扣减；正式的分布式配额使用 Redis Lua。这里的取舍是局部对象锁实现简单，但只在单进程有效；Redis Lua 能覆盖多实例，代价是增加网络依赖，因此生产环境 Redis 不可用时选择 Fail-closed，而不是悄悄切到每实例各算各的本地限额。

## 4. 线程池参数如何解释

ThreadPoolExecutor 的核心参数是 Core Pool Size、Maximum Pool Size、Keep Alive、Work Queue、Thread Factory 和 Rejection Handler。执行顺序是先创建核心线程，核心线程满后入队，队列满后再扩到最大线程，最后执行拒绝策略。

无界队列会让 Maximum Pool Size 基本失效，并把过载变成内存增长和长时间排队。NoteWeave 将 Answer I/O、SSE Dispatch、SSE Connection 和 Redis Bridge 分池，避免长连接、网络等待和短分发任务互相占用线程。队列有界并使用 AbortPolicy，让过载通过指标与错误显式暴露。

CPU 密集任务的线程数通常接近 CPU Core，I/O 密集任务可根据等待/计算比例增加：

```text
threads ~= cores * (1 + wait_time / compute_time)
```

公式只是起点，还要受数据库连接池、Provider 并发和内存限制。线程数大于 Hikari 连接数时，很多线程只是在等待连接。

项目配置可以沿着 Bean 和调用方完整说明。`RealtimeExecutorConfig.answerIoExecutor()` 配成 Core 4、Max 16、Queue 64、`AbortPolicy`，由回答生成中的阻塞 I/O 使用；`sseDispatchExecutor()` 是 `2/8/128`，由 `SessionEventMux` 与 `ConversationEventMux` 派发事件；`sseConnectionExecutor()` 是 `4/32/0`，由 `ConversationController` 和 `TaskController` 承接长连接，零队列使新连接在容量用尽时立即失败；`answerBridgeExecutor()` 是 `2/16/0`，因为 Redis XREAD 是阻塞任务，如果先进入大队列，两个核心线程就可能让其他 Run 的 Pump 永远排队。后台 Artifact Outbox 使用 `SchedulingConfiguration.artifactDispatchExecutor()` 的 `1/1/0`，避免同一进程并行派发破坏 Claim 节奏。所有这些池都使用 `AbortPolicy`，所以面试时还要说明调用方如何观察拒绝，不能只说“做了线程池隔离”。当前参数是配置基线，不是压测得出的最优值；调优要同时看 Active、Queue、Rejected、Hikari Pending、Provider 429 和端到端 TTFT。

## 5. CompletableFuture 与上下文传播

`CompletableFuture` 的异步方法若不指定 Executor，可能使用 Common ForkJoinPool。服务端不应让阻塞 I/O 混入公共池，否则一个模块可以拖慢其他任务。异常也需要在 `handle`、`exceptionally` 或最终 Join 处消费，不能只启动后不观察结果。

ThreadLocal 和 MDC 不会自动跨线程传播。NoteWeave 的 Executor 对 MDC 做复制，任务执行后必须清理，避免线程复用把上一个 Workspace 或 Trace 信息带到下一请求。安全身份不应只依赖 ThreadLocal 快照，关键回调仍要用请求中的稳定身份重新校验。

这里需要按当前代码准确描述。主代码没有使用 `CompletableFuture`，异步边界主要通过注入的 `Executor.execute()`、`ScheduledExecutorService`、Kafka Consumer 和 Worker 回调实现，所以 `CompletableFuture` 属于面试延伸，不是项目卖点。MDC 的 `TaskDecorator` 目前只配置在 `RealtimeExecutorConfig.answerIoExecutor()`：提交时调用 `MDC.getCopyOfContextMap()` 捕获上下文，执行前保存工作线程原值，`finally` 中恢复或清空。`sseDispatchExecutor`、`sseConnectionExecutor` 和 `answerBridgeExecutor` 当前没有同样的装饰器，不能回答成“所有异步线程都会自动带 Trace”。用户身份也没有从 MDC 读取，`CurrentUserProvider` 从 Servlet Request Attribute 获取；离开 HTTP 请求后的研究 Worker、Artifact Worker 和内部回调使用独立内部 Token、TaskId、WorkerInstanceId 与 Fencing Token 重新鉴权。可补的测试是单线程池连续执行两个不同 MDC 的任务，并让第一个任务抛异常，断言第二个任务看不到前一个请求的数据。

## 6. JVM 内存区域与 OOM

Heap 保存对象，按代际与收集器策略管理；每个线程有 Java Stack，保存栈帧、局部变量和调用状态；Metaspace 保存类元数据；Direct Memory、线程栈、JIT Code Cache 和 Native Library 也占进程内存。`-Xmx` 只限制 Heap，机器仍可能因 Direct Buffer、线程数、Fork JVM 或 Native Allocation 失败。

常见区别：

| 错误 | 常见原因 | 排查证据 |
|---|---|---|
| Java heap space | 对象保留过多、堆上限太小 | Heap Dump、GC Log |
| Metaspace | 动态类加载或 ClassLoader 泄漏 | Class Histogram |
| Unable to create native thread | 线程过多、栈或系统限制 | Thread Dump、进程限制 |
| Direct buffer memory | NIO Direct Buffer 未释放或上限过低 | NMT、Buffer Pool |
| Native mmap failure | 进程/系统内存不足或地址空间碎片 | `hs_err_pid`、系统指标 |

项目全量测试曾出现 G1 Virtual Space `mmap` 失败，不能简单归类成业务内存泄漏。

项目里真正控制内存的第一手段不是 JVM 参数，而是给高风险容器设置上限。`SessionEventMux` 与 `ConversationEventMux` 的 Replay Capacity 默认 512、单订阅者队列默认 64；Embedding 文档批次默认 32，单条输入最多 12000 字符；线程池队列也全部有界。队列满以后，Subscriber 会关闭或执行器拒绝，而不是无限堆积事件。当前 Compose 只显式给 Elasticsearch 设置 `-Xms512m -Xmx512m`，Backend 没有提交固定 `-Xmx` 或 GC 参数，因此面试时应说“应用侧已经限制队列和批次，但 Backend Heap 仍要按部署环境设定”，不能声称完成了 JVM 生产调优。排查时可从实时事件队列指标、线程数量和 Heap Dump 的 `RunChannel`、`Subscriber`、文档批次数量互相印证；如果 Heap 正常而进程 RSS 持续增长，再查 Direct Buffer、线程栈和 Native Memory。

## 7. GC 与长尾延迟

G1 把 Heap 分成 Region，优先回收收益较高的区域，并以停顿目标做并发标记与混合回收。停顿目标不是硬 SLA。高分配速率、大对象、Remembered Set 压力和堆设置不当都会造成长尾。

Stop-the-world 暂停会影响 Lease Heartbeat。当前 Research Task Lease 为 60 秒、Heartbeat 为 15 秒、失败预算为 30 秒；若 JVM 停顿超过安全窗口，其他实例可能接管。旧实例恢复后必须依靠 Fencing Token 阻止晚到写入，不能指望“GC 不会停那么久”。指标应关联 GC Pause、Heartbeat Failure、Lease Recovered 和任务冲突。

这条链路在代码中由 `ResearchAgentTaskService.heartbeat()` 落地：续租 SQL 必须同时命中 Worker、Lease Epoch 和 Fencing Token，返回的新过期时间才有效。配置校验要求 Heartbeat 小于 Lease 的三分之一，失败预算加一个 Heartbeat 仍小于 Lease，目的是给网络抖动和一次失败留出空间。Answer Stream 使用另一套 150 秒 Lease、45 秒续租，不能把两组数字混用。当前仓库没有为 Backend 固化 G1 Pause Goal，也没有生产 GC P99 数据，所以正确口径是“系统已设计停顿后的正确性保护，但尚未用线上数据证明 GC 长尾满足目标”。验证可以暂停 Worker 进程超过 Lease，让另一实例接管，再恢复旧实例，断言旧 Fencing Token 的 Heartbeat 和完成提交都被拒绝。

## 8. Spring 事务常见追问

`@Transactional` 通常通过代理生效，同类内部方法直接调用可能绕过代理；Private 方法也不能按普通代理语义拦截。Checked Exception 默认不一定回滚，需要明确 `rollbackFor` 或异常体系。异步线程不会自动继承调用线程事务。

传播行为中，REQUIRED 加入当前事务或新建事务；REQUIRES_NEW 挂起外层并创建新事务；NESTED 依赖 Savepoint。Outbox 要求业务状态与消息意图同事务，不能误用 REQUIRES_NEW，否则外层业务回滚而 Outbox 已提交。

外部 Kafka、MinIO、ES 调用不应在持有数据库行锁的长事务中执行。项目通过短事务 Claim，事务外发布，再用条件更新确认，缩短锁时间并接受重复投递。

项目对自调用问题有一个可直接展示的例子。`WikiIngestService.runSourceIngestNow()` 负责组织流程，但真正需要事务的数据库步骤交给独立 Bean `WikiIngestTransactionExecutor.execute()`，方法上标有 `@Transactional`，因此调用会经过 Spring 代理，而不是在同一个 Service 中写 `this.doInTransaction()`。Outbox 的边界则体现在业务服务先在本地事务中写任务与 `task_outbox`，`DurableOutboxDispatcher.dispatchTaskMessages()` 之后再 Claim、调用 Publisher、确认 SENT 或安排重试。Kafka 发布不被包进创建业务对象的长事务。测试应使用真实 Spring Context 和 MySQL，构造事务中途异常，验证业务行与 Outbox 同时回滚；再让发布端在已经发送后超时，验证重复派发会被消费者幂等键吸收。`SegmentSummaryPromotionService` 还显式使用了 `@Transactional(noRollbackFor = BusinessException.class)`，面试时可以用它说明回滚规则是业务选择，不是“所有异常都回滚”。

## 9. TCP、HTTP 与超时

TCP 提供有序可靠字节流，不保留应用消息边界。建立连接通常经历三次握手，正常关闭涉及 FIN/ACK；连接池与 Keep-alive 减少重复握手和 TLS 成本。TCP 成功只表示字节到达对端协议栈，不表示业务事务成功。

HTTP 客户端至少区分 Connect Timeout、Read Timeout、Write Timeout、Connection Pool Acquire Timeout 和任务总 Deadline。每层独立设置但要满足外层预算：

```text
connect + retries * per_attempt_timeout < task_deadline
```

无限重试会突破用户 Deadline，也会在下游恢复时制造重试风暴。指数退避加 Jitter 只用于可恢复错误，永久 Schema、权限和输入错误直接终态化。

项目里可以对照两类 HTTP Client。Artifact Worker 控制面由 `ArtifactWorkerRestClientFactory.create()` 构造 JDK HttpClient，Connect Timeout 默认 3 秒，`JdkClientHttpRequestFactory` 的 Read Timeout 默认 30 秒，由 `HttpArtifactWorkerControlClient` 用于 Resume、Acquisition Ack 等短调用；Artifact 任务启动已改走 Kafka，不再受这两个 HTTP Timeout 控制。Embedding 与 Rerank Provider 也使用 JDK HttpClient，连接建立固定为 10 秒，单次请求分别使用配置中的 Query 10 秒、Batch 60 秒和 Rerank 20 秒，最大尝试次数默认 3。Provider 对 408、429 和 5xx 重试，对无效响应、维度不匹配等契约错误直接失败。当前调用链尚未实现统一 Deadline 和完整取消传播，因此不能把各层 Timeout 简单相加后声称满足端到端 SLA。面试时可以指出这个演进边界：下一步应把 Answer Run Deadline 向 Provider、Worker 和重试器下传，并确保剩余预算不足时不再开启新 Attempt。

## 10. DNS、TLS 与 SSRF

DNS 解析结果可变化，SSRF 防护不能只在首次解析时检查 Host，连接时还要防 DNS Rebinding，并限制 Redirect 后的新地址。内网、Loopback、Link-local 和云元数据地址应拒绝。HTTPS 还需要校验证书和 Hostname，不能为方便关闭验证。

Research Fetch 已实现更完整的出站检查，但 Search/LLM Egress 的防护边界不同。面试时不能把某一条请求路径的 SSRF 防护推广到所有外连能力。

Research Worker 的真实入口在 `workers/research-worker/app/fetch_adapters.py`。受控请求会用 `socket.getaddrinfo()` 解析目标，筛掉非公网地址，再把连接固定到验证过的 IP；HTTPS 仍以原 Host 做 SNI 和证书校验。它最多跟随 5 次 Redirect，每次跳转都重新检查目标，跨 Origin 时剥离凭据。测试 `test_fetch_adapters.py` 覆盖了解析结果、固定连接和 Redirect 改端口时去除认证信息。Backend 的 `ResearchExternalSnapshotArchiveService.validate()` 只负责归档入口的第二层校验：限制 `http/https`、拒绝 UserInfo、localhost、云元数据域名和多种数字 IP 写法，但它刻意不解析普通用户域名，因为真正的 DNS 解析和连接固定在 Worker 中完成。这种分层可以防止恶意 URL 成为持久化来源，但 OpenAI-compatible LLM、Embedding、Rerank Endpoint 目前是管理员配置的固定地址，并没有复用同一套 Fetch 沙箱。因此只能说 Research Fetch 链路具备 SSRF 防护，不能泛称项目所有 HTTP 出站都防 DNS Rebinding。

## 11. 排障题回答顺序

CPU 高先看线程运行状态、热点方法和 GC，不直接加机器；内存高区分 Heap、Native、Direct Buffer 与 Page Cache；接口慢拆 Queue、DB、Provider 与 Streaming；线程数高检查阻塞点和线程池隔离；连接池 Pending 高检查慢 SQL、长事务和池大小。每次都要用指标和 Dump 验证假设。

落到项目排障时，可以从 `OperationalMetricsBinder.bind()` 注册的指标开始：`noteweave.outbox.messages{status}` 判断积压在哪个状态，`noteweave.outbox.oldest_ready_age_seconds` 判断最老可派发消息等待多久，`noteweave.source.messages{status}` 和 `noteweave.source.projection_chunks{status}` 判断资料解析与 ES 投影是否卡住。实时回答还要结合各执行器 Active、Queue、Rejected，数据库看 Hikari Active、Pending，外部 Provider 看超时、429 和降级原因。一个具体判断是：Answer 慢但 Outbox Age 正常，进一步看 `answerIoExecutor` Queue；Queue 高且 Hikari Pending 为零，更可能是 Provider 等待；Hikari Pending 同时升高才继续查慢 SQL 和长事务。仓库提供 Micrometer 和 Actuator 指标，跨 HTTP、Kafka 和 Worker 的关联主要依靠 RunId、TaskId、Attempt、SnapshotVersion 及结构化日志；当链路规模扩大时，再按新的排查成本增加指标维度。

## 12. HashMap、ConcurrentHashMap 与集合选择

HashMap 的核心是数组加桶结构。Java 8 中发生哈希冲突时先形成链表，桶内元素达到阈值且数组容量足够后才可能树化为红黑树；扩容通常把容量翻倍，并根据 Hash 的高位决定节点留在原位置还是移动到原位置加旧容量。平均查询接近 O(1)，极端冲突下会退化。它允许空 Key 和空 Value，但不是线程安全容器。并发写可能产生覆盖、可见性异常和结构竞争，不能因为“不同线程写不同 Key”就默认安全。

ConcurrentHashMap 在 Java 8 中不再用早期的 Segment 作为主要结构，而是通过 CAS、桶头锁和 volatile 可见性协调更新。读取通常不加互斥锁，初始化、空桶插入和计数会用 CAS，桶内冲突更新才锁住局部节点。它不允许空 Key 或空 Value，因为并发读取返回空值时无法区分“没有映射”和“映射值为空”。`computeIfAbsent` 适合原子创建映射，但回调不能做长时间阻塞 I/O，否则会放大桶竞争，也要防止递归更新。

集合选择要从访问模式出发。ArrayList 适合按下标读和尾部追加，中间插入需要移动元素；LinkedList 节点额外占内存，缓存局部性差，实际业务中很少因为“插入 O(1)”就更快，因为找到插入位置本身仍可能是 O(n)。CopyOnWriteArrayList 适合读多写极少、元素不大的监听器快照，写入会复制整个数组。NoteWeave 的运行态订阅者、任务索引或本地缓存如果存在高频增删，应使用并发 Map、有界队列或明确锁，不应机械套 Copy-on-write。面试继续追问时，要能说明时间复杂度只是上界，内存布局、热点冲突、迭代一致性和写入比例同样影响选择。

迭代器还会被追问 Fail-fast。普通集合通过修改计数尽早发现并发结构变化，抛出异常只是 Bug 检测，不是线程安全保证；ConcurrentHashMap 的迭代是弱一致视图，允许遍历期间更新，但不保证看到某个瞬间的完整快照。需要稳定快照时，应在业务版本或锁边界内复制，不依赖某个并发集合“看起来没有报错”。

项目中的集合选择有两个可讲清楚的例子。`WorkloadQuotaService` 用 `ConcurrentHashMap` 保存本地令牌桶和 Lease 镜像，用 `ConcurrentHashMap.newKeySet()` 去重已安排清理的 Key，用 `ConcurrentLinkedQueue` 保存待清理 Lease Key；创建 Bucket 用 `computeIfAbsent()`，但实际扣 Token 仍对单个 Bucket 加锁，因为读取余额、按时间补充、更新时钟和扣减必须作为一个复合操作。`SessionEventMux` 也没有把 `ConcurrentHashMap` 当成万能线程安全，Map 只保证 Channel 查找与创建，Channel 内的 ArrayDeque 和 Subscriber List 仍由局部锁保护。面试官追问 `computeIfAbsent` 时，可以指出项目回调只创建小对象，没有在 Map 的原子计算中访问 Redis 或数据库。若需要跨实例配额，代码切换到 Redis Lua，而不是继续扩大本地并发集合的职责。

## 13. equals、hashCode、不可变对象与泛型

使用 HashMap 或 HashSet 时，逻辑相等对象必须有相同的 `hashCode`。只重写 `equals` 不重写 `hashCode`，会让相等对象落入不同桶，导致查找和去重失效。对象作为 Key 后如果参与 Hash 的字段被修改，后续查询可能再也找不到原条目，因此身份对象、版本号、幂等键和缓存 Key 更适合设计成不可变值对象。Record 能减少样板代码，但其中引用的集合仍可能可变，不能把 Record 等同于深度不可变。

Java 泛型通过类型擦除实现，大多数泛型参数在运行时不会保留为可直接判断的具体类型，因此不能写 `new T()`，也不能可靠执行 `obj instanceof List<String>`。桥接方法用于保持多态，通配符遵循 PECS：生产数据的参数偏向 `? extends T`，消费数据的参数偏向 `? super T`。这条规则解决的是 API 的读写边界，不是让所有方法签名都加通配符。

项目中的 WorkspaceId、RunId、SnapshotVersion 如果都用裸 String，编译器无法阻止参数传错。值对象能把格式校验、相等性和日志脱敏集中起来，但会增加序列化、ORM 映射和接口转换成本。面试时应把“为什么不可变”讲到线程安全、缓存 Key 稳定、事件快照和重放，再说明并非所有 DTO 都值得包装。高频追问通常会落到 String 为什么不可变、字符串常量池、`==` 与 `equals`、浅拷贝与深拷贝，回答核心仍是对象身份、逻辑相等和可变状态的边界。

String 不可变使常量池共享、Hash 缓存和安全参数传递更可控，拼接次数很多时应使用 StringBuilder，跨线程共享可变拼接才考虑带同步的 StringBuffer。包装类型缓存会让部分小整数用 `==` 看似相等，业务比较仍应使用值语义。面试官喜欢用边界例子检查是否真正理解，因此回答规则后最好给出一个 HashSet 去重失效或可变 Key 找不到的具体过程。

项目的真实值语义可以用 `MemoryCompiledPackCache.CacheKey` 展开。它是一个 Record，由 `workspaceId`、`actorFingerprint`、`packType`、`requestFingerprint`、`policyVersion` 和 `stateFingerprint` 共同组成；写 Redis 时这些字段也按相同顺序拼入 Key，读取后还会执行 `cacheKey.equals(envelope.cacheKey())`，防止拿到 Schema 一样但身份或策略版本不一致的缓存包。Record 自动生成的 `equals()` 和 `hashCode()` 让内存比较稳定，但字段仍都是裸 String，因此格式与脱敏要由上游保证。另一个边界是许多 Command 和 Receipt Record 内含 List 或 Map，它们只是浅层不可变，构造后如果继续修改原集合，Record 内容仍会变化。项目目前没有把 WorkspaceId、TaskId 全部包装成强类型值对象，这是减少 JDBC、JSON 和 Controller 转换成本的取舍，也意味着同为 String 的参数仍可能传错，需要数据库约束、身份校验和集成测试兜底。

## 14. 类加载、双亲委派与 SPI

类的生命周期包括加载、验证、准备、解析、初始化、使用和卸载。加载阶段找到字节码并创建 Class 对象；准备阶段为静态字段分配内存并设置零值；初始化阶段执行静态字段赋值和静态代码块。触发初始化的常见场景包括创建实例、读取非编译期常量静态字段、反射调用和初始化子类。只引用父类静态字段时，子类不一定初始化。

双亲委派表示 ClassLoader 收到加载请求后，通常先交给父加载器，父加载器找不到才由自己加载。它减少核心类被重复或恶意替换的风险，也保持同一个类身份稳定。JDBC、SPI 和容器插件场景可能通过线程上下文类加载器反向加载应用实现。判断两个类是否相同，不只看全限定名，还要看定义它们的 ClassLoader；同名类由不同加载器加载，可以同时存在且不能直接强转。

NoteWeave 当前 MCP 和 Python Worker 主要是进程级扩展边界，不应把它描述为 JVM 内部插件 ClassLoader。进程隔离让依赖、崩溃和语言运行时更容易分开，但协议版本、部署和观测成本更高。若未来增加 Java Skill 插件，才需要认真处理类加载隔离、依赖冲突、卸载、权限和签名。面试官问“为什么不用自定义 ClassLoader”时，可以从当前扩展是否需要热加载、是否信任插件、是否允许共享 JVM 故障域回答，而不是只背双亲委派定义。

类卸载通常要求定义类的 ClassLoader、它加载的所有 Class 和实例都不可达，线程上下文加载器、静态集合和驱动注册都可能让插件无法卸载。线上遇到 Metaspace 持续增长时，要检查重复创建 ClassLoader、动态代理或字节码生成，而不是只调高上限。打包冲突则可从实际加载来源、依赖树和类加载日志验证。

这题在项目中最重要的是承认边界。当前 Skill、MCP 与 Research Worker 都不是通过 `ServiceLoader` 或自定义 ClassLoader 在 Backend JVM 内热加载：MCP 通过协议调用外部 Tool Server，Research 和 Artifact 是独立进程，Spring Bean 仍由正常 Classpath 和组件扫描创建。这样选择是因为第三方工具存在依赖冲突、阻塞和崩溃风险，进程边界更容易做超时、鉴权和资源隔离；代价是多一层协议、部署和可观测性成本。因此“JDBC SPI 如何工作”可以作为 Java 原理回答，但不能说 NoteWeave 用 SPI 加载 Agent 插件。只有未来要支持可信 Java Skill 热插拔时，才需要设计子 ClassLoader、API Parent、依赖隔离、签名校验和卸载检测。

## 15. ThreadLocal、上下文传播与内存泄漏

每个 Thread 内部持有 ThreadLocalMap，Key 是 ThreadLocal 的弱引用，Value 是强引用。Key 被回收后，Value 不一定立即释放，只有后续访问触发清理或线程结束才可能回收。线程池中的线程长期存活，因此 ThreadLocal 使用后未 `remove`，既可能保留大对象，也可能把上一个请求的用户、Trace 或事务上下文污染到下一个请求。弱引用 Key 并不能自动解决 Value 泄漏。

ThreadLocal 适合传递请求范围且同步调用链中不方便逐层传参的小型上下文，例如 MDC TraceId。它不适合保存业务真源，也不会自动传播到新线程、CompletableFuture、调度线程或响应式链路。项目仅在 Answer I/O 线程池上通过 TaskDecorator 捕获并恢复 MDC，执行后清理；这种做法要使用 `try/finally`，并防止嵌套提交覆盖原上下文。事务和 SecurityContext 是否传播还应分别判断，不能看到 MDC 可传播就把数据库事务一起跨线程延伸。

继续追问时，要区分 ThreadLocal 泄漏、线程池队列积压和真正的 Heap 泄漏。排查可用 Heap Dump 查看 Thread 对象到 Value 的引用链，再结合线程池规模与对象类型判断。替代方案是显式 Context 参数、结构化任务上下文或框架提供的上下文传播。显式参数更透明且容易测试，代价是签名传递较多；ThreadLocal 接入方便，代价是隐式依赖和异步边界容易丢失。

`InheritableThreadLocal` 只在线程创建时复制父值，对预先创建的线程池通常不能表达每个任务上下文，也可能传播可变对象。面试官问如何测试上下文污染时，可以在单线程 Executor 连续提交两个不同用户任务，断言第二个任务看不到第一个任务的 MDC、身份或事务信息，并覆盖异常路径是否执行清理。

项目还有一个真正使用 ThreadLocal 的位置：`ResearchAgentCompletionMetrics` 用 `ThreadLocal<String> transactionStage` 记录原子完成事务当前走到哪个 Fault Injection Stage，回滚时把 Stage 写入低基数指标，结束后由 `clearTransaction()` 调用 `remove()`。它适合 ThreadLocal，是因为值只服务于当前同步事务调用，不是业务真源；任务最终状态仍在数据库中。请求用户则没有放进自定义 ThreadLocal，`CurrentUserProvider.currentUserId()` 从当前 Servlet Request Attribute 获取。异步 Worker 不继承这个请求上下文，必须显式传 Workspace、Task 和内部服务凭证。回答时可以把 MDC 传播、事务 Stage 和用户身份分开，说明三者生命周期不同，不能用一个“上下文 ThreadLocal”全部承载。

## 16. JDK 动态代理、CGLIB 与 Spring AOP

JDK 动态代理基于接口和 InvocationHandler 生成代理类，CGLIB 通过生成目标类子类拦截可覆盖方法，因此 Final 类、Final 方法和 Private 方法不能按普通子类代理方式增强。Spring 会根据目标类型和配置选择代理方式。代理对象负责在调用前后加入事务、鉴权、指标或重试，目标对象本身并没有凭空获得这些能力。

自调用问题来自调用没有经过代理。一个 Bean 的 `outer()` 直接执行 `this.inner()`，即使 `inner()` 标注了 `@Transactional` 或 `@Retryable`，也可能绕过拦截器。常见修复是重新划分 Bean 边界，让调用经过另一个 Bean，或把事务放到真正的应用服务入口。通过暴露代理再自取调用会增加隐式耦合，不宜作为默认设计。代理顺序也重要，重试包住事务意味着每次尝试可以获得新事务；事务包住重试则多次尝试可能共享一个已标记回滚的事务。

NoteWeave 的 Outbox 创建、任务状态迁移和回调确认依赖事务边界。面试时可用“业务状态与消息意图必须在同一个本地事务”解释 AOP 的价值，再指出 Kafka 发布、模型调用和对象存储 I/O 不应因为一个注解被包进长事务。AOP 适合横切能力，但业务状态机仍要在代码和数据库条件中显式表达。继续追问可讲代理对象类型、注解放在接口还是实现、异常被吞掉为何不回滚，以及异步方法为何失去原事务。

多切面同时存在时还要考虑顺序。鉴权应在副作用发生前，指标需要记录最终异常，重试与事务的嵌套决定每次 Attempt 是否重新开始事务。可以通过 `@Order` 或 Ordered 明确顺序，并用集成测试验证日志与提交行为。AOP 不适合隐藏关键业务分支，否则读代码时无法看出一次调用会重试或写库多少次。

项目当前主要依赖 Spring 事务代理，没有把任务状态机藏进自定义 AOP。`WikiIngestTransactionExecutor.execute()` 单独成 Bean，就是为了让 `WikiIngestService` 的流程调用明确经过事务代理；`AnswerRunService`、`TaskService`、`WorkerTaskCallbackService` 等应用服务在公开方法上标记事务，外部 Kafka、ES 和 Worker 调用则留在事务之外。生产鉴权主要由 Servlet Filter 和 `WorkspaceAccessGuard` 显式执行，也不是靠一个看不见的切面完成。面试官问 JDK Proxy 与 CGLIB 时，可以先讲机制，再说明这些 Service 多为没有业务接口的具体类，Spring 通常需要类代理；真正应验证的是方法是否通过 Bean 调用、异常是否继续抛出、事务是否提交，而不是背代理类型。仓库目前也没有复杂的 `@Order` 切面链，因此不要虚构“鉴权、重试、事务三切面顺序已经设计完成”。

## 17. Spring Bean 生命周期、循环依赖与自动配置

Bean 创建通常经历实例化、属性填充、Aware 回调、BeanPostProcessor 前置处理、初始化方法、后置处理，销毁时执行对应回调。AOP 代理往往由后置处理器创建，因此注入到其他 Bean 的可能是代理对象。构造器注入能让依赖显式且便于测试，也能在启动时暴露循环依赖。Setter 或字段注入形成的部分单例循环依赖在某些情况下可通过三级缓存提前暴露引用，但涉及构造器循环、Prototype 或代理时仍可能失败。即使框架能绕过，循环依赖通常说明职责边界需要调整。

Spring Boot 自动配置通过条件注解根据 Classpath、Bean、属性和环境创建默认组件，用户自定义 Bean 可以使默认配置退让。排查“为什么某个 Bean 没创建”时，要看 Condition Evaluation Report、配置绑定、Profile 和 Bean 名称，而不是只加扫描路径。`@ConfigurationProperties` 适合有层级且需要校验的配置，密钥不应写入仓库默认值；生产配置还要通过启动 Guard 拒绝弱密钥、非 TLS 和危险回退。

NoteWeave 将实时执行器、调度器和普通请求线程池分开配置，原因是不同工作负载的队列、拒绝和关闭语义不同。把所有 Executor 交给一个默认 Bean 会让长连接、Provider I/O 和后台调度互相挤占。面试官继续追问时，应说明 Bean Scope、`@PostConstruct` 时机、优雅关闭、配置优先级和条件装配测试。不要把“能启动”当成配置正确，错误的 Profile 或默认密钥可能只在生产环境暴露。

Singleton 表示同一个 ApplicationContext 内通常只有一个 Bean 实例，不等于 JVM 全局单例，也不自动线程安全；Request Scope 只适合 Web 请求生命周期，异步任务离开请求后不能继续依赖。优雅关闭时先停止接新任务，再等待在途任务、续租或保存 Checkpoint，最后释放线程池和连接。Destroy 回调有时间预算，不能把所有恢复逻辑只放在进程退出钩子中。

项目启动和关闭都有具体落点。`NoteWeaveProperties` 通过 `@ConfigurationProperties(prefix = "noteweave")` 绑定层级配置，`ProductionConfigurationGuard.validateProductionConfiguration()` 在 `@PostConstruct` 阶段检查生产环境弱密钥、本地用户回退、QA MySQL 回退、Quota 本地回退、非 TLS Worker、Kafka、MinIO 与 ES 配置，违规时直接阻止应用启动。`MinioObjectStorage` 和 `InternalServiceAuthFilter` 也在初始化阶段校验依赖；`AuthBootstrapInitializer` 用 `ApplicationRunner` 处理启动后的认证初始化。关闭时，Answer I/O 与 SSE Dispatch 分别最多等待 20 秒和 10 秒，SSE Connection 与 Redis Bridge 不等待；两个 ScheduledExecutorService 通过 Bean 的 `destroyMethod = "shutdown"` 关闭。不同设置反映了业务语义：短任务尽量排空，长连接不能无限拖住停机。若任务数量或长连接规模增加，再把 Drain、超时和连接迁移纳入部署验证，面试时把 Bean 生命周期与部署编排边界分开。

## 18. Java 高频题如何回答到项目层

常见八股的第一层是准确说明机制，第二层解释它解决什么问题和有什么限制，第三层落到项目中的真实选择。问线程池时，不只背七个参数，还要说明 Answer I/O、SSE Dispatch、Connection 与 Redis Bridge 为什么隔离；问 volatile 时，要说明它提供可见性和有序性但不提供复合操作原子性；问 CAS 时，要继续讲 ABA、版本号和数据库 Fencing；问 GC 时，要把停顿和 Lease Heartbeat、长尾延迟关联起来。

回答中应避免两种极端。一种只背源码细节，却说不出为什么业务需要；另一种只讲项目名词，却解释不了底层机制。较稳的三分钟结构是：用二十秒给定义和适用场景，用一分钟讲数据结构或执行流程，用一分钟讲 NoteWeave 的使用与失败窗口，最后用四十秒讲替代方案、验证方法和当前边界。面试官打断后，可以直接进入集合扩容、代理调用、锁升级、GC 日志或线程 Dump 等细节。

复习时可以做双向抽查。从“Agent 旧 Worker 晚到”反推 JMM、CAS、Lease 和 Fencing，也可以从“volatile 不保证复合原子性”正向找到状态机条件更新。每题准备一个错误方案和一个验证手段，能明显减少背诵感。源码版本相关细节要注明 Java 版本，避免把 Java 7 的 HashMap 或早期 ConcurrentHashMap 实现当作当前答案。

## 19. Java 基础母题长回答

### 19.1 JMM、锁、CAS 和线程池如何串成一个完整回答

Java 内存模型定义线程怎样通过主内存交互，以及哪些操作之间存在 Happens-before。volatile 写对后续同一变量的读可见，并限制相关重排序，但 `count++` 仍是读、计算、写三个步骤，不能因此获得复合原子性。synchronized 同时提供互斥、可见性和可重入语义，Lock 在此基础上提供可中断、超时、公平策略和多个 Condition。AQS 用一个同步状态、CAS 和等待队列支持 ReentrantLock、Semaphore、CountDownLatch 等同步器。

CAS 比较内存值与期望值，相同才更新，适合短临界区和低冲突状态。竞争激烈时会自旋浪费 CPU，也有 ABA 问题。版本号、StampedReference 或数据库 Epoch 能区分“值看似回来但中间发生过变化”。NoteWeave 的 Lease 与 Fencing 把这个思想扩展到跨进程：旧 Worker 即使在 GC Pause 后恢复，也必须带原 Token 或 Epoch 做条件写，新 Owner 已接管时更新影响行数为零。

线程池把任务提交与执行资源解耦，核心参数包括 Core、Max、KeepAlive、Queue、ThreadFactory 和 RejectionHandler。无界队列会让 MaxPoolSize 失去扩容意义并把过载变成内存和延迟问题；零队列要求立即移交，适合不允许排队的资源；CallerRuns 会把压力传回调用线程，Abort 会显式失败。NoteWeave 分离 Answer I/O、SSE Dispatch、Connection 和 Redis Bridge，是为了让长连接、网络等待和事件派发互不占满线程池，但数据库与 Provider 仍是共享瓶颈。

面试官继续追问时，可以说明线程数由服务时间、等待比例、下游并发与内存共同决定。测试要构造高竞争 CAS、队列满、拒绝、取消、异常和旧 Owner 晚到。指标看 Active、Queue、Oldest Age、Rejected、任务 P95 与依赖饱和度。回答的 Trade-off 是：细分线程池提高隔离，但增加容量配置和资源总量；CAS 减少阻塞，但高竞争与复杂状态更适合显式锁或数据库条件更新。

### 19.2 JVM 内存、OOM 与 GC 怎样排查

JVM Heap 保存大多数对象，线程栈保存局部变量、调用帧和操作数栈，Metaspace 保存类元数据，Code Cache 保存 JIT 代码，Direct Buffer、线程栈和本地库属于 Native 内存。`-Xmx` 只限制 Heap，不是进程总内存。常见 OOM 要按错误类型区分：Heap 空间不足、GC 回收低效、Metaspace 墾殖、Direct Buffer 耗尽、无法创建 Native Thread，以及容器直接 OOMKill。

G1 把 Heap 划为 Region，通过并发标记和 Mixed GC 优先回收收益较高的区域。停顿目标是调优目标，不是 SLA。分配速率、大对象、跨 Region 引用、堆大小和并发标记来不及都可能造成长尾。排查时先保留 GC Log、Heap Dump、Native Memory Tracking、容器事件和负载时间线。Heap Dump 用 Retained Size、Dominator Tree 和 GC Root 引用链找缓存、ThreadLocal、队列或监听器；进程 RSS 高但 Heap 正常时继续看 Native、线程数和 Page Cache。

NoteWeave 的 SSE Replay Buffer、Subscriber Queue、文档批次和 Context 都必须有界。测试中出现过 G1 Native `mmap` 失败，说明测试 Fork、容器依赖和系统内存也是交付容量的一部分。GC Pause 还会暂停 Heartbeat，Lease 到期后旧 Worker 恢复必须被 Fencing 拒绝。调大 Heap 可能减少 GC 频率，却增加最坏停顿并挤压 Native 空间；缩小队列会增加拒绝。修复后应用相同工作负载验证 Old Gen 平台期、峰值后回落、GC P99、任务成功率和 Lease 冲突。

### 19.3 Spring 事务为什么会失效，边界怎样设计

`@Transactional` 通常由 Spring AOP 代理实现，调用必须经过代理才能进入拦截器。同一个 Bean 内 `this.inner()` 自调用、Private 方法或对象不是 Spring Bean 时，注解可能不生效。Runtime Exception 默认触发回滚，Checked Exception 需要按异常体系明确 `rollbackFor`；业务代码捕获异常后不再抛出，代理看不到失败，也可能提交。异步线程不会继承原事务，事务上下文不能靠 ThreadLocal 跨线程延长。

REQUIRED 加入当前事务或创建新事务；REQUIRES_NEW 挂起外层并独立提交；NESTED 依赖 Savepoint。若 Outbox 使用 REQUIRES_NEW，外层业务回滚而发布意图已提交，会制造幽灵消息。正确边界是业务状态与 Outbox 在一个短本地事务中提交，Kafka、MinIO、ES 和模型调用放在事务外，通过 Claim、重试、幂等和条件确认处理不确定性。长事务会占连接、持锁并保留 Undo。

多切面的嵌套也影响语义。重试包住事务可以让每次 Attempt 使用新事务，事务包住重试可能在第一次异常后就把同一事务标记为 Rollback-only。鉴权应在副作用前，指标需要观察最终结果。NoteWeave 的应用服务应显式表达状态机，不让 AOP 隐藏关键业务分支。测试要覆盖自调用、Checked Exception、异常被捕获、重复回调、业务提交后发布失败和外部调用超时。

事务测试不能只用 Mock Repository，因为代理、传播和数据库锁语义只有在 Spring Context 与真实数据库中才成立。可以先写入业务状态和 Outbox，再让事务抛异常，断言两者都不可见；也要验证 REQUIRES_NEW 的独立提交确实符合预期。日志中记录 TransactionId 或业务 ID，不记录敏感 Payload，方便把回滚与重试串起来。

### 19.4 TCP、HTTP、DNS 与 TLS 如何共同影响一次调用

一次 HTTP 调用可能经历 DNS 解析、TCP 连接、TLS 握手、连接池获取、请求写入、服务端排队、业务执行和响应读取。TCP 保证有序可靠字节流，不保证业务事务成功；HTTP Keep-alive 与连接池减少握手成本，却会占用文件描述符和下游连接。TLS 验证证书链、有效期和 Hostname，再协商会话密钥，关闭验证会让加密失去身份保证。

超时必须分层：DNS、Connect、TLS、Pool Acquire、Read、Write、单次 Attempt 和总 Deadline。外层预算应大于内层正常路径，又要限制全部重试之和。无限重试、各层分别重试和没有 Jitter 会形成风暴。Provider 已完成但响应丢失时，客户端看到 Read Timeout，不能据此断言业务未执行，具有副作用的 Callback 和 Writeback 必须用幂等键或 Receipt 查询。

DNS 与 SSRF 还涉及安全。只校验 URL 字符串不足以阻止 DNS Rebinding、重定向和内网地址表示，连接前要验证最终解析地址、协议、端口和目标网络，并限制响应大小与超时。Research Fetch 的出站防护不能推广为所有 LLM Egress 都相同。故障注入应覆盖解析慢、连接拒绝、TLS 错误、首字节慢、中途断流和重复响应，指标把 Connect、TTFB、Read 与业务结果分开。

连接池还可能复用已经被中间设备关闭的空闲连接，第一次读写才发现 Reset；合理的空闲探测、连接寿命和重试可以处理，但有副作用请求仍需幂等。面试官问 HTTP/2 时，可说明多路复用减少应用层队头阻塞，TCP 丢包仍影响同一连接；HTTP/3 用 QUIC 把 Stream 丢包隔离，但部署复杂度更高。当前项目不需要为了回答八股声称已使用这些协议。

### 19.5 Java 服务排障怎样形成证据链

CPU 高先确认容器配额、User/System/I/O Wait，再用多次线程 Dump、JFR 或火焰图定位死循环、正则热点、重试或 GC；内存高区分 Heap、Native、Direct Buffer、线程栈和 Page Cache；线程数高检查阻塞栈与线程池创建点；接口慢拆排队、连接池、SQL、Provider、流式发送；连接池 Pending 高继续查长事务、锁等待与慢 SQL。一次采样只能形成假设，不能直接下结论。

完整过程包括影响范围、时间线、版本切片、止损、假设、证据、根因、修复和验证。重启只清除现场，不是根因；调大池和 Heap 可能延后故障；关闭校验让延迟变快可能损害正确性。NoteWeave 排障要用 RunId、TaskId、Attempt、SnapshotVersion 串联 HTTP、Outbox、Kafka、Worker 和 Callback，并关联用户成功率、队列年龄、GC、数据库和 Provider 指标。

面试时最好给出一个反证过程，例如最初怀疑 GC，但 GC Pause 正常，线程 Dump 显示所有 Answer I/O 线程等待同一 Provider，进一步发现重试叠乘。修复后用原工作负载重放，检查 P99、错误率、结果质量、单位成本和恢复行为，并补自动化故障测试、告警与 Runbook。没有真实生产事故时明确说是开发、压测或审查案例。

证据应保留可比较的时间窗口和版本。火焰图、Dump、Trace 与配置快照都标注采集时间，避免拿故障后的状态解释故障前原因。若临时止损改变了流量或并发，后续复现要还原这些条件。最终复盘区分触发因素、根本缺陷和放大因素，分别给出修复 Owner 与验证门禁。

## 20. Java 八股中的项目真实设置

### 20.1 线程池参数不再只背七个参数

Answer I/O 当前为 Core 4、Max 16、Queue 64，等待关闭最多 20 秒并传播 MDC；SSE Dispatch 为 `2/8/128`，等待关闭 10 秒；SSE Connection 为 `4/32/0`，核心线程允许 60 秒超时且关闭不等待；Redis Bridge 为 `2/16/0`，零队列是因为阻塞 XREAD 需要先扩线程；Artifact Dispatch 为 `1/1/0`。所有 ThreadPoolTaskExecutor 使用 AbortPolicy。

这些数字说明工作负载被隔离且过载显式化。Answer I/O 的 16 个线程可能争用 Hikari 20 个连接、Quota 并发 4 或 LLM Provider，所以不能单池计算 QPS。调优时要同时报告 Active、Queue、Rejected、数据库 Pending、TTFT 和 Provider 429。

### 20.2 JVM、Lease 与长尾的真实关系

Answer Stream Lease 是 150 秒，每 45 秒续租；Research Task Lease 是 60 秒，Compose Heartbeat 15 秒、失败预算 30 秒、请求超时 5 秒。Research 校验器要求 Heartbeat 小于 Lease 三分之一，失败预算加一个 Heartbeat 仍小于 Lease。GC 或进程暂停超过窗口时，旧 Owner 恢复不能确认结果，必须由 Stream Owner、Lease Epoch 或 Fencing Token 拒绝。

### 20.3 网络超时的真实预算

Backend 调 Artifact Worker 的 Connect Timeout 为 3 秒、Read Timeout 为 30 秒；Redis 为 500/750 ms；Embedding Query 10 秒、Batch 60 秒；Rerank 20 秒；Backend、Research 与 Artifact LLM 默认 60 秒；Kafka 同步 Publish 最多等待 10 秒。Attempts 多数为 3，但不能把每层次数相乘后当成合理总预算。调用链仍需要统一 Deadline 和取消传播。

完整源码位置与参数关系见[项目真实配置参数与容量口径](15-项目真实配置参数与容量口径.md)。

## 21. 源码与测试证据索引

这张表用于面试前反向抽查。只要说到“项目里用了”，至少能继续回答生产类、核心方法、真实参数或测试证据中的两项。

| 知识点 | 生产代码落点 | 核心方法或设置 | 主要验证证据 |
|---|---|---|---|
| CAS、Lease、Fencing | `ResearchAgentTaskService`、`DurableOutboxDispatcher` | `claimTask()`、`heartbeat()`、`claim()`、`acknowledgeTaskMessage()` | `ResearchAgentTaskServiceTest`、`DurableOutboxDispatcherTest` |
| synchronized 与局部锁 | `SessionEventMux`、`WorkloadQuotaService` | `synchronized (channel)`、`synchronized (subscriber)`、`localRateDecision()` | `SessionEventMuxTest`、`WorkloadQuotaServiceTest` |
| 线程池与拒绝 | `RealtimeExecutorConfig`、`SchedulingConfiguration` | `4/16/64`、`2/8/128`、`4/32/0`、`2/16/0`、`AbortPolicy` | 配置绑定测试、事件队列和拒绝指标 |
| MDC 与 ThreadLocal | `RealtimeExecutorConfig`、`ResearchAgentCompletionMetrics` | `TaskDecorator`、`clearTransaction()` | 异常路径清理测试、回滚 Stage 指标 |
| JVM 内存边界 | `SessionEventMux`、`ConversationEventMux`、Embedding 配置 | Replay 512、Subscriber Queue 64、Batch 32、Input 12000 | 有界队列测试、Heap Dump 与 GC Log |
| Spring 事务代理 | `WikiIngestTransactionExecutor`、`SegmentSummaryPromotionService` | `execute()`、`noRollbackFor` | `WikiIngestFailureTransactionTest` |
| HTTP 超时与重试 | `ArtifactWorkerRestClientFactory`、`OpenAiCompatibleEmbeddingClient`、`OpenAiCompatibleRerankClient` | Artifact 控制面 Connect 3 秒、Read 30 秒；Provider Connect 10 秒、Attempt 3 | `HttpArtifactWorkerControlClientTest`、Embedding 和 Rerank Client Test |
| DNS、TLS、SSRF | Research Worker `fetch_adapters.py`、`ResearchExternalSnapshotArchiveService` | `getaddrinfo()`、IP Pinning、逐跳 Redirect 校验、`validate()` | `test_fetch_adapters.py`、`ResearchExternalSnapshotArchiveServiceTest` |
| 集合与复合原子操作 | `WorkloadQuotaService`、`SessionEventMux` | `computeIfAbsent()`、`compute()`、局部锁、Redis Lua | `WorkloadQuotaServiceTest`、`WorkloadQuotaRedisIntegrationTest` |
| Record 与缓存 Key | `MemoryCompiledPackCache.CacheKey` | 六字段 Key、Envelope Key 二次相等校验 | `MemoryCompiledPackCacheTest` |
| Bean 生命周期与启动门禁 | `ProductionConfigurationGuard`、`AuthBootstrapInitializer` | `@PostConstruct`、`ApplicationRunner`、Executor Shutdown | `ProductionConfigurationGuardTest` |
| 排障指标 | `OperationalMetricsBinder` | Outbox 状态、Oldest Ready Age、Source 和 Projection 状态 | `OperationalMetricsBinderTest` |

## 当前运行基线

Backend 编译目标是 Java 17，Spring Boot 3.3.5 使用 JDBC、线程池和 `SseEmitter`；回答 SSE 默认连接超时 120 秒，连接被拒绝时必须转为可解释终态事件。Research/Artifact worker 是 Python 3.12，网络安全和 DNS 固定主要在 Research Worker fetch adapter 与 Java 归档校验两端完成。不要把 JVM 线程池参数或单机测试当成生产容量。

## 22. Java 基础如何串成项目链路的 4 到 5 分钟回答

如果面试官从 Java 基础追问到项目，我会沿“请求进入、事务提交、异步执行、事件交付”这条线讲，而不是把术语逐个背出来。HTTP 请求进入 Spring Controller 后，参数校验和身份过滤在边界完成，Service 负责 Workspace ACL、状态机、幂等和事务，JDBC DAO 用条件更新和受影响行数表达并发仲裁。事务提交后，Outbox 或 Task 把工作交给 Kafka 和 Worker，SSE 连接只负责传输持久化事件。这样可以把 JVM 内的线程安全、数据库的 CAS、跨进程的 Lease 和浏览器的重连放在同一张因果图里。

Java 内存模型解决的是同一 JVM 内的可见性和重排，不解决多实例业务一致性。`volatile` 能让状态写入及时可见，但不能让 `count++` 变成原子操作；CAS 和 `AtomicInteger` 适合进程内计数，MySQL 的条件更新则像跨实例的业务 CAS，必须带状态、Attempt、Lease Epoch 或 Fencing Token。项目不会用 `synchronized` 保护所有 Service，因为它只能覆盖单 JVM，Outbox Claim、配额和任务所有权仍交给 MySQL 或 Redis。旧 Owner 即使读到相同 Task ID，也会因为代际 Token 不匹配而被拒绝，这正是防止 ABA 和晚到写回的关键。

线程池也要按等待类型隔离。回答生成的阻塞 I/O、SSE 连接、事件 Dispatch、Redis Bridge 和后台 Outbox 不能共享一个无界公共池，否则长连接或外部 Provider 会挤占其他模块。队列有界、拒绝策略显式，线程数还要受 Hikari 连接池、Provider 并发、内存和下游速率限制约束。`CompletableFuture` 如果不指定 Executor 可能混入公共 ForkJoinPool，当前项目更主要使用注入 Executor、Kafka Consumer、Scheduler 和 Worker callback，所以回答时不能把不存在的异步抽象说成核心实现。MDC 可以传播 Trace，但用户身份不能只依赖 ThreadLocal，跨请求和 Worker 回调仍要用稳定 Token、Task 和 Fencing 重新认证。

网络问题要按超时预算拆分。DNS、TCP 建连、TLS 握手、HTTP 读取、Kafka Poll、Provider 生成、数据库连接和 SSE 交付各自占用时间，单一的“接口超时”无法解释长尾。当前 Artifact 用 4200 秒 Max Poll 和 70 分钟 Delivery Lease 覆盖小时级任务；推荐改为 Kafka 快速登记 Durable Execution，长任务 Deadline、Heartbeat 和 Fencing 全部属于 Execution Lease。SSE 断线靠 Last-Event-ID 回放而不是重跑回答。SSRF 防护还要处理私网地址、DNS Rebinding、重定向和跨端口凭证。

JVM 排障同样要结合业务。Heap 正常而进程 RSS 增长，可能是线程栈、Direct Memory 或 Native Allocation；Replay Queue、Subscriber Queue、Embedding Batch 和线程池队列有界，能够限制业务对象堆积，但 Backend 的生产 Heap 和 GC 参数仍需按部署环境验证。当前 Java 17、Spring Boot 3.3.5、Hikari、Kafka、Redis 和 SSE 的配置是保护基线，不是压测结论。面试回答的重点是知道哪一层负责什么，知道哪些机制只在进程内有效，并能说明测试和指标如何证明，而不是把“用了并发”和“用了线程池”当成结论。

## 23. 必须独立讲三分钟的 Java 知识点

| B 档知识点 | 三分钟主回答入口 | 一阶项目落点 | 二阶追问 |
| --- | --- | --- | --- |
| JMM、CAS 与 Fencing | 2、19.1 | Research Heartbeat、Outbox Claim | 为什么 `volatile` 不能保护多实例，数据库 CAS 与 CPU CAS 有什么不同，ABA 如何处理 |
| 线程池、隔离与背压 | 4、19.1、20.1 | Answer I/O、SSE、Redis Bridge | 零队列为何适合阻塞 Pump，AbortPolicy 后业务返回什么，线程数为何受 Hikari 和 Provider 限制 |
| JVM 内存、GC 与 Lease | 6、7、19.2、20.2 | 有界 Replay、Subscriber、Heartbeat | Heap 正常但 RSS 上升查什么，STW 超过 Lease 后如何保证正确性，调大 Heap 有什么反作用 |
| Spring 事务与 Outbox | 8、19.3 | Wiki 事务执行器、业务状态与 Outbox | 自调用为何失效，`REQUIRES_NEW` 为什么会制造幽灵消息，外部 I/O 为什么不进长事务 |
| HTTP Timeout 与 Unknown Outcome | 9、19.4、20.3 | Provider、Artifact 控制面、Callback | Read Timeout 后能否重试，Deadline 怎样向下传播，TCP 成功为何不等于业务成功 |
| SSRF、DNS 与 TLS | 10、19.4 | Research Fetch Adapter | DNS Rebinding 怎么防，Redirect 为什么重新校验，为什么不能宣称所有出站链路都已沙箱化 |

每个 B 档回答按“原理、项目反例、当前实现、失败窗口、验证与边界”组织，正常语速约三分钟。HashMap、泛型、Bean 生命周期等问题如果没有继续进入并发、安全或性能，只保留 30 到 90 秒速查，不重复完整项目故事。

### 二阶回答示例：数据库 CAS 与 CPU CAS 是不是同一回事

两者都包含“只有当前值仍等于期望值才更新”的思想，但实现层不同。CPU CAS 是单机共享内存上的原子指令，可能有自旋、ABA 和缓存一致性成本；数据库条件更新由 SQL、事务、索引和行锁完成，跨进程生效，还会受到隔离级别、锁等待和提交失败影响。NoteWeave 用 `status + attempt + owner/token` 组成期望状态，更新影响行数为零表示所有权或代际已经变化。它避免旧 Worker 覆盖新状态，却不能撤销旧 Worker 已经完成的外部副作用，因此还需要幂等键、Receipt 或对账。
