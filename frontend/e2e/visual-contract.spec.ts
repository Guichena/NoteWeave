import { expect, test, type Page } from "@playwright/test";
import { loginIfRequired, navigateFromSidebar, useFixtureWorkspace } from "./helpers";

for (const viewport of [
  { width: 1280, height: 720, name: "desktop" },
  { width: 1024, height: 768, name: "compact desktop" }
]) {
  test(`desktop visual proportions remain coherent on ${viewport.name}`, async ({ page }) => {
    test.setTimeout(240_000);
    await page.setViewportSize(viewport);
    await page.goto("/");
    await assertAuthLayout(page, viewport.width);
    await loginIfRequired(page);

    await expect(page.locator(".workbench-shell")).toBeVisible();
    // 来源面板、资料目录都需要工作台里真的有资料，先切到夹具工作台
    await useFixtureWorkspace(page, viewport.width);
    await openChatFromSidebar(page);
    await assertNoHorizontalOverflow(page);

    const shellMetrics = await page.evaluate(() => {
      const navItems = [...document.querySelectorAll<HTMLElement>(".sidebar-nav-item")];
      const sidebar = document.querySelector<HTMLElement>(".app-sidebar")?.getBoundingClientRect();
      return {
        fontFamily: getComputedStyle(document.body).fontFamily,
        sidebarWidth: sidebar?.width ?? 0,
        navItemHeights: navItems.map((item) => item.getBoundingClientRect().height),
        navItemFontSize: navItems[0] ? Number.parseFloat(getComputedStyle(navItems[0]).fontSize) : 0
      };
    });
    expect(shellMetrics.fontFamily).toMatch(/Inter|Segoe UI|PingFang SC|Microsoft YaHei/);
    expect(shellMetrics.sidebarWidth).toBeGreaterThanOrEqual(56);
    expect(shellMetrics.sidebarWidth).toBeLessThanOrEqual(280);
    expect(shellMetrics.navItemHeights.length).toBe(4);
    expect(shellMetrics.navItemHeights.every((height) => height >= 32 && height <= 44)).toBe(true);
    expect(shellMetrics.navItemFontSize).toBeGreaterThanOrEqual(13);
    expect(shellMetrics.navItemFontSize).toBeLessThanOrEqual(15);

    await assertChatProportions(page, viewport.width);
    await assertArtifactProportions(page, viewport.width);
    await navigateFromSidebar(page, "工作台资料库", viewport.width);
    await assertLibraryProportions(page, viewport.width);
    await navigateFromSidebar(page, "Deep Research 工作台", viewport.width);
    await assertResearchProportions(page);
    await assertResearchPalette(page);
    await navigateFromSidebar(page, "Wiki 知识库", viewport.width);
    await assertWikiProportions(page);
    await navigateFromSidebar(page, "Memory 审核", viewport.width);
    await assertMemoryProportions(page, viewport.width);
  });
}

async function assertAuthLayout(page: Page, viewportWidth: number) {
  const authCard = page.locator(".auth-card");
  if (!await authCard.isVisible()) return;
  const metrics = await page.evaluate(() => {
    const card = document.querySelector<HTMLElement>(".auth-card")?.getBoundingClientRect();
    const context = document.querySelector<HTMLElement>(".auth-context-panel")?.getBoundingClientRect();
    const input = document.querySelector<HTMLElement>(".auth-field input")?.getBoundingClientRect();
    const submit = document.querySelector<HTMLElement>(".auth-submit-btn")?.getBoundingClientRect();
    return {
      card: card ? { x: card.x, right: card.right, width: card.width, height: card.height } : null,
      context: context ? { x: context.x, right: context.right, width: context.width, height: context.height } : null,
      inputHeight: input?.height ?? 0,
      submitHeight: submit?.height ?? 0,
      documentWidth: document.documentElement.scrollWidth
    };
  });
  expect(metrics.card).not.toBeNull();
  expect(metrics.context).not.toBeNull();
  expect(metrics.card!.x).toBeGreaterThanOrEqual(0);
  expect(metrics.card!.right).toBeLessThanOrEqual(viewportWidth);
  expect(metrics.context!.x).toBeGreaterThanOrEqual(0);
  expect(metrics.context!.right).toBeLessThanOrEqual(viewportWidth);
  expect(metrics.inputHeight).toBeGreaterThanOrEqual(38);
  expect(metrics.submitHeight).toBeGreaterThanOrEqual(40);
  expect(metrics.documentWidth).toBeLessThanOrEqual(viewportWidth);

  await page.getByRole("tab", { name: "注册" }).click();
  await expect(page.getByRole("button", { name: "创建账号并登录" })).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await page.getByRole("tab", { name: "登录" }).click();
}

async function assertChatProportions(page: Page, viewportWidth: number) {
  await expect(page.locator(".chat-panel")).toBeVisible();
  if (viewportWidth >= 1200) {
    await expect(page.locator(".sources-pane")).toBeVisible();
  } else {
    await page.getByRole("button", { name: "打开来源" }).click();
    await expect(page.locator(".sources-pane")).toBeVisible();
  }
  const metrics = await elementMetrics(page, [
    ".answer-mode-switch",
    ".composer-send-button",
    ".source-add-button",
    ".composer-box"
  ]);
  expect(metrics[0]?.height).toBeGreaterThanOrEqual(28);
  expect(metrics[1]?.height).toBeGreaterThanOrEqual(34);
  expect(metrics[2]?.height).toBeGreaterThanOrEqual(36);
  expect(metrics[3]?.width).toBeGreaterThan(320);
  const metadata = await elementMetrics(page, [
    ".source-row-copy small",
    ".pane-footnote",
    ".chat-welcome-meta",
    ".composer-scope"
  ]);
  expect(metadata.every((item) => !item || item.fontSize >= 11)).toBe(true);
  await assertNoHorizontalOverflow(page);
}

async function assertArtifactProportions(page: Page, viewportWidth: number) {
  // 1200px 以下来源面板是带遮罩的抽屉，遮罩会挡住顶部的产物开关，先把抽屉收起来
  if (await page.locator(".context-panel-backdrop").isVisible()) {
    await page.keyboard.press("Escape");
    await expect(page.locator(".context-panel")).toBeHidden();
  }
  await page.getByRole("button", { name: "打开产物", exact: true }).click();
  await expect(page.locator(".studio-pane .artifact-rail")).toBeVisible();
  await expect(page.locator(".artifact-action-card").first()).toBeVisible();

  const metrics = await page.evaluate(() => {
    const cards = [...document.querySelectorAll<HTMLElement>(".artifact-action-card")];
    const titles = [...document.querySelectorAll<HTMLElement>(".artifact-action-copy strong")];
    return {
      cardHeights: cards.map((element) => element.getBoundingClientRect().height),
      clippedTitles: titles.filter((element) => element.scrollWidth > element.clientWidth + 1)
        .map((element) => element.textContent?.trim() ?? ""),
      titleFontSizes: titles.map((element) => Number.parseFloat(getComputedStyle(element).fontSize))
    };
  });

  // 类型卡片为紧凑的单行图标卡，保证触控高度即可
  expect(metrics.cardHeights.every((height) => height >= 44)).toBe(true);
  expect(metrics.clippedTitles).toEqual([]);
  expect(metrics.titleFontSizes.every((size) => size >= 13)).toBe(true);
  await page.getByRole("button", { name: "关闭产物工作台", exact: true }).click();
  await expect(page.locator(".context-panel")).toBeHidden();
  void viewportWidth;
}

async function assertLibraryProportions(page: Page, viewportWidth: number) {
  await expect(page.locator(".source-library-page")).toBeVisible();
  const metrics = await elementMetrics(page, [
    ".source-library-hero-copy h2",
    ".source-library-catalog",
    ".source-library-tools",
    ".source-upload-dropzone"
  ]);
  expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(26);
  expect(metrics[0]?.fontSize).toBeLessThanOrEqual(36);
  expect(metrics[2]?.width).toBeGreaterThan(260);
  expect(metrics[3]?.height).toBeGreaterThanOrEqual(104);
  // 空资料库只保留居中的上传卡片；有资料时目录在左、上传工具在右。
  if (metrics[1]) {
    expect(metrics[1].width).toBeGreaterThan(260);
    // 1199px 以下改为单栏堆叠，资料目录排在上传工具上方
    if (viewportWidth >= 1200) {
      expect(metrics[1].x).toBeLessThan(metrics[2]!.x);
    } else {
      expect(metrics[1].y).toBeGreaterThanOrEqual(metrics[2]!.y);
    }
  }
  const filterMetrics = await elementMetrics(page, [".source-library-filter-row button", ".source-delete-button", ".source-status-badge"]);
  if (filterMetrics[0]) {
    expect(filterMetrics[0].height).toBeGreaterThanOrEqual(28);
    expect(filterMetrics[0].fontSize).toBeGreaterThanOrEqual(12);
  }
  if (filterMetrics[1]) expect(filterMetrics[1].height).toBeGreaterThanOrEqual(30);
  if (filterMetrics[2]) expect(filterMetrics[2].fontSize).toBeGreaterThanOrEqual(11);
  await assertNoHorizontalOverflow(page);
}

async function assertResearchProportions(page: Page) {
  await expect(page.locator(".research-page-shell")).toBeVisible();
  // 工作台里已有研究记录时会直接打开最近一条运行，这里要量的是新建研究的表单
  await expect(page.locator(".research-runs")).toBeVisible();
  await page.getByRole("button", { name: "新研究" }).click();
  await expect(page.locator(".research-composer")).toBeVisible();
  await expect(page.locator(".research-composer-intro h2, .research-run-heading h2").first()).toBeVisible();
  const metrics = await elementMetrics(page, [
    ".research-composer-intro h2, .research-run-heading h2",
    ".research-new-button",
    ".research-mode-switch button, .research-run-tabs button"
  ]);
  expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(22);
  expect(metrics[0]?.fontSize).toBeLessThanOrEqual(36);
  expect(metrics[1]?.height).toBeGreaterThanOrEqual(34);
  expect(metrics[2]?.height).toBeGreaterThanOrEqual(24);
  const metadata = await elementMetrics(page, [".research-composer-hint", ".research-runs-list small", ".research-run-metrics dt"]);
  expect(metadata.every((item) => !item || item.fontSize >= 11)).toBe(true);
  await assertNoHorizontalOverflow(page);
}

async function assertResearchPalette(page: Page) {
  const palette = await page.evaluate(() => {
    const background = (selector: string) => {
      const element = document.querySelector<HTMLElement>(selector);
      return element ? getComputedStyle(element).backgroundColor : "";
    };
    const root = document.querySelector<HTMLElement>('.workbench-shell[data-view="research"]');
    const primary = document.querySelector<HTMLElement>(".research-start-button");
    const primaryStyle = primary ? getComputedStyle(primary) : null;
    const ink = root ? getComputedStyle(root).getPropertyValue("--primary").trim() : "";
    const domain = root ? getComputedStyle(root).getPropertyValue("--accent").trim() : "";
    const colorProbe = document.createElement("span");
    colorProbe.style.backgroundColor = domain;
    document.body.append(colorProbe);
    const resolvedDomain = getComputedStyle(colorProbe).backgroundColor;
    colorProbe.style.backgroundColor = ink;
    const resolvedInk = getComputedStyle(colorProbe).backgroundColor;
    colorProbe.remove();
    return {
      theme: document.documentElement.dataset.theme,
      canvas: background(".workbench-canvas"),
      runs: background(".research-runs"),
      composer: background(".research-composer-box"),
      modes: background(".research-mode-switch"),
      ink,
      resolvedInk,
      domain,
      resolvedDomain,
      primary: primaryStyle?.backgroundColor ?? "",
      primaryColor: primaryStyle?.color ?? ""
    };
  });

  expect(palette.theme).toBe("light");
  expect(palette.ink).not.toBe("");
  expect(palette.domain).not.toBe("");
  expect(palette.primary).toBe(palette.resolvedInk);
  expect(palette.primaryColor).not.toBe(palette.primary);
  expect(new Set([palette.canvas, palette.runs, palette.composer, palette.modes]).size).toBeGreaterThanOrEqual(3);
}

async function assertWikiProportions(page: Page) {
  await expect(page.locator(".wiki-workbench")).toBeVisible();
  if (await page.locator(".wiki-empty-workbench").isVisible()) {
    const metrics = await elementMetrics(page, [".wiki-empty-main h2", ".wiki-empty-action"]);
    expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(24);
    expect(metrics[0]?.fontSize).toBeLessThanOrEqual(36);
    if (metrics[1]) expect(metrics[1].height).toBeGreaterThanOrEqual(36);
    const metadata = await elementMetrics(page, [".wiki-empty-main > p", ".wiki-empty-manual > summary"]);
    expect(metadata.every((item) => !item || item.fontSize >= 11)).toBe(true);
  }
  await assertNoHorizontalOverflow(page);
}

async function assertMemoryProportions(page: Page, viewportWidth: number) {
  await expect(page.locator(".memory-workbench")).toBeVisible();
  const metrics = await elementMetrics(page, [".memory-hero h2", ".memory-composer .primary-action"]);
  expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(22);
  expect(metrics[0]?.fontSize).toBeLessThanOrEqual(36);
  expect(metrics[1]?.height).toBeGreaterThanOrEqual(30);
  const metadata = await elementMetrics(page, [
    ".memory-layers dd",
    ".memory-section-heading > span",
    ".memory-kind-switch button",
    ".memory-scope-select select"
  ]);
  expect(metadata.every((item) => !item || item.fontSize >= 11)).toBe(true);
  // 宽屏下记忆与资料的分工以两栏对照展示
  if (viewportWidth >= 900) {
    const layers = await elementMetrics(page, [".memory-layers > div:first-child", ".memory-layers > div:last-child"]);
    expect(layers.every(Boolean)).toBe(true);
    expect(layers[0]!.y).toBeCloseTo(layers[1]!.y, 0);
    expect(layers[0]!.x).toBeLessThan(layers[1]!.x);
  }
  await assertNoHorizontalOverflow(page);
}

async function elementMetrics(page: Page, selectors: string[]) {
  return page.evaluate((targets) => targets.map((selector) => {
    const element = document.querySelector<HTMLElement>(selector);
    if (!element) return null;
    const bounds = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return {
      x: bounds.x,
      y: bounds.y,
      width: bounds.width,
      height: bounds.height,
      fontSize: Number.parseFloat(style.fontSize)
    };
  }), selectors);
}

/** 对话视图没有导航项，从侧边栏的会话条目进入。 */
async function openChatFromSidebar(page: Page) {
  await page.locator(".sidebar-conversation").first().click();
  await expect(page.locator(".chat-panel")).toBeVisible();
}

async function assertNoHorizontalOverflow(page: Page) {
  const metrics = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth
  }));
  expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth);
}



