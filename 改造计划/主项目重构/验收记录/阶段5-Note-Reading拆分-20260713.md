# 阶段 5 Note Reading 拆分验收

- 日期：2026-07-13
- 后端测试：154/154
- 状态：Note reading-window 检索与规划已独立；Recall、关系和排序继续待拆

## 本批完成

- `NoteReadingRetriever` 批量 hydrate verify sources 的窗口并保持原跨 source 排序与最多 8 条限制；
- `NoteReadingPlanner` 唯一承接关键词评分、primary、continuation、secondary 规划和 metadata locator；
- `NoteEvidenceRetriever` 不再通过 `NoteRetrievalService` 打开窗口；
- `NoteRetrievalService` 删除 `openSourceWindowsForNote` 及旧窗口评分/规划 helpers。

## 自动验证

- Reading/Note/Hydrator/架构定向测试：18/18；
- 旧 reading 方法搜索：0；
- `mvn clean test`：39 suites，154/154，0 failures，0 errors，0 skipped；
- `git diff --check`：通过，仅有工作区既有 CRLF 提示。

## 尚未完成

- Note recall、Journal、关系信号和排序仍位于 `NoteRetrievalService`；
- BM25 deterministic baseline/gold set 与离线回放尚未建立；
- vector/fusion/rerank 继续等待 baseline 门禁。
