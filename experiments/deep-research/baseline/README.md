# 失败基线证据包（DR-001）

冻结时间：2026-09-18
Manifest：[`../manifest/experiment-manifest.json`](../manifest/experiment-manifest.json)
数据集版本：`research-eval-v1`（本目录导出时尚未引入，这些 Run 属于早期真实调用探索）

## 1. 代表性 Run

| Run | 状态 | 任务终态原因 | Cell 状态 | evidence_validation | candidate |
| --- | --- | --- | --- | --- | --- |
| `31d1d44f-85b9-44e3-b1fd-89dcbefa3c14` | FAILED | `NO_SUPPORTED_CANDIDATE` ×2 | GAP 4 | 0 | 0 |
| `2f2e25c9-54a0-4993-837f-822e4e633b54` | FAILED | `CANDIDATES_PROPOSED` ×1、`NO_SUPPORTED_CANDIDATE` ×1 | VERIFIED 3、GAP 1 | 3 | 3 |
| `b53ee5dd-c630-42d0-9b61-35ed4eff81a8` | FAILED | `NO_SUPPORTED_CANDIDATE` ×2 | GAP 4 | 0 | 0 |

三者均以 `FAILED` 收尾，没有进入允许的业务终态，正是第 5 节「不再把证据不足等同于系统异常」要解决的问题。

## 2. 可离线复盘的根因

`research_agent_execution.usage_json` 暴露了关键事实：

```json
// 31d1d44f 的两个任务
{"search_calls":1,"fetch_calls":1,"read_calls":1,"extract_calls":1,"llm_calls":0,
 "evidence_cards":0,"candidates_submitted":0}

// 2f2e25c9 的任务一
{"search_calls":1,"fetch_calls":1,"read_calls":1,"extract_calls":1,"llm_calls":1,
 "evidence_cards":3,"candidates_submitted":3}

// 2f2e25c9 的任务二
{"search_calls":1,"fetch_calls":1,"read_calls":1,"extract_calls":1,"llm_calls":1,
 "evidence_cards":0,"candidates_submitted":0}
```

结论：

1. `31d1d44f` / `b53ee5dd` 的零卡原因是 **LLM 未配置**（`NOTEWEAVE_RESEARCH_LLM_API_KEY` / `LLM_MODEL` 为空，
   `llm_calls=0`），抽取被诚实跳过，而不是「模型读过窗口但没有可支持的 claim」。
2. `2f2e25c9` 的任务二 **确实调用了模型**（`llm_calls=1`）却产出 0 张卡，可能是非法 JSON、
   未知 window、错误列或非精确引文，当前数据无法区分。
3. 因此现有 `budget_usage` 不足以解释零卡：**缺少结构化拒绝原因**。这是 DR-101 / DR-102 的直接输入。
4. `2f2e25c9` 已有 3 个 VERIFIED Cell，却因剩余 1 个 GAP Cell 让整个 Run 进入 FAILED，
   属于第 5 节要恢复的「部分结果诚实完成」缺口，对应 DR-201 / DR-305。

## 3. 证据包结构

```
runs/<runId>/
  index.json              # 终态、任务级 terminal_reason、Cell 状态计数、各表行数
  tables/<table>.json     # 按 Run 过滤的原始行，字段来自 information_schema 反射
```

导出命令见 [`../README.md`](../README.md)。`tables/` 未包含日志；若需要 Worker 日志，
用 `--log-file` 传入对应文件，脚本会复制到 `runs/<runId>/log/`。

## 4. 复现

```powershell
python scripts/deepresearch/export_baseline_run.py --user noteweave `
  --run 31d1d44f-85b9-44e3-b1fd-89dcbefa3c14 `
  --run 2f2e25c9-54a0-4993-837f-822e4e633b54 `
  --run b53ee5dd-c630-42d0-9b61-35ed4eff81a8
```

脚本只读；重复导出同一 Run 会覆盖同名文件，结果可逐字节比对。
