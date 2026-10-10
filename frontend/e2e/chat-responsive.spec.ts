import { expect, test, type Page } from "@playwright/test";
import {
  expectNoHorizontalOverflow,
  loginIfRequired,
  openChat,
  openSidebar,
  useFixtureWorkspace
} from "./helpers";

for (const viewport of [
  { width: 1280, height: 720, name: "desktop" },
  { width: 1024, height: 768, name: "compact desktop" },
  { width: 375, height: 812, name: "mobile" }
]) {
  test(`Chat keeps its workspace context usable on ${viewport.name}`, async ({ page }) => {
    test.setTimeout(240_000);
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto("/");
    await loginIfRequired(page);
    await expect(page.locator(".workbench-shell")).toBeVisible();
    // 右侧来源面板只有工作台里有资料时才有内容，先切到带资料的夹具工作台。
    await useFixtureWorkspace(page, viewport.width);
    await openChat(page, viewport.width);

    const metrics = await page.evaluate(() => ({
      viewportWidth: window.innerWidth,
      documentWidth: document.documentElement.scrollWidth
    }));
    expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth);
    await expect(
      page.locator(".conversation .message-row").first().or(page.locator(".chat-welcome"))
    ).toBeVisible();

    if (viewport.width >= 900) {
      // 宽屏：侧边栏显示工作台下的多个对话；≥1200 且工作台有资料时右侧来源面板默认展开
      await expect(page.locator(".app-sidebar")).toBeVisible();
      await expect(page.locator(".sidebar-conversation").first()).toBeVisible();
      if (viewport.width >= 1200) {
        await expect(page.locator(".sources-pane")).toBeVisible();
      } else {
        await expect(page.locator(".context-panel")).toBeHidden();
        await page.getByRole("button", { name: "打开产物" }).click();
        await expect(page.locator(".studio-pane")).toBeVisible();
        await page.keyboard.press("Escape");
        await expect(page.locator(".context-panel")).toBeHidden();
      }
      await assertWorkspaceSwitcherFits(page, viewport.width);
      expect(consoleErrors).toEqual([]);
      return;
    }

    // 抽屉收起有 220ms 过渡，等它真的滑出视口再量位置
    await expect(page.locator(".workbench-shell.is-mobile-nav-open")).toHaveCount(0);
    await expect.poll(async () => page.evaluate(() => (
      document.querySelector<HTMLElement>(".app-sidebar")?.getBoundingClientRect().right ?? Number.POSITIVE_INFINITY
    ))).toBeLessThanOrEqual(0.5);

    const mobileLayout = await page.evaluate(() => {
      const sidebar = document.querySelector<HTMLElement>(".app-sidebar");
      const composer = document.querySelector<HTMLElement>(".composer-box");
      const modeTrigger = document.querySelector<HTMLElement>(".answer-mode-switch");
      return {
        sidebarRight: sidebar?.getBoundingClientRect().right ?? Number.POSITIVE_INFINITY,
        sidebarPosition: sidebar ? getComputedStyle(sidebar).position : "missing",
        composerTop: composer?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
        composerBottom: composer?.getBoundingClientRect().bottom ?? Number.NEGATIVE_INFINITY,
        modeTop: modeTrigger?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
        modeBottom: modeTrigger?.getBoundingClientRect().bottom ?? Number.NEGATIVE_INFINITY
      };
    });
    expect(mobileLayout.sidebarRight).toBeLessThanOrEqual(0.5);
    expect(mobileLayout.sidebarPosition).toBe("fixed");
    // 三个元素都必须真的存在，否则下面的包含关系判断会变成空断言
    expect(Number.isFinite(mobileLayout.composerTop)).toBe(true);
    expect(Number.isFinite(mobileLayout.modeTop)).toBe(true);
    expect(Number.isFinite(mobileLayout.modeBottom)).toBe(true);
    expect(mobileLayout.modeTop).toBeGreaterThanOrEqual(mobileLayout.composerTop);
    expect(mobileLayout.modeBottom).toBeLessThanOrEqual(mobileLayout.composerBottom);

    await expect(page.locator(".context-panel")).toBeHidden();
    await page.getByRole("button", { name: "打开来源" }).click();
    await expect(page.locator(".sources-pane")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.locator(".context-panel")).toBeHidden();

    await openSidebar(page, viewport.width);
    // Esc 关掉工作台切换器之后，主导航抽屉必须留在原地
    await assertWorkspaceSwitcherFits(page, viewport.width);
    await expect(page.locator(".workbench-shell.is-mobile-nav-open")).toHaveCount(1);
    await page.getByRole("button", { name: "工作台资料库", exact: true }).click();
    await expect(page).toHaveURL(/\/library$/);
    await expect(page.locator(".source-library-page")).toBeVisible();
    const formatRow = page.getByLabel("支持的资料格式");
    await expect(formatRow).toContainText("PDF");
    await expect(formatRow).toContainText("MD");
    await expectNoHorizontalOverflow(page);
    expect(consoleErrors).toEqual([]);
  });
}

async function assertWorkspaceSwitcherFits(page: Page, viewportWidth: number) {
  await page.getByRole("button", { name: "切换工作台" }).click();
  const dialog = page.getByRole("dialog", { name: "工作台切换器" });
  await expect(dialog).toBeVisible();
  const bounds = await dialog.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(viewportWidth);
  const documentWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(documentWidth).toBeLessThanOrEqual(viewportWidth);
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
}
