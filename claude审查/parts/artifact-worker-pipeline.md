## Artifact Worker · 生成管线

### main.py

### [中] PDF 导出下载路由的文件名正则过于宽松，仅靠 is_relative_to 兜底
- 位置：`workers/artifact-worker/app/main.py:145-157`
- 问题：`file_name` 校验为 `[^/\\]{1,160}\.pdf`，允许冒号、控制字符、非 ASCII 等。Windows 上形如 `C:foo.pdf` 的盘符相对路径在 `export_root / file_name` 拼接时会替换掉 `export_root`，真正拦截依赖后面的 `is_relative_to` 判断。
- 证据：正则只排除 `/` 与 `\`；未排除 `:`、`.` 前缀、`NUL` 等；防穿越唯一保证是第 155 行 `is_relative_to`。
- 影响：一旦后续重构去掉/改动 `is_relative_to` 检查，即刻退化为路径穿越；当前为纵深防御可用但脆弱。
- 建议：将文件名限制为白名单字符集（如 `[A-Za-z0-9_-]{1,120}\.pdf`），与导出侧 `_safe_file_stem` 的产出保持一致，保留 `is_relative_to` 作为二次防线。

### [低] debug 明细路由用 ValueError/裸异常表达 404，语义不清
- 位置：`workers/artifact-worker/app/main.py:966-969`
- 问题：`debug_get_waiting_task_detail` 找不到记录时 `raise ValueError(...)`，其他 detail 路由（capability、acquisition、writeback）在下游同样抛 ValueError。这些会被全局异常处理器转成 500 而非 404。
- 证据：第 968 行 `raise ValueError(f"waiting task not found: {task_id}")`；download 路由用的是 `HTTPException(404)`，风格不一致。
- 影响：调试接口对“未找到”返回 500，掩盖真实错误类别，观测性差。
- 建议：统一 not-found 语义为 `HTTPException(status_code=404)`。

### runner.py

### [中] verify 路径重复计算 build_output_contract_trace 三次
- 位置：`workers/artifact-worker/app/runner.py:260-262`
- 问题：runner 先显式调用 `build_output_contract_trace(result, plan, repaired_checks)`（260 行），随后 `verify_artifact_output(...)`（262 行）内部又调用一次 `build_output_contract_trace`（见 verifier.py:18）。同一大对象轮廓/短语/证据/结构校验被完整跑两遍（合计三次，因为 verify 内部还各跑）。
- 证据：`verifier.verify_artifact_output` 第 18 行 `contract_trace = build_output_contract_trace(...)`；runner 又单独存了一份 `output_contract_trace`。
- 影响：每个任务对 markdown 做重复正则/子串扫描与 trace 组装，浪费 CPU；数据无差异纯冗余。
- 建议：`build_output_contract_trace` 只算一次，`verify_artifact_output` 接收已算好的 trace（或让 runner 只调 verify 并从其结果派生 contract_trace）。

### [中] async provider 判定与 build_acquisition_receipt 的 request_id 生成两处硬编码，易漂移
- 位置：`workers/artifact-worker/app/runner.py:866-868` 与 `content_runtime.py:1169-1175`
- 问题：`_resolve_async_provider_capabilities` 里手写 `f"fetch-{task_input.task_id}-{source_plan.source_id}-{operation_key.lower()}"`，与 `content_runtime._build_operation_request_id` 逻辑重复但不共享函数。
- 证据：两处字符串模板必须字节级一致才能命中 `get_acquisition_result_payload(request_id)`。
- 影响：任一处改动（例如加前缀/改分隔符）都会静默导致 async 判定与 receipt/结果查询错位，产物拿不到已获取内容而重复等待。
- 建议：`runner` 复用 `content_runtime._build_operation_request_id`（提升为公开函数）。

### [中] 导出（PDF/LaTeX 编译）在生成主链路同步阻塞，超时上限达 1 小时
- 位置：`workers/artifact-worker/app/runner.py:145-149` → `export_runtime.py:31` → `custom_mcp_executor.py:122-131`
- 问题：`export_artifact_if_required` 通过 `_call_custom_mcp_tool` 同步 `subprocess.run(..., timeout=load_settings().mcp_process_timeout_seconds)`，该超时默认 3600 秒。此调用发生在 `/tasks/{id}/run`（或 resume）的请求线程内。
- 证据：config.py `mcp_process_timeout_seconds: int = Field(default=3600, ...)`；export 在 `run_artifact_task` 主体（非后台线程）中执行。
- 影响：LaTeX 编译慢或卡死时，整个任务执行 HTTP 请求最长阻塞 1 小时，占用 worker 线程；与其它 async provider 采用回调/后台线程的模式不一致。
- 建议：为导出设置独立的、更短的超时；或将 PDF 导出走与 acquisition 相同的 async provider/后台线程回调路径。

### [中] _build_lifecycle_trace 用 `event == events[-1]` 做对象相等判断标记末步
- 位置：`workers/artifact-worker/app/runner.py:813-822`
- 问题：以 `status if event == events[-1] else "COMPLETED"` 判定最后一步。`ArtifactProgressEvent` 是 pydantic 模型，`==` 为字段值相等而非身份。若两个事件字段完全相同（例如 metrics 相同的两步），非末步也可能被判为末步。
- 证据：正常 PHASE_SEQUENCE 各 phase 不同不会撞，但该写法语义脆弱；应使用索引/身份判断。
- 影响：潜在的生命周期状态标注错误（把中间步标成最终态），观测数据失真。
- 建议：改用 `enumerate` 按索引判断末步，或 `event is events[-1]`。

### [低] _build_evidence_coverage 用 source.title 作 key 映射到 source_id，标题重复会覆盖
- 位置：`workers/artifact-worker/app/runner.py:917-920`（`_build_memory_promotion_preview` 1003-1006 同样）
- 问题：`source_title_to_id = {source.title: source.source_id for ...}`。当两个来源标题相同时，后者覆盖前者，且 section.source_refs 用标题匹配也会归到错误 source_id。
- 证据：source_refs 全程以“标题”为引用键（generation_runtime 也用 title 作 allowed_source_titles）。
- 影响：多来源同名（如两篇同题网页）时证据覆盖/记忆候选的 source_id 归属错误。
- 建议：来源引用改用 source_id 作为稳定键，标题仅作展示。

### generation_runtime.py

### [高] LLM 生成的 section heading 必须与 outline 精确相等，否则内容被 repair 丢弃
- 位置：`workers/artifact-worker/app/generation_runtime.py:110-132` 与 `repair.py:16-27`
- 问题：`_parse_sections` 原样接收 LLM 的 heading，`repair_sections` 用 `by_heading = {section.heading: section}` 再按 `plan.outline` 精确取。凡 heading 与 outline 文案不完全一致的 section 全部被当作“缺失”，替换成确定性默认文案；LLM 多出的 section 直接丢弃。
- 证据：repair.py:16-27 `by_heading.get(heading)` 为 None 即插默认体。
- 影响：LLM 只要 heading 有细微差异（空格/标点/大小写/顺序），生成结果被静默丢弃并回退为模板，`generation_trace.mode` 仍标 LLM_GENERATION，形成“看似用了 LLM 实则模板”的假象。
- 建议：按顺序/规范化匹配 outline heading，或将 LLM heading 归一化后对齐；不匹配时保留 LLM 正文而非整体丢弃。

### [中] 源上下文预算被 TRANSCRIPT 双 CCO 与 MIXED_CONTEXT CCO 重复消耗
- 位置：`workers/artifact-worker/app/generation_runtime.py:79-100` 配合 `content_runtime.py:337-419`
- 问题：`_build_source_payload` 按 24k 总预算/6k 单条截断遍历所有 CCO。但 content_runtime 对 TRANSCRIPT 源会产出两个 CCO（TRANSCRIPT + EXTERNAL_CONTENT_SNAPSHOT，正文相同），多源时又追加一个 MIXED_CONTEXT CCO（把所有源正文再拼一遍）。这些重复正文都会挤占 LLM 上下文预算。
- 证据：content_runtime.py:338-385 同一 `plain_text` 生成两个对象；:397-418 MIXED_CONTEXT 再 `"\n".join(descriptor plain_text)`。
- 影响：真实来源被重复内容挤出预算，模型可用信息减少；token 浪费。
- 建议：`_build_source_payload` 按 source_id 去重（同一来源只喂一次），或跳过 MIXED_CONTEXT/快照副本。

### [中] LLM 未配置或返回无效时静默回退为确定性模板，仅靠 trace 体现
- 位置：`workers/artifact-worker/app/generation_runtime.py:25-31, 59-67`
- 问题：`llm_client is None`（未配 key/model）或响应解析为空时，返回 `fallback_sections` 并把 mode 记为 `DETERMINISTIC_FALLBACK`。没有告警/日志，仅埋在 `generation_trace`。
- 证据：无 `logger`，两处直接 return fallback。
- 影响：生产环境误删/未配 LLM 凭据会长期以模板产物“成功”交付，难以察觉质量塌陷。
- 建议：回退时打 warning 日志；必要时在 verification/lifecycle notes 显式标注降级，供上游告警。

### json_repair.py

### [高] 注释剥离正则 `//.*?$` 会破坏字符串内含 `//` 的合法 JSON（如 URL）
- 位置：`workers/artifact-worker/app/json_repair.py:20`
- 问题：`re.sub(r"//.*?$", "", candidate, flags=re.MULTILINE)` 无差别删除每行 `//` 之后的内容。LLM 生成正文里极常见 URL（`https://...`），会被截成 `"https:` 导致该候选 JSON 失效；由于所有候选套用同一替换，最终 `parse_json_payload` 返回 None，触发确定性回退。
- 证据：candidates 全部经过同一 `repaired = re.sub(...)`。
- 影响：只要 LLM 输出的任意字符串值含 `//`，整份 JSON 解析失败并静默降级为模板，属高频真实触发的健壮性缺陷。
- 建议：不要用行级正则删注释；改用容忍注释的解析（json5 风格）或仅在字符串外剥离；至少保留“未剥离”原始候选作为最后尝试。

### [中] 尾逗号正则 `,\s*([}\]])` 可能改动字符串字面量内容
- 位置：`workers/artifact-worker/app/json_repair.py:21`
- 问题：正则不区分字符串内外，若正文里出现 `... ,}` 或 `, ]` 这类子串（在字符串值中）也会被替换，改变原文语义。
- 证据：与 `//` 同样是纯文本级替换。
- 影响：低概率但会静默篡改被解析出的文本内容。
- 建议：使用真正的解析器容错，或先定位字符串边界再处理。

### content_runtime.py

### [高] _adapt_source_inputs / 动作解析在单次任务内被重复执行多遍
- 位置：`workers/artifact-worker/app/content_runtime.py:755-809`（被 `build_content_acquisition_plan`、`build_canonical_content_objects`、`build_acquisition_receipt`、`describe_input_adapter_routes` 各自调用）
- 问题：`_adapt_source_inputs` 每次都重新 `resolve_action_compatibility`、`try_resolve_artifact_skill_definition`、重建虚拟 URL 源。runner 一次任务中该函数至少被调 3-4 次；`describe_input_adapter_routes` 又在 `action_resolver` 里被调。
- 证据：runner.py:54-55 build_execution_plan→build_content_acquisition_plan；:55 build_canonical_content_objects；:186 build_acquisition_receipt。
- 影响：重复的 catalog 解析与列表构建，纯 CPU 浪费；同时放大下条“action_key 来源不一致”的风险面。
- 建议：在任务起点计算一次 descriptors/effective_action_key 并透传。

### [中] CCO 构建用 effective_action_key，而 receipt 构建用 plan.action_key，二者可能不同导致 produced_cco_ids 错位
- 位置：`workers/artifact-worker/app/content_runtime.py:315`（CCO，默认 effective_action_key）对比 `:473`（receipt，`_adapt_source_inputs(task_input, plan.action_key)`）
- 问题：`build_canonical_content_objects` 用 `resolve_action_compatibility(...).effective_action_key`，`build_acquisition_receipt` 用 `plan.action_key`（= action_resolution.resolved_action_key）。当显式 action 与 skill 绑定一致走 EXPLICIT 分支时 resolved_action_key=requested_action_key，而 effective_action_key 也=它，通常一致；但在自定义 action/AUTO 推断路径下 `plan.action_key`（context 推断）与 `effective_action_key`（"AUTO"）可能不同，进而 `_resolve_normalization_target_kind` 结果不同 → CCO 的 kind/数量与 receipt 里 `normalization_target_kind`、`produced_cco_ids` 索引错配。
- 证据：`_index_ccos_by_source` 基于 CCO 的 source_trace，与 receipt 里按 plan.action_key 重算的 descriptors 独立。
- 影响：receipt 的 output_kind/segment_count/result_locator 与真实 CCO 不一致，观测与下游写回预览失真。
- 建议：CCO 与 receipt 统一使用 `plan.action_key`（或统一 effective_action_key）作为唯一动作口径。

### [中] TRANSCRIPT 源产出两个 CCO，重复内容进入检索/记忆/证据统计
- 位置：`workers/artifact-worker/app/content_runtime.py:337-385`
- 问题：normalization_target_kind == "TRANSCRIPT" 时先 append 一个 TRANSCRIPT CCO，再 append 一个 EXTERNAL_CONTENT_SNAPSHOT CCO，二者 title/plain_text 相同。`_index_ccos_by_source` 会把两者都算作该 source 的产物。
- 证据：:337-361 append TRANSCRIPT；:365-385 又 append kind=EXTERNAL_CONTENT_SNAPSHOT，正文均为 `plain_text`。
- 影响：produced_cco_ids 翻倍、segment_count 翻倍、LLM 上下文重复；检索/记忆预览重复计数。
- 建议：明确是否需要双 CCO；若仅为保留转写与快照两视图，应在下游按 kind 去重。

### [中] 未采集内容时静默回退到 sample_text/summary/title 作为“已获取”正文
- 位置：`workers/artifact-worker/app/content_runtime.py:774`
- 问题：`plain_text = source.sample_text.strip() or source.summary.strip() or source.title`。当 acquisition_payload 为空（如 web/transcript 尚未真正抓取）时，直接用样本文本甚至标题充当正文继续生成。
- 证据：:321-336 仅在有 acquisition_payload 时覆盖 plain_text，否则保留该回退值。
- 影响：外部内容未就绪时仍可能生成“看似有据”的产物，实际正文只是标题/摘要，证据可信度虚高。
- 建议：区分“无正文”与“样本占位”，对需要外部采集的路由在无 payload 时应保持等待/标注证据缺失，而非用 title 当正文。

### [低] build_content_acquisition_plan 中局部变量 `steps` 重复定义、且 required_capabilities 未含 CARD/多源分支
- 位置：`workers/artifact-worker/app/content_runtime.py:163, 280`
- 问题：`steps` 在多源分支与默认分支两次 `steps: list[...] = [...]`（类型重复注解，非 bug 但坏味道）。另外多源分支 required_capabilities 仅在 has_external_web 时含 READ_WEB_PAGE，若多源里混有 VIDEO_URL/AUDIO 则其转写能力未被收入 plan.required_capabilities（由各 source_plan 各自携带，但顶层 plan 能力面不完整）。
- 证据：:206-217 多源分支 `required_capabilities=["READ_WEB_PAGE"] if has_external_web else []`。
- 影响：顶层 required_capabilities 与逐源 required_capabilities 口径不一致，能力预解析可能遗漏多源混合场景的转写能力。
- 建议：顶层能力应为各 source_plan required_capabilities 的并集。

### acquisition_runtime.py

### [中] 采集回调 ack 在持有全局锁期间做文件读取（最大 4MB）
- 位置：`workers/artifact-worker/app/acquisition_runtime.py:411`（`_summarize_provider_payload`→`_extract_provider_payload_text`→`read_text_file_limited`）
- 问题：`acknowledge_acquisition_operation` 在 `with _acquisition_lock:` 块内调用 `_summarize_provider_payload`，后者会读取 provider 产出的字幕/转写文件（上限 4MB）。文件 IO 在全局锁内进行。
- 证据：:364 进入锁，:411 调用 summarize，:484 才 `_persist...` 并退出锁。
- 影响：单个慢速磁盘/大文件读取会阻塞所有 acquisition 操作（list/dispatch/其他 ack），并发吞吐下降。
- 建议：把文件读取/摘要移到锁外，仅在拿到结果后短暂持锁更新状态。

### [中] configure_acquisition_runtime_store 读取损坏状态文件时无 path 上下文（P2-2 未落地）
- 位置：`workers/artifact-worker/app/acquisition_runtime.py:34-39`
- 问题：`payload = json.loads(_acquisition_storage_path.read_text(...))` 无 try/except。状态文件损坏时抛裸 `JSONDecodeError`，无文件路径上下文，直接使启动/配置失败。设计记录 P2-2 明确列为“待修复”。
- 证据：:35 直接 json.loads；对比 P0/P1 已落地（原子写、claim、上限、超时均可见）。
- 影响：状态文件损坏导致 worker 启动崩溃且难定位；与文档收尾目标存在偏差。
- 建议：捕获 JSON/OS 异常，附带路径与建议（重置 runtime），或降级为空状态并告警。

### [低] register_acquisition_runtime 保留在途操作时仍计数并可能重复注册回调回执
- 位置：`workers/artifact-worker/app/acquisition_runtime.py:154-168, 170-203`
- 问题：当 `_should_preserve_existing_runtime_operation` 命中（已 RUNNING/ACKNOWLEDGED/FAILED）时保留旧记录，但仍 `registered_operation_count += 1`；随后若“新构建”的 operation.callback_status=="ACKNOWLEDGED" 又会写 callback receipt，可能与在途实际状态冲突。
- 证据：:168 计数无条件自增；:170 起的回执注册不判断是否保留了旧操作。
- 影响：重复注册/计数在重跑（如显式 run + provider 回调交叉）时产生不一致的快照统计与回执。
- 建议：保留分支下应跳过回执注册并按“preserved”单独计数。

### [低] input_digest 仅由 request_id/locator/capability/source 拼接，不含内容，无法用于去重内容变更
- 位置：`workers/artifact-worker/app/acquisition_runtime.py:563-571`
- 问题：`_build_input_digest` 用元数据拼接 sha256，不含实际输入内容；命名易误解为“输入内容摘要”。
- 影响：若上游期望以 input_digest 做幂等/内容变更检测会失效（同 locator 内容变化摘要不变）。
- 建议：明确其为“请求标识摘要”或纳入内容哈希。

### export_runtime.py

### [中] _safe_file_stem 用 isalnum() 保留非 ASCII（中文）字符，导出文件名含中文
- 位置：`workers/artifact-worker/app/export_runtime.py:75-80`
- 问题：`character.isalnum()` 对中文返回 True，故中文标题原样进入文件名。虽下载路由正则 `[^/\\]{1,160}\.pdf` 放行，但跨系统/编码、URL 传递、LaTeX 输出目录处理时中文文件名易出问题。
- 证据：:76-78 仅把非 alnum 且非 `-_` 的替换为 `-`。
- 影响：文件名含中文/全角字符，潜在编码与下载兼容问题。
- 建议：限制为 ASCII 字母数字与 `-_`，中文转拼音或哈希兜底。

### [中] 导出仅硬编码识别 skill_key=="bilibili_course_note_pdf"
- 位置：`workers/artifact-worker/app/export_runtime.py:17-25`
- 问题：是否导出 PDF 完全由字符串等值判断决定，其它需要二进制导出的 skill 无法触发；导出目标（system MCP bilibili server）也硬编码。
- 证据：:18 `if skill_key != "bilibili_course_note_pdf": return NOT_REQUIRED`。
- 影响：扩展性差，新增导出类 skill 必须改代码；与“配置化 Production Action/Skill”设计取向不符。
- 建议：将“是否需要导出/导出器”下沉为 skill/action 配置项。

### [低] _resolve_video_url 用子串 `in source.source_uri` 判定 bilibili，大小写/子域不稳
- 位置：`workers/artifact-worker/app/export_runtime.py:64-72`
- 问题：未 `.lower()` 归一，`"bilibili.com" in source.source_uri` 对大写域名漏判；与 content_runtime 里已归一的判定不一致。
- 影响：某些 URL 大小写形态下取不到 video_url，PDF 元信息缺失。
- 建议：与 content_runtime 统一使用归一化后的 URL 匹配工具函数。

### writeback_runtime.py

### [高] 写回请求/回执全内存，不持久化，进程重启即丢失（与 acquisition 持久化不一致）
- 位置：`workers/artifact-worker/app/writeback_runtime.py:19-27`
- 问题：`_writeback_requests`/`_writeback_receipts` 仅内存字典，`clear_writeback_runtime` 也只清内存；无 configure/persist（对比 acquisition_runtime 有文件持久化与原子替换）。
- 证据：全文件无 storage_path/持久化逻辑；run 结束把 request 写入 result_payload 但运行时状态不落盘。
- 影响：worker 重启后 dispatch/ack 的写回请求全部丢失，DISPATCHED_TO_HOST 的回调 token 无法再匹配（`acknowledge_writeback_request` 会抛 not found），破坏 at-least-once 回调；文档 P1-2 强调 runtime 状态需持久化，此处存在偏差。
- 建议：与 acquisition 一致地持久化写回运行时状态（原子写）。

### [中] register_writeback_request 的 request_id 仅含 version_id+target，重复注册会静默覆盖
- 位置：`workers/artifact-worker/app/writeback_runtime.py:41, 71-72`
- 问题：`request_id = f"writeback-{version_id}-{allowed_target.lower()}"`，同一版本同目标重复生成会覆盖已存在（可能已 DISPATCHED/ACKED）的请求记录，无冲突保护。
- 证据：:71-72 直接 `_writeback_requests[request_id] = ...`，无 existing 检查。
- 影响：重跑或幂等重放时会把已推进的写回状态重置回 READY，丢失 dispatch/ack 历史。
- 建议：注册前检查是否已存在终态/在途请求并保留。

### [低] acknowledge_writeback_request 通过遍历全表匹配 callback_token，O(n) 且 token 无唯一索引
- 位置：`workers/artifact-worker/app/writeback_runtime.py:240-249`
- 问题：用线性扫描 `next(record for ... if callback_token 匹配)`。dispatch 每次生成新 token 并覆盖 request["callback_token"]，历史 delivery 的 token 不再可匹配（只认最后一次）。
- 影响：多次 dispatch 后，旧 delivery 的合法回调（重试）无法匹配到请求；请求量大时线性扫描亦低效。
- 建议：维护 token→request_id 索引，并保留历史 token 的可匹配性以支持重试。

### [低] dispatch 允许从 FAILED 重新派发但未清理上一次 delivery_attempts 的终态语义
- 位置：`workers/artifact-worker/app/writeback_runtime.py:174, 191-208`
- 问题：`status in {"READY_FOR_HOST_WRITEBACK","FAILED"}` 可再 dispatch，追加新 delivery_attempt。旧 FAILED attempt 保留但 request 顶层 last_error 被清空，attempts 与顶层状态的因果关系仅靠顺序体现。
- 影响：审计时难以区分“重试成功”与“多次失败后成功”，可观测性一般（非正确性 bug）。
- 建议：为 attempt 增加显式 supersedes/attempt_index 语义。

### composer.py

### [低] render_markdown 生成的 markdown 与实际导出/section 不完全一致，且 source_refs 仅取前 3 源
- 位置：`workers/artifact-worker/app/composer.py:11, 24-33`
- 问题：markdown 的“Source Scope”只列 `source_scope[:3]` 标题；而 PDF 导出走 repaired_sections 独立渲染（export_runtime），两条渲染路径并存，未来易漂移。section body 直接内联无转义（若 body 含 markdown 控制字符会影响结构，但当前语义可接受）。
- 影响：markdown 与 PDF 两种产物结构可能不一致；来源展示被截断。
- 建议：统一以 sections+来源全集为单一渲染来源，PDF 由同一 markdown 派生或明确声明两条链路差异。

### compiler.py

### [中] resolve_capability_bindings 被调用两次（compiler 预解析 + runner 正式解析），重复且可能不一致
- 位置：`workers/artifact-worker/app/compiler.py:78-83` 与 `runner.py:57-62`
- 问题：compiler 里为 capability_union_policy 先做一次 `resolve_capability_bindings`（preliminary），runner 又对同一 `plan.lazy_loaded_capabilities` 再解析一次。两次解析间若 provider 注册表状态变化，policy 判定与运行期绑定不一致。
- 证据：compiler:78 preliminary_resolved_bindings；runner:57 resolved_bindings。
- 影响：重复 CPU；策略评估与实际执行的绑定可能基于不同快照，边界条件下判定漂移。
- 建议：将解析结果在 plan 中透传给 runner 复用，或统一在一处解析。

### [低] schema gate 用 ValueError 抛出计划拒绝，全部走同一异常类型，难以分类处理
- 位置：`workers/artifact-worker/app/compiler.py:54-66, 91-106, 219, 249`
- 问题：action-skill 不匹配、prompt-recipe 不匹配、source 为空、能力策略拒绝、证据门拒绝、图有环/悬挂边等全部 `raise ValueError(...)`。上层 callback 以 `type(exc).__name__` 作 error_code，全部变成 `VALUEERROR`。
- 证据：callback.py:342 `error_code=type(exc).__name__.upper()`。
- 影响：不同拒绝原因在 Java 侧无法按类型区分重试/不可重试，可观测与重试策略受限。
- 建议：定义专用异常类（PlanRejectedError 等）并携带 reason_code。

### intent_compiler.py

### [低] _extract_max_bullets 仅处理中文数字“三”，且存在无效的链式 replace 死代码
- 位置：`workers/artifact-worker/app/intent_compiler.py:303-315`
- 问题：`.replace("三","3").replace("三条","3条").replace("三点","3点")` —— 首个 replace 后 "三" 已不存在，后两个 replace 为死代码；其它中文数字（五/十/两）不支持。
- 影响：形如“控制在五条以内”提取不到 max_bullets，约束丢失（静默）。
- 建议：用完整中文数字映射或正则统一提取。

### [低] max_bullets 约束被提取后并未在生成/修复中实际强制
- 位置：`workers/artifact-worker/app/intent_compiler.py:291-300`（产出 `max_bullets=N` constraint）对比 `repair.py:150-166`（`_repair_resume_highlights` 只补足到 ≥3，不裁剪上限）
- 问题：constraints 里写入 `max_bullets=N`，但 repair 与 verifier 只检查“至少 3 条”，从不按上限裁剪；LLM 也不保证遵守。
- 影响：用户“最多 N 条”约束不生效，是需求-实现偏差。
- 建议：在 repair 中依据 max_bullets 裁剪，或在 verifier 增加上限校验。

### repair.py

### [中] repair 完全依赖 outline heading 精确匹配，非目标 action 的自定义 section 无本地修复
- 位置：`workers/artifact-worker/app/repair.py:16-27, 36-66`
- 问题：只对 QUIZ/WIKI_PAGE 及固定中文标题（“简历亮点”“关键词”）做结构修复；其它 action/自定义 outline 仅做“缺失则插默认体”。默认体文案（`_default_section_body`）大量硬编码中文与特定项目术语。
- 证据：:79-147 默认体针对 QUIZ/WIKI/简历硬编码；其它 heading 落到 `f"Local repair inserted section for {heading}."` 英文占位。
- 影响：非内置 action 的产物在缺段时被填入无意义英文占位；修复能力与 action 强耦合、不可配置。
- 建议：将 section 级修复策略下沉到 skill/action 配置（verifier_policy/repair_policy 已存在但此处未使用）。

### [低] _repair_resume_highlights 丢弃非 bullet 行，可能删除有效说明文字
- 位置：`workers/artifact-worker/app/repair.py:150-166`
- 问题：只保留 `- ` 开头行（`bullet_lines`），其余正文（引导语、分组标题）在返回时被丢弃。
- 影响：LLM 生成的非 bullet 说明被静默删除，产物信息丢失。
- 建议：保留原正文，仅在 bullet 数不足时追加补充项。

### verifier.py

### [中] 存在 failed_checks 时状态被判为 WARN 而非 FAIL，可能掩盖硬性契约失败
- 位置：`workers/artifact-worker/app/verifier.py:121`
- 问题：`status = "PASS" if not failed_checks and not warnings else "WARN"`。即使 outline 缺段、必需短语缺失、证据覆盖 FAIL、结构校验 FAIL 都只产生 WARN，从不产生 FAIL。
- 证据：所有 `_append_trace(status="FAIL", ...)` 都进 failed_checks，但整体状态仅到 WARN。
- 影响：契约“失败”与“告警”不可区分；下游据 verification.status 判定质量时无法拦截真正不合格产物。（注：审查记录第 4 节说明当前 WARN-且允许出草稿是既有产品语义，故列为设计取向问题而非纯 bug，但 failed 与 warn 混同仍值得修正。）
- 建议：区分“可降级 WARN”与“硬失败 FAIL”，failed_checks 非空时给出 FAIL 或独立的 hard_failed 标记。

### [低] verifier 对 sections 混合 dict/model 两种形态做兼容判断，说明上游类型不统一
- 位置：`workers/artifact-worker/app/verifier.py:308, 364-372`
- 问题：`_verify_resume_highlight_structure` 既处理 `isinstance(section, dict)` 又处理 `getattr(section, "body")`，因为 result_payload["sections"] 是 dump 后的 dict，而部分路径可能传模型。类型不统一增加分支与出错面。
- 影响：可维护性差，易漏处理某一形态。
- 建议：统一 sections 的类型契约（全 dict 或全模型）。

### policy.py

### [中] custom server 前缀判断用 `startswith("custom")` 而其它模块用 `startswith("custom-")`，口径不一致
- 位置：`workers/artifact-worker/app/policy.py:60, 75` 对比 `runner.py:553`、`content_runtime.py:1005`、`acquisition_runtime`
- 问题：policy 用 `selected_server_id.startswith("custom")`（无连字符），而 runner/content_runtime 用 `"custom-"`。server_id 形如 `customfoo`（无连字符）在 policy 会被判为 custom，但在 async 分发处不会，判定不一致。
- 影响：边界命名下能力策略与执行分发对“是否自定义 MCP”判断分歧，可能出现 policy 拦截但执行侧不当作 custom（或反之）。
- 建议：统一使用 `startswith("custom-")` 或集中一个 `is_custom_server(server_id)` 工具。

### [中] Capability Union 高风险组合仅在同时出现 READ_WORKSPACE_DOC 时拦截，硬编码单一规则
- 位置：`workers/artifact-worker/app/policy.py:82-88`
- 问题：数据外泄防护只覆盖 `"READ_WORKSPACE_DOC" in caps and high_risk_external_network`。若工作台读取能力以别的 capability_name 出现（如 READ_CARD/READ_SOURCE），组合风险不被拦截。
- 证据：仅硬编码 `READ_WORKSPACE_DOC` 一个键。
- 影响：Capability Union Policy 的核心风控存在绕过面，未覆盖等价的工作台读取能力。
- 建议：以 scope_type（如 WORKSPACE_READ 类）而非单一 capability 名做组合判定。

### [低] evaluate_writeback_gate 未校验所需 writeback capability 是否真实可解析
- 位置：`workers/artifact-worker/app/policy.py:202-212`
- 问题：ALLOW 分支直接返回 required_capabilities（来自静态映射），未确认这些能力有可用 provider；实际 gating 交给后续 provider 解析。若映射能力无绑定，writeback preview 可能停在 WAITING 而 gate 已 ALLOW。
- 影响：writeback 状态语义分散在 policy 与 runner._build_writeback_preview 两处，边界情形判断分散。
- 建议：在文档中明确两级判定职责，或在 gate 阶段附带能力可达性提示。

### models.py

### [低] ArtifactSkillDefinition 的 requires_url_input/url_input_keys 为每次访问重算的 property，且被高频调用
- 位置：`workers/artifact-worker/app/models.py:83-116`
- 问题：这两个 property 每次访问都遍历 input_schema；intent_compiler/content_runtime 在校验与虚拟源构建中多次访问。属只读派生值，可缓存。
- 影响：轻微重复计算（数据量小，影响有限）。
- 建议：如成为热点可用 functools.cached_property 或预计算。

### [低] result_payload 为无 schema 的 dict[str, object]，大量运行时字段无类型约束
- 位置：`workers/artifact-worker/app/models.py:846` 及 runner 全程对 `result.result_payload[...]` 赋值
- 问题：`ArtifactTaskResult.result_payload: dict[str, object]` 收纳 30+ 字段（markdown/execution_plan/各种 trace/commit 等），全部无类型校验，键名靠约定。
- 影响：字段拼写错误、结构漂移不会被 pydantic 捕获；下游（Java/前端）契约脆弱。
- 建议：为核心 payload 字段建立 typed 子模型或文档化契约。

### config.py

### [中] LLM/写回/采集等状态文件默认路径为相对路径，依赖进程工作目录
- 位置：`workers/artifact-worker/app/config.py:11-14`
- 问题：`artifact_repository_file_path` 等默认 `.artifact-worker-*.json` 为相对路径。main.py 又基于 `artifact_runtime_state_file_path` 的 parent 推导 exports 根目录。工作目录不同则状态文件与导出目录位置漂移。
- 证据：main.py:151-153 `Path(settings.artifact_runtime_state_file_path).resolve().parent / "exports"`。
- 影响：以不同 cwd 启动 worker 会读到不同/新建的空状态，历史 runtime 状态“丢失”；多实例可能相互踩踏相对路径文件。
- 建议：默认使用绝对路径或显式的数据目录配置，并在启动时校验。

### [低] llm_timeout_seconds 无上下界校验，可被配成 0/负值（虽 client 侧 max(1,...) 兜底）
- 位置：`workers/artifact-worker/app/config.py:28-31` 对比 `llm_client.py:115`
- 问题：`llm_timeout_seconds` 无 `ge` 约束；靠 `build_default_llm_client` 的 `max(1, ...)` 兜底。mcp_process_timeout_seconds 有 `ge/le` 但 LLM 超时没有。
- 影响：配置面校验不一致；极大值不受限（可配成数天）。
- 建议：为 llm_timeout_seconds 增加 `ge=1, le=<上限>`。

### io_limits.py

### [中] read_text_file_limited 无路径来源约束，可读取任意本地文件
- 位置：`workers/artifact-worker/app/io_limits.py:32-44`
- 问题：仅做大小上限（4MB）与 utf-8 容错，不校验路径是否位于受控目录。调用方（content_runtime `_build_text_from_acquisition_payload`、acquisition_runtime `_extract_provider_payload_text`）直接把 provider payload 中的 `selected_subtitle_path`/`txt_files[0]` 传入。
- 证据：provider_payload 来自 MCP 工具 ack（`acknowledge_acquisition_operation` 的 provider_payload），若 provider/回调被污染可指向任意路径（如读取敏感文件并进入产物）。
- 影响：结合外部/自定义 provider，存在本地文件任意读取风险，读取内容会进入 CCO/产物正文。审查记录 P1-3 提到需“增加大小和来源约束”，来源约束此处缺失。
- 建议：限制可读路径必须位于受控执行器输出根目录内（类似 main.py 的 is_relative_to 校验）。

### [低] read_text_file_limited 一次性 read(max+1) 到内存，超限才报错
- 位置：`workers/artifact-worker/app/io_limits.py:38-43`
- 问题：`handle.read(max_bytes + 1)` 一次读入最多 ~4MB 到内存再判超限，正常路径可接受，但在锁内（见 acquisition_runtime）会放大阻塞。
- 影响：与前述“锁内文件读取”叠加，峰值内存与锁持有时间上升。
- 建议：结合锁外读取即可缓解。

### llm_client.py

### [中] OpenAI 兼容客户端把所有异常（含 4xx 语义错误）吞成空串静默回退
- 位置：`workers/artifact-worker/app/llm_client.py:85-95`
- 问题：`except (URLError, TimeoutError, ValueError, JSONDecodeError)` 统一 `logger.warning` 后 `return ""`。鉴权失败（401）、模型不存在（404）、配额（429）等 HTTPError 均被当成“空响应”→ 走确定性回退。
- 证据：HTTPError 是 URLError 子类，被同一分支吞掉。
- 影响：配置错误/额度耗尽等本应告警的问题被静默降级为模板产物，长期不可见。
- 建议：区分可恢复（超时/网络）与不可恢复（鉴权/参数）错误，对后者提升日志级别或上抛，避免长期静默降级。

### [中] LLM base_url 无 SSRF/协议约束，且请求体大小未限
- 位置：`workers/artifact-worker/app/llm_client.py:49, 76-84`
- 问题：`base_url` 仅 strip/rstrip，未校验协议/主机；`urllib.request.urlopen` 会跟随重定向且可请求内网地址。请求体（sources 已在 generation_runtime 截断，但 payload dict 本身）未做整体大小上限。
- 证据：:49 直接用配置 base_url；请求侧无 payload 体积校验（仅响应侧有 MAX_LLM_RESPONSE_BYTES）。
- 影响：base_url 虽为运维配置（非用户输入，风险较低），但缺协议白名单与重定向控制；若配置被污染存在 SSRF 面。
- 建议：校验 base_url 为 https 且非内网；如需可禁用重定向。

### [低] 响应解析仅取 choices[0].message.content，未处理 function_call/refusal/多 choice
- 位置：`workers/artifact-worker/app/llm_client.py:96-102`
- 问题：只读第一个 choice 的 message.content。若模型返回 refusal 字段或 content 为结构化数组（部分兼容实现），会得到空串→回退。
- 影响：某些兼容后端返回形态下静默降级。
- 建议：兼容 content 为 list（拼接 text 片段）与 refusal 情形。

### callback.py

### [中] resume 成功后先 emit 回调再 remove_waiting_task，emit 与 remove 之间的崩溃会导致重复完成
- 位置：`workers/artifact-worker/app/callback.py:181-188`
- 问题：`_emit_callbacks_for_result`（含 send_complete）成功后，若非等待态才 `remove_waiting_task`。send_complete 已带幂等键，但若在 send_complete 之后、remove 之前进程崩溃，waiting task 仍在队列，后续会再次被 wake→再次执行生成与 complete；依赖 Java 幂等键去重。
- 证据：:182 emit；:186-187 之后才 remove。
- 影响：at-least-once 语义下的重复执行窗口；重复生成消耗资源（幂等键仅保证 Java 侧不重复推进状态，不阻止 worker 重复计算/重复写 runtime）。
- 建议：complete 成功即原子标记该 task 已终结（持久化），wake 前先检查终结标记。

### [中] progress 事件先于 complete 发送，多个 progress 失败会中断且不发 complete，但生成已完成
- 位置：`workers/artifact-worker/app/callback.py:246-252`
- 问题：`for event in events: callback_client.send_progress(...)` 任一 progress 抛异常（网络抖动）会打断循环，直接向上抛（在 run_with_callbacks 中此异常发生在 run_artifact_task 之后、`_emit_callbacks_for_result` 内，不再被 fail try 包裹——good），但 complete 不会发送，任务停在 RUNNING 等重试。
- 证据：:147-152 run 在 try 内，emit 在 try 外；progress 异常向上传播到 FastAPI → 500。
- 影响：progress 传输抖动使已成功生成的任务重跑（重跑会重复 commit_artifact_result、重新 reserve version）。commit/version reserve 无幂等保护（见下）。
- 建议：progress 失败不应阻断 complete；progress 可 best-effort（吞掉单个 progress 传输异常并继续）。

### [高] 任务重试会重复 reserve_next_artifact_version 与 commit_artifact_result（生成无幂等）
- 位置：`workers/artifact-worker/app/callback.py:137-152` → `runner.py:172, 289-296`
- 问题：`run_artifact_task_with_callbacks` 在任何回调/生成失败后 `raise`，Java 侧重试会再次执行整条 `run_artifact_task`，其中 `reserve_next_artifact_version` 递增版本、`commit_artifact_result` 追加版本与 retrieval 条目。无基于 task_id 的幂等保护。
- 证据：runner 每次执行都 reserve 新 version_id 并 commit。
- 影响：一次任务的多次重试会在 Worker repository 产生多个版本/检索条目，版本号膨胀、retrieval 重复；虽 Java 是版本真源，但 Worker 快照被污染。
- 建议：以 task_id 幂等（同 task 重跑复用已 reserve 的版本或先查后建）。

### [低] _dispatch_system_provider_operations 遍历 receipt dict 手工解析，与模型脱节且吞并异常
- 位置：`workers/artifact-worker/app/callback.py:261-285`
- 问题：从 result_payload["acquisition_receipt"]（dict）手工逐层 `isinstance` 解析并调用 `submit_system_mcp_acquisition_operation`。任一 submit 抛异常会中断整个 dispatch 循环（无 per-op try），已 dispatch 的与未 dispatch 的处于不一致态；且返回值 dispatches 未被使用。
- 影响：单个 system provider 分发失败会阻断同批其它源的分发；返回值废弃降低可观测性。
- 建议：per-operation try/记录失败并继续；或复用模型对象而非裸 dict 解析。

### 跨文件 / 执行器（export 与 acquisition 依赖 custom_mcp_executor）

### [中] 自定义 MCP 子进程 launch_command/args/env/cwd 来自注册表，构成受控命令执行面
- 位置：`export_runtime.py:31` 与 `acquisition_runtime`→`custom_mcp_executor.py:93-131`
- 问题：`_call_custom_mcp_tool` 用 `subprocess.run([launch_command, *launch_args], cwd=working_directory, env={**os.environ, **launch_env})` 启动进程。这些字段来自 custom MCP server 注册（debug/internal 注册端点）。虽非 shell=True（无 shell 注入），但注册者可指定任意可执行文件与参数、任意工作目录、覆盖环境变量。
- 证据：命令、cwd、env 全部取自 server 模型字段，无白名单校验。
- 影响：能注册 custom MCP server 者即可让 worker 执行任意本地程序；env 合并允许覆盖 PATH 等敏感变量。属特权注册面，需确保注册端点受 internal_auth_token 保护并有审计。
- 建议：对 launch_command 做可执行白名单/绝对路径校验，限制 cwd 范围，禁止覆盖敏感环境变量。

### [低] 导出/采集子进程默认超时 3600s，且 stdout 全量 splitlines 解析无行数/体积上限
- 位置：`custom_mcp_executor.py:130-165`
- 问题：`timeout=mcp_process_timeout_seconds`（默认 3600）；`completed.stdout.splitlines()` 逐行 `json.loads` 无输出体积上限（capture_output 已将全部 stdout 读入内存）。
- 影响：异常工具可产生超大 stdout 撑爆内存；1 小时超时叠加同步导出（见 runner 条目）放大阻塞。
- 建议：限制导出超时与 stdout 体积上限。

### [低] runner._build_wait_provider_delivery_attempts 硬编码 dispatch_count=1 与 ack_status=PENDING
- 位置：`workers/artifact-worker/app/runner.py:504-536`
- 问题：等待事件里构造的 provider_delivery_attempts 恒为单条、dispatch_count=1、ack_status="PENDING"，与 acquisition_runtime 实际 dispatch_count/attempts 脱节（后者才是真源）。`_resolve_wait_dispatch_count` 优先用 operation.dispatch_count，但 attempt 列表本身是伪造的。
- 影响：等待态上报给 Java 的 provider_job.provider_delivery_attempts 与 runtime 真实投递历史不一致，观测数据失真。
- 建议：从 acquisition_runtime 的实际 operation 读取 attempts，而非在等待事件里重造。

### [低] evidence 覆盖默认密度与 verifier 取值链存在潜在不一致
- 位置：`runner.py:957`（`required_citation_density or "LOW"`）对比 `verifier.py:153-158`（回退取 `plan.evidence_gate.required_citation_density`）
- 问题：evidence_coverage 的 required_density 来自 `plan.evidence_gate.required_citation_density`（源自 style_profile.citation_density），而 verifier 在 evidence_coverage 缺字段时又回退到 plan.evidence_gate；两处默认（"LOW" vs 模型默认）与大小写处理分散。
- 影响：低概率下密度口径不一致导致证据门判定与展示不符。
- 建议：单点计算 required_density 并透传。

### 测试缺口（综合）
- 位置：`workers/artifact-worker`（对照 scope 内文件）
- 问题：以下高风险路径缺少针对性回归：(1) json_repair 对含 URL(`//`)/字符串内 `,}` 的 LLM 输出（当前会误伤，见对应条目）；(2) generation LLM heading 与 outline 不一致时内容被丢弃的行为；(3) writeback runtime 无持久化后重启丢失、重复注册覆盖；(4) io_limits 读取受控目录外路径的来源约束；(5) 任务重试导致版本/commit 重复的幂等；(6) LLM 4xx（鉴权/配额）静默降级。
- 影响：这些正确性/健壮性缺陷无测试护栏，回归风险高。
- 建议：为上述场景补充单元/集成测试。
