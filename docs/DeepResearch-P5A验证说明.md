# Deep Research P5-A 验证说明

## 1. 目的

`P5-A` 关注的是这条正式交付链：

`run -> export md -> save as source -> source_scope reuse`

这份说明文档负责固定：

- 当前 `P5-A` 的验证入口
- 前端和后端各自验证什么
- 跑通后可以证明什么

---

## 2. 固定验证入口

执行脚本：

```powershell
.\scripts\verify-p5a-research-delivery.ps1
```

当前脚本会顺序执行两段验证：

1. 前端导出契约
2. 后端写回 / 复用契约

---

## 3. 前端验证

命令：

```powershell
npm --prefix frontend run test -- src/researchReportDelivery.test.ts
```

当前覆盖：

- `final_report_markdown` 存在时能生成导出 artifact
- 导出文件名会优先使用 `final_report_title`
- 当标题为空白时会回退到 `question`
- 文件名会做安全字符清洗
- 没有 markdown 内容时不会伪造导出对象

对应代码：

- `frontend/src/researchReportDelivery.ts`
- `frontend/src/researchReportDelivery.test.ts`

---

## 4. 后端验证

命令：

```powershell
.\mvnw.cmd -f backend/pom.xml "-Dtest=Phase6ResearchArtifactContractTest#completedResearchReportShouldBeSavedAsWorkspaceSource+savedResearchReportShouldRemainIdentifiableInChatCitations" test
```

当前覆盖：

- 完成态 research run 可以 `save-report-as-source`
- 保存后的报告会形成正式 `GENERATED_RESEARCH_REPORT` source
- detail / history / checkpoint 能看到 `saved_report_source`
- downstream research run 可以把保存后的报告作为 `source_scope`
- downstream 查询面保留 `generated_by / generated_ref_id / research_artifact` 同源信息

对应测试：

- `backend/src/test/java/com/noteweave/Phase6ResearchArtifactContractTest.java`

---

## 5. 跑通后证明什么

如果这套验证通过，当前就能证明：

1. 报告导出链路不是只存在于 UI 按钮，而是有正式契约
2. 报告写回资料池不是一次性动作，而是会形成稳定 source 对象
3. 写回后的研究报告可以进入后续 `source_scope reuse`
4. `research run -> research artifact -> saved report source -> downstream reuse` 这条链已经有固定验证入口
