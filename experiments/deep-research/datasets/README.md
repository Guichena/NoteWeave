# 固定评测集

| 字段 | 值 |
| --- | --- |
| dataset_key | `research-eval-v1` |
| dataset_version | `1.0.0` |
| 冻结时间 | 2026-09-18 |
| 样本数 | 24 |
| 类别分布 | NORMAL 6 / MISSING 6 / CONFLICT 6 / FAULT 6 |

机器可读定义在 [`research-eval-v1.json`](./research-eval-v1.json)，校验入口：

```powershell
python scripts/deepresearch/check_eval_dataset.py
```

校验在以下情况直接失败：样本数不在 20 到 30 之间、任一类别为空、`case_key` 重复、
`machine_checks` 使用了受控词表之外的值、FAULT 样本缺少故障注入描述、
非 FAULT 样本声明了故障注入。

## 类别与预期

| 类别 | 预期终态 | 说明 |
| --- | --- | --- |
| `NORMAL` | `COMPLETED_VERIFIED` | 来源可核验，要求引用可逐项回溯。 |
| `MISSING` | `INSUFFICIENT_EVIDENCE` 或 `COMPLETED_WITH_LIMITATIONS` | 预算内确实无合格证据，禁止虚构结论，也必须与基础设施故障区分。 |
| `CONFLICT` | `COMPLETED_WITH_LIMITATIONS` | 保留双方 Claim/Evidence，受限反证必须在次数或预算处停止并留下原因。 |
| `FAULT` | 视注入点而定 | 证据路径正常，故障在 Provider、Worker、消息或回调；要求分类准确且不重复已确认的外部调用。 |

## 受控断言词表

`machine_checks` 只能取以下值，便于后续评测脚本实现稳定断言：

`TERMINAL_STATE`、`EXPLAINABLE_TERMINATION`、`NO_UNSUPPORTED_CLAIM`、`CITATION_TRACEABLE`、
`CELL_NO_REGRESSION`、`REPLAN_LOCAL_ONLY`、`EXTRACTION_REASON_CODE`、`COUNTEREVIDENCE_BOUNDED`、
`NO_DUPLICATE_EXTERNAL_CALL`、`STALE_CALLBACK_REJECTED`、`INFRA_FAILURE_CLASSIFIED`。

## 版本纪律

- 评测集被引用后不允许原地修改问题文本或预期；需要变更时新增 `dataset_version`。
- 隐藏样本与失败类别必须与公开样本一同保留，禁止只优化可见样本。
- 每轮的原始结果写入 `../runs/`，聚合指标由脚本生成，见 `../README.md`。
- `research-eval-v1.json` 的 `acceptance_environment` 是**期望**，不是测量结果。某次运行实际使用的
  Provider、模型与开关以该次运行的 `manifest/experiment-manifest.json` →
  `effective_environment` 为准；两者不一致时以测量值为准，并在实验记录中说明。
