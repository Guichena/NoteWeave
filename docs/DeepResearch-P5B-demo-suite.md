# Deep Research P5-B Demo Suite

## 1. 目的

`P5-B` 负责把 `Deep Research` 收成一套正式 demo 包：

- 固定 2 - 3 个演示样例
- 固定每个样例重点看什么
- 固定一组验收命令

本套件的结构化清单见：

- [DeepResearch-P5B-demo-suite.json](/D:/java-projects/NoteWeave-v2/docs/DeepResearch-P5B-demo-suite.json)

---

## 2. 固定 Demo 样例

### 2.1 `wide_deep_report`

目标：

- 展示 `Research Harness` 如何把 wide search 和 deep read 收敛成正式报告

重点亮点：

- `Research Harness`
- `Table-as-State`
- 来源驱动的正式报告

重点看：

- 来源：Workspace source 如何进入 verified findings
- process：`Search Timeline / Read Timeline`
- audit：`Verifier Gate / Harness Control State`

### 2.2 `conflict_counterfactual`

目标：

- 展示冲突证据如何触发 `反证分支`

重点亮点：

- `Dual Verifier`
- `反证分支`
- guarded write 收口

重点看：

- 来源：conflict evidence 与 target evidence 绑定
- process：额外查询与 evidence summary
- audit：`Counterfactual Branch Summary / Verifier Gate`

### 2.3 `external_fetch_fallback`

目标：

- 展示外部网页抓取失败或无 transport 时，系统如何保留 fallback 证据并完成报告

重点亮点：

- `ResearchToolbox`
- fetch/read fallback 元数据
- 可解释的退化路径

重点看：

- 来源：external source 与 url 保留
- process：`Fetch Timeline / Read Timeline` 中的 fallback
- audit：fallback 下仍保持完整 verifier / control state

---

## 3. 固定验收命令

`P5-B` 固定验收入口：

```powershell
.\scripts\verify-p5b-demo-suite.ps1
```

当前这条脚本会依次执行：

1. `node scripts/check-p5b-demo-suite.mjs`
2. `powershell -ExecutionPolicy Bypass -File workers/scripts/run-gate1-smoke.ps1`
3. `conda run -n noteweave-workers python -m pytest workers/research-worker/tests/test_harness.py`
4. `.\mvnw.cmd -f backend/pom.xml -Dtest=Phase6ResearchArtifactContractTest test`
5. `npm --prefix frontend run build`
6. `npm --prefix frontend run ui:check`

---

## 4. 跑通后说明什么

如果这套命令通过，就可以证明：

1. `Gate 1` 的三类研究样例可复跑
2. worker 侧 `Research Harness` 回归口径稳定
3. backend 侧 `run / detail / checkpoint / artifact / writeback` 契约稳定
4. frontend 侧展示面仍满足当前冻结口径
5. `Deep Research` 已具备正式 demo 套件和固定验收命令
