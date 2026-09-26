# DR-302 负例报告：类型 / 精确引文 / 来源关系校验逐原因码拒绝证明

- **计划条目**：M3 DR-302「实现类型、精确引文和来源关系校验 | 1.5 天 | 错误类型与伪引文被拒绝 | 负例报告」
- **证据产物**：`backend/src/test/java/com/noteweave/research/ResearchAgentEvidenceEnforcementNegativeTest.java`（23 个用例，23/23 通过 = 15 个 reason code 覆盖矩阵 20 例 + DR-112 不可达不变量 3 例）
- **被观测对象（未改动）**：`ResearchEvidenceQualificationService.validateEvidence` / `validateBinding`、
  `ResearchTypedClaimValidator`、`ResearchAgentCompletionCommitter.validateEvidenceAuthority`、
  `ResearchAgentCompletionCanonicalizer.validate`、`ResearchAgentCompletionMergePlanner`
- **门禁前提**：DR-301 已使被否候选集合（`rejectedCandidateKeys()`）成为按 Candidate 的写前/写后硬门禁。
  本报告验证的是「每一个 reason code 是否真的导致拒绝」，**不新增、不修改任何校验逻辑**。

---

## 1. 覆盖矩阵

「可达」= 该 reason code 能由一次真实完成信封（`ResearchAgentCompletionService.complete`）产生，
并且该 Candidate 在库内没有被提升。
「不可达」= 该分支在更早一层就被拦下，或服务端派生的 authority 使分支恒不成立；
报告中给出**实际拦截它的层**与**替代断言**（替代断言同样真实跑过）。

| # | reason code | 触发方式（构造要点） | 断言结果 | 库内状态断言 | 是否可达 | 备注 |
|---|---|---|---|---|---|---|
| 1 | `NUMBER_VALUE_CONTRADICTION` | claim `value-1` / 精确引文 `trusted quote 0`（引文含数字 0），relation `SUPPORTS`，candidateValue == claim | `rejectedMerges` 1 条：`from=0, to=0, decision=REJECTED`, 原因码 `EVIDENCE_QUALIFICATION_REJECTED` | `candidate_value=old-0`、`cell_status=CANDIDATE_READY`、`cell_version=0`、`evidence_refs_json=null`、`last_merge_id=null`；validation `REJECTED`；`research_cell_evidence=0` | 是 | 字面规则（SUPPORTS + 值一致）本会提升到 v1 → **DR-301 前后行为反转**；同时断言 `validation_mode=SHADOW`、`strict` 关闭，证明门禁不依赖 flag |
| 2 | `UNIT_CONTRADICTION` | claim `latency 100 ms` / 引文 `latency 100 s`（同数值、不同单位） | 同上 | 同上 | 是 | 同上 |
| 3 | `DATE_VALUE_CONTRADICTION` | claim `revenue in 2019` / 引文 `revenue in 2020` | 同上 | 同上 | 是 | 日期跨度内的数字被去重，不会误报数值冲突 |
| 4 | `COMPARISON_DIRECTION_CONTRADICTION` | claim `growth increased 5` / 引文 `growth decreased 5` | 同上 | 同上 | 是 | 数值 5 一致，仅方向矛盾，只出方向码 |
| 5 | `TYPED_FACT_MISSING_FROM_CITATION_SPAN` | claim `value 42` / 引文 `trusted statement`（引文无任何类型化事实） | 同上 | 同上 | 是 | 伪引文主路径：claim 的类型化事实在引文跨度里找不到 |
| 6 | `RELATION_NOT_SUPPORTING` | claim/引文/candidateValue 三者一致，relation `WEAK_SUPPORT` | `rejectedMerges` 1 条 `to=0`，原因码 `NOT_ENOUGH_INFO` | 同上 | 是 | 字面规则本就拒绝；DR-301 的价值是补上可审计 reason code |
| 7 | `CLAIM_CANDIDATE_MISMATCH` | claim `alpha claim` ≠ candidateValue `value-0`，relation `SUPPORTS` | `rejectedMerges` 1 条 `to=0`，原因码 `NOT_ENOUGH_INFO` | 同上 | 是 | 同上 |
| 8 | `SOURCE_AUTHORITY_MISSING` | 信封无法构造：authority 由服务端重建 | — | — | **否** | 被 `ResearchAgentCompletionCommitter.validateEvidenceAuthority`（:473-514）先拦：`RESEARCH_AGENT_COMPLETION_EVIDENCE_UNGROUNDED`；`source == null` 在真实链路恒不成立。**替代**：服务层 `evaluate(taskRow, envelope, Map.of())` → `REJECTED` + 含该码 |
| 9 | `QUOTE_EMPTY` | 信封无法构造：`quote_text=""` | — | — | **否** | 被 `ResearchAgentCompletionCanonicalizer.validate`（:`245` `text(quote_text, …, allowBlank=false)`）先拦：`RESEARCH_AGENT_COMPLETION_INVALID`。**替代①**：完成信封抛 `INVALID` 且零库内写入；**替代②**：服务层 `evaluate(quote="")` → `REJECTED` + `QUOTE_EMPTY` |
| 10 | `CLAIM_EMPTY` | 信封无法构造：`claim_text=""` | — | — | **否** | 同上（:`246`）。**替代①**：信封抛 `INVALID` 且零写入；**替代②**：服务层 → `REJECTED` + `CLAIM_EMPTY`（candidateValue 同为 `""`，排除 mismatch 干扰） |
| 11 | `RELATION_UNKNOWN` | 信封无法构造：`relation_type=UNSUPPORTED` | — | — | **否** | 被 canonicalizer 白名单 `RELATIONS={SUPPORTS,WEAK_SUPPORT,CONFLICTS}`（:`71`、:`247`）先拦：`INVALID`。**替代①**：信封抛 `INVALID` 且零写入；**替代②**：服务层 → `REJECTED` + `RELATION_UNKNOWN`（+`RELATION_NOT_SUPPORTING`） |
| 12 | `SOURCE_DOMAIN_MISSING` | authority 服务端派生，domain 恒非空 | — | — | **否** | workspace：`workspace-source:<id>`；外部归档：`normalizeSourceDomain(url, provider, id)`（:`492`）恒有 fallback。**替代**：服务层伪造 `sourceDomain=""` → `REJECTED` + 该码 |
| 13 | `LINEAGE_DIGEST_INVALID` | authority 服务端派生，lineage 恒为合法 sha256 | — | — | **否** | workspace：`sha256Hex(trustedContent)`；外部归档：`contentSha256` 且入库前已与 `sha256Hex(content)` 比对（:`485`）。**替代**：服务层 `lineage="not-a-digest"` → `REJECTED` + 该码 |
| 14 | `SNAPSHOT_NOT_QUALIFIED` | 信封 `snapshot_status` 被限制为 `{WORKSPACE, EXTERNAL_ARCHIVED}` | — | — | **否** | canonicalizer（:`248`）先拦 `INVALID`；authority 的 snapshotStatus 正由此派生（:`481`/`:490`）。**替代①**：信封 `UNARCHIVED` → `INVALID` 且零写入；**替代②**：服务层 authority `snapshotStatus="UNARCHIVED"` → `REJECTED` + **仅**该码（`containsExactly`） |
| 15 | `QUALIFIED_SOURCE_REQUIRED` | 信封要求 `candidate.evidence_keys` 非空；独立来源计数恒 ≥1 | — | — | **否** | canonicalizer 要求 evidence_keys 非空；`candidateQuorum==1 && independentSourceCount<1` 分支要求 authority 不完整，而派生 authority 恒完整。**替代**：服务层 authority 空集 + `candidateQuorum=1` → `REJECTED` + 该码 |

**汇总**：15 个 reason code 中 **7 个可达**（全部通过完成信封端到端拒绝）、**8 个不可达**（全部给出实际拦截层 + 已跑通的替代断言）。
没有任何一个 code 被跳过或伪造。

### 库内状态断言（每个「可达」用例都逐条断言，非仅断言返回值）

- `research_cell`：`candidate_value = old-0`、`cell_status = CANDIDATE_READY`、`cell_version = 0`、
  `evidence_refs_json = null`、`last_merge_id = null`（与完成前逐列指纹一致；`active_task_id`/`updated_at`
  不计入，因为提交成功后释放 cell 绑定是预期行为）；
- `research_evidence_validation`：`final_status = REJECTED`、`reason_codes_json` 含目标原因码；
- `research_cell_evidence`：该 Run 计数为 0；且按 `source_evidence.agent_completion_id` 关联后仍为 0 →
  **该 Evidence 确实没有进入 `research_cell_evidence`**；
- 收条：`acceptedMerges` 为空、`rejectedMerges` 单元素且 `to_version == from_version`；
- 收条 `rejectedMerges[0].reason_code`：代码 1–5 断言为 `EVIDENCE_QUALIFICATION_REJECTED`，代码 6–7 断言为
  `NOT_ENOUGH_INFO`。这不是装饰：`ResearchAgentCompletionMergePlanner.planSingle`（`:46-54`）只会在
  `literalSupport == true`（`SUPPORTS` + `candidateValue == claimText`）**且** qualification 否决时才产出
  `EVIDENCE_QUALIFICATION_REJECTED`。因此该断言**直接证明代码 1–5 的字面绑定是通过的**，也就证明在 DR-301
  之前它们会被提升为 `VERIFIED` —— 拒绝只能来自本报告所验证的 qualification 门禁，而非字面规则。

---

## 2. 对照组（证明门禁没有收得过严）

`shouldStillPromoteASupportsCandidateWhoseExactQuoteCarriesTheClaimTypedFact`：

- `relation_type=SUPPORTS`；
- 引文 `trusted quote 0` 精确落在可信来源 sample 内，且**含有 claim 的类型化事实**（数值 0），
  `typed_facts_json` 状态为 `ENTAILED`；
- 来源权威完整（`workspace-source:source-0` + 合法 lineage + `WORKSPACE` 快照）。

结果：Candidate **正常提升** —— `acceptedMerges = [(entity-1:field-0, 0→1, ACCEPTED, VERIFIED_AND_VERSION_MATCHED)]`，
`research_cell` 变为 `cell_status=VERIFIED / cell_version=1 / candidate_value=value-0 / evidence_refs_json=[ev-…]`，
`research_cell_evidence` 1 行，`research_evidence_validation.final_status=QUALIFIED`。

---

## 3. 实际执行的命令与结果

```
.\mvnw.cmd -f backend/pom.xml -o -Dtest='ResearchAgentEvidenceEnforcementNegativeTest' -DfailIfNoTests=false test
  → Tests run: 20, Failures: 0, Errors: 0, Skipped: 0     BUILD SUCCESS

.\mvnw.cmd -f backend/pom.xml -o -Dtest='com.noteweave.research.*Test' -DfailIfNoTests=false test
  → Tests run: 298, Failures: 0, Errors: 1, Skipped: 1    BUILD FAILURE
```

回归口径说明：基线为 **267 run / 0 failures / 1 skipped**。本轮 **298 = 267 + 20（本任务新类）+
8（并发新增 `ResearchAgentRunCompletionGateTest`，DR-305）+ 3（并发新增
`ResearchAgentReplanAuditDiscoveryWiringTest`，DR-110）**。

**整包回归中观测到 1 个 error（仅陈述观测，不作归属结论）**：它出现在
`ResearchAgentReplanAuditDiscoveryWiringTest`（他人并发新增、git 未跟踪）的
`acceptedDiscoveryRevisionShouldPersistOneScopeExpandedReplanAuditRow`，报错为
`org.h2.jdbc.JdbcSQLNonTransientException: No data is available [2000-224]`；该类我单独运行时观测到
3 run / 3 failures。**我无法在本任务验证窗口内对该红灯的归属下结论**——主对话已核实：该测试与其依赖的
`ResearchDiscoveryProposalService.java` 在我观测时点正处于并发编辑中（台账已更正，此红非其缺陷）。
可确定的观测只有两条：①该类我观测到时为红；②本任务新增类在同一整包中为
**20 run / 0 failures / 0 errors**（当时 20 例；DR-112 后为 23 例）。

另记录一次**瞬时红灯（自愈）**：首轮整包回归一度出现 `Tests run: 254, Errors: 75`，全部为
`ApplicationContext failure threshold (1) exceeded: skipping repeated attempt to load context`，即某个
Spring 上下文加载失败后，后续共享该上下文的类被连带跳过。同批次并发执行体收口后重跑即为上表的
298/1，故判定为并发编辑窗口内的瞬时状态，非本任务缺陷。**本任务未改动主代码、迁移、Python、
`ResearchAgentCompletionServiceTest` 及任何既有测试断言。**

---

## 4. 缺陷与未决风险（均未自行修改主代码）

1. **`validateBinding` 的 `evidence.isEmpty()` 分支不可达（主对话裁决：不修代码，改用测试固化）。**
   该分支会算出 `QUALIFIED_SOURCE_REQUIRED`，但 `ResearchEvidenceQualificationService.evaluate()`
   只在 `for (Evidence evidence : bound)` 循环内写 validation 行；`bound` 为空时**根本不产生 validation 行**，
   因此该 REJECTED 不会进入 `rejectedCandidateKeys()`。经复核，`bound` **可证不可能为空**，由三条独立的
   canonicalizer 不变量保证：
   ① `validateCandidate` 拒绝 `evidence_keys` 为空的 candidate；
   ② `validate` 拒绝引用 envelope 中不存在 evidence 的 candidate（"Candidate evidence_keys are duplicated
   or outside the envelope"）；
   ③ `validate` 拒绝 envelope 内重复的 `evidence_key`，故 `evidenceByKey.get` 永不返回 null。
   因此该分支**今天零影响（不可达）**，而非「静默失效风险」。
   主对话裁决：**不新增 candidate-only validation 行**（那会让不可达代码变成永久不可测代码，且需
   `evidence_id` 可空 + `persist` 改造，比现状更差）；正确做法是把这三条不变量**固化为 CI 门禁**，
   谁将来放宽，测试先红 —— 已由 DR-112 落地（见 §5）。
   注：另一条 `candidateQuorum==1 && independentSourceCount<1` 分支是有效的（bound 非空时会落行）。

2. **跨语言语义不一致（不属于 DR-302 范围，仅记录）。**
   Python `workers/research-worker/app/claim_fact_validator.py` 会产出 `LOCAL_POLARITY_CONTRADICTION`
   与 `NO_TYPED_CLAIM_FACTS`，而 Java `ResearchTypedClaimValidator` 只实现了
   日期 / 单位 / 数值 / 比较方向 / 引文缺失五类，没有极性（polarity）对应实现。
   Java 侧 `NO_TYPED_CLAIM_FACTS` 也只在 `NOT_APPLICABLE` 状态出现，不会写入 reason_codes。
   若 Python 侧依赖这些码做门禁，两边判定会分叉。

3. **并发窗口内的观测（不含归属结论）。**
   验证期间我观测到 `ResearchAgentReplanAuditDiscoveryWiringTest` 为红（整包回归 1 error；我单独运行该类
   3 failures；根因为 `No data is available [2000-224]`）。该观测**不作归属判断**：主对话已核实该时段
   `ResearchDiscoveryProposalService.java` 与其测试正被另一执行体并发编辑（台账已更正，此红非其缺陷）。
   我只陈述：本任务未触碰该文件及其依赖；其最终归属以主对话台账为准。另：并发新增的
   DR-305 `ResearchAgentRunCompletionGateTest` 在我观测时为绿（8 例）；首轮整包曾出现一次瞬时上下文加载
   失败（75 errors，全部 `ApplicationContext failure threshold exceeded`，随后自愈，见 §3）。

---

## 5. DR-112：把「使死分支不可达」的三条不变量固化为 CI 门禁

`validateBinding` 的 `evidence.isEmpty()` 分支不可达，由三条 canonicalizer 不变量保证（见 §4.1）。
为防止未来有人放宽其中任一条导致门禁静默失效，新增 3 个用例，每个都断言 canonicalizer 的拒绝码
`RESEARCH_AGENT_COMPLETION_INVALID`、**拒绝消息片段**（证明是目标规则抛出，而非被更早规则拦下）、以及
零写入（`research_evidence_validation` 0 行；`research_cell` 指纹不变；`research_agent_completion` /
`source_evidence` / `research_cell_evidence` 均 0 行）：

| 用例 | 守护的不变量 | 依赖它的代码 | 触发方式 | 断言消息片段 |
|---|---|---|---|---|
| `shouldRejectACandidateWithNoEvidenceKeysBeforeAnEmptyBindingCanReachQualification` | candidate 必须引用 ≥1 个 evidence key（`validateCandidate`） | `validateBinding` 的 `evidence.isEmpty()` 分支 | candidate `evidence_keys=[]`（非 `null`） | `evidence references are invalid` |
| `shouldRejectACandidateReferencingAnEvidenceKeyOutsideTheEnvelope` | candidate keys ⊆ envelope evidence keys（`validate` 子集检查） | `evaluate()` 的 `evidenceByKey` 查找 | candidate 引用不存在的 key | `duplicated or outside the envelope` |
| `shouldRejectACandidateWithDuplicateEvidenceKeys` | candidate keys 唯一（`validate` 子集检查的 size 分支） | 同上 | candidate `evidence_keys=[k,k]` | `duplicated or outside the envelope` |

**非空转保证**：用例 1 用 `evidence_keys=[]`（非 `null`，排除另一条路径）且版本/置信度均合法；
用例 2 断言所引用 key 唯一（只可能命中 "outside" 子句）；用例 3 断言所引用 key 确在 envelope evidence 中
（只可能命中重复/size 子句）。

**主代码改动**：仅在 `ResearchEvidenceQualificationService.java` 的 `evidence.isEmpty()` 分支前**新增一段注释**
（无任何逻辑改动），写明三条不变量依据与未来陷阱：一旦上游放宽，此 rejection 无法落成 validation 行、
`rejectedCandidateKeys()` 看不到它、门禁会静默失效。注释最终文本随回传提供。

---

## 6. 交付文件清单

- 新增：`backend/src/test/java/com/noteweave/research/ResearchAgentEvidenceEnforcementNegativeTest.java`（23 用例）
- 修改（DR-112 授权，**仅新增注释、零逻辑改动**）：
  `backend/src/main/java/com/noteweave/research/ResearchEvidenceQualificationService.java`
- 新增（唯一文档产物）：`experiments/deep-research/reports/DR-302-negative-cases.md`
- 未改动：迁移、Python、`ResearchAgentCompletionServiceTest` 及任何既有测试断言。
