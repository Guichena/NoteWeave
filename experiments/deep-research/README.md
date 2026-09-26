# Deep Research 实验目录

本目录是 [DeepResearch 简历能力落地执行计划](../../docs/DeepResearch-简历能力落地执行计划.md) 第 13 节要求的统一证据目录。
它由 Manifest 串联，禁止用截图或人工口述替代原始记录。

## 目录契约

| 目录 | 内容 | 对应任务 |
| --- | --- | --- |
| `manifest/` | 代码版本、数据库迁移版本、Feature Flag 快照、Provider 清单、工具链版本 | DR-000 |
| `baseline/` | 失败基线的 Run 状态、Cell 状态、外部收据、预算账本导出 | DR-001 |
| `datasets/` | 固定评测集与类别标签，带版本号 | DR-002 |
| `replay-fixtures/` | 脱敏后的 Provider 响应，用于离线重放 | DR-104 |
| `runs/` | 评测运行产出的原始记录（每个 Run 一份） | DR-601 |
| `reports/` | 由脚本从原始记录聚合出的指标与 Bad Case 清单 | DR-603 |

## 重建 Manifest

```powershell
python scripts/deepresearch/build_experiment_manifest.py
python scripts/deepresearch/build_experiment_manifest.py --no-live   # 容器未运行时
```

Manifest 区分两类信息，两类 digest 分开，互不掩盖：

| 部分 | 来源 | digest 性质 |
| --- | --- | --- |
| `code` / `toolchain` / `feature_flags` / `worker_provider_environment_declared` / `acceptance_environment_declared` | 仓库文件 | `manifest_digest`，同一仓库状态重复执行必须相同 |
| `effective_environment.containers` | `docker exec <container> printenv` | 每个容器一个 digest，容器配置变化时会变化——这是正确行为 |

只声明值不够：`docker-compose.yml` 里的是模板，真正的生效值由 `.env` 与运行期覆盖决定。
`effective_environment.containers.worker.drift_vs_declared` 逐键给出「声明值 → 生效值」的差异，
`effective_environment.acceptance_compliance` 测量第 2.2 节要求的「高级特性关闭」是否成立。
密钥字段一律只保留 `SET` / `UNSET`，不写入明文。

容器不可用时会显式写入 `available: false` 与说明，**不会**伪装成已冻结运行期配置。

## 导出失败基线

```powershell
python scripts/deepresearch/export_baseline_run.py --user noteweave `
  --run 31d1d44f-85b9-44e3-b1fd-89dcbefa3c14 `
  --run 2f2e25c9-54a0-4993-837f-822e4e633b54 `
  --run b53ee5dd-c630-42d0-9b61-35ed4eff81a8
```

脚本通过 `information_schema` 反射列名，不硬编码字段，因此迁移新增字段不会让证据包静默漏采。
Run 表本身没有失败原因列；任务级 `terminal_reason` 由脚本聚合进 `index.json`。

## 离线重放抽取 fixture

```powershell
cd workers/research-worker
python -m app.extraction_replay --fixtures ../../experiments/deep-research/replay-fixtures
python -m app.extraction_replay --fixtures ../../experiments/deep-research/replay-fixtures --json
```

fixture 为 `research-extraction-replay.v1` 脱敏格式（`desensitized` 必须为 `true`，否则拒绝加载）：
只保留抽取调用所需的最小输入（问题、schema 列、read window 文本）与 Provider 原始 JSON 响应，
不含真实域名、URL、公司/产品名与凭据。重放不触网、不读环境变量、不依赖时间，
每个 fixture 连续重放两次必须得到逐字节相同的 diagnostics。

任一 fixture 与实际结果不一致时 CLI 打印 `FAIL` 并以非零退出码结束，因此可以直接用作回归门禁。

## 纪律

- 高级 Feature Flag 关闭、单 Worker、并发度 1 是验收环境的固定值，任何实验必须在 Manifest 中说明偏离。
- 指标只能由脚本从 `runs/` 的原始记录生成，不允许手工改写。
- 失败用例与 Bad Case 必须与成功样本一同保留。
