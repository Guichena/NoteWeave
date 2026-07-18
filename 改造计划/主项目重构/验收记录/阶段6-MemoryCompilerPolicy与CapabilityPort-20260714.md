# 阶段 6 Memory Compiler Policy 与 Capability Port 验收

- 日期：2026-07-14
- 范围：Capability 依赖反转、scope/neighborhood/utility/freshness 排序、token budget、编译 trace
- 兼容约束：保留 Control Pack 原字段、Artifact Skill 校验和历史 JSON 读取

## Capability 依赖反转

新增通用：

```text
com.noteweave.capability.CapabilityCatalogPort
```

`ArtifactSkillCatalogService` 实现该 Port，将 canonical skill key 与 action key 投影为通用 capability descriptor。`MemoryCompilerService` 只依赖 Port 的 `ObjectProvider`：

- adapter 存在时继续使用原 Artifact Skill canonicalization 和不存在 Skill 的业务错误；
- adapter 缺失时不阻止应用启动；
- fallback 使用 normalized capability key 和 skill neighborhood；
- `compilation_trace.degraded=true`；
- reason 为 `capability_catalog_unavailable`。

生产 `com.noteweave.memory` 与 `com.noteweave.capability` 源码中不再出现 Artifact import。

## 版本化 Compiler Policy

新增 `MemoryCompilerPolicy`，版本为 `memory-compiler-policy-v1`。

默认预算：

- Chat：320 token proxy；
- Artifact：480 token proxy；
- Research：480 token proxy。

排序优先级：

1. USER scope 高于 WORKSPACE scope；
2. 当前用户只能读取自己的 USER scope Memory；
3. exact task neighborhood 高于 broad neighborhood；
4. broad 高于 COMMON；
5. 相同 scope/neighborhood 下 utility 高者优先；
6. utility 相同则 updated-at 更新者优先；
7. 最后以 Memory Object ID 稳定排序。

这使 workspace 中其他用户的私人 Memory 不会进入当前用户 Control Pack，也避免高 utility 的 COMMON 规则覆盖更具体但 utility 略低的 task rule。

## Token Proxy 与对象级预算

Compiler 对最终新增的六类控制字段执行增量成本计算：

- style constraints；
- structure constraints；
- terminology policy；
- forbidden patterns；
- interaction policy；
- review checklist。

已经被高优先级 Memory 选中的重复字符串成本为零。估算规则：

- 每个汉字计一个 token proxy；
- 非空白、非汉字字符按每四字符约一个 token；
- 每个字符串增加一个分隔开销。

预算以整个 Memory Object 为最小准入单元；若新增成本越界，整个对象被丢弃，已有高优先级对象不会被截成半条规则。Evidence safety policy 成本预先占用预算。

## 可解释 Trace

Control Pack 新增 `compilation_trace`：

- `policy_version`；
- `maximum_tokens/selected_tokens`；
- `candidate_count/selected_count/dropped_count`；
- `truncated`；
- `degraded`；
- `degradation_reasons`。

每个 `memory_reference` 新增：

- `scope_priority`；
- `neighborhood_priority`；
- `selection_reason`，包含 utility 与 fresh-at。

历史持久化 Control Pack 缺少 trace 时由 record compact constructor 投影为 `legacy-unbounded`，不破坏 Research/Artifact 历史任务反序列化。

## 架构防线

`ArchitectureBoundaryTest` 固定：

- Memory package 不得依赖 Artifact package；
- `MemoryCompilerService` 必须依赖 `CapabilityCatalogPort`；
- `MemoryCompilerService` 必须依赖 `MemoryCompilerPolicy`；
- `ArtifactSkillCatalogService` 必须实现 Capability port。

## 自动验证

定向回归：

- `MemoryCompilerPolicyTest`：3/3；
- `MemoryCompilerServiceTest`：3/3；
- `Phase5MemoryContractTest`：8/8；
- `ArchitectureBoundaryTest`：23/23；
- 合计：37/37。

Backend 全量：

- Tests：259/259；
- Surefire reports：70；
- Failures：0；
- Errors：0；
- Skipped：0。

## 后续

Compiler 已有界并解耦，但 Candidate/Object 的 review 状态尚无统一队列和决策命令。下一批建立 Memory review read model，覆盖 Candidate review、Object negative outcome review、STALE 和 active conflict resolution。
