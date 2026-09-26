import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const credentials = loadCredentials();

for (const viewport of [
  { width: 1024, height: 768, name: "compact desktop" },
  { width: 375, height: 812, name: "mobile" }
]) {
  test(`Wiki, Memory, and Library keep their domain layouts on ${viewport.name}`, async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto("/");
    await loginIfRequired(page);
    await expect(page.locator(".workbench-shell")).toBeVisible();

    await navigateFromRail(page, "Wiki 知识库", viewport.width);
    await expect(page.locator(".wiki-workbench")).toBeVisible();
    await expectNoHorizontalOverflow(page);

    await expect.poll(async () => (
      await page.locator(".wiki-empty-workbench").isVisible()
      || await page.getByRole("button", { name: "打开关系面板" }).isVisible()
    )).toBe(true);
    const wikiIsEmpty = await page.locator(".wiki-empty-workbench").isVisible();
    if (wikiIsEmpty) {
      await expect(page.getByRole("heading", { name: "准备工作台知识网络" })).toBeVisible();
      await expect(page.locator('[aria-label="Wiki 构建流程"]')).toBeVisible();
      await expect(page.getByRole("complementary", { name: "Wiki 准备状态" })).toBeVisible();
    } else if (viewport.width >= 961) {
      await page.getByRole("button", { name: "打开关系面板" }).click();
      const relations = page.getByRole("complementary", { name: "Wiki 关系面板" });
      await expect(relations).toBeVisible();
      await expect(page.getByRole("group", { name: "图谱视角" })).toBeVisible();
      await expect.poll(async () => {
        const box = await relations.boundingBox();
        return box ? box.x + box.width : Number.POSITIVE_INFINITY;
      }).toBeLessThanOrEqual(viewport.width);
      const bounds = await relations.boundingBox();
      expect(bounds).not.toBeNull();
      expect(bounds!.x).toBeGreaterThanOrEqual(0);
      await page.getByRole("button", { name: "关闭关系面板", exact: true }).click();
    } else {
      await page.getByRole("button", { name: "打开关系面板" }).click();
      const relations = page.getByRole("complementary", { name: "Wiki 关系面板" });
      await expect(relations).toBeVisible();
      await expect(page.getByRole("group", { name: "图谱视角" })).toBeVisible();
      await expect.poll(async () => {
        const box = await relations.boundingBox();
        return box ? box.x + box.width : Number.POSITIVE_INFINITY;
      }).toBeLessThanOrEqual(viewport.width);
      const bounds = await relations.boundingBox();
      expect(bounds).not.toBeNull();
      expect(bounds!.x).toBeGreaterThanOrEqual(0);
      expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(viewport.width);
      await page.getByRole("button", { name: "关闭关系面板", exact: true }).click();
    }

    await navigateFromRail(page, "Memory 审核", viewport.width);
    await expect(page.locator(".memory-workbench")).toBeVisible();
    await expect(page.getByLabel("0 条待审核")).toBeVisible();
    await expect(page.getByRole("list", { name: "候选到审核决策的阶段" })).toBeVisible();
    await expect(page.getByText("进入审核队列", { exact: true })).toBeVisible();
    await expect(page.getByText("核对来源与冲突", { exact: true })).toBeVisible();
    await expect(page.getByText(/进入运行时/).first()).toBeVisible();
    if (viewport.width >= 961) {
      await expect(page.locator(".memory-runtime-inline")).toBeVisible();
      await expect(page.locator(".memory-version-panel")).toBeHidden();
    } else {
      await expect(page.getByLabel("运行时约束")).toHaveCount(1);
    }
    await expectNoHorizontalOverflow(page);

    await page.goto("/library");
    await expect(page.locator(".source-library-page")).toBeVisible();
    await expectNoHorizontalOverflow(page);
    const libraryLayout = await page.evaluate(() => {
      const layout = document.querySelector<HTMLElement>(".source-library-layout");
      const catalog = document.querySelector<HTMLElement>(".source-library-catalog");
      const tools = document.querySelector<HTMLElement>(".source-library-tools");
      return {
        columns: layout ? getComputedStyle(layout).gridTemplateColumns : "",
        catalogWidth: catalog?.getBoundingClientRect().width ?? 0,
        toolsWidth: tools?.getBoundingClientRect().width ?? 0
      };
    });
    expect(libraryLayout.catalogWidth).toBeGreaterThan(200);
    expect(libraryLayout.toolsWidth).toBeGreaterThan(200);
    if (viewport.width < 768) {
      expect(libraryLayout.columns.split(" ")).toHaveLength(1);
    }
    expect(consoleErrors).toEqual([]);
  });
}

async function navigateFromRail(page: Page, name: string, viewportWidth: number) {
  if (viewportWidth < 768) {
    await page.getByRole("button", { name: "打开主导航" }).click();
  }
  await page.getByRole("button", { name }).click();
}

async function expectNoHorizontalOverflow(page: Page) {
  const metrics = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth
  }));
  expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth);
}

async function loginIfRequired(page: Page) {
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
  if (!username || !password) {
    throw new Error("Real Playwright E2E requires NoteWeave bootstrap credentials");
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
