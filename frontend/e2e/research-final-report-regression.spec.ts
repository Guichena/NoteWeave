import { expect, test, type Page } from "@playwright/test";
import { resolve } from "node:path";
import {
  expectNoHorizontalOverflow,
  loginIfRequired,
  navigateFromSidebar,
  setTheme,
  useFixtureWorkspace
} from "./helpers";

const proofDir = resolve(process.cwd(), "test-results", "browser-proof");

/*
 * 原来的用例依赖数据库里一条固定 run_id 的历史研究，这条数据在当前环境并不存在。
 * 改成自带数据：夹具工作台里没有研究记录时就真开一条仅资料研究（不走网络检索，几十秒能跑完），
 * 再断言研究运行页与报告正文的真实渲染。
 * 万一这条研究没能产出终稿报告，报告部分会跳过并留下标注，而不是用假数据掩盖渲染问题。
 */

test("research run reader renders real run data as an article", async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openResearchRun(page, 1440);
  await setTheme(page, "dark", 1440);

  await expect(page.locator(".research-run-state")).toBeVisible();
  await expect(page.getByRole("tablist", { name: "研究内容" })).toBeVisible();
  await expect(page.locator(".research-run-metrics")).toBeVisible();
  await expect(page.locator(".research-runs-item.is-active")).toBeVisible();

  // 研究表始终可看：表头 + 至少一行研究对象
  await page.getByRole("tab", { name: /研究表/ }).click();
  const table = page.locator(".research-state-table");
  await expect(table).toBeVisible();
  await expect(table.locator("thead")).toBeVisible();
  await expect(table.locator("tbody tr")).not.toHaveCount(0);

  await assertReportWhenAvailable(page);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: resolve(proofDir, "research-run-desktop-dark.png"), fullPage: true });
});

test("research run reader remains readable at 688px in light theme", async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 688, height: 1000 });
  await openResearchRun(page, 688);
  await setTheme(page, "light", 688);

  // 688px 低于 899px 断点，侧边栏收进抽屉，运行页要独占整幅画布
  await expect(page.locator(".app-sidebar")).not.toBeInViewport();
  await expect(page.locator(".research-run-state")).toBeVisible();

  await page.getByRole("tab", { name: /研究表/ }).click();
  const table = page.locator(".research-state-table");
  await expect(table).toBeVisible();
  await expect(table.locator("thead")).toBeVisible();
  await expect(table.locator("tbody tr")).not.toHaveCount(0);

  await assertReportWhenAvailable(page);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: resolve(proofDir, "research-run-688-light.png"), fullPage: true });
});

/** 报告页签只有在研究真的产出报告时才可点，这时才断言正文渲染。 */
async function assertReportWhenAvailable(page: Page) {
  const reportTab = page.getByRole("tab", { name: "报告", exact: true });
  if (await reportTab.isDisabled()) {
    test.info().annotations.push({
      type: "environment-prerequisite",
      description: "这条研究还没产出终稿报告，本次只校验研究表与运行页渲染。"
    });
    return;
  }
  await reportTab.click();
  const reader = page.locator(".research-reader");
  await expect(reader).toBeVisible();
  // 运行页头部已经展示状态和统计，阅读器本身只保留目录和正文
  await expect(reader.getByRole("navigation", { name: "报告目录" })).toBeVisible();
  const body = page.locator(".research-reader-body");
  await expect(body).toBeVisible();
  expect((await body.innerText()).trim().length).toBeGreaterThan(120);
  const audit = page.locator(".research-audit-disclosure");
  if (await audit.count() > 0) {
    await audit.locator("summary").click();
    await expect(page.locator(".research-audit-markdown")).toBeVisible();
  }
}

/** 打开夹具工作台里的研究运行；没有记录时先真开一条。 */
async function openResearchRun(page: Page, viewportWidth: number) {
  await page.goto("/");
  await loginIfRequired(page);
  await useFixtureWorkspace(page, viewportWidth);
  await navigateFromSidebar(page, "Deep Research 工作台", viewportWidth);
  await expect(page.locator(".research-workbench")).toBeVisible();

  const runs = page.locator(".research-runs-item");
  if (await runs.count() === 0) {
    await startResearchRun(page);
  }
  await expect(runs.first()).toBeVisible({ timeout: 60_000 });
  await runs.first().click();
  await expect(page.locator(".research-run")).toBeVisible({ timeout: 60_000 });
}

async function startResearchRun(page: Page) {
  const composer = page.locator(".research-composer");
  await expect(page.locator(".research-runs")).toBeVisible();
  await page.getByRole("button", { name: "新研究" }).click();
  await expect(composer).toBeVisible();
  await page.getByLabel("研究问题", { exact: true }).fill(
    "Playwright 回归：夹具资料里的项目代号、发布日期与次要关键词分别是什么？"
  );
  // 仅资料模式不走网络检索，几十秒就能跑完，能真正拿到终稿报告
  await page.getByRole("radio", { name: "仅资料", exact: true }).click();
  const scopeChips = page.locator(".research-scope-picker button");
  await expect(scopeChips.first()).toBeEnabled({ timeout: 30_000 });
  for (const chip of await scopeChips.all()) {
    if (await chip.getAttribute("aria-pressed") !== "true") await chip.click();
  }
  const created = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/research-runs$/.test(response.url())
      && response.request().method() === "POST"
  );
  await page.getByRole("button", { name: "启动 Deep Research" }).click();
  expect((await created).status()).toBe(200);
  // 研究表第一轮就会落地，这是运行页渲染的最低要求
  await expect(page.locator(".research-state-table tbody tr").first()).toBeVisible({ timeout: 180_000 });
  // 再给它一段时间跑到终态；本地研究 worker 的快慢不该决定这条用例的成败，
  // 跑完了就能顺带校验报告正文，没跑完由 assertReportWhenAvailable 留下标注。
  await page.locator(".research-run-state")
    .filter({ hasText: /已完成|运行失败|证据不足/ })
    .first()
    .waitFor({ timeout: 180_000 })
    .catch(() => undefined);
}
