# Deep Research Gate 1 样例说明

## 1. 目的

这份说明文档服务于 `Gate 1`：

- 固定真实研究样例
- 固定每个样例重点验证什么
- 固定跑通后的最小通过口径

对应执行入口：

```powershell
.\workers\scripts\run-gate1-smoke.ps1
```

---

## 2. 样例清单

当前 `Gate 1` 固定为 3 个样例：

1. `wide_deep_report`
2. `conflict_counterfactual`
3. `external_fetch_fallback`

---

## 3. 样例说明

### 3.1 `wide_deep_report`

目标：

- 验证 `Research Harness` 的默认主链可稳定完成一次 wide + deep 研究
- 验证系统能输出正式报告、来源和基础 process / audit 对象

重点看：

- 是否产出 `report_markdown`
- 是否产出 `report_structure`
- `toolbox_summary.search / fetch / read` 是否齐全
- `audit_summaries` 和 `verifier_gate_policy` 是否齐全

通过口径：

- 报告生成成功
- 至少 1 条来源被正式带入结果
- `process` 基础对象存在
- `audit` 基础对象存在

### 3.2 `conflict_counterfactual`

目标：

- 验证冲突证据出现时，系统会走 `反证分支`
- 验证 `Dual Verifier` 能把冲突研究收口为 guarded write，而不是直接糊成单一路径

重点看：

- `counterfactual_summary.has_counterfactual_recheck`
- `counterfactual_branch_count`
- `verifier_gate_policy.report_gate_action`
- 回归口径是否与 `harness_regression.conflict_counterfactual` 一致

通过口径：

- 报告生成成功
- `反证分支` 显式存在
- verifier gate 动作存在
- 样例通过既有 harness regression 对齐校验

### 3.3 `external_fetch_fallback`

目标：

- 验证外部网页抓取失败或无 transport 时，系统仍能保留 fallback 证据并完成报告
- 验证 fetch/read fallback 元数据能进入正式结果对象

重点看：

- `fallback_document_count`
- `fallback_read_window_count`
- `external_source_count`
- `toolbox_summary.fetch_summary`
- `toolbox_summary.read_summary`

通过口径：

- 报告生成成功
- 至少 1 个 fallback document
- 至少 1 个 fallback read window
- 至少 1 个 external source

---

## 4. 结果解释

如果 `Gate 1` 通过，说明当前系统已经具备：

- 可复跑的真实研究样例入口
- 可展示的 `Research Harness` 主链
- 可展示的 `process` 基础对象
- 可展示的 `audit` 基础对象
- 至少一条 `反证分支` 场景
- 至少一条 external fallback 场景

如果 `Gate 1` 不通过，则按总执行文档进入：

1. `H1` 工具栈硬化
2. `H2` verifier / 反证分支硬化
3. `H3` checkpoint / resume 硬化

---

## 5. 当前入口

代码位置：

- `workers/research-worker/app/gate1_smoke.py`
- `workers/research-worker/tests/test_gate1_smoke.py`
- `workers/scripts/run-gate1-smoke.ps1`

推荐执行：

```powershell
conda run -n noteweave-workers python -m pytest workers/research-worker/tests/test_gate1_smoke.py

.\workers\scripts\run-gate1-smoke.ps1
```
