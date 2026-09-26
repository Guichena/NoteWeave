# 3～5 分钟演示步骤

## 1. 正常研究（约 60 秒）

1. 打开 Research 页面，提交 `min-normal-api-comparison`。
2. 展示问题被拆成 Matrix / Cell，而不是直接进入长文本生成。
3. 等待终态，打开报告中的一条正式 Claim。
4. 展示 `Cell → Candidate → Evidence → exact quote → snapshot → URL`，并打开原 URL。

## 2. 局部 Replan（约 60 秒）

1. 打开冲突或缺失样本对应的真实 Run。
2. 指出一个 VERIFIED Cell 与一个待修复 Cell。
3. 展示 `LOCAL_REPAIR` plan 和 `research_agent_replan_audit`。
4. 对比前后 Task：只新增失败 Cell 的 Task，VERIFIED Cell 的调用计数不增加。

## 3. Worker 中断恢复（约 60～90 秒）

1. 运行 `min-worker-crash-after-fetch`，在抓取归档后停止 Worker。
2. 展示 lease 过期后同一 Task 被新 Execution 领取。
3. 展示已归档 URL 的 fetch/read 计数没有增加，后续抽取继续完成。
4. 提交旧 Execution 的延迟 completion，展示稳定拒绝码和 canonical Cell 未改变。

## 4. 诚实失败（约 45 秒）

1. 打开 quote 篡改或 Provider 故障样本。
2. 展示不合格 Candidate 没有提升，正式报告没有伪造 Claim。
3. 展示 `COMPLETED_WITH_LIMITATIONS` / `INSUFFICIENT_EVIDENCE` / `INFRASTRUCTURE_FAILURE` 之一及 reason code。

## 演示前门禁

- `check_minimal_eval.py` 输出 `resume_claim_ready: true`。
- 浏览器中能访问 Run、Cell、报告与引用详情。
- 预先确认四个 Run ID，避免现场临时寻找记录。
- 不展示 `.env`、API key、完整请求头或未脱敏 Provider 响应。
