import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const credentials = loadCredentials();

for (const viewport of [
  { width: 1280, height: 720, name: "desktop" },
  { width: 1024, height: 768, name: "compact desktop" },
  { width: 375, height: 812, name: "mobile" }
]) {
  test(`Chat keeps its workspace context usable on ${viewport.name}`, async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto("/");
    await loginIfRequired(page);
    await expect(page.locator(".workbench-shell")).toBeVisible();

    const metrics = await page.evaluate(() => ({
      viewportWidth: window.innerWidth,
      documentWidth: document.documentElement.scrollWidth
    }));
    expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth);
    await expect(
      page.locator(".conversation .message-row").first().or(page.locator(".chat-welcome"))
    ).toBeVisible();

    if (viewport.width >= 900) {
      // 宽屏：来源常驻；≥1280 时产物也常驻
      await expect(page.locator(".sources-pane")).toBeVisible();
      await expect(page.getByRole("button", { name: "打开资料库" })).toBeVisible();
      if (viewport.width >= 1280) {
        await expect(page.locator(".studio-pane")).toBeVisible();
      } else {
        await expect(page.locator(".studio-pane")).toBeHidden();
        await page.getByRole("button", { name: "打开产物" }).click();
        await expect(page.locator(".studio-pane")).toBeVisible();
        await page.keyboard.press("Escape");
        await expect(page.locator(".studio-pane")).toBeHidden();
      }
      await assertWorkspaceSwitcherFits(page, viewport.width);
      expect(consoleErrors).toEqual([]);
      return;
    }

    const mobileLayout = await page.evaluate(() => {
      const nav = document.querySelector<HTMLElement>(".app-nav");
      const composer = document.querySelector<HTMLElement>(".composer-box");
      const modeTrigger = document.querySelector<HTMLElement>(".answer-mode-trigger");
      return {
        navRight: nav?.getBoundingClientRect().right ?? Number.POSITIVE_INFINITY,
        navPosition: nav ? getComputedStyle(nav).position : "missing",
        composerTop: composer?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
        composerBottom: composer?.getBoundingClientRect().bottom ?? Number.NEGATIVE_INFINITY,
        modeTop: modeTrigger?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
        modeBottom: modeTrigger?.getBoundingClientRect().bottom ?? Number.NEGATIVE_INFINITY
      };
    });
    expect(mobileLayout.navRight).toBeLessThanOrEqual(0.5);
    expect(mobileLayout.navPosition).toBe("fixed");
    expect(mobileLayout.modeTop).toBeGreaterThanOrEqual(mobileLayout.composerTop);
    expect(mobileLayout.modeBottom).toBeLessThanOrEqual(mobileLayout.composerBottom);

    await expect(page.locator(".sources-pane")).toBeHidden();
    await page.getByRole("button", { name: "打开来源" }).click();
    await expect(page.locator(".sources-pane")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.locator(".sources-pane")).toBeHidden();

    await page.getByRole("button", { name: "打开主导航" }).click();
    await expect(page.getByText("共享当前工作台资料库", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "工作台资料库" }).click();
    await expect(page).toHaveURL(/\/library$/);
    await expect(page.locator(".source-library-page")).toBeVisible();
    const formatRow = page.getByLabel("支持的资料格式");
    await expect(formatRow).toContainText("PDF");
    await expect(formatRow).toContainText("MD");
    await expectNoHorizontalOverflow(page);
    await assertWorkspaceSwitcherFits(page, viewport.width);
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

async function loginIfRequired(page: Page) {
  const loginForm = page.locator(".auth-card");
  if (!await loginForm.isVisible()) {
    return;
  }
  await loginForm.locator('input[autocomplete="username"]').fill(credentials.username);
  await loginForm.locator('input[autocomplete="current-password"]').fill(credentials.password);
  await loginForm.locator('button[type="submit"], button').last().click();
}

async function expectNoHorizontalOverflow(page: Page) {
  const metrics = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth
  }));
  expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth);
}

function loadCredentials() {
  const fileValues = readSimpleEnv(resolve(process.cwd(), "..", ".env"));
  const username = process.env.NOTEWEAVE_E2E_USERNAME
    ?? process.env.NOTEWEAVE_BOOTSTRAP_USERNAME
    ?? fileValues.NOTEWEAVE_BOOTSTRAP_USERNAME;
  const password = process.env.NOTEWEAVE_E2E_PASSWORD
    ?? process.env.NOTEWEAVE_BOOTSTRAP_PASSWORD
    ?? fileValues.NOTEWEAVE_BOOTSTRAP_PASSWORD;
  if (!username || !password) {
    throw new Error(
      "Real Playwright E2E requires NOTEWEAVE_E2E_USERNAME/PASSWORD or bootstrap credentials in ../.env"
    );
  }
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
