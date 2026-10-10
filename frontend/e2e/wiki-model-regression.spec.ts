import { expect, test } from "@playwright/test";
import { ensureFixtureWikiPages, loginIfRequired, openChat, useFixtureWorkspace } from "./helpers";

const question = "Cedar Harbor 的计划发布日期是哪一天？它和 Indigo Atlas 这个关键词之间是什么关系？";

test("Wiki answer streams, renders evidence cards, and survives refresh", async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto("/");
  await loginIfRequired(page);
  // 用例自带资料：夹具工作台里的两份资料内容固定，Wiki 模式回答可以对着它们断言
  await useFixtureWorkspace(page, 1440);
  // Wiki 模式要命中正式知识页，先把两页互相链接的夹具页面准备好
  await ensureFixtureWikiPages(page, 1440);
  await openChat(page, 1440);

  await page.getByRole("radiogroup", { name: "回答模式" }).getByRole("radio", { name: "Wiki" }).click();
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
  await expect(assistant).toBeVisible({ timeout: 60_000 });
  await expect(assistant.locator(".answer-run-spinner")).toHaveCount(0, { timeout: 180_000 });
  await expect(assistant).not.toContainText(/生成失败|request timed out|ANSWER_STREAM_FAILED/i);
  // 页面关系、综合结论等内容收在折叠卡片里，展开后才能读到正文
  await expandMessageCards(assistant);
  const answerText = (await assistant.innerText()).trim();
  expect(answerText.length).toBeGreaterThan(80);
  expect(answerText).toMatch(/Cedar Harbor/);
  expect(answerText).toMatch(/Indigo Atlas/);
  expect(answerText).toMatch(/2026-09-30/);
  expect(answerText).not.toMatch(/没有可直接命中的正式页面/);
  // 回答下方的折叠卡片承载页面关系与来源引用
  expect(await assistant.locator(".message-card").count()).toBeGreaterThan(0);
  await expect(assistant).toContainText(/关键页面关系|页面关系|来源引用|来源回链/);
  await expect(page.locator(".chat-typing")).toHaveCount(0);
  expect(await hasHorizontalOverflow(page)).toBe(false);
  await page.screenshot({ path: "test-results/browser-proof/wiki-answer-desktop.png", fullPage: true });

  // 刷新后历史消息要从服务端恢复
  await page.reload();
  await expect(page.locator(".workbench-shell")).toBeVisible();
  await expect(page.locator(".bubble.user").filter({ hasText: "Cedar Harbor" }).last()).toBeVisible({
    timeout: 60_000
  });
  const restored = page.locator(".bubble.assistant").last();
  await expandMessageCards(restored);
  await expect(restored).toContainText(/Cedar Harbor/);
  await expect(restored).toContainText(/2026-09-30/);
  await expect(restored).toContainText(/关键页面关系|页面关系|来源引用|来源回链/);

  await page.setViewportSize({ width: 375, height: 812 });
  await expect(restored).toBeVisible();
  expect(await hasHorizontalOverflow(page)).toBe(false);
  await page.screenshot({ path: "test-results/browser-proof/wiki-answer-mobile.png", fullPage: true });
});

/** 展开回答里的折叠证据卡片。 */
async function expandMessageCards(bubble: import("@playwright/test").Locator) {
  for (const card of await bubble.locator(".message-card").all()) {
    const open = await card.evaluate((element) => (element as HTMLDetailsElement).open);
    if (!open) await card.locator("summary").click();
  }
}

async function hasHorizontalOverflow(page: import("@playwright/test").Page) {
  return page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
}
