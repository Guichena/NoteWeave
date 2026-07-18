# 阶段 5 QA Reviewed Gold 编译与漂移校验验收

- 日期：2026-07-14
- 后端测试：177/177
- 输入：`retrieval-qa-annotation-request-v1` + `retrieval-qa-annotation-draft-v1`
- 输出：sanitized `retrieval-gold-v1` + `retrieval-shadow-v1`
- 状态：人工 reviewed draft 可安全编译为可回放 gold/shadow；真实数据人工标注与生产阈值校准尚待执行

## 完成内容

- annotation draft 的每个 case 新增 capture latency 和 raw candidate count；
- 新增 `QaGoldAnnotationCompiler`，读取受控 raw request 与已经人工审核的 sanitized draft；
- compiler 使用相同 salt 重新执行 `QaGoldAnnotationDraftCapture`，只获得当前脱敏候选；
- reviewed/current case 必须具有相同的假名 case/workspace、脱敏 query、allowed Source、topK 和 candidate pool；
- candidate count 和完整候选记录必须逐项一致，包括 rank、evidence/source/snapshot、chunk locator、title/content/source type、score、scope 状态和 citation labels；
- 任意候选集合、顺序、score、snapshot 或正文变化都会触发 candidate drift，要求重新 capture 和人工审核；
- case 必须标记 `REVIEWED`，并明确设置 `shouldRefuse`；
- refusal case 不允许选择 evidence/citation；非 refusal case 必须至少选择一个 relevant evidence；
- relevant evidence 必须存在于候选且位于 allowed scope；
- expected citation 必须精确等于 relevant evidence 携带的 citation label 集合，不能漏标或加入无关 citation；
- 编译成功后直接以假名化候选构造 sanitized gold，并以当前 capture 的 rank/score/latency/candidate count 构造 shadow；
- `CompilationResult` 和 `compileAndWrite` 都不暴露或写出 raw gold。

## 验证

- `QaGoldAnnotationCompilerTest`：3/3；
- 绿色路径验证 reviewed draft 直接输出 gold/shadow，并可进入 `RetrievalShadowComparator`；
- 输出文件不包含 raw workspace/source/chunk、token、email、手机号、IP 或 Windows 路径；
- 固定 candidate drift 会明确失败；
- 固定 PENDING draft 和 out-of-scope ground truth 会明确失败；
- 受控测试中的 shadow scope violation 为 1，是故意保留的越权候选检测证据，不代表生产表现；
- Compiler/Draft/Sanitizer/ApplicationContext 定向回归：7/7，0 failures，0 errors，0 skipped；
- `mvnw.cmd -f backend/pom.xml clean test`：50 份 Surefire 报告，177/177，0 failures，0 errors，0 skipped；
- `ArchitectureBoundaryTest`：13/13；
- `git diff --check` 与相关新文件 trailing whitespace 检查：通过。

## 安全与运行边界

- raw annotation request 仍是本地受控输入，可能包含真实 workspace/query/Source ID；
- reviewed draft、gold 和 shadow 均只包含假名化 ID 与脱敏文本；
- reviewer 只能修改 annotation status、shouldRefuse、relevant evidence、expected citation 和 notes，不能修改候选快照字段；
- compiler 每次都会重新查询当前 Port，因此索引变化不会被静默套用旧标签；
- 当前能力是 Spring programmatic component，下一步补充受控 CLI/运维入口后再运行真实人工标注批次。
