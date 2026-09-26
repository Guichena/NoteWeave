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
      const railItems = [...document.querySelectorAll<HTMLElement>(".global-rail-item")];
      const first = railItems[0];
      const rootStyle = getComputedStyle(document.body);
      return {
        fontFamily: rootStyle.fontFamily,
        railItemHeights: railItems.map((item) => item.getBoundingClientRect().height),
        railItemFontSize: first ? Number.parseFloat(getComputedStyle(first).fontSize) : 0
      };
    });
    expect(shellMetrics.fontFamily).toMatch(/Segoe UI|PingFang SC|Microsoft YaHei/);
    expect(shellMetrics.railItemHeights.every((height) => height >= 44 && height <= 50)).toBe(true);
    expect(shellMetrics.railItemFontSize).toBeGreaterThanOrEqual(12);
    expect(shellMetrics.railItemFontSize).toBeLessThanOrEqual(14);

    await assertGlobalRailProportions(page);

    await assertChatProportions(page, viewport.width);
    await assertArtifactProportions(page);
    await navigateFromRail(page, "工作台资料库");
    await assertLibraryProportions(page, viewport.width);
    await navigateFromRail(page, "Deep Research 工作台");
    await assertResearchProportions(page);
    await assertResearchPalette(page);
    await navigateFromRail(page, "Wiki 治理工作台");
    await assertWikiProportions(page);
    await navigateFromRail(page, "Memory 人工审核");
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
    await expect(page.locator(".sources-pane")).toBeHidden();
    await expect(page.locator(".mobile-sources-toggle")).toBeVisible();
  }
  const metrics = await elementMetrics(page, [
    ".answer-mode-trigger",
    ".composer-actions .composer-send-button",
    ".composer-actions .artifact-rail-trigger"
  ]);
  expect(metrics[0]?.height).toBeGreaterThanOrEqual(39.5);
  expect(metrics[1]?.height).toBeGreaterThanOrEqual(39.5);
  expect(metrics[2]?.height).toBeGreaterThanOrEqual(39.5);
  const metadata = await elementMetrics(page, [
    ".chat-session-meta",
    ".mode-context-metric",
    ".source-list-heading > span",
    ".chat-knowledge-node small",
    ".chat-knowledge-node strong"
  ]);
  expect(metadata.every((item) => !item || item.fontSize >= 12)).toBe(true);
  await assertNoHorizontalOverflow(page);
}

async function assertArtifactProportions(page: Page) {
  await page.getByRole("button", { name: "打开产物", exact: true }).click();
  await expect(page.locator(".artifact-rail")).toBeVisible();
  await expect(page.getByRole("dialog", { name: "产物工作台" })).toBeVisible();
  await expect(page.getByRole("dialog", { name: "产物工作台" })).toHaveAttribute("aria-modal", "true");
  await expect(page.locator(".artifact-action-card").first()).toBeVisible();

  const metrics = await page.evaluate(() => {
    const cards = [...document.querySelectorAll<HTMLElement>(".artifact-action-card")];
    const text = [...document.querySelectorAll<HTMLElement>(".artifact-action-summary, .artifact-action-meta")];
    return {
      cardHeights: cards.map((element) => element.getBoundingClientRect().height),
      clippedText: text.filter((element) => (
        element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1
      )).map((element) => element.textContent?.trim() ?? ""),
      metadataFontSizes: text.map((element) => Number.parseFloat(getComputedStyle(element).fontSize))
    };
  });

  expect(metrics.cardHeights.every((height) => height >= 80)).toBe(true);
  expect(metrics.clippedText).toEqual([]);
  expect(metrics.metadataFontSizes.every((size) => size >= 12)).toBe(true);
  await page.getByRole("button", { name: "关闭产物工作台", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "产物工作台" })).toBeHidden();
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
  expect(metrics[0]?.fontSize).toBeLessThanOrEqual(32);
  expect(metrics[1]?.width).toBeGreaterThan(260);
  expect(metrics[2]?.width).toBeGreaterThan(260);
  expect(metrics[3]?.height).toBeGreaterThanOrEqual(104);
  if (viewportWidth >= 1100) expect(metrics[1]!.x).toBeLessThan(metrics[2]!.x);
  const filterMetrics = await elementMetrics(page, [".source-library-filter-row button", ".source-delete-button", ".source-library-statuses > span"]);
  if (filterMetrics[0]) {
    expect(filterMetrics[0].height).toBeGreaterThanOrEqual(39.5);
    expect(filterMetrics[0].fontSize).toBeGreaterThanOrEqual(12);
  } else {
    const emptyAction = await elementMetrics(page, [".source-library-empty-action"]);
    expect(emptyAction[0]?.height).toBeGreaterThanOrEqual(40);
  }
  if (filterMetrics[1]) expect(filterMetrics[1].height).toBeGreaterThanOrEqual(39.5);
  if (filterMetrics[2]) expect(filterMetrics[2].fontSize).toBeGreaterThanOrEqual(12);
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
  expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(20);
  expect(metrics[0]?.fontSize).toBeLessThanOrEqual(26);
  expect(metrics[1]?.fontSize).toBeGreaterThanOrEqual(26);
  expect(metrics[1]?.fontSize).toBeLessThanOrEqual(32);
  expect(metrics[2]?.height).toBeGreaterThanOrEqual(40);
  expect(metrics[3]?.height).toBeGreaterThanOrEqual(39.5);
  const metadata = await elementMetrics(page, [".research-launchboard-context dt", ".research-launchboard-context div > small", ".research-launchboard-flow small"]);
  expect(metadata.every((item) => !item || item.fontSize >= 12)).toBe(true);
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
    const ink = root ? getComputedStyle(root).getPropertyValue("--nw-color-ink").trim() : "";
    const domain = root ? getComputedStyle(root).getPropertyValue("--nw-domain-color").trim() : "";
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
      flow: background(".research-launchboard-flow"),
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
    expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(26);
    expect(metrics[0]?.fontSize).toBeLessThanOrEqual(32);
    if (metrics[1]) expect(metrics[1].height).toBeGreaterThanOrEqual(39.5);
    const metadata = await elementMetrics(page, [".wiki-empty-flow small", ".wiki-empty-context dt", ".wiki-empty-advice span"]);
    expect(metadata.every((item) => !item || item.fontSize >= 12)).toBe(true);
  }
  await assertNoHorizontalOverflow(page);
}

async function assertMemoryProportions(page: Page, viewportWidth: number) {
  await expect(page.locator(".memory-workbench")).toBeVisible();
  const metrics = await elementMetrics(page, [".memory-workbench h2", ".memory-workbench button"]);
  expect(metrics[0]?.fontSize).toBeGreaterThanOrEqual(20);
  expect(metrics[0]?.fontSize).toBeLessThanOrEqual(26);
  // Chromium may report a nominal 40px CSS height as 39.999984px.
  expect(metrics[1]?.height).toBeGreaterThanOrEqual(39.5);
  const metadata = await elementMetrics(page, [
    ".memory-panel-heading > span",
    ".memory-empty-kicker",
    ".memory-review-flow small",
    ".memory-review-flow li span:not(.memory-review-flow-icon)",
    ".memory-review-handoff > div > span"
  ]);
  expect(metadata.every((item) => !item || item.fontSize >= 12)).toBe(true);
  if (viewportWidth > 1120) {
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

async function assertGlobalRailProportions(page: Page) {
  const metrics = await page.evaluate(() => {
    const measure = (selector: string) => [...document.querySelectorAll<HTMLElement>(selector)].map((element) => {
      const bounds = element.getBoundingClientRect();
      return {
        height: bounds.height,
        width: bounds.width,
        fontSize: Number.parseFloat(getComputedStyle(element).fontSize)
      };
    });
    return {
      footerButtons: measure(".global-rail-actions button"),
      logout: measure(".global-rail-logout"),
      createConversation: measure(".global-rail-icon-button"),
      auxiliaryText: measure(".workspace-switcher-label, .global-rail-conversation-note, .global-rail-conversation-copy small"),
      workspaceSummaryClipped: [...document.querySelectorAll<HTMLElement>(".workspace-switcher-copy small")]
        .some((element) => element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1)
    };
  });
  expect(metrics.footerButtons.every((item) => item.height >= 39.5 && item.fontSize >= 12)).toBe(true);
  expect(metrics.logout.every((item) => item.height >= 39.5 && item.fontSize >= 12)).toBe(true);
  expect(metrics.createConversation.every((item) => item.height >= 39.5 && item.width >= 39.5)).toBe(true);
  expect(metrics.auxiliaryText.every((item) => item.fontSize >= 12)).toBe(true);
  expect(metrics.workspaceSummaryClipped).toBe(false);
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
