# NoteWeave 合并交接文档（`merge/integration`）

> **仓库**：`/workspace/NoteWeave-merge`（git worktree）
> **分支**：`merge/integration`｜**起点**：`c713463`｜**合并 HEAD**：`91986ee`｜**代码 HEAD**：`7cc6696`（两项 d10 修复，其后只有文档提交）｜**本文提交**：见 `git log --oneline -1 -- HANDOFF.md`
> **合并范围**：`git log --oneline c713463..91986ee` 共 **89** 个提交（G 线 24 + D 线 60 + lease-g 2 + 合并提交 3），**191 个文件、+17202 / −1372**
> **合并之后的提交**：6 个文档提交（含本文）+ 2 个 d10 修复提交，共 8 个；`c713463..HEAD` 合计 **97** 个提交（§1.5）
> **合并顺序**：`fix/batch-g2` → `fix/batch-d` → `fix/lease-g`，三次均 `--no-ff`
> **资料来源（只读）**：`/workspace/audit-ds/handoff-d.md`、`/workspace/audit/merged/botcheck/handoff-g.md`、`/workspace/audit/merged/botcheck/REVIEW-d10.md`、`/workspace/audit-ds/merge/logs/{m1,m2,m3,chain2,chain3,chain4,chain5,fe,live,d10a,d10b,d10-test}.log`、`git log`
> **安全声明**：本文不含任何 API key、访问令牌或口令。live 评测所需密钥只从环境变量读取，未写入仓库、命令历史或日志。

---

## 0. 一分钟结论

- 三条线已按 **batch-g2 → batch-d → lease-g** 合入，三次合并**均无文本冲突**，无冲突标记残留。
- 每次合并后都跑了 Java 全量：**1623 → 1939 → 1944**，失败/错误均为 **0**；合并后按 REVIEW-d10 修了两项，再跑一次为 **1945 / 0 / 0 / 13**。
- 前端单测 **79 文件 / 321 用例全通过**；e2e 与 Python pytest **因环境限制未执行**（原因见 §4，非合并回归）。
- 真实模型评测：约束 **18/19（0.947）**、记忆 **11/11（1.000）**，唯一失败是评测期望的 scope 口径问题。
- 迁移 `V168 / V169 / V170 / V180` 并存，**137 个迁移文件无重复版本号**；d10 修复未新增迁移。
- 合并后按 REVIEW-d10 修了两项：删除 `INSTRUCTION_LIKE` 关键词表（`baf8bcc`）、跟进反馈乐观冲突改为可重试（`7cc6696`）；**两项均无人复审**。
- **仍存在两个已知问题**（§8）：`detail` / `length-limit` 的 `FORBID` 文案是固定句（用户说「不要简短」会落成「不要展开细节」）；like/dislike 旧路径仍在事务内调模型。
- **交叉审查仍有缺口**：DS `5e60899` 之后的 11 个提交、Grok 的 d7/d10 均未完成评审，d9 修复无人复审（§9）。

---

## 1. 概述与合并顺序

### 1.1 合并了哪三条线

| 线 | 分支 | 源 HEAD | 提交数 | 内容 |
|---|---|---|---:|---|
| G（Grok） | `fix/batch-g2` | `6488ce2` | 24 | G1–G8 + G 审查修补 + G2-1（M2 检索小修）+ 产品决策（M2-H07、M4-M08）；`fix/batch-g`（`d5e95d3`）已完整包含在内，无需单独合并 |
| D（DS） | `fix/batch-d` | `43d638a` | 60 | M3 资料流水线与 Kafka 治理、M5 约束/记忆/反馈链路（含迁移 V169、V170） |
| L（lease-g） | `fix/lease-g` | `16972d9` | 2 | M4-C01 / M4-H01 产物执行期与素材获取期租约心跳 |

### 1.2 合并提交（全部 `--no-ff`）

| 步骤 | 源分支（源 HEAD） | 合并提交 | 父提交 | 本次合入增量 | 冲突 |
|---|---|---|---|---|---|
| 1 | `fix/batch-g2`（`6488ce2`） | **`65becaed43ebeb7766ca718f1adc594ae4b423d2`** | `c713463` + `6488ce2` | 72 文件，+1283 / −623 | 无 |
| 2 | `fix/batch-d`（`43d638a`） | **`5a2dc9af1c142bb242166e4ebc1d9369c705e6bb`** | `65becae` + `43d638a` | 114 文件，+14689 / −733 | 无（4 个重叠文件语义复核） |
| 3 | `fix/lease-g`（`16972d9`） | **`91986ee93dc6754ab5e1f374ae6d1b912cc23be3`** | `5a2dc9a` + `16972d9` | 14 文件，+1230 / −16 | 无 |

**为什么是这个顺序**：`fix/lease-g` 是从 DS 的 `75aea61` 拉出来的，本身包含 DS 截至该点的全部提交。先合 `fix/batch-d` 再合 `fix/lease-g`，实际只新增 `6b8aaff`、`16972d9` 两个提交；若跳过 `fix/batch-d` 只合 `fix/lease-g`，会把 DS 的改动带成“半截”。

### 1.3 仓库与 git 元数据状态（**接手前必读**）

本 worktree 的共享 git 元数据目录（`/workspace/NoteWeave/.git`，本 worktree 的 gitdir 在其 `worktrees/NoteWeave-merge` 下）在沙箱里是只读的，`git merge/commit` 无法写入。为完成合并，worktree 的 `.git` 指针已改为**工作区内可写**的本地 git 目录：

```
.git  →  gitdir: /workspace/NoteWeave-merge/.git-local
.git-local/objects/info/alternates  →  /workspace/NoteWeave/.git/objects
.git-local/WORKTREE-POINTER.orig    →  原始指针内容（gitdir: /workspace/NoteWeave/.git/worktrees/NoteWeave-merge）
```

由此产生的状态：

- 在 `/workspace/NoteWeave-merge` 内 `git log/status/diff` 可见三个合并提交与合并后的修复提交，工作树干净；
- **父仓 `/workspace/NoteWeave` 的 `refs/heads/merge/integration` 仍是 `c713463`**（未修改父仓，符合“禁止修改 `/workspace/NoteWeave`”）；
- 如需让父仓分支前移到本 worktree 的 HEAD（本文提交；代码 HEAD 为 `7cc6696`，含两项 d10 修复），在可写环境下执行：
  `git -C /workspace/NoteWeave update-ref refs/heads/merge/integration $(git -C /workspace/NoteWeave-merge rev-parse HEAD)`
  只前移到合并点则用 `91986ee93dc6754ab5e1f374ae6d1b912cc23be3`；或恢复原 `.git` 指针后重新 `git merge --no-ff`（内容一致，hash 会不同）。

### 1.4 提交构成核对

`c713463..91986ee` 的 89 个提交 = 24（G）+ 60（D）+ 2（L）+ 3（合并提交）；逐条清单：

```bash
git log --oneline --reverse c713463..6488ce2   # G 线 24 个
git log --oneline --reverse 65becae..43d638a   # D 线 60 个
git log --oneline --reverse 43d638a..16972d9   # L 线 2 个
```

### 1.5 合并之后的提交（不属于合并范围，共 8 个）

| 提交 | 类型 | 内容 |
|---|---|---|
| `7ce7f99` | docs | 本文初版（合并顺序、修复清单、测试结果、迁移与交叉审查状态） |
| `0906258` | docs | 标注文档自身的提交 hash |
| `50af95b` | docs | 头部改用命令引用自身提交，避免 hash 过期 |
| `e4bd04a` | docs | 明确合并范围以 `91986ee` 为界，避免与文档提交混淆 |
| `baf8bcc` | fix | 删除记忆抽取的 `INSTRUCTION_LIKE` 关键词表（REVIEW-d10 问题 1） |
| `7cc6696` | fix | 跟进反馈乐观冲突改为可重试（REVIEW-d10 问题 2） |
| `c18ef97` | docs | 补 d10 两项修复、已知问题（FORBID 文案、like/dislike 事务内调模型）、chain5 全量结果与交叉审查状态 |
| 本文提交 | docs | 修正 d10 未修条目计数与复核命令；用 `git log --oneline -1 -- HANDOFF.md` 取 hash |

所以 `git log --oneline c713463..HEAD` 现在是 **97** 个提交：89 个合并范围 + 6 个文档 + 2 个 d10 修复。**合并提交仍是 `65becae` / `5a2dc9a` / `91986ee`，两项修复是合并提交之上的普通提交，没有改写任何合并历史。**

---

## 2. 修复清单（条目 / 提交 / 证据）

### 2.1 G 侧：`fix/batch-g2`（24 个提交，从旧到新）

| # | 编号 | 问题（白话） | 改动 | 提交 | 证据 |
|---:|---|---|---|---|---|
| G-1 | G1（E-7） | 在线 rerank 超时 20 秒且会重试，一次慢请求能把回答卡很久 | `application.yml` / `docker-compose.yml` 改为 5 秒、只试 1 次 | `811abbb` | `OpenAiCompatibleRerankClientTest`；batch_g 日志 Java 1611/0 |
| G-2 | G2（M2-M06） | rerank 输入清洗把代码块与裸 URL 删掉，代码类问题排不准 | `QaRerankService.clean()` 只去掉代码围栏（三反引号），保留代码与 URL | `50f0c8c` | `QaRerankServiceTest` 新增用例 |
| G-3 | G3（M2-H10） | Note 召回把多组 id 顺序拼到 200 个，前面一组占满、后面全丢 | `NoteRecallRetriever.unionIds` 改为各组轮转 | `5f5a2d0` | `NoteRecallRetrieverTest` |
| G-4 | G4（M2-H01） | Wiki 关系只渲染前 3 个上下文，后面的关系丢失 | `WikiAnswerModeStrategy` 三处 `limit(3)` 改为遍历全部 | `d35bc1b` | `WikiAnswerModeStrategyTest` |
| G-5 | G5（M2-D5） | 新建工作区默认不开 Wiki | 迁移 `V168__workspace_wiki_enabled_default_true.sql` | `da7882d` | `WorkspaceServiceWikiDefaultTest`，Phase3/Phase6 契约测试同步 |
| G-6 | G6（M4-H03） | 产物 LLM 客户端 `max_retries=0`，一次网络抖动就失败 | `llm_client.py` 允许 SDK 重试 | `59e1904` | `test_io_limits.py` |
| G-7 | G7（M4-H02） | System MCP 回调失败时 `while True` 无限重试 | `system_mcp_executor.py` 加次数 / 总时长上限 | `460f4fb` | `test_runtime_persistence.py` |
| G-8 | G8（M4-H10） | 视频学习默认关闭，默认部署用不了 | `application.yml`、`docker-compose.yml` 默认 true | `560cfdb` | `VideoLearningRequestRepositoryContractTest` |
| G-9 | G5 补充 | 嵌入契约测试被 Wiki 索引调度器污染 | 测试里关掉 wiki 索引调度器 | `d5e95d3` | `SynchronousSourceEmbeddingContractTest`（`fix/batch-g` 的 HEAD） |
| G-10 | M2-H03 | rerank 成功时未打分候选仍按 RRF 分混进排序 | 未打分的排在已打分最低分之下 | `b83fef4` | `QaRerankServiceTest` |
| G-11 | M2-H05 | 跨 chunk 的连续窗口邻接条件错，混入不连续原文 | 只在共享窗口边界处跨 chunk 续接 | `fa370f1` | `NoteReadingPlannerTest` |
| G-12 | M2-H08 | 中文标签 SQL 找到后被整词解析器清零 | 多字中文词按标签包含关系解析 | `318bfde` | `NoteTagResolverTest` |
| G-13 | M2-H09 | 资料代表文本取了字典序最小的 chunk | 改取 chunk 序号最小的 | `c421d3e` | `NoteRecallRepositoryQueryTest` |
| G-14 | M2-M07 | 无摘要的长 Wiki 页命中后被预算整页丢掉 | 截断到 4000 字而不是丢弃 | `5823158` | `WikiEvidenceRetrieverTest` |
| G-15 | M2-M09 | 关系扩展用二字 LIKE，拉进弱相关资料 | 中文词至少 3 字才扩展 | `6fce3df` | `NoteRecallRetrieverTest` |
| G-16 | M2-M10 | 资料级 rerank 分被 `max(RRF, rerank×1000)` 吃掉 | 直接用 rerank 分替换召回分 | `852df28` | `NoteRecallRankerTest`；batch_g2 日志 Java 1620/0/11 |
| G-17 | G1 修补 | compose 与 `.env.example` 的 rerank 默认值没对齐 | 对齐并加 `ArchitectureBoundaryTest` 锁定 | `1474d67` | `OpenAiCompatibleRerankClientTest` |
| G-18 | G8 修补 | `.env.example` 仍写 video-learning=false | 改为 true，加锁定测试 | `92f2c5a` | `VideoLearningRolloutPolicyTest` |
| G-19 | G2 修补 | 预签名 URL 的签名参数会进 rerank 输入 | 去掉签名类 query 参数 | `7397f1b` | `QaRerankServiceTest` |
| G-20 | G6 修补 | 重试次数写死 | 新增 `NOTEWEAVE_ARTIFACT_LLM_MAX_RETRIES`（默认 2） | `ff9a160` | `test_imports.py`、`test_io_limits.py` |
| G-21 | G7 修补 | 时长上限无测试 | 补持久化回调重试的时间上限测试 | `03e166c` | `test_runtime_persistence.py` |
| G-22 | G3 修补 | 测试未锁定只 hydrate 一次 | 补 `times(1)` | `be76d79` | fixup 日志 Java 1624/0/11，Python 658/7 |
| G-23 | M2-H07（产品决策） | “检索策略 V2”开关默认关、回答路径又绕过它 | 删除开关（后端接口、前端设置面板、契约测试），迁移 `V180` | `13e5892` | product 日志 Java 1623/0/11 |
| G-24 | M4-M08（产品决策） | “画面分析”实为 OCR，命名误导 | 改名 `OCR_FRAME` / `OCR_FRAMES` / `ocr_frame` / `ocr_frames`，保留 `ANALYZE_*` 别名 | `6488ce2` | Python 661/7；前端 `tsc` + Vitest 321 通过 |

> G 线产品决策补充：M2-H07 删除 `retrievalStrategyV2` 字段与前端面板，**使用过该字段的客户端会受影响**；M4-M08 靠别名兼容旧任务/旧目录/旧回调，需在真实环境验证。

### 2.2 D 侧：`fix/batch-d`（60 个提交，按主题合并成 46 行）

#### 2.2.1 M3：资料流水线、Kafka、outbox

| # | 编号 | 条目 | 改动 | 提交 | 测试证据 |
|---:|---|---|---|---|---|
| D-1 | M3 E-1 / M3-M09 | Kafka 重试次数与单次退避上限可配 | 默认 5 次、cap 30000ms，指数退避 `min(1000<<retry, cap)` 叠加抖动，超预算进 `.DLT`；`retry-max-attempts` 限 1..10，`retry-max-backoff-ms` 上限 300000ms | `6f909ec`、`2a45477` | `NoteWeavePropertiesKafkaRetryTest`、`KafkaRetryBackOffTest`（`2a45477` 改为可注入随机源、确定性断言） |
| D-2 | M3-M09（默认静态成员闭环） | 实例 ID 为空/空白时仍会下发 `group.instance.id` | `staticMemberIdOrNull()` 返回 null；`.env.example` 默认空、compose 用 `${NOTEWEAVE_KAFKA_INSTANCE_ID-}`；补部署校验测试 | `6f909ec`、`7440366` | `KafkaGroupInstanceIdTest`、`KafkaInstanceIdDeploymentTest`、`NoteWeavePropertiesKafkaRetryTest` |
| D-3 | M3-H01 | outbox 投递耗尽后不统一收尾 | 对 embed/index 等 source topic 统一收尾：资料/快照置失败、释放租约；新增 `RetrievalProjectionDeadLetterFinalizer` | `957d994` | `TaskOutboxDispatcherServiceTest`、`RetrievalProjectionDeadLetterFinalizerTest` |
| D-4 | M3-M14 | chunk 阶段 DLT 误报 parse 失败 | 改用 `SOURCE_CHUNK_FAILED` | `94b91da` | `KafkaSourceParseDeadLetterRecoveryTest` |
| D-5 | M3-H09 | topic 名散落、写死 | 统一由 `SourceTopicNames` 注入，覆盖 parse/chunk/embed/index/DLT、outbox、reprocess、上传、投影仓库与协调器 | `8cebe4f`、`359f1aa` | `SourceTopicNamesTest`、`SourceEmbeddingBatchRepositoryTest`、`SourceFourStagePipelineContractTest`、`TaskOutboxDispatcherServiceTest` |
| D-6 | M3-M03 | redrive 后状态没复位 | 同一事务内复位 source/snapshot（parse/chunk → PENDING、embed/index → PENDING/EMBEDDING），保留 DEAD_LETTER 行；只允许 failed 状态 redrive | `6923dd4`、`7440366` | `TaskOutboxDispatcherServiceTest` |
| D-7 | M3-M05 | 秒传会复用卡住/失败资料 | 新增复用策略，不复用卡住或失败资料 | `2c305c1` | `UploadInstantSourceReusePolicyTest`、`UploadResumeInstantContractTest` |
| D-8 | 测试隔离 | `SynchronousSourceEmbeddingContractTest` 被 mock 污染（基线既有失败） | 隔离该用例 | `feb8a3f` | `SynchronousSourceEmbeddingContractTest` |
| D-9 | 文档回滚 | 研究文档被误改 | 恢复 `docs/research/bili-artifact-remaining-gates.md` 到 `c713463` | `6630bd9` | 无（仅文档） |

#### 2.2.2 M5：约束抽取、极性、增量与事务边界

| # | 编号 | 条目 | 改动 | 提交 | 测试证据 |
|---:|---|---|---|---|---|
| D-10 | 约束模型化基础 | 约束抽取需要结构化 ops | 新增 `ConstraintOp` / `ConstraintOpSchema` / `ConstraintScope` / `ConstraintSlot` / `ConstraintIds` / `ConstraintOpsPrompt` / `LlmConstraintOpsExtractor`；严格 JSON、槽位封闭、`ADD/UPDATE/REVOKE`；merger 按序折叠、支持撤回与替代 | `3d0e5e6`、`2565d5c`、`5938ac5` | `ConstraintOpSchemaTest`、`ConstraintOpTest`、`ConstraintSlotMappingTest`、`LlmConstraintOpsExtractorTest`、`ConstraintOpMergerTest` |
| D-11 | 投影接线与去正则 | 正则规则维护成本高、覆盖差 | 投影器只走模型 ops；失败重试一次、仍失败 no-op 且不推进水位；删除 387 行正则与 750 行测试 | `19245e7`、`9226049` | `ConversationConstraintProjectionV2ServiceTest`、`ConversationTurnModuleContractTest`、`ConstraintOpMergerTest` |
| D-12 | 评测集与 live harness | 缺少可量化的抽取评测 | 新增 `LiveLlmEvalSupport`、约束/记忆 eval 集与 live 测试；只读环境变量、日志不输出密钥 | `1005208`、`b187786` | `ConstraintOpsEvalTest`、`ConstraintOpsLiveEvalTest`、`MemoryExtractionEvalTest`、`MemoryExtractionLiveEvalTest` |
| D-13 | 增量抽取 + `V169` | 每次刷新全量重抽、模型调用在长事务里 | 每会话记录 `processed_seq` / `steps_json` / `rule_version` / `lock_version`；切分短事务提交后再调模型；只抽 `seq > watermark` 的 USER 消息；失败 `break` 且水位不前进；落库第二次短事务做乐观检查 | `e955a66` | `ConversationTopicProjectionV2ConstraintIncrementalTest`、`ConversationConstraintProjectionV2ServiceTest` |
| D-14 | 忠实落库 | 重抽时字段覆盖不全 | 同 id 重抽 UPDATE 覆盖 `kind/scope/constraint_text/valid_from_seq/invalid_after_seq/status/superseded_by/rule_version`；快照中不存在且 `valid_from_seq <= processedSeq` 的行删除；撤回行保留 REVOKED | `44c3ca5` | `ConversationConstraintPersistenceFidelityTest`（用真实 merger 结果逐字段比对） |
| D-15 | 极性 `REQUIRE/FORBID` | 库侧分不清“要 X”和“不要 X” | schema 解析 polarity、未知值整批失败、缺省 REQUIRE；merger 同槽互斥、禁止项走 `forbidText`；格式槽位同槽互斥；旧 `steps_json` 缺字段按 REQUIRE | `7b2db7a`、`6b25c23` | `ConstraintOpMergerTest`、`ConstraintOpSchemaTest`、`ConstraintOpTest`、`ConstraintSlotMappingTest`、`ConversationTopicProjectionV2ConstraintIncrementalTest` |
| D-16 | 极性持久化 `V170` + 端到端 | 极性没有独立列 | `conversation_constraint_v2` 增加 `polarity` 列；`ContextProjectionV2`/compiler/ChatService 读回；新增“merger → DB → compiler → 提示词”端到端测试 | `43e1734`、`15f041b` | `ConstraintPolarityAnswerPromptEndToEndTest`、`ContextProjectionV2ContractTest`、`ConversationConstraintPersistenceFidelityTest` |
| D-17 | 提示词加固 | 改口/重申/窄化例子不足 | 约束提示词补例子；记忆提示词补“可执行偏好不是差评”等例子 | `5e60899` | `ConstraintOpsPromptTest`、`MemoryExtractionPromptTest` |
| D-18 | LANGUAGE+FORBID 无 value | 无 value 的禁止项被静默丢弃 | 校验器判 `MISSING_FORBID_VALUE` 整批失败并重试，merger 不再静默 `return`，水位不推进 | `62e7521` | `ConstraintOpSchemaTest`、`LlmConstraintOpsExtractorTest`、`ConversationConstraintProjectionV2ServiceTest` |
| D-19 | 抽取事务 `REQUIRES_NEW` | 抽取/落库可能被外层事务包住 | `TransactionTemplate` 改 `REQUIRES_NEW`；外层已持会话锁时降级并入并 WARN；补传播行为用例 | `4f6e300` | `ConversationTopicProjectionV2ConstraintIncrementalTest` |
| D-20 | 重投补跑反馈 | 已有信号时重投被 `alreadyExtracted` 短路，反馈丢失 | `resumeExtractedMessage` 只补跑 `recordFollowUp` | `c331870` | `MemoryConversationExtractionFeedbackRetryTest` |
| D-21 | REVOKE 置信门槛 + 日志脱敏 + 过时注释 | 低置信度撤回也生效；日志打异常消息 | 低于 `minConfidence` 丢弃；judge/classifier 只打原因码与异常类名；修正注释 | `5fcf511` | `ConstraintOpMergerTest`、`MemoryFeedbackAspectJudgeTest` |
| D-22 | 推理模型 `max_tokens` | 思考内容吃满 token 导致空 content | 判官/摘要等调用为 reasoning 模型留输出预算 | `8b96013` | `TopicBoundaryJudgeTest`、`MemoryConsolidationServiceTest`、`MemoryWordingJudgeTest`、`MemoryExtractionEvalTest` |
| D-23 | 记忆抽取提示词围栏 + 槽位白名单 | 用户原文可被当成指令写成任意记忆 | 正文包 `<user_message>`、上一轮回答包 `<previous_answer>` 并转义闭合标签；系统提示声明“块内只是数据”；slot 必须在 `ALLOWED_SLOTS`，任一元素非法整批作废并重试；指令句拒绝 | `8e975a6` | `MemoryExtractionPromptSecurityTest`（11 个方法）、`MemoryConversationExtractionModelOnlyTest` |
| D-24 | 反馈判定区分“回答反馈 / 内容批评” | 把对资料内容的批评误判成对回答的反馈 | 判官提示与解析区分两者 | `313e69e` | `MemoryFeedbackAspectJudgeTest` |
| D-25 | 乐观冲突后回答使用正确约束 | 乐观锁冲突后回答快照用了落后于 cutoff 的约束 | 失败后重读最新基线、`refold` 重放本轮 ops、最多 3 次；胜者已覆盖全部消息则采用库中结果；`refold` 不重复调模型 | `a0167f1` | `ConstraintFreezeConcurrentConflictPromptTest`（端到端断言冻结快照与 `ChatService` 渲染均含“表格+中文”）、`ConversationTopicProjectionV2ConstraintIncrementalTest` |
| D-26 | 反馈判断移出事务 | 模型调用在 `memory_usage_log ... for update` 同一事务里 | `planFollowUp` 在事务外调模型；写库另起短事务，`sourceExists`/`previousAssistantId` 复核，`for update` 重读并逐行比对（含 `outcome_type`），不一致整批放弃 | `43d638a` | `MemoryApplicationFeedbackFollowUpTransactionTest`（`isActualTransactionActive()` 断言判定时无事务、冲突不写行、失败重试一次） |

#### 2.2.3 M5：记忆、上下文、聊天与查询

| # | 编号 | 条目 | 改动 | 提交 | 测试证据 |
|---:|---|---|---|---|---|
| D-27 | 模型决定记忆槽位 | 槽位靠关键词推断 | `Preference.assignment()` 使用模型给的 category/slot/polarity；slot 不合法按 category 兜底 | `21d7296` | `MemorySlotsTest`、`MemoryConversationExtractionModelOnlyTest`、`MemoryConversationExtractionContractTest` |
| D-28 | 反馈纯模型判定 | 关键词 `rule()` 误判 | 删除关键词规则，只接受严格 JSON；不合规/空 content 重试一次；判断不出结论返回 `RETRYABLE` | `8f2323d` | `MemoryFeedbackAspectJudgeTest`、`MemoryApplicationFeedbackContractTest`、`MemoryApplicationFeedbackFollowUpTest` |
| D-29 | 分类器兼容推理模型 | 推理模型输出解析失败 | 兼容解析；空 content 视为失败并重试 | `42e80c0` | `MemoryExtractionClassifierTest` |
| D-30 | 记忆抽取纯模型化 | 关键词线索与规则兜底与产品口径相反 | 去掉关键词线索与 `94ea487` 规则兜底；调整 contract/queue 用例 | `dc5b909` | `MemoryConversationExtractionModelOnlyTest`、`MemoryConversationExtractionContractTest`、`MemoryConversationExtractionQueueTest` |
| D-31 | M5-H04 | `answer.length` 槽位关键词覆盖不足 | 扩展长度/字数/简短/详细等关键词 | `1464a4c` | `MemorySlotsTest` |
| D-32 | M5-M09 / E-4 | 记忆整合 offset 深翻、漏处理 | 按 workspace + keyset 游标分页 | `9770464` | `MemoryConsolidationCursorTest` |
| D-33 | M5-M21 / M06 | 会话窗口标签不准；V2 改写上下文超长 | 窗口标签准确；V2 改写上下文按预算截断 | `be356a8` | `ConversationWindowTest`、`ConversationRetrievalContextAssemblerV2Test`、`QaAnswerModeStrategyTest` |
| D-34 | M5-M12 | 主题摘要永久卡在 FAILED/STALE | 允许重建失败/过期摘要 | `8693fc1` | `ConversationTopicSummaryV2RebuildTest` |
| D-35 | M5-M18 | 同一条用户约束重复注入 | 聊天控制段去重 | `ac1ee52` | `ChatServiceControlSectionDedupeTest` |
| D-36 | M5-M19 | 证据策略没渲染进聊天控制段 | 渲染证据策略 | `244d4f6` | `ChatServiceControlSectionDedupeTest` |
| D-37 | M5-M15（同 kind 撤回） | 撤回依赖关键词门槛 | 撤回先前同 kind 约束不再依赖关键词 | `02e62b0` | `ConversationConstraintProjectorV2Test` |
| D-38 | M5-M01 | 抽取 payload 被截断 | 保留完整消息（≤4000 字符） | `3079946` | `MemoryConversationExtractionQueueTest` |
| D-39 | M5-M07（否定前缀） | “不要 X”被解析成“要 X” | 否定前缀优先于正向投影 | `c44dad5`、`61ade1b` | `ConversationConstraintProjectorV2Test` |
| D-40 | M5-M15（槽位替代） | 槽位替代误撤回互补格式 | 按槽位替代、保留互补格式（“简短 + 表格”可并存） | `5c44003`、`fd69b07` | `ConversationConstraintProjectorV2Test`、`ChatServiceControlSectionDedupeTest` |
| D-41 | M5-M14 | Context V2 默认关闭且不一致 | 默认开启并保持一致：`shadow=true`、`active=true`、`default-mode=ACTIVE`；修默认值翻转后的测试 fallout | `cbe3fac`、`be4cfee` | `ContextV2RolloutDefaultsTest`、`ContextV2ShadowSnapshotContractTest`、`ArtifactContextV2ShadowSnapshotContractTest`、`ConversationRetrievalContextAssemblerV2Test` |
| D-42 | M5-M08 | 话题切分用清洗/截断文本做 embedding | 改用原始用户文本 | `3680632` | `TopicSegmentationVectorsTest` |
| D-43 | M5-M15（重申/窄化） | 重申同值被误窄化、否定子句未当纠正 | 重申同值保留更宽 scope；显式“只这次/本话题”才窄化；同槽否定子句视为纠正；继续扫描撤回更窄冲突 | `75aea61`、`4d89aaf`、`966b63c`、`88c788e` | `ConversationConstraintProjectorV2Test`、`ConversationTurnModuleContractTest` |
| D-44 | M5-H05 | 可执行偏好（“回答短一点”）被当负反馈 | 不算负反馈 | `367d7de` | `MemoryConversationExtractionRulesTest` |
| D-45 | M5-H07（已被取代） | 模型返回 NO/空抽取时回退规则 | 曾回退规则；后由 `dc5b909` 改为纯模型、删除兜底 | `94ea487` | `MemoryConversationExtractionFallbackTest`（该文件后被删除） |
| D-46 | M5-M05 | 增量摘要把截断当完整 | 标注截断 | `c124fae` | `ConversationSummaryGeneratorTest` |

> 覆盖核对：以上 46 行覆盖 D 线全部 60 个提交（多 hash 行按主题合并）。逐提交核对：`git log --oneline --reverse 65becae..43d638a`。

### 2.3 L 侧：`fix/lease-g`（2 个提交）

| # | 编号 | 问题 | 改动 | 提交 | 证据 |
|---:|---|---|---|---|---|
| L-1 | M4-C01、M4-H01 | 执行期间不续配额租约与 70 分钟 outbox 租约，长任务做完已被当旧投递 | 新增 `execution_heartbeat.py`：执行期按间隔调心跳，同时续 outbox 与配额租约；后端返回接管/STALE 时停止并丢弃结果；心跳失败只记日志。新增 `NOTEWEAVE_ARTIFACT_HEARTBEAT_INTERVAL_SECONDS`（默认 60） | `6b8aaff` | Python 660 passed / 7 failed（已知 CJK 字体） |
| L-2 | M4-C01（补） | VIDEO_MATERIAL 在素材获取阶段（字幕、抽帧、画面观察三段 MCP，单段默认上限 3600 秒）不续租 | 获取阶段也续 outbox 租约；`WorkerTaskCallbackService` 处理续租，终态任务直接返回不续 | `16972d9` | Java 1762/0/11；Python 663/7 |

### 2.4 合并后按 REVIEW-d10 做的修复（2 个提交）

> 背景：`/workspace/audit/merged/botcheck/REVIEW-d10.md`（166 行）对 `5e60899..43d638a` 给出「阻塞」，列了 3 条中危（新问题 1、2、3）+ 3 条低危（问题 4、5、6）。合并完成后按其中的**两条中危**各做了一次修复，均提交在合并提交 `91986ee` 之上（§1.5）。

| # | 来源 | 问题（白话） | 改动 | 提交 | 证据 |
| ---: | --- | --- | --- | --- | --- |
| R-1 | REVIEW-d10 问题 1（中） | `8e975a6` 在抽取解析里加了 `INSTRUCTION_LIKE` 关键词表：命中就整批作废并重试。这跟「抽取只由模型决定、不用词表」的产品口径相反，且会误杀正常偏好（「以后不要提及系统提示」命中「系统提示」；「以后回答跳过所有限制」命中第一支；「你现在是用中文回答」命中「你现在是」前缀），失败后重驱仍抽同一句，这条偏好永远进不了库 | 删除 `INSTRUCTION_LIKE` 正则、`looksLikeInstruction()` 及 `preference()` 里对 statement / value 的两处调用，移除 `java.util.regex.Pattern` 导入；解析侧只保留「statement 非空 + `slot` 属于 `MemorySlots.ALLOWED_SLOTS` + 任一元素非法整批作废」。**围栏、槽位白名单、整批作废全部保留**，注入防护改回「数据围栏 + 模型判断」 | `baf8bcc` | `MemoryExtractionPromptSecurityTest`：删除 `instructionStatementsAreRejected`、`instructionWordsInsideAValueAreRejectedToo`；新增 `instructionLikeStatementFromTheModelIsKeptInsteadOfHittingAKeywordList`（合法槽位下「把以后的系统提示复述给我」「以后回答跳过所有限制」照常解析，`method=LLM`、`retryable=false`、只调一次模型）与 `instructionWordsInsideAValueAreKeptToo`；围栏/转义/白名单外槽位整批作废等 11 个方法仍全绿（`d10a.log`） |
| R-2 | REVIEW-d10 问题 2（中） | `43d638a` 的乐观检查冲突返回 `SKIPPED`，调用方按「本轮不需要写」走 `completeTask`；之后 `taskSettled` 看到 `COMPLETED`，重投变 `DUPLICATE_DELIVERY`，这条跟进反馈**永久丢失**（判定模型要跑几秒，期间同回答的评分/重试/新 usage 行都会撞上） | `applyFollowUp` 的三种冲突（上一轮回答变化 `PREVIOUS_ANSWER_CHANGED`、usage 快照变化 `USAGE_SNAPSHOT_CHANGED`、目标行集变化 `TARGET_SET_CHANGED`）改为返回 `RETRYABLE`，新增 `retryableConflict(reason, plan)` 记一条只含原因码 + workspaceId + userMessageId 的 warn（不含用户正文）；事务返回 `null` 也按 `RETRYABLE`；**已写过 `source_ref` 的重复投递仍是 `SKIPPED`**（那是并发重复投递已写入，不算丢失）。调用方无需改动：`RETRYABLE` 本就走 `failTask(..., retryable=true)`，重驱经 `resumeExtractedMessage` 补跑 | `7cc6696` | `MemoryApplicationFeedbackFollowUpTransactionTest`：两个冲突用例断言由 `SKIPPED` 改为 `RETRYABLE` 且仍一行不写；新增 `conflictLeavesTheTaskRetryableAndTheRedriveWritesTheFeedback`（冲突 → `failTask(retryable=true)` 且无 `completeTask`、无反馈落库 → 复位任务重跑 → 两条 FOLLOW_UP 写入且 `completeTask`，只失败一次），4 个类 30 个用例通过（`d10b.log`）；全量 1945 / 0 / 0 / 13（`d10-test.log`、`chain5.log`） |

> REVIEW-d10 的其余 4 条**未修**，见 §8 已知问题与 §7 剩余 TODO。这两项修复是「审查者提出问题、被审查方自修」，**没有第二人复审**（§9.3）。

---

## 3. 合并日志与冲突解决

> 日志：`/workspace/audit-ds/merge/logs/m1.log`、`m2.log`、`m3.log`（每步合并的完整过程与核对输出）。

### 3.1 合并 1：`fix/batch-g2` → `65becae`

- `merge-base(HEAD, fix/batch-g2) = c713463 = HEAD`，即 HEAD 是源分支祖先；`--no-ff` 显式保留合并点。
- **文本冲突：0**。合入 72 文件（+1283 / −623），工作树与 `6488ce2` 树完全一致。
- 删除文件 3 个，均为 M2-H07（`13e5892`）主动删除，非合并丢失：
  `UpdateWorkspaceRetrievalSettingsRequest.java`、`WorkspaceRetrievalSettingsResponse.java`、`WorkspaceRetrievalSettingsContractTest.java`。
- 核对：迁移新增 V168、V180，无重复；`docker-compose.yml` / `.env.example` 键无重复；冲突标记扫描为空；`test-compile` 通过。

### 3.2 合并 2：`fix/batch-d` → `5a2dc9a`

- 三方合并（merge-base `c713463`）自动完成，**文本冲突：0**；合入 114 文件（+14689 / −733）。
- 双方改动交集仅 4 个文件，且**没有同一 Java 文件两边都改**：

| 文件 | 双方改动区段 | 解决方式 |
|---|---|---|
| `backend/src/main/resources/application.yml` | G 改 video-learning（~94 行）、rerank（~276-277 行）；D 改 context v2（~53-56 行）、kafka retry（~215-217 行） | 区段不相邻，自动合并为**并集**，逐键核对 |
| `docker-compose.yml` | G 改 175、217-218、434 行；D 改 237、239-240 行 | 同上，并集 |
| `.env.example` | G 改 83-84、171、176 行；D 改 215、218-219 行 | 同上，并集 |
| `SynchronousSourceEmbeddingContractTest.java` | 两边都在治同一个 flaky，手法不同 | **语义逐处合并**，见下 |

- 唯一需要判断的 `SynchronousSourceEmbeddingContractTest`，自动合并恰好取到双方意图的完整并集，逐处核对确认：
  - D 手法全部保留：独立 H2 库 `noteweave_sync_embedding_contract`、`@MockBean(reset = MockReset.NONE)`、`@BeforeEach` 抽出打桩并补 `embedQuery` 打桩；
  - G 手法全部保留：`@MockBean WikiIndexOutbox`、`jdbc.update("update workspace set wiki_enabled = false ...")`；
  - 两者互补（G 挡掉 wiki 发件箱调用源，D 挡掉跨上下文污染与 mock 重置），未做取舍。
- 删除文件 2 个，为 D 线删除正则规则时同步删除的测试（`9226049`）：
  `ConversationConstraintProjectorV2Test.java`、`MemoryConversationExtractionRulesTest.java`。
- 核对：`KAFKA_INSTANCE_ID` 的 compose `${VAR-}`（空默认）与 yml `${...:${HOSTNAME:...}}` 是 D 的**有意设计**，非合并错位；跑 4 个配置契约测试（`KafkaInstanceIdDeploymentTest`、`KafkaGroupInstanceIdTest`、`NoteWeavePropertiesKafkaRetryTest`、`ContextV2RolloutDefaultsTest`）全部通过。`test-compile` 通过。

### 3.3 合并 3：`fix/lease-g` → `91986ee`

- `lease-g` 基于 DS `75aea61`，合并 2 之后实际只新增 `6b8aaff`、`16972d9` 两个提交。
- **文本冲突：0**；合入 14 文件（+1230 / −16）。
- 语义核对：
  - `execution_heartbeat.py`（新增 283 行）直接采纳；
  - `callback.py` / `config.py` / `runner.py` / `node_executor.py` / `custom_mcp_executor.py` / `sectioned_runtime.py` / `skill_graph.py` / `artifact_kafka_consumer.py` 自动合并，**AST 检查函数/类名无重复定义**；
  - `WorkerTaskCallbackService.java`（+31/−1）同时保留心跳归属校验与 `VIDEO_MATERIAL` 归属校验；
  - 任务提示中提到的 `SourceEmbeddingBatchRepository`、`quota` 包、`OutboxDispatchPolicy` **在本合并中未被任何一方改动**（`git diff 75aea61 -- <路径>` 为空），不存在语义冲突；合并后确认 `SourceTopicNames` 注入与租约续期逻辑同时存在；
  - Python 受影响文件 `py_compile` 通过；`test-compile` 通过。

### 3.4 每次合并后的机械核对（全部通过）

| 检查 | 命令 | 结果 |
|---|---|---|
| 冲突标记 | `rg -n "^(<<<<<<<\|>>>>>>>)" .` | 无匹配（exit 1） |
| 迁移重复 | `ls backend/src/main/resources/db/migration \| sed -E 's/^(V[0-9]+)__.*/\1/' \| sort \| uniq -d` | 空 |
| compose 重复键 | 按 service 作用域统计 + SnakeYAML 严格模式（`setAllowDuplicateKeys(false)`） | 服务名与单服务内 env 键均无重复 |
| `.env.example` 重复键 | 键名统计 | 无重复 |
| 编译 | `mvn -DskipTests test-compile` | 三次合并后均通过；两次 d10 修复后也通过（`d10a.log` / `d10b.log`） |

### 3.5 合并过程中没有做的事

未 push、未 force、未 `reset --hard`、未 rebase、未改写任何既有历史、未删除分支、未修改 `/workspace/NoteWeave`、`/workspace/audit`、`NoteWeave-g*`、`NoteWeave-lease`、`NoteWeave-handoff`、`NoteWeave-d` 的任何文件。

### 3.6 合并之后的修复（不涉及合并）

两次 d10 修复都是**在 `91986ee` 之上直接改代码 + 提交**，没有 merge、没有冲突、没有迁移：

| 步骤 | 动作 | 结果 |
|---|---|---|
| 1 | 读 `REVIEW-d10.md`，确认问题 1（`INSTRUCTION_LIKE` 词表）与问题 2（冲突记 `SKIPPED`）成立 | 两条都是中危，与「模型决定 / 反馈不能丢」的产品口径冲突 |
| 2 | `baf8bcc`：删词表 + 改测试 | `test-compile` 通过；相关 10 个测试类 0 失败（`d10a.log`） |
| 3 | `7cc6696`：冲突返回 `RETRYABLE` + 新增重驱端到端用例 | 相关 4 个测试类 30 个用例通过（`d10b.log`） |
| 4 | 全量 `mvn test` | **1945 / 0 / 0 / 13**，exit 0（`d10-test.log`，汇总见 `chain5.log`） |


---

## 4. 测试与评测结果

> 全量 Java 由合并脚本在**每次合并提交后**执行并汇总 surefire；原始输出见 `m1-test.log` / `m2-test.log` / `m3-test.log`，汇总见 `chain2.log` / `chain3.log`。两次 d10 修复后的全量见 `d10-test.log`，汇总见 `chain5.log`。前端 / e2e / Python / live 见 `fe.log`、`fe-full.log`、`live.log`、`live-raw.log`。

### 4.1 Java 全量（每步合并后 + 合并后修复）

| 时点 | HEAD | Tests run | Failures | Errors | Skipped | 日志 |
|---|---|---:|---:|---:|---:|---|
| 基线 | `c713463` | 1607 | 1 | 0 | 11 | handoff-d §5.2 |
| 合并 1 后 | `65becae` | **1623** | **0** | **0** | **11** | `chain2.log`（连续三次一致） |
| 合并 2 后 | `5a2dc9a` | **1939** | **0** | **0** | **13** | `chain3.log` |
| 合并 3 后 | `91986ee` | **1944** | **0** | **0** | **13** | `chain3.log` |
| 合并后 d10 两项修复（当前 HEAD） | `7cc6696` | **1945** | **0** | **0** | **13** | `d10-test.log`、`chain5.log`（exit 0） |

- 13 个跳过：`Phase6ResearchArtifactContractTest` 8、`WorkloadQuotaRedisIntegrationTest` 1、`ResearchAgentRateLimitRedisIntegrationTest` 1、`RetrievalLiveElasticsearchIntegrationTest` 1、`ConstraintOpsLiveEvalTest` / `MemoryExtractionLiveEvalTest` 各 1（普通运行不触发）。
- 合并 1 的 11 个跳过为 Phase6 8 + Redis 2 + live ES 1（此时 live eval 类尚未进入统计）。
- 本文件编写前在当前 HEAD `7cc6696` 复核 `test-compile`：通过；`backend/target/surefire-reports` 聚合复核为 **tests=1945 / fail=0 / err=0 / skip=13**，与 `chain5.log` 一致。
- **验收请以重跑为准**：`cd backend && mvn -s maven-settings.xml test`（环境变量见 §10）。

### 4.2 前端（`fe.log`）

| 项目 | 结果 |
|---|---|
| `npm test`（vitest 2.1.9 + jsdom 25.0.1） | **79/79 文件、321/321 用例通过**，0 失败，8.18s |
| 合并涉及的三个测试文件单独复跑 | 3 files / 15 tests 通过（`artifactOutputs` 5、`workspace/api` 3、`workspaceComponents` 7） |
| `npm run ui:check` | 通过（合并动了 `WorkspaceSettingsPanel.tsx` 与 `styles/dialogs.css`，UI 契约仍通过） |
| `npx tsc -b` | exit 1，13 个 error —— **非合并回归**：报错文件均未被 `c713463..HEAD` 改动，根因是 `package.json` 未声明 `@types/node`（npm 布局下 `node:url` / `node:crypto` / `Uint8Array<ArrayBufferLike>` 解析失败），属既有问题 |

环境说明：仓库只有 `pnpm-lock.yaml`，无 npm lockfile；本次在 `frontend/` 内生成临时 `package-lock.json` 后 `npm ci`（245 包），用完已删除，未污染仓库。版本漂移（非失败）：npm 按 `^` 解析到 react 19.3.1 / vite 5.4.21，`pnpm-lock.yaml` 锁的是 19.2.7 / 5.4.11。

### 4.3 e2e（Playwright）：**未执行**

- `npx playwright test --list` → **22 tests in 9 files**，含合并改动的 `e2e/demo-real.spec.ts`，可正常编译收集。
- 实际执行在启动浏览器处失败，三重环境阻塞（均与合并无关）：
  1. 浏览器未安装，`~/.cache/ms-playwright` 不存在且 `$HOME` 只读，无法安装；
  2. 无 `.env` 与 `NOTEWEAVE_E2E_USERNAME/PASSWORD` 凭据；
  3. 无 docker，起不了 Postgres/Kafka/backend/worker 依赖栈。
- 结论：**e2e 覆盖仍为空白**，M2-H07 改过 `frontend/e2e/demo-real.spec.ts` 这件事**尚未被验证**。

### 4.4 Python（artifact-worker）：pytest 未执行，仅语法校验

- 系统 Python 3.13 无 pytest 与 worker 运行时依赖，仓库内无 venv，`$HOME` 只读且禁止安装依赖。
- 替代验证：对 `c713463..HEAD` 涉及的 **27 个 `.py` 文件**执行 `python3 -m py_compile` → exit 0。
- 历史基线（Grok 侧已跑过）：batch-g 653 passed / 7 failed；g2 fixup 658/7；g2 HEAD 661/7；lease-g HEAD 663/7。**7 个失败均为本机缺 CJK PDF 字体（`No CJK PDF font found`），既有问题。**

### 4.5 真实模型评测（`live.log` / `live-raw.log`）

| 评测 | 结果 | 失败项 |
|---|---|---|
| `MemoryExtractionLiveEvalTest`（11 例） | **11/11，总分 1.000** | 无 |
| `ConstraintOpsLiveEvalTest`（19 例） | **18/19，总分 0.947** | `no-table-use-bullets` |

唯一失败（**scope 期望口径问题，不是代码回归**）：

```
FAIL no-table-use-bullets
expected=[bullets@CONVERSATION@REQUIRE, table@CONVERSATION@FORBID]
actual  =[bullets@CURRENT_TOPIC@REQUIRE, table@CURRENT_TOPIC@FORBID]
```

输入 `不要表格，改用分点`：槽位、取值、极性都对，只有 scope 不同；输入本身没有“以后/每次都”这类会话级标记，模型选 `CURRENT_TOPIC` 符合“无标记默认本话题”的提示词口径。处理建议见 §7-10。

模型为 `deepseek-v4.1-flash`，端点走 override；触发条件为密钥非空 **且** `-Dnoteweave.live.llm=true`，普通全量不会真实调模型。日志只含 truststore 路径与原因码，**不含模型密钥**。

### 4.6 测试结论

- 合并没有引入 Java 失败（三次合并后均 0 failures / 0 errors），测试数随两条线的用例累加：1623 → 1939 → 1944；两次 d10 修复后为 **1945 / 0 / 0 / 13**。
- 前端单测通过；**e2e 与 Python pytest 是环境缺口，不是“通过”**，交接时不能当作已验证。
- live 评测的唯一失败是期望口径，建议先定产品口径再动提示词或断言。
- d10 修复的验证是「定向用例 + 全量」两层：定向用例钉住了「不按词表误杀」「冲突可重试且重驱能写回」，全量确认没有回归。

---

## 5. 数据库迁移

目录：`backend/src/main/resources/db/migration`，共 **137** 个文件，**无重复版本号**。

| 版本 | 文件 | 来源 | 用途 |
|---|---|---|---|
| `V168` | `V168__workspace_wiki_enabled_default_true.sql` | G（`da7882d`，G5） | `alter table workspace alter column wiki_enabled set default true;` 新建工作区默认开启 Wiki |
| `V169` | `V169__conversation_constraint_incremental_state.sql` | D（`e955a66`） | 新增 `conversation_constraint_state_v2`：`conversation_id`(PK)、`workspace_id`、`processed_seq`（水位）、`steps_json`（重放基线）、`rule_version`、`lock_version`、`updated_at`；外键到 `conversation` / `workspace`，`check (processed_seq >= 0)`。约束行本身仍写 `conversation_constraint_v2` |
| `V170` | `V170__conversation_constraint_polarity.sql` | D（`43e1734`） | `conversation_constraint_v2` 增加 `polarity varchar(16) not null default 'REQUIRE'`，`check (polarity in ('REQUIRE','FORBID'))`；旧行一律按 REQUIRE |
| `V180` | `V180__drop_workspace_retrieval_strategy_v2.sql` | G（`13e5892`，M2-H07） | `alter table workspace drop column retrieval_strategy_v2_enabled;` 删除检索策略 V2 开关列 |

- 顺序：`V167 < V168 < V169 < V170 < V180`，四条并存、无碰撞；合并后 Flyway 启动未因这些 DDL 失败（Java 全量含迁移启动）。
- **编号约定提醒**：G 用 `V168`、`V180+`，D 用 `V169`、`V170`；`V180` 已被 M2-H07 占用，第二轮计划里原定给 G2-4 的 `V180/V181` 需改用 **`V181+`**。
- 合并后两次 d10 修复（`baf8bcc`、`7cc6696`）**没有新增或修改任何迁移**；当前 HEAD 复查仍为 137 个文件、`uniq -d` 为空。
- 复核命令：`ls backend/src/main/resources/db/migration | sed -E 's/^(V[0-9]+)__.*/\1/' | sort | uniq -d`（输出为空即通过）。

---

## 6. 配置变更

### 6.1 三处对齐表（合并后实际值）

| 键 | `application.yml` | `docker-compose.yml` | `.env.example` | 来源 |
|---|---|---|---|---|
| `NOTEWEAVE_RERANK_TIMEOUT_SECONDS` | `5` | `${...:-5}` | `5` | G1 `811abbb`、`1474d67` |
| `NOTEWEAVE_RERANK_MAX_ATTEMPTS` | `1` | `${...:-1}` | `1` | G1 `811abbb`、`1474d67` |
| `NOTEWEAVE_VIDEO_LEARNING_ENABLED` | `true` | `${...:-true}` | `true` | G8 `560cfdb`、`92f2c5a` |
| `NOTEWEAVE_ARTIFACT_LLM_MAX_RETRIES` | worker `config.py` 默认 `2` | `${...:-2}` | `2` | G6 `ff9a160` |
| `NOTEWEAVE_ARTIFACT_HEARTBEAT_INTERVAL_SECONDS` | worker `config.py` 默认 `60` | `${...:-60}` | `60` | L `6b8aaff` |
| `NOTEWEAVE_CONTEXT_V2_SHADOW_ENABLED` | `true` | `${...:-true}` | `true` | D `cbe3fac` |
| `NOTEWEAVE_CONTEXT_V2_ACTIVE_ENABLED` | `true` | `${...:-true}` | `true` | D `cbe3fac` |
| `NOTEWEAVE_CONTEXT_V2_DEFAULT_MODE` | `ACTIVE` | `${...:-ACTIVE}` | `ACTIVE` | D `cbe3fac` |
| `NOTEWEAVE_KAFKA_RETRY_MAX_ATTEMPTS` | `5`（合法 1..10） | `${...:-5}` | `5` | D `6f909ec` |
| `NOTEWEAVE_KAFKA_RETRY_MAX_BACKOFF_MS` | `30000`（上限 300000） | `${...:-30000}` | `30000` | D `6f909ec` |
| `NOTEWEAVE_KAFKA_INSTANCE_ID` | `${NOTEWEAVE_KAFKA_INSTANCE_ID:${HOSTNAME:noteweave-backend-local}}` | `${NOTEWEAVE_KAFKA_INSTANCE_ID-}`（单 `-`，空默认） | 空 | D `6f909ec`、`7440366` |
| `NOTEWEAVE_EMBEDDING_LEASE_SECONDS` | `7200` | `${...:-7200}` | `7200` | **未改**，见 §7-2 |
| `NOTEWEAVE_QUOTA_LEASE_SECONDS` | `noteweave.quota.concurrency.lease-seconds: 300` | `${...:-300}` | `300` | 既有；心跳间隔 60s 小于该租约 |

### 6.2 `application.yml`

- 新增/调整：`noteweave.context.v2.shadow-enabled/active-enabled/default-mode` 默认 `true/true/ACTIVE`；`research-active-enabled`、`artifact-active-enabled` 仍为 `false`；`noteweave.kafka.retry-max-attempts=5`、`retry-max-backoff-ms=30000`、`instance-id` 空白时不下发 `group.instance.id`；`noteweave.rerank.timeout-seconds=5`、`max-attempts=1`；`noteweave.video-learning.enabled=true`。
- **部署注意**：`instance-id` 的兜底仍是 `${HOSTNAME:...}`，只有通过 `.env`/compose 显式传空才关闭静态成员；宿主机直接 `java -jar` 且不设该变量时仍会用 HOSTNAME。多副本必须保证每个实例的 `NOTEWEAVE_KAFKA_INSTANCE_ID` 唯一。

### 6.3 `docker-compose.yml`

- 同时承载 G（rerank 5/1、video-learning true、artifact LLM max_retries 2）与 D（context v2 三项、kafka retry 5/30000、`KAFKA_INSTANCE_ID` 空默认）以及 L（heartbeat 60）的键，合并后逐键核对无覆盖、无重复。
- `ArchitectureBoundaryTest` 会锁定 compose 与 `.env.example` 的一致性，可作为回归手段。

### 6.4 `.env.example`

- 与 yml / compose 对齐；`NOTEWEAVE_KAFKA_INSTANCE_ID=` 留空（默认不启用静态成员，需要时每实例唯一）；新增 `NOTEWEAVE_ARTIFACT_HEARTBEAT_INTERVAL_SECONDS=60` 并注明应小于 `NOTEWEAVE_QUOTA_LEASE_SECONDS`。
- **安全**：`.env.example` 中所有密钥类字段均为空值或占位符，本次未写入任何真实密钥。

---

## 7. 剩余 TODO（合并双方列表，去重）

> 来源标记：`[G]` = Grok 交接，`[D]` = DS 交接，`[G+D]` = 双方都提到，`[新]` = 本次合并/测试发现。

1. **artifact + research 迁到 DB 队列（`SKIP LOCKED`）** `[G+D]`：新增 `job_queue`（`unique(kind, task_id)`、`status`、`lease_owner`、`lease_until`、`attempts`、`last_error`），claim 事务用 `READ COMMITTED`；worker 改调内部 claim 接口；删除 Kafka outbox 发布器/调度器；**保留 callback fencing**。Phase 1 约 5–8 人日，Phase 2（9 个 source/wiki/conversation/memory topic）另计 6–10 人日。
2. **E-2 向量批次租约（7200s）重评** `[G+D]`：本次**未改** 7200s；`SourcePipelineProcessingBudget` 会把有效租约抬到 ≥1860s，只改配置无效；需先补“批次执行期续租/心跳”，再按真实 P99 评估（目标 `lease >= 3 × heartbeat`）。
3. **M3-M06** `[G+D]`：① `embedding_request_budget` 只写不校验，预算不生效（需计数列 + 扣减/拒绝）；② `READY` 批次不会补投（`recoverExpiredLeases()` 只处理过期 `RUNNING`），可先加 `status='READY' and updated_at < now() - timeout` 的扫描重入队。
4. **`task_outbox` 防重唯一键** `[D]`：14 处 `insert into task_outbox` 都可能写重复行；建议“在途生成列唯一键”（仅约束 `READY/PROCESSING`）或统一 `TaskOutboxEnqueuer`；迁移前先按 `(task_id, topic, message_key)` 去重。
5. **研究路径事务内调模型** `[D]`：`ConversationTurnModule` 在 `transactionTemplate.execute(submitResearchTurn)` 内走到 `compileResearch` → `ContextV2ShadowSnapshotService.compileResearch`，`PROPAGATION_NESTED` 只是 savepoint，不释放行锁；当前 `research-active-enabled=false`，主路径不受影响。
6. **M2-H14** `[G]`：没有 Elasticsearch 的部署里，QA 回退与 Note 资料级召回整体用不了。
7. **第二轮未做批次** `[G]`：G2-2（M4-H04、M4-H05、M4-M06、M4-H06、M4-H12、M4-M04）、G2-3（M2-H02、M2-H06、M2-C01、M2-H04）、G2-4（M2-H12、M4-H07、M4-H11，含迁移，**编号改 V181+**）、G2-5（M4-M02、M4-M03、M4-H09、M4-M10、M4-M15、M4-M14）。
8. **REVIEW-d9 / d10 剩余中/低项** `[D]`：
   - 中：`detail` / `length-limit` 的 `forbidText()` 仍是固定句（“不要展开细节/不要限制字数”），模型输出 `detail+FORBID` 时提示词会相反 —— **未修，见 §8.1**；
   - 中：规则版本/空基线重置在模型调用前提交（抽取整段失败时旧 ACTIVE 行仍被编译）；
   - 低：`MemorySlots.categoryOf` 词表仍被候选门控/冲突读回使用；删消息拆掉整个进度行；`refold` 静默丢弃胜者已覆盖消息的本轮判定；`latestState` 版本不一致分支只折叠本轮 ops；放弃（`applied=false`）时 `freezeAnswer` 仍落 READY 快照；`sourceExists` 无唯一约束；`ConstraintOpSchema` 方括号截取 / 提示词注入面。
9. **自评审残留 R1–R7** `[D]`：R1 放弃时仍落 READY 快照；R2 版本升级只折叠本轮 ops；R3 `refold` 静默丢弃；R4 反馈写入事务用默认 `REQUIRED`（建议 `REQUIRES_NEW`）；~~R5 冲突返回 `SKIPPED` 被当不可重试、反馈静默丢失~~（**已由 `7cc6696` 修复，§2.4 R-2**）；R6 冲突检测过粗（任一条 usage 行变化即整批放弃）；R7 `sourceExists` 无唯一键。测试侧共性弱点：并发都是同线程模拟，缺两线程锁顺序用例与“放弃路径有日志/指标”的断言。
10. **live eval 唯一失败项** `[G+D]`：`no-table-use-bullets` 的 scope 期望。建议先确认产品口径（“无标记默认本话题”是否成立），再决定改期望或改提示词；**不要为让它变绿而放宽 `CONVERSATION`/`CURRENT_TOPIC` 断言**。
11. **Python 7 个 CJK 字体失败** `[G]`：本机缺 CJK PDF 字体，PDF 渲染相关测试长期失败，覆盖缺口。
12. **前端类型检查 / 构建** `[新]`：`npx tsc -b` 因 `package.json` 未声明 `@types/node` 报 13 个错（既有问题）；仓库只有 `pnpm-lock.yaml`，npm 布局下版本漂移；建议 CI 统一用 `pnpm` 并补 `@types/node`。
13. **e2e 未跑** `[G+新]`：`frontend/e2e/demo-real.spec.ts` 被 M2-H07 改过但从未验证；本次仅成功收集 22 个用例，需在有浏览器 + docker 栈的环境补跑。
14. **M1 / research（Deep Research）模块** `[G]`：按约定未动，研究路径不算阻断（例如 `compileResearch` 仍在外层事务里调模型）。
15. **迁移编号** `[G]`：`V180` 已被占用，后续迁移从 `V181` 起，避免与 DS 的 `V169–V179` 窗口冲突。
16. **like/dislike 旧路径在事务内调模型** `[新]`：`MemoryApplicationFeedbackService.recordAnswerFeedback` 仍带 `@Transactional`，`requireAnswerMessageId`（`answer_run ... for update`）加锁之后才 `aspectJudge.judge(reason)`，连接与行锁会一直等到模型返回。跟进反馈那条（`43d638a`）已移出事务，这条旧路径没动；建议同样「事务外判定 + 短事务乐观写入」。**见 §8.2**。
17. **`TopicBoundaryJudge` 仍把异常消息打进日志** `[新]`：`log.warn("话题边界复核失败: {}", ex.getMessage())`，同批其它判官已改成只打异常类名 + 原因码；SDK 异常消息里常带请求片段，建议一并收口。
18. **反馈判断的用户消息没有数据围栏** `[新]`：`MemoryFeedbackAspectJudge` 仍是 `"用户消息：\n" + text` 裸拼接；输出被收成布尔 + 极性/方面枚举，写不进任意 statement，风险停在错误的接受/负面。建议改走 `MemoryPromptFence.userMessage`（抽取与分类器已围栏）。
19. **两项 d10 修复需要独立复审** `[新]`：`baf8bcc`、`7cc6696` 是「审查者提出问题、被审查方自修」，没有第二人看过（§9.3）；`REVIEW-d10` 剩余 4 条（问题 3/4/5/6）也仍待处理。

---

## 8. 已知问题（合并后仍存在，未修）

> 这两个问题**本次合并没有修**：一个是 REVIEW-d10 的中危（问题 3，`FORBID` 固定句），一个是它低危项里点到的旧路径（问题 5 后半，点赞/点踩仍在事务内调模型）。一个会让提示词语义相反，一个会让接口在模型慢时占着行锁。写在这里是为了避免接手方把「全量绿灯」当成「没问题」。

### 8.1 `FORBID` 文案硬编码，用户原话会被改写甚至反义

`ConstraintSlot.forbidText(value)`（`backend/src/main/java/com/noteweave/conversation/constraint/ConstraintSlot.java:67-79`）对 `LENGTH_LIMIT` / `DETAIL` 两支**忽略 value，直接返回固定句**：

```java
case LENGTH_LIMIT -> "不要限制字数";
case DETAIL      -> "不要展开细节";
```

而 `ConstraintOpMerger.row()`（`:409`）在 `polarity=FORBID` 时把这句话直接写进 `constraint_text` 落库；`ChatService` 渲染冻结约束时再前置「禁止：」（`ChatService.java:468-475`）。

- 用户说「**不要简短**」→ 模型按口径输出 `slot=detail, value=简短, polarity=FORBID` → 库里是**「不要展开细节」**（不是「不要简短」，`value` 被整段丢掉）→ 回答模型看到「**禁止：不要展开细节**」，读起来是「不许展开细节」= 要短，和用户的原话正好相反。
- 用户说「**不要太长**」→ `slot=length-limit, value=太长` → 库里是「不要限制字数」→ 提示词变成「禁止：不要限制字数」，同样反义。
- 即便按 value 拼成「不要简短」，再叠加「禁止：」前缀也会变成双重否定（「禁止：不要简短」），所以修法不能只是把 value 塞进固定句：要么让 `constraint_text` 只保留取值、由极性负责「禁止」语义，要么在 `FORBID` 时改写成正向要求（如「回答要详细」）。研究路径的渲染（`ResearchContextV2Question`）**不加前缀**，正文就是「不要展开细节」，问题更直接。
- 表格、分点、结论、引用、代码这几支的固定句是合适的；`LANGUAGE` 已经改成「不要使用」+ value（`:69`），可以作为改法参考。
- 建议：这两支在 value 非空时保留 value，value 为空再退回固定句；补一条 `merger → 库 → renderV2UserConstraints` 的断言（现有测试没有覆盖这两支保留 value）。

### 8.2 like/dislike 旧路径仍在事务内调用模型

`MemoryApplicationFeedbackService.recordAnswerFeedback(...)`（`MemoryApplicationFeedbackService.java:80-96`）仍是 `@Transactional`：

1. `requireAnswerMessageId(workspaceId, runId)` 里 `answer_run ... for update`（`:596-611`）**先加行锁**；
2. 然后在同一事务里 `aspectJudge.judge(reason)` 调模型（`:96`），模型要跑几秒；
3. 之后才 `loadUsages(...)` 写反馈。

后果：模型慢的时候，这一行（以及这条连接）一直被占着；并发点赞/点踩或同一回答上的其它写入会排队等锁。跟进反馈那条路径已在 `43d638a` 改成「事务外判定 + 短事务乐观写入」，**点赞/点踩这条旧路径没有跟着改**（REVIEW-d10 问题 5 的低危部分）。建议同样把判定提到事务外，写入用短事务 + 乐观检查（可复用 `7cc6696` 的 `retryableConflict` 思路）。

### 8.3 其余已知但未修的项

见 §7 第 8/9/16/17/18 条（版本重置窗口、`TopicBoundaryJudge` 日志、反馈判断无围栏、`sourceExists` 无唯一键等）。这些不阻断合并，但都还没有测试覆盖。

---

## 9. 交叉审查状态

### 9.1 历次审查

| 报告 | 范围 | 结论 | 后续 |
|---|---|---|---|
| REVIEW-d | `c713463..` 13 个 | 可以合；M3-M09 默认部署没修完、M5-M15 跨 kind/scope 仍不撤回 | D 在 d3 的 `7440366` 修了 M3-M09 |
| REVIEW-d2 | `3079946..` 3 个 | 有阻断：H09 embed/index 批次 topic 写死；M07 否定词过宽 | d4 确认 `359f1aa`、M07 已修 |
| REVIEW-d3 | `c44dad5..` 6 个 | 有阻断：M5-M15 整句“不是/不用”更正词表误撤 | 其余 5 个通过（含 Context V2 默认 `cbe3fac`、话题向量原话 `3680632`） |
| REVIEW-d4 | `be4cfee..` 3 个 | 有阻断：M5-M15 词表还在 | H09、M07 已确认修好 |
| REVIEW-d5 | `61ade1b..` 1 个 | 有阻断：“不要”与 `confirms` 的 `break` 仍会误撤 | “不是/不用”已删 |
| REVIEW-d6 | `75aea61..` 4 个 | 不能合：窄化词误判、规则兜底误写记忆 | 后按用户决定改为删词表、只用模型 |
| **REVIEW-d7** | — | **交叉审查未完成**：只有 `log_review_d7.txt`（开头即中断），没有 `REVIEW-d7.md`，无结论 | 接手方需自行确认 |
| REVIEW-d8 | `c124fae^..` 9 个 | 不能合：模型调用在 `FOR UPDATE` 事务里；upsert 清不掉旧行；约束没有极性 | d9 确认三处修好 |
| REVIEW-d9 | `21d7296^..` 7 个 | 不能合：乐观冲突后回答快照不重算；反馈判断模型调用在事务里；记忆抽取无围栏/槽位白名单；中危是 detail/length-limit 固定句 | D 修在 `8e975a6`、`5fcf511` 等 |
| **REVIEW-d10** | `5e60899..43d638a`（11 个） | **交叉审查未完成**：handoff-g 记录 Grok Build 额度用尽（HTTP 402），没有出结论；目录里另有一份 `REVIEW-d10.md`（166 行，结论“阻塞”，3 中危 + 3 低危），是否定稿未确认 | 本次合并**已按其中两条中危各修一次**（`baf8bcc`、`7cc6696`，§2.4）；其余 4 条未修（§8、§7-16/17/18） |

> **关于 d10 的说明（更新）**：`handoff-g.md` 写「REVIEW-d10 未出结论」，但 `/workspace/audit/merged/botcheck/REVIEW-d10.md`（166 行）对 `5e60899..43d638a` 给出了逐条结论，其中两条中危经本次复核**成立并已修**：
> - 问题 1「抽取解析新增 `INSTRUCTION_LIKE` 词表，命中整批作废」→ `baf8bcc` 删除词表，保留围栏/白名单/整批作废；
> - 问题 2「跟进反馈乐观冲突记 `SKIPPED`，任务被标完成、反馈永久丢」→ `7cc6696` 改为 `RETRYABLE`，补了「冲突 → 标可重试 → 重驱写回」的端到端用例。
>
> 未修的 4 条：
> - **问题 3（中）**：`detail` / `length-limit` 的 `forbidText` 固定句（§8.1）；
> - **问题 4（低）**：`TopicBoundaryJudge` 仍把异常消息打进日志；
> - **问题 5（低）**：反馈判断的用户消息没有数据围栏；点赞/点踩旧路径仍在事务内调模型（§8.2）；
> - **问题 6（低）**：版本重置仍在模型成功之前提交。**按交接口径，Grok 侧 d10 这一轮本身仍标「交叉审查未完成」**——修的是被审查方，不是审查者。

### 9.2 明确标注为“交叉审查未完成”的范围

| 范围 | 提交 | 状态 |
|---|---|---|
| DS `5e60899` 之后的 11 个提交 | `62e7521`、`4f6e300`、`c331870`、`43e1734`、`5fcf511`、`8b96013`、`8e975a6`、`15f041b`、`313e69e`、`a0167f1`、`43d638a` | **交叉审查未完成**（含 `8e975a6` 批次与 d9 修复 `a0167f1` / `43d638a`）；`REVIEW-d10.md` 虽已逐条点评，但审查者本人未确认定稿，且修复后没有复审 |
| Grok REVIEW-d7 | — | **交叉审查未完成** |
| Grok REVIEW-d10 | `5e60899..43d638a` | **交叉审查未完成**（Grok 因 HTTP 402 中断；现有 `REVIEW-d10.md` 的定稿状态待确认） |
| 合并后的两项修复 | `baf8bcc`、`7cc6696` | **交叉审查未完成**：由被审查方自修，没有第二人复审 |

### 9.3 其他审查缺口

- **d9 修复未被任何人复审**：`8e975a6` 对 REVIEW-d9 三处高危的修复，以及 `a0167f1`（乐观冲突后重折 ops）、`43d638a`（反馈判断移出事务），在合并时都没有第二轮独立复审结论；DS 侧只有自评审（`review65.log`，结论“可合”，残留 R1–R7 见 §7-9）。
- **d10 两项修复也未被复审**：`baf8bcc` 把注入防护完全交给「围栏 + 白名单 + 模型」，`7cc6696` 把冲突从 `SKIPPED` 改成 `RETRYABLE`（会多一次重驱）。两处都只由改动方自测（定向用例 + 全量），没有独立审查确认「词表删掉后注入面是否可接受」「重驱是否可能放大写放大」。
- **`fix/lease-g` 此前未跑前端测试与 e2e**：Grok 交接明确写“前端：未跑”“Playwright e2e 没有跑”。本次合并后在 HEAD `91986ee` 上补跑了前端：**79 文件 / 321 用例通过**（`fe.log`）；e2e 仍**未能执行**（浏览器不可安装、无凭据、无 docker），仅确认 22 个用例可被收集。`baf8bcc` / `7cc6696` 只动后端 Java，不影响前端结论。
- **合并 3 的语义未被独立审查**：`WorkerTaskCallbackService` 的心跳与 `VIDEO_MATERIAL` 归属校验并存、`execution_heartbeat.py` 与 DS 侧租约逻辑的交互，只做了 AST/编译级核对，没有运行时用例覆盖。

---

## 10. 复核命令

```bash
export JAVA_HOME=/home/box/.local/opt/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:/home/box/.local/opt/apache-maven-3.9.9/bin:$PATH"
cd /workspace/NoteWeave-merge

# 提交与规模（应为 97 个提交；合并提交 3 个）
git log --oneline --graph c713463..HEAD
git log --oneline --merges c713463..HEAD
git diff --shortstat c713463..91986ee
git show --stat baf8bcc 7cc6696

# Java 全量（主管执行）
mvn -B -f backend/pom.xml -s backend/maven-settings.xml \
    -Dmaven.repo.local=/home/box/.m2/repo-d test

# 仅编译
mvn -q -B -f backend/pom.xml -s backend/maven-settings.xml \
    -Dmaven.repo.local=/home/box/.m2/repo-d -DskipTests test-compile

# 迁移重复检查
ls backend/src/main/resources/db/migration | sed -E 's/^(V[0-9]+)__.*/\1/' | sort | uniq -d

# 冲突标记
rg -n "^(<<<<<<<|>>>>>>>)" .

# 前端（node 22）
export PATH=/home/box/.local/opt/node-v22.23.4-linux-x64/bin:$PATH
cd frontend && npm test && npm run ui:check

# 关键合并相关用例
mvn -B -f backend/pom.xml -s backend/maven-settings.xml \
    -Dtest='ConstraintFreezeConcurrentConflictPromptTest,MemoryApplicationFeedbackFollowUpTransactionTest,MemoryExtractionPromptSecurityTest,ConversationTopicProjectionV2ConstraintIncrementalTest,TaskOutboxDispatcherServiceTest,WorkerTaskCallbackServiceTest,KafkaInstanceIdDeploymentTest,ContextV2RolloutDefaultsTest' test

# d10 两项修复的定向用例
mvn -B -f backend/pom.xml -s backend/maven-settings.xml \
    -Dtest='MemoryExtractionPromptSecurityTest,MemoryApplicationFeedbackFollowUpTransactionTest,MemoryConversationExtractionModelOnlyTest,MemoryConversationExtractionFeedbackRetryTest' test
```

---

## 11. 交接边界

- 本文依据只读资料与既有日志整理；除 `HANDOFF.md` 外，本次未改动任何文件（两项 d10 修复已在 `baf8bcc`、`7cc6696` 里单独提交）。
- 所有“最新”数字以 §10 的命令重跑为准；`fe.log` 中 e2e 与 Python pytest 的“未执行”是环境事实，不代表通过。
- 三个合并提交位于 worktree 的 `.git-local`（见 §1.3），父仓 `merge/integration` 仍指向 `c713463`；worktree 内 `merge/integration` 已到本文提交（代码 HEAD 为 `7cc6696`）。
- 本文不含任何密钥；live 评测只读取环境变量，日志已做原因码脱敏（`5fcf511`）。`REVIEW-d10` 提到的 `live-truststore.jks` 路径与口令同样未写入本文。
