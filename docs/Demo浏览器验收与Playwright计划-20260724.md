# 本地全功能 Playwright 验收计划

日期：2026-07-24

本计划执行 [本地全功能演示修复计划](./Demo快速修复计划-20260724.md)。Playwright 只访问真实启动的 `docker compose --profile app` 环境，不 mock API，不造假数据。

## 1. 原则

- 每个用例创建真实工作台、资料、任务、run 或 artifact。
- 只简化环境和输入，不简化产品功能。
- 外部网络、Bilibili 和真实 LLM 仅在环境已配置时运行；未配置时测试应验证清晰的配置错误或本地模式标记。
- 每个用例保留 screenshot、trace、视频和关键 id，失败后可从数据库和日志继续定位。

## 2. 用例分组

| 分组 | 真实用户路径 | 关联 F 项 |
| --- | --- | --- |
| 工作台 | 登录、本地身份、创建工作台、切换、刷新、浏览器前进后退 | F-02、F-23、F-33、F-34 |
| 资料与任务 | 上传、解析、索引、删除、进度、失败终态 | F-42、F-47、F-48、F-52、F-54、F-55 |
| Chat | QA、Note、Wiki、流式回答、取消、刷新后结果一致 | F-27、F-28、F-30、F-43、F-44、F-49、F-56、F-57 |
| Wiki | ingest、搜索、页面查看与失败展示 | F-09、F-41 |
| Research | 创建、过程、报告、详情、checkpoint、resume、保存报告资料 | F-01、F-10、F-22、F-45、F-46 |
| Artifact | 技能目录、创建、进度、等待恢复、版本、导出、下载、保存资料、回写 | F-07、F-31、F-35、F-37、F-46、F-50、F-51、F-58、F-61 |
| Memory 与设置 | 审核、版本、成员、ACL、检索设置 | F-38、F-39 |

## 3. 用例结构

每条 Playwright 用例按以下结构实现：

1. 通过真实 UI 进入或创建专用演示工作台。
2. 调用 UI 创建本用例所需的资料或任务。
3. 等待真实页面显示非终态进度，再等待成功或明确失败终态。
4. 断言用户可见的结果、详情入口和刷新后的状态。
5. 将 workspace、task、run、artifact、source id 写入测试附件，便于排查。

优先顺序与修复计划一致：先共同运行底座，再长任务一致性，再所有功能入口，最后 Research 历史链收口。

## 4. 执行方式

```powershell
docker compose --profile app up --build -d
cd frontend
corepack pnpm exec playwright install chromium
corepack pnpm exec playwright test
```

初期不接入每次提交的 CI。准备 Demo、完成一批修复或修改前端路由/异步状态后，手动运行全量真实环境验收。

## 5. 完成条件

全部功能分组连续运行两次通过。第二次不得依赖人工修改数据库、清空运行态文件或绕过 UI。任一失败都先映射回对应 F 项并修复，不能通过跳过功能、替换假数据或增加无意义 loading 状态让用例通过。
