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
    const welcomeKnowledgePath = page.getByLabel("工作台资料与当前会话的关系");
    if (await welcomeKnowledgePath.isVisible()) {
      await expect(welcomeKnowledgePath).toContainText(/\d+ 份资料 · \d+ 个会话共用/);
    } else {
      await expect(page.locator(".chat-session-meta")).toContainText(/\d+ 份工作台资料/);
      await expect(page.locator(".chat-session-meta")).toContainText(/\d+ 个会话/);
      await expect(
        page.locator(".conversation .bubble").first().or(page.locator(".chat-welcome"))
      ).toBeVisible();
    }

    if (viewport.width >= 768) {
      if (viewport.width >= 1200) {
        await expect(page.locator(".sources-pane")).toBeVisible();
      } else {
        await expect(page.locator(".sources-pane")).toBeHidden();
      }
      await expect(page.locator(".chat-session-header h2")).not.toHaveText("等待会话");
      await expect(page.getByRole("button", { name: "打开资料库" })).toBeVisible();
      await assertWorkspaceSwitcherFits(page, viewport.width);
      const welcome = page.locator(".chat-welcome");
      if (await welcome.isVisible()) {
        const bounds = await page.evaluate(() => {
          const conversation = document.querySelector(".conversation")?.getBoundingClientRect();
          const panel = document.querySelector(".chat-welcome")?.getBoundingClientRect();
          return conversation && panel
            ? { conversationBottom: conversation.bottom, welcomeBottom: panel.bottom }
            : null;
        });
        expect(bounds).not.toBeNull();
        expect(bounds!.welcomeBottom).toBeLessThanOrEqual(bounds!.conversationBottom);
      }
      expect(consoleErrors).toEqual([]);
      return;
    }

    const mobileLayout = await page.evaluate(() => {
      const rail = document.querySelector<HTMLElement>(".global-rail");
      const main = document.querySelector<HTMLElement>(".workbench-main");
      const welcome = document.querySelector<HTMLElement>(".chat-welcome");
      const composer = document.querySelector<HTMLElement>(".composer-dock");
      const modeTrigger = document.querySelector<HTMLElement>(".answer-mode-trigger");
      const sourcesToggle = document.querySelector<HTMLElement>(".mobile-sources-toggle");
      return {
        mainTop: main?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
        railRight: rail?.getBoundingClientRect().right ?? Number.POSITIVE_INFINITY,
        railPosition: rail ? getComputedStyle(rail).position : "missing",
        welcomeBottom: welcome?.getBoundingClientRect().bottom ?? 0,
        composerTop: composer?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
        modeTop: modeTrigger?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY,
        modeBottom: modeTrigger?.getBoundingClientRect().bottom ?? Number.NEGATIVE_INFINITY,
        composerBottom: composer?.getBoundingClientRect().bottom ?? Number.NEGATIVE_INFINITY,
        sourcesTop: sourcesToggle?.getBoundingClientRect().top ?? Number.POSITIVE_INFINITY
      };
    });
    expect(mobileLayout.mainTop).toBeLessThan(80);
    expect(mobileLayout.railRight).toBeLessThanOrEqual(0.5);
    expect(mobileLayout.railPosition).toBe("fixed");
    expect(mobileLayout.composerTop).toBeGreaterThanOrEqual(mobileLayout.welcomeBottom);
    expect(mobileLayout.modeTop).toBeGreaterThanOrEqual(mobileLayout.composerTop);
    expect(mobileLayout.modeBottom).toBeLessThanOrEqual(mobileLayout.composerBottom);
    expect(mobileLayout.sourcesTop).toBeLessThan(mobileLayout.composerTop);

    const sourcesToggle = page.locator(".mobile-sources-toggle");
    await expect(sourcesToggle).toBeVisible();
    await sourcesToggle.click();
    await expect(page).toHaveURL(/\/library$/);
    await expect(page.locator(".source-library-page")).toBeVisible();
    const formatRow = page.getByLabel("支持的资料格式");
    await expect(formatRow).toContainText("PDF");
    await expect(formatRow).toContainText("MD");
    await expectNoHorizontalOverflow(page);

    await page.getByRole("button", { name: "打开主导航" }).click();
    await expect(page.getByText("共享当前工作台资料库", { exact: true })).toBeVisible();
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
