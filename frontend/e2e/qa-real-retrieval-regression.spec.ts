import { expect, test, type Locator, type Page } from "@playwright/test";
import { resolve } from "node:path";
import {
  FIXTURE_SOURCES,
  fixtureSourceFileName,
  loginIfRequired,
  openChat,
  useFixtureWorkspace
} from "./helpers";

const CEDAR_FILE = fixtureSourceFileName(FIXTURE_SOURCES[0].title);
const INDIGO_FILE = fixtureSourceFileName(FIXTURE_SOURCES[1].title);
const proofDir = resolve(process.cwd(), "test-results", "browser-proof");

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  await loginIfRequired(page);
  // 用例自带资料：夹具工作台里的两份资料内容固定，可以断言真实的检索结果与引用。
  await useFixtureWorkspace(page, 1440);
  await openChat(page, 1440);
  await page.getByRole("radiogroup", { name: "回答模式" }).getByRole("radio", { name: "问答" }).click();
  await expect(page.locator(".sources-pane")).toBeVisible();
});

test("QA retrieves an indexed workspace source and renders its citation", async ({ page }) => {
  test.setTimeout(180_000);
  await setQaScope(page, [CEDAR_FILE, INDIGO_FILE]);

  const marker = `qa-index-regression-${Date.now()}`;
  const assistant = await askQuestion(
    page,
    `根据工作台资料回答：项目代号是什么？计划发布日期是哪一天？${marker}`
  );
  await expect(assistant).toContainText("Cedar Harbor", { timeout: 120_000 });
  await expect(assistant).toContainText("2026-09-30");
  await expect(assistant).toContainText(CEDAR_FILE);
  await expect(assistant.getByText("来源引用", { exact: true })).toBeVisible();
  await page.screenshot({ path: resolve(proofDir, "qa-real-retrieval.png"), fullPage: true });
});

test("QA honors a changed source scope across consecutive questions", async ({ page }) => {
  test.setTimeout(300_000);

  // 只保留 indigo 这一份资料，验证问答范围真的收窄
  await setQaScope(page, [INDIGO_FILE]);
  await expect(page.locator(".composer-scope")).toContainText("已选 1 /");
  const first = await askQuestion(page, `选中资料里的次要关键词是什么？scoped-first-${Date.now()}`);
  await expect(first).toContainText("Indigo Atlas", { timeout: 120_000 });
  await expect(first).toContainText(INDIGO_FILE);
  await expect(first).not.toContainText(CEDAR_FILE);

  // 换成只保留 cedar，同一个会话里的下一问必须跟着换引用
  await setQaScope(page, [CEDAR_FILE]);
  await expect(page.locator(".composer-scope")).toContainText("已选 1 /");
  const second = await askQuestion(page, `新选中的资料里项目代号是什么？scoped-second-${Date.now()}`);
  await expect(second).toContainText("Cedar Harbor", { timeout: 120_000 });
  await expect(second).toContainText(CEDAR_FILE);
  await expect(second).not.toContainText(INDIGO_FILE);
});

/** 把问答范围精确设置成给定的几份资料。 */
async function setQaScope(page: Page, keepFileNames: string[]) {
  const checks = page.locator(".sources-pane .source-row-check");
  await expect(checks.first()).toBeAttached();
  const rows: Array<{ check: Locator; keep: boolean }> = [];
  for (const check of await checks.all()) {
    const label = await check.getAttribute("aria-label");
    const fileName = label?.replace(/^在问答中使用\s*/, "") ?? "";
    rows.push({ check, keep: keepFileNames.includes(fileName) });
  }
  // 面板不允许把最后一份来源取消掉，所以先勾上要保留的，再取消其余的。
  for (const row of rows.filter((item) => item.keep)) {
    if (!await row.check.isChecked()) await row.check.check();
  }
  for (const row of rows.filter((item) => !item.keep)) {
    if (await row.check.isChecked()) await row.check.uncheck();
  }
}

async function askQuestion(page: Page, question: string): Promise<Locator> {
  const assistantCount = await page.locator(".bubble.assistant").count();
  await page.locator(".composer-box textarea").fill(question);
  const sendButton = page.locator(".composer-send-button");
  await expect(sendButton).toBeEnabled();
  await sendButton.click();
  const assistant = page.locator(".bubble.assistant").nth(assistantCount);
  await expect(assistant).toBeVisible({ timeout: 60_000 });
  await expect(assistant.locator(".answer-run-spinner")).toHaveCount(0, { timeout: 120_000 });
  await expect(page.locator(".chat-typing")).toHaveCount(0, { timeout: 120_000 });
  return assistant;
}
