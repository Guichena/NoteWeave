## Research Worker · IO/LLM/检索/校验/契约层
（审查发现如下，分批追加）

### [严重] 外部 URL 读窗丢失 untrusted/prompt-injection 标记，注入内容被当可信内容
- 位置：`workers/research-worker/app/read_adapters.py:126`（UrlReadAdapter.read_hit 的 ResearchReadWindow 构造）
- 问题：WorkspaceReadAdapter 会把 document 的 `untrusted_content / prompt_injection_detected / prompt_injection_signals / source_type / author / institution / published_at / updated_at / freshness_status` 透传给 ResearchReadWindow；但 UrlReadAdapter 的构造（126-174 行）完全没有传这些字段。ResearchReadWindow 中 `untrusted_content` 默认 False、`prompt_injection_detected` 默认 False、`prompt_injection_signals` 默认空（models.py:466-468）。
- 证据：fetch_adapters.py 在抓取时对外部内容调用 `detect_prompt_injection` 并写入 document.prompt_injection_signals；这些结果到 UrlReadAdapter 后被丢弃。extractor.py:83 用 `window.untrusted_content` 决定 `trust_boundary`；因此外部网页被标为 `TRUSTED_WORKSPACE_CONTENT`。verifier.py:96 `[w for w in read_windows if w.prompt_injection_detected]` 对外部窗口恒为 False，`PROMPT_INJECTION_DETECTED` 告警永不触发。
- 影响：外部不可信网页在给 LLM 抽取时被标记为可信，且抓取阶段检测到的 prompt-injection 信号被静默丢弃，本地校验器的注入隔离链路对外部内容整体失效——这是安全相关的正确性缺陷（提示注入防线被绕过）。
- 建议：在 UrlReadAdapter.read_hit 中显式透传 `untrusted_content=document.untrusted_content`、`prompt_injection_detected=document.prompt_injection_detected`、`prompt_injection_signals=list(document.prompt_injection_signals)`，并补齐 source_type/author/institution/published_at/updated_at/freshness_status（与 WorkspaceReadAdapter 对齐）。

### [中] 外部读窗丢失来源元数据导致引用溯源缺失
- 位置：`workers/research-worker/app/read_adapters.py:126`
- 问题：同上根因——UrlReadAdapter 未透传 source_type/author/institution/published_at/updated_at/freshness_status。
- 证据：extractor.py:199-215 从 window 读取 author/institution/published_at/updated_at/freshness_status 写入 ResearchEvidenceCard；外部证据卡这些字段全部退化为默认值（author=""、freshness_status="UNKNOWN"）。
- 影响：外部来源证据卡永远缺失作者/发布时间/新鲜度，报告引用与新鲜度判定对外部来源无效。
- 建议：与严重项一并透传所有来源元数据字段。

### [高] json_repair 的行注释剥离会破坏含 `//` 的合法 JSON（如 URL）
- 位置：`workers/research-worker/app/json_repair.py:47`
- 问题：`re.sub(r"//.*?$", "", text, flags=re.MULTILINE)` 用于去掉 `//` 注释，但会把字符串值内的 `//` 及其后内容一并删除。
- 证据：LLM 输出常含 URL，如 `{"url": "https://example.com/x"}`，`//` 之后到行尾会被删成 `{"url": "https:`，导致 json.loads 失败；所有候选（含原始 stripped）都经过该修复，故含 URL 的合法 JSON 也会解析失败返回 None。
- 影响：抽取/校验/引用等所有依赖 parse_json_payload 的链路，只要 LLM 合法返回里带 URL（source_url、引用链接极常见）就解析失败，退化为空结果，静默丢证据。
- 建议：仅在能确认不在字符串内时剥离注释，或改用容错 JSON 解析器；至少先尝试直接 json.loads(candidate) 未修复版本，仅在失败后再做保守修复。

### [高] gzip 解压炸弹：先整体解压后再检查大小
- 位置：`workers/research-worker/app/fetch_adapters.py:1016`
- 问题：`_decompress_response` 调用 `gzip.decompress(raw)` 一次性把全部数据解压进内存，之后（1019 行）才判断 `len(decompressed) > max_bytes`。大小限制在解压完成之后才生效。
- 证据：`_read_response_bytes` 对压缩输入上限为 `min(2_000_000, max(65_536, token_budget*120))`（默认约 96KB），但 gzip 压缩比可达 1000×，96KB 可膨胀到数十~上百 MB，全部先驻留内存再被拒。
- 影响：恶意/异常网页返回高压缩比 gzip 可触发瞬时大内存占用，构成 DoS（尤其在 fetch_max_concurrency 并发抓取时叠加）。
- 建议：改用 `zlib.decompressobj` 流式解压，边解压边累计字节数，超过 max_bytes 立即中止并抛错，避免整体解压。

### [中] SSRF 校验对 IPv4-mapped IPv6 等边界地址可能漏判
- 位置：`workers/research-worker/app/fetch_adapters.py:819`
- 问题：`_resolve_public_addresses` 依赖 `ipaddress.ip_address(...).is_global` 判断是否公网地址。旧版本 Python 对 IPv4-mapped IPv6（如 `::ffff:169.254.169.254`、`::ffff:127.0.0.1`）的 is_global/is_private 判定存在已知缺陷，可能误判为公网。
- 证据：`socket.getaddrinfo` 未限定 family，可能返回 IPv4-mapped 记录；仅以 `is_global` 作为唯一门禁。
- 影响：在特定运行时/DNS 结果下，映射地址可能绕过 SSRF 校验访问内网/元数据端点。整体设计（DNS 解析+连接 IP pinning）较稳健，此为残余边界风险。
- 建议：对 IPv4-mapped/IPv4-compatible 显式解映射为 IPv4 后再判 is_global；额外显式拒绝 loopback/link-local/unique-local/保留段，并锁定运行时 Python 版本下限。

### [中] CompositeSearchAdapter 溢出补充轮重复调用外部搜索，重复计费
- 位置：`workers/research-worker/app/search_adapters.py:373`
- 问题：首轮按 provider_quota 分配后若 `len(merged) < global_limit`，第二轮 for 循环对**所有** adapter 再次调用 `adapter.search(...)`，对外部搜索适配器即再次发起真实 HTTP 检索。
- 证据：373-381 行 overflow 补充轮无条件重新 `adapter.search`；ExternalSearchAdapter.search 每次都会真实分页请求 provider。
- 影响：当配额未填满时，每个外部 provider 的检索请求量与费用翻倍，且额外增加延迟；对按次计费的 Serper 等 provider 直接造成成本浪费。
- 建议：溢出补充轮只对尚有余量的 adapter、且用「剩余预算」而非重跑完整检索；或缓存首轮结果做二次分配，避免重复网络调用。

### [中] verifier 直接下标访问 stop_contract["min_sources"] 可抛 KeyError
- 位置：`workers/research-worker/app/verifier.py:200`
- 问题：`if len(ledger.rows) < int(plan.stop_contract["min_sources"]):` 用 `[]` 直接取键，而全文件其它 stop_contract 访问均用 `.get(..., default)`。
- 证据：若 plan.stop_contract 未包含 `min_sources`（或值非 int 可转），本地校验器会抛 KeyError/ValueError。
- 影响：一旦 planner 未写入 min_sources，run_local_verifier 直接崩溃，整个研究回合失败；且该异常在 callback 层被判为可重试（RuntimeError 之外），可能反复重试同一确定性失败。
- 建议：改为 `int(plan.stop_contract.get("min_sources", 0) or 0)` 并对非法值做兜底。

### [高] 本地校验器的注入告警对外部内容永不触发（read_adapters 缺陷的下游放大）
- 位置：`workers/research-worker/app/verifier.py:96`
- 问题：`injection_windows = [window for window in read_windows if window.prompt_injection_detected]`；因 UrlReadAdapter 丢失该标记（见 read_adapters 严重项），外部窗口恒为 False。
- 证据：fetch 阶段检测到的注入信号→document.prompt_injection_signals→在 read 阶段被丢→verifier 处永远筛不出注入窗口，`PROMPT_INJECTION_DETECTED` 决策记录与 global 阻断（verifier.py:653）从不产生。
- 影响：即便外部页面含明显 prompt-injection，dual-verifier 也不会隔离/降级，report_gate 仍可能放行；安全告警链路对最常见的外部来源整体失效。
- 建议：修复 read_adapters 透传后本项自动缓解；另建议 verifier 兜底对 `window.untrusted_content` 的外部窗口重新调用 detect_prompt_injection 做二次核验。

### [中] extractor 在 LLM 缺失分值时回填合成 support/conflict 分（与 P0-4 诚实化目标相悖）
- 位置：`workers/research-worker/app/extractor.py:165`
- 问题：`support_score = _clamp_score(raw_card.get("support_score"), default=0.72)`、conflict 默认 0.05；当 LLM 未返回分值时回填固定 0.72/0.05。
- 证据：文件顶部注释（12-15 行）明确指出要根除「硬编码 0.82/0.58 伪证据分」；但此处仍在无分值时注入 0.72 支撑分，进而影响 cell_verifier 的 composite 判定与 ledger 支撑度。
- 影响：LLM 省略分值即被赋予较高默认支撑分，重新引入「伪分驱动」的风险，弱化 verifier 有效性。
- 建议：无 LLM 分值时应保守取低分（如 0.0 或 WEAK_SUPPORT 级别）或标记为不可判定，而非默认 0.72。

### [中] cell_verifier 忽略真实 source_quality，导入常量成死代码
- 位置：`workers/research-worker/app/cell_verifier.py:268`（`_avg_source_quality`）；导入见 `cell_verifier.py:30`
- 问题：`_avg_source_quality` 仅按 relation_type 给固定因子（SUPPORTS=0.85 等），忽略 ResearchEvidenceCard 已有的 `source_quality` 字段；顶部 `from app.source_profile import HIGH_TRUST_QUALITY_SCORES` 从未使用（死导入）。
- 证据：models.py:613 证据卡带 `source_quality`（OFFICIAL_DOC/GENERAL_WEB…），docstring 却声称「不带显式 source_quality_score」；composite=avg_support*0.85 与来源可信度无关。
- 影响：OFFICIAL_DOC 与 GENERAL_WEB 在规则降级判定中被同等对待，SUPPORTS 判定不反映来源可信度；且 HIGH_TRUST_QUALITY_SCORES 导入误导维护者。
- 建议：用 `HIGH_TRUST_QUALITY_SCORES.get(card.source_quality, 0.55)` 计算质量因子，或移除死导入并修正 docstring。

### [中] citation_verifier 逐 (finding,evidence) 调用 LLM 蕴含判定，无缓存去重
- 位置：`workers/research-worker/app/citation_verifier.py:55`
- 问题：`_semantic_support_status` 对每个 finding 的每条 evidence 各发一次 `research.verify.citation` LLM 调用，相同 (claim, quote) 对不缓存。
- 证据：55 行在双层循环内逐条调用；同一证据被多个 finding 引用时重复请求。
- 影响：LLM 调用数随 finding×evidence 线性增长，成本/延迟偏高，且可能更快触及 `max_total_calls` 预算导致后续调用被 CALL_BUDGET_EXHAUSTED 阻断。
- 建议：对 (claim, quote) 做记忆化缓存；或批量提交多对进行一次判定。

### [低] report_refiner 多轮修订实际失效，max_revisions>1 无效
- 位置：`workers/research-worker/app/report_refiner.py:19`、`report_refiner.py:83`
- 问题：修订循环 `for revision_no in range(1, max_revisions+1)` 在首轮末尾执行 `failed_rows.clear()`，下一轮开头 `if not failed_rows: break` 立即退出，导致最多只做 1 轮。
- 证据：83 行清空后循环条件恒中断；`revision_count` 恒 ≤1，尽管默认 max_revisions=2。
- 影响：多轮引用降级修订的设计意图不生效（死逻辑）；若一次降级引入新的失败行也不会被二次处理。
- 建议：以每轮重算 failed_rows（基于最新 citation_verification）驱动循环，而非直接 clear；或明确将 max_revisions 固定为 1 并删除误导性的循环外壳。

### [优化] report_refiner 用 `item not in demoted` 对 dict 列表做 O(n·m) 比较
- 位置：`workers/research-worker/app/report_refiner.py:26`
- 问题：`[item for item in verified if item not in demoted]` 对 dict 列表逐一相等比较，复杂度 O(n·m) 且依赖 dict 值相等。
- 影响：findings 较多时低效；若两条 finding 内容恰好相等会误删。
- 建议：改用 row_id 集合过滤（demoted 已按 row_id 计算），按 id 判定成员关系。

### [低] llm_client 读取 LLM 响应无大小上限
- 位置：`workers/research-worker/app/llm_client.py:130`
- 问题：`data = json.loads(response.read().decode("utf-8"))` 无字节上限，完全信任 LLM 端点返回体大小。
- 证据：与 fetch/搜索侧的 `_read_response_bytes`/max_response_bytes 限制不同，此处无任何截断。
- 影响：异常/被劫持的 LLM 端点返回超大响应可致内存膨胀；虽为受配置端点，风险较低但缺乏一致的防御深度。
- 建议：`response.read(limit+1)` 并在超限时报错，或至少设上限（如 8~16MB）。

### [低] llm_client 重试退避无抖动且不在 sleep 期间检查取消
- 位置：`workers/research-worker/app/llm_client.py:140`、`llm_client.py:144`
- 问题：`time.sleep(min(2**(attempt-1), 4))` 固定退避无 jitter；且 cancellation_checker 只在每次尝试开始处调用（126 行），sleep 期间不响应取消。
- 影响：多 worker 同时重试可能同步冲击 provider（无抖动）；取消延迟最长约 4s×attempts。
- 建议：加入随机抖动；将 sleep 拆分为可中断的小段并在其间检查取消。

### [低/优化] rate_limiter 在持锁期间 sleep，跨线程串行化
- 位置：`workers/research-worker/app/rate_limit.py:16`
- 问题：`FixedIntervalRateLimiter.acquire` 在持有 `self._lock` 时 `time.sleep(wait)`，令所有并发 fetch 线程完全串行等待。
- 证据：CompositeFetchAdapter 用 ThreadPoolExecutor（fetch_max_concurrency 默认 4）共享同一 transport 的限流器；持锁 sleep 使并发退化为串行间隔。
- 影响：这是固定间隔限流的正确语义，但缺少突发额度/令牌桶，且持锁 sleep 让「并发抓取」名不副实。
- 建议：如需真正并发，改用信号量+令牌桶；或明确文档化该限流为全局串行间隔。

### [低] 外部搜索 GET 传输同时发送 X-API-KEY 与 Authorization，且响应无大小上限
- 位置：`workers/research-worker/app/search_adapters.py:182`、`search_adapters.py:110`
- 问题：HttpGetSearchTransport 对任意 base_url 同时带 `X-API-KEY` 与 `Authorization: Bearer {api_key}`；两个传输类的 `response.read()` 均无字节上限。
- 证据：184-189 行两头都塞 api_key；110/193 行整体 read。
- 影响：若 base_url 配置成非预期端点，会向其泄露 api_key（双份）；超大 provider 响应可致内存压力。虽 base_url 为运维配置，仍属防御不足。
- 建议：按 provider 只发其所需鉴权头；对响应读取加上限。

### [低] search_adapters 非 serper 的 JSON provider 缺 base_url 时回退为 provider 名作 URL
- 位置：`workers/research-worker/app/search_adapters.py:754`（`_default_base_url`）
- 问题：`_default_base_url` 对非 serper 直接返回 `provider_name`，HttpJsonSearchTransport 会把它当作请求 URL。
- 证据：756-757 行；若 provider="searchapi" 且未配 base_url，则 POST 到 URL="searchapi"，urlopen 抛 URLError 被吞，返回空。
- 影响：配置缺失时静默返回 0 结果，难以定位（无显式错误）。
- 建议：非 serper 且缺 base_url 时直接抛配置错误或跳过该 provider 并记录告警。

### [低] source_profile 域名分类用子串匹配易误判
- 位置：`workers/research-worker/app/source_profile.py:57`
- 问题：`any(token in domain for token in ["medium.com","substack.com","news","blog"])` 等用子串包含，`"news"`/`"blog"`/`"api."` 会命中无关域名（如 `businessnews.example`、`renews.com`）。
- 影响：来源质量分类误判（如把普通站点判为 SECONDARY_SOURCE 或 OFFICIAL_DOC），进而影响 verifier 的 low-trust 判定。
- 建议：对这些 token 用更精确的域名后缀/主机段匹配，而非全字符串包含。

### [中] kafka_consumer 重试整条消息会重跑整个研究任务
- 位置：`workers/research-worker/app/kafka_consumer.py:130`
- 问题：重试循环里 `handle_research_run_message` 每次都重新 `fetch_task_input` 并 `run_research_task` 全流程执行。
- 证据：71-77 行 handle 无状态；127-139 行对可重试异常在同一消息上重复调用。
- 影响：任务后段失败（如 send_complete 前）时会重跑完整搜索/抓取/LLM，成本高；progress 事件会重复发送（complete 有 idempotency_key 但 progress 序号递增不同）。
- 建议：区分「已产出结果、仅回放最终提交」与「需重跑」；参考 agent_kafka_consumer 的 execution.result 复用模式。

### [低] kafka_consumer failed_count 统计的是失败尝试次数而非失败消息数
- 位置：`workers/research-worker/app/kafka_consumer.py:134`
- 问题：`failed_count += 1` 在每次失败尝试时自增，最终既作 `failed_count` 又作 `failed_attempt_count`，语义混淆。
- 影响：消费汇总指标（failed_count）会高于实际失败消息数，监控/告警口径易被误读。
- 建议：分离「失败尝试计数」与「失败消息计数」，failed_count 只在消息最终失败时+1。

### [低] agent_kafka_consumer 的 ExecutionStopped 会终止整个消费循环
- 位置：`workers/research-worker/app/agent_kafka_consumer.py:268`
- 问题：租约丢失抛出的 ExecutionStopped 从 for 循环向外传播，越过 `consume_research_agent_commands` 使整个消费者线程退出（offset 不提交）。
- 证据：268-271 行 `raise`；外层未捕获。
- 影响：单个任务租约失效即让 worker 停止消费全部后续消息，依赖进程级重启恢复；属设计取舍但运维影响较大，需确保有守护重启。
- 建议：文档化该「快速失败+重启」策略；或将 ExecutionStopped 局部处理（跳过该消息、不提交 offset）后继续消费其余消息。

### [低] callback 对 Java 响应无大小上限，且异常 error_code 由类型名生成致重试分类粗糙
- 位置：`workers/research-worker/app/callback.py:149`、`callback.py:229`
- 问题：`_request` 用 `response.read()` 无上限读取 Java 响应；send_fail 的 error_code 用 `type(exc).__name__.upper()`，`_is_retryable_worker_error` 仅按关键字（AUTH/CONFIG/VALIDATION…）判定。
- 证据：确定性异常（如 KeyError→"KEYERROR"）不含非重试关键字，被判为 retryable，会被上游反复重试同一必然失败。
- 影响：确定性 bug（如 verifier 的 min_sources KeyError）被标为可重试，浪费重试预算且掩盖根因。
- 建议：响应读取加上限；对确定性异常（KeyError/AttributeError/IndexError 等）显式归类为不可重试。

### [中] config.load_settings 每次调用都重新解析环境，无缓存
- 位置：`workers/research-worker/app/config.py:104`
- 问题：`load_settings()` 每次都 `return Settings()`，重新读取所有环境变量并做校验。
- 证据：llm_client.build_default_*、fetch_adapters、search_adapters、consumers、callback 等多处按操作反复调用 load_settings。
- 影响：热路径（每次抓取/搜索构建 adapter）重复解析 env、重复触发 pydantic 校验，产生不必要开销；且运行期 env 变更会被意外读入，行为不一致。
- 建议：用 `functools.lru_cache` 或模块级单例缓存 Settings。

### [低] task_snapshot / completion 契约的 digest 为纯 SHA-256 完整性校验（非鉴权），依赖 claim 通道可信
- 位置：`workers/research-worker/app/task_snapshot_contract.py:45`、`research_agent_completion_contract.py:236`
- 问题：`require_valid_digest` 与 envelope/receipt digest 均为无密钥 SHA-256（`hmac.compare_digest` 仅做常量时间比较，非 HMAC 签名）。
- 证据：与设计文档 MA4D 一致（「不含 snapshot_digest 的 canonical JSON、UTF-8 SHA-256」），鉴权来自受信 claim HTTP 响应与服务端存储的 digest 比对，Kafka 命令不携带 snapshot——故非漏洞。
- 影响：仅提供完整性/一致性而非真实性；若未来 claim 通道不可信则无签名保护。另 `budget: dict[str,int|float]` 的浮点在跨运行时（Java/Python）规范化差异可能导致 digest 不一致。
- 建议：维持现设计并在契约注释中标明「完整性非鉴权」；对 budget 尽量用整数 ppm 表达避免浮点规范化偏差（completion 契约已用 *_ppm，snapshot budget 建议同样约束）。

### [低] reporter 将不可信外部内容逐字嵌入 Markdown 报告
- 位置：`workers/research-worker/app/reporter.py:528`、`reporter.py:857`（`_render_row_markdown`/`claim_text`/`quote_text`）
- 问题：报告直接把 claim_text、quote_text、source_title（可能来自外部网页且含 prompt-injection 文本）拼入 Markdown 输出，未做转义/隔离标注。
- 证据：因 read_adapters 缺陷（严重项）注入窗口不会被隔离，含注入文本的证据仍可进入 verified/evidence_ledger 并被逐字渲染。
- 影响：生成的报告可能含 Markdown/链接注入内容；若下游把报告再喂给 LLM（二次消费）则构成注入放大。属产物内容，非代码执行，风险中低。
- 建议：对渲染进 Markdown 的外部文本做转义或代码块包裹，并对被标记注入的窗口在报告中明确隔离标注。

### [低] trace_security.sanitize_trace_payload 无递归深度限制，且列表内嵌套 dict 递归无长度并保护
- 位置：`workers/research-worker/app/trace_security.py:23`
- 问题：对任意嵌套 dict/list 递归脱敏，无深度上限；深层嵌套 payload 理论上可致递归较深。
- 证据：35-45 行对 dict/list 递归，仅对 list 截断前 20 项，未限制嵌套层数。
- 影响：正常 trace 结构浅，风险很低；但对抗性/异常构造的深嵌套 payload 缺乏保护。
- 建议：加入最大递归深度阈值，超出即哈希摘要化。

### [低] reader.open_read_windows 会二次抓取，run_research_read 路径重复 fetch 风险
- 位置：`workers/research-worker/app/reader.py:14`；`read_adapters.py:230`
- 问题：reader.open_read_windows 先 `run_research_fetch` 再传 fetched_documents 给 run_research_read；而 run_research_read 在 fetched_documents 为 None 时又会自行 `run_research_fetch`。两条入口若误用易造成重复抓取。
- 证据：reader.py:14 显式抓取后传入；read_adapters.py:231-232 兜底再抓取。
- 影响：当前 reader 路径已传 documents 不会重复；但 API 存在两处 fetch 触发点，后续维护误调用 run_research_read(search_hits=...) 会重复网络抓取。
- 建议：统一 fetch 入口，read 层只消费已抓取文档，移除 run_research_read 内的隐式 fetch 兜底或明确文档化。

### [低] cell_verifier 的 verdict.evidence_ids 声明了 LLM 未见过的证据
- 位置：`workers/research-worker/app/cell_verifier.py:263`
- 问题：`_verify_with_llm` 只把 `cards[:5]` 放进 prompt（224 行），但返回的 CellVerdict.evidence_ids 用 `[card.evidence_id for card in cards]`（全部 cards）。
- 影响：verdict 归因的证据集合可能超出 LLM 实际判定所依据的 5 条，溯源不精确。
- 建议：evidence_ids 只记录实际进入 prompt 的那批（cards[:5]）。

### [低] fetch_adapters 响应头 title() 归一无法处理重复头，Content-Type 等以最后一个为准
- 位置：`workers/research-worker/app/fetch_adapters.py:867`
- 问题：`{str(key).title(): str(value) for key, value in response.getheaders()}` 用字典推导，重复响应头（如多个 Set-Cookie/Content-Type）后者覆盖前者。
- 影响：极端/异常响应下取到的 Content-Type/Content-Encoding 可能非预期，影响二进制/编码判定；常规场景影响很小。
- 建议：对关心的头显式取第一个或合并处理。

### [优化] fetch_adapters 每次抓取重复两次 DNS 解析
- 位置：`workers/research-worker/app/fetch_adapters.py:849`、`fetch_adapters.py:851`
- 问题：`_pinned_http_get` 循环内先 `validate_public_http_url(current_url)`（内部已 `_resolve_public_addresses`），随后又 `_resolve_public_addresses(parsed)` 再解析一次。
- 影响：每个 URL/每次重定向多一次 getaddrinfo，轻微增加延迟；两次解析间存在 TOCTOU 窗口（已由连接 IP pinning 缓解）。
- 建议：合并为单次解析，复用地址列表用于校验与连接。

### [优化] prompt 注入检测正则易误报，TOOL_POLICY_OVERRIDE 过宽
- 位置：`workers/research-worker/app/fetch_adapters.py:761`
- 问题：`TOOL_POLICY_OVERRIDE` 模式 `\b(call|invoke|use|run)\b.{0,30}\b(tool|function|shell|terminal|browser)\b` 会命中大量正常技术文本（如 "use the browser"、"run the function"）。
- 影响：大量正常外部技术文档被标为含注入信号，触发不必要的隔离/告警，降低信号可信度（狼来了效应）。
- 建议：收紧模式（要求更强的指令性上下文），或对信号做分级（高置信/低置信）而非一刀切标记。

### [低] extract_evidence_cards 的 quote 定位在全文 find，模型仅见前 1200 字
- 位置：`workers/research-worker/app/extractor.py:83`、`extractor.py:154`
- 问题：LLM 只被喂 `window.window_text[:1200]`，但 `quote_start = window.window_text.find(quote)` 在全文查找；若同一片段在 1200 字之后重复出现，quote_start 仍取首次出现位置，通常一致，但语义上定位依据与模型可见范围不完全对齐。
- 影响：绝大多数情况正确；边界情形下 quote_start/quote_end 可能指向模型未见的重复片段。
- 建议：在模型可见窗口范围内定位（对 `window_text[:1200]` 做 find），或把完整可定位窗口喂给模型。

### [中] 测试/校验缺口：无针对 read_adapters 字段透传、json_repair URL、gzip 解压炸弹、SSRF 映射地址的用例
- 位置：`workers/research-worker/app`（read_adapters.py / json_repair.py / fetch_adapters.py）
- 问题：本次发现的严重/高问题（外部窗口 untrusted 透传、`//` 破坏含 URL 的 JSON、gzip 解压炸弹、IPv4-mapped SSRF）均属易被单测覆盖但当前显然缺失的场景。
- 影响：这些安全/正确性回归无自动化防护，后续改动易再次引入。
- 建议：补充针对性单测：外部读窗必须保留 untrusted_content=True 且带注入信号；含 https URL 的 LLM JSON 必须成功解析；高压缩比 gzip 必须被安全拒绝而非 OOM；`::ffff:127.0.0.1`/`::ffff:169.254.169.254` 必须被 SSRF 校验拦截。

### [低] evaluate_gold_set 直接下标 `item["case_key"]` 缺容错
- 位置：`workers/research-worker/app/evaluate_gold_set.py:17`
- 问题：`{str(item["case_key"]): item for item in gold_payload.get("cases", [])}` 对每个 case 直接取 `case_key`，缺键即 KeyError；gold 文件格式错误时报错信息不友好。
- 影响：仅离线评测工具，影响面小；但畸形 gold 文件导致原始 KeyError 而非清晰提示。
- 建议：用 `.get("case_key")` 并校验缺失时抛带路径的 SystemExit。

### [低] agent_task_client 的 claim/heartbeat/permit 请求读取 Java 响应无字节上限
- 位置：`workers/research-worker/app/agent_task_client.py:214`
- 问题：`_request_serialized` 仅 complete 路径传了 `max_response_bytes=_MAX_RECEIPT_BYTES`；claim/heartbeat/permit/delivery-failure 走默认 `max_response_bytes=None`，`response.read()` 无上限。
- 证据：196-198 行 `_request` 不传上限；215 行 None 分支整体读取。
- 影响：受信 Backend，风险低；但与 complete 路径的严格上限不一致，缺乏一致防御。
- 建议：为所有内部请求设置合理响应字节上限。

### [优化] deterministic_fake_toolchain.extract 多目标时对样本行数要求脆弱
- 位置：`workers/research-worker/app/deterministic_fake_toolchain.py:49`
- 问题：多列时要求 sample_text 的非空行数 ≥ 列数且前 N 行互异，否则抛 ValueError。
- 影响：仅金丝雀/测试夹具，正常无害；但对夹具数据格式要求隐性且报错时机在执行中，易让金丝雀因数据微调而失败。
- 建议：在构造夹具处前置校验，或放宽为按列 key 复用整段样本。

### [低] models.ResearchEvidenceCard 缺 source_quality_score，下游只能按 relation 兜底
- 位置：`workers/research-worker/app/models.py:599`
- 问题：ResearchEvidenceCard 只有 `source_quality`（字符串枚举）而无数值 `source_quality_score`，而 ResearchReadWindow/ResearchSearchHit 都带 score。
- 证据：cell_verifier `_avg_source_quality` 正因此才退化为按 relation_type 兜底（见前述中危项）。
- 影响：证据卡丢失了 read/fetch 阶段已算出的 source_quality_score，导致下游可信度加权只能近似。
- 建议：在 extractor 构造证据卡时透传 `window.source_quality_score`，并补充该字段到 ResearchEvidenceCard。

### [低] llm_client 预算耗尽阻断在按 purpose 覆盖 model 之后、但估算 token 用整体 request_payload 序列化长度
- 位置：`workers/research-worker/app/llm_client.py:164`
- 问题：`prompt_tokens` 缺失时用 `max(1, len(json.dumps(request_payload)) // 4)` 估算输入 token；request_payload 已含被 compact 的 payload，但 default=str 未用，且 ensure_ascii 默认 True 会放大非 ASCII 字符串长度，估算偏差较大。
- 影响：成本估算（estimated_cost）对中文等非 ASCII 内容显著偏高/不稳，llm_cost_ledger 指标不精确。
- 建议：估算时对文本用 `len(text)` 或 tiktoken 近似，避免用 ASCII 转义后的 JSON 串长度作 token 代理。

### [优化] verifier_gate_policy / agent_command_contract / unicode_contract 审查通过，无功能缺陷
- 位置：`workers/research-worker/app/verifier_gate_policy.py`、`agent_command_contract.py`、`unicode_contract.py`、`agent_contracts.py`、`intent_contract.py`、`research_agent_completion_contract.py`
- 问题：这些文件逻辑严谨（严格 pydantic 约束、NFC 归一、去重键、规范化 digest、租约/围栏令牌一致性校验），未发现正确性缺陷；research_agent_completion_contract 的 ppm 整数化、去重、大小上限、规范序列化尤为完善。
- 影响：无。
- 建议：保持现状；仅需关注跨运行时（Java 侧）规范化必须与 `_compact_json_bytes`（NFC + sort_keys + 紧凑分隔 + allow_nan=False + 身份列表排序）严格一致，否则 digest 校验会误拒。
