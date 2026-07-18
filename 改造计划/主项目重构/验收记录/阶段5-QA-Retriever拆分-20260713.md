# 阶段 5 QA Retriever 拆分验收

- 日期：2026-07-13
- 后端测试：153/153
- 状态：QA 具体检索实现已移出 `RetrievalService`；Note Retriever 与评测基线待施工

## 职责拆分

1. 新增 `QaPassageRetriever`，独立承接 ES passage 召回、批量 provenance hydrate、MySQL fallback、关键词评分和来源多样性选择。
2. `QaPassageEvidenceRetriever` 直接依赖 `QaPassageRetriever`，只负责把 `RetrievedChunk` 映射为统一 Passage evidence。
3. QA `RetrievedChunk` DTO 归属具体 retriever；`RetrievalService` 删除 QA DTO、`retrieveForQa`、ES port 依赖和 QA 私有 helpers。
4. 删除 Note `ReadingWindow.toRetrievedChunk` 死转换，防止 Note 模型重新耦合 QA DTO。

## 行为等价边界

- ES 仍以 topK 12 检索，命中后按原分数乘 10，并输出 `fulltext:bm25`；
- ES 异常或无命中仍进入原 MySQL READY source 最近 80 chunk fallback；
- fallback 仍按 content 3 倍、title/source type 2 倍计分；
- 最终仍先选择不同 source，再按原顺序回填同 source chunk，总数最多 6；
- Research Report provenance、evidence id、display title、scope、score、match reason 和 citation 主键不变；
- `qa-passage-v1` plan/prompt version 未提升，因为本批仅迁移职责，不改算法。

## 架构门禁

- `QaPassageEvidenceRetriever` 禁止重新依赖 `RetrievalService`；
- Chat retrieval 仍只能依赖 `ChunkSearchPort`，不能依赖 Elasticsearch infrastructure；
- `RetrievalService` 中搜索 `retrieveForQa`、`ChunkSearchPort`、`ChunkSearchHit` 和 `RetrievedChunk` 均为零命中。

## 自动验证

- QA Retriever、Evidence adapter、strategy、Hydrator 与架构定向回归：20/20；
- 完整 Backend：38 suites，153/153，0 failures，0 errors，0 skipped；
- `git diff --check`：通过，仅有工作区既有 CRLF 提示；
- Spring 集成上下文与三模式端到端契约通过，新组件注入未改变 AnswerRun/citation 行为。

## 尚未完成

- Note 候选、Journal、关系图、窗口读取仍聚合在 `RetrievalService`；
- 固定 gold set、BM25 可回放 baseline 与质量/延迟门禁尚未建立；
- vector、fusion、rerank 尚未施工；
- EvidenceBundle 仍只在运行时完整存在，revision 当前仅保存 `evidence-bundle:<bundleId>` 引用。
