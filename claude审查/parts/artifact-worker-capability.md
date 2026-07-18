## Artifact Worker · 能力/技能/MCP层
（审查发现如下，分批追加）

## capability_provider.py

### [高] set/get override key 不对称，导致 candidate 级状态覆盖对全局键失效
- 位置：`capability_provider.py:655-670`（`_override_key` vs `_candidate_key`）
- 问题：写入时用 `_override_key`，当 `provider_id` 与 `tool_name` 都为 None 时返回裸 `capability_name`；读取时（如 `get_capability_provider_status`）先查 `_candidate_key(name, None, None)`=`"NAME:*:*"`，再回退到裸 `capability_name`。但 `run_capability_provider_health_checks`/`discovery_scan` 写入用的是 `_candidate_key`（`"NAME:provider:tool"`）。若外部用 `set_capability_provider_status(name, status)`（无 provider/tool）写入裸键，随后 candidate 级 get 会先命中健康检查写的 `NAME:provider:tool`，可能拿到与预期不符的状态。
- 证据：写与读使用不同 key 构造函数，且回退层级不对齐。
- 影响：provider 状态覆盖语义在“全局覆盖”和“候选覆盖”之间不一致，测试用全局 set 时可能被健康检查结果掩盖或反之。
- 建议：读写统一使用同一 key 规则并显式定义“裸键=通配默认”的优先级顺序，补充单测覆盖 set(global) 与 candidate-get 的交互。

### [中] 健康检查/发现扫描持锁期间调用重量级唤醒，且唤醒在锁外递归执行任务
- 位置：`capability_provider.py:528-550, 546, 677-684`
- 问题：`run_capability_provider_health_checks` 在持有 `_provider_status_lock` 时完成状态写入后，于锁外调用 `_wake_waiting_tasks_for_capabilities`，后者进入 `capability_wait_queue.wake_waiting_tasks_for_capability` → `run_artifact_task`，任务执行中可能再次触发 provider 状态查询/健康检查，形成同一线程重入。虽当前锁已释放不会死锁，但唤醒会在健康检查调用栈内同步跑完整个产物任务，调用方（可能是 HTTP 请求线程）被长时间阻塞。
- 证据：`run_artifact_task` 在唤醒路径中同步执行。
- 影响：一次 health-check API 调用可能同步跑完多个被阻塞任务，响应时间不可控，且异常传播语义复杂。
### [低] list_capability_providers 直接读 `_provider_last_checked_at`/`_provider_last_discovered_at` 未持锁
- 位置：`capability_provider.py:456-463`
- 问题：`list_capability_providers` 中 `last_checked_at`/`last_discovered_at` 直接 `.get()` 全局 dict，而这两个 dict 会被健康检查在持锁下并发写入。此处无锁读取，存在读到部分更新/竞态的可能（CPython dict.get 本身不崩溃，但语义上与 `get_capability_provider_*` 的持锁读取不一致）。
- 证据：同模块其他读取函数均在 `with _provider_status_lock` 内访问。
- 影响：并发健康检查与列举时时间戳可能不一致，属数据一致性瑕疵。
- 建议：统一走持锁 getter，或将两处改为调用 `get_capability_provider_last_*`。

### [低] discovery snapshot 的 discovered/undiscovered 统计口径与文档"已发现"不符
- 位置：`capability_provider.py:591-606`
- 问题：`debug_provider_discovery_snapshot` 把 `REGISTERED` 也计入 `discovered_count`。REGISTERED 表示"已注册但未经发现扫描"，与 DISCOVERED 混为一谈，使 discovery 扫描前后 discovered_count 不变，观测价值降低。
- 证据：`discovery_status in {"REGISTERED", "DISCOVERED"}`。
- 影响：难以从快照区分"是否真正跑过发现扫描"。
- 建议：分别统计 REGISTERED / DISCOVERED，或明确文档定义。

## capability_resolver.py

### [中] _select_capability_binding 中 route_basis 被重复计算且存在死代码
- 位置：`capability_resolver.py:117, 132, 168`
- 问题：`route_basis` 先由 `_derive_route_basis(routes)` 赋值（117 行），随后 132 行又被覆盖为 `",".join(sorted(routes))...`，最后在选中候选分支（168 行）再覆盖回 `_derive_route_basis(routes)`。当未选中任何候选（`selected_candidate is None`）时，返回的是 132 行那份逗号拼接值，与选中候选时的单一路由语义不一致；117 行赋值完全是死代码。
- 证据：三处对同一变量赋值，117 行结果从未被使用。
- 影响：route_basis 输出在"有/无候选"两条路径下格式不一致，且含死代码，易误导下游 trace 消费者。
### [中] 候选选择的 approval_mode 统一取默认 binding，忽略候选自身的能力绑定
- 位置：`capability_resolver.py:138, 208-222`
- 问题：`_is_candidate_runtime_ready` 用的是 `binding.approval_mode`（来自 `resolve_capability_binding(capability_name)` 的默认绑定），但候选可能来自自定义 MCP，其 `approval_mode` 应由该 provider 的绑定决定。对于同一 capability_name 存在多个 provider（如 builtin + custom）且审批模式不同的情况，运行时就绪判定会用错审批要求。
- 证据：`_select_capability_binding` 只解析一次 `binding = resolve_capability_binding(capability_name)`，并把该 approval_mode 传给所有候选就绪判断。
- 影响：可能对需要审批的自定义 provider 放行，或对不需审批的 provider 误判为待审批。
- 建议：按候选 provider 解析各自的 approval_mode/scope，而非统一使用能力级默认绑定。

### [中] 无匹配候选时回退到全部候选，可能选中路由/动作不兼容的 provider
- 位置：`capability_resolver.py:203-205`
- 问题：`_matching_candidates` 在 `matching_candidates` 为空时回退 `matching_candidates = candidates`（全部候选）。这会导致：即便没有任何 provider 支持当前 route/action/skill_graph，也会按 preference_rank 选一个，绕过 `_candidate_matches_context` 的约束。
- 证据：`if not matching_candidates: matching_candidates = candidates`。
- 影响：例如 VIDEO_FILE 路由却选中只支持 VIDEO_URL 的 provider，运行期才失败，能力路由约束形同虚设。
- 建议：无匹配时应返回空并让上层进入 WAITING/UNAVAILABLE，而非强行回退到不兼容 provider。

### [低] resolve_capability_bindings 与 resolve_capability_providers 逻辑重复
- 位置：`capability_resolver.py:16-32` 与 `35-101`
- 问题：两函数都对每个 capability 调用 `_select_capability_binding`；`resolve_capability_bindings`（被 runner/compiler 使用）与 `resolve_capability_providers` 的选绑逻辑重复维护，容易出现两条路径行为漂移。
- 证据：均以 `_select_capability_binding` 为核心，返回结构不同。
- 影响：重复代码，选绑口径需两处同步。
## capability_approval_queue.py

### [高] request_id 不含 tool_name，同一 (task,capability,provider) 多工具审批请求互相覆盖
- 位置：`capability_approval_queue.py:24`
- 问题：`request_id = f"approval-{task_id}-{capability}-{provider_id}"` 不含 `tool_name`。同一 provider 暴露多个 tool（如 bilibili 的 get_subtitle/transcribe/render）映射到同一 capability 时，后一次 `create_capability_request` 会用相同 key 覆盖前一条审批记录。
- 证据：`_approval_requests[request_id] = record` 以该 key 存储。
- 影响：审批请求丢失/串扰，审批某工具却影响另一工具，或待审批列表缺项。
- 建议：request_id 纳入 tool_name（并做大小写/字符规整）。

### [中] approve_capability_request 唤醒任务在持锁外同步执行且异常无回滚
- 位置：`capability_approval_queue.py:81-101`
- 问题：审批通过后调用 `wake_waiting_tasks_for_capability` 同步跑任务，若任务执行抛异常会向审批 API 调用方传播，但审批状态与 provider approval_status 已被改写为 APPROVED，无法回滚，造成"已批准但恢复失败"的不一致，且异常语义混淆审批动作本身。
- 证据：先 set APPROVED，再 wake，wake 内 `run_artifact_task` 可抛。
- 影响：审批接口偶发 500，且状态不可逆。
- 建议：审批与恢复解耦；恢复异常应捕获并作为 resume_attempts 内的失败项返回，不污染审批结果。

### [低] set_capability_request_status 允许任意字符串状态，无校验
- 位置：`capability_approval_queue.py:53-78`
- 问题：`status` 仅做 `strip().upper()` 写入，未约束到合法枚举（PENDING/APPROVED/REJECTED...）。
- 影响：可写入非法状态，下游按状态分支判断时行为未定义。
## capability_wait_queue.py

### [高] wake_waiting_tasks_for_capability 迭代快照期间恢复任务，可对同一任务重复触发/漏触发
- 位置：`capability_wait_queue.py:209-240`
- 问题：函数先 `list_waiting_tasks()` 取快照，再逐个 `wake_waiting_task`。恢复过程无锁保护整个循环；`wake_waiting_task` 内部虽用 `_claim_waiting_task` 的 RESUMING 声明防重入，但多个 capability 的健康检查/审批并发进入时，A 线程正跑任务（已 claim），B 线程遍历到同一记录会因 `already being resumed` 抛 ValueError（在 `_claim_waiting_task` 中 raise），该异常会冒泡到 B 的健康检查/审批 API 使其失败。
- 证据：`_claim_waiting_task` 对 RESUMING 状态 `raise ValueError`，而 `wake_waiting_tasks_for_capability` 未捕获。
- 影响：并发唤醒下审批/健康检查接口偶发 500；且被并发 claim 的任务不会被 B 重试。
- 建议：`wake_waiting_task` 对"已在恢复中"应静默跳过而非抛异常；循环内对单任务异常做隔离收集。

### [中] 恢复完成后仅在非 WAITING 时删除，approval_request 记录的 capability 匹配为大小写敏感全等
- 位置：`capability_wait_queue.py:219-228`
- 问题：唤醒筛选条件 `normalized_capability_name not in unavailable_capabilities and != approval_capability_name`。`approval_request` 的 capability_name 未必规整为大写即写入（`enqueue_waiting_task` 直接透传 approval_request dict）。此处虽对 approval_capability_name 做了 `.upper()`，但 enqueue 侧存储的键名/大小写若与此处假设不一致（如缺 capability_name 字段）会导致该分支永不命中，审批型等待任务无法被唤醒。
- 证据：唤醒依赖 approval_request 内 `capability_name` 字段存在且语义一致，但 enqueue 未强制。
- 影响：审批完成后等待任务可能无法自动恢复。
- 建议：enqueue 时规范化 approval_request 结构并补单测覆盖审批唤醒路径。

### [中] 崩溃恢复：RESUMING→回滚仅在 configure 时进行，运行中崩溃留下永久 RESUMING
- 位置：`capability_wait_queue.py:29-40, 107-117`
- 问题：进程崩溃时若任务处于 RESUMING，持久化文件保留 RESUMING；只有下次 `configure_waiting_task_store`（启动加载）才回滚为原状态。若运行期间线程崩溃但进程存活，`release_waiting_task_claim` 依赖异常路径调用；一旦 `run_artifact_task` 以非异常方式卡死或被 kill，claim 永不释放，任务卡在 RESUMING 无法再次唤醒。
- 证据：仅 `_wake_waiting_task` 的 except 分支和启动加载会释放 claim。
- 影响：任务可能永久滞留 RESUMING。
### [中] _persist_waiting_tasks_locked 的 Windows PermissionError 回退非原子，可能损坏文件
- 位置：`capability_wait_queue.py:43-57`
- 问题：`os.replace` 抛 PermissionError 时回退为 `_waiting_tasks_storage_path.write_text(...)` 直接覆盖目标文件（非原子），若此时进程崩溃会留下截断/半写文件；下次 `configure_waiting_task_store` 用 `json.loads` 读取会抛 JSONDecodeError 且无 try/except 保护（对照 registry `_load` 有 JSONDecodeError 捕获，此处没有），导致启动即崩溃。
- 证据：line 23 `json.loads(...read_text())` 无异常处理；line 56 非原子写。
- 影响：等待队列文件损坏会阻断 worker 启动。
- 建议：回退写入也走临时文件+重试；加载时捕获并隔离损坏文件。

### [低] enqueue_waiting_task 未持锁读改，且覆盖同 task_id 记录丢失 blocked_operations
- 位置：`capability_wait_queue.py:66-87`
- 问题：`_waiting_tasks[task_input.task_id] = record` 直接整体覆盖。若同一任务因不同 capability 分批阻塞，二次 enqueue 会丢弃首次记录的 unavailable_capabilities/blocked_operations，只保留最后一次传入值。
- 证据：直接赋值而非合并。
- 影响：多能力阻塞的任务可能只保留部分等待原因，恢复不完整。
- 建议：按 task_id 合并 unavailable_capabilities 与 blocked_operations。

### [低] wake_waiting_task 中 sync_artifact_runtime_trace 仅在 matched_operation 分支执行
- 位置：`capability_wait_queue.py:174-199`
- 问题：只有 `matched_operation` 为真（即 request_id 唤醒且命中 blocked op）时才 `sync_artifact_runtime_trace`；能力/审批唤醒（无 request_id）恢复成功后不同步 runtime_trace 到已提交版本，导致版本上的 lifecycle_trace/resume 信息缺失。
- 证据：`if matched_operation:` 包裹了 trace 附加与 sync。
- 影响：审批/能力恢复的版本缺少 resume trace，可观测性不一致。
## action_resolver.py

### [高] FAQ 关键词为乱码（mojibake），中文 FAQ 意图永远无法命中
- 位置：`action_resolver.py:125`
- 问题：`_contains_any(brief_blob, ["faq", "甯歌闂", "闂瓟"])` 中后两个中文关键词是编码损坏（应为“常见问”“问答”之类）。当前字节序列几乎不可能出现在正常 UTF-8 用户输入中，等价于只保留了英文 "faq"。
- 证据：`"甯歌闂", "闂瓟"` 明显为 GBK/UTF-8 错配产生的乱码。
- 影响：中文"常见问题/问答"类需求无法触发 FAQ 动作推断，静默降级到默认 REPORT。
- 建议：修复为正确中文关键词（如 "常见问题"、"问答"），并加编码检查/单测防回归。

### [低] brief_blob 全部 lower()，与自定义 action 关键词大小写匹配对英文有效但对已 upper 的内置分支存在潜在不一致
- 位置：`action_resolver.py:188-197, 220-221`
- 问题：`_build_brief_blob` 统一 `lower()`，`_contains_any` 也 `candidate.lower()`，一致。但 `_select_custom_action_candidate` 里 `matched_keywords` 用 `keyword.lower() in brief_blob` 而 `matched_structure_keywords` 用 `keyword.lower() in structure_blob`（structure_blob 亦 lower），一致；此项仅提示：source_metadata platform 比较用 upper，混用大小写规整策略，建议集中规整以防未来漂移。
- 影响：低，当前一致。
- 建议：统一大小写规整工具函数。

## action_compat.py

### [中] normalize_skill_key 与 artifact_skill_catalog._normalize_skill_key 各自实现，规整口径易漂移
- 位置：`action_compat.py:65-66` 与 `artifact_skill_catalog.py:167-168`
- 问题：两处独立实现 skill_key 规整（strip/lower/replace -/space），逻辑当前相同但重复。若一处修改（如新增字符替换）另一处不同步，会导致 `resolve_action_key_from_skill_key` 与 catalog 解析对同一输入产生不同 canonical key。
- 证据：两文件各有一份等价实现。
- 影响：潜在的技能键解析不一致。
## artifact_repository.py

### [高] reserve_next_version 与 commit_result 分离，版本号存在 TOCTOU 竞态与碰撞
- 位置：`artifact_repository.py:95-100, 272-278, 102-110`
- 问题：`reserve_next_version` 用 `len(target_versions)+1` 生成 `target-vN` 后释放锁，`commit_result` 另取一次锁追加。两个并发任务对同一 target 会 reserve 到相同 `vN`，随后各自 commit，产生重复 version_id；且 rollback 也 append 计数，`len()` 生成的编号可能与历史已删/回滚版本碰撞。
- 证据：reserve 与 commit 是两次独立加锁，中间无占位。
- 影响：版本号重复，`get_version_detail`/回滚按 version_id 查找命中错误记录。
- 建议：reserve+commit 合并为单次原子操作，或用单调递增计数器/UUID 而非 len()。

### [中] File 后端每次操作全量读写 JSON，且无跨操作一致性，性能与并发差
- 位置：`artifact_repository.py:280-290, 445-469`
- 问题：`FileArtifactRepositoryBackend` 每次 commit/list/detail 都 `_read_state` 全量反序列化整个状态文件，`commit_result` 全量 `_write_state`。版本量增大后每次提交 O(N) 读写并 fsync，且多进程无文件锁（仅进程内 `Lock`）。
- 证据：`_read_state`/`_write_state` 操作整份 state。
- 影响：版本增多后性能显著下降；多 worker 进程并发写会互相覆盖。
- 建议：改用增量/分片存储或数据库；多进程需 OS 级文件锁。

### [中] 内存后端 rollback 复制 source_record 时保留原 verification/preview，状态标 ROLLED_BACK 但内容未清理
- 位置：`artifact_repository.py:218-224, 403-409`
- 问题：`rollback_record = {**source_record, "version_id":..., "status":"ROLLED_BACK", ...}` 完整继承源版本的 artifact_preview/verification_status/runtime_trace，仅改状态与 id。回滚版本对外呈现为"已回滚"但携带原版本产物内容与通过状态，语义含糊。
- 证据：浅复制源记录。
- 影响：回滚版本可能被误当作有效产物检索/展示。
## artifact_skill_catalog.py

### [中] 技能输入 schema 声明 url required，但注册/编排层未强制校验，url 可缺失
- 位置：`artifact_skill_catalog.py:29-46, 74-79, 98-103`
- 问题：`bilibili_course_note_pdf`/`video_summary` 的 input_schema 标 `required:["url"]`，但该 schema 仅作展示元数据，仓库内未见对 task_input 实际执行 JSON Schema 校验（catalog 只提供 definition）。因此声明的必填约束不被强制。
- 证据：schema 生成后仅存于 definition，无校验调用。
- 影响：缺 url 的视频类技能任务可进入编排，直到运行期 provider 才失败。
- 建议：在编排入口按 skill input_schema 做校验，或明确 schema 仅为文档。

### [低] alias 与 action binding 未覆盖 catalog 全部键的一致性校验
- 位置：`artifact_skill_catalog.py:118-135`
- 问题：`_SKILL_ACTION_BINDINGS` 与 `_ARTIFACT_SKILLS` 手工同步；`course_notes` 与 `bilibili_course_note_pdf` 均绑定 `COURSE_NOTES`。新增技能若漏配 binding，`resolve_artifact_skill_action_key` 静默返回 ""，使 skill_key 无法绑定动作而回退 AUTO。
- 证据：`.get(canonical_skill_key, "")` 静默默认。
- 影响：新技能可能静默失去 skill→action 绑定。
- 建议：加载时断言两表键集一致。

## runtime_node_registry.py

### [中] 内置技能图/技能从不校验 runtime 节点是否受支持，仅自定义图校验
- 位置：`runtime_node_registry.py:28-47`；对照 `registry.py:1911-1922`（仅 `register/_load` 自定义图调用）
- 问题：`ensure_runtime_node_skill_registered` 只在自定义图注册/加载时调用。内置 SKILL_GRAPH_TEMPLATES 若引用了 `SUPPORTED_RUNTIME_NODE_SKILL_KEYS` 之外的 skill（例如新增内置技能忘了登记），运行到 `skill_graph._execute_skill` 才 `raise ValueError(unsupported skill execution)`，缺少启动期校验。
- 证据：内置图未过校验。
- 影响：内置图配置错误延迟到运行期暴露。
## skill_graph.py

### [高] execute_skill_graph 按 plan.node_sequence 线性执行，完全忽略图 edges 与拓扑顺序
- 位置：`skill_graph.py:33-41`
- 问题：运行时直接 `for node_plan in plan.node_sequence` 顺序执行，不读取 SkillGraphTemplate.edges，也不做拓扑排序。DAG 环检测只在 `registry._validate_skill_graph_template`（注册期）进行，运行期节点顺序完全取决于 `node_sequence` 的构造顺序。若 compiler 生成的 node_sequence 与 edges 的拓扑序不一致（如自定义图 edges 定义了非线性依赖），运行期会用错误顺序执行，前置节点输出未就绪。
- 证据：`execute_skill_graph` 无 edges 引用；节点间数据通过共享 `state` dict 传递，强依赖顺序。
- 影响：非线性/分支 DAG 或 node_sequence 排序错误时，节点读取到空 state 静默产出降级内容，而非报错。
- 建议：运行期按 edges 做拓扑排序执行并校验 node_sequence 与拓扑序一致。

### [中] state 通过共享 dict 传递、节点强顺序耦合，"局部修复只重跑单节点"的文档承诺无法在运行时实现
- 位置：`skill_graph.py:21-68`
- 问题：所有节点共享单一 `state`，输出以 `state.update` 累积。verifier/repair 作为图中固定节点顺序执行，无法对"仅失败节点"做隔离重跑（对照《受控式异步产物生成Agent编排升级设计》强调局部修复）。当前"repair"仅是流水线上又一个节点，并非按需重跑机制。
- 证据：线性 for 循环 + 全局 state。
- 影响：与设计文档"局部节点重跑、不重跑全链路"的表述存在偏差，实际是全链路顺序跑。
- 建议：明确文档与实现边界，或引入节点级缓存与选择性重执行。

### [中] _execute_skill 用 `state["prompt_recipe"]` 直接下标，若 recipe 缺失将 KeyError
- 位置：`skill_graph.py:178, 267, 388`
- 问题：多处 `state["prompt_recipe"].section_guidance` 直接下标访问。虽 `execute_skill_graph` 初始化时放入 prompt_recipe，但 `_node_guidance` 用 `.get` 防御，两处风格不一致；若未来 state 被节点覆盖（节点返回含 "prompt_recipe" 键的 dict 经 `state.update`）会破坏。
- 证据：下标 vs `.get` 混用。
- 影响：潜在 KeyError/AttributeError。
## registry.py

### [中] 所有 register_custom_* 存在 check-then-insert TOCTOU，并发注册同键后写者胜出
- 位置：`registry.py:1284-1290, 1388-1389`（action，其他 register_* 同样模式）
- 问题：先在锁内检查 `key in _custom_*`，随后释放锁做校验/构造，最后再取锁 `_custom_*[key]=obj` 无二次存在性检查。两个并发注册同一 key 会双双通过首检并都写入，后者覆盖前者（冲突检测失效）。
- 证据：插入处未在同一临界区内复查存在性。
- 影响：并发注册可绕过"冲突拒绝"，产生非预期覆盖。
- 建议：将存在性检查与插入放入同一锁临界区（check-and-set 原子化）。

### [中] _load_custom_artifact_config_store 清空与重填非原子，并发解析可读到空注册表
- 位置：`registry.py:1694-1769`
- 问题：加载时先分别对六类注册表 `clear()`，再逐条 `model_validate` 后插入。清空到重填之间若有并发 `resolve_*`/`list_*`，会读到空或半填充状态，且各类各自加锁，无全局一致视图。
- 证据：clear 与 populate 分处不同锁段。
- 影响：热加载配置期间任务解析可能报 unknown action/skill。
- 建议：构建完整新副本后原子替换，或加载期间加全局屏障。

### [低] 内置技能图未经 `_validate_skill_graph_template`，环/悬挂节点无启动期保障
- 位置：`registry.py:794-964, 1911-1942`
- 问题：DAG 校验（唯一 node_id、edges 引用存在节点、Kahn 拓扑判环）仅对自定义图执行。内置图靠人工正确性，无自动校验；新增内置图引入环或错误 node_id 不会在启动期被发现。
- 证据：`_validate_skill_graph_template` 仅由自定义注册/加载调用。
- 影响：内置图配置错误延迟暴露。
## custom_mcp_executor.py

### [高] 自定义 MCP 以注册的任意 launch_command + launch_args 启动子进程，构成远程代码执行面
- 位置：`custom_mcp_executor.py:93-131`
- 问题：`_call_custom_mcp_tool` 用 `subprocess.run([server.launch_command, *launch_args], cwd=working_directory, env={**os.environ, **server.launch_env})` 启动进程。若自定义 MCP 注册接口对最终用户/低权限方开放，攻击者可注册任意可执行程序、任意参数、任意工作目录与环境变量，实现任意命令执行。虽 `register_custom_mcp_server` 要求 server_id 以 `custom-` 开头，但对 launch_command 本身无白名单/沙箱限制。
- 证据：命令与参数完全来自注册数据，无允许列表校验。
- 影响：注册权限一旦失守即等于宿主 RCE；即使内部可信，也缺乏纵深防御。
- 建议：对 launch_command 施加白名单/签名校验、限定工作目录与 env 白名单、以受限用户/容器运行；明确注册接口的授权边界。

### [中] 子进程未捕获 TimeoutExpired，超时会向调用线程抛异常而非走 ACK-FAILED
- 位置：`custom_mcp_executor.py:122-131, 60-82`
- 问题：`subprocess.run(..., timeout=...)` 超时抛 `subprocess.TimeoutExpired`。在 `_run_custom_mcp_acquisition_operation` 中该异常被 `except Exception` 捕获并走 FAILED ACK（尚可）；但超时后子进程可能未被彻底终止/回收（run 会 kill 但其派生的孙进程如 yt-dlp/xelatex 可能残留），无显式进程组清理。
- 证据：无 `start_new_session`/进程组 kill。
- 影响：超时后孙进程（下载/转码）可能泄漏，累积占用资源。
- 建议：以新进程组启动并在超时后杀整组；记录超时事件。

### [中] stdout 逐行 json.loads 无异常保护，provider 打印非 JSON 行即抛
- 位置：`custom_mcp_executor.py:134-139`
- 问题：`responses.append(json.loads(stripped))` 对每个非空行解析。若 MCP 子进程在 stdout 混入任何非 JSON 日志行（很常见），`json.loads` 抛 JSONDecodeError 使整次调用失败。
- 证据：无 try/except，未区分 JSON-RPC 行与噪声。
- 影响：provider 稍有 stdout 噪声即导致能力调用失败，鲁棒性差。
### [中] _build_tool_arguments 直接透传 operation["tool_arguments"] 给子进程工具，无参数白名单
- 位置：`custom_mcp_executor.py:168-198`
- 问题：当 operation 含 `tool_arguments` dict 时原样 `dict(explicit_arguments)` 作为 tools/call 参数下发。若上游能被外部影响，可传入如 `cookies_file` 指向任意本地路径、`output_dir` 指向敏感目录，或 `input_path` 越权读取本地文件（bilibili server 会 `expanduser().resolve()` 后读/转码任意路径）。
- 证据：无参数键白名单或路径边界校验。
- 影响：结合 MCP server 的文件读写能力，可能造成任意本地文件读取/写入（LFI/路径穿越）。
- 建议：对下发参数做键白名单与路径根目录约束。

### [低] 无 tool_arguments 时对未知工具 raise，但 bilibili render_latex_pdf 用固定占位 sections
- 位置：`custom_mcp_executor.py:186-197`
- 问题：`render_latex_pdf` 分支在缺显式参数时用硬编码占位标题/正文，产出的是无意义的"Generated Artifact"文档，而非真实产物。
- 证据：固定 sections 占位。
- 影响：export 能力在默认参数路径下产出占位内容，易被误认为成功导出。
- 建议：缺必要参数时应报错而非产出占位。

## system_mcp_executor.py

### [高] recover 与 submit 存在竞态：检查 _executor_threads 后释放锁再启动，可能重复派发同一 request
- 位置：`system_mcp_executor.py:60-95, 74-76, 84-86`
- 问题：`recover_system_mcp_acquisition_operations` 先在锁内 `if request_id in _executor_threads: continue`，随后释放锁，再在另一锁段插入并 start。两次 recover 并发（或 recover 与 submit 并发）时，同一 request_id 可能通过检查后被两个线程分别派发，导致对同一操作重复执行并重复回调 ACK。
- 证据：检查与插入分处不同临界区，无占位。
- 影响：重复执行外部作业、重复 ACK，破坏幂等。
### [中] _send_host_ack 每次重试重新读取 token 但成功 ACK 与失败 ACK 均无幂等键，网络抖动致重复回调
- 位置：`system_mcp_executor.py:98-179`
- 问题：`_run_system_mcp_acquisition_operation` 成功后 `_send_host_ack(final_status=ACKNOWLEDGED)`；`_send_host_ack` 内部重试 3 次。若首次请求实际已被 Java 处理但响应丢失，重试会再次投递同一 callback_token 的 ACK。回调体无幂等序号，依赖 Java 侧按 callback_token 去重；worker 侧未保证 exactly-once。
- 证据：重试仅凭 callback_token，无 attempt/nonce。
- 影响：宿主若未严格幂等，会重复推进作业状态。
- 建议：ACK 携带幂等键并要求宿主按键去重；文档明确 at-least-once 语义。

### [中] wait_for_system_mcp_operation join 超时后不区分"仍在跑"与"已完成"，静默返回可能过期状态
- 位置：`system_mcp_executor.py:49-57`
- 问题：`thread.join(timeout=30)` 超时后不检查线程是否仍存活，直接 `peek_acquisition_operation` 返回当前（可能仍是 DISPATCHED）状态。调用方无法区分"操作真的完成"还是"等待超时"。
- 证据：join 后无 `is_alive()` 判定。
- 影响：轮询/等待接口可能返回中间态被误当终态。
- 建议：join 超时后显式标注 still_running。

### [低] recover 跳过条件用 callback_status/status 硬编码字符串集合，与 provider_job_status 状态机重复且易漂移
- 位置：`system_mcp_executor.py:72, 77, 89`
- 问题：恢复逻辑内联判断 `callback_status in {"ACKNOWLEDGED","FAILED"}`、`status.startswith("WAITING_FOR_")` 等，与 `provider_job_status.resolve_provider_job_status` 的状态映射各自维护。
- 证据：状态字符串多处硬编码。
- 影响：状态枚举演进时多处需同步。
## system_mcp_registry.py

### [中] system MCP 用 sys.executable 直接跑 server 脚本，依赖当前解释器已装 yt_dlp/faster_whisper
- 位置：`system_mcp_registry.py:20-24`
- 问题：`launch_command=sys.executable, launch_args=[server_script], launch_env={}`。系统 MCP 直接用 artifact-worker 进程自身的解释器执行，`launch_env={}` 未注入独立环境前缀。而 bilibili server 的转写/下载依赖（yt_dlp、faster_whisper、reportlab、xelatex）需在同一解释器可用，否则运行期报错。与自定义 blueprint（用独立 conda env + launcher）策略不一致。
- 证据：system server 与 blueprint 的启动方式差异明显。
- 影响：系统 MCP 能否工作强依赖 worker 环境是否装齐媒体依赖，部署脆弱。
- 建议：系统 MCP 也走独立环境/依赖校验，或启动前探测依赖并给出明确错误。

### [低] list_system_mcp_servers 每次调用重建对象并解析路径，且被 executor 频繁调用
- 位置：`system_mcp_registry.py:12-41`；调用方 `system_mcp_executor.py:35,105`
- 问题：每次 `resolve_system_mcp_server` 都 `list_system_mcp_servers()` 重新 `Path(__file__).resolve()` 并构造 pydantic 模型，属重复计算。
- 影响：轻微性能浪费。
- 建议：模块级缓存常量。

## provider_job_status.py

### [中] 无 capability_name 时把 PLANNED/PENDING_UPSTREAM 映射后其余一律 NOT_REQUIRED，掩盖失败态
- 位置：`provider_job_status.py:14-17`
- 问题：当 `capability_name` 为空时，只识别 PENDING_UPSTREAM/PLANNED，否则统一返回 NOT_REQUIRED。若无 capability 却传入了 operation_status=FAILED/COMPLETED（异常组合），会被错误归为 NOT_REQUIRED，丢失失败信号。
- 证据：early-return 分支未覆盖 FAILED/COMPLETED。
- 影响：边界输入下失败状态被吞没为"无需"。
- 建议：无 capability 时也应透传 FAILED/COMPLETED 或显式拒绝非法组合。

### [中] callback_status 与 operation_status 冲突时无一致性校验，callback 优先可能与实际不符
- 位置：`provider_job_status.py:19-46`
- 问题：函数无条件让 callback_status 优先于 operation_status（如 callback=DISPATCHED 但 operation 已 COMPLETED 时返回 DISPATCHED）。轮询期间两状态可能短暂不一致，返回滞后的 DISPATCHED 会让上层认为作业未完成。
- 证据：callback 分支全部在 operation 分支之前返回。
- 影响：作业已完成却被报告为进行中，轮询多转一轮（或误判）。
## mcp/bilibili_render_pdf_server.py

### [高] cookies_file/input_path/output_dir 等路径参数无根目录约束，存在任意文件读写/穿越
- 位置：`bilibili_render_pdf_server.py:208-213, 341-353, 379-384, 525-531`
- 问题：`cookies_file`、`input_path`、`output_dir`、`output_stem` 均来自 tool arguments，经 `expanduser().resolve()` 后直接用于读文件（cookies 传给 yt-dlp）、遍历转写（rglob 任意目录）、写 tex/pdf 到任意目录。无 allowlist 根目录限制。若调用方参数可被外部影响，可读取本机任意 cookies/文件或向任意路径写入。
- 证据：无 `is_relative_to(output_root)` 之类边界校验。
- 影响：路径穿越导致敏感文件读取或任意位置写入。
- 建议：将所有路径限制在 output_root/受控根目录内并拒绝越界；对 cookies_file 明确来源与权限。

### [高] video_url 送入 yt-dlp 子进程，正则可被绕过导致 SSRF/任意下载
- 位置：`bilibili_render_pdf_server.py:19-21, 196-216, 533-560, 604-621`
- 问题：`get_bilibili_subtitle` 用 `VIDEO_URL_PATTERN.match` 校验，但正则未锚定结尾（无 `$`），`^(https?://)?...bilibili.com/video/BV...` 之后允许任意后缀；`_fetch_video_metadata`/`_download_*` 又用 `normalized_url` 直接交给 yt-dlp。攻击者可构造 `bilibili.com/video/BVxxx@evil.com/...` 或利用 yt-dlp 的 URL 解析差异，触发对非预期主机的请求（SSRF/任意站点下载）。且 `normalized_url = url if startswith http else "https://"+url` 会给无协议输入强加 https，可能改变主机解析。
- 证据：正则无结尾锚点；URL 未再规范化即下发。
- 影响：SSRF、下载任意远程资源、绕过 bilibili 限定。
- 建议：正则加 `$` 锚定并对 host 做严格白名单校验，规范化后再校验 host。

### [中] xelatex 子进程与 yt-dlp/transcribe 子进程均无超时，MCP 内可无限挂起
- 位置：`bilibili_render_pdf_server.py:406-412, 857-864, 693`
- 问题：`_run_subprocess` 与 `_render_latex_pdf` 的 xelatex 调用均未设 `timeout`。yt-dlp 下载、whisper 转写、xelatex 编译若卡住会无限阻塞 MCP 子进程。虽调用方 custom_mcp_executor 对整个 MCP 进程设了 `mcp_process_timeout_seconds`，但系统 MCP 路径（system_mcp_executor 经 `_call_custom_mcp_tool`）同样受该超时，超时后内部 yt-dlp/xelatex 孙进程不被清理。
- 证据：这些 subprocess.run 无 timeout 且无进程组管理。
- 影响：挂起与孙进程泄漏。
### [中] _build_latex_document 用正则 re.sub 注入用户内容到模板 newcommand，转义不足可能破坏/注入 LaTeX
- 位置：`bilibili_render_pdf_server.py:442-484, 955-968`
- 问题：`_latex_escape` 覆盖常见特殊字符，但替换进 `\newcommand{\notetitle}{...}` 用 `re.sub(pattern, replacement, template, count=1)`，replacement 字符串中若 `_latex_escape` 输出含 `\g`、`\1` 等反向引用序列会被 re.sub 当作组引用处理（re.sub 的 replacement 对反斜杠有特殊语义）。而 `\textbackslash{}` 等转义结果本身含反斜杠，可能触发 `re.error` 或错误替换。
- 证据：转义后的文本直接作为 `re.sub` 的 replacement。
- 影响：含特殊字符的标题/频道名可能导致渲染异常或模板破坏。
- 建议：用 `re.sub(..., lambda m: replacement)` 或普通字符串替换，避免 replacement 反向引用解释。

### [中] 手写 PDF 回退（_render_portable_cjk_pdf）拼裸字节 PDF，长文本/大量页时 xref 偏移与对象编号易错
- 位置：`bilibili_render_pdf_server.py:1017-1087`
- 问题：手工构造 PDF 对象、页对象 id `5+index*2`/`6+index*2` 与 content id 交错，xref 偏移手算。逻辑复杂且无校验，页数多或内容异常时极易产出损坏 PDF；`page_lines` 每页固定 42 行、字符按定宽切分，对 CJK 混排会错位。
- 证据：纯手工字节拼装无断言。
- 影响：回退 PDF 可能损坏或排版错乱，被当作成功导出。
- 建议：仅保留 ReportLab 路径并将其列为部署硬依赖；手写回退作降级需加完整性校验。

### [低] render_latex_pdf 返回 pdf_status 恒为 "COMPILED"，即便走回退或编译失败
- 位置：`bilibili_render_pdf_server.py:401, 420-434`
- 问题：`pdf_status = "COMPILED"` 初始化后从不更新；即便 xelatex 失败走 portable 回退，返回值仍报 `pdf_status: COMPILED`，仅 `execution_mode` 区分。状态字段误导。
- 证据：pdf_status 无条件为 COMPILED。
- 影响：上层依据 pdf_status 判定成功会误判回退/失败为编译成功。
### [低] handle_line/handle_message 对 json.loads 失败无保护，畸形行使 serve_stdio 崩溃
- 位置：`bilibili_render_pdf_server.py:36-52`
- 问题：`serve_stdio` 循环内 `handle_line` 直接 `json.loads(line)`，非 JSON 行会抛 JSONDecodeError 未被捕获，使整个 stdio 服务循环崩溃退出（而 JSON-RPC 规范应返回 -32700 parse error）。
- 证据：`payload = json.loads(line)` 无 try/except。
- 影响：单条畸形输入即终止 MCP 会话。
- 建议：解析失败返回标准 parse error 响应并继续循环。

### [低] _run_transcription 优先用当前解释器内的 faster_whisper，忽略请求的 device，且 CPU 转写可能极慢无进度
- 位置：`bilibili_render_pdf_server.py:655-666, 714-744`
- 问题：`_run_bundled_faster_whisper` 使用传入 device（默认 cpu），大模型 CPU 转写耗时可能远超上层超时；无分段进度与超时保护。字幕回退路径固定 `device="cpu"`（line 313 调用处）。
- 证据：无时长/资源上限。
- 影响：长视频转写可能触发上层 MCP 超时，前功尽弃且资源浪费。
- 建议：限制音频时长/加超时与进度上报。

### [优化] system MCP 将 get_subtitle 重命名为 get_bilibili_subtitle 的映射散落多处
- 位置：`system_mcp_executor.py:107-108`、`capability_wait_queue.py:330-345`、`system_mcp_registry.py:33` vs `bilibili_render_pdf_server.py:90`
- 问题：注册表用 `get_subtitle`，实际 server 工具名是 `get_bilibili_subtitle`，多处硬编码转换（executor 改写、wait_queue `_to_public_tool_name`）。命名不统一，映射逻辑重复且易漏。
- 影响：维护成本、易出现某处未转换导致 unknown tool。
## 跨文件 / 补充

### [中] custom_mcp_executor 向子进程传入完整 os.environ，敏感环境变量泄漏给第三方 MCP
- 位置：`custom_mcp_executor.py:121`
- 问题：`env = {**os.environ, **server.launch_env}` 将 worker 进程全部环境变量（可能含 internal_auth_token、DB 凭据、云密钥等）继承给任意注册的自定义 MCP 子进程。
- 证据：全量继承 os.environ。
- 影响：第三方/用户注册的 MCP 可读取宿主全部机密。
- 建议：仅传递最小必要 env（白名单）+ launch_env，剥离敏感变量。

### [中] 与文档偏差：受控编排"局部修复/按需重跑"在运行时未实现，DAG 仅注册期校验
- 位置：`skill_graph.py:33-68`、`registry.py:1911-1942`；对照 `docs/受控式异步产物生成Agent编排升级设计.md`、`docs/产物生成Agent独立模块施工文档.md`
- 问题：设计文档强调 Schema-Gated Skill Graph Runtime 的图执行与局部修复；实现为线性 node_sequence 顺序执行 + 固定 verifier/repair 节点，edges 不参与运行时，修复非按需重跑。DAG 环检测仅覆盖自定义图注册，内置图与运行时均无校验。
- 影响：实现与文档描述的"图运行时"存在偏差，能力被弱化为流水线。
- 建议：补齐运行期拓扑执行与选择性重跑，或更新文档表述以匹配实现。

### [低] 测试缺口：并发/竞态、崩溃恢复、MCP 安全边界均缺覆盖
- 位置：`workers/artifact-worker/tests/*`（对照本审查各竞态/安全项）
- 问题：reserve/commit 版本竞态、wake_waiting 并发 claim 抛错、recover/submit 重复派发、approval request_id 覆盖、custom MCP 命令/路径/env 边界、bilibili URL 正则绕过、PDF 回退完整性等均无对应回归测试（现有测试多为 happy-path 与状态观察）。
- 影响：上述高危项无自动化防回归。
- 建议：补并发压测、崩溃恢复、恶意参数/URL 的安全用例。
