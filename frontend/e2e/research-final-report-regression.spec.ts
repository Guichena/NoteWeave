import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const runId = "f2f71072-0ec3-422d-8af4-e273830a11c4";
const workspaceName = "Deep Research 浏览器验收";
const proofDir = resolve(process.cwd(), "..", ".codex-tmp", "browser-proof");
const credentials = loadCredentials();

test("completed verified report renders as an article with a semantic comparison table", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openFinalReport(page);
  await setTheme(page, "dark");

  await expect(page.locator("span").filter({ hasText: runId }).first()).toBeVisible();
  await expect(page.getByText("COMPLETED", { exact: false }).first()).toBeVisible();
  await expect(page.locator(".research-reader-kicker")).toHaveText("Deep research report");
  await expect(page.locator(".research-reader table")).toHaveCount(1);
  await expect(page.locator(".research-reader h3", { hasText: "对比表" })).toBeVisible();
  await expect(page.locator(".research-reader-body")).toContainText("PostgreSQL");
  await expect(page.locator(".research-reader-body")).toContainText("MySQL");
  await page.locator(".research-audit-disclosure summary").click();
  await expect(page.locator(".research-audit-markdown")).toContainText("postgresql.org");
  await expect(page.locator(".research-audit-markdown")).toContainText("docs.oracle.com");
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: resolve(proofDir, "research-report-desktop-dark.png"), fullPage: true });
});

test("completed verified report remains readable at 688px in light theme", async ({ page }) => {
  await page.setViewportSize({ width: 688, height: 1000 });
  await openFinalReport(page);
  await setTheme(page, "light");

  const table = page.locator(".research-reader table");
  await expect(table).toBeVisible();
  await expect(table.locator("thead")).toBeVisible();
  await expect(table.locator("tbody tr")).not.toHaveCount(0);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: resolve(proofDir, "research-report-688-light.png"), fullPage: true });
});

async function openFinalReport(page: Page) {
  await page.goto("/research");
  await loginIfRequired(page);
  await expect(page.locator(".research-workbench")).toBeVisible();

  const switcher = page.locator(".workspace-switcher-trigger");
  if (!await switcher.getByText(workspaceName, { exact: false }).isVisible().catch(() => false)) {
    await switcher.click();
    const search = page.locator(".workspace-switcher-search input");
    await search.fill(workspaceName);
    await page.getByRole("option", { name: new RegExp(workspaceName) }).click();
  }

  if (!await page.getByText(runId, { exact: false }).isVisible().catch(() => false)) {
    await expect(page.locator(".research-history-list")).toBeVisible();
    const targetByQuestion = page.locator(".research-history-card").filter({ hasText: "PostgreSQL 17" }).first();
    await expect(targetByQuestion).toBeVisible();
    await targetByQuestion.click();
  }
  await expect(page.locator(".research-reader")).toBeVisible();
}

async function loginIfRequired(page: Page) {
  const loginForm = page.locator(".auth-card");
  if (!await loginForm.isVisible()) return;
  await loginForm.locator('input[autocomplete="username"]').fill(credentials.username);
  await loginForm.locator('input[autocomplete="current-password"]').fill(credentials.password);
  await loginForm.locator('button[type="submit"]').click();
}

async function setTheme(page: Page, expected: "light" | "dark") {
  const root = page.locator("html");
  for (let attempt = 0; attempt < 2; attempt += 1) {
    if (await root.getAttribute("data-theme") === expected) return;
    await page.getByRole("button", { name: "切换明暗主题" }).click();
  }
  await expect(root).toHaveAttribute("data-theme", expected);
}

async function expectNoHorizontalOverflow(page: Page) {
  const metrics = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth
  }));
  expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth);
}

function loadCredentials() {
  const values = Object.fromEntries(
    readFileSync(resolve(process.cwd(), "..", ".env"), "utf8")
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith("#") && line.includes("="))
      .map((line) => {
        const separator = line.indexOf("=");
        return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
      })
  );
  const username = process.env.NOTEWEAVE_E2E_USERNAME ?? values.NOTEWEAVE_BOOTSTRAP_USERNAME;
  const password = process.env.NOTEWEAVE_E2E_PASSWORD ?? values.NOTEWEAVE_BOOTSTRAP_PASSWORD;
  if (!username || !password) throw new Error("Real E2E credentials are not configured");
  return { username, password };
}
