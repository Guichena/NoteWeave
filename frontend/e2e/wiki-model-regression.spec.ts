import { expect, test, type Page } from "@playwright/test";

const workspaceId = "46ed460c-c240-42c9-8152-2595cdc21ef4";
const conversationId = "842e6577-a61e-47fb-9661-8d03c7d86b31";
const question = "NoteWeave 的资料、会话、研究、Wiki、记忆与产物之间是什么关系？";

test("Wiki answer streams, renders evidence cards, and survives refresh", async ({ page }) => {
  test.setTimeout(180_000);
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto("/");
  await loginIfRequired(page);

  await page.evaluate(({ workspaceId, conversationId }) => {
    sessionStorage.setItem("noteweave.workspace.selection", JSON.stringify({ workspaceId, conversationId }));
  }, { workspaceId, conversationId });
  await page.reload();
  await expect(page.locator(".workbench-shell")).toBeVisible();
  await expect.poll(() => page.evaluate(() => sessionStorage.getItem("noteweave.workspace.selection")))
    .toContain(workspaceId);

  await page.getByRole("button", { name: /回答模式：/ }).click();
  await page.getByRole("menuitemradio", { name: /^Wiki/ }).click();
  const assistantCount = await page.locator(".bubble.assistant").count();
  await page.locator(".composer-box textarea").fill(question);
  const created = page.waitForResponse((response) =>
    /\/api\/v2\/conversations\/[^/]+\/messages$/.test(response.url())
      && response.request().method() === "POST"
  );
  await page.getByRole("button", { name: "发送到 Wiki" }).click();
  const createdResponse = await created;
  expect(createdResponse.status()).toBe(200);

  const assistant = page.locator(".bubble.assistant").nth(assistantCount);
  await expect(assistant).toBeVisible();
  await expect(assistant.locator(".answer-run-spinner")).toHaveCount(0, { timeout: 120_000 });
  await expect(assistant).not.toContainText(/生成失败|request timed out|ANSWER_STREAM_FAILED/i);
  const answerText = (await assistant.innerText()).trim();
  expect(answerText.length).toBeGreaterThan(80);
  expect(answerText).toMatch(/资料|来源/);
  expect(answerText).toMatch(/Wiki|知识/);
  expect(answerText).toMatch(/记忆/);
  expect(answerText).toMatch(/产物|成果/);
  expect(await assistant.locator(".message-card").count()).toBeGreaterThan(0);
  await expect(assistant).toContainText(/关键页面关系|页面关系/);
  await expect(assistant).toContainText(/来源引用|来源回链/);
  await expect(page.locator(".chat-typing")).toHaveCount(0);
  expect(await hasHorizontalOverflow(page)).toBe(false);
  await page.screenshot({ path: "test-results/wiki-qwen-desktop.png", fullPage: true });

  await page.reload();
  await expect(page.locator(".workbench-shell")).toBeVisible();
  await expect(page.locator(".bubble.user").filter({ hasText: question }).last()).toBeVisible();
  const restored = page.locator(".bubble.assistant").last();
  await expect(restored).toContainText(/Wiki|知识/);
  await expect(restored).toContainText(/来源引用|来源回链/);

  await page.setViewportSize({ width: 375, height: 812 });
  await expect(restored).toBeVisible();
  expect(await hasHorizontalOverflow(page)).toBe(false);
  await page.screenshot({ path: "test-results/wiki-qwen-mobile.png", fullPage: true });
});

async function loginIfRequired(page: Page) {
  const authCard = page.locator(".auth-card");
  if (!await authCard.isVisible()) return;
  const username = process.env.NOTEWEAVE_E2E_USERNAME;
  const password = process.env.NOTEWEAVE_E2E_PASSWORD;
  if (!username || !password) throw new Error("Missing NOTEWEAVE_E2E_USERNAME/PASSWORD");
  await authCard.locator('input[autocomplete="username"]').fill(username);
  await authCard.locator('input[autocomplete="current-password"]').fill(password);
  await authCard.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.locator(".workbench-shell")).toBeVisible();
}

async function hasHorizontalOverflow(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
}
