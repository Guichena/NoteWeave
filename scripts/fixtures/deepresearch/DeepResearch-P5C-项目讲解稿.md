# Deep Research P5-C 项目讲解稿

## 1. 为什么不是普通问答

这套 `Deep Research` 不是“把问题丢给模型然后等一段长答案”的普通问答，而是一个验证驱动的研究系统。它解决的是复杂开放式研究任务里的三个核心问题：

1. 状态易漂移
2. 路径易跑偏
3. 结果难验证

对应的系统答案是：

- 用 `Research Harness` 组织研究主链
- 用 `Closed-Loop Research` 控制研究、读取、验证、纠偏和成文
- 用 `Table-as-State` 保存研究状态真源
- 用 `Dual Verifier` 决定继续搜、纠偏、恢复还是允许写报告
- 用 `反证分支` 处理冲突证据，避免一路错到底

所以它的价值不在“会写长答案”，而在“能把长任务做成可控闭环”。

## 2. 固定 Demo 顺序

固定 demo 顺序只讲一条主线：

1. 用户输入一个明确的研究任务，启动独立 `Deep Research`
2. 主界面先看最终结论、正式报告、来源列表
3. 打开 `process`，展示 `search / fetch / read` 是怎么推进的
4. 打开 `audit`，展示 `Dual Verifier`、`反证分支`、`checkpoint / resume`
5. 展示 `export md -> save as source -> source_scope reuse`
6. 最后回到总结：为什么它不是普通问答，而是 `Closed-Loop Research`

## 3. 五个核心关键词怎么讲

### 3.1 Research Harness

讲法：

`Research Harness` 是整个系统的研究组织器。它不是一个单纯的 trace 容器，而是把规划、搜索、抓取、读取、抽取、验证、纠偏和写报告组织成固定主链。

一句话说法：

“我们把 Deep Research 做成了一个验证驱动的 Research Harness，而不是一次性 prompt 生成器。”

### 3.2 Closed-Loop Research

讲法：

`Closed-Loop Research` 指的是研究不是只跑一遍，而是会根据验证结果继续搜索、补读、开反证分支或者收口成文。研究、读取、验证和修正是一个闭环。

一句话说法：

“系统不是搜完就写，而是 verifier 通过之后才允许收口。”

### 3.3 Table-as-State

讲法：

`Table-as-State` 是研究状态真源。来源、read window、evidence、row、cell、verifier decision 都能落在结构化状态对象里，不依赖 prompt 和记忆硬撑。

一句话说法：

“我们把研究状态从 prompt 迁到了显式表状态，所以长任务不容易漂。”

### 3.4 Dual Verifier

讲法：

`Dual Verifier` 分本地和全局两层：本地 verifier 看当前证据和行级状态，全局 verifier 决定是继续、 guarded write、还是允许 final write。

一句话说法：

“Dual Verifier 负责控制闭环，不让系统轻易过早成文。”

### 3.5 反证分支

讲法：

`反证分支` 只在必要时打开，目标不是多开分支，而是专门处理冲突证据和路径纠偏。它证明系统不是遇到冲突就糊过去，而是真的会做 counterfactual recheck。

一句话说法：

“冲突证据不是被平均掉，而是会触发 bounded counterfactual branch。”

## 4. 简历亮点短版

可直接用于简历或面试开场的短版表述：

“设计并实现验证驱动的 Deep Research 智能体，围绕复杂开放式研究任务中的状态漂移、路径跑偏和结果难验证问题，提出 `Research Harness + Closed-Loop Research + Table-as-State + Dual Verifier + 反证分支` 的受控研究闭环；系统支持外部搜索/抓取/读取、正式研究报告生成、来源回流资料池，以及 `process / audit` 双层可展示工作台交付。”

## 5. 结束总结

整个项目最后要讲清的不是“我们也做了 Deep Research”，而是：

1. 我们把它做成了可运行、可验证、可演示、可复跑的正式系统
2. 每个亮点都能落到真实对象：
   - worker harness
   - Gate 1 样例
   - P5-A 写回链路
   - P5-B demo suite
3. 所以这套项目亮点不是概念包装，而是能被代码、脚本和验证结果支撑的真实能力
