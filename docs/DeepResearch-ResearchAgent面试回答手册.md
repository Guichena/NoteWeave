# Deep Research Research Agent 面试回答手册

> **面试口径冻结（M0，2026-07-11）**：M1/M2 完成前，只能把本项目表述为 verification-oriented Deep Research prototype。下文关于 `Table-as-State` 真源、独立 `Dual Verifier`、可裁决反证分支、citation accuracy 和 crash-safe resume 的完成态回答属于目标讲法，当前不得直接照读为已实现事实。安全口径见 `ResearchAgent-Capability-Coverage.md`。

## 1. 这份文档怎么用

这份文档是面试手册，不是架构说明书。

它的目标只有一个：

让你围绕 `Research Agent` 的简历亮点，能按真实面试顺序稳定回答：

1. 开场怎么讲
2. 核心亮点怎么讲
3. 技术细节怎么讲
4. trade-off 怎么讲
5. 简历逐条追问怎么答
6. 压力面怎么扛

推荐复习顺序：

1. 先看 `2. 开场回答`
2. 再看 `3. 简历亮点的标准讲法`
3. 再看 `4-8` 的深挖内容
4. 最后看 `9-10` 的拷打题和万能模板

---

## 2. 开场回答

### 2.1 一分钟版本

`我做的是一个面向复杂开放式研究任务的 Deep Research Agent。它不是普通问答，也不是简单 RAG，而是把研究、读取、验证和修正组织成一个验证驱动的闭环。核心上我设计了 Research Harness 来组织主链，用 Table-as-State 管理研究状态，用 Dual Verifier 控制什么时候继续补证、什么时候允许成文，再通过反证分支处理冲突证据。最后交付的不是一段答案，而是一份带来源基础、可导出、可回流工作台的正式研究报告。`

### 2.2 三分钟版本

`这个项目最初针对的是复杂开放式研究任务里的三个典型问题。第一，任务一长，状态很容易漂移，模型会忘掉已经验证过什么；第二，路径很容易跑偏，搜着搜着就偏离原始研究目标；第三，结果很难验证，经常变成模型写了一篇看起来像报告的长文，但其实来源基础不牢。`

`如果再往面试官会关心的设计视角说，这类任务还天然同时需要“宽度”和“深度”。宽度指的是不能只押一条 query 和一类来源，要能覆盖候选路径；深度指的是不能只搜到就写，而要把证据继续读透、抽取、验证并受控收口。我们这套设计本质上就是在解决复杂研究任务里怎么同时把宽度做出来、又把深度做扎实。`

`所以我没有把它做成“搜索增强问答”，而是做成一个验证驱动的 Closed-Loop Research。具体来说，我先用 Research Harness 把 Planner、Search、Fetch、Read、Extract、Verify、Branch、Report 组织成一个固定主链；再把研究状态从 prompt 和记忆里迁到结构化表状态，也就是 Table-as-State；然后用本地 verifier 和全局 verifier 组成 Dual Verifier，决定是继续补证、开反证分支，还是允许带护栏收口。`

`这样做的结果是，系统最终交付的不只是答案，而是 report_structure、final_report_markdown、citations、process、audit 这些正式成果，并且能导出成 md 文件，也能写回工作台资料池继续复用。`

---

## 3. 简历亮点的标准讲法

我们的简历亮点主线可以统一成这一句：

`设计并实现验证驱动的 Deep Research 智能体，围绕复杂开放式研究任务中的状态漂移、路径跑偏和结果难验证问题，同时解决研究宽度不足和研究深度不稳的问题，提出 Research Harness + Closed-Loop Research + Table-as-State + Dual Verifier + 反证分支 的受控研究闭环；系统支持外部搜索/抓取/读取、正式研究报告生成、来源回流资料池，以及 process / audit 双层可展示工作台交付。`

面试里不要一次性把整句背完，而是拆成下面几块去讲。

### 3.1 为什么不是普通 RAG，而是 Deep Research

推荐回答：

`普通 RAG 更适合收敛型问题，也就是给定知识库、答案相对明确的问题。它的优点是简单、便宜、路径短。`

`但我们的目标是复杂开放式研究任务，这类任务的难点不是单次 retrieve，而是状态会漂、路径会偏、证据会冲突。普通 RAG 在这个场景下最大的问题是 retrieve 完往往就直接 generate，中间没有很强的验证和纠偏机制。`

`如果面试官继续往深了追，我会再补一句：这个项目真正难的不是“能不能拿到资料”，而是拿到很多资料以后，系统还能不能知道自己已经验证到哪、是不是走偏了、现在有没有资格写进正式报告。换句话说，我们在解决的是研究控制问题，而不只是检索问题。`

`如果面试官开始往宽了扩，我会再补一句：这也是为什么我们的交付不只是一个回答，而是报告、来源、process、audit 和 save as source 的完整闭环。因为一旦把它当成真正的研究功能，而不是一个问答按钮，产品边界和交付形态都会跟普通 RAG 很不一样。`

`所以我们最后没有做成普通 RAG，而是做成验证驱动的研究闭环。`

一句总结：

`RAG 更适合找答案，Deep Research 更适合做研究。`

### 3.2 Research Harness

推荐回答：

`Research Harness 是整个系统的研究组织器，不是一个简单的 trace 容器。它负责把规划、搜索、抓取、读取、抽取、验证、纠偏和写报告组织成固定主链，同时产出 audit summary、toolbox summary 和 control state。`

为什么这么选：

`我调研时也考虑过更轻的纯工具编排方式，它的优点是实现快，但长任务里状态很容易散。Research Harness 的代价是工程复杂度更高，但换来的是主链稳定、运行态统一、前后端都更容易消费。`

`如果继续追问 Harness 到底值不值得，我会讲得更工程化一点：没有 Harness，你也能把工具调起来，但很难把 planning、tool run、checkpoint、recovery、report assembly 和前端展示挂到同一个 run 视角上。Harness 的本质价值不是“更优雅”，而是让系统第一次具备了统一控制面。`

`如果面试官从宽度来扩，我也会补一句：Harness 不只影响 Agent 内部，还影响 backend 契约、前端详情展示、导出报告、保存资料这些外围能力。也就是说它不是单纯的推理组织器，而是整个 Deep Research 功能的运行骨架。`

一句总结：

`Harness 解决的是“主链怎么不散”。`

### 3.3 Closed-Loop Research

推荐回答：

`Closed-Loop Research 的意思不是多跑几轮，而是让研究、读取、验证、修正形成因果闭环。系统不是 search 完就写，而是要根据 verifier 的结果决定下一步是 READ_MORE、EXTRACT_AGAIN、COUNTERFACTUAL_RECHECK，还是允许 SYNTHESIZE_REPORT。`

为什么这么选：

`我也考虑过更轻的“边想边调工具”和“先规划后执行”方案。它们的优点是简单、自然、落地快，但在开放式研究里，状态还是容易漂，而且静态计划很容易中途失效。最后我保留了 planning 和分步推进，但把真正的控制权交给了 verifier 和显式状态。`

`如果继续往深了追，我会强调 Closed-Loop Research 最关键的不是“多轮”，而是“验证结果能不能真的改写下一步动作”。很多系统也会多轮调用工具，但如果验证失败以后只是再试一次，而不是明确变成 READ_MORE、EXTRACT_AGAIN、COUNTERFACTUAL_RECHECK 这种受控动作，那它的闭环还是不够硬。`

一句总结：

`我们不是不要 plan，而是不相信开放式研究能靠一次计划走到底。`

### 3.4 Table-as-State

推荐回答：

`Table-as-State 是这个项目里最关键的一层。系统没有把研究状态放在 prompt 和记忆里，而是显式沉淀成 row、cell、requirement progress、verifier decision 这些结构化对象。这样系统明确知道自己验证到了什么、还缺什么、哪里冲突，而不是靠模型“好像记得”。`

为什么这么选：

`我调研时也考虑过 summary memory、纯 JSON 对象和图结构。summary memory 轻，但不适合程序化控制；JSON 适合承载对象，但不天然适合做 finding / requirement 聚合；图结构关系表达强，但对当前阶段的 verifier / recovery / report 控制来说不如表直接。最后选择 table，是因为它最适合做状态控制。`

`如果面试官继续深问“为什么 table 是你的最大亮点”，我会讲得再具体一点：因为 table 不是为了存数据，而是为了把系统最难的四件事都挂到一个真源上，分别是 coverage、conflict、requirement progress 和 write eligibility。只要这四件事还靠 prompt 和记忆，研究任务就不可能真正稳定。`

`如果面试官往宽了扩，我也会补一句：table 的价值不只体现在 verifier，它还决定了 checkpoint 怎么存、resume 怎么接、report 怎么写、前端 audit 怎么讲，所以它不是一个孤立的数据结构设计，而是整个系统的状态中枢。`

一句总结：

`JSON 更适合承载，图更适合关系，table 更适合研究状态控制。`

### 3.5 Dual Verifier

推荐回答：

`Dual Verifier 分本地和全局两层。Local Verifier 看当前这一轮的 evidence 和 ledger 是否站得住，Global Verifier 看整个 run 现在能不能收口。`

为什么这么选：

`单 verifier 更轻，但容易把“局部证据够不够”和“全局任务能不能结束”混在一起。最后我拆成两层，代价是多了一层复杂度，但收口会稳很多。`

`如果继续深问，我会补一层 trade-off：单 verifier 最大的问题不是精度不够，而是职责混杂。它既想当证据检查器，又想当任务裁判，最后很容易把局部瑕疵和全局是否可交付混成一个分数。拆成两层以后，系统才有能力一边承认局部不足，一边在全局上做受控收口。`

一句总结：

`单 verifier 更轻，双 verifier 更稳；我们当前更需要后者。`

### 3.6 反证分支

推荐回答：

`反证分支不是为了让系统看起来更复杂，而是专门处理冲突证据和路径纠偏的。遇到 conflict finding 或明显偏航风险时，系统不会继续沿着原路径写，而是围绕 conflicted row 和 recovery target 做 bounded counterfactual recheck。`

为什么这么选：

`我也考虑过更大的分支树搜索，它探索更强，但成本和复杂度会迅速上升。我们最后保留的是“必要时挑战当前结论”的思想，而不是做无边界分支搜索。`

`如果面试官继续往深了追，我会强调反证分支解决的不是“探索不够多”，而是“过早自信”。很多研究系统不是找不到资料，而是太早相信第一条路径。反证分支的意义就在于，系统一旦发现冲突，会被迫从“继续支持当前答案”切换到“主动挑战当前答案”。`

一句总结：

`ToT 更像扩搜索树，我们更像定向纠偏。`

### 3.7 外部搜索 / 抓取 / 读取

推荐回答：

`我们没有把网页能力做成一个大而全的工具，而是拆成 Search / Fetch / Read 三层。Search 解决去哪找，Fetch 解决怎么稳定拿，Read 解决拿到内容后围绕什么目标去读。`

为什么这么选：

`单体工具更快，但做不了细粒度 query budget、fetch fallback、read focus 和过程展示。我们最后选分层工具，是因为研究系统更需要可控和可解释。`

`如果面试官往宽了扩，我会把它再挂回产品展示：Search / Fetch / Read 拆层以后，前端 process 才能真实展示“先搜到了什么，再抓到了什么，再围绕什么焦点去读”，否则展示层只能看到一堆网页工具黑盒输出。`

### 3.8 报告生成 + save as source

推荐回答：

`我们的输出目标不是一段答案，而是正式研究报告。报告不只有结论，还有来源基础、冲突处理和闭环状态。研究完成后它还能导出 md，并且写回资料池，作为下一轮 Deep Research 的 source_scope 继续复用。`

为什么这么选：

`直接答案更快，但很难沉淀成资产。正式报告 + save as source 的代价是多做一层 artifact 设计和写回链路，但它让输出从对话结果升级成知识资产。`

`如果继续追问为什么这件事对项目宽度重要，我会回答：因为它意味着 Deep Research 不是一次性消费，而是能沉淀成工作台里的正式资料。这样这个项目就不仅仅是一个 Agent 能力点，还变成了一个知识生产和回流的功能闭环。`

### 3.9 process / audit 双层展示

推荐回答：

`主界面先看结果、报告、来源，详情层再分成 process 和 audit。process 讲 search/fetch/read 是怎么推进的，audit 讲 verifier、反证分支和 checkpoint 是怎么控制闭环的。`

为什么这么选：

`我也考虑过把所有内部状态平铺到主界面，但那样会让产品失焦。最后选择分层展示，是牺牲一点“炫技感”，换更清晰的结果交付。`

---

## 4. 使用原则

这一节不要单独背成一个章节，而是把它当作整本手册的回答习惯。

### 4.1 每个亮点都默认按五层来答

1. 先说它解决什么问题
2. 再说为什么最后选这个设计
3. 再说内部机制怎么运转
4. 再说失败时怎么恢复或兜底
5. 最后补 trade-off 和落地证据

### 4.2 每个亮点都默认能被横向扩问

面试官随时可能把问题从 Agent 内核扩到这些面：

1. 产品边界
2. 工具栈
3. 状态真源
4. 验证收口
5. 报告交付
6. 成本与展示

所以后面每一节的问答，我都会尽量把“深问”和“宽问”直接写进回答里，而不是单独抽出来讲。

---

## 5. Table-as-State 怎么详细讲

这是最容易被深问的一块，建议重点准备。

### 5.1 三分钟版本

`我们的 table 不是为了把数据存得像 Excel，而是为了把研究过程的中间状态，从“藏在 prompt 和记忆里”变成“显式可验证状态”。具体来说，系统每读完一批内容，不会直接去写答案，而是先抽成 evidence card，再把每张 evidence card 升格成一行 row。每个 row 不只记录证据内容，还会记录它来自哪个 source、是哪条 query 命中的、围绕什么 read focus 读出来的、它当前是 verified 还是 conflicted、以及它补上了哪些 requirement。`

`然后 row 会再展开成更细的 cell，系统就能知道这一行里哪些字段已经齐了，哪些字段还缺。再往上，ledger 会统计整个 run 现在有多少 verified row、多少 conflicted row、哪些 requirement 还没完成。后面的 verifier、反证分支、report、checkpoint、resume 都不是直接看自然语言上下文，而是直接消费这张表。`

### 5.2 八分钟版本

`我设计 Table-as-State 的原因很简单。复杂研究任务最大的麻烦不是搜不到，而是搜到很多东西以后，系统不知道自己现在到底掌握了什么、还缺什么、哪些证据冲突、哪些结论已经够资格写进报告。如果这些状态只存在 prompt 里，模型可能“好像记得”，但系统没法程序化判断。`

`所以我把状态拆成几层。第一层是 evidence card，也就是从 read window 里抽出来的证据单元，它只保留 claim、quote、support score、conflict score 这些最核心字段。第二层是 row，也就是把 evidence card 放进研究上下文里，给它补上 source、query、read focus、source quality、read strategy、当前 row status 这些信息。第三层是 cell，把 row 再拆到列级，这样系统能知道哪些字段是 requirement 真正需要的，哪些字段已经齐了，哪些字段还缺。第四层是 requirement progress，也就是从研究目标反推出来的完成合同，明确哪些 finding 必须被 verified row 覆盖，哪些 finding 必须有 conflicted row 才算处理过冲突。第五层才是 ledger 汇总，它会给整个 run 算 verified row 数、conflicted row 数、coverage score、pending requirement。`

`这样后面的所有关键模块都能围着 table 工作。verifier 不是看一段文本摘要，而是看 ledger 里有没有 verified row、是不是 low-trust source 在支撑当前答案、required finding 有没有 ready。反证分支也不是盲目再搜，而是从 table 里找到 conflicted row 和缺失 requirement，再生成 recovery target。report 也不是凭空写，而是从 verified row、conflicted row、provenance binding 里翻译成人类可读报告。checkpoint 和 resume 也是一样，它们保存和恢复的不是一段回答，而是整张状态表。`

### 5.3 高频追问

`table 到底存什么？`

推荐回答：

`它既存内容，也存状态，还存这个内容和研究目标之间的关系。最底层是 evidence card，中间是 row 和 cell，上层是 requirement progress 和 ledger 聚合。`

`为什么 row 和 cell 要分开？`

推荐回答：

`row 负责研究语义，cell 负责验证粒度。row 告诉系统“这条 finding 当前整体怎样”，cell 告诉系统“这条 finding 到底缺哪个字段”。`

`为什么不用 summary memory？`

推荐回答：

`summary memory 适合给模型看，不适合给系统做控制。系统需要明确知道哪些 finding ready、哪些 requirement missing、哪些 evidence conflicted，这些更适合 table。`

`为什么 report、checkpoint、resume 都要经过 table？`

推荐回答：

`因为我们要保证 research state、report state 和 recovery state 同源。如果不经过 table，这三者很容易各写各的。`

---

## 6. 关键机制怎么深挖

### 6.1 Research Harness 怎么详细讲

#### 三分钟版本

`Research Harness 本质上是整个研究系统的主控层。它不是单纯把几个工具串起来，而是把 plan、search、fetch、read、extract、verify、branch、report 这些阶段组织成一条固定主链，同时把控制态、审计摘要和工具摘要统一产出来。`

`你可以把它理解成研究系统的“运行框架”。没有它，工具还是能调，但整条链会比较散；有了它，系统才真正具备 run 级视角。`

#### 深一层怎么讲

`我设计 Harness，核心不是为了让代码更好看，而是因为复杂研究任务不能只靠“下一步调用哪个工具”来控制。真正困难的是：这个任务现在进行到哪了、当前 run 的状态怎么汇总、前后端怎么消费、checkpoint 怎么抽象。Harness 解决的就是这些 run 级问题。`

`它一头接 planner 和 worker loop，一头接 audit summary、toolbox summary、control state 和最终 artifact，所以它其实承担的是“把研究链路组织成可交付对象”的职责。`

#### 高频追问

`Harness 和普通 orchestrator 有什么区别？`

推荐回答：

`普通 orchestrator 更像工具调度器，Harness 更像研究系统主控层。前者重点是“把步骤跑通”，后者重点是“把整条研究链变成统一可控对象”。`

`为什么必须有 Harness？`

推荐回答：

`因为长研究任务里，最大的风险不是某一步不会调，而是整条链没有统一控制面。Harness 解决的是主链不散的问题。`

### 6.2 Closed-Loop Research 怎么详细讲

#### 三分钟版本

`Closed-Loop Research 的意思是，系统不是搜完就写，而是让研究、读取、验证和修正形成闭环。当前一轮 search/read/extract 完成后，系统会根据 verifier 和 requirement progress 决定下一步是补读、重抽、反证，还是允许收口。`

#### 深一层怎么讲

`这里的关键不是“多轮”，而是“验证驱动下一步动作”。如果没有这个闭环，系统很容易变成：搜到一些材料 -> 写出一篇长文 -> 看起来像研究报告。但真正有价值的研究系统，应该能回答“为什么还不能写”“为什么现在可以写”“为什么这里要开纠偏分支”。`

`所以我把 loop decision 做成类型化动作，而不是一句自然语言建议。这样 recovery 就不是笼统的“再试试”，而是明确的 READ_MORE、EXTRACT_AGAIN、反证复核或带护栏收口。`

#### 高频追问

`闭环具体闭在哪里？`

推荐回答：

`闭在 verifier 和下一轮计划之间。当前轮次的验证结果会直接改写下一轮动作。`

`怎么避免闭环无限循环？`

推荐回答：

`靠 stop contract 和预算边界，比如 loop round cap、branch budget、query budget，以及 WRITE_WITH_GUARDRAILS 这种受控收口。`

### 6.3 Dual Verifier 到底判什么

#### 三分钟版本

`Dual Verifier 分本地和全局两层。本地 verifier 看当前这一轮 evidence 和 ledger 是否站得住，全局 verifier 看整个 run 现在能不能正式收口。`

#### 深一层怎么讲

`我之所以拆成两层，是因为“局部证据够不够”和“整个任务能不能结束”其实不是一个问题。局部可能还有小瑕疵，但全局已经可以 guarded write；也可能局部看起来不错，但 run 级 requirement 还没补齐。`

`所以 Local Verifier 更像局部质量检查器，Global Verifier 更像 run 级裁判。这样可以避免一个总分把所有问题混在一起。`

#### 高频追问

`Local Verifier 主要看什么？`

推荐回答：

`主要看有没有 search hit、read window、evidence card、required finding、low-trust foundation、fallback-heavy path 和 conflict signal。`

`Global Verifier 主要看什么？`

推荐回答：

`主要看当前 run 是 READY_TO_WRITE、WRITE_WITH_GUARDRAILS，还是必须继续 recovery。`

一句话：

`Local 看局部质量，Global 看全局收口。`

### 6.4 反证分支怎么触发

#### 三分钟版本

`反证分支不是随机开，而是围绕冲突和缺口开。系统会先看 ledger 里有没有 conflicted row，或者 conflict requirement 还是 missing，再把这些目标转成 recovery target，然后做一轮 bounded counterfactual recheck。`

#### 深一层怎么讲

`这里的重点不是“多开一条路”，而是“挑战当前结论”。普通路径扩展更多是在继续往前找支持证据；反证分支则是在问：如果当前路径错了，最值得怀疑的点在哪，最值得补哪类相反或冲突证据。`

`所以它不是发散式搜索，而是围绕 conflicted row、缺失 requirement 和 target source 做定向纠偏。`

#### 高频追问

`反证分支和普通分支搜索有什么区别？`

推荐回答：

`普通分支更像扩搜索空间，反证分支更像定向纠偏。`

`为什么一定要 bounded？`

推荐回答：

`因为无边界分支会快速变成成本黑洞。我们需要的是纠偏，不是再造一棵搜索树。`

### 6.5 为什么 Search / Fetch / Read 要分层

#### 三分钟版本

`因为它们本来就是三种不同问题。Search 解决去哪找，Fetch 解决怎么稳定拿正文或 fallback，Read 解决拿到内容后围绕什么目标去读。`

#### 深一层怎么讲

`如果把三件事揉成一个大网页工具，短期开发会更快，但长期很多能力都做不出来，比如 query budget、provider fallback、transport fallback、read focus、source quality 以及过程展示。`

`Research Agent 的价值不是“网页工具能不能跑”，而是“研究链路能不能控”。所以分层设计虽然复杂一点，但能把预算、可靠性和解释能力都做出来。`

#### 高频追问

`为什么不做一个大而全网页工具？`

推荐回答：

`因为单体工具更快，但分层工具更可控。研究系统更需要后者。`

### 6.6 为什么报告比答案更重要

#### 三分钟版本

`因为研究任务最重要的不是一句结论，而是结论的来源基础和验证状态。报告能把 verified findings、source foundation、conflict review 和 closed-loop state 一起沉淀下来。`

#### 深一层怎么讲

`如果只输出答案，用户能得到一个结果，但系统很难把这个结果继续复用。正式报告的价值在于：它既是给人读的交付物，又是给系统继续回流的知识资产。`

`所以我们不是 markdown 写完就结束，而是先有 report structure，再有 artifact，再有导出和 save as source。`

#### 高频追问

`报告和普通 markdown 输出的区别是什么？`

推荐回答：

`普通 markdown 更像展示形式，正式报告更像结构化研究产物。`

`为什么一定要 save as source？`

推荐回答：

`因为这说明输出不是一次性回答，而是长期可复用资料。`

### 6.7 LoopDecision 和 `WRITE_WITH_GUARDRAILS` 怎么详细讲

#### 三分钟版本

`我没有把闭环做成“验证一下，不行就再试试”的松散模式，而是做成了明确的 LoopDecision。也就是说，系统每一轮不是自由发挥，而是被约束在几个明确动作里，比如继续 READ_MORE、重新 EXTRACT_AGAIN、开启 COUNTERFACTUAL_RECHECK、扩 EXPAND_SOURCE_SCOPE，或者允许 SYNTHESIZE_REPORT / WRITE_WITH_GUARDRAILS。`

`这里最关键的是 `WRITE_WITH_GUARDRAILS`。它的意义不是降低标准，而是承认开放式研究里存在“局部仍有不确定，但整体已经可以正式交付”的情况。这样系统既不会过早硬写，也不会为了追求完美无限循环。`

#### 深一层怎么讲

`LoopDecision 的本质价值是把 recovery 从自然语言建议变成类型化控制动作。这样 verifier 输出的就不只是“好像还差一点”，而是“差的是 coverage、extract 质量、source scope 还是冲突复核”，不同缺口对应不同下一步。`

`而 `WRITE_WITH_GUARDRAILS` 的价值在于，它把“研究型系统的收口”从二元判断变成分级判断。现实里不是所有任务都能等到完全无不确定性才交付，所以我们需要一个受控收口档，让系统在明确标注验证状态、来源基础和剩余不确定性的前提下，交付正式报告。`

#### 高频追问

`为什么不直接只有“继续搜”和“写报告”两种状态？`

推荐回答：

`因为那样太粗。开放式研究里失败的原因很多，可能是没搜够、可能是读得不对、可能是抽取没站住、也可能是需要反证。如果只有两种状态，系统根本无法做定向恢复。`

`WRITE_WITH_GUARDRAILS 会不会变成偷懒？`

推荐回答：

`不会，前提是它必须伴随显式的 guardrails，比如来源基础、验证状态、冲突处理结果和剩余不确定性说明。它不是“差不多就行”，而是“在受控说明前提下收口”。`

### 6.8 Extract / Verify 为什么值得单独讲

#### 三分钟版本

`Search / Fetch / Read 只是把信息拿进来，真正把网页内容变成研究状态的是 Extract 和 Verify。Extract 负责把读窗里的内容整理成 evidence cards，再沉淀成 ledger rows；Verify 负责判断这些 row 有没有资格支撑 finding、有没有冲突、下一步要不要 recovery。`

`所以这两层是系统从“会读网页”变成“会做研究”的关键转换层。没有它们，前面拿到的还是原始材料，后面也没法做真正的状态驱动控制。`

#### 深一层怎么讲

`我会把 Extract 理解成“从内容世界进入状态世界”的入口。前面不管是 workspace 文档还是外部网页，到了 Extract 这里都会被整理成更统一的 evidence unit，后面才能跟 requirement、column、source quality、conflict status 挂上关系。`

`Verify 则不只是评分器，而是 recovery 驱动器。它输出的不只是一个好坏判断，而是 decision records、warnings、recovery actions、intent completion contract 和 research intent alignment。也就是说，它既判断当前证据能不能站住，也判断现在是不是还在为原始研究目标服务。`

#### 高频追问

`为什么很多人会低估 Extract？`

推荐回答：

`因为表面上看 Extract 像是中间加工层，但实际上如果没有它，后面的 verifier、table、report 都只能吃原始文本，系统就退回到“让模型自己记住一切”的状态。`

`Verify 和 Dual Verifier 是什么关系？`

推荐回答：

`可以理解成 Verify 是验证层动作，Dual Verifier 是验证层的核心 gate 设计。前者负责产出 decision、warning 和 recovery signals，后者负责决定这些信号如何进入最终收口与循环控制。`

### 6.9 运行契约和 Run System 怎么详细讲

#### 三分钟版本

`这个项目不是一次性后端调用，而是显式对象化的 Research Run 系统。它有 research-runs、checkpoints、resume-from-checkpoint、save-report-as-source 这些明确契约，所以研究过程不是临时会话，而是可持久化、可恢复、可回流的运行对象。`

`这件事很重要，因为长研究任务天然会跨轮次、跨页面、跨交付阶段。如果没有 run system，很多能力就只能存在于一次内存执行里，无法真正落到工作台产品里。`

#### 深一层怎么讲

`我设计 run contract，核心是把“研究过程”也产品对象化。这样前端不只是拿一段最终文本，而是能拿 summary、detail、checkpoint、resume、artifact 这些不同粒度的正式对象。`

`checkpoint / resume 的意义也不只是容错，而是让复杂研究任务具备分段推进能力。系统可以在关键轮次冻结状态，再从某个 checkpoint 恢复继续研究，这说明我们的状态真源和闭环控制不是临时拼起来的，而是已经到了可管理运行生命周期的程度。`

#### 高频追问

`为什么 checkpoint / resume 是亮点，不只是工程配套？`

推荐回答：

`因为它反过来证明了状态系统是成立的。只有当研究状态真的被显式化、对象化了，checkpoint 和 resume 才有意义；如果状态还主要藏在 prompt 里，这两个能力根本做不稳。`

`save-report-as-source 为什么属于运行契约的一部分？`

推荐回答：

`因为它说明 run 的结束不是“返回答案”，而是“产出一个可继续进入下一轮研究系统的正式对象”。这让运行闭环和资料闭环连上了。`

### 6.10 Provenance / Artifact / 交付闭环 怎么详细讲

#### 三分钟版本

`我们的交付不是单一 markdown，而是围绕 `report_structure` 组织的一组正式产物。里面至少包括 verified findings、source foundation、closed-loop state、conflict review、intent completion contract 和 provenance bindings，然后再生成 final markdown，并统一打包成 artifact。`

`这意味着展示层、导出层和回流层不是三套逻辑，而是围绕同一个 artifact 工作。这样系统就能同时满足前端展示、md 导出和 save as source 三种交付。`

#### 深一层怎么讲

`我会把 provenance 这层理解成“来源基础进入正式报告”的桥。很多系统也会带 citation，但 citation 往往是后补的；我们这里更强调 source_foundation、provenance_bindings、source_refs 这些东西是报告结构的一部分，而不是最后装饰一下。`

`Artifact 的价值则在于统一消费。前端详情页、导出文件、写回资料池都消费同一份研究产物，而不是前端自己拼一套、导出再拼一套、回流再拼一套。这样一来，展示闭环和交付闭环都更稳。`

#### 高频追问

`为什么你一直强调 provenance，而不只强调 citation？`

推荐回答：

`因为 citation 更像引用标记，provenance 更像来源基础和形成链路。我们的目标不是让报告“看起来有引用”，而是让系统和用户都能知道这些 finding 到底建立在什么来源组合上。`

`Artifact 最体现工程价值的地方是什么？`

推荐回答：

`最体现工程价值的地方是统一消费。它把展示对象、导出对象、回流对象收敛成一个研究产物，避免三套逻辑各自漂移。`

### 6.11 成本模型和控费逻辑 怎么详细讲

#### 三分钟版本

`这个系统真正贵的不是字数，而是搜索、抓取、读窗保留、验证轮次和反证分支。所以我们的控费核心不是限制生成长度，而是限制 search budget、loop rounds、branch budget、result retention 这些结构化变量。`

`这也是为什么我会把 `Dual Verifier` 和 `WRITE_WITH_GUARDRAILS` 看成成本闸门。系统不是无穷无尽地搜到满意，而是知道什么时候继续花预算是值得的，什么时候已经可以受控收口。`

#### 深一层怎么讲

`如果把成本讲得更工程化，我会说这套系统天然适合按 run budget 来控费，而不是按 token 长度粗暴计费。因为 run cost 主要来自 Search、Fetch、Read retention、Verify、Report 和 Recovery overhead，而不是最终输出写了多少字。`

`所以我们后来才会把运行画像抽成 QUICK / STANDARD / DEEP 三档。这样既方便内部做预算，也方便对外把价格解释成研究深度和验证强度，而不是答案长度。`

#### 高频追问

`为什么按档位而不是按字数计费？`

推荐回答：

`因为真正贵的是研究过程，不是最终文本。一个 800 字但做了外部搜索、抓取、验证和反证的研究任务，可能比一篇 3000 字纯生成文本贵得多。`

`你觉得最关键的控费点是什么？`

推荐回答：

`我觉得最关键的是三类：query family budget、loop/branch budget、guarded write 收口。因为这三类分别控制了宽搜黑洞、纠偏黑洞和收口黑洞。`

### 6.12 落地证据和“不是 PPT 架构” 怎么详细讲

#### 三分钟版本

`我会明确说，这个项目不是停留在设计层，因为它已经有 worker、backend、frontend、demo 和 final aggregate 的落地证据。也就是说，我不是只讲了一套方法，而是这套方法已经进入脚本、测试、契约、构建和展示工件里。`

`这一点很重要，因为 Deep Research 这种项目最容易被质疑成“概念很好，但没法运行”。所以我会主动把落地证据作为回答的一部分，而不是等面试官质疑时再临时补。`

#### 深一层怎么讲

`如果被追问，我会把证据分五层讲：第一层是 worker 侧的 smoke 和 harness test，证明主链能跑；第二层是 backend contract test，证明 run system 和 artifact 契约不是空的；第三层是 frontend build 和 ui:check，证明展示层真的接得住；第四层是 demo 套件和讲解稿，证明项目是可展示的；第五层是 final aggregate 脚本，证明交付包可以统一验证。`

`这类证据的价值不只是“我做过测试”，而是它们分别对应了可运行性、可验证性、可展示性和可交付性四种不同主张。`

#### 高频追问

`怎么证明这不是 PPT 架构？`

推荐回答：

`我不会只说“代码已经写了”，而是会拿出分层证据：worker 的 smoke / harness test，backend 的 artifact contract test，frontend 的 build / ui check，demo 套件和最终汇总校验脚本。这样每一层主张都有对应证据。`

`如果面试官说“这些只是测试，不代表真实价值”怎么办？`

推荐回答：

`我会承认测试不等于全部价值，但它至少证明这不是纸面设计。然后我会再把它挂回最终交付：前端有结果、process、audit，报告可导出，产物可回流，这才是产品价值闭环。`

---

## 7. trade-off 怎么答

这一节是最像技术面的。

### 7.1 为什么不是普通 RAG

`优点是简单、便宜、实现快。缺点是状态主要在上下文里，冲突处理和路径纠偏弱。最后没选，是因为我们要解决的是研究控制问题，不只是 retrieval 问题。`

### 7.2 为什么不是纯“边想边调工具”主控

`优点是 reasoning 和 action 交替自然，适合中短链任务。缺点是长任务里状态容易漂，run 级完成度不清楚。最后吸收了工具驱动的优点，但把状态和收口控制显式化了。`

### 7.3 为什么不是纯“先规划后执行”主控

`优点是结构清楚。缺点是开放式研究里计划容易失效。最后选择保留 planning，但让计划可以被 verifier 和 recovery 动态改写。`

### 7.4 为什么不是大规模分支搜索

`优点是探索更强。缺点是成本和复杂度快速上涨。最后选择 bounded counterfactual branch，只在必要时纠偏。`

### 7.5 为什么不是纯“文本自我修正”主控

`优点是文本层自我修正成本低。缺点是它更像语言层修正，不是研究状态层修正。最后把自反思思想降到辅助层，主控仍然是 table + verifier。`

### 7.6 为什么不是图结构优先

`优点是关系表达更强。缺点是当前阶段对 requirement / verifier / recovery 这种控制问题不如表直接。最后先用表做第一真源，图适合后续增强。`

### 7.7 为什么不是单 verifier

`优点是轻。缺点是局部质量和全局收口会混在一起。最后拆成 Local + Global，是用一点复杂度换更稳的 gate。`

### 7.8 为什么不是一个大网页工具

`优点是开发快。缺点是 budget、fallback、read focus 和过程展示都做不细。最后拆成 Search / Fetch / Read。`

### 7.9 为什么不是更重的多 agent 协作

`优点是角色更丰富。缺点是复杂度、同步成本和展示难度都高。最后选择吸收外部方法里最有价值的思想，但把复杂度压在最关键的主链里。`

### 7.10 最推荐的 trade-off 总结

`我不是在追求某个单点最强，而是在追求整条链路同时满足可运行、可验证、可展示、可回流。最后选的是最贴合当前产品边界的方案，而不是最复杂的方案。`

---

## 8. 简历逐条追问题库

这一节按简历那条亮点逐句拆问。

### 8.1 “设计并实现验证驱动的 Deep Research 智能体” 会怎么问

`什么叫验证驱动？`

推荐回答：

`不是说多了一个 verifier 模块，而是 verifier 结果会直接驱动下一步动作和最终收口。`

`为什么不是普通 agent + 搜索？`

推荐回答：

`因为我们不是只解决“能不能搜”，而是解决“搜到之后状态怎么稳、路径怎么纠偏、结果怎么验证”。`

`验证驱动到底体现在哪，不是口号吗？`

推荐回答：

`不是口号，关键点在于 verifier 不是收尾点评器，而是主控的一部分。它会直接决定下一步动作是继续补证、开反证分支，还是允许受控收口。也就是说，验证结果不是被动记录，而是主动改写执行路径。`

`如果不用“验证驱动”，最容易出什么问题？`

推荐回答：

`最容易出的不是搜不到，而是太早写。系统找到几条像样的资料以后，就会过早相信当前路径，然后一路顺着写下去。验证驱动的价值就是强迫系统回答：现在到底够不够写，如果不够，缺的是 coverage、requirement 还是冲突处理。`

### 8.2 “状态漂移、路径跑偏、结果难验证” 会怎么问

`状态漂移具体指什么？`

推荐回答：

`长任务里系统越来越依赖 prompt 和记忆，慢慢失去对已验证 finding 和未完成 requirement 的稳定认知。`

`路径跑偏具体发生在哪？`

推荐回答：

`要么搜索越搜越偏，要么模型抓住早期路径一路写到底，即使后来出现冲突证据也不回头。`

`结果难验证是什么意思？`

推荐回答：

`就是最后生成的长文看起来像研究报告，但系统很难明确证明它到底靠什么证据站住。`

`这三个问题里哪个最难？`

推荐回答：

`我认为最难的是“结果难验证”。因为状态漂移和路径跑偏最后都会汇总成一个后果，就是你虽然生成了内容，但说不清它到底凭什么成立。所以我们最后才把很多设计都收束到 verifier、table 和 report eligibility 上。`

### 8.3 “Research Harness” 会怎么问

`它和普通 orchestrator 有什么区别？`

推荐回答：

`普通 orchestrator 更像工具串联器，Research Harness 是研究系统主控层。`

`如果没有 Harness，只保留 planner 和 tools，不行吗？`

推荐回答：

`短链路可以，长研究任务不太行。因为 planner 负责的是任务拆分，tools 负责的是动作执行，但中间缺了一个 run 级控制面，去统一管理状态沉淀、checkpoint、recovery、artifact 组装和前端展示。Harness 补的就是这一层。`

`Harness 最体现工程价值的地方是什么？`

推荐回答：

`最体现工程价值的地方是它让研究过程第一次能以 run 为单位被消费。这样 backend 可以持久化，前端可以展示 detail，report 可以导出，save as source 可以回流。没有这层，能力点是散的。`

### 8.4 “Closed-Loop Research” 会怎么问

`闭环具体闭在哪里？`

推荐回答：

`闭在 verifier 和下一轮计划之间。`

`闭环里最容易做假的地方是什么？`

推荐回答：

`最容易做假的地方是表面上有“多轮”，但实际上验证失败并不会改变策略，只是再搜一次、再读一次。真正的闭环一定要让失败类型进入控制逻辑，变成不同的 recovery action。`

`为什么闭环不等于无限循环？`

推荐回答：

`因为我们的目标不是一直找，而是受控收敛。闭环外面还有 round cap、query budget、branch budget 和 guarded write 这些边界，所以它不是无止境自治，而是验证驱动的有限研究。`

### 8.5 “Table-as-State” 会怎么问

`为什么它是亮点？`

推荐回答：

`因为它把系统从 prompt 驱动升级成状态驱动。`

`为什么状态驱动比 prompt 驱动更重要？`

推荐回答：

`因为 prompt 驱动更像“模型知道了什么”，状态驱动更像“系统知道了什么”。前者适合生成，后者才适合控制。我们这个项目真正难的是控制研究过程，所以必须让状态脱离 prompt，变成系统可消费的真源。`

`除了 verifier，谁还直接受益于 Table-as-State？`

推荐回答：

`至少还有三块直接受益：checkpoint/resume、report synthesis、前端 audit。因为它们都需要看到同一份状态真源，而不是各自重新猜一次系统现在走到了哪里。`

### 8.6 “Dual Verifier” 会怎么问

`为什么要两个 verifier？`

推荐回答：

`因为局部证据质量和全局收口不是一回事。`

`为什么不把两个 verifier 合成一个更复杂的评分器？`

推荐回答：

`因为一旦合成一个总分，局部证据不足、冲突没处理完、全局 requirement 没补齐这些问题就会混在一起。分层的价值不只是更细，而是让系统知道自己现在是在处理局部质量问题，还是在处理全局交付问题。`

`Dual Verifier 最重要的 trade-off 是什么？`

推荐回答：

`代价当然是复杂度更高，但它换来的是更稳定的收口逻辑。我觉得这是值得的，因为复杂研究系统最怕的不是多写一点代码，而是最后连“为什么现在可以写报告”都说不清。`

### 8.7 “反证分支” 会怎么问

`和普通分支搜索有什么区别？`

推荐回答：

`普通分支更像发散探索，反证分支更像定向纠偏。`

`为什么你强调“挑战当前结论”，而不是“继续扩大搜索”？`

推荐回答：

`因为研究系统更常见的失败不是搜索空间太小，而是过早锁定了当前路径。继续扩大搜索只会带来更多支持性材料，不一定能纠偏；反证分支则是强迫系统去找能否推翻当前判断的证据。`

`bounded 的边界一般靠什么定？`

推荐回答：

`一般看三类东西：冲突强度、剩余预算和当前 requirement 缺口。如果冲突弱、预算紧，而且全局已经接近可交付，就没必要无限开分支；如果冲突强、关键 requirement 还缺，那就值得开更明确的反证复核。`

### 8.8 “外部搜索 / 抓取 / 读取” 会怎么问

`为什么要拆三层？`

推荐回答：

`因为去哪找、怎么拿、拿到后怎么读，本来就是三类问题。`

`三层里你觉得最容易被低估的是哪一层？`

推荐回答：

`我觉得最容易被低估的是 Read。很多人觉得搜到网页就差不多了，但真正影响报告质量的，往往是系统有没有围绕 requirement 去读、有没有把页面内容转成结构化 evidence，而不是只把一篇网页塞给模型。`

### 8.9 “正式研究报告生成” 会怎么问

`为什么最后一定是报告？`

推荐回答：

`因为研究任务更看重来源基础和验证状态，而不是一句答案。`

`为什么不是先有 markdown，再慢慢补结构？`

推荐回答：

`因为那样很容易退化成“先写一篇像报告的东西，再往回补结构”。我们反过来做，是先有 report structure、findings、citations 和 artifact，再去生成最终 markdown，这样报告才是真正从研究状态长出来的。`

### 8.10 “来源回流资料池” 会怎么问

`为什么 save as source 是亮点？`

推荐回答：

`因为这说明输出不是一次性回答，而是长期可复用知识资产。`

`save as source 对下一轮研究最实际的价值是什么？`

推荐回答：

`最实际的价值是缩短下一轮的冷启动时间。因为下一次研究不必再从零开始找上下文，而是可以直接把上一次的正式报告和来源基础纳入 source_scope，等于把一次研究成果变成下一次研究的高质量起点。`

### 8.11 “process / audit 双层展示” 会怎么问

`为什么不把所有 audit 放主界面？`

推荐回答：

`因为主界面做结果交付，audit 做内部闭环解释。`

`为什么这不只是前端问题，而是架构问题？`

推荐回答：

`因为展示层怎么分，背后对应的是系统有没有把结果层、过程层和审计层真的拆开。如果后端和 worker 没有把 report、process、audit 分别产出来，前端也做不出这种层次。`

---

### 8.12 如果面试官开始从“项目宽度”来扩问

`这个项目除了 Agent 本身，还有哪些地方值得问？`

推荐回答：

`我会把项目宽度拆成六个面：产品边界、任务入口、工具栈、状态真源、验证收口、报告交付。这样面试官无论从哪一面切进来，我都能把它挂回同一条主线。`

`如果继续扩，我会再补两面：成本定价和前端展示。因为这两个面最能说明项目是不是可运行、可展示、可交付，而不是只停在算法设计。`

`项目宽度里最容易失分的地方是什么？`

推荐回答：

`最容易失分的是只会讲 Agent 内核，不会讲产品边界和交付闭环。因为面试官最后想知道的不是“你是不是想了很多机制”，而是“这是不是一个完整项目”。`

`如果面试官从产品一路扩到工程，你最推荐的展开顺序是什么？`

推荐回答：

`我会按这个顺序讲：先讲为什么 Deep Research 是独立功能，再讲任务输入和运行边界，再讲 Harness 和工具栈，再讲 Table-as-State 和 Dual Verifier，最后落到报告交付、展示和 save as source。这样从产品到架构到交付是一条顺的。`

### 8.13 如果面试官开始从“项目深度”来连续追问

`如果面试官一直追下去，最容易被追到哪几层？`

推荐回答：

`一般会被追到四层：第一层是为什么不是普通 RAG；第二层是为什么状态一定要显式化；第三层是 verifier 和反证分支怎么控制闭环；第四层是你怎么证明这些不是纸上设计，而是真的跑在系统里。`

`怎么避免深问时越答越散？`

推荐回答：

`我会强行把回答拉回同一个骨架：先说问题，再说设计选择，再说内部机制，最后补 trade-off 和落地证据。这样即使面试官连续追三四轮，我也不会变成一堆零散点。`

`如果被连续追到非常细，最值得守住的三个点是什么？`

推荐回答：

`我觉得最值得守住的是三个点：第一，状态真源为什么必须显式化；第二，验证结果为什么必须进入主控；第三，为什么交付的是正式研究报告而不是长答案。只要这三个点守住，整个项目就不会被问塌。`

### 8.14 `LoopDecision / WRITE_WITH_GUARDRAILS` 会怎么问

`为什么你要把 recovery 做成 LoopDecision，而不是让模型自己判断下一步？`

推荐回答：

`因为开放式研究里“下一步该干嘛”本身就是主控逻辑。如果把它继续留给自然语言自由发挥，系统还是会回到黑盒推理。LoopDecision 的价值就是把 recovery 变成显式动作，系统才真的可控。`

`WRITE_WITH_GUARDRAILS` 听起来像妥协，为什么反而是亮点？`

推荐回答：

`因为它体现了研究型系统对现实约束的尊重。真实任务不可能永远等到零不确定性才交付，所以我们需要受控收口，而不是二元地“没完全证实就永远不结束”。`

### 8.15 `Extract / Verify` 会怎么问

`为什么 Search / Fetch / Read 之后还要再单独讲 Extract？`

推荐回答：

`因为前面三层只是把内容拿进来，Extract 才是真正把内容转成 evidence card 和 ledger row。没有这一步，后面的 verifier、table 和 report 都只能面对原始文本。`

`Verify 为什么不只是打分？`

推荐回答：

`因为我们需要的不只是质量判断，而是恢复驱动。Verify 要输出 decision、warning、recovery action、intent completion 和 alignment，这样验证结果才能进入主控。`

### 8.16 `Research Run / checkpoint / resume` 会怎么问

`为什么你一直强调这是一个 run system？`

推荐回答：

`因为这说明它不是一次性函数调用，而是正式的运行对象。只有当 research-run、checkpoint、resume、artifact 这些都显式存在时，复杂研究任务才真的进入产品系统，而不是停留在单次会话里。`

`checkpoint / resume 最能说明什么？`

推荐回答：

`最能说明状态真源和闭环控制都已经工程化了。因为如果状态还主要靠 prompt 和记忆，checkpoint 和 resume 基本做不稳。`

### 8.17 `report_structure / provenance / artifact` 会怎么问

`为什么你的报告不是普通 markdown？`

推荐回答：

`因为 markdown 只是展示层，真正的研究产物是 report_structure 和 artifact。里面有 findings、source foundation、closed-loop state、conflict review、provenance bindings，最后 markdown 只是这些结构化内容的呈现形式。`

`为什么 provenance 比 citation 更值得讲？`

推荐回答：

`因为 citation 更像结果上的引用标记，provenance 更像形成过程里的来源基础。我们更在意的是 finding 到底建立在什么来源组合上，而不只是文末有没有几个脚注。`

### 8.18 `成本模型 / 档位 / 控费` 会怎么问

`为什么你的定价不按字数，而按档位？`

推荐回答：

`因为真正昂贵的是研究过程，不是答案长度。搜索次数、抓取复杂度、读窗保留、验证轮次和反证分支才是主要成本，所以更合理的口径是按研究深度和验证强度分档。`

`你觉得最关键的控费闸门是什么？`

推荐回答：

`我觉得最关键的是 query family budget、loop/branch budget 和 guarded write 收口。这三类分别控制宽搜黑洞、纠偏黑洞和收口黑洞。`

### 8.19 `怎么证明它真落地了` 会怎么问

`如果面试官说这听起来像 PPT 架构，你怎么回？`

推荐回答：

`我会直接把证据分层讲：worker 有 smoke 和 harness test，backend 有 artifact contract test，frontend 有 build 和 ui check，另外还有 demo 套件和最终汇总校验脚本。这样不是一句“我写了代码”，而是每一层能力都有对应证据。`

`为什么你把落地证据也当成亮点的一部分？`

推荐回答：

`因为像 Deep Research 这种项目，最容易被质疑的是“讲得很漂亮，但到底能不能跑”。所以把落地证据纳入标准回答，本身就是在降低这类项目的可信度风险。`

### 8.20 外部高频讨论转化题库

这一节不是重复前面的主架构，而是把外部关于 `Research Agent`、`LLM Agent`、`Deep Research`、`RAG + Agent` 的高频讨论点，直接翻译成更像真实面试里的追问。

`如果不只看最终答案质量，你会怎么评估一个 Research Agent？`

推荐回答：

`我不会只看“最后答案像不像”，而会拆成至少五层：任务完成度、来源基础、冲突处理、成本效率和可交付性。对我们这个项目来说，最终不是只看 final markdown，而是要一起看 report_structure、source_foundation、closed_loop_state、counterfactual 处理结果，以及这次 run 花了多少搜索 / 验证预算。`

`换句话说，我会把评估从“文本结果”提升到“研究过程 + 研究产物 + 资源消耗”的联合评估。因为复杂研究任务里，一个看起来不错的答案，不代表这个系统真的可控。`

`为什么你这么强调 auditability，而不是只强调准确率？`

推荐回答：

`因为研究型系统和普通问答最大的区别，就是用户会追问“你凭什么这么说”。所以我认为 auditability 至少和准确率同等级重要。我们这个项目里对应的落点就是 Table-as-State、provenance bindings、process / audit 双层展示，以及 report 里的 source_foundation。`

`如果只追准确率，系统很容易退化成“最后写得像”。但 auditability 会逼系统把来源基础、冲突处理和闭环状态一起交出来，这才更符合 Research Agent 的本质。`

`很多外部讨论都会问：为什么 claim-level trace 很重要？你会怎么答？`

推荐回答：

`我会说 claim-level trace 的本质，就是让系统能回答“这条 finding 到底由哪些证据支撑”。这件事如果做不好，最后报告里的很多句子都只是模型综合后的印象。`

`我们项目里没有直接用“花哨名词”包装，而是把它落到 evidence card、ledger row、source_foundation、provenance_bindings 这些对象上。这样不管面试官问审计性、可追溯性还是来源覆盖，我都能落回同一套状态和报告结构。`

`Research Agent 为什么不能只靠长上下文和 summary memory？`

推荐回答：

`这是外部讨论里很高频的问题。我会回答：长上下文和 summary memory 更适合帮助模型继续生成，不适合帮助系统持续控制。因为它们很难稳定表达 coverage、conflict、requirement progress 和 write eligibility。`

`所以我们最后的选择不是“不要记忆”，而是把真正关键的研究记忆迁移到结构化状态，也就是 Table-as-State。模型可以看摘要，但系统必须看真源。`

`如果面试官问：Research Agent 的 memory policy 应该怎么设计？`

推荐回答：

`我会把 memory policy 分成三层：第一层是临时上下文，服务当前轮 reasoning；第二层是结构化研究状态，服务跨轮控制；第三层是正式产物回流，服务跨任务复用。我们这个项目分别对应 read window / state ledger / save as source。`

`这种分层的好处是，系统不会把所有东西都塞进一个大记忆里。真正需要长久保存的是 verified findings、conflict state、intent completion 这些控制性信息，而不是所有中间文本。`

`外部很常讨论 objective drift。你会怎么讲我们是怎么防漂移的？`

推荐回答：

`我会说 objective drift 不是一个抽象风险，而是长研究任务里的常态。系统越跑越容易忘记原始研究目标、时间范围、交付要求和已完成 requirement。`

`我们这里防漂移不是靠“提醒模型别跑偏”，而是靠 intent contract、research intent alignment、target requirement_ids、loop decision 和 verifier gate 一起控。也就是说，目标不是一句 prompt，而是闭环里的显式约束。`

`如果问到“你怎么处理 distractor、trap documents、伪相关结果”呢？`

推荐回答：

`这是最近外部 benchmark 和讨论里特别常见的一类问题。我会回答：我们不假设搜索结果天然干净，所以系统必须能处理伪相关命中、低信任来源、以及看起来像支持但其实偏航的材料。`

`对应到项目里，就是 query family 不只做 direct search，还会做 triangulation、verified_evidence、counterfactual 这类搜索；后面再通过 Local Verifier 检查 low-trust foundation、conflict signal、fallback-heavy path。也就是说，我们不是假设工具干净，而是把“脏输入”纳入主链设计。`

`如果面试官问：你信不信 LLM 自己做长程规划？`

推荐回答：

`我会回答：我相信 LLM 可以辅助 planning，但我不相信开放式研究能靠一次静态计划走到底。这也是为什么我保留了 planning，却把真正控制权交给 Harness、LoopDecision 和 Dual Verifier。`

`外部很多讨论最后都会回到一个现实问题：规划本身会过时。Research Agent 真正要解决的，不是“有没有计划”，而是“计划失效以后系统还能不能受控修正”。`

`Research Agent 的可复现性怎么讲？尤其是外部网页一直在变。`

推荐回答：

`我会先承认：live web research 天然比离线 benchmark 更难完全复现，因为网页、排序、抓取结果都会变。真正现实的目标不是逐字节复现，而是尽量把研究过程对象化、快照化、可审计化。`

`所以我们做的是 research-run、checkpoint、fetch normalization、snapshot_status、artifact 和最终汇总校验。这样即使外部网页变化了，至少这次研究为什么得出这个结果、用过哪些来源、经历了哪些 loop decision，都是可以追查和复盘的。`

`如果面试官问：为什么你把 cost-efficiency 也当成核心知识点？`

推荐回答：

`因为外部对 Agent 的一个很大质疑就是“能做，但太贵”。所以我认为 cost-efficiency 不是产品层附加项，而是架构层核心指标。`

`我们项目里对应的回答就是：成本不是看字数，而是看 search budget、read retention、verify rounds、branch budget 和 guarded write 收口。也就是说，我们不是等系统“搜到满意”为止，而是让系统知道什么时候继续花钱有价值，什么时候应该收口。`

`最后一个高频问题：Research Agent 应该怎么向用户表达不确定性？`

推荐回答：

`我认为最差的做法是假装确定。Research Agent 的可信度，不在于永远给肯定句，而在于能不能把“哪些 finding 已验证、哪些来源更强、哪些地方仍有冲突或护栏收口”讲清楚。`

`这也是为什么我很重视 WRITE_WITH_GUARDRAILS、closed_loop_state、source_foundation 和 conflict review。它们本质上都是在把不确定性结构化，而不是把不确定性藏起来。`

`外部也很常问：什么时候根本不该用 Research Agent？`

推荐回答：

`我会先承认，不是所有任务都值得上 Deep Research。对于封闭知识库里的简单问答、单轮事实查询、或者强时延约束的小任务，普通 RAG 或直接工具调用往往更合适。`

`Research Agent 真正适合的是开放式、多来源、容易冲突、需要正式交付和来源基础的任务。所以我不会把它包装成通用默认解，而是明确它是高价值复杂任务的受控研究模式。`

`如果面试官问：你怎么处理 prompt injection 或恶意网页内容？`

推荐回答：

`我不会假设外部网页是可信的。Research Agent 一旦接入 live web，就必须默认网页内容可能带有诱导、注入、伪装指令甚至恶意工具提示。`

`对应到我们项目里，核心思路不是“相信网页再让模型自己辨别”，而是把网页当成 evidence 候选，而不是 control source。也就是说，真正能驱动主链的是 Harness、LoopDecision、intent contract、verifier gate 和结构化状态，而不是网页里的自然语言命令。网页可以提供内容，但不能接管控制权。`

`外部讨论里还有一个高频问题：stale memory 怎么办？`

推荐回答：

`我会回答：memory 最怕的不是忘，而是记错了还一直用。尤其在多轮研究里，过期摘要、旧 finding、已经被冲突证据推翻的结论，如果继续留在高优先级记忆里，会比“没记住”更危险。`

`所以我们项目里更强调结构化状态和验证状态，而不是盲目累积记忆。一个 finding 能不能继续参与后续推理，不是因为它还在上下文里，而是因为它在 ledger 里仍然处于可信状态。`

`如果面试官问：Research Agent 的 process metrics 应该看什么？`

推荐回答：

`我不会只看最终 answer accuracy，还会看过程指标，比如 query family 覆盖情况、fetch 成功率、有效 read window 比例、extract 成功率、verifier recovery 频率、counterfactual 触发率，以及 guarded write 比例。`

`这些指标的意义在于，它们能帮助我们定位系统到底是搜得不对、抓得不稳、读得太散、抽取得太弱，还是验证 gate 太松或太严。复杂 Agent 的调优如果只盯最终答案，很容易找不到真正瓶颈。`

`还有一种外部很常见的问法：你怎么做 calibration，而不是只做 confidence wording？`

推荐回答：

`我会说 calibration 的关键不是给答案配一个“我大概 80% 确定”的主观表述，而是让不确定性和证据状态挂钩。比如来源是不是高信任、requirement 是否补齐、有没有 conflict row、这次是不是 WRITE_WITH_GUARDRAILS 收口，这些都比一句语言上的自信程度更有价值。`

`所以我们项目里更强调结构化 uncertainty expression，也就是把不确定性落到 closed_loop_state、source_foundation、conflict review 和 guarded write 上，而不是单独做一个看起来很智能的 confidence 句子。`

`如果面试官问：现在很多 agent benchmark 分很高，为什么现实里还是经常翻车？`

推荐回答：

`我会说这是一个很典型的 reality gap 问题。benchmark 往往更容易控制环境、任务边界和工具噪声，但真实 Research Agent 面对的是动态网页、来源质量不一、抓取失败、提示注入、目标漂移和成本约束。`

`所以我不会把 benchmark 分数当成唯一证明，而会更强调 run-level controllability。对我们这个项目来说，更关键的是它在脏输入和不稳定外部环境下，能不能继续保持 verifier gate、状态真源和受控收口。`

`外部最近也很常问 citation accuracy：有 citation 不代表真的可信，你会怎么答？`

推荐回答：

`我完全同意。citation 的存在只说明系统给了链接，不说明链接真的支持 claim。Research Agent 真正难的是 citation association 和 citation support 这两层都要成立。`

`这也是为什么我更重视 provenance 和 source_foundation，而不是只看文末有没有引用。我们项目里希望回答的是“这条 finding 由哪些来源组合支撑，冲突有没有处理，当前是不是 guarded write”，而不是只给一个看起来很像论文的脚注格式。`

`如果面试官追问：那 hallucinated citation 怎么防？`

推荐回答：

`我不会说可以百分之百杜绝，但可以显著降低。核心方法不是让模型“尽量别编”，而是让 citation 生成尽量晚、尽量依赖前面已经结构化好的 finding / source binding / artifact，而不是在自由写作时再临时补链接。`

`换句话说，citation 应该尽量从 report_structure 和 provenance binding 长出来，而不是从最终 prose 里反推回去。这样即使还有错误，也更容易被 audit 层发现和纠正。`

`很多外部讨论现在会强调 human-in-the-loop。你怎么看？`

推荐回答：

`我认为对 Deep Research 这类高价值任务来说，human-in-the-loop 不是能力弱的表现，而是现实可交付体系的一部分。尤其在高风险结论、关键来源争议、或 guarded write 收口场景下，人类 review 很有价值。`

`我们这个项目虽然不是做重人工审批流，但已经天然给 human review 留了位置：主界面看报告和来源，详情层看 process / audit，研究结果还能导出和回流。这意味着它不仅能自动跑，也能被人有效复核。`

`如果面试官问：source diversity 为什么也是研究型 Agent 的重点？`

推荐回答：

`因为复杂研究任务最容易出现的一个问题，就是系统过度依赖单一来源类型。比如全靠新闻聚合、全靠某类博客、或者全靠搜索结果前几条，这样很容易形成视角偏差。`

`我们项目里之所以强调 query family、source_scope、triangulation 和 counterfactual，本质上就是为了避免“看起来搜了很多，其实都来自同一种信息链”的伪宽度。真正的宽度不只是结果条数多，而是来源结构更健康。`

## 9. 压力面 / 拷打题

### 9.1 你这不就是把 RAG 包装成了 Deep Research 吗

推荐回答：

`如果只是多了搜索，我同意这句话；但我们真正多出来的是显式状态、验证闭环和反证纠偏。`

### 9.2 你是不是过度设计了

推荐回答：

`这是我一直在压的风险，所以我没有上重型多 agent、没有上大规模搜索树、也没有让图结构当第一真源。最后保留下来的模块都直接服务状态控制、验证收口和研究交付。`

### 9.3 为什么你的方案听起来像把很多外部方法拼在一起

推荐回答：

`我确实调研过多种外部方法，但不是拼贴，而是取舍。最后留下来的都是对我们产品边界最有帮助的部分。`

### 9.4 你怎么证明这些取舍是对的，不只是主观偏好

推荐回答：

`我不说它对所有场景都最优，但对我们当前的目标最合理。因为我们的目标不是论文 benchmark 极限，也不是通用 agent 平台，而是一个可运行、可验证、可展示、可回流的 Deep Research 系统。`

### 9.5 如果给你更多时间，你会推翻现在的方案吗

推荐回答：

`不会推翻主线，但会继续增强。主线我认为是稳的，也就是 Harness、Table-as-State、Dual Verifier、bounded counterfactual branch 这几层。`

### 9.6 你怎么证明这不是 PPT 架构，而是真正落地了

推荐回答：

`我不会只说“代码已经有了”，而是会给出分层证据。worker 侧有 smoke 和 harness test，backend 有 artifact contract test，frontend 有 build 和 ui check，另外还有 demo 套件和最终汇总校验脚本。这样每一层能力主张都有对应证据，而不是只靠口头保证。`

`更重要的是，这些证据不是孤立的。它们分别对应了可运行性、可验证性、可展示性和可交付性，所以能证明这套系统不是停在概念设计，而是真的进入了完整交付链。`

---

## 10. 白板题和收尾模板

### 10.1 如果面试官让你白板讲架构

建议就画五个框：

1. `User Input`
2. `Research Harness`
3. `Search / Fetch / Read`
4. `Table-as-State + Dual Verifier`
5. `Report / Save as Source / UI`

讲法固定成三句话：

1. `上游是用户显式输入研究任务，不依赖上下文。`
2. `中间是 verifier-gated 的 Closed-Loop Research，不是搜完就写。`
3. `下游交付的是正式报告、来源基础和可回流资产。`

### 10.2 最推荐的收尾总结

`这个项目最核心的不是“做了一个也能搜索网页的 Agent”，而是把复杂开放式研究任务做成了一个真正可控的研究系统。我用 Research Harness 组织研究主链，用 Table-as-State 管理状态真源，用 Dual Verifier 控制验证闭环，再通过反证分支处理冲突和路径纠偏。最后交付的是正式研究报告、来源基础和可回流知识资产，而不是一段一次性回答。`

### 10.3 万能保底模板

如果面试里卡住了，可以用这个模板保底：

`我先讲一下这个设计解决的问题。这个问题如果只用普通 RAG 或普通工具调用，很容易出现状态漂移、路径跑偏和结果难验证。为了解决这个问题，我做了一个验证驱动的闭环：先用 Research Harness 组织主链，再用 Table-as-State 管理状态真源，然后用 Dual Verifier 决定继续补证、开反证分支还是允许收口。最后不是输出一段答案，而是输出带来源基础、可导出、可回流的正式研究报告。`

这个模板的好处是：

1. 不容易答散
2. 会自动把亮点落回问题驱动
3. 方便面试官继续追问到任何一个模块
