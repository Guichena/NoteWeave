# Phase 11.5: 个人 Artifact 沉淀回个人 Wiki

本文档用于指导 NoteWeave Phase 11.5 编码实现。

范围：

```text
Phase 11.5: Artifact -> SynthesisCard / ArtifactCardRelation / Personal Wiki Index
```

本阶段目标是让用户确认有长期价值的个人 Artifact 能沉淀为个人 Wiki Card，同时避免自动污染已有 ConceptCard 或 MethodologyCard。

---

## 1. 参考文档

```text
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_11_personal_generation.md
docs/features/phase_7_personal_wiki_compiler_cards.md
```

---

## 2. 阶段目标

完成后系统应具备：

- 用户可以从个人 Artifact 触发“沉淀到个人 Wiki”。
- MVP 只支持 `Artifact -> SynthesisCard`。
- 沉淀必须经过用户确认。
- 创建 SynthesisCard 时写入 `artifact_card_relation`。
- SynthesisCard 证据写入 `synthesis_card_citation`，不能只放 JSON。
- SynthesisCard 可查询、可查看来源 Artifact 和 ArtifactVersion。

---

## 3. 本阶段不做的事

- 不做生成完成后自动写入个人 Wiki。
- 不自动修改已有 ConceptCard。
- 不自动修改 MethodologyCard。
- 不做复杂 merge proposal。
- 不做团队 Wiki 发布。
- 不做 Quiz、测验、答题、评分或题库。

---

## 4. 数据模型

复用或新增蓝图中的表：

```text
synthesis_card
artifact_card_relation
synthesis_card_citation
synthesis_concept_relation
```

关键字段：

```text
synthesis_card.source_artifact_id
synthesis_card.source_artifact_version_id
artifact_card_relation.artifact_id
artifact_card_relation.artifact_version_id
artifact_card_relation.card_type = SYNTHESIS
artifact_card_relation.relation_type = SUMMARIZED_INTO
```

规则：

- SynthesisCard 必须属于当前用户 PERSONAL Space。
- SynthesisCard 必须保留 Artifact 与 ArtifactVersion 来源。
- Citation 必须从 ArtifactCitation 或可回溯证据生成，不能只复制展示 JSON。

---

## 5. Service 设计

### PersonalArtifactDistillationService

```java
DistillArtifactResponse createProposal(Long userId, Long artifactId, DistillArtifactRequest request);
SynthesisCardResponse confirmToSynthesisCard(Long userId, Long proposalId);
```

MVP 可以把 proposal 作为短生命周期 DB 记录、Task output 或明确的确认请求对象实现，但必须满足：

- 用户确认前不写 SynthesisCard。
- 确认时重新校验 Artifact owner-only 权限。
- 确认时绑定当前 ArtifactVersion，避免沉淀内容和用户看到的版本不一致。

---

## 6. API 设计

```http
POST /api/v1/artifacts/{artifactId}/distill-to-personal-wiki
GET  /api/v1/artifacts/{artifactId}/card-relations
GET  /api/v1/personal/research-projects/{projectId}/synthesis-cards
GET  /api/v1/personal/synthesis-cards/{cardId}
```

`distill-to-personal-wiki` 必须是用户显式操作，不允许后台自动调用。

---

## 7. 流程

```text
用户打开个人 Artifact
  ↓
点击“沉淀到个人 Wiki”
  ↓
选择 SYNTHESIS
  ↓
系统基于当前 ArtifactVersion 生成待确认内容
  ↓
用户确认
  ↓
创建 SynthesisCard
  ↓
写 artifact_card_relation
  ↓
写 synthesis_card_citation
```

---

## 8. 验收清单

- Artifact 默认仍只作为 Artifact 保存。
- 用户确认前不会创建 SynthesisCard。
- 用户确认后可以创建 SynthesisCard。
- SynthesisCard 能回溯 sourceArtifactId、sourceArtifactVersionId 和 Citation。
- `artifact_card_relation` 能反查 Artifact 与 SynthesisCard 的关系。
- 其他用户不能沉淀或查看该个人 Artifact。
- 不自动合并 ConceptCard 或 MethodologyCard。

