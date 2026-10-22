# OTH-13 / OTH-15 / OTH-16 独立静态核实报告

## 范围、方法与分类

- 核实基线：`70631bd015067b19f9d55aaec92d7429751dc936`；源码为本机只读快照，服务器开发目录为 `/data/workspace/noteweave-oth-20261023`，未在发布目录操作。
- 已完整读取 [AGENTS](<AGENTS.md>)、[非 research 执行提示词](<execution.txt>)、[其他线路说明](<other-track.txt>)、[任务总账](<ledger.json>)、[HANDOFF 第12—15节](<HANDOFF.md#L570-L664>)，并读取 [research 职责边界](<research-boundaries.txt#L27-L46>)。
- 本轮仅 glob/grep/read 静态追踪，以及 WSL→SSH `git show` 获取历史差异；未改产品代码、未运行安装/构建/测试、未启动浏览器、未访问业务数据、未调用付费模型、未部署、未创建下级 agent。
- 历史原文已落本地并逐行读取：[baf8bcc diff](<history-baf8bcc.diff>)、[7cc6696 diff](<history-7cc6696.diff>)。父 agent 的 [祖先核对记录](<history-evidence.txt#L1-L37>)显示两修复与前置 `43d638a` 均为基线祖先。

| 工作包 | 主分类 | 本轮结论 |
|---|---|---|
| OTH-13 | 已确认需要修复 | pnpm 固定版本、冻结锁安装已具备；显式 Node 类型依赖仍缺。补齐依赖后仍须集中干净构建验收，不能引用历史成功关闭。 |
| OTH-15 | 仅需验收 | 历史健康/登录/工作区 API 成功仅证明可达性；完整资料、QA、Note、Wiki、记忆反馈和 artifact 流程未验收。现有用例不是可直接全跑的安全白名单。 |
| OTH-16 | 仅需验收；关联剩余问题回填既有编号 | 两个窄修复已修复并有代码证据，独立静态复审完成；真实并发、完整重驱和模型安全质量不能宣布通过。解析残留归 OTH-03，事务/冲突/重驱归 OTH-06，来源幂等归 OTH-07，入队契约协同 OTH-11。 |

## OTH-13：依赖、锁与构建

### 当前静态事实

1. [package.json:5–15](<../../../../../frontend/package.json#L5-L15>)已有 `packageManager: pnpm@11.7.0`，`build` 明确是 `tsc -b && vite build`；不是只有打包而没有类型检查。
2. [Dockerfile:1–11](<../../../../../frontend/Dockerfile#L1-L11>)使用 Node22 Alpine、Corepack 激活 pnpm11.7.0，复制 manifest/lock/workspace 配置，`pnpm install --frozen-lockfile` 后 `pnpm build`。未发现需要重复修复的 pnpm 固定版本问题。Node/基础镜像仅固定 major/tag，未固定 digest，不据此新建缺陷。
3. [CI:24–42](<../../../../../.github/workflows/ci.yml#L24-L42>)同为 pnpm11.7.0、Node22、冻结锁安装，并运行 test/build/ui:check。[CI:3–6](<../../../../../.github/workflows/ci.yml#L3-L6>)触发为 pull_request 与 main push，开发分支存在不等于对应 CI 已跑。
4. 路径检索仅发现 [pnpm-lock.yaml](<../../../../../frontend/pnpm-lock.yaml>)，无 npm/yarn 锁；[锁文件:1–53](<../../../../../frontend/pnpm-lock.yaml#L1-L53>)为 v9、autoInstallPeers=true，直接依赖 specifier 与 manifest 一致。react 锁19.2.7、vite锁5.4.11、typescript锁5.6.3；不能在验收时临时换 npm 再称锁定依赖复现。
5. [package.json:27–34](<../../../../../frontend/package.json#L27-L34>)与[锁 importer:35–53](<../../../../../frontend/pnpm-lock.yaml#L35-L53>)均无直接 `@types/node`。锁中已有间接/peer `@types/node@26.1.1`，不能说完全未安装 Node 类型，但也不能据此证明干净项目可正确解析直接使用的 Node API。[vite.config.ts:4](<../../../../../frontend/vite.config.ts#L4>)直接导入 `node:url`，[tsconfig.node.json:1–9](<../../../../../frontend/tsconfig.node.json#L1-L9>)纳入该配置；测试与 e2e 还直接使用 node:crypto/fs/path。
6. [pnpm-workspace.yaml:1–2](<../../../../../frontend/pnpm-workspace.yaml#L1-L2>)显式允许 esbuild 构建，干净安装时不能遗漏这个文件。

### 历史通过证据的强度

- [HANDOFF:301–310](<HANDOFF.md#L301-L310>)记录旧前端79文件321例通过，但该次使用临时 npm lock、npm ci，发生 react/vite 版本漂移；该次 `tsc -b` 的13错不代表当前基线仍必然失败。
- [HANDOFF:582、590–595](<HANDOFF.md#L582-L595>)记录整合提交 `083b299f` 前端 TypeScript 与生产构建成功。它是历史摘要，不含本轮干净依赖目录、实际命令版本、完整日志或锁摘要。
- [总账:434–439](<ledger.json#L434-L439>)明确历史通过不能替代干净环境复现；本地快照检索未发现 `.log` 原文，未声称读取不存在的构建收据。
- [构建路径历史日志](<history-build-log.txt>)证明相关文件存在历史提交，不是 CI 运行成功证据。

### 最小修复与集中验收建议

- 单一负责人修改 manifest 与锁：显式增加与目标 Node22 兼容的 `@types/node`，用仓库指定 pnpm11.7.0 同步 importer/依赖图。保留 frozen-lockfile、build 与 ui:check，不用全局安装或本机 node_modules 隐式补足。
- 集成冻结后，在独立空目录/容器记录 commit、Node/pnpm 版本、lock SHA-256；无旧 node_modules/构建产物，先冻结锁安装，再类型检查与生产构建；确认安装未改锁。必要前端单测和 UI 契约随后集中执行。
- Docker路径与CI路径都需要验证有效环境；网络/registry/Corepack下载不可达属于具体环境阻塞，不以绕过锁解决。此报告没有执行这些命令，也未验证 pnpm/Node22 的实际安装启动兼容性。

## OTH-15：现有流程覆盖、风险与隔离

### 历史边界

- [HANDOFF:594–595](<HANDOFF.md#L594-L595>)只记录部署后的健康、登录、认证工作区API、公开注册403与前端入口200。
- [HANDOFF:650–660](<HANDOFF.md#L650-L660>)记录配置迁移后的相同健康检查；用户/工作区/资料计数由3/1/0变3/2/1，不得为对齐旧计数删后续数据。此历史检查没有覆盖完整浏览器流程或真实模型质量。
- [HANDOFF:312–319](<HANDOFF.md#L312-L319>)旧 e2e 仅收集22例/9文件，实际未执行。快照现在有9个 spec 与1个 helper，未在本轮执行收集，不能把22宣称为本轮用例数。

### 配置和现有非 research 场景

[Playwright配置:5–29](<../../../../../frontend/playwright.config.ts#L5-L29>)单worker、不并行、不重试；默认目标 `127.0.0.1:3000`，环境可覆盖，浏览器可指定路径/channel；没有 webServer 自动起隔离环境，没有生产域名拒绝或非 research project；失败 trace/截图/video 会保存敏感页面内容。

| 场景 | 现有代码与可证明范围 | 缺口/运行风险 |
|---|---|---|
| 资料上传 | [demo-real:119–160](<../../../../../frontend/e2e/demo-real.spec.ts#L119-L160>)上传MD/PDF并等可检索，最后删除本次文件 | 真实写入与删除；依赖解析/切片/向量/索引，不是无副作用 UI 检查 |
| QA检索与范围 | [qa-real:15–60](<../../../../../frontend/e2e/qa-real-retrieval-regression.spec.ts#L15-L60>)固定资料问答、引用与连续切换范围 | 会真实上传缺失夹具并发问；依赖模型和索引 |
| Note/Wiki基础入口 | [demo-real:163–172、320–338](<../../../../../frontend/e2e/demo-real.spec.ts#L163-L172>)、[发送辅助](<../../../../../frontend/e2e/demo-real.spec.ts#L320-L338>)只要求200/最终非空文本 | Note没有内容质量/范围/持久化/精确降级断言，错误文字也可能满足非空 |
| Wiki实质回答 | [wiki-model:6–62](<../../../../../frontend/e2e/wiki-model-regression.spec.ts#L6-L62>)预置双向页、事实/关系卡、刷新恢复 | 真写Wiki且真模型；空库时 [demo-real:180–200](<../../../../../frontend/e2e/demo-real.spec.ts#L180-L200>)所谓创建用例只检查空态，未创建第一页 |
| 记忆 | [demo-real:203–212](<../../../../../frontend/e2e/demo-real.spec.ts#L203-L212>)刷新GET与标题/无错误 | 没有点赞点踩、改评、跟进反馈、冲突重试、候选审核或持久化断言，不能算完整记忆反馈验收 |
| artifact任务 | [demo-real:229–279](<../../../../../frontend/e2e/demo-real.spec.ts#L229-L279>)接受ready/failed/cancelled或provider_wait | 证明终态/等待可见，不证明成功产物质量 |
| artifact下载 | [artifact-browser-save:14–64](<../../../../../frontend/e2e/artifact-browser-save.spec.ts#L14-L64>)下载扩展名、字节非空 | 无现成ready产物会自动真实生成；非空不验证文档内容正确性 |
| 桌面/响应式 | [desktop-user-flows:13–119](<../../../../../frontend/e2e/desktop-user-flows.spec.ts#L13-L119>)登录表单、对话框、主题、表单前置条件 | 同文件混合Research界面，部分helper自动建工作台/资料；不能按文件名认定只读 |

### 不可直接全跑的理由

- [helpers:39–52](<../../../../../frontend/e2e/helpers.ts#L39-L52>)凭据会从显式E2E变量回退到bootstrap变量及父目录环境文件。必须显式注入专用测试账号，阻止误用已有业务凭据，不在报告/日志输出值。
- [helpers:175–213](<../../../../../frontend/e2e/helpers.ts#L175-L213>)按固定名称复用常驻工作台，资料缺失时补上传；[helpers:239–279](<../../../../../frontend/e2e/helpers.ts#L239-L279>)会补建Wiki；名称相同不代表隔离。
- [artifact-browser-save:23–26](<../../../../../frontend/e2e/artifact-browser-save.spec.ts#L23-L26>)没有产物就生成；禁用research用例并不能阻止非research付费模型调用。
- [package.json:11–12](<../../../../../frontend/package.json#L11-L12>)默认e2e运行全目录，包含research任务用例。需精确用例白名单；混合的demo/desktop/visual用例应按test标题筛选或后续拆分，不能仅排除research命名文件后全跑。

### 集中验证前置条件

1. 等主 agent 集成冻结，记录代码、迁移、功能开关及镜像身份；与实施阶段共享环境错开。
2. 准备独立 Compose project、数据库、Redis、Kafka topic/group、ES物理索引、MinIO bucket/数据卷和端口，严禁复用线上地址与卷；测试专用账号/工作台/固定夹具。保留原生产运行入口不改。
3. 无付费授权时先用可控替身/关闭provider验证错误及降级；隔离环境可参考 [CI:89–124](<../../../../../.github/workflows/ci.yml#L89-L124>)一次性runner环境的provider-disabled smoke设计，但不要在服务器发布栈照抄 up/down -v。现有这项检查只有失败关闭范围，不能代替完整成功流程。
4. 成功模型流程单独列预算、授权与预期，未授权不执行；provider-disabled分支与真实成功分支分别记结果。ES故障只对隔离依赖注入，不能停线上ES。
5. 准备与锁一致的Playwright浏览器和系统依赖，显式baseURL及受限输出目录。报告/trace脱敏，并核对下载结果不含真实用户内容。
6. 非research白名单包含：MD/PDF→可读/可检索；QA引用与范围切换；Note内容/刷新与降级；Wiki页面/关系/引用；点赞点踩改评与跟进反馈重驱；artifact生成、编辑/反馈、下载。现有缺失断言须补或人工有记录验收，不把刷新页当反馈验证。
7. 重驱/并发/降级依赖 OTH-02/03/06/07/08–12 的集成结果。旧11项Note/Wiki失败保留单列，未经重跑不定性为本轮回归或已修。
8. 不触发RES-07、不扩展research质量任务；线上写入或模型调用不足授权的具体子项单独等待，不阻塞隔离静态/替身验证。

## OTH-16：独立审查两项 d10 修复

### A. `baf8bcc`：删除 INSTRUCTION_LIKE

**结论：窄修复已修复并有证据；安全质量仅需验收，残留归既有OTH。**

- [历史diff:13–85](<history-baf8bcc.diff#L13-L85>)只移除Pattern导入、INSTRUCTION_LIKE及statement/value调用与帮助方法，更新说明；没有删除围栏或槽位白名单。
- 当前[抽取:99–105](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L99-L105>)保留数据指令隔离提示；[拼接:563–568](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L563-L568>)两段都围栏；[MemoryPromptFence:23–48](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryPromptFence.java#L23-L48>)转义大小写/空白闭合标签；[slot:440–449](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L440-L449>)校验允许槽位。全Java搜索无INSTRUCTION_LIKE/looksLikeInstruction残留。
- [安全测试:134–185](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryExtractionPromptSecurityTest.java#L134-L185>)确实断言合法槽位下敏感措辞保留且不因关键词重试；[测试:36–132](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryExtractionPromptSecurityTest.java#L36-L132>)覆盖围栏、闭合转义、非法槽位和同批非法项。这支持不按词表误杀的产品目标，不应重新加词表。
- 这些测试的[客户端:201–235](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryExtractionPromptSecurityTest.java#L201-L235>)返回预置模型结果、无Spring上下文，不能证明真实模型抵抗注入、事实不进记忆或下游提示质量。围栏/白名单限制结构，不能从合法槽位推断内容安全。

**解析边界残留 → OTH-03**：当前[parse:395–415](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L395-L415>)截首`[`至末`]`，接受带前后额外文本的JSON片段；第3个合法项后立即break，第4个非法项不校验，和任一非法整批作废的宣称不一致。这些未由本次删除引入，仍应归现有解析边界工作包，补测试后统一处理。缺失slot按category默认、polarity/category/explicit等宽松转换也需要由OTH-03明确兼容边界，不擅自收紧所有字段。

**残留category词表 → OTH-01**：[MemorySlots:30–62](<../../../../../backend/src/main/java/com/noteweave/memory/MemorySlots.java#L30-L62>)仍有结构/术语/否定/交互类别词表，不能把去INSTRUCTION_LIKE描述为全部业务规则已去词表。[候选服务:52–66](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryCandidateService.java#L52-L66>)把categoryOf用于候选门控，[Gate:30–41](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryCandidateGate.java#L30-L41>)类别影响风险分；[冲突读回:115–125](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConflictService.java#L115-L125>)也使用它。需按OTH-01复现真实误分类影响，不能仅因存在词表删除规则。

### B. `7cc6696`：跟进反馈冲突可重试

**结论：三个乐观冲突和null结果映射已修复并有代码证据；不能升级为完整并发/恢复验收通过。**

- [历史diff:54–110](<history-7cc6696.diff#L54-L110>)把上一回答变化、usage快照变化、目标集变化和null结果改RETRYABLE，日志仅稳定原因码/workspace/message id。当前[反馈服务:181–191、239–284](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L181-L284>)保持该行为；已经存在sourceRef仍SKIPPED，避免把重复投递当冲突。
- [抽取服务:236–250](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L236-L250>)已有信号但未完成任务走补反馈；[334–342](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L334-L342>)RETRYABLE走markTaskForRetry；[604–609](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L604-L609>)传`failTask(..., true)`。不能再把R5返回SKIPPED登记为未开发。

### 真实事务边界，不只看注解

1. 当前生产调用是 [KafkaTaskConsumer:302–327](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaTaskConsumer.java#L302-L327>)→[extract:218–224](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L218-L224>)→followUp。消费者及handle无@Transactional；[Kafka工厂:197–244](<../../../../../backend/src/main/java/com/noteweave/infra/KafkaConfig.java#L197-L244>)未配置数据库/Kafka事务管理器。
2. [persistExtraction:272–288](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L272-L288>)先followUp，再打开持久化TransactionTemplate；反馈[plan:195–230](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L195-L230>)先无锁readUsages和模型，再[186](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L186>)短事务apply。当前已知路径没有外层事务，因此默认REQUIRED本身不能判为缺陷，也不能机械改REQUIRES_NEW。
3. [反馈服务:76](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L76>)及[抽取服务:157](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L157>)模板均默认REQUIRED；未来/直接事务调用会加入外层，模型仍可能处在调用者事务中，需代理集成测试钉住边界。
4. OTH-02真实旧问题仍在：[ChatController:151–158](<../../../../../backend/src/main/java/com/noteweave/chat/ChatController.java#L151-L158>)调用Spring反馈服务，[@Transactional rating:80–97](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L80-L97>)先requireAnswerMessageId；[596–618](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L596-L618>)已取得answer_run FOR UPDATE，再judge(reason)。7cc6696没有修这条路径。

### 剩余风险和映射

| 已有编号 | 独立静态证据与结论 | 必需验证 |
|---|---|---|
| OTH-06 | [usage比较:248–256](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L248-L256>)比较整组UsageRow，任何无关行变化也整批RETRYABLE。快照字段[637–665](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L637-L665>)只有usage中的revision、outcome_type、slot等，并非完整业务版本/反馈事件版本；同outcome不同反馈、当前revision变化和删除竞态尚未证实安全。 | 两连接并发、相关/无关usage变化、上一回答删除/变化、版本变更、死锁/回滚；明确定义哪些变化应影响本次判定。 |
| OTH-06，协同OTH-11 | RETRYABLE只是任务事件标记。[TaskService:347–358](<../../../../../backend/src/main/java/com/noteweave/task/TaskService.java#L347-L358>)写FAILED与retryable事件，不入队；Kafka正常返回会确认消费。[Outbox:115–132](<../../../../../backend/src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java#L115-L132>)投递成功进入SENT，而[185–205](<../../../../../backend/src/main/java/com/noteweave/infra/TaskOutboxDispatcherService.java#L185-L205>)重驱仅接受DEAD_LETTER。当前已查入口没有正常消费后FAILED任务的完整重投闭环，不能只因有redriveTask就称恢复完成。 | 查清可达重驱入口、限次/退避及重新发布；真实任务状态+Outbox+消费者集成，不用手工更新RUNNING代替。 |
| OTH-06 | [persist:301–303](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryConversationExtractionService.java#L301-L303>)二次alreadyExtracted命中直接completeTask，丢弃本轮followUp RETRYABLE。并发另一投递先写信号时可能把未完成反馈结束；属于静态竞态推演，未运行复现。 | 两投递在外层首次检查与persist重查之间交错；断言完成状态不吞未完成反馈。 |
| OTH-07 | [sourceExists:901–906](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L901-L906>)只是count；[V163:41–48](<../../../../../backend/src/main/resources/db/migration/V163__memory_application_outcome.sql#L41-L48>)仅普通索引，检索迁移未见额外唯一键。[applyFollowUp:240–265](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryApplicationFeedbackService.java#L240-L265>)在取usage锁前查source，锁后未重新认领来源；尤其targets空只写回答级事件，不改变usage，后来的同source并发可再次写。 | MySQL真实并发同source/空targets、不同source合法反馈；唯一性必须允许一个source对多usage多行，不能直接唯一(workspace,source_ref)。 |
| OTH-03 | [judge:102–123](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryFeedbackAspectJudge.java#L102-L123>)仍裸拼用户文本；[134–141](<../../../../../backend/src/main/java/com/noteweave/memory/MemoryFeedbackAspectJudge.java#L134-L141>)截首尾大括号。异常日志局部已限原因码/类型，不能说这类都未修。 | 围栏和严格输出边界；保留feedback分类语义；针对测试。 |

### 现有测试可证明什么

- [FollowUpTransactionTest:72–123](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackFollowUpTransactionTest.java#L72-L123>)使用H2和真实DataSourceTransactionManager，不是纯mock事务，可验证没有外层事务时模板会写入；但服务是直接new，无Spring代理、真实MySQL或Kafka。
- [133–160](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackFollowUpTransactionTest.java#L133-L160>)在模型stub的同线程onCall内执行更新/插入，证明快照差异分支，不证明并发锁顺序与隔离级别。
- [169–201](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryApplicationFeedbackFollowUpTransactionTest.java#L169-L201>)mock TaskService并手工更新RUNNING，再直接persistExtraction；所谓重驱端到端只串了服务分支，没有真实恢复发布。
- [FeedbackRetryTest:49–129](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryConversationExtractionFeedbackRetryTest.java#L49-L129>)覆盖已有信号补反馈、重复已完成任务不重抽；[136–187](<../../../../../backend/src/test/java/com/noteweave/memory/MemoryConversationExtractionFeedbackRetryTest.java#L136-L187>)数据库/反馈/事务/状态均mock，仍非并发集成。
- 本次检索相关FollowUp测试未见CountDownLatch/ExecutorService/线程并发、三原因码逐项、NO_APPLY_RESULT或业务回滚断言。不得把现有委托rollback实现当成已有回滚场景测试。
- [历史HANDOFF:204–205](<HANDOFF.md#L204-L205>)记d10a/d10b及全量1945结果；本地没有原始日志，且未重跑。仅作为历史摘要，不凭原作者报告宣布此次独立复审测试通过。

## 缺失材料与不可关闭事项

- 本地快照glob未找到d10原审查、G2原文或测试日志。当前可读只有 [HANDOFF旧列表:420](<HANDOFF.md#L420>)、[其他线路说明:165–171](<other-track.txt#L165-L171>)、[总账:397–428](<ledger.json#L397-L428>)。19个G2编号缺问题描述、触发条件、文件定位与验收标准，统一分类缺少资料待核实，不猜实现、不新增计数。
- 不能证明：真实模型抗注入/长期记忆质量、MySQL双线程下来源幂等/锁顺序/冲突粒度、正常消费后RETRYABLE的完整恢复、当前基线干净构建、完整非research浏览器成功流程。
- 独立复审者不实现对应memory修复；OTH-13另派实现人，后续可由本审查者复核manifest/lock/CI变更与干净构建收据。
- 本报告发现的剩余问题全部映射到已有OTH-01/02/03/06/07/11/13/15，OTH-16承担独立验证，不额外统计为新工作包。没有扩展RES或ARC任务。
