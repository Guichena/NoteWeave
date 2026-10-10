import { expect, test } from "@playwright/test";
import {
  ensureStudioVisible,
  loginIfRequired,
  openChat,
  useFixtureWorkspace
} from "./helpers";

/*
 * 原来的写法通过 /src/... 动态 import 直接调用 downloadBlob，只有 Vite 开发服务器能解析这个路径，
 * 打包后的生产构建跑不起来。这里改成走真实界面：打开一个已生成的产物，点它的下载按钮，
 * 断言浏览器真的收到了这次下载，以及文件名和内容都对得上。
 */
test("artifact file save reaches the browser download shelf", async ({ page }) => {
  test.setTimeout(300_000);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  await loginIfRequired(page);
  await useFixtureWorkspace(page, 1440);
  await openChat(page, 1440);
  await ensureStudioVisible(page);

  const readyOutput = page.locator('.artifact-output-row[data-state="ready"]').first();
  if (await readyOutput.count() === 0) {
    await generateArtifact(page);
  }
  await expect(readyOutput).toBeVisible({ timeout: 240_000 });
  await readyOutput.click();

  const downloadButton = page.getByRole("button", { name: /^下载 / }).first();
  await expect(downloadButton).toBeEnabled();
  const downloadEvent = page.waitForEvent("download");
  await downloadButton.click();

  const download = await downloadEvent;
  const fileName = download.suggestedFilename();
  expect(fileName).toMatch(/\.(md|markdown|pdf|html|json|txt|docx|pptx)$/i);
  const stream = await download.createReadStream();
  const chunks: Buffer[] = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk));
  const contents = Buffer.concat(chunks);
  expect(contents.byteLength).toBeGreaterThan(0);
  if (/\.(md|markdown|txt|json|html)$/i.test(fileName)) {
    expect(contents.toString("utf8").trim().length).toBeGreaterThan(0);
  }
});

/** 工作台里还没有可下载的产物时，先真实生成一个。 */
async function generateArtifact(page: import("@playwright/test").Page) {
  const skillCard = page.locator(".artifact-action-card").first();
  await expect(skillCard).toBeVisible({ timeout: 60_000 });
  await skillCard.click();
  const composer = page.locator(".artifact-composer-view");
  await expect(composer).toBeVisible();
  await composer.locator("textarea").fill(
    `Playwright 下载回归用产物，请输出一段简短的要点摘要。Marker: ${Date.now()}`
  );
  const created = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/artifact-jobs$/.test(response.url())
      && response.request().method() === "POST"
  );
  await composer.getByRole("button", { name: "生成产物", exact: true }).click();
  expect((await created).status()).toBe(200);
}
