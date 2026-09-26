import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const credentials = loadCredentials();
const workspaceName = "Codex 全面测试 2026-08-19";

test("QA retrieves an indexed workspace source and renders its citation", async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto("/");
  await loginIfRequired(page);
  await expect(page.locator(".workbench-shell")).toBeVisible();
  await selectWorkspace(page, workspaceName);

  await page.getByRole("button", { name: "笔记本：来源、对话与产物", exact: true }).click();
  const modeButton = page.getByRole("button", { name: "回答模式：问答 RAG", exact: true });
  if (!await modeButton.isVisible().catch(() => false)) {
    await page.getByRole("button", { name: /^回答模式：/ }).click();
    await page.getByRole("menuitem", { name: "问答 RAG", exact: true }).click();
  }
  const assistantCount = await page.locator(".bubble.assistant").count();
  const marker = `qa-index-regression-${Date.now()}`;
  await page.locator(".composer-box textarea").fill(
    `According to the workspace source, what is the project codename and planned release date? ${marker}`
  );
  const sendButton = page.locator(".composer-send-button");
  await expect(sendButton).toBeEnabled();
  await sendButton.click();

  const assistant = page.locator(".bubble.assistant").nth(assistantCount);
  await expect(assistant.locator(".answer-run-spinner")).toHaveCount(0, { timeout: 90_000 });
  await expect(assistant).toContainText("Cedar Harbor", { timeout: 90_000 });
  await expect(assistant).toContainText("2026-09-30");
  await expect(assistant).toContainText("codex-browser-test-source.md");
  await expect(assistant.getByText("来源引用", { exact: true })).toBeVisible();
  await page.screenshot({
    path: resolve(process.cwd(), "..", ".codex-tmp", "browser-proof", "qa-real-retrieval.png"),
    fullPage: true
  });
});

test("QA honors a changed source scope across consecutive questions", async ({ page }) => {
  test.setTimeout(240_000);
  await page.goto("/");
  await loginIfRequired(page);
  await expect(page.locator(".workbench-shell")).toBeVisible();
  await selectWorkspace(page, workspaceName);
  await page.getByRole("button", { name: "笔记本：来源、对话与产物", exact: true }).click();

  // 在“来源”栏里只保留 frontend-source.md 的勾选
  const sourceChecks = page.locator(".sources-pane .source-row-check");
  const target = page.getByRole("checkbox", { name: "在问答中使用 frontend-source.md", exact: true });
  await expect(target).toBeChecked();
  for (const check of await sourceChecks.all()) {
    const label = await check.getAttribute("aria-label");
    if (label !== "在问答中使用 frontend-source.md" && await check.isChecked()) {
      await check.uncheck();
    }
  }
  await expect(page.locator(".composer-scope")).toContainText("已选 1 /");

  const first = await askQuestion(page,
    `What is the secondary keyword in the selected source? scoped-first-${Date.now()}`);
  await expect(first).toContainText("Indigo Atlas", { timeout: 90_000 });
  await expect(first).toContainText("frontend-source.md");
  await expect(first).not.toContainText("codex-browser-test-source.md");

  await scope.getByRole("button", { name: "frontend-source.md", exact: true }).click();
  await scope.getByRole("button", { name: "codex-browser-test-source.md", exact: true }).click();
  const second = await askQuestion(page,
    `Now what is the project codename in the newly selected source? scoped-second-${Date.now()}`);
  await expect(second).toContainText("Cedar Harbor", { timeout: 90_000 });
  await expect(second).toContainText("codex-browser-test-source.md");
  await expect(second).not.toContainText("frontend-source.md");
});

async function askQuestion(page: Page, question: string) {
  const assistantCount = await page.locator(".bubble.assistant").count();
  await page.locator(".composer-box textarea").fill(question);
  const sendButton = page.locator(".composer-send-button");
  await expect(sendButton).toBeEnabled();
  await sendButton.click();
  const assistant = page.locator(".bubble.assistant").nth(assistantCount);
  await expect(assistant.locator(".answer-run-spinner")).toHaveCount(0, { timeout: 90_000 });
  return assistant;
}

async function loginIfRequired(page: Page) {
  const form = page.locator(".auth-card");
  if (!await form.isVisible()) return;
  await form.locator('input[autocomplete="username"]').fill(credentials.username);
  await form.locator('input[autocomplete="current-password"]').fill(credentials.password);
  await form.locator('button[type="submit"]').click();
}

async function selectWorkspace(page: Page, name: string) {
  const trigger = page.locator(".workspace-switcher-trigger");
  if (await trigger.getByText(name, { exact: false }).isVisible().catch(() => false)) return;
  await trigger.click();
  await page.getByRole("searchbox", { name: "搜索工作台" }).fill(name);
  await page.getByRole("option", { name: new RegExp(name) }).click();
  await expect(trigger).toContainText(name);
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
