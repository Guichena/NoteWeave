# NoteWeave 质量、评测、测试与发布门禁

> 本文是 QA、Note、Wiki、Research、Artifact、Memory、资料基础设施与公共可靠性的唯一评测契约，也吸收原“下一阶段测试计划”。任何简历数字、灰度判断和优化结论都必须回到本文的分母、窗口、切片与版本记录。

## 1. 目的、硬门槛与证据边界

NoteWeave 不使用单一“综合准确率”。一次变更至少从结果质量、证据可信、权限安全、工程可靠、成本与漂移五个维度判断。QA、Note、Wiki 和 Research 的任务目标不同，不能把分数合并成一个 RAG 指标。

以下是硬门槛，不能被均值抵消：

- 跨 Workspace、越权 Source 或已撤销版本进入有效结果，样本数必须为 0。
- 旧 Lease Worker、无效审批或 Payload 不匹配的 Callback 被接受，样本数必须为 0。
- 红队集中危险工具调用未被阻断，样本数必须为 0。
- 删除或撤销后的内容超过批准传播窗口仍可被新 Run 读取，样本数必须为 0。
- 测试集泄漏、Judge 未校准或运行版本不完整时，不允许发布质量提升结论。

证据标签：

- `[当前实现]`：代码、配置、迁移或测试入口可证明，不代表本轮已执行。
- `[目标设计]`：指标定义、发布阈值与生产工作流。
- `[已测-模拟]`：固定数据、Mock、故障注入或模拟环境的实际记录。
- `[行业参考]`：公开 Benchmark、论文、标准或官方文档。
- `[生产待验证]`：真实用户、Provider、规模和持续窗口才可验证。

`[行业参考]` 检索可参考 BEIR、KILT 和 TREC RAG，长文可参考 Qasper、NarrativeQA 和 LongBench，多跳可参考 HotpotQA、2WikiMultiHopQA 与 HoVer；Ragas/LangSmith 的 Faithfulness、Context Precision/Recall 和 Judge 方法可作口径参考。公开榜单不能当作 NoteWeave 成绩，详细来源见 [公开 Benchmark 与简历指标宣传口径调研](../research/公开Benchmark与简历指标宣传口径调研.md)。

## 2. 统一实验记录

每次回放、压测、故障注入或灰度都生成不可变 `run_manifest`。至少记录：

| 字段 | 必填内容 |
|---|---|
| Identity | `run_id`、开始/结束时间、执行人或自动任务、环境 |
| Dataset | 文件、版本、样本数、语言、任务类型、Workspace/风险分桶、Gold 生成与复核方式 |
| Release Bundle | 源码快照标识、迁移基线、模型、Prompt、Schema、Validator、Embedding、Reranker、索引、Skill/Graph、Policy 版本 |
| Strategy | Chunk/Overlap、Top K、RRF、Rerank、Token、超时、重试、并发、Feature Flag |
| Infrastructure | Java/Python 版本、机器、MySQL/Kafka/Redis/ES/MinIO、真实或 Mock Provider |
| Statistics | 指标版本、分子、分母、点估计、95% CI、无效/排除样本及原因 |
| Evidence | 执行命令、原始结果、失败样本、Trace/Metric 查询和报告路径 |
| Boundary | `[当前实现]`、`[已测-模拟]`、`[目标设计]` 或 `[生产待验证]`，以及不能推出的结论 |

共同规则：

1. 离线质量窗口为一次冻结数据集的完整运行；线上窗口按能力采用滚动 1 小时、6 小时和 28 天，发布决策使用同一版本内完整窗口。
2. 只有 Gold 本身无效、预先声明的 Dry Run 或尚未被系统接纳的请求可以按指标合同排除，并必须保存数量与原因。基础设施未启动、Provider 超时、失败、未知结果和系统取消若发生在接纳之后，必须留在严格接纳 Cohort；有审计证据的用户主动取消只能从明确命名的执行器或模型质量指标中排除，同时仍留在严格完成率并单列取消率。拒答是产品结果，不得静默删除。
3. 所有指标至少按 Workspace 类型、任务类型、语言、Source 类型/大小、模型/Provider、风险级别和输入长度切片；设备只对浏览器、端侧性能或上传链路切片。
4. K、Gold 版本、Judge、Rubric 或过滤规则改变时，指标版本必须递增，不能与旧值直接拼接。
5. 变更比较优先使用同一 Query、同一 Snapshot、同一索引和配对 Bootstrap；只报告均值而不提供样本与区间，不能作为发布证据。

### 2.1 EVO 是什么

本文把 `EVO` 作为 NoteWeave 内部的评测记忆法，不把它冒充行业标准或某个现成框架：

- **Evaluation**：结果有没有完成任务。QA 看相关证据、引用和拒答，Note 看 Source 命中与阅读窗口，Wiki 看当前版本和关系证据，Research 看字段、行列、引用和反证闭环。
- **Verification**：即使平均质量很好，也必须检查不能被均值抵消的不变量，例如 Workspace Scope、当前 Snapshot、Lease/Fencing、Callback 协议、危险工具阻断和删除传播。
- **Observation**：保存足以解释失败和复现比较的上下文，包括 Case、切片、版本 Bundle、阶段 Trace、失败原因、指标分子分母和非内容 Evidence Receipt。

普通单元测试回答“这段代码在给定输入下是否符合断言”，EVO 还要回答“产品任务是否做好”“是否以不安全方式做对”“退化发生在哪一层”。因此测试全绿只是必要条件，不是发布充分条件。确定性 Fixture 也有价值，但它主要保护计算公式、排序契约、脱敏稳定性和门禁动作，不能替代真实 Elasticsearch、Embedding、Reranker、Provider 和用户分布。

### 2.2 评测单元与版本 Bundle

一个可比较的 Eval Unit 不是孤立的 Query，而是下面这组对象的笛卡尔约束：

```text
Eval Unit = Case × Dataset Version × Strategy × Provider/Model
          × Index/Snapshot × Prompt/Schema × Judge/Rubric × Gate Policy
```

其中任一项变化，都可能使同名指标失去可比性。例如 Recall 上升可能来自 Gold 改了、Scope Filter 被放松或 Top K 变大，不一定来自 Retriever 更好。NoteWeave 的最小 Gold Case 应保留下列字段，实际 Schema 以对应 Fixture 和编译器为准：

| 字段 | 作用 | 最小反例 |
|---|---|---|
| `caseId`、`taskType`、`query` | 稳定身份和任务路由 | 相同文本在 QA 与 Note 使用同一判断标准 |
| Workspace、Source、Snapshot Scope | 权限和时间边界 | 相关但属于其他 Workspace 的片段被判为正确 |
| Candidate 与 Gold Relevant ID | 计算 Recall、MRR、nDCG | 只保存最终答案，无法判断召回还是生成失败 |
| Expected Citation/Refusal | 验证引用与证据不足语义 | 没有答案的 Case 被当成 Recall 失败，而不是拒答 Case |
| Strategy 与 Requested Top K | 固定比较条件 | 新策略把 K 从 5 改成 50 后宣称 Recall 提升 |
| Slice 与 Risk | 防止均值掩盖问题 | 简单事实题提升，越权切片回退却仍被放行 |
| Provenance、Annotator、Rubric Version | 追溯 Gold 来源 | Judge 与 Gold 使用同一模型生成，形成自证循环 |

### 2.3 数据集不是越大越好，而是要能击穿假设

EVO 数据按用途分层管理，不把全部样本混成一个分数：

| 数据层 | 主要目的 | NoteWeave 示例 | 发布作用 |
|---|---|---|---|
| Smoke | 快速检查 Schema、计算器和链路可运行 | 当前 4 Case Retrieval Gold | 每次改动快速回放，但不能证明泛化 |
| Regression | 固化历史缺陷和核心场景 | QA/Note/Wiki 命中、引用、拒答 | 阻止已知问题复发 |
| Hard Case | 区分看似相同的策略 | 长 Query、精确错误码、多跳、冲突来源、近重复片段 | 判断 RRF、Rerank 和窗口策略的边际价值 |
| Safety Red Team | 验证不可妥协的不变量 | 跨 Workspace Candidate、撤销 Snapshot、注入工具调用 | 任一高风险失败即硬阻断 |
| Drift/Shadow | 在相同输入上比较基线和候选 | Baseline 与 Shadow 的 Ranking、延迟和 Scope Delta | 决定灰度、停止或回滚 |

小而锋利的 Fixture 可以尽早发现契约破坏，但不能支撑生产质量结论。扩容时优先从生产 Bad Case、人工纠正、拒答争议和高风险切片抽样，并进行近重复去重；不要只批量生成与现有题型同分布的简单问题。

### 2.4 Judge、统计与泄漏

确定性规则能判断 ID、Scope、版本、Schema、数值和状态时，优先使用规则；只有语义蕴含、来源质量和开放式完整性才交给人工或 LLM Judge。LLM Judge 上线前要用冻结校准集与双人标注对齐，保存 Prompt、模型、Rubric、顺序随机化和分歧仲裁结果。若 Judge 与候选答案共享生成模型，至少要增加盲化、对调顺序和人工抽检，降低自偏好与位置偏差。

样本量小时，`4/4 = 100%` 只能描述这 4 个 Case。正式比较保存逐 Case 差值，优先做配对 Bootstrap 置信区间；分类标注记录分歧率和 Cohen's Kappa。训练、调参、Prompt 调试和最终报告集应隔离，Query、改写、答案与同一文档切片的近重复都要查重。否则“优化”可能只是记住评测集。

### 2.5 从失败定位到发布动作

EVO 不以生成一张分数表结束，而是形成下面的闭环：

```text
冻结 Dataset 与 Bundle
  -> Baseline/Shadow 配对回放
  -> 按任务、风险、语言、长度和来源切片
  -> 定位 Recall / Ranking / Citation / Refusal / Scope / Latency
  -> 复核 Bad Case 和 Judge 分歧
  -> Gate 决定继续、灰度、停止或回滚
  -> 失败样本去敏后进入 Regression/Hard Case
```

发布必须同时满足软质量预算和硬安全门槛。离线通过后仍需 Canary 观察输入、模型、检索和策略漂移；任何 Scope Violation、越权 Citation 或失效 Snapshot 命中都直接 Stop。只有问题切片修复、全量回放通过且灰度窗口稳定后才恢复，而不是用整体均值回升抵消高风险失败。

### 2.6 当前固定回放：可计算，但不能外推

`[已测-模拟]` 2026-08-12 执行 9 个 Retrieval Eval 测试类，共 22 个测试，失败 0、错误 0、跳过 0。运行使用固定 JSON、Mock 和确定性计算器，没有连接在线 Elasticsearch、Embedding、Reranker 或 LLM Provider。

固定 `stage5-retrieval-gold-v1` 有 4 个 Case：Spring Boot 配置 QA、检索重构 Note、Citation Provenance Wiki，以及 `quantum banana theorem` 证据不足拒答。Profiler 记录 11 个 Candidate，其中 Scope 内 9 个；Query Token 共 29，Requested Top K 共 7，返回 Evidence 4，Relevant Evidence 4，Expected Citation 4；Warmup 1 次，Measured Iteration 5 次。这是 Smoke/Contract 规模，不是生产样本量。

质量门禁 Fixture 的通过样例为：Case Count 4、Macro Recall@K 1.0、Mean TopK Overlap 1.0、Top1 Changed Ratio 0、Scope Violation 0、P95 900 微秒。当前 Policy 字段要求 Macro Recall、MRR、nDCG、Citation Precision 和 `Citation Coverage` 不低于 0.9，`Refusal Accuracy` 等于 1，主要质量 Delta 不低于 -0.05，Overlap 不低于 0.75，Top1 Changed Ratio 不高于 0.25，Scope Violation 等于 0，P95 不高于 1000 微秒。这里 `Citation Coverage` 的语义对应 Citation Completeness；`Refusal Accuracy` 只在应拒答集上计算，语义对应 Refusal Recall。两者都是兼容字段名，当前固定 Fixture 尚不能证明 Claim-Evidence Coverage 或 Refusal Precision 达标。

故意退化的 Shadow 更能解释 EVO 的价值：

| 指标 | Baseline | 退化 Shadow | 判断 |
|---|---:|---:|---|
| Macro Recall@K | 1.0 | 0.5 | 质量明显回退 |
| MRR | 1.0 | 0.5 | 相关结果排序退化 |
| Citation Precision / Coverage | 1.0 / 1.0 | 0.5 / 0.5 | 现有字段显示一半引用不准确且一半应引用 Claim 未覆盖，不能据此替代完整 Evidence 支持判断 |
| Refusal Accuracy（兼容字段） | 1.0 | 0 | 应拒答集上的 Refusal Recall 从全部命中退化为全部漏拒，未衡量过度拒答 |
| Top1 Changed Count | 0 | 3 | 4 Case 中 3 个首位结果变化 |
| Scope Violation | 0 | 1 | 安全硬门槛失败 |
| P95 Latency | 900 微秒 | 1200 微秒 | 同时超过延迟预算 |

即使把演练样例改成“Shadow 延迟更快”，Scope Violation 为 1 仍必须拒绝发布。这个判断来自不变量，不依赖综合分如何加权。

QA 的 3 Case 消融 Fixture 从 Keyword Only 0.3333、Vector Only 0.6667 到 Hybrid RRF 1.0、Hybrid RRF + Rerank 1.0、Full Pipeline 1.0；Note 的 3 Case 从 Metadata Only 0.3333、Metadata + Journal/Metadata + Semantic 0.6667 到组合路径 1.0。它们是手工固化的回归数字。Rerank 在这 3 个简单 QA Case 上没有额外 Lift，不代表它没有价值，只说明当前数据缺少能区分首排和重排的 Hard Query。

脱敏 Fixture 覆盖 4 条 Path、总计 16 个值：同 Salt 结果稳定，不同 Salt 身份不同，脱敏后 Recall 1.0、现有字段 `Citation Coverage` 1.0、Scope Violation 0。该 Citation 数字只证明固定样例中的 Citation Completeness 没有因脱敏下降，不证明 Citation Accuracy 或 Claim-Evidence Coverage 为 1.0。`retrieval-evaluation-evidence-receipt-v1` 仅保留 Byte Size、SHA-256、安全版本、策略 Profile、聚合质量/延迟和 Ranking Signature，不保存路径、Query、正文及 Case/Evidence/Source/Citation ID。`rankingsStable=true` 只证明两个输入 Artifact 的排名签名相同，不证明独立执行、数字签名或线上真实性。

## 3. 检索与排序指标

下表所有阈值均为 `[目标设计]`。具体回归预算在每次发布前写入 Manifest，不能事后按结果修改。

| 指标与公式 | 窗口、过滤与切片 | 数据源、标签与版本 | 超阈值动作与恢复 |
|---|---|---|---|
| Hit@K = Top K 含至少一个 Gold Evidence 的合格 Query 数 / 合格 Query 总数 | 冻结 Gold 全量；排除 Gold 缺失；按任务、语言、Source 类型、单/多跳、K 切片 | Retrieval Gold + 排序结果；`[已测-模拟]` 才能填值；记录 Dataset/Index/Retriever v | 低于基线回归预算则停止放量，检查召回与过滤；修复后配对回放的 CI 回到门槛内再恢复 |
| Recall@K = 每个 Query 的 `|TopK ∩ Gold| / |Gold|` 宏平均；同时保留总分子/分母 | 同上；Gold 为空样本进入拒答集，不进入 Recall | 同上；Metric `retrieval_recall@k.v1` | 高价值切片回退即阻断，即使总均值提升；恢复需总集与问题切片均通过 |
| Precision@K = 每个 Query 的相关 Top K 数 / 实际返回数的宏平均 | 不足 K 按实际返回数；空返回记 0；按噪声、重复、权限过滤切片 | Gold 相关性标注 + 返回列表；记录 K 和标注版本 | Precision 下降且 Token/延迟上升时缩小候选或调整融合；连续回放无回退后恢复 |
| MRR = 首个相关结果排名倒数的 Query 均值，无相关命中记 0 | 仅有明确首个正确 Evidence 的合格 Query；按精确查询/语义查询切片 | Gold + 排名；`retrieval_mrr.v1` | MRR 回退而 Recall 不变时优先检查融合与 Rerank；新排序 CI 通过后恢复 |
| nDCG@K = `DCG@K / IDCG@K` 的宏平均，相关性等级由 Rubric 固定 | 需要 0/1/2 等级 Gold；按多证据、多跳和冲突查询切片 | 双人相关性标注 + 排名；记录 Rubric/Judge v | 高相关 Evidence 下沉超预算则回滚 Reranker；标注复核与回放通过后恢复 |
| Note Source Hit@K = Top K Source 含 Gold Source 的 Query 数 / 合格 Note Query 数 | 长文 Note Gold；按文档长度、格式和跨 Source 切片 | Note Gold、Source 排名、Snapshot v；`[已测-模拟]` | 回退则检查粗排与 Source 权重；固定长文回放恢复后再灰度 |
| Reading Window Continuity = 覆盖 Gold 连续范围且无越界版本的窗口数 / 标注窗口总数 | Note Gold；按窗口长度、页边界、表格/代码切片 | Anchor/Window Trace + Gold Span；Metric v1 | 连续性低或 Token 超预算时调窗口扩展；两项同时满足后恢复 |
| Wiki Supporting Evidence F1 = 支持页/边的宏平均 Precision 与 Recall 的调和均值 | Wiki Contract Gold；按单页、多跳、重命名和删除切片 | Page/Link/Backlink 版本与 Gold；Metric v1 | F1 回退或旧 Head 命中则停止 Wiki 投影发布；重建并对账后恢复 |

QA 当前参数、Note 当前参数和 Wiki 投影策略分别见对应专题。参数事实不等于指标结果。

## 4. 引用、事实与来源质量

| 指标与公式 | 窗口、过滤与切片 | 数据源、标签与版本 | 超阈值动作与恢复 |
|---|---|---|---|
| 引用有效率 = 可解析、可访问、版本存在且当前有权的 Citation 数 / 发出的 Citation 数 | 冻结回放或线上滚动窗口；空回答无 Citation 不进入分母；按 Source 类型、内外部、年龄切片 | Citation Resolver、ACL/Version 真源；记录 Resolver/Schema v | 任一越权引用硬阻断；失效率超预算时降级为不展示或拒答，依赖修复并回放通过后恢复 |
| 引用准确率 = 语义上支持所绑定 Claim 的 Citation 数 / 被判定 Citation 数 | 只含可核验 Claim；双人标注或已校准 Judge；按数字、日期、比较、否定切片 | Claim-Citation 对、Rubric/Judge v；值可为 `[已测-模拟]` 或 `[生产待验证]` | 低于门槛暂停 Prompt/Model 发布，分析错误类型；人工复核集与 Judge 对齐后恢复 |
| Citation Completeness（当前兼容字段名为 Citation Coverage）= 至少绑定一个有效 Citation 的应引用 Claim 数 / 全部应引用 Claim 数 | 排除纯主观/格式语句；按模块、风险和 Claim 类型切片 | Claim Splitter + Citation Map；记录 Splitter v | 完整度下降则收紧生成或增加 Evidence；恢复要求高风险切片无回退 |
| Claim-Evidence Coverage = 被一组 Evidence 完整支持的原子 Claim 数 / 全部可核验原子 Claim 数 | 一个链接仅部分支持时记未完整覆盖；同上切片 | Typed Claim、Evidence Ledger、Rubric v | 超过无支持预算则阻止 Research/Artifact 提交；补证或删 Claim 后重新验证 |
| Faithfulness = 不超出所给 Context 的判定为真陈述数 / 被判定陈述数 | 固定 Context，不允许 Judge 使用外部常识补证；按回答长度和模型切片 | 人工金标 + 已校准 Judge；Judge/Prompt v | Judge 与人工未对齐时只作诊断；回退时回滚 Prompt/Model，校准后恢复 |
| 事实一致性 = 与 Gold/权威 Source 不矛盾的可核验事实数 / 可核验事实总数 | 来源冲突样本单独统计，不把冲突简单判错；按事实类型切片 | Gold Fact、Source Snapshot、Typed Validator v | 高风险事实矛盾硬阻断；修复抽取/验证并全量回放后恢复 |
| 来源质量 = 各被采用 Source 的 Rubric 得分总和 / 可得最高总分 | Rubric 固定权威性、第一方性、可追溯性；按主题和来源类型切片 | Source Metadata + 双人 Rubric；`[目标设计]`，生产值待验证 | 低质量来源占比超预算则降低权重或要求补证；重新验证来源后恢复 |
| 来源多样性 = 独立 Provenance Group 数 / 被采用有效 Source 数，并同时报告最大单组占比 | 去除镜像、转载和同一上游；按 Research 主题切片 | Canonical URL、Publisher/Domain 归并规则 v | 单源集中超预算时增加发现步骤或明确单源限制；达到多样性门槛后恢复提交 |
| 来源新鲜度 = 满足任务时效 SLA 的有效 Source 数 / 需要时效判断的有效 Source 数 | 非时效任务不进入分母；按时效级别和抓取日期切片 | Published/Updated/Fetched At + Freshness Policy v | 过期时重新抓取或标记不确定；Snapshot 更新并重新验证后恢复 |
| 冲突证据发现率 = 系统识别并保留冲突的 Gold Conflict Case 数 / Gold Conflict Case 总数 | 仅冲突集；按数值、日期、定义和来源版本切片 | Conflict Gold + Evidence Ledger；Metric v1 | 漏检高风险冲突则阻断 Research 发布；规则/Prompt 修复并冲突集全过后恢复 |
| 无依据结论率 = 无完整 Evidence 支持的最终可核验结论数 / 最终可核验结论总数 | 失败、拒答不从分母删除，单列；按风险、模块和长度切片 | Final Claim + Evidence Validator v | 高风险任何无依据结论硬阻断，其他超预算降级为拒答；补证回放通过后恢复 |

Citation Completeness 只判断应引用 Claim 是否附有有效 Citation，当前代码和历史 Receipt 仍可能使用字段名 `Citation Coverage`；Claim-Evidence Coverage 判断 Evidence 是否完整支持原子 Claim，两者不能互相替代。对外讲解优先使用语义明确的新名称，读取历史数据时通过 Metric Schema Version 做兼容映射，不能静默改写旧时间序列。

## 5. 拒答、人工与安全指标

| 指标与公式 | 窗口、过滤与切片 | 数据源、标签与版本 | 超阈值动作与恢复 |
|---|---|---|---|
| Refusal Recall = 被正确拒答的证据不足/越权/冲突未解样本数 / 全部应拒答样本数；当前兼容字段名为 Refusal Accuracy | 专用拒答 Gold；按原因和风险切片 | Gold + Answer State/Reason Code；Metric v1 | 漏拒高风险样本硬阻断；修复 Evidence Gate 后全量拒答集通过 |
| Refusal Precision = 被正确拒答的样本数 / 全部被拒答样本数 | Positive 与 Negative Case 必须放在同一评测 Cohort；按风险和拒答原因切片 | Gold + Answer State/Reason Code；`[目标设计]` | Precision 下降说明过度拒答；修复阈值后与 Recall 联合恢复 |
| Answer Coverage = 非拒答样本数 / 全部合格样本数 | 失败和超时不能伪装成拒答；按 Query 类型、风险和 Evidence 可用性切片 | Answer State + Gold；`[目标设计]` | Coverage 下降必须解释是安全收益还是可用性回退，不能只用 Selective Accuracy 掩盖 |
| Selective Accuracy = 正确且有依据的已回答样本数 / 所有非拒答样本数 | 同时报告 Answer Coverage，禁止只提高拒答率换准确率；按模块切片 | Gold + Claim/Evidence 判定；Metric v1 | Accuracy 提升但 Coverage 跌出门槛仍阻断；两者联合通过后恢复 |
| 人工修改率 = 审核者实质修改的产物数 / 进入人工审核且可编辑的产物数 | 排除纯格式自动化；按 Artifact/Note/Memory 类型切片 | Revision Diff + Review Event；`[生产待验证]` | 持续升高则暂停自动写回，分析修改类型；灰度窗口回到基线后恢复 |
| 采纳率 = 被接受、写回或设为 Head 的交付结果数 / 可供采纳的已交付结果数 | 取消与系统失败单列；按类型、用户群和版本切片 | Version/Writeback/Head Event；`[生产待验证]` | 下降只触发诊断，不能单独证明质量；修复后跨窗口回到基线 |
| 接管率 = 用户转为手工完成或显式接管的任务数 / 已开始且提供接管能力的任务数 | 按失败、等待、质量不满原因切片 | UI Event + Run State；`[生产待验证]` | 上升触发任务级降级和原因分析；连续窗口恢复到批准区间 |
| 危险工具调用阻断率 = 被策略阻断的注入危险调用数 / 红队注入危险调用总数 | 固定红队全量；按直接/间接注入、SSRF、审批重放、Metadata 投毒切片 | Policy Audit + Red-team Dataset/Policy v；`[已测-模拟]` 才可填值 | 目标 100%，任何漏拦截停止相关 Skill/Tool；修复后全量红队与回归通过 |
| Scope Violation Rate = 越权结果、引用、工具或写回数 / 所有被检查操作数 | 离线与线上全量安全事件；按层和动作切片 | ACL Audit、SQL/Cache/Search/Callback 事件；Metric v1 | 目标 0，发现即停用能力、撤销结果并对账；根因修复和全链回放后恢复 |

## 6. 输入理解、Research、Artifact 与 Memory 指标

| 指标与公式 | 窗口、过滤与切片 | 数据源、标签与版本 | 超阈值动作与恢复 |
|---|---|---|---|
| 输入槽位完整率 = Gold 必填槽位中被正确提取且未擅自补全的槽位数 / Gold 必填槽位总数 | 冻结理解集；按 QA/Research/Artifact、语言、单/多轮、显式/省略输入切片 | Original Input、Understanding Snapshot、Gold Slot、Policy v；统一合同为 `[目标设计]` | 关键 Scope/写回槽位任何误补硬阻断；修复抽取或改为澄清，全量理解集通过后恢复 |
| 指代与术语解析准确率 = 被正确解析到 Gold 实体/别名的样本数 / 含可判定指代或术语样本数 | 仅含可唯一判定 Gold；歧义样本进入澄清集；按跨轮距离与领域词切片 | Conversation Snapshot、Entity/Alias Decision、Gold v | 越权 Scope 或错误对象解析硬阻断；回滚 Rewrite/Policy 并复验后恢复 |
| 澄清 Precision / Recall = 必要且被触发澄清数 / 全部触发澄清数；必要且被触发澄清数 / 全部必要澄清数 | 冻结歧义集；按能力、风险、缺失槽位与多意图切片 | Clarification Decision、Gold Reason、Policy v；`[目标设计]` | Recall 低会误执行，Precision 低会打断用户；按风险设联合门槛，两个指标均恢复后放量 |
| 错误路由率 = 进入错误能力、Retrieval Plan 或 Skill 的请求数 / 有 Gold 路由的请求数 | 取消与用户改意图单列；按能力、风险和候选集歧义切片 | Route/Skill Decision、Gold、Catalog/Policy v | 高风险错路由硬阻断；回退 Router/Catalog，回放无关键错误后恢复 |
| Plan 依赖合法率 = 依赖均存在、无环且前置条件满足的可执行 Step 数 / 被检查 Step 数 | Research 固定计划集；按任务类型、深度和 Plan Revision 切片 | Plan/Matrix/Stage、Planner v；部分为 `[当前实现]`，统一指标为 `[目标设计]` | 非法依赖阻断调度；修复 Planner 并重放所有 Revision 后恢复 |
| 有效 Replan 率 = 修复已记录偏差且通过后续门禁的 Replan 数 / 已接受 Replan 数 | 仅统计有原因码和新 Digest 的 Replan；按偏差与回滚粒度切片 | Plan Revision、Reason、Checkpoint、Final Gate v | 无效改计划或目标降级超预算时停用相关策略；固定偏差集恢复后放量 |
| 回滚距离 = 被撤销的 Step/Cell/Stage 数，并同时报告其中已验证单元占比 | 故障与偏差回放；按局部/阶段/全局切片 | Plan Diff、Cell Version、Evidence/Receipt v | 已验证单元被大范围重复撤销时阻断全局 Replan；依赖失效范围回归后恢复 |
| 重复外部动作率 = 相同语义 Operation Key 被重复实际执行的次数 / 外部动作实际执行次数 | 恢复、超时和重规划集；查询 Receipt 的读操作不算重复副作用 | Operation Intent/Receipt、Provider Request ID、Bundle v | 非幂等重复硬阻断；修复 Receipt/Checkpoint 并故障矩阵全过后恢复 |
| Research 严格接纳完成率 = 在统一观察截止前通过最终 Evidence/Schema Gate 的 Completed ResearchRun 数 / 固定已接纳 ResearchRun Cohort | 失败、证据不足、用户取消和超期未完成分别报告且保留在分母；按主题、深度、Provider 切片 | Research Run/Stage/Ledger + Bundle v；`[已测-模拟]` 或 `[生产待验证]` | 下降先区分正确失败和系统失败；只对系统回退阻断，修复后固定 Gold 回放恢复 |
| Research 恢复率 = 故障注入后恢复到与无故障 Canonical Digest 一致且无重复副作用的 Run 数 / 注入故障 Run 数 | 固定故障矩阵；按阶段、故障类型和接管次数切片 | Checkpoint、Receipt、Completion Replay；Metric v1 | 任一旧写或重复副作用硬阻断；故障矩阵全过后恢复 |
| Required WorkItem Completion = 通过类型化验收的 Required WorkItem 数 / Required WorkItem 总数 | Research Gold；按 WorkItem 类型、关键性、难度和预算切片 | Plan/WorkItem/Evidence Validation + Schema v；当前 Cell 映射为 `MATRIX_CELL` | 关键 WorkItem 回退阻断；修复规划/发现/验证后 Gold 全量回放 |
| Matrix Cell Coverage = 通过 Cell 验收的 Required Cell 数 / Required Cell 总数 | 只统计比较型 Research Gold；按实体数、维度数和难度切片 | Matrix/Cell/Evidence Validation + Schema v | 比较型关键 Cell 回退阻断；不能外推到开放调查、时间线或来源审计 |
| Artifact Skill 选择准确率 = 选择与 Gold Skill 相同且策略允许的 Job 数 / 有唯一或可接受 Skill Gold 的 Job 数 | 固定选择集；歧义样本允许等价集合；按语言、类型、风险切片 | Selection Trace + Catalog/Policy v；`[已测-模拟]` | 高风险错选硬阻断；分类器/Catalog 修复后回放恢复 |
| Artifact 严格接纳完成率 = 在观察截止前提交可读取 READY Version 与完整 File Manifest 的 ArtifactRun 数 / 固定已接纳 ArtifactRun Cohort | Job 是长期用户意图，不作为生成次数分母；取消、失败和超期未完成分列且保留；按 Skill、大小、Provider 切片 | Job/Run/Version/File/Receipt + Bundle v | 低于门槛暂停对应 Skill；失败分类修复并灰度窗口恢复 |
| Artifact Final Contract Pass = 最终通过全部确定性 Validator 的 Candidate 数 / 已接受最终 Contract 判定的 Candidate 数；另报 First-pass Yield 和 Repair Yield | Version 只在内容 Contract 通过后创建，不能把尚不存在的“候选 Version”当分母；按 Validator、Skill、Repair 次数切片 | Candidate/Validator Trace + Schema/Validator v | 最终失败超预算或 Repair 放大则回滚 Bundle；固定快照回放通过后恢复 |
| Memory 候选审核通过率 = Approved Candidate 数 / 已完成 Review 的 Candidate 数 | 不含待审；按 Candidate 类型、来源、自动/人工切片 | Review Decision + Policy/Extractor v；值为 `[生产待验证]` | 仅作分布指标，异常漂移触发抽样复核，不能追求越高越好 |
| Memory 撤销残留率 = 撤销传播窗口结束后仍被索引、Cache、Pack 或新 Run 读取的条目数 / 已撤销条目数 | 故障回放与线上全量撤销；按投影层切片 | Revoke Event、Watermark、Index/Cache 对账 + Compiler v | 目标 0，发现即停编译并清理；全层对账为 0 且滞回窗口通过后恢复 |
| Memory 错误注入率 = 被证明错误或越权且进入正式 Control Pack 的条目数 / 进入正式 Control Pack 的条目数 | Shadow 不进入分母；按来源、自动批准、运行类型切片 | Item/Version/Pack/Outcome/纠正 + Policy v | 高风险目标 0；发现即撤销、停自动批准并回溯受影响 Run，修复回放后恢复 |
| Control Pack 有效采用率 = 被 Run 实际使用且无负反馈的条目使用次数 / Control Pack 条目总使用次数 | 无使用不计；按 Run Type、Item Type 和 Revision 切片 | Usage Log + Outcome；`[生产待验证]` | 下降触发排序/预算复核，不能自动删除条目；Shadow/灰度恢复后再发布 |

## 7. 资料基础设施与工程可靠性

| 指标与公式 | 窗口、过滤与切片 | 数据源、标签与版本 | 超阈值动作与恢复 |
|---|---|---|---|
| Projection Ready 延迟 P95/P99 = Accepted 到当前 Snapshot 全部必需投影 Ready 的耗时分位数 | 固定文件矩阵或线上滚动窗；按格式、大小、页数、是否 OCR 切片 | Upload/Projection Event + Parser/Chunk/Index v | 超预算降低接入并发或降级非必需投影；Lag 与错误预算恢复后放量 |
| 投影最终收敛率 = 在批准恢复窗口内达到正确 Projection Version 的 Snapshot 数 / 需要投影的 Snapshot 数 | 包含重试与故障样本；按失败组件切片 | MySQL Projection State + ES/Vector 对账 | 低于门槛暂停新索引版本；补偿/重建完成且对账通过后恢复 |
| 删除传播残留率 = 窗口结束后仍存在可读投影/缓存/副本的 Tombstone 对象数 / 删除对象数 | 全量删除与故障注入；按 ES、Vector、Cache、Memory、Eval、Artifact 切片 | Cleanup Task、Tombstone、各投影对账 | 目标 0，发现即阻断相关读取并清理；所有层对账为 0 后恢复 |
| Outbox 最老未发布年龄 = 当前时刻减最早 Eligible Outbox 创建时间 | 1/6/28 小时窗口；按 Topic/Event Type 切片 | Outbox Metrics + Publisher v | 超能力 SLO 停止非关键写入并修复 Dispatcher；年龄与 Lag 在滞回窗回基线后恢复 |
| Kafka 最老消息年龄/Consumer Lag | Broker 当前位点与消费位点差及最老记录年龄 | 按 Topic/Partition/Consumer Group；重试 Topic 单列 | Kafka Metrics + Consumer v | 年龄比 Lag 更接近用户影响；超阈值降载/扩容/修复毒消息，稳定窗后恢复 |
| Worker Lease 接管时间 = 原 Owner 失效到新 Owner 成功 Claim 的耗时 | 故障注入或线上故障；按任务类型和 Lease 配置切片 | Lease/Heartbeat/Fencing Event v | 超 RTO 调整检测与 Lease；旧写拒绝异常升高先查抖动，矩阵通过后恢复 |
| Callback 未知结果年龄 = 当前时刻减最早 `UNKNOWN/DISPATCHED` 未对账 Receipt 时间 | 按 Provider、能力、幂等能力切片 | Operation/Callback Receipt v | 超阈值暂停非幂等调用并对账；未知队列清零或回到批准基线后恢复 |
| Provider P95/P99、限流率 | 成功/全部调用延迟分位；429 或明确限流调用数 / Provider 调用数 | 1/6/28 小时；按 Provider、模型、操作、输入长度切片 | Adapter Metrics + Provider/Model v | 超阈值降并发、退避或切换受测降级；稳定窗口恢复后放量 |
| 准入拒绝率 = 被 Admission Controller 拒绝或降级的请求/任务数 / 全部准入决策数 | 1/6/28 小时；按原因、能力、Workspace 类型、同步/异步切片 | Quota/Executor/Provider Decision + Policy v；现有局部指标为 `[当前实现]`，统一口径为 `[目标设计]` | 结合下游饱和判断；误拒升高回滚策略，过载漏放则收紧，两个滞回窗稳定后恢复 |
| 排队最老年龄与饥饿率 = 各队列最老 Eligible 任务年龄；超过任务类型等待预算仍未 Claim 的任务数 / Eligible 任务数 | 滚动窗口；按能力、Workspace 份额、优先级和成本级别切片 | Task/Outbox Queue、Claim Event、Admission v | 超阈值暂停低优先级入口、保留恢复容量并检查热点；积压和份额回到批准区间后恢复 |
| 资源耦合饱和事件 = Executor Rejected、Hikari Pending、Provider 429、Worker/Callback 饱和在同一时间窗共同出现的次数 | 压测或线上 1/6 小时；按工作负载与实例切片 | 全量 Metric + Trace 样本、Config/Bundle v | 不单独扩某一个池；重算最小容量并压测，所有相邻资源均低于恢复阈值后放量 |
| 单位成功任务成本 = 窗口内模型、搜索、存储、计算等归集成本 / 通过质量门禁的成功任务数 | 失败成本保留在分子；按能力、Provider、输入规模切片 | Usage/Receipt/Billing + Bundle v；`[生产待验证]` | 超预算暂停放量或降级，不得删失败样本；成本与质量同时恢复后放量 |
| 能力 SLO 达成率 = 满足能力 SLI 的 Good Event 数 / Eligible Event 数 | 28 天主窗，1 小时/6 小时 Burn Rate；按能力不按全站混算 | 全量 Metrics + SLO v | 多窗 Burn Rate 超阈值暂停发布、降级；两个连续滞回窗回到恢复阈值 |
| RTO/RPO 演练达成率 = 在目标时间/数据损失内恢复且对账通过的演练数 / 有效演练数 | 每种灾难场景独立；排除脚本无效但保留记录 | Restore Manifest、Backup、Digest/Receipt 对账 v；`[已测-模拟]` | 任一关键能力未达标则冻结相关发布；修复后重做同场景演练 |

## 8. 统计可信度、标注与漂移

### 8.1 Bootstrap 置信区间

`[目标设计]` 对 Query/Task 级指标使用至少 1000 次有放回 Bootstrap，报告 2.5% 与 97.5% 分位形成 95% CI。策略对比使用配对重采样，保持同一 Query 的 A/B 结果绑定。样本小、类别极不平衡或分布聚类明显时，同时报告原始分子/分母和分桶结果，不用窄 CI 制造确定性。

### 8.2 双人标注与 Cohen's Kappa

每个新 Gold 版本先双人独立标注至少一个冻结校准集。分歧率 = 不一致样本数 / 双人共同标注样本数；Cohen's Kappa = `(observed agreement - expected agreement) / (1 - expected agreement)`。记录标签集、Rubric、标注者、仲裁结果和 Kappa 版本。Kappa 未达到发布前声明的门槛时，先改 Rubric 和重新校准，不直接扩大 Judge 自动评测。

### 8.3 LLM Judge 对齐

Judge 与人工金标一致率 = Judge 与仲裁标签相同样本数 / 校准样本数，同时报告每类 Precision/Recall、混淆矩阵和 Kappa。Judge Model、Prompt、Temperature、Schema 或 Rubric 变化即升级 Judge 版本并重新校准。未对齐 Judge 的结果只能标 `[行业参考]` 或诊断信号，不能作为 `[已测-模拟]` 发布门禁。

### 8.4 近重复泄漏

训练/调参集与测试集先做规范化文本 Hash、MinHash/Embedding 近邻和 Source/URL 家族检查。泄漏率 = 与训练或调参集超过冻结相似阈值的测试样本数 / 测试样本总数。发现泄漏时移除同家族样本、重新冻结 Dataset Version 并重跑；不得仅从报告里删除异常高分。

### 8.5 四类漂移

| 漂移 | 计算与数据源 | 窗口/切片 | 动作与恢复 |
|---|---|---|---|
| 输入漂移 | Query 长度、语言、任务类型、文件类型分布相对基线的 PSI/JS Divergence | 7/28 天，按能力与 Workspace 类型 | 超预声明阈值触发再抽样和评测扩容；新基线审核后恢复 |
| 模型漂移 | 固定 Canary 集在相同 Bundle 上的质量、拒答、成本和延迟变化 | 每次 Provider/模型变化及每日 Canary | 回退则 Pin 旧模型或停流；Canary 与 Gold 复验通过后恢复 |
| 检索漂移 | Gold/Shadow Query 的 Recall、nDCG、候选来源和 Projection Lag 变化 | 每次索引发布及每日回放 | 回滚 Index/Embedding/Reranker；重建和对账后恢复 |
| 知识新鲜度 | 过期 Source、失效 Citation、Stale Wiki/Memory 依赖占比 | 1/7/28 天，按时效风险切片 | 触发重新抓取、重建或标记不确定；依赖复核完成后恢复 |

## 9. 测试层分别证明什么

| 测试层 | 证明边界 | 不能证明 |
|---|---|---|
| 领域单元测试 | 规则、映射和单状态转移 | 数据库锁、网络和真实 Provider |
| 状态机/Property-Based | 任意生成序列下的不变量、无非法回退、终态稳定 | 外部系统语义 |
| API 与事件契约 | Schema、Reason Code、幂等键和兼容关系 | 消息一定到达业务终态 |
| 新旧版本兼容 | Rolling Upgrade、旧消息/Checkpoint/Bundle 可处理 | 大表迁移耗时与生产容量 |
| Repository/数据库集成 | SQL、约束、事务、并发条件更新 | 跨组件恢复 |
| Kafka/Outbox/Callback 集成 | 重复、乱序、发布窗口和 Receipt | 真实 Broker 长期容量 |
| Provider Adapter 契约 | 超时、限流、错误分类、结果查询和脱敏 | Provider SLA 与真实质量 |
| 固定快照回放 | 同一版本的质量、回归和可复现差异 | 真实用户分布 |
| RAG/Research 离线评测 | 检索、证据、拒答和报告质量 | 线上采纳和长期漂移 |
| Prompt Injection 红队 | 已知攻击集的阻断与审计 | 对未知攻击绝对安全 |
| Playwright 真实浏览器 E2E | 用户主流程、SSE 重连、版本比较和恢复 UI | 后端最大吞吐 |
| 混沌演练 | 进程终止、网络延迟、Kafka/MySQL/ES/MinIO/Collector 故障下的恢复 | 未覆盖故障和跨地域灾难 |
| 备份恢复与对账 | RTO/RPO、恢复顺序、缺失/重复/未知状态 | 从未演练的生产拓扑 |

Collector 故障测试只证明遥测降级与恢复，不能把 Collector WAL 当成业务消息持久化。

## 10. 分阶段执行与发布门禁

### 阶段 0：冻结口径

生成 Manifest，冻结数据集、Bundle、环境、策略、指标版本和基线。现有 Retrieval Gold、QA/Note Ablation、Note Marginalia、Wiki Contract 与 Research Benchmark 可以作为起点，但必须先核对样本与当前 Schema。

### 阶段 1：QA、Note、Wiki 回放

在同一 Query/Snapshot/Index 上比较 BM25、Vector、Hybrid + RRF、Hybrid + Rerank。QA 报 Recall/nDCG、引用、Faithfulness 和拒答；Note 报 Source Hit、Window Continuity 与修改率代理；Wiki 报 Evidence F1、Head Version 与 Backlink。Scope Violation、旧版本重新可见和删除残留是硬门槛。

这里要区分两层语义：在线查询允许受控容错。例如 Rerank Provider 关闭、超时或返回不符合契约时，QA 可以保留合法的 RRF 顺序继续返回，并在结果和 Trace 中标记 `Degraded` 与原因；这表示本次请求可交付，不表示 Rerank 已成功或质量达到发布标准。正式索引/策略 Release Gate 仍要求 Provider Ready、Dual Coverage、Completed Build 和 `No Degraded Runs`。因此“运行时可降级”和“可以正式发布”是两个独立判断，降级运行样本只能用于诊断或 Shadow，不能充当正式 Gate 的通过样本。

### 阶段 2：Research、Artifact、Memory 回放

Research Gold 覆盖正常、证据不足、来源冲突、网页变化和 Checkpoint 故障。Artifact 覆盖 Skill 选择、文件校验、等待/恢复、Callback 未知、写回冲突和 Metadata 投毒。Memory 覆盖候选、冲突、审核、确定性编译、错误注入、撤销和 Shadow Recall。

### 阶段 3：资料链路与故障注入

文件矩阵至少含 PDF、DOCX、HTML/Markdown，并覆盖纯文本、复杂排版、表格、扫描件、标题层级、列表、链接和代码块。记录大小、页数/字符数、解析耗时、Chunk 数与 Projection Ready 时间。故障覆盖 Kafka 重试、Dispatcher Kill、ES 暂停、对象缺失、Callback 丢失、Lease 接管和删除延迟。

### 阶段 4：灰度、停止与回滚

按 Workspace Allowlist、Bundle 或 Skill/Index Version 灰度。上线前写明质量回归预算、安全硬门槛、能力 SLO、成本上限、Burn Rate、观察窗口和恢复滞回。触发门槛时停止扩大流量，按影响执行关闭 Feature Flag、恢复旧 Bundle、降低并发、关闭外部 Tool、暂停自动写回或回滚索引。进行中的 Run 保持创建时版本，不能中途换 Bundle。

发布门禁检查的是拟发布的完整版本组合，不是要求每一次线上请求都必须拥有 Rerank。线上可以把降级作为可观测的失败保护；但只要发布窗口含有 `Degraded Final Plan Run`，或 Rerank/Embedding 等必需 Provider 未 Ready，就不能切换 Alias、扩大策略灰度或签发正式 Quality Receipt。

## 11. 可复用测试入口

`[当前实现]` 仓库存在以下代表性入口，执行前需核对类名与环境，不因“有测试文件”声称本轮通过：

```powershell
mvn -f backend/pom.xml -Dtest=RetrievalBenchmarkReplayTest,RetrievalShadowComparatorTest,RetrievalQualityGateTest,FinalRetrievalGoldContractTest test
mvn -f backend/pom.xml -Dtest=Phase3NoteWikiContractTest,WikiPageVersionCacheTest,WikiIngestServiceTest,KnowledgeVersionServiceTest,KnowledgeQueryServiceTest,KnowledgeGraphServiceTest test
mvn -f backend/pom.xml -Dtest=SourceRetrievalProjectionServiceCompensationTest,SourceRetrievalProjectionCoordinatorTest,RetrievalBackfillServiceCatalogGateTest,KafkaRetrievalProjectionFailureRecoveryTest,SourceDeletionCleanupListenerTest test
pytest -q workers/research-worker/tests/test_ma5_benchmark.py workers/research-worker/tests/test_ma5_role_quality.py workers/research-worker/tests/test_ma4h_execution_control.py workers/research-worker/tests/test_ma4g_completion_contract.py
pytest -q workers/artifact-worker/tests
```

零失败只能写“本次 N 轮零失败”，并附运行版本、命令和时间。它不表示风险为零，也不替代未执行的 MySQL、Kafka、MinIO、浏览器、混沌或恢复测试。

## 12. 历史证据与当前空白

`[已测-模拟]` 历史 Research 验收记录：Backend 883 tests、0 failures、0 errors、11 skipped；Research Worker 393 passed。确定性分布式回放到达 `NO_SUPPORTED_CANDIDATE` 失败屏障，未产生成功 Artifact。该记录证明当时版本的测试与失败门禁行为，不代表当前工作区测试结果，也不能外推生产完成率、质量、成本或容量。更早的 2026-07 Worker 截面（M0 `132 passed, 12 xfailed` 至 M3/M4 `201 passed`）已被本记录取代，不再作为对外口径。

`[当前实现]` 已有 Retrieval Gold、Shadow Comparator、Quality Gate、Wiki Contract、Research Benchmark、Artifact Worker 测试和资料投影补偿测试入口。它们证明评测与回归表面存在，不等于所有 Manifest、双人标注、CI、漂移监控和灰度门禁已经自动化。

`[生产待验证]` 目前仍需真实数据验证：用户采纳/修改/接管，真实 Provider 的 P95/P99 与限流，单位成功任务成本，长期 Memory 污染，知识新鲜度，能力 SLO、RTO/RPO 和跨组件恢复。没有对应 Manifest 与原始结果时，文档和简历只保留指标名及目标设计，不填估算值。

## 13. 简历与面试使用规则

关于论文、官方产品评测和同类 Benchmark 的指标映射，以及当前固定回放能否作为简历数字的专项审计，见[指标可宣传性审计与补充](../research/指标可宣传性审计与补充.md)。

简历最多选择少量互补指标，不追求表格全塞。推荐从 Research Field Coverage/Citation Support、QA Recall 或 nDCG、Note Window Continuity、Wiki Head/Backlink、Artifact Validator、Memory Revoke、Projection Ready 和 Recovery 中按岗位选择。

每个数字必须能回答：分子分母是什么、样本从哪里来、窗口多长、哪些样本被过滤、有哪些切片、使用哪个 Bundle/Judge/Index、置信区间如何、相对哪个基线、触发过什么失败、不能推出什么结论。缺少 Dataset、Bundle、Environment、Command、Result 或 Boundary 任一项时，不进入简历。
