# OTH-12 最小方案独立二次审查

基线 `70631bd015067b19f9d55aaec92d7429751dc936`。只读审查，不实现、不运行测试、不操作浏览器或线上服务。已读取[主审报告](<audit-main-outbox-retrieval.md#L48-L68>)并独立追踪候选、回填、最终证据资格和前端入口。

## 结论

**同意问题成立；不建议直接按原4类文件范围实施。** 最小安全方案应分两层：

1. **现有INDEXED资料的单通道故障降级**：保留成功的keyword/vector/metadata通道与明确原因，所有通道不可用且本地回退关闭仍明确失败。保持生产guard，不等于启用生产数据库回退。
2. **显式允许的本地PARSED/DISABLED读取**：这是已有本地回退能力的补全，只在对应模式开关允许并实际进入本地路径时使用。候选、Note元数据/正文窗口、最终证据资格必须使用同一受控策略；不能在共享SQL里无条件`OR DISABLED`。

新增独立核实的重要事实：

- [最终身份适配器:51–68](<../../../../../backend/src/main/java/com/noteweave/infra/JdbcEvidenceOwnershipAdapter.java#L51-L68>)仍强制INDEXED/PROJECTED；[Orchestrator:87–99](<../../../../../backend/src/main/java/com/noteweave/answer/strategy/RetrievalOrchestrator.java#L87-L99>)对QA和Note实际调用该守卫。只改候选/窗口将导致合法本地证据最终被`EVIDENCE_SCOPE_VIOLATION`拒绝。
- [现有port:9–17](<../../../../../backend/src/main/java/com/noteweave/answer/strategy/EvidenceOwnershipPort.java#L9-L17>)只有workspace和identity，无AnswerMode、读取策略。不能在适配器简单`qaFlag || noteFlag`，否则只开Note开关也放宽QA资格。必须带可信的模式限定策略，旧接口保持INDEXED_ONLY默认。
- [前端可检索判断:34–41](<../../../../../frontend/src/features/sources/model.ts#L34-L41>)和[QA发送守卫:98–114](<../../../../../frontend/src/features/conversations/ChatWorkbench.tsx#L98-L114>)把DISABLED视为仅可阅读并禁用QA发送。因此后端局部通过不等于完整用户流程可用；前端不能仅靠DISABLED状态猜测服务端允许本地回退。
- [生成来源gate:23–45](<../../../../../backend/src/main/java/com/noteweave/research/ResearchGeneratedSourceReadGate.java#L23-L45>)是来源和冻结上下文可读性校验，不是research质量验收器。本地路径必须调用同一gate，不修改其研究语义。

## 1. 事实与策略边界

### 1.1 源状态的真实产生方式

[SourceParseService:184–214](<../../../../../backend/src/main/java/com/noteweave/source/SourceParseService.java#L184-L214>)在projection关闭时写：

- source：`status=READY, parse_status=PARSED, index_status=DISABLED`。
- source_snapshot：`parse_status=PARSED, index_status=DISABLED, processing_stage=READY`。
- source_chunk：`projection_status=NOT_PROJECTED`。
- task：完成阶段`PARSED_LOCAL_ONLY`，描述仅本地可读、未写检索投影。

这组状态足以区分已解析本地资料与失败/处理中的资料，但**DISABLED单列不构成授权或可读资格**。不应把它改写成INDEXED，也不应伪造PROJECTED。

### 1.2 默认/生产约束必须保持

[QA构造参数:58–70](<../../../../../backend/src/main/java/com/noteweave/chat/QaPassageRetriever.java#L58-L70>)、[Note构造参数:49–70](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRecallRetriever.java#L49-L70>)默认false；[生产guard:88–101、129–133](<../../../../../backend/src/main/java/com/noteweave/config/ProductionConfigurationGuard.java#L88-L133>)与[229–230](<../../../../../backend/src/main/java/com/noteweave/config/ProductionConfigurationGuard.java#L229-L230>)禁止生产开启。不能为证明OTH-12可用修改guard、默认值、线上环境或环境身份。

## 2. 具体允许矩阵

所有允许项还必须同时满足：当前请求有工作区读取权限；source/chunk/workspace相互一致；snapshot确属该source；QA显式范围不能扩大；生成来源gate允许；正文不为空且符合既有相关性/证据预算；不把任意资料标题或元数据命中视为已读正文。

`I`＝INDEXED_ONLY，`L-QA`＝仅QA配置授权的本地回退，`L-NOTE`＝仅Note配置授权的本地回退。策略由服务端配置与真实执行路径决定，客户端过滤字段/模型输出不可授予。

| source状态 | snapshot状态与版本 | chunk状态 | I索引通道 | L-QA/L-NOTE数据库路径 |
|---|---|---|---|---|
| READY/PARSED/INDEXED | 同源、当前允许快照、PARSED/INDEXED | PROJECTED | 允许；仍校验索引hit的source+snapshot+chunk | 允许，用于ES运行期故障后的本地回退，标明降级 |
| READY/PARSED/DISABLED | **全状态中的最新版本**、PARSED/DISABLED、READY | NOT_PROJECTED | 拒绝 | 对应模式显式授权且实际本地路径才允许；标记local-only |
| READY/PARSED/DISABLED | 旧DISABLED版本，新版本为PENDING/FAILED/INDEXING等 | 任意 | 拒绝 | 拒绝，不能取max(DISABLED)绕过新版本 |
| READY/PARSED/INDEXED | 旧INDEXED版本但存在更新未合格快照 | PROJECTED | 当前代码选择max(INDEXED)，见下方兼容说明 | 新增L路径拒绝旧版；不能分别取旧INDEXED与新DISABLED并混读 |
| READY/PARSED/DISABLED | snapshot=INDEXED或chunk=PROJECTED等不一致混合状态 | 任意 | 拒绝本地解释 | 拒绝，不能把互不一致状态视为修复完成 |
| READY但parse非PARSED | 任意 | 任意 | 不新增允许 | 拒绝 |
| PROCESSING/PENDING/FAILED/DELETED或其他非READY | 任意 | 任意 | 拒绝 | 拒绝；已有可见chunk不能授权 |
| READY/PARSED且index=PENDING/INDEXING/FAILED | 任意 | 任意 | 拒绝 | 拒绝；索引失败不是DISABLED本地模式 |
| 任何状态 | snapshot不存在、错source、错workspace、chunk不归该snapshot | 任意 | 拒绝 | 拒绝 |
| 任何状态 | 合格snapshot但无正文chunk/无Note window | 无可用正文 | 不能产正文证据 | 可以保留已授权元数据诊断，但不伪造可读窗口/成功全文召回 |

**最新版本兼容说明**：当前[QA SQL:269–278](<../../../../../backend/src/main/java/com/noteweave/chat/QaPassageRetriever.java#L269-L278>)和[Hydrator:53–61](<../../../../../backend/src/main/java/com/noteweave/chat/RetrievalHydrator.java#L53-L61>)定义的是最新INDEXED，不是全状态最新。新增local路径必须采用全状态max(version_no)，避免新资料状态不合格时回读旧本地正文。是否将现有生产索引路径也统一为全状态最新属于已有行为变化：建议主agent明确批准后同一OTH-12实施，否则保留I路径兼容并记录旧索引路径尚未满足更强口径，不能悄悄声称已统一。绝不能用`max(version_no where index_status in (...))`冒充最新版本。

### 2.1 开关/故障矩阵

| 环境与能力 | 结果 |
|---|---|
| prod且任一local开关true | 保持启动guard拒绝，不能自动降级到本地 |
| 对应模式local=false，至少一个索引通道成功 | 可以返回该通道合格证据+通道失败原因；不读DISABLED |
| 对应模式local=false，全部必需索引通道不可用 | 保留明确provider不可用错误；不能空数组伪成功 |
| 对应模式local=true，索引不可用 | 进入对应L策略，可读合格INDEXED及合格最新DISABLED本地数据；仍保留所有资格过滤 |
| 仅Note local=true，QA=false | Note允许L-NOTE；QA仍I，不得因共享适配器OR两个开关放宽 |
| 索引调用成功但零相关结果 | 零结果与provider失败分开；不自动改变本地策略。QA当前hybrid正常空结果直接返回，保留这一行为，除非主agent明确批准扩大回退触发条件 |
| local-only资料、索引通道健康但不包含它 | 仅开启fallback开关不代表必须混合全本地语料；是否同时检索local-only是产品口径，最小修复不自动扩大 |

### 2.2 生成来源矩阵

| 生成来源 | 当前gate行为 | 本方案要求 |
|---|---|---|
| 非research_agent（普通上传/其他生成来源） | [gate:24](<../../../../../backend/src/main/java/com/noteweave/research/ResearchGeneratedSourceReadGate.java#L24>)直接true | 仍须状态/归属/当前版本/权限矩阵；不新增artifact等来源特许 |
| research_agent，ref为空/不存在或不属工作区 | [gate:25–38](<../../../../../backend/src/main/java/com/noteweave/research/ResearchGeneratedSourceReadGate.java#L25-L38>)抛ORIGIN_INVALID | 不返回；BusinessException不能被catch-all转换成普通ES故障后绕过 |
| research_agent，run存在，context_snapshot_id=null | [gate:40](<../../../../../backend/src/main/java/com/noteweave/research/ResearchGeneratedSourceReadGate.java#L40>)允许兼容旧来源 | 保留现有行为，不擅自新增run状态/质量规则；仍须状态矩阵 |
| research_agent，replay_availability=METADATA_ONLY | [gate:41–43](<../../../../../backend/src/main/java/com/noteweave/research/ResearchGeneratedSourceReadGate.java#L41-L43>)false | 排除，不将元数据保留解释成正文可用 |
| research_agent，context存在且不为METADATA_ONLY | [gate:44–45](<../../../../../backend/src/main/java/com/noteweave/research/ResearchGeneratedSourceReadGate.java#L44-L45>)调用contextGate | 必须成功；失效/撤回/删除导致gate失败时拒绝。不得改为null gate或直接放行 |

保存新research报告的入口[ResearchArtifactService:71–85](<../../../../../backend/src/main/java/com/noteweave/research/ResearchArtifactService.java#L71-L85>)要求COMPLETED和非空报告，但读取gate并不重查质量/状态。这里只保留已有契约，不把它描述为所有未合格research产物均已静态证明可拦截；更强质量要求交RES负责人，不扩展本任务。

## 3. 调用链与最小设计约束

### 3.1 通道保留

[QA:74–85](<../../../../../backend/src/main/java/com/noteweave/chat/QaHybridRetriever.java#L74-L85>)先embedding/vector再keyword，前者失败使后者未尝试；[Note:84–119](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRecallRetriever.java#L84-L119>)任一路失败清空两路。应各自捕获**provider/网络故障**，保留成功结果；权限、来源gate、版本守卫BusinessException仍向上失败。失败通道无score不伪造score，保留原RRF权重/相关性/预算，日志仅受限原因码。

### 3.2 Note不是改候选SQL就结束

- [候选回填:127](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRecallRetriever.java#L127>)与[扩展回填:449–486](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRecallRetriever.java#L449-L486>)必须使用同一策略。tags/journal/共引只产生候选ID，不能跳过最终资格。
- [NoteEvidenceRetriever:52–85](<../../../../../backend/src/main/java/com/noteweave/chat/NoteEvidenceRetriever.java#L52-L85>)先recall，再metadata，再reading，再序列化快照。应由recall结果携带服务端选择的只读策略/快照身份，后续显式传递；不要ThreadLocal或单例可变布尔状态。
- [NoteReadingRetriever:76–106](<../../../../../backend/src/main/java/com/noteweave/chat/NoteReadingRetriever.java#L76-L106>)窗口资格必须接受同策略；其[181–251](<../../../../../backend/src/main/java/com/noteweave/chat/NoteReadingRetriever.java#L181-L251>)本来已有窗口semantic/rerank的有原因回退，保留这些行为。
- [NoteRetrievalService:57–86](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRetrievalService.java#L57-L86>)元数据读取依赖windows与stats。stats不应单独扩大为不校验生成来源的旁路，也不能把DISABLED显示成INDEXED。
- [CandidateSource:118–137](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRetrievalService.java#L118-L137>)目前无snapshot身份。每次重新查最新只能保证单次查询最新，不能保证候选元数据、窗口和最终证据来自同一快照。最低限度应在新增本地路径携带snapshotId并在回填确认；若不做，只能声明并发快照一致性待验收，不能宣称该项解决。

### 3.3 最终守卫

[EvidenceScopeGuard:19–50](<../../../../../backend/src/main/java/com/noteweave/answer/strategy/EvidenceScopeGuard.java#L19-L50>)保持原样：显式scope只适用于QA；source/snapshot/passage/accessScope完整且一致。不能用fallback绕过。

[EvidenceOwnershipGuard:27–37](<../../../../../backend/src/main/java/com/noteweave/answer/strategy/EvidenceOwnershipGuard.java#L27-L37>)必须仍进行真实数据库验证。新增模式/读取策略只能从可信服务端执行信息传入，适配器自身再次校验模式开关，不能接受可伪造的证据metadata当授权。现有旧接口/测试构造器默认I；不可把`trustValidatedRetriever()`用于生产。

最终检查建议重新调用现有生成来源gate，防止候选取回后来源撤回仍被采用；这不会修改研究gate实现。即便如此，最终校验结束后的撤回仍有竞态，不声称无锁多查询实现具备完整快照隔离。用并发测试确定产品接受的CURRENT_AT_RETRIEVAL边界。

## 4. 唯一文件清单与所有权

建议**整个OTH-12后端由一个实现者串行拥有以下文件**，不是按QA/Note拆给多人。下表为推荐最小安全设计的去重清单；新增策略类型的具体名字由主agent登记，避免公共文件多人改。

### 4.1 后端生产代码（唯一负责人）

| 文件 | 必需改动/边界 |
|---|---|
| [QaHybridRetriever](<../../../../../backend/src/main/java/com/noteweave/chat/QaHybridRetriever.java>) | 主通道独立故障、可用通道保留和诊断；保持索引资格 |
| [QaPassageRetriever](<../../../../../backend/src/main/java/com/noteweave/chat/QaPassageRetriever.java>) | 受开关约束的L-QA候选分支、最新快照、降级原因 |
| [NoteRecallRepository](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRecallRepository.java>) | 候选、词项、标签、按ID回填统一策略及快照身份；旧入口默认I |
| [NoteRecallRetriever](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRecallRetriever.java>) | 通道独立处理、确定L-NOTE执行策略，并传给直接/扩展回填 |
| [RetrievalHydrator](<../../../../../backend/src/main/java/com/noteweave/chat/RetrievalHydrator.java>) | Note窗口/stats的策略与快照绑定；QA索引ownership/adjacent保持I，禁止共享全局OR |
| [NoteRetrievalService](<../../../../../backend/src/main/java/com/noteweave/chat/NoteRetrievalService.java>) | CandidateSource/NoteRecallPlan的受控策略或快照信息，metadata回填接收它；兼容序列化需检查 |
| [NoteReadingRetriever](<../../../../../backend/src/main/java/com/noteweave/chat/NoteReadingRetriever.java>) | 明确传递策略/快照给窗口hydration，不改变原semantic/rerank降级 |
| [NoteEvidenceRetriever](<../../../../../backend/src/main/java/com/noteweave/chat/NoteEvidenceRetriever.java>) | 把recall策略一致传递给metadata/reading；诊断和最终证据保留 |
| [EvidenceOwnershipPort](<../../../../../backend/src/main/java/com/noteweave/answer/strategy/EvidenceOwnershipPort.java>) | 增加可信模式/读取策略的验证入口，旧入口保持I；不可只全局OR开关 |
| [EvidenceOwnershipGuard](<../../../../../backend/src/main/java/com/noteweave/answer/strategy/EvidenceOwnershipGuard.java>) | 接受服务端模式/策略并转交适配器；保持全部身份必须合格 |
| [RetrievalOrchestrator](<../../../../../backend/src/main/java/com/noteweave/answer/strategy/RetrievalOrchestrator.java>) | 在实际plan.mode/执行策略下调用最终守卫，不捕获并吞安全异常 |
| [JdbcEvidenceOwnershipAdapter](<../../../../../backend/src/main/java/com/noteweave/infra/JdbcEvidenceOwnershipAdapter.java>) | 最终状态/快照/开关交叉检查与生成来源重新校验；KnowledgeVersion路径不扩大 |
| 新增受控读取策略类型，建议`retrieval/SourceReadPolicy.java` | 表达I/L-QA/L-NOTE及快照约束；服务端工厂生成、默认拒绝。是否需要集中SQL帮助方法由实现者决定，不引入全局静态配置 |

这里不是要求全部一定大改：若采用更窄实现能证明同样的模式隔离、回填一致和最终校验，可压缩辅助类型，但不得遗漏最终身份守卫。当前四文件方案不能证明这些不变量。

### 4.2 验收测试文件（同负责人；后集中执行）

- [QaHybridRetrieverChannelTest](<../../../../../backend/src/test/java/com/noteweave/chat/QaHybridRetrieverChannelTest.java>)、[QaPassageRetrieverTest](<../../../../../backend/src/test/java/com/noteweave/chat/QaPassageRetrieverTest.java>)。
- [NoteRecallRepositoryQueryTest](<../../../../../backend/src/test/java/com/noteweave/chat/NoteRecallRepositoryQueryTest.java>)、[NoteRecallRetrieverTest](<../../../../../backend/src/test/java/com/noteweave/chat/NoteRecallRetrieverTest.java>)。
- [RetrievalHydratorTest](<../../../../../backend/src/test/java/com/noteweave/chat/RetrievalHydratorTest.java>)、[NoteReadingRetrieverTest](<../../../../../backend/src/test/java/com/noteweave/chat/NoteReadingRetrieverTest.java>)、[NoteRetrievalServiceHydrationTest](<../../../../../backend/src/test/java/com/noteweave/chat/NoteRetrievalServiceHydrationTest.java>)。
- [JdbcEvidenceOwnershipAdapterTest](<../../../../../backend/src/test/java/com/noteweave/infra/JdbcEvidenceOwnershipAdapterTest.java>)、[RetrievalOrchestratorTest](<../../../../../backend/src/test/java/com/noteweave/answer/strategy/RetrievalOrchestratorTest.java>)。
- [ProductionConfigurationGuardTest](<../../../../../backend/src/test/java/com/noteweave/ProductionConfigurationGuardTest.java>)只补/保持禁止生产本地回退断言，不修改允许口径。
- 新增一次局部数据库集成测试，将候选→Note回填或QA→Orchestrator真实OwnershipAdapter串起来；已有[adapter测试:20–50](<../../../../../backend/src/test/java/com/noteweave/infra/JdbcEvidenceOwnershipAdapterTest.java#L20-L50>)只mock SQL检查，不能发现查询资格互相矛盾。

### 4.3 不修改及条件扩展

- 不修改[ProductionConfigurationGuard](<../../../../../backend/src/main/java/com/noteweave/config/ProductionConfigurationGuard.java>)、[ResearchGeneratedSourceReadGate](<../../../../../backend/src/main/java/com/noteweave/research/ResearchGeneratedSourceReadGate.java>)、[ResearchContextV2Gate](<../../../../../backend/src/main/java/com/noteweave/research/ResearchContextV2Gate.java>)实现；不修改环境/Compose/迁移/研究文件。
- [ChatService](<../../../../../backend/src/main/java/com/noteweave/chat/ChatService.java>)归OTH-01当前负责人，不并改；plan.mode可从Orchestrator已有参数取得，无需为这个理由占用ChatService。
- [前端model](<../../../../../frontend/src/features/sources/model.ts>)、[ChatWorkbench](<../../../../../frontend/src/features/conversations/ChatWorkbench.tsx>)、[ChatSourcesPane](<../../../../../frontend/src/features/conversations/ChatSourcesPane.tsx>)及对应测试不能先全局放开DISABLED。要完成本地QA浏览器入口，先明确服务端能力返回契约，再单独分配前后端能力接口文件；在这之前将该用户入口列OTH-15待验收/OTH-12能力契约待确认，不冒充完成。

## 5. 必需验收表

1. I路径保持原样；QA=false/Note=true和反向组合互不授权。
2. 最新DISABLED/PARSED/NOT_PROJECTED允许；旧版本、新版本PENDING/FAILED、错workspace/source/snapshot、非READY、parse未完成、状态混合均拒绝。
3. INDEXED后ES故障能用允许的本地数据；开关关闭仍明确失败；keyword成功vector失败和反向成功分别保留可用部分，双失败与成功零命中区分。
4. QA sourceScope精确过滤；Note不擅自支持当前禁止的显式scope；窗口/统计/邻接不跨snapshot，统计不泄露已撤回生成来源。
5. research_agent来源缺失、错workspace、METADATA_ONLY、context不可读仍拒绝；旧null context兼容不变；普通来源也经过完整状态约束。
6. 真实Orchestrator+OwnershipAdapter接受合格本地PASSAGE，拒绝伪造local策略/其他模式借权；生成来源gate异常不转为普通provider降级。
7. 候选后插入新snapshot、删除/撤回来源的并发交错；记录最终验证能保障到哪个时点，不以同线程mock声称并发通过。
8. 最终evidence/trace保留本地回退与失败通道原因，空结果不报完整召回；metadata、windowCount、真实窗口一致。
9. production guard回归；前端QA入口待服务端能力契约确定后单独验收。所有故障仅隔离替身，不停线上ES、不调用付费模型。

## 6. 分类与交接建议

OTH-12已确认需要修复的范围包括通道保留和本地数据库状态矛盾。可先实施独立的通道保留，再实施显式本地读取闭环；后者必须纳入最终证据守卫与模式隔离。最新索引快照兼容口径、是否在索引健康时同时检索local-only、前端能力返回是需主agent明确的具体决定，不应阻塞已确认的独立通道修复。

本报告不新增工作包、不扩展RES；前端完整流程不足继续映射OTH-15。未执行任何测试，未宣称真实并发或端到端已通过。
