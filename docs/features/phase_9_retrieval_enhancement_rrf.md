# Phase 9: 增强检索、向量召回与 Weighted RRF

本文档用于指导 NoteWeave 第九阶段编码实现。

范围：

```text
Phase 9: Embedding / VectorRetriever / WikiRetriever Interface / Weighted RRF / EvidencePostProcessor Enhancement
```

第九阶段目标是在 Phase 4 基础 RAG 上增强检索质量：加入向量召回、可扩展 Retriever 接口、Weighted RRF 融合排序和更完整的 Evidence 后处理。

本阶段不做 Team Wiki 发布，不做 Artifact，不做个人 Wiki Compiler。

---

## 1. 参考文档

```text
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_3_document_processing_indexing.md
docs/features/database_api_blueprint.md
```

可参考 PaiSmart-main：

```text
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\client\EmbeddingClient.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\HybridSearchService.java
D:\java-projects\PaiSmart-main\src\main\java\com\yizhaoqi\smartpai\service\RetrievalStrategyAdvisorService.java
```

---

## 2. 阶段目标

- 接入 EmbeddingClient。
- 为 DocumentChunk 生成 embedding。
- ES mapping 增加 dense_vector 字段。
- 实现 Retriever 接口。
- 实现 Bm25Retriever。
- 实现 VectorRetriever。
- 预留 WikiRetriever 接口。
- 实现 Weighted RRF。
- EvidencePostProcessor 支持去重、相邻 Chunk 合并、同文档限流、上下文长度控制。
- TeamChatService 可配置使用 hybrid retrieval。

---

## 3. 本阶段不做的事

- 不做 Wiki 发布。
- 不做 Wiki 页面入索引。
- 不做 Rerank 模型。
- 不做 Artifact。
- 不做个人功能。
- 不做检索评估平台，只保留基础日志。

---

## 4. 配置

```yaml
embedding:
  enabled: ${EMBEDDING_ENABLED:true}
  api:
    base-url: ${EMBEDDING_API_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
    api-key: ${EMBEDDING_API_KEY:}
    model: ${EMBEDDING_API_MODEL:text-embedding-v4}
    dimension: ${EMBEDDING_DIMENSION:2048}
    batch-size: ${EMBEDDING_BATCH_SIZE:10}

rag:
  retrieval:
    mode: ${RAG_RETRIEVAL_MODE:HYBRID}
    bm25-weight: ${RAG_BM25_WEIGHT:1.0}
    vector-weight: ${RAG_VECTOR_WEIGHT:1.2}
    wiki-weight: ${RAG_WIKI_WEIGHT:1.5}
    rrf-k: ${RAG_RRF_K:60}
    top-k: ${RAG_RETRIEVAL_TOP_K:10}
```

---

## 5. Retriever 抽象

```java
public interface Retriever {
    String name();
    List<RetrievalHit> retrieve(RetrievalQuery query);
}
```

RetrievalQuery：

```text
userId
spaceId
knowledgeBaseIds
query
topK
filters
```

RetrievalHit：

```text
retrieverName
chunkId
documentId
knowledgeBaseId
spaceId
chunkIndex
documentTitle
content
score
rank
metadata
```

---

## 6. Embedding 设计

### EmbeddingClient

```java
List<float[]> embedTexts(List<String> texts);
```

要求：

- 支持 batch。
- API key 缺失时报错或使用 stub。
- 记录 latency。
- 捕获限流与超时。

### Document Embedding 回填

在文档处理流程中：

```text
保存 DocumentChunk
  ↓
EmbeddingClient 批量生成向量
  ↓
写入 ES dense_vector
```

如果 embedding 失败：

- 本阶段可允许 BM25 索引成功，向量状态失败。
- 不应导致文档完全不可检索。

---

## 7. VectorRetriever

流程：

```text
对 query 生成 embedding
  ↓
ES kNN 查询
  ↓
filter spaceId / knowledgeBaseId
  ↓
返回 RetrievalHit
```

要求：

- 必须带权限 filter。
- query embedding 失败时降级 BM25。

---

## 8. Weighted RRF

公式：

```text
score(d) += weight(retriever) * 1 / (rrfK + rank(d))
```

实现：

```java
List<RetrievalHit> fuse(List<List<RetrievalHit>> hitLists, RrfOptions options);
```

去重 key：

```text
chunkId
```

默认权重：

```text
BM25 = 1.0
Vector = 1.2
Wiki = 1.5
```

---

## 9. EvidencePostProcessor 增强

增强能力：

```text
去重
同文档限流
相邻 Chunk 合并
超长上下文截断
引用编号整理
低分结果过滤
```

相邻 Chunk 合并：

```text
同 documentId
chunkIndex 相邻
合并后不超过 maxMergedChars
```

---

## 10. API 与调试

增强 Search Debug：

```http
GET /api/v1/team/knowledge-bases/{knowledgeBaseId}/search?keyword=部署&mode=HYBRID
```

返回：

```json
{
  "items": [],
  "retrievalMode": "HYBRID",
  "debug": {
    "bm25Count": 8,
    "vectorCount": 8,
    "fusionCount": 10
  }
}
```

---

## 11. 验收清单

- DocumentChunk 可以写入 embedding。
- BM25 Retriever 可用。
- VectorRetriever 可用。
- Hybrid retrieval 可用。
- Weighted RRF 排序稳定。
- Evidence 后处理可合并相邻 Chunk。
- TeamChatService 可使用 HYBRID 模式。
- 向量失败时可降级 BM25。
- 权限 filter 始终存在。

---

## 12. 给 AI 执行第九阶段的边界提醒

- 不要实现 Wiki 发布。
- 不要实现 Artifact。
- 不要实现 Rerank。
- 不要移除 BM25。
- 不要绕过权限 filter。
- 向量失败必须可降级。
- 所有 API 必须使用 `/api/v1`。

