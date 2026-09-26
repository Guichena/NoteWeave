# Deep Research 最小验收集

本目录把“简历里能写什么”约束成 8 个可复查场景。它不是效果 benchmark，也不把单元测试当成真实 Provider 验证。

## 当前状态

- `[当前实现]` 8 题题型、期望终态、reason code 和机器检查项已冻结在 `manifest.json`。
- `[已测-模拟]` 校验器会拒绝题型漂移，也会拒绝没有 Run ID / 证据文件却标为 `PASSED` 的记录。
- `[生产待验证]` 8 个 `runtime.status` 均为 `PENDING_RUNTIME`；Docker 和真实链路恢复后逐题填写。

## 校验

```powershell
$env:UV_CACHE_DIR='D:\java-projects\NoteWeave-v2\.uv-cache'
$env:UV_PYTHON_INSTALL_DIR='D:\java-projects\NoteWeave-v2\.uv-python'
uv run python scripts/deepresearch/check_minimal_eval.py
```

只有输出 `passed_count: 8` 且 `resume_claim_ready: true`，才可宣称最小简历闭环完成。`pending_runtime_count: 8` 只表示定义完整，不代表真实链路通过。

## 执行可直接联网的 5 题

先以 dry-run 核对选择，不会创建数据或调用 Provider：

```powershell
uv run python scripts/deepresearch/run_minimal_eval.py
```

Docker 服务健康后，用环境变量提供专用验收账号并显式加 `--execute`。脚本会创建独立 Workspace、串行执行 2 正常 + 2 缺失 + 1 冲突题，并把公开 API 原始证据保存到 `runs/`。原始证据仍标记为 `[生产待验证]`，必须完成 required checks 审计后才可回填 `PASSED`。

Worker 中断、quote 篡改与 Provider 故障三题不会由该脚本“普通运行”冒充；必须按 `DEMO.md` 执行真实注入并保存执行前后证据。

## 每题真实证据

真实运行后在 `runs/<case-key>.json` 保存一份脱敏证据，并把相对路径回填到 manifest：

```json
{
  "case_key": "min-normal-api-comparison",
  "run_id": "真实 UUID",
  "started_at": "ISO-8601",
  "terminal_state": "COMPLETED_VERIFIED",
  "reason_codes": [],
  "claim_count": 2,
  "traceable_claim_count": 2,
  "unsupported_candidate_promotions": 0,
  "accepted_stale_callbacks": 0,
  "external_call_counts": {"search": 1, "fetch": 2, "read": 2, "extract": 1},
  "replan_audit_ids": [],
  "artifact_ids": ["真实 artifact id"],
  "operator_notes": "只记录复现步骤和异常，不粘贴密钥或网页全文。"
}
```

Worker 恢复题必须额外记录第一次与接管 Execution 的 ID、注入退出点、恢复前后调用计数、旧 callback 的拒绝码。局部 Replan/冲突题必须记录 audit ID、受影响 Cell 和未受影响 Cell 的调用计数。
