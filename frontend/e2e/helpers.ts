import { expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/** 侧边栏在 899px 以下变成抽屉，和 styles/shell.css 里的断点保持一致。 */
export const SIDEBAR_DRAWER_MAX_WIDTH = 899;

/** 常驻夹具工作台：多个用例共用，避免每次回归都重新上传资料。 */
export const FIXTURE_WORKSPACE = "Playwright 常驻夹具工作台";

export const FIXTURE_SOURCES = [
  {
    title: "playwright-fixture-cedar",
    body: [
      "playwright-fixture-cedar",
      "",
      "本资料用于 Playwright 真实回归，内容固定不变。",
      "项目代号是 Cedar Harbor。",
      "计划发布日期是 2026-09-30。",
      "负责团队是检索平台组，交付方式为灰度发布。"
    ].join("\n")
  },
  {
    title: "playwright-fixture-indigo",
    body: [
      "playwright-fixture-indigo",
      "",
      "本资料用于 Playwright 真实回归，内容固定不变。",
      "次要关键词是 Indigo Atlas。",
      "该关键词只出现在这一份资料里，用于验证问答范围是否真的生效。"
    ].join("\n")
  }
] as const;

export function fixtureSourceFileName(title: string) {
  return `${title}.md`;
}

export function loadCredentials() {
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

/** 登录页出现时才填表；已有会话直接返回。 */
export async function loginIfRequired(page: Page) {
  const authCard = page.locator(".auth-card");
  if (!await authCard.isVisible()) {
    await expect(page.locator(".workbench-shell")).toBeVisible();
    return;
  }
  const credentials = loadCredentials();
  await authCard.locator('input[autocomplete="username"]').fill(credentials.username);
  await authCard.locator('input[autocomplete="current-password"]').fill(credentials.password);
  await authCard.locator('button[type="submit"], button').last().click();
  await expect(page.locator(".workbench-shell")).toBeVisible();
}

function isDrawerLayout(viewportWidth: number) {
  return viewportWidth <= SIDEBAR_DRAWER_MAX_WIDTH;
}

/** 窄屏下侧边栏是抽屉，操作侧边栏之前先把它打开。 */
export async function openSidebar(page: Page, viewportWidth: number) {
  if (!isDrawerLayout(viewportWidth)) return;
  if (await page.locator(".workbench-shell.is-mobile-nav-open").count() > 0) return;
  await page.getByRole("button", { name: "打开主导航" }).click();
  await expect(page.locator(".workbench-shell.is-mobile-nav-open")).toHaveCount(1);
  await expect(page.locator(".sidebar-conversations")).toBeVisible();
}

/** 侧边栏导航项按 title/aria-label 定位，点击后抽屉会自动收起。 */
export async function navigateFromSidebar(page: Page, name: string, viewportWidth: number) {
  await openSidebar(page, viewportWidth);
  await page.getByRole("button", { name, exact: true }).click();
}

/** 对话视图没有独立导航项，通过侧边栏里的会话条目进入。 */
export async function openChat(page: Page, viewportWidth: number) {
  await openSidebar(page, viewportWidth);
  const conversation = page.locator(".sidebar-conversation").first();
  if (await conversation.count() > 0) {
    await conversation.click();
  } else {
    await createConversation(page, `Playwright 会话 ${Date.now()}`, viewportWidth);
  }
  await expect(page.locator(".chat-panel")).toBeVisible();
}

export async function openAccountMenu(page: Page, viewportWidth: number) {
  await openSidebar(page, viewportWidth);
  const trigger = page.getByRole("button", { name: "账户与工作台菜单" });
  // 菜单里的主题切换不会自动收起菜单，重复调用时不要把它切回去。
  if (await trigger.getAttribute("aria-expanded") !== "true") await trigger.click();
  await expect(page.getByRole("menu", { name: "账户与工作台菜单" })).toBeVisible();
}

/** 新建工作台入口在账户菜单里（也可以从工作台切换器底部进入）。 */
export async function createWorkspace(page: Page, name: string, viewportWidth = 1280) {
  await openAccountMenu(page, viewportWidth);
  const createItem = page.getByRole("menuitem", { name: "新建工作台" });
  await expect(createItem).toBeEnabled();
  await createItem.click();
  const dialog = page.getByRole("dialog", { name: "创建研究工作台" });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("工作台名称").fill(name);
  await dialog.getByLabel("用途说明").fill("真实浏览器回归用工作台");
  await dialog.getByRole("button", { name: "创建工作台", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator(".workspace-switcher-trigger")).toContainText(name);
  return name;
}

export async function createConversation(page: Page, title: string, viewportWidth = 1280) {
  await openSidebar(page, viewportWidth);
  await page.getByRole("button", { name: "新建会话", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "新建独立会话" });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("会话名称").fill(title);
  await dialog.getByRole("button", { name: "创建会话", exact: true }).click();
  await expect(dialog).toHaveCount(0);
}

/** 切换到指定名称的工作台；不存在时返回 false。 */
export async function selectWorkspaceByName(page: Page, name: string, viewportWidth = 1280) {
  const trigger = page.locator(".workspace-switcher-trigger");
  await openSidebar(page, viewportWidth);
  if ((await trigger.innerText()).includes(name)) return true;
  await expect(trigger).toBeEnabled();
  await trigger.click();
  const dialog = page.getByRole("dialog", { name: "工作台切换器" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("searchbox", { name: "搜索工作台" }).fill(name);
  const option = dialog.locator(".popover-option")
    .filter({ has: page.getByText(name, { exact: true }) })
    .first();
  if (await option.count() === 0) {
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
    return false;
  }
  await option.click();
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toContainText(name);
  return true;
}

/**
 * 常驻夹具工作台：带两份内容固定的可检索资料。
 * 已存在就直接复用，缺资料时补齐，保证问答、来源面板等用例有真实数据可用。
 */
export async function useFixtureWorkspace(page: Page, viewportWidth = 1280) {
  const existed = await selectWorkspaceByName(page, FIXTURE_WORKSPACE, viewportWidth);
  if (!existed) {
    await createWorkspace(page, FIXTURE_WORKSPACE, viewportWidth);
  }
  await ensureFixtureSources(page, viewportWidth);
  return FIXTURE_WORKSPACE;
}

export async function ensureFixtureSources(page: Page, viewportWidth = 1280) {
  await navigateFromSidebar(page, "工作台资料库", viewportWidth);
  await expect(page.locator(".source-library-page")).toBeVisible();

  for (const source of FIXTURE_SOURCES) {
    const fileName = fixtureSourceFileName(source.title);
    const row = page.locator(".source-library-row").filter({ hasText: fileName });
    if (await row.count() > 0) continue;
    const disclosure = page.locator(".source-paste-disclosure");
    if (!await disclosure.locator("textarea").isVisible()) {
      await disclosure.locator("summary").click();
    }
    await disclosure.locator("textarea").fill(source.body);
    const uploadCompleted = page.waitForResponse((response) =>
      /\/api\/v2\/uploads\/[^/]+\/complete$/.test(response.url()) && response.status() === 200
    );
    await disclosure.locator(".primary-action").click();
    await uploadCompleted;
    await expect(row).toBeVisible({ timeout: 90_000 });
  }

  // 资料要走完解析、切片、向量化和写索引才能参与问答。
  for (const source of FIXTURE_SOURCES) {
    const row = page.locator(".source-library-row").filter({ hasText: fixtureSourceFileName(source.title) });
    await expect(row).toContainText("可检索", { timeout: 180_000 });
  }
}

export const FIXTURE_WIKI_PAGES = [
  {
    title: "Playwright Cedar Harbor 发布计划",
    body: [
      "Cedar Harbor 是检索平台组当前推进的项目代号。",
      "计划发布日期是 2026-09-30，采用灰度发布。",
      "相关页面：[[Playwright Indigo Atlas 关键词]]。"
    ].join("\n\n")
  },
  {
    title: "Playwright Indigo Atlas 关键词",
    body: [
      "Indigo Atlas 是本工作台资料里的次要关键词。",
      "它只出现在一份资料里，用于验证问答范围。",
      "相关页面：[[Playwright Cedar Harbor 发布计划]]。"
    ].join("\n\n")
  }
] as const;

/**
 * Wiki 模式要命中正式页面才能给出带页面关系的回答，
 * 所以这里先确认夹具工作台里有两页互相链接的知识页。
 */
export async function ensureFixtureWikiPages(page: Page, viewportWidth = 1280) {
  await navigateFromSidebar(page, "Wiki 知识库", viewportWidth);
  await expect(page.locator(".wiki-workbench")).toBeVisible();

  for (const wikiPage of FIXTURE_WIKI_PAGES) {
    const existing = page.locator(".wiki-page-card").filter({ hasText: wikiPage.title });
    if (await existing.count() > 0) continue;

    if (await page.locator(".wiki-empty-workbench").isVisible()) {
      const manual = page.locator(".wiki-empty-manual");
      if (!await manual.locator("form").isVisible()) await manual.locator("summary").click();
      await page.getByLabel("首个 Wiki 页面标题").fill(wikiPage.title);
      await page.getByLabel("首个 Wiki 页面正文").fill(wikiPage.body);
      const created = waitForKnowledgeItemCreated(page);
      await page.getByRole("button", { name: "创建首个 Wiki 页面" }).click();
      await created;
    } else {
      await page.getByRole("button", { name: /管理/ }).click();
      const manage = page.getByRole("dialog", { name: "知识库管理" });
      await expect(manage).toBeVisible();
      await manage.getByRole("tab", { name: "补页 / 修正" }).click();
      await manage.getByLabel("页面标题").fill(wikiPage.title);
      await manage.getByLabel("页面正文").fill(wikiPage.body);
      const created = waitForKnowledgeItemCreated(page);
      await manage.getByRole("button", { name: "提交补页 / 修正文案" }).click();
      await created;
      // 遮罩和抽屉头部的图标按钮同名，必须点抽屉内的那个，否则会被抽屉挡住
      await manage.getByRole("button", { name: "关闭知识库管理" }).click();
      await expect(manage).toHaveCount(0);
    }
    await expect(page.locator(".wiki-page-card").filter({ hasText: wikiPage.title })).toBeVisible({
      timeout: 60_000
    });
  }
}

function waitForKnowledgeItemCreated(page: Page) {
  return page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/knowledge-items$/.test(response.url())
      && response.request().method() === "POST"
      && response.status() === 200
  );
}

export async function expectNoHorizontalOverflow(page: Page) {
  const metrics = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth
  }));
  expect(metrics.documentWidth).toBeLessThanOrEqual(metrics.viewportWidth);
}

export async function setTheme(page: Page, expected: "light" | "dark", viewportWidth = 1280) {
  const root = page.locator("html");
  for (let attempt = 0; attempt < 2; attempt += 1) {
    if (await root.getAttribute("data-theme") === expected) break;
    await openAccountMenu(page, viewportWidth);
    await page.getByRole("menuitem", { name: "切换明暗主题" }).click();
  }
  await expect(root).toHaveAttribute("data-theme", expected);
  await closeSidebarOverlays(page);
}

/** 收起账户菜单与移动端抽屉，让页面回到可测量的常态。 */
export async function closeSidebarOverlays(page: Page) {
  // 抽屉收起时侧边栏是 visibility: hidden，按 role 查不到，这里用类名定位。
  const accountTrigger = page.locator(".account-trigger");
  if (await accountTrigger.count() > 0 && await accountTrigger.getAttribute("aria-expanded") === "true") {
    await page.keyboard.press("Escape");
    await expect(accountTrigger).toHaveAttribute("aria-expanded", "false");
  }
  if (await page.locator(".workbench-shell.is-mobile-nav-open").count() > 0) {
    await page.keyboard.press("Escape");
    await expect(page.locator(".workbench-shell.is-mobile-nav-open")).toHaveCount(0);
    // 抽屉滑出有 220ms 过渡，等它真的离开视口
    await expect.poll(async () => page.evaluate(() => (
      document.querySelector<HTMLElement>(".app-sidebar")?.getBoundingClientRect().right ?? 0
    ))).toBeLessThanOrEqual(0.5);
  }
}

/** 宽屏下产物栏常驻；较窄时通过打开产物抽屉打开。 */
export async function ensureStudioVisible(page: Page) {
  const toggle = page.getByRole("button", { name: "打开产物", exact: true });
  const studio = page.locator(".studio-pane .artifact-rail");
  if (!await studio.isVisible()) await toggle.click();
  await expect(studio).toBeVisible({ timeout: 30_000 });
}
