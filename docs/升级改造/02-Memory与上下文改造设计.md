# 模块二：Memory 与上下文改造设计

## 1. 现状结论

记忆链路的骨架是完整的：抽取（LLM，失败退回规则）→ 信号 → 候选门控 → 人工复核或自动生效 → 编译成控制包注入提示词，另外有版本、撤销、效果反馈和编译缓存。上下文窗口规划在 token 预算、摘要滞后补齐、约束生效区间上的设计也讲得通。

问题集中在判断逻辑上：冲突怎么识别、门控分数从哪来、注入哪些记忆、效果反馈有没有真正回流。

## 2. 缺陷清单

| 编号 | 缺陷 | 位置 |
|---|---|---|
| M1 | 冲突检测只看正负极性：两条记忆相似度不低于 0.85 且一条正向一条负向时才算冲突。用中文回答和用英文回答这类同为正向、措辞差别大的矛盾识别不出来，会同时生效 | `MemoryCandidateService.java:146` |
| M2 | 置信度、效用、风险都按来源类型查固定表（用户反馈 0.98、模型推断 0.35 等），同一偏好被反复提起也不会提高分数 | `MemoryCandidatePolicy.java` |
| M3 | 注入上下文的记忆与当前问题无关，只按作用域、任务类型、效用、更新时间排序，在 token 预算内依次放入 | `MemoryCompilerService.java:174`、`MemoryCompilerPolicy.java:53` |
| M4 | 每条候选都全表加载工作区所有生效记忆，逐条计算 Jaccard 相似度 | `MemoryCandidateService.java:166` |
| M5 | 抽取前有正则初筛（以后、每次、记住、我喜欢等措辞），没有这些词的消息不会交给模型，例如太长了、别用英文术语这类隐式反馈全部漏掉 | `MemoryConversationExtractionService.java:47` |
| M6 | 已有等价记忆时直接跳过新信号，重复出现不会强化已有记忆，也不会更新措辞 | `MemoryConversationExtractionService.java:192` |
| M7 | 效果反馈没有闭环：`POST /memory/outcomes` 只有接口，前后端都没有调用方，效用分数实际上从不更新，过期判定也就不会触发 | `MemoryController.java:158`、`MemoryOutcomePolicy` |
| C1 | 话题切分只用词项 TF-IDF，同义词、中英文混用时相似度为 0；延续判断靠正则匹配句首的这、那、补充等字，新话题以这开头时会被误判为延续 | `TopicSegmenterV2.java:29-45`、`:104` |

## 3. 改造方案

### S1 基于槽位的冲突检测（解决 M1）

1. 抽取提示词增加三个输出字段：
   - `slot`：偏好约束的对象，用受控词表加自由后缀，例如 `answer.language`、`answer.length`、`format.table`、`terminology.<术语>`；
   - `value`：取值，例如 `zh`、`concise`；
   - `polarity`：`REQUIRE` 或 `FORBID`。
2. 记忆对象的 `normalized_value_json` 增加这三个字段，新增迁移脚本给 `memory_item` 加 `slot_key` 列并建索引。历史数据由一次性回填任务调用 LLM 补齐，补不出来的记为 `slot_key = 'unknown'`。
3. 冲突判定分两层：
   - **确定性判定**：slot 相同时，取值不同，或取值相同但极性相反，判为冲突；slot、取值、极性都相同，判为等价；
   - **语义判定**：slot 不同或为 unknown 时，取向量相似度最高的 5 条生效记忆，调用 LLM 判断是否矛盾（`CONTRADICT / EQUIVALENT / UNRELATED`）。LLM 不可用时记为 `CONFLICT_CHECK_NOT_RUN`，候选进入人工复核，不自动生效。
4. 冲突处理：
   - 新信号来自用户明确表达，且时间更新：新记忆生效，旧记忆标为 `SUPERSEDED`，保留版本链；
   - 其余情况：两条都进入复核队列，复核界面并排展示。

### S2 证据累积的门控评分（解决 M2、M6）

1. 每条记忆维护 Beta 分布参数 `(α, β)`：
   - 先验取决于来源类型：明确表达 `(4, 1)`，推断 `(1.5, 1.5)`，模型推断 `(1, 2)`；
   - 每出现一次等价信号 `α += w`，其中 `w` 按来源类型和间隔会话数加权，同一会话内的重复只计一次；
   - 每出现一次反驳信号或负面效果反馈 `β += w`。
2. 置信度取后验均值 `α / (α + β)`；不确定性取后验方差，方差大时即使均值过线也进入复核。
3. 风险分改为由三项组合：类别基础风险（forbidden 类风险高于 style 类）、作用域（工作区级高于用户级）、与生效记忆的冲突情况。阈值全部移到 `noteweave.memory.policy.*` 配置项。
4. M6：等价信号不再跳过，改为强化已有记忆（更新 α、`last_reinforced_at`、`reinforcement_count`）。新措辞更具体时（LLM 判断）生成新版本。

### S3 与问题相关的记忆注入（解决 M3）

1. 记忆分成两类：
   - **常驻类**：style、forbidden、interaction，影响所有回答，始终参与选择；
   - **按需类**：terminology、structure 中针对特定主题的条目，需要和当前问题相关才注入。
2. `compileChatControlPack` 等方法增加 `query` 参数。按需类记忆的相关度 = 向量相似度与 BM25 分数做 RRF 融合；记忆文本的向量在生效时计算并缓存。
3. 选择方式：价值 = 置信度 × 效用 × 相关度（常驻类相关度取 1），在 token 预算内按价值与 token 数之比贪心选取。编译 trace 记录每条记忆的入选或落选原因。
4. 编译缓存的 key 加入查询的规范化指纹；按需类记忆为空时退化为当前的缓存行为。

### S4 候选匹配改为索引查询（解决 M4）

1. 先按 `slot_key` 和 `task_neighborhood` 在 SQL 里过滤；
2. 语义候选用 ES 的向量检索（复用资料检索的 embedding 客户端，新建 `noteweave-memory` 索引），只取前 5 条；
3. 记忆生效、撤销、改版时同步更新索引，走现有 outbox。

### S5 去掉正则初筛，改为轻量分类（解决 M5）

1. 删除 `DURABLE_CUE` 作为是否抽取的前置条件。
2. 改为两段式：
   - 先用小模型或同一模型的短提示做二分类（这条消息是否包含对回答方式的要求或反馈），输出不超过 10 个 token；
   - 判定为是的消息再进入完整抽取。
3. 新增对隐式反馈的抽取：用户对上一条回答的不满（太长了、看不懂、别用英文），抽成 `FORBID` 或 `REQUIRE` 信号，来源类型记为 `CONVERSATION_FEEDBACK`。
4. LLM 不可用时退回现有的规则抽取，规则只作为降级方案。

### S6 效果反馈闭环（解决 M7）

自动采集以下几类反馈，写入 `memory_application_outcome`，作用对象是该次运行实际注入的记忆（控制包里已有 `memory_object_ids`）：

| 信号 | 来源 | 映射 |
|---|---|---|
| 重新生成回答 | 前端重新生成按钮，后端 AnswerRun 记录 `regenerated_from` | `RETRIED` |
| 用户对回答点赞、点踩 | 前端新增反馈按钮和接口 | `POSITIVE` / `NEGATIVE` |
| 产物被用户编辑 | 产物保存时计算编辑距离比例 | `EDITED`，`editMagnitude` 取编辑比例 |
| 下一轮用户消息被 S5 识别为对上一条回答的不满 | 抽取任务 | `NEGATIVE` |
| 下一轮正常追问且无不满 | 抽取任务 | `ACCEPTED`（权重减半） |

负面反馈只作用于与反馈内容相关的记忆：先由 LLM 判断反馈针对哪个方面（长度、语言、格式等），再映射到对应 slot。无法判断时平均分摊，并降低权重。

### S7 话题切分加入语义相似度（解决 C1）

1. 消息向量与话题向量（话题内用户消息向量的指数滑动平均）计算余弦相似度，和现有 TF-IDF 相似度做加权融合，权重和阈值用标注数据校准。
2. 句首延续正则不再直接决定结果，改为一个加分特征，并且只有当前消息的语义相似度也不低于下限时才生效。
3. 处于不确定区间的切分（现有 `UNCERTAIN` 状态）异步调用 LLM 复核，结果写回切分记录，下一轮上下文规划时生效。
4. 向量服务不可用时退回纯 TF-IDF，并记录降级原因。

## 4. 实施顺序

| 阶段 | 内容 |
|---|---|
| 2A | S1 + S4：槽位、迁移脚本、冲突检测、索引查询 |
| 2B | S2 + S5：证据累积评分、去掉正则初筛、隐式反馈抽取 |
| 2C | S3：与问题相关的记忆注入 |
| 2D | S6：效果反馈闭环（涉及前端按钮和接口） |
| 2E | S7：话题切分语义化 |

## 5. 评测

- **冲突检测**：构造 100 对偏好（矛盾、等价、无关各约三分之一），统计 precision / recall；
- **记忆注入**：构造多轮对话集，统计关键约束命中率（应注入的记忆是否注入）和误注入率；
- **上下文**：与全量历史回放对比平均输入 token 和关键事实命中率，这是简历中 34% 和 92% 两个数字的出处，评测脚本和结果放进 `experiments/context-memory/`。

## 6. 参考业界方案后的修订

依据见 [00-业界方案调研](00-业界方案调研.md) 第二部分。

| 修订 | 影响的方案 | 来源 |
|---|---|---|
| 冲突处理两段式：槽位精确匹配能判定的直接处理，其余检索最相似的 5 条生效记忆，由模型选择 `ADD / MERGE / SUPERSEDE / NOOP`；输出不合规重试一次，仍失败进入复核 | S1、S4 | Mem0 |
| `memory_runtime_revision` 增加 `valid_from`、`invalid_at`；替代只设置失效时间，不删除；效果反馈按当时生效的版本归因 | S1、S6 | Zep / Graphiti |
| 新增后台整理任务：合并近似重复、复查长期未强化的记忆、重算 Beta 后验，使用较强的模型 | S2 | Letta |
| 注入价值 = 置信度 × 效用 × 相关度 × 新近度衰减，权重用评测集校准 | S3 | Generative Agents |
| 话题切分以改写后的独立问题计算向量，相邻消息相似度加深度分数找边界，显式切换规则保留为强信号；用 Pk、WindowDiff 评测 | S7 | TextTiling 系列研究 |
| 评测集覆盖知识更新和拒答 | 评测 | LongMemEval |

## 7. 实施记录

### 2A（2026-10-12 完成）

- 抽取输出增加 `slot`、`value`、`polarity`。规则降级先按语句归类，顺序是 forbidden、terminology、structure、interaction，其余为 style。槽位优先采用已经合法的 `slot`；没有时再按语言、长度、表格等特征推断，然后按类别落到 `answer.constraint`、`terminology.term`、`format.structure`、`interaction.style` 或 `answer.style`。显式 `REQUIRE` 或 `FORBID` 优先；信号类型为 `NEGATIVE` 或类别为 forbidden 时记 `FORBID`，其余记 `REQUIRE`。接口上看不到合法槽位、或存的是 `legacy:`、`candidate:` 身份键时，`slot_key` 返回 `unknown`，这个值不写回身份列。
- 冲突分两段。同槽同值且极性相反判 `POLARITY`，同槽取值不同判 `SLOT_VALUE`，同槽同值同极性判等价。其余在同一工作区和任务邻域内，用索引 `noteweave-memory` 取最相似 5 条生效记忆，由模型选择 `ADD`、`MERGE`、`SUPERSEDE`、`NOOP`。输出不合规时把错误附上并重试一次。
- 与第 6 节原文有两处差异，判断已写在进度看板协调区。槽位冲突以及模型给出的 `MERGE`、`SUPERSEDE` 都不在晋升时自动替换，复核必须 `REPLACE_EXISTING` 或 `REJECT`。确认后旧记忆改为 `SUPERSEDED`，对应 revision 写入 `valid_until`，不删除、不脱敏。用户主动 `REVOKE` 仍是 `DELETED`。`valid_from` 沿用现有列，失效时间继续用 `valid_until`，没有新增 `invalid_at`。
- 索引或向量不可用，并且没有同槽判定时，对外 `conflict.type` 为 `NONE`，降级原因 `MEMORY_CONFLICT_INDEX_UNAVAILABLE`。向量已经命中，但模型不可用、调用失败或两次输出都不合规时，对外为 `CHECK_NOT_RUN`，对内状态 `CONFLICT_CHECK_NOT_RUN`，不能自动生效。空索引按 `ADD` 处理。
- `GET /memory/review` 和 `GET /memory/items` 每一项返回 `slot_key` 与 `conflict`。列表不返回 `SUPERSEDED`。编译器仍只读 `ACTIVE` 且已批准的记忆。冲突未决时，被指向的旧记忆改为 `REVIEW_REQUIRED`，暂时离开控制包。
- 索引写入放在 `memory` 包的 `MemoryIndexOutbox`，文档字段包含工作区、记忆、槽位、展示文本、任务邻域和向量。不改 retrieval 的投影写入器。调度发现客户端不可用时直接返回，不累计失败次数。历史回填由 `MemorySlotBackfillService` 在开关打开时执行，默认关闭；模型给不出合法槽位时 `semantic_slot_key` 保持 null。
- 迁移脚本是 `V160__memory_slot_and_conflict_index.sql`。配置 `noteweave.memory.index-name` 默认 `noteweave-memory`，`index-dispatch-delay-ms` 默认 30000，`slot-backfill-enabled` 默认 false。
- 置信度、按问题注入记忆、反馈接口、去掉正则初筛，以及把等价跳过改成强化，都留到后续阶段。实现提交 `a9525db`。测试环境没有真实模型和真实 Elasticsearch，发件箱保持 `PENDING`。

### 2B（2026-10-12 完成）

- 证据改为 Beta 后验。`USER_FEEDBACK`、`PROJECT_DECISION`，以及 explicit 为 true 的 `CONVERSATION_FEEDBACK`，先验是 (4, 1)，均值 0.8。隐式对话反馈和 `ARTIFACT_FEEDBACK` 先验是 (1.5, 1.5)，均值 0.5。`MODEL_INFERENCE` 先验是 (1, 2)，均值约 0.3333。信号上的 `confidence_score` 写入这个先验均值。效用分仍按原来源表计算。列表按 `evidence_alpha`、`evidence_beta` 现算 `mean = α/(α+β)` 和 `variance = αβ/[(α+β)^2(α+β+1)]`。历史行迁移默认 1.5 与 1.5。
- 风险分由类别、作用域、冲突相加。forbidden 0.28、terminology 0.18、structure 0.14、interaction 0.12、style 0.10。`WORKSPACE` 加 0.16，其他作用域加 0.06。`CONFLICTING_ACTIVE_MEMORY` 或 `CONFLICT_CHECK_NOT_RUN` 加 0.55，上限 0.99。阈值在 `noteweave.memory.policy`：证据 0.70、效用 0.60、风险 0.70、后验方差 0.04。方差超过 0.04 时，均值过线也进入复核，所以模型先验即使分数写成 0.98 仍是 `NEEDS_REVIEW`。策略版本改为 `memory-candidate-policy-v2`。候选晋升按 `WORKSPACE` 计风险。
- 新会话权重是来源权重除以 1 加已记录会话数。来源权重明确为 1、推断为 0.5、模型为 0.25。创建时记下当前会话，权重为 0，`reinforcement_count` 不加。同一 `session_key` 和方向再次出现不累计。`SUPPORT` 增加 α 并加一次强化次数，`CONTRADICT` 只增加 β。会话键优先用 `conversation:{id}`，查不到对话时用 `message:{messageId}`。
- 等价信号保留原记忆并增加 α。措辞不同且模型确认更具体时，`ACTIVE` 写新版本，`PROPOSED` 只改 `display_text`。模型不可用或两次输出不合规时只更新 α。反驳信号增加 β，新候选仍按 2A 进入复核，确认前不替换旧记忆。负面效果反馈写入 β 留到 2D。`revision.confidence` 仍是晋升时写入的风险分，运行时效用分继续读这一列。
- 抽取去掉 `DURABLE_CUE` 入队条件。非空且长度在上限内的消息都进入 `MEMORY_EXTRACTION`。模型可用时先用最多 10 个 token 做 yes/no 分类，不合规重试一次。回答 NO 时记 `CLASSIFIED_NO` 并且不抽取。回答 YES 时带上一条助手回答做完整抽取。模型不可用、分类失败或完整抽取不合规时退回规则。规则仍要同时命中长期措辞和指示语才算明确偏好，另外识别太长、太短、太啰嗦、看不懂、听不懂、说人话、简单点、详细点、别用英文、不要用英文、少用英文。这类隐式反馈的 explicit 为 false，来源是 `CONVERSATION_FEEDBACK`，极性是 `FORBID`，先验按推断，因此进入复核。
- 后台整理 `MemoryConsolidationService` 默认打开。首次等待和间隔都是 `consolidation-delay-ms`，默认 3600000。测试环境关闭；关闭时 `consolidate()` 直接返回，不访问数据库。模型不可用时不合并，原因记 `MEMORY_CONSOLIDATION_JUDGE_UNAVAILABLE`，仍会把 `candidate-v3`、已批准、`ACTIVE`、强化次数为 0、且 `last_reinforced_at` 早于 90 天的条目改为 `REVIEW_REQUIRED`。`observation-v1` 等历史记忆不自动移出控制包。合并要求同工作区、同合法槽、语句等价，并且模型返回 `merge` 为 true；输出不合规重试一次。保留强化次数更高的一条，另一条改为 `SUPERSEDED` 并写 `valid_until`，事件是 `MERGED_DUPLICATE`，索引操作是 `DELETE`。不删除正文，也不脱敏。
- `GET /memory/items` 增加 `confidence`、`reinforcement_count`、`last_reinforced_at`。`GET /memory/review` 本阶段不加这些字段。迁移是 `V161__memory_evidence_beta.sql`。`docker-compose.yml` 同步了 `NOTEWEAVE_MEMORY_CONSOLIDATION_ENABLED`，默认 true。
- 与第 6 节原文的差异已写在进度看板协调区。按问题注入记忆留到 2C，反馈接口留到 2D。实现提交 `b5252d0`。测试环境关闭了 LLM 和整理任务，短分类成功、措辞改写和近似合并没有用真实模型验证。
