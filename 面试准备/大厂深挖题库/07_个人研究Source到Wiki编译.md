# 文件：07_个人研究Source到Wiki编译.md

## 0. 本篇定位

这篇负责个人研究链路的深挖。它适合回答 Source 如何导入、为什么要编译成 ArticleCard / ConceptCard、Evidence backtrace 为什么必要、Artifact 为什么不等于长期知识，以及个人研究如何从资料整理走到结构化沉淀。

## 1. 本主题面试官想考什么

这个主题考察个人知识管理链路：Source 导入、URL 安全、可读文本保证、LLM 结构化抽取、证据回溯、概念合并、Card 搜索和 owner-only 权限。它能体现 NoteWeave 不只是团队 RAG，还有个人研究沉淀闭环。

## 2. 高频问题清单

### 基础问题

- 个人研究主链路是什么？
- Source 支持哪些类型？
- Source READY 的条件是什么？
- ArticleCard 和 ConceptCard 分别是什么？

### 进阶问题

- URL 导入为什么要做安全限制？
- `SafeUrlContentFetcher` 防了哪些风险？
- WikiCompiler 不只是抽卡片，还做了什么？
- Concept 去重和 alias 有什么作用？
- 为什么需要 evidence backtrace？

### 深挖追问

- LLM 输出 JSON 解析失败怎么办？
- Source 没有可读文本为什么不能 compile？
- Quote 为什么不能完全信任模型输出？
- 同项目内概念重复如何合并？
- 个人 Source 和团队 Document 能否共用解析能力？

### 压力追问

- 如果用户导入恶意 URL，如何避免 SSRF？
- 如果资料很多，个人编译怎么做增量？
- 如果概念合并错了，如何纠正？
- 如果以后支持 Bibtex，应该补在哪一层？

## 3. 问答与讲解

### Q1：个人研究从 Source 到 Card 的链路怎么走？

#### 面试官为什么问

面试官想看你能不能讲清个人侧不是“总结文档”，而是有自己的领域模型和证据链。

#### 回答思路

按 `ResearchProject -> Source -> import -> compile -> ArticleCard/ConceptCard -> Citation` 讲。

#### 结合我的项目怎么答

个人研究从 ResearchProject 开始，用户可以导入 TEXT、FILE、URL 类型 Source。Source import 成功后必须有可读取 raw/parsed text 才能标记 READY。之后 `WikiCompilerService` 创建 `SOURCE_COMPILE` Task，调用 LLM 生成 ArticleCardDraft 和 ConceptExtractionDraft，再通过 EvidenceBacktraceService 回溯原文证据，通过 ConceptMergeService 做概念归一与合并，最后保存 ArticleCard、ConceptCard、ConceptRelation 和 Card Citation。

#### 技术原理 / 链路设计讲解

个人侧链路关注“把资料变成可复用知识结构”：

```text
ResearchProject
-> Source(TEXT/FILE/URL)
-> raw/parsed text
-> SOURCE_COMPILE
-> ArticleCard(summary/keyPoints/evidence)
-> ConceptCard(alias/relation/evidence)
-> Artifact generation context
```

Card 的意义是把大段资料变成可索引、可引用、可组合的知识单元，而不是每次生成都全量塞 Source 原文。

#### 技术栈特点与选型理由

LLM 适合做结构化抽取，但不能作为唯一事实源；MySQL 适合保存 Card 和关系；MinIO 保存 Source 原文和解析文本；EvidenceBacktrace 把 LLM 的 quote 和 Source 文本建立可验证联系。

#### 可直接复述的面试回答

个人研究链路是从 ResearchProject 下的 Source 开始的。Source 可以是文本、文件或 URL，但 READY 的前提是系统里必须有可读取的 raw 或 parsed text。之后会创建 `SOURCE_COMPILE` 任务，由 WikiCompiler 调 LLM 生成 ArticleCard 和 ConceptCard 的结构化草稿。这里不是直接相信模型输出，而是通过 EvidenceBacktrace 回到 Source 原文找证据，再用 ConceptMergeService 做概念归一、合并、alias 和 relation。最终保存 ArticleCard、ConceptCard 以及正式 citation 关系，供后续 Artifact 生成使用。

#### 常见追问

- Source READY 为什么必须有文本？
- ArticleCard 和 ConceptCard 关系是什么？
- 概念合并错了怎么办？

#### 常见坑

不要说“Source 导入后直接让 LLM 总结”。项目亮点在 compile、evidence backtrace、concept merge 和 card citation。

---

### Q2：URL 导入为什么要做安全限制？具体防什么？

#### 面试官为什么问

这是安全高频题。只要项目支持 URL fetch，面试官很可能追 SSRF。

#### 回答思路

说明用户输入 URL 后服务端会主动发请求，如果不限制，可能访问内网、localhost、云元数据服务或大文件。再讲 SafeUrlContentFetcher 的限制。

#### 结合我的项目怎么答

`SafeUrlContentFetcher` 只允许 http/https，不允许 userInfo，禁止 localhost、`.localhost`、`.local`，解析 DNS 后逐个检查 IP，拦截 loopback、site local、link local、multicast、私网 IPv4、IPv6 ULA/link-local 等地址；还限制 redirect 次数、read timeout、最大响应字节数，并且每次 redirect 后重新校验目标 URI。

#### 技术原理 / 链路设计讲解

SSRF 的核心风险是攻击者让服务端替自己访问内部资源，例如 `127.0.0.1`、`10.x.x.x`、`169.254.169.254`、内网域名、重定向到内网地址。只校验字符串不够，必须 DNS resolve 后检查实际 IP；只校验初始 URL 也不够，redirect 后还要复核。

#### 技术栈特点与选型理由

服务层封装 `UrlContentFetcher`，便于测试和替换 transport；限制 maxResponseBytes 可以避免大响应拖垮内存；timeout 避免慢连接占用资源。

#### 可直接复述的面试回答

URL 导入是 SSRF 风险点，因为服务端会代表用户去访问一个外部地址。如果不限制，用户可能构造 URL 访问 localhost、内网服务、云元数据地址，甚至通过重定向绕过校验。项目里的 `SafeUrlContentFetcher` 做了几层限制：只允许 http/https，不允许 userInfo；禁止 localhost、local 域；DNS 解析后检查每个 IP，拦截 loopback、site local、link local、multicast、常见私网 IPv4 和 IPv6 私网地址；每次 redirect 后重新校验；同时限制 redirect 次数、读取超时和最大响应体大小。

#### 常见追问

- DNS rebinding 怎么处理？
- 只检查 host 字符串够不够？
- 为什么要限制最大响应大小？

#### 常见坑

不要只说“校验 URL 格式”。格式合法不代表安全，必须检查解析后的地址和重定向链。

---

### Q3：为什么要做 evidence backtrace，而不是直接相信 LLM 给的 quote？

#### 面试官为什么问

这是 AI 可信度问题。面试官想看你是否知道模型会编造 quote 或改写证据。

#### 回答思路

说明 LLM 抽取适合提出候选，但证据必须能回到 Source 原文。否则 Card 后续生成 Artifact 时会把幻觉当事实。

#### 结合我的项目怎么答

`WikiCompilerService` 调 LLM 生成 Article 和 Concept 草稿后，会通过 `EvidenceBacktraceService` 加载 Source 可读文本，并将 evidence quote 与 Source 建立 citation。个人生成时 `PersonalEvidenceService` 也要求 SOURCE-backed citations；如果没有可追踪 Source evidence，生成会失败而不是产出 READY Artifact。

#### 技术原理 / 链路设计讲解

证据回溯的意义是把模型输出从“文本建议”变成“可验证知识”。模型可以抽摘要、概念和关系，但证据必须绑定 source、offset、quote、citation。否则后续 Artifact 会引用一段不存在的原文，系统就失去可信度。

#### 技术栈特点与选型理由

MySQL 存 Card Citation 和 Concept Relation，MinIO/Source 存原文快照。这样 Card 不是孤立 JSON，而是可回到源资料。

#### 可直接复述的面试回答

LLM 的 quote 不能直接当证据，因为模型可能改写、概括甚至编造原文。所以我的处理是：LLM 负责提出 ArticleCard、ConceptCard 和 evidence quote 的候选，真正落库前要通过 EvidenceBacktrace 回到 Source 的 raw/parsed text 找可验证证据，并保存正式 citation。后续个人 Artifact 生成也只接受 SOURCE-backed evidence，如果卡片没有能回溯到 Source 的 citation，就让生成失败，而不是生成一个看似完整但没有证据的 Artifact。

#### 常见追问

- quote 找不到怎么办？
- evidence 是精确匹配还是模糊匹配？
- 如果原文被删除，Card citation 怎么处理？

#### 常见坑

不要说“模型一般不会编”。大厂面试里，可信 AI 的默认假设是模型不可靠，系统要兜底。

---

## 4. 本主题总结

个人研究链路要讲清：Source READY 必须有文本，URL fetch 必须防 SSRF，WikiCompiler 是结构化编译和证据回溯，Concept 合并减少知识碎片，Card Citation 保证后续生成有根据。

## 5. 面试前自查清单

- 我是否能完整讲出 Source 到 Card 的链路？
- 我是否能说明 URL SSRF 防护细节？
- 我是否能解释为什么 Source READY 必须有 readable text？
- 我是否能说明 evidence backtrace 的价值？
- 我是否能把 Bibtex 讲成未来 Source importer 扩展，而不是当前已实现主链路？

## 6. 整条链路深讲：Source 如何变成可复用个人知识

第一步是 ResearchProject 边界。个人研究资料必须挂在某个 ResearchProject 下，并且 owner-only。这个边界保证个人资料不会被团队成员或其他用户误访问。

第二步是 Source 导入。TEXT 类型直接保存 raw text；FILE 类型复用解析能力，把文件转成可读文本；URL 类型通过 `SafeUrlContentFetcher` 拉取正文。无论哪种类型，Source 标记 READY 的前提都是系统里存在可读取文本。

第三步是 URL 安全校验。服务端主动访问用户输入 URL，所以必须防 SSRF。当前实现限制 scheme、禁止 userInfo、禁止 localhost/local 域、DNS 解析后拦截私网/loopback/link-local/multicast 地址，并对 redirect 后的新地址重复校验，同时限制 timeout、redirect 次数和最大响应体。

第四步是创建编译任务。用户触发 compile 时，`WikiCompilerService.compileSource` 校验 Source owner、importStatus=READY，并通过 EvidenceBacktraceService 确认可读文本存在，然后创建 `SOURCE_COMPILE` Task。

第五步是 ArticleCard 抽取。Worker 执行 compile task 时，加载 Source 文本，构造 article prompt，调用 LLM 得到 ArticleCardDraft，包括 title、summary、keyPoints、tags、evidenceQuotes。JSON 解析失败会重试，最终失败则任务失败。

第六步是证据回溯。LLM 给出的 evidence quote 不能直接信任，系统要回到 Source 原文建立 citation。ArticleCard 的 evidence cache 可以用于展示，正式 citation 关系用于审计。

第七步是 Concept 抽取与合并。系统再构造 concept prompt，得到 ConceptDraft 和 ConceptRelationDraft。`ConceptMergeService` 会按 normalized name 在同项目内合并相近概念，减少重复卡片；alias 和 relation 用来表达同义词、相关概念和概念网络。

第八步是持久化与状态收敛。保存 ArticleCard、ConceptCard、ArticleConceptRelation、ConceptRelation、Card Citation，Source compileStatus 变 READY，ResearchProject compileStatus 刷新。后续个人 Artifact 生成会从这些 Card 和 Citation 加载上下文，而不是全量塞 Source 原文。

## 7. 原理与设计原因速查

- 为什么 Source READY 必须有文本：没有可读文本就无法编译、无法 evidence backtrace，也无法可靠生成 Artifact。
- 为什么 URL fetch 要解析 IP：只校验字符串会被 DNS、重定向、编码形式绕过，必须看最终解析地址。
- 为什么 LLM 输出要 JSON：结构化抽取便于落库为 Card、Relation、Citation，但必须处理解析失败。
- 为什么 Concept 要合并：同一研究项目里概念重复会污染后续生成上下文，导致答案啰嗦和冲突。
- 为什么 evidence backtrace 必要：模型 quote 可能改写或编造，只有回到 Source 原文才算可信证据。
- 为什么不全量塞 Source 原文：token 成本高、噪声大、不可控，Card + citation 是更稳定的中间表示。

## 8. 3 到 5 分钟深答模板

个人研究链路我会从 Source 的可信输入讲起。用户在 ResearchProject 下导入 Source，可以是文本、文件或 URL，但系统只有在拿到可读 raw/parsed text 后才会把 Source 标记 READY。URL 这块是安全风险点，所以服务端 fetch 前会限制 http/https、禁止 userInfo、解析 DNS 后拦截 localhost、私网、link-local、multicast 等地址，并且 redirect 后重新校验。Source READY 后，用户触发 compile，系统创建 `SOURCE_COMPILE` Task。Worker 加载 Source 文本，先让 LLM 抽 ArticleCard 草稿，再抽 ConceptCard 和关系草稿。这里不会直接相信 LLM 的 quote，而是通过 EvidenceBacktrace 回到 Source 原文，保存正式 citation。概念落库前会用 ConceptMergeService 做归一化和合并，避免同项目里重复概念太多。最后 ArticleCard、ConceptCard、alias、relation 和 citation 都持久化，后续 Artifact 生成读取这些结构化卡片和 SOURCE-backed evidence，而不是每次把原始资料全塞进 prompt。
