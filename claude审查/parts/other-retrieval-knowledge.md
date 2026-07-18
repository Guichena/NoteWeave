## 其他模块 · retrieval/search/knowledge
（审查发现如下，分批追加）

### KnowledgeService 删除后的悬空引用检查（结论）
- 位置：全仓库
- 问题：`git status` 显示 `knowledge/KnowledgeService.java` 与 `chat/RetrievalService.java` 已删除（D）。检查是否有残留引用。
- 证据：`grep -r "KnowledgeService"` 仅命中 `backend/src/test/java/com/noteweave/ArchitectureBoundaryTest.java`，且均为架构约束断言（`.doesNotContain("KnowledgeService")`、`dependOnClassesThat().haveSimpleName("KnowledgeService")`），用于**禁止**再次引入该类，属于有意保留的护栏。主代码（retrieval/search/knowledge/chat 等）无任何编译期引用；职责已拆分到 `KnowledgeQueryService / KnowledgeCommandService / KnowledgeGovernanceService / KnowledgeVersionService / KnowledgeGraphService / KnowledgeWikiMutationService / KnowledgeWikiSearchEngine`。
- 影响：无悬空引用，删除是干净的。
- 建议：无需处理；`ArchitectureBoundaryTest` 的相关断言应保留。

### [中] wiki ingest/retract 失败状态因 @Transactional 回滚而丢失
- 位置：`backend/src/main/java/com/noteweave/knowledge/WikiIngestService.java:62-104`
- 问题：`runSourceIngestNow` / `runSourceRetractNow` 标注 `@Transactional`，catch 块中先 `taskCommandPort.completeTask(taskId, "WIKI_INDEXED_FAILED", ...)` 写入失败状态，随后 `throw ex`。由于失败状态写入与业务写入处于同一事务，`throw` 会触发整个事务回滚，失败标记同样被回滚，task 停留在 STARTED/中间态而非 FAILED。
- 证据：`try { ... } catch (RuntimeException ex) { taskCommandPort.completeTask(taskId, "WIKI_INDEXED_FAILED", ...); throw ex; }` 全部在 `@Transactional` 方法体内。
- 影响：Kafka 消费失败时任务无法正确落到失败态，运维视图（wiki-stats 的 pendingTaskCount、recentTasks）显示不准；重试/告警链路失效。
- 建议：失败状态写入应使用 `REQUIRES_NEW` 独立事务，或在事务提交/回滚后由消费者外层记录失败；不要在同一事务里既标失败又抛异常。

### [中] retract 触发全量重 ingest，O(n) 放大且重复生成 citation
- 位置：`backend/src/main/java/com/noteweave/knowledge/WikiIngestService.java:81-104,119-159,183-213`
- 问题：删除单个 Source 时，`runSourceRetractNow` / `enqueueAndRunSourceRetractIfEnabled` 会对**剩余全部 READY 资料**逐一调用 `ingestSource` 重建。每次 `ingestSource` 又调用 `createCitations` 无条件 `insert into citation`（无去重/无清理旧引用），并对每个概念页重复 upsert + 建 citation，同时 `refreshWorkspaceIndexPage` 每次触发全量 stats+lint 重算。
- 证据：`for (String remainingSourceId : remainingSourceIds) { ingestSource(...); }` 与 `createCitations` 每次 `Ids.newId()` 新插入。
- 影响：删一份资料的成本是 O(资料数 × 概念数)，citation 表随每次 ingest/rebuild 无界增长（旧 citation 不回收），大工作台性能与存储恶化。
- 建议：retract 只针对受影响页面做增量处理；citation 生成改为按 (version, chunk) 幂等 upsert 或在追加新版本时清理孤立 citation；index 页刷新在批量结束后只做一次。

### [中] getWikiStats / lintWiki 被高频重复调用，聚合成本被反复放大
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeGovernanceService.java:43-114,202-275`；`KnowledgeQueryService.java:49-123`；`WikiIngestService.java:375-436`
- 问题：`getWikiStats` 内部调用一次 `lintWiki`（5 段聚合查询），而 `getWikiIndex` 先调 `getWikiStats`（含 lint）后又**单独再调一次 `lintWiki`**；`getWikiRebuildAdvice` 也再调一次 lint；`refreshWorkspaceIndexPage`（每 ingest 一次）又调用 `getWikiStats` + `listWikiIssues`（各触发一次 lint）。
- 证据：`getWikiIndex` 行 121 `knowledgeGovernanceService.lintWiki(workspaceId)` 与 `getWikiStats` 内 `lintWiki(workspaceId)` 重复；工作台重建时每份资料都会 refreshWorkspaceIndexPage。
- 影响：单个概览请求执行数十条聚合/分组查询；批量重建时 lint 成本按资料数线性放大，是主要性能热点。
- 建议：在一次请求内复用同一份 lint 结果（方法入参传入或请求级缓存）；批量 ingest 期间关闭逐条 index 刷新，结束统一刷新一次。

### [中] getWikiIndex 在 RowMapper 内发起嵌套查询（N+1 且占用同一结果集连接）
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeQueryService.java:69-101`
- 问题：`recentSources` 查询的 RowMapper 内对每行调用 `knowledgeGovernanceService.relatedWikiPagesForSource(...)`（内部 2 条查询）。这既是 N+1，又是在外层 ResultSet 尚未关闭时借用连接池另一条连接执行嵌套查询。
- 证据：RowMapper lambda 中 `List<WikiTaskRelatedPageResponse> relatedPages = knowledgeGovernanceService.relatedWikiPagesForSource(workspaceId, rs.getString("id"), 3);`
- 影响：连接池很小时可能阻塞/死锁；正常也放大查询数。
- 建议：先把 recentSources 收集为列表再在 RowMapper 外批量查询关联页面，或用一次 JOIN/IN 查询聚合。

### [中] Wiki 检索链路 findRelevantWikiPageContexts 存在 N+1
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeQueryService.java:296-320`
- 问题：`findRelevantWikiPageContexts` 对命中的每个页面（最多 5）分别调用 `listOutgoingLinks`、`listBacklinks`、`citationsForVersion` 各 1 条查询，即 5×3=15 条查询；`citationIdsForWikiPages` 另有一次 IN 查询。这是 Wiki 聊天回答链路的在线路径。
- 证据：`.map(page -> new WikiPageContext(page, listOutgoingLinks(page.itemId())..., listBacklinks(page.itemId())..., citationsForVersion(page.versionId())...))`
- 影响：Wiki 模式回答的检索延迟随命中页数线性放大，直接影响首 token 延迟指标（问答/ Wiki 设计文档 §16.4 / Wiki §6）。
- 建议：用 `IN (...)` 一次性批量查询 outgoing/backlink/citation 后在内存按 itemId/versionId 分组装配。

### [中] KnowledgeWikiSearchEngine：全表载入 + 内存打分，且检索为纯 LIKE/子串命中（非真正向量/BM25/rerank）
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeWikiSearchEngine.java:22-144`
- 问题：`loadRows` 固定 `limit 120` 拉取工作台全部 WIKI 页（含 4 个相关子查询计数），在 Java 内存做打分排序；`score` 仅用 `lower.contains(term)` 子串命中累加，`extractTerms` 用非中文/字母数字分隔并对纯中文回退为整串。既非 ES 全文/向量检索，也无 rerank 模型。
- 证据：`where ... limit 120`；`if (lower.contains(term)) score += Math.max(1, term.length());`
- 影响：与设计文档「Wiki Index + Page Search + 图谱信号排序 / Hybrid Retrieval + Evidence Rerank」（Wiki §2、问答RAG §8/§14 的 RerankService）存在偏差；超过 120 页的工作台会静默丢弃页面导致召回不全；中文检索用整串 contains，长句几乎无法命中。
- 建议：文档已声明「ES 向量作同接口替换实现」，应把 wiki-search 也纳入 `ChunkSearchPort` 式端口，或至少对中文做分词；`limit 120` 应分页或提高并按分数截断而非按 updated_at 截断（当前先按 updated_at desc 截 120 再打分，热门旧页可能被排除）。

### [中] listRecentWikiTasks 存在 N+1（每任务 2 条查询）
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeGovernanceService.java:328-352,457-516`
- 问题：`listRecentWikiTasks`（limit 6）的 RowMapper 内对每个任务调用 `resolveWikiTaskTargetTitle`（1 查询）和 `loadWikiTaskRelatedPages`（1-2 查询）。由于 getWikiStats 高频调用它，级联放大。
- 证据：RowMapper 内 `resolveWikiTaskTargetTitle(...)` 与 `loadWikiTaskRelatedPages(...)`。
- 影响：每次概览/stats 请求额外 ~12-18 条查询。
- 建议：批量收集 targetId 后一次性 IN 查询标题与关联页面。

### [中] Wiki 内容归一化/自动互链每次都全表扫描 knowledge_item，且逐候选重算禁区
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeWikiMutationService.java:70-110,169-282`
- 问题：单次 WIKI 追加版本会：`normalizeWikiContent`→`loadAutoLinkCandidateTitles`（全表扫描所有 ACTIVE WIKI 标题）一次，`upsertWikiLinks`→`inferWikiTitleMentions`（同样全表扫描）再一次，并对每个 link draft 调用 `findWikiItemIdByTitle`（N+1）。`injectAutoLink` 对每个候选标题都重新调用 `computeForbiddenSpans(content)` 扫描整篇正文。
- 证据：`loadAutoLinkCandidateTitles` 与 `inferWikiTitleMentions` 各自执行 `select title... from knowledge_item where item_type='WIKI'`；`injectAutoLink` 内 `List<TextSpan> forbidden = computeForbiddenSpans(content);` 位于按候选标题循环内。
- 影响：批量重建时约 O(页面数² × 正文长度)；是 rebuild/ingest 的隐藏性能热点。
- 建议：一次加载候选标题集合复用；forbidden spans 每篇正文只算一次；用一次 IN 查询解析所有 target 标题到 itemId。

### [低] 自动互链对中文标题按子串注入，易产生错误链接
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeWikiMutationService.java:264-333`
- 问题：`injectAutoLink`/`findFirstSafeMention` 仅对 ASCII 词做词边界检查（`needsAsciiBoundary`/`hasAsciiBoundary`），中文标题（如长度 2 的「模型」）按纯 `indexOf` 子串匹配，会把「模型化」「模型层」等中的子串误标为 `[[模型]]`。
- 证据：`isGoodAutoLinkCandidate` 允许长度≥2 的中文标题；中文无边界校验分支。
- 影响：正文被持久化注入错误 `[[...]]`，进而生成错误页面关系边与图谱边，污染检索与治理信号。
- 建议：对 CJK 也引入邻字校验或最小长度约束（如≥3），或仅回写为关系边而不改写正文。

### [中] QaEvidenceRelevancePolicy 大量硬编码词表/词干，明显过拟合特定 gold 集
- 位置：`backend/src/main/java/com/noteweave/retrieval/QaEvidenceRelevancePolicy.java:30-51,329-364`
- 问题：`STRONG_ANCHOR_PAIRS`（`{artifact,version}`、`{noteweave,v2}`）、`GENERIC_QUERY_TERMS`/`GENERIC_QUERY_PHRASES`（含「的关键要求」「的共同目标」「请同时总结」等具体短语）、`normalizeEnglish` 内逐词硬编码词干（`verif`/`capabilit`/`boundar`/`regenerat`/`compar`/`citation`/`implement`）都是针对具体测试语料手工调参的产物，而非通用词法处理。
- 证据：`if (token.startsWith("verif")) return "verif";` 等一串特判；`GENERIC_QUERY_PHRASES = List.of("请同时总结", ..., "的关键要求", "的共同目标")`。
- 影响：换语料/换领域即失效；策略版本号声称 v2 稳定契约，但实际对输入分布高度敏感，维护性差、易回归。
- 建议：抽出为可配置词表/停用词资源文件并标注来源；英文词干改用标准 stemmer；中文改用分词器而非 bigram+短语特判；用更大的 gold 集验证泛化。

### [低] 指标聚合对空集合默认返回 1.0，可能掩盖“无可测样本”
- 位置：`backend/src/main/java/com/noteweave/retrieval/eval/RetrievalBenchmarkReplay.java:146,152-153,162`
- 问题：`average(...)` 空列表返回 1.0d；`reciprocalRank`/`ndcg` 在 relevant 为空时返回 1.0；`refusalAccuracy` 无拒答样本返回 1.0。这些“真空即满分”默认，若质量门槛数据集意外缺某类样本，会让 macro 指标虚高为 1.0 而非报错。
- 证据：`return cases.isEmpty() ? 1.0d : ...average().orElse(1.0d);`
- 影响：质量门（RetrievalQualityGate）可能因某维度真空而误判通过。
- 建议：区分“真空(NaN/未适用)”与“满分”，或在 gold 校验层强制每类样本最小数量（部分已由 minimumCaseCount 覆盖，但未按 mode/citation 维度约束）。

### [低] listItems(WIKI) 复用 loadRows 的 limit 120，与其它列表语义不一致
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeQueryService.java:142-148`
- 问题：`listItems` 对 WIKI 走 `wikiSearchEngine.loadRows`（硬编码 `limit 120`，按 updated_at 截断），而对非 WIKI 走无 limit 的 SQL。工作台页面超过 120 时列表页会静默漏页，且与 wiki-home/wiki-index 使用的口径耦合。
- 证据：`if ("WIKI".equalsIgnoreCase(itemType)) return wikiSearchEngine.loadRows(workspaceId)...`
- 影响：大工作台 Wiki 列表不完整；分页缺失。
- 建议：为 WIKI 列表提供独立的分页查询，避免复用检索用的 120 截断。

### [低] rebuildWikiLinks 先全量删链再重建，且依赖 appendVersion 隐式重建链接
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeGovernanceService.java:354-381`
- 问题：`@Transactional` 内 `delete from knowledge_item_link where workspace_id=?` 清空全部链接后逐页重建；内容被 normalize 改动的页面走 `appendVersion` 并 `continue`，链接靠 appendVersion 内部 `replaceOutgoingLinks` 重建（当前正确但为隐式耦合）。若未来 appendVersion 不再重建链接，这些页链接会丢失。
- 证据：`if (!normalizedContent.equals(page.content())) { knowledgeVersionService.appendVersion(...); continue; }`
- 影响：脆弱耦合；大工作台一次 rebuild 会重写全部链接行，写放大。
- 建议：显式在两个分支都调用链接重建；或按页 diff 只更新变化的链接边而非全删全建。

### [低] upsertWikiLinks 无唯一约束/去重，依赖调用方先删除
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeWikiMutationService.java:70-110`
- 问题：`upsertWikiLinks` 直接 `insert` link 行，不做 (source_item_id,target_title) 去重；正确性依赖调用方（replaceOutgoingLinks/rebuild）先 delete。若被误在已有链接的 item 上直接调用会产生重复边。
- 证据：方法名为 upsert 但实现为纯 insert，无 on-conflict/存在性判断。
- 影响：潜在重复关系边，污染图谱/统计（linkCount 虚高）。
- 建议：加数据库唯一约束或改为真正 upsert；或重命名以反映“仅追加”语义。

### [低] QaChunkSearchShadowCapture 对 chunkNo 直接 parseInt，非数字即崩溃
- 位置：`backend/src/main/java/com/noteweave/retrieval/eval/QaChunkSearchShadowCapture.java:126`
- 问题：`Integer.parseInt(hit.chunkNo())` 直接解析 `ChunkSearchHit.chunkNo`（String）。若搜索实现返回空串/null/非数字（ES 字段缺失或格式不同），抛 `NumberFormatException` 使整次 capture 失败。
- 证据：`Integer.parseInt(hit.chunkNo())`；`ChunkSearchHit.chunkNo` 为 String 类型。
- 影响：影子评估管线对搜索后端字段格式脆弱。
- 建议：做健壮解析（缺失/非法回退为 0 或跳过），或在 ChunkSearchHit 契约中把 chunkNo 定为 int。

### [低] WikiIngestService 自调用使 runSourceIngestNow/@Transactional 传播语义失效
- 位置：`backend/src/main/java/com/noteweave/knowledge/WikiIngestService.java:161-181`
- 问题：`enqueueAndRunSourceIngest`（本类 @Transactional 方法）在同步模式直接内部调用 `runSourceIngestNow(...)`。Spring 代理对自调用不生效，`runSourceIngestNow` 的 `@Transactional` 被忽略，实际运行在外层事务里。这与前述“失败标记随事务回滚丢失”问题叠加。
- 证据：`if (!messagingMode.isAsyncEnabled()) { runSourceIngestNow(taskId, workspaceId, sourceId); ... }`
- 影响：事务边界与预期不符；异步/同步两条路径事务行为不一致，难以推理。
- 建议：明确同步路径的事务边界（提取到独立 bean，或统一由消费者/门面控制事务）。

### [低] KnowledgeGraphService ego 邻接排序用 getOrDefault(center) 兜底，掩盖数据不一致
- 位置：`backend/src/main/java/com/noteweave/knowledge/KnowledgeGraphService.java:216-221`
- 问题：邻居排序 `nodeById.getOrDefault(itemId, nodeById.get(centerItemId)).degree()` 用中心节点兜底缺失节点，随后 `.thenComparing(itemId -> nodeById.get(itemId).title())` 又直接 `get`（无兜底）。当前因边已按节点集过滤应不触发，但两处不一致的防御风格若数据异常会 NPE。
- 证据：一处 `getOrDefault(..., nodeById.get(centerItemId))`，紧邻一处裸 `nodeById.get(itemId)`。
- 影响：潜在 NPE / 排序语义可疑（用中心度数替身）。
- 建议：统一防御；缺失节点直接过滤掉而非替身兜底。

### [低] 与设计文档的偏差：Wiki/QA 检索缺真正的向量召回与 rerank 模型
- 位置：`knowledge/KnowledgeWikiSearchEngine.java`（wiki-search）；`retrieval/`（QA 策略仅词法充分性守卫）
- 问题：设计文档《问答RAG链路设计》§4/§9 与《Wiki模式设计》§2 要求 Hybrid Retrieval（关键词+向量）+ Evidence Rerank；当前 wiki-search 为内存子串打分+图谱信号加权，QA 侧 `QaEvidenceRelevancePolicy` 只是词法充分性过滤、`QaEvidenceSelectionPolicy` 只做来源多样化+预算截断，均无向量召回与 rerank 打分模型。
- 证据：`score` 用 `contains`；`RerankService` 在文档模块列表中列出但检索/知识两模块内无对应实现（rerank 仅体现为 rerankScore 字段透传）。
- 影响：属于文档已部分承认的“轻量等价实现”，但需明确记录偏差，避免验收口径误解；中文语义召回能力弱。
- 建议：在 README/设计索引中标注当前为词法+结构信号近似实现；若引入 ES 向量，应经 `ChunkSearchPort` 同接口替换并纳入影子评估门禁。

### [优化] 测试缺口
- 位置：`backend/src/test/java/com/noteweave/{retrieval,knowledge,search}`
- 问题：retrieval/eval 与多数 knowledge service 有较完整单测，但存在缺口：(1) `KnowledgeWikiSearchEngine` 无直接单测（打分/中文分词/limit 120 截断行为未覆盖）；(2) `KnowledgeWikiMutationService` 的自动互链注入（`injectAutoLink`/中文子串/代码块禁区/词边界）无直接单测；(3) `search/` 包（`ChunkSearchPort` 实现）本目录无测试，QA 影子仅用 mock；(4) `KnowledgeController` 无 Web 层/鉴权集成测试；(5) WikiIngestService 失败路径（catch→completeTask FAILED 后回滚）无回归测试。
- 证据：test 目录清单中无上述类对应测试文件。
- 影响：自动互链改写正文、检索截断、失败态落库等高风险逻辑缺回归保护。
- 建议：补齐上述单测，尤其是自动互链的中文误链与失败态事务行为。
