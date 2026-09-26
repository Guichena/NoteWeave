import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const credentials = loadCredentials();

for (const viewport of [
  { width: 1280, height: 720, name: "desktop" },
  { width: 1024, height: 768, name: "compact desktop" }
]) {
  test(`desktop visual proportions remain coherent on ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await page.goto("/");
    await assertAuthLayout(page, viewport.width);
    await login(page);

    await expect(page.locator(".workbench-shell")).toBeVisible();
    await assertNoHorizontalOverflow(page);

    const shellMetrics = await page.evaluate(() => {
      const navItems = [...document.querySelectorAll<HTMLElement>(".app-nav-item")];
      const topbar = document.querySelector<HTMLElement>(".app-topbar")?.getBoundingClientRect();
      return {
        fontFamily: getComputedStyle(document.body).fontFamily,
        topbarHeight: topbar?.height ?? 0,
        navItemHeights: navItems.map((item) => item.getBoundingClientRect().height),
        navItemFontSize: navItems[0] ? Number.parseFloat(getComputedStyle(navItems[0]).fontSize) : 0
      };
    });
    expect(shellMetrics.fontFamily).toMatch(/Inter|Segoe UI|PingFang SC|Microsoft YaHei/);
    expect(shellMetrics.topbarHeight).toBeGreaterThanOrEqual(48);
    expect(shellMetrics.topbarHeight).toBeLessThanOrEqual(64);
    expect(shellMetrics.navItemHeights.length).toBe(5);
    expect(shellMetrics.navItemHeights.every((height) => height >= 30 && height <= 44)).toBe(true);
    expect(shellMetrics.navItemFontSize).toBeGreaterThanOrEqual(12);
    expect(shellMetrics.navItemFontSize).toBeLessThanOrEqual(15);

    await assertChatProportions(page, viewport.width);
    await assertArtifactProportions(page, viewport.width);
    await navigateFromRail(page, "工作台资料库");
    await assertLibraryProportions(page, viewport.width);
    await navigateFromRail(page, "Deep Research 工作台");
    await assertResearchProportions(page);
    await assertResearchPalette(page);
    await navigateFromRail(page, "Wiki 知识库");
    await assertWikiProportions(page);
    await navigateFromRail(page, "Memory 审核");
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
  await expect(page.locator(".sources-pane")).toBeVisible();
  if (viewportWidth >= 1280) {
    await expect(page.locator(".studio-pane")).toBeVisible();
  } else {
    await expect(page.locator(".studio-toggle")).toBeVisible();
  }
  const metrics = await elementMetrics(page, [
    ".answer-mode-trigger",
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
  if (viewportWidth < 1280) {
    await page.getByRole("button", { name: "打开产物", exact: true }).click();
  }
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

  expect(metrics.cardHeights.every((height) => height >= 80)).toBe(true);
  expect(metrics.clippedTitles).toEqual([]);
  expect(metrics.titleFontSizes.every((size) => size >= 13)).toBe(true);
  if (viewportWidth < 1280) {
    await page.getByRole("button", { name: "关闭产物工作台", exact: true }).click();
    await expect(page.locator(".studio-pane")).toBeHidden();
  }
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
  expect(metrics[1]?.width).toBeGreaterThan(260);
  expect(metrics[2]?.width).toBeGreaterThan(260);
  expect(metrics[3]?.height).toBeGreaterThanOrEqual(104);
  if (viewportWidth >= 1024) expect(metrics[1]!.x).toBeLessThan(metrics[2]!.x);
  const filterMetrics = await elementMetrics(page, [".source-library-filter-row button", ".source-delete-button", ".source-library-statuses > span"]);
  if (filterMetrics[0]) {
    expect(filterMetrics[0].height).toBeGreaterThanOrEqual(28);
    expect(filterMetrics[0].fontSize).toBeGreaterThanOrEqual(12);
  } else {
    const emptyAction = await elementMetrics(page, [".source-library-empty-action"]);
    expect(emptyAction[0]?.height).toBeGreaterThanOrEqual(36);
  }
  if (filterMetrics[1]) expect(filterMetrics[1].height).toBeGreaterThanOrEqual(30);
  if (filterMetrics[2]) expect(filterMetrics[2].fontSize).toBeGreaterThanOrEqual(11);
  await assertNoHorizontalOverflow(page);
}

async function assertResearchProportions(page: Page) {
  await expect(page.locator(".research-page-shell")).toBeVisible();
  await expect(page.locator(".research-launchboard h2, .research-page h2").first()).toBeVisible();
  const metrics = await elementMetrics(page, [
    ".research-index h2",
    ".research-launchboard h2, .research-page h2",
    ".research-inline-actions button",
    ".research-filter-row button"
  ]);
  expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(18);
  expect(metrics[0]?.fontSize).toBeLessThanOrEqual(26);
  expect(metrics[1]?.fontSize).toBeGreaterThanOrEqual(26);
  expect(metrics[1]?.fontSize).toBeLessThanOrEqual(36);
  expect(metrics[2]?.height).toBeGreaterThanOrEqual(34);
  expect(metrics[3]?.height).toBeGreaterThanOrEqual(28);
  const metadata = await elementMetrics(page, [".research-launchboard-context dt", ".research-launchboard-context div > small", ".research-launchboard-flow small"]);
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
    const primary = document.querySelector<HTMLElement>(".research-launchboard-actions .primary-action");
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
      index: background(".research-index"),
      paper: background(".research-page"),
      context: background(".research-launchboard-context"),
      flow: background(".research-launchboard-flow > div:last-child"),
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
  expect(new Set([palette.canvas, palette.index, palette.paper, palette.context, palette.flow]).size).toBeGreaterThanOrEqual(3);
}

async function assertWikiProportions(page: Page) {
  await expect(page.locator(".wiki-workbench")).toBeVisible();
  if (await page.locator(".wiki-empty-workbench").isVisible()) {
    const metrics = await elementMetrics(page, [".wiki-empty-main h2", ".wiki-empty-action"]);
    expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(24);
    expect(metrics[0]?.fontSize).toBeLessThanOrEqual(36);
    if (metrics[1]) expect(metrics[1].height).toBeGreaterThanOrEqual(36);
    const metadata = await elementMetrics(page, [".wiki-empty-flow small", ".wiki-empty-context dt", ".wiki-empty-advice span"]);
    expect(metadata.every((item) => !item || item.fontSize >= 11)).toBe(true);
  }
  await assertNoHorizontalOverflow(page);
}

async function assertMemoryProportions(page: Page, viewportWidth: number) {
  await expect(page.locator(".memory-workbench")).toBeVisible();
  const metrics = await elementMetrics(page, [".memory-workbench h2", ".memory-workbench button"]);
  expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(16);
  expect(metrics[0]?.fontSize).toBeLessThanOrEqual(26);
  expect(metrics[1]?.height).toBeGreaterThanOrEqual(30);
  const metadata = await elementMetrics(page, [
    ".memory-panel-heading > span",
    ".memory-empty-kicker",
    ".memory-review-flow small",
    ".memory-review-flow li span:not(.memory-review-flow-icon)",
    ".memory-review-handoff > div > span"
  ]);
  expect(metadata.every((item) => !item || item.fontSize >= 11)).toBe(true);
  if (viewportWidth >= 1280) {
    const columns = await elementMetrics(page, [
      ".memory-queue-panel",
      ".memory-review-panel",
      ".memory-version-panel"
    ]);
    expect(columns.every(Boolean)).toBe(true);
    expect(columns[0]!.y).toBeCloseTo(columns[1]!.y, 0);
    expect(columns[1]!.y).toBeCloseTo(columns[2]!.y, 0);
    expect(columns[0]!.x).toBeLessThan(columns[1]!.x);
    expect(columns[1]!.x).toBeLessThan(columns[2]!.x);
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

async function navigateFromRail(page: Page, name: string) {
  await page.getByRole("button", { name, exact: true }).click();
}

async function assertNoHorizontalOverflow(page: Page) {
  const metrics = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth
  }));
  expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth);
}

async function login(page: Page) {
  const loginForm = page.locator(".auth-card");
  if (!await loginForm.isVisible()) return;
  await loginForm.locator('input[autocomplete="username"]').fill(credentials.username);
  await loginForm.locator('input[autocomplete="current-password"]').fill(credentials.password);
  await loginForm.locator('button[type="submit"], button').last().click();
}

function loadCredentials() {
  const fileValues = readSimpleEnv(resolve(process.cwd(), "..", ".env"));
  const username = process.env.NOTEWEAVE_E2E_USERNAME
    ?? process.env.NOTEWEAVE_BOOTSTRAP_USERNAME
    ?? fileValues.NOTEWEAVE_BOOTSTRAP_USERNAME;
  const password = process.env.NOTEWEAVE_E2E_PASSWORD
    ?? process.env.NOTEWEAVE_BOOTSTRAP_PASSWORD
    ?? fileValues.NOTEWEAVE_BOOTSTRAP_PASSWORD;
  if (!username || !password) throw new Error("Real Playwright E2E requires NoteWeave bootstrap credentials");
  return { username, password };
}

function readSimpleEnv(path: string) {
  try {
    return Object.fromEntries(
      readFileSync(path, "utf8")
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith("#") && line.includes("="))
        .map((line) => {
          const separator = line.indexOf("=");
          return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
        })
    ) as Record<string, string>;
  } catch {
    return {};
  }
}
