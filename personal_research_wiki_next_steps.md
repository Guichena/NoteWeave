# NoteWeave 个人研究 Wiki 后续操作步骤与开发计划

## 1. 当前已经完成的基础能力

本轮个人方向的核心改造已经从「资料摘要型 wiki」推进到「研究问题驱动的个人认知系统」。当前后端已完成以下主干能力：

### 1.1 ResearchQuestion 问题层

已在 `ResearchProject` 与 `ChatSession` 之间新增 `ResearchQuestion` 层。

当前结构变为：

```text
ResearchProject
  └─ ResearchQuestion
       └─ ChatSession
```

作用：

- 一个 project 下可以同时推进多个研究问题。
- 多个 chat session 可以绑定到同一个 research question。
- 记忆、判断、结论可以按问题隔离，避免多对话并行时上下文串味。

已完成内容：

- `ResearchQuestion` 实体、状态、Repository、Service、Controller、DTO。
- `ChatSession` 增加 `researchProjectId` / `researchQuestionId`。
- `SessionSummary` 增加 `researchQuestionId`。
- `MemoryContextService` 升级为：

```text
question claims -> question summaries -> session summaries -> space memory -> user memory
```

### 1.2 Claim 判断层

已新增 `Claim` 作为研究问题下的判断、假设、结论、开放问题、决策单元。

当前结构变为：

```text
ResearchProject
  └─ ResearchQuestion
       ├─ ChatSession
       └─ Claim
            ├─ ClaimConceptRelation
            └─ ClaimCitation
```

作用：

- 结论不再漂浮在 summary 里，而是被显式结构化。
- 同一概念可以跨问题复用，但具体结论必须绑定到某个 research question。
- `supersedesClaimId` 支持旧判断被新判断取代。

已完成内容：

- `Claim` 实体、枚举、Repository、Service、Controller、DTO。
- `claim_concept_relation`：把 claim 和 concept 绑定，关系类型支持 `SUPPORTS / CONTRADICTS / RELATED`。
- `claim_citation`：把 claim 和 citation 绑定，保留证据链。
- `ClaimService.summarizeCurrentForQuestion`：回答时注入当前问题的有效判断，并过滤掉被 supersede 的旧 claim。

### 1.3 跨问题比较

已完成 concept 维度的跨问题比较能力。

接口：

```http
GET /api/v1/personal/concept-cards/{conceptCardId}/cross-question-comparison
```

作用：

- 查看同一个 concept 在不同 research question 下分别支持了哪些判断。
- 自动检测冲突：
  - claim stance 出现 `SUPPORTED` 与 `REFUTED` 对立；或
  - relationType 出现 `SUPPORTS` 与 `CONTRADICTS` 对立。

这是个人研究系统区别于普通笔记的关键能力：系统不再强行把知识合并成一个全局结论，而是保留「同一概念在不同问题里的不同角色」。

### 1.4 跨问题 Claim 召回

已完成基于 queryText 的跨问题 claim 召回。

作用：

- 当前问题回答时，除了读取当前 question 的已有结论，还会召回用户其他 research question 里与当前问题相关的旧判断。
- 目前实现为 DB + 内存关键词重叠排序，未接 ES。

当前 prompt 注入顺序大致为：

```text
Current question conclusions
Related conclusions from your other research questions
Current research question memory
Relevant session summaries
Workspace long-term memory
User stable preferences
Evidence
User question
```

---

## 2. 当前能力边界与注意事项

### 2.1 Claim 已进入回答上下文，但还不是独立检索语料

当前 claim 的进入方式是：

- 当前 question 的 claim：通过 `researchQuestionId` 直接读取。
- 其他 question 的 claim：通过 DB + keyword overlap 召回。

尚未完成：

- claim 独立建 ES index。
- claim 作为 `HybridRetriever` 的一条召回通道参与 RRF 融合。

短期不建议马上做 ES 化，因为个人 claim 数量通常较小，当前 DB + 内存排序足够支撑 MVP；当单用户 claim 数达到几千甚至上万时，再考虑独立索引。

### 2.2 当前还缺少稳定的「问题综述页」

现在已经有：

- 问题层；
- 判断层；
- 概念关系；
- 跨问题比较；
- 回答时读取已有判断。

但还缺少一个用户能直接消费的稳定产物：

```text
一个 research question 当前到底研究到哪一步？
目前结论是什么？
依据是什么？
争议点是什么？
下一步是什么？
```

所以接下来最推荐做的是：**ResearchQuestion Overview / Synthesis Page 自动生成**。

### 2.3 EntityCard 仍未做

`EntityCard` 用于承载人物、公司、产品、论文、方法、工具、指标等实体锚点。

它很重要，但更偏底层归并，不如问题综述页直接提升用户体验。因此建议排在问题综述页之后。

---

## 3. 推荐的下一阶段目标

下一阶段建议围绕一个目标展开：

> 把 research question 从「结构化存储对象」升级为「可持续推进、可生成综述、可对外输出的研究工作台」。

优先级建议：

```text
P0：问题综述页生成
P1：问题工作台 API 聚合
P2：Claim 自动抽取与写回
P3：EntityCard 实体锚点
P4：Claim ES 化 / HybridRetriever 融合
```

---

## 4. P0：问题综述页生成

### 4.1 目标

为每个 `ResearchQuestion` 生成一份稳定的 markdown 综述视图。

它不是新的事实源，而是由结构化对象投影出来的页面：

```text
ResearchQuestion + Claims + Concepts + Citations + SessionSummary -> Question Overview Markdown
```

### 4.2 产物形态

建议新增一个轻量实体：`ResearchQuestionOverview`。

字段建议：

```text
id
spaceId
userId
researchProjectId
researchQuestionId
title
summary
currentAnswer
keyClaimsJson
supportingEvidenceJson
conflictsJson
openIssuesJson
nextStepsJson
markdown
generatedFromSnapshotJson
createdAt
updatedAt
```

说明：

- `markdown` 是前端展示和导出的主内容。
- `generatedFromSnapshotJson` 保存本次生成时引用的 claimId、conceptId、citationId，便于追溯。
- 不建议把 overview 作为唯一事实源，事实仍在 claim / concept / citation 中。

### 4.3 Service 设计

新增：

```text
com.noteweave.personal.question.service.ResearchQuestionOverviewService
```

核心方法：

```java
ResearchQuestionOverviewResponse generate(Long userId, Long questionId)
ResearchQuestionOverviewResponse getLatest(Long userId, Long questionId)
```

生成流程：

```text
1. 校验 question 归属
2. 读取该 question 下所有有效 claim
3. 过滤 superseded claim
4. 读取 claim_concept_relation
5. 读取 claim_citation
6. 按 claimType 分组：CONCLUSION / HYPOTHESIS / OPEN_ISSUE / DECISION
7. 检测冲突：SUPPORTED / REFUTED 或 SUPPORTS / CONTRADICTS
8. 生成 markdown
9. 保存 overview
```

### 4.4 Markdown 模板建议

```markdown
# {question.title}

## 当前结论

{currentAnswer / top conclusions}

## 关键判断

- [CONCLUSION / SUPPORTED / confidence] ...
- [HYPOTHESIS / UNCERTAIN / confidence] ...

## 支持证据

- claim: ...
  - citation: ...

## 争议与冲突

- 概念 A 在本问题中支持结论 X，但在其他问题中曾反对结论 Y

## 未解决问题

- ...

## 下一步

- ...

## 关联概念

- Concept A
- Concept B
```

### 4.5 API 建议

```http
POST /api/v1/personal/research-questions/{questionId}/overview:generate
GET  /api/v1/personal/research-questions/{questionId}/overview
```

可选：

```http
GET /api/v1/personal/research-questions/{questionId}/overview/markdown
```

### 4.6 验收标准

- 给一个已有 claim 的 question，能生成 markdown。
- superseded claim 不进入当前结论。
- OPEN_ISSUE 进入「未解决问题」。
- `claim_concept_relation` 中出现 SUPPORTS/CONTRADICTS 时，overview 能标注争议。
- 无 claim 的 question 返回空状态或引导提示，不报错。

---

## 5. P1：问题工作台 API 聚合

### 5.1 目标

前端不要分别请求 question / claims / concepts / comparison / summaries，而是提供一个聚合接口作为研究问题工作台入口。

### 5.2 API 建议

```http
GET /api/v1/personal/research-questions/{questionId}/workspace
```

返回：

```json
{
  "question": {},
  "currentClaims": [],
  "openIssues": [],
  "relatedConcepts": [],
  "conflictingConcepts": [],
  "recentSessions": [],
  "latestOverview": {},
  "nextStep": "..."
}
```

### 5.3 Service 设计

新增：

```text
ResearchQuestionWorkspaceService
```

聚合来源：

- `ResearchQuestion`
- `Claim`
- `ClaimConceptRelation`
- `ConceptCard`
- `SessionSummary`
- `ResearchQuestionOverview`

### 5.4 验收标准

- 单接口能支撑前端问题详情页首屏。
- 有当前结论、未解决问题、关联概念、最新 overview。
- 不触发 N+1 查询。

---

## 6. P2：Claim 自动抽取与写回

### 6.1 目标

现在 claim 主要靠接口手动创建。下一步应该让系统在对话结束后，从用户消息 + assistant answer 中自动提取候选 claim。

### 6.2 接入点

当前已有：

```text
MemoryWritebackService.writeAfterRound(...)
```

建议新增：

```text
ClaimWritebackService
ClaimWritebackStrategy
```

在正式 session 且绑定 `researchQuestionId` 时触发。

### 6.3 抽取规则

只抽取高价值内容：

- 明确结论：适合作为 `CONCLUSION`
- 待验证假设：适合作为 `HYPOTHESIS`
- 未解决问题：适合作为 `OPEN_ISSUE`
- 明确决策：适合作为 `DECISION`

不要抽：

- 普通寒暄
- 单轮临时推理细节
- 无依据的模型展开
- 明显与当前 question 无关的内容

### 6.4 LLM 输出 JSON 建议

```json
{
  "claims": [
    {
      "statement": "...",
      "claimType": "CONCLUSION",
      "stance": "SUPPORTED",
      "confidence": 0.78,
      "rationale": "...",
      "conceptNames": ["GraphRAG", "personal wiki"],
      "citationIds": [1, 2]
    }
  ]
}
```

### 6.5 关键保护

- 默认只生成候选 claim，可由用户确认。
- 或先做后台自动写入，但状态设为 `DRAFT` / `PENDING_REVIEW`。当前 `PersonalCardStatus` 只有 `READY`，若要支持审核，需要扩枚举。
- 自动抽取必须避免污染长期判断层。

### 6.6 验收标准

- 绑定 question 的 chat 完成后，能生成候选 claim。
- 不绑定 question 的 chat 不写 claim。
- 同一轮重复执行不会重复插入相同 claim。
- 用户能删除或覆盖错误 claim。

---

## 7. P3：EntityCard 实体锚点

### 7.1 目标

补齐「概念 -> 实体」层，让系统能识别和归并：

- 人物
- 公司
- 产品
- 论文
- 方法
- 工具
- 数据集
- 指标

### 7.2 数据模型建议

```text
EntityCard
- id
- spaceId
- researchProjectId nullable
- canonicalName
- normalizedName
- entityType
- aliasesJson
- description
- externalRefsJson
- confidence
- cardStatus
- createdAt
- updatedAt
```

关系表：

```text
concept_entity_relation
- conceptCardId
- entityCardId
- relationType
- evidence
```

可选：

```text
claim_entity_relation
- claimId
- entityCardId
- relationType
- evidence
```

### 7.3 设计原则

- `ConceptCard` 负责抽象概念。
- `EntityCard` 负责可指代对象。
- claim 绑定具体结论时，可以引用 concept，也可以引用 entity。

### 7.4 验收标准

- 同一实体的 alias 能归并。
- 同一 entity 能在多个 concept / claim 中复用。
- concept comparison 之后可以扩展为 entity comparison。

---

## 8. P4：Claim ES 化与 HybridRetriever 融合

### 8.1 触发条件

短期不建议立刻做。满足以下任一条件再做：

- 单用户 claim 数超过几千。
- DB + 内存关键词召回响应明显变慢。
- 需要 claim 与 doc/wiki/vector 一起做统一 RRF 融合。
- 需要语义召回，而不是关键词召回。

### 8.2 设计方案

新增：

```text
ClaimIndexService
SearchIndexClaimSupport
ClaimRetriever
```

ES index：

```text
claim
- spaceId
- userId
- researchProjectId
- researchQuestionId
- claimId
- statement
- rationale
- claimType
- stance
- confidence
- conceptNames
- updatedAt
```

`HybridRetriever` 扩为：

```text
BM25 + Vector + Wiki + Claim
```

### 8.3 验收标准

- claim 更新后能刷新索引。
- claim 删除/归档后能从索引删除。
- query 能语义召回历史 claim。
- RRF trace 里能看到 claim 分支命中数。

---

## 9. 推荐执行顺序

### Step 1：先做问题综述页

原因：

- 当前数据结构已经足够支撑。
- 用户价值最直接。
- 不依赖 ES、前端复杂交互、实体归并。

建议拆分：

```text
1. V22 migration: research_question_overview
2. ResearchQuestionOverview entity/repository/dto
3. ResearchQuestionOverviewService.generate
4. Controller API
5. 单测：superseded claim 过滤、open issue 分组、空 question
6. 前端接入 overview 展示
```

### Step 2：做问题工作台聚合接口

原因：

- 让前端页面更好做。
- 把 question 的核心状态集中成一个 view model。

建议拆分：

```text
1. Workspace DTO
2. WorkspaceService 聚合 question/claim/concept/overview
3. Controller API
4. 单测验证无 claim、有冲突、有 overview 三种场景
```

### Step 3：做 Claim 自动抽取候选

原因：

- 降低用户维护成本。
- 让系统真正从 chat 中持续沉淀结构化认知。

建议先候选、不自动强写长期结论。

### Step 4：做 EntityCard

原因：

- 补齐从概念到实体的线索。
- 为后续更强的 cross-question / cross-project comparison 打基础。

### Step 5：根据数据量决定是否 Claim ES 化

如果 claim 数量还小，继续保留 DB + 内存召回即可。

---

## 10. 下一步建议直接开工的任务

建议下一步直接实现：

> `ResearchQuestionOverview`：把一个问题下的 claim、concept、citation 编译成一份可展示、可导出的 markdown 综述。

最小可开发版本：

```text
- 表：research_question_overview
- API：POST /research-questions/{id}/overview:generate
- API：GET  /research-questions/{id}/overview
- 生成内容：当前结论 / 关键判断 / 未解决问题 / 关联概念 / 下一步
- 不接 LLM，先用模板生成，保证稳定可测
```

等模板版跑通后，再加 LLM 润色版本。
