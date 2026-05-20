const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const BASE_URL = process.env.NOTEWEAVE_BASE_URL || "http://127.0.0.1:18082";
const ADMIN_USERNAME = process.env.NOTEWEAVE_ADMIN_USERNAME || "admin";
const ADMIN_PASSWORD = process.env.NOTEWEAVE_ADMIN_PASSWORD || "NoteWeave123!";
const ALICE_USERNAME = process.env.NOTEWEAVE_ALICE_USERNAME || "alice";
const ALICE_PASSWORD = process.env.NOTEWEAVE_ALICE_PASSWORD || "NoteWeave123!";
const OUTPUT_PATH = path.join(__dirname, "last-run.json");

const OUTPUT = {
  startedAt: new Date().toISOString(),
  baseUrl: BASE_URL,
  runs: []
};

function issue(run, type, detail) {
  run.issues.push({ type, detail });
}

async function waitForAppIdle(page) {
  await page.waitForLoadState("domcontentloaded");
  await page.waitForTimeout(300);
  await page.waitForFunction(() => {
    const text = document.body ? document.body.innerText : "";
    return !text.includes("正在加载");
  }, null, { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(300);
}

async function clickIfVisible(page, selector, label, run) {
  const locator = page.locator(selector).first();
  if (await locator.count()) {
    if (await locator.isVisible()) {
      await locator.click();
      await waitForAppIdle(page);
      run.steps.push(`clicked:${label}`);
      return true;
    }
  }
  issue(run, "missing_control", label);
  return false;
}

async function fillIfExists(page, selector, value, label, run) {
  const locator = page.locator(selector).first();
  if (await locator.count()) {
    await locator.fill(value);
    run.steps.push(`filled:${label}`);
    return true;
  }
  issue(run, "missing_field", label);
  return false;
}

async function closeDrawerIfOpen(page, run) {
  const closeButton = page.locator('[data-action="close-drawer"]').first();
  if (await closeButton.count() && await closeButton.isVisible()) {
    await closeButton.click();
    await waitForAppIdle(page);
    run.steps.push("clicked:close-drawer");
  }
}

async function snapshot(page, run, label) {
  await waitForAppIdle(page);
  const h1 = await page.locator("h1").first().textContent().catch(() => "");
  const body = await page.locator("body").textContent().catch(() => "");
  run.snapshots.push({
    label,
    url: page.url(),
    h1: h1 || "",
    hasErrorState: body.includes("错误码") || body.includes("加载失败"),
    hasEmptyState: body.includes("暂无")
  });
}

async function login(page, username, password, run) {
  await page.goto(`${BASE_URL}/login`, { waitUntil: "domcontentloaded" });
  await waitForAppIdle(page);
  await page.locator('input[name="usernameOrEmail"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('#login-form button[type="submit"]').click();
  await page.waitForURL((url) => !url.pathname.endsWith("/login"), { timeout: 15000 });
  await waitForAppIdle(page);
  run.steps.push(`login:${username}`);
}

async function setupRun(browser, account) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
  const page = await context.newPage();
  const run = {
    account,
    steps: [],
    issues: [],
    snapshots: [],
    consoleErrors: [],
    requestFailures: []
  };

  page.on("console", (msg) => {
    if (msg.type() === "error") {
      run.consoleErrors.push(msg.text());
    }
  });

  page.on("pageerror", (error) => {
    run.consoleErrors.push(`pageerror:${error.message}`);
  });

  page.on("requestfailed", (request) => {
    run.requestFailures.push({
      url: request.url(),
      method: request.method(),
      failure: request.failure() ? request.failure().errorText : "unknown"
    });
  });

  page.on("response", (response) => {
    if (response.status() >= 400) {
      run.requestFailures.push({
        url: response.url(),
        method: response.request().method(),
        status: response.status()
      });
    }
  });

  return { context, page, run };
}

async function adminFlow(page, run) {
  await snapshot(page, run, "post-login");

  await clickIfVisible(page, '[data-nav="/spaces"]', "spaces-nav", run);
  await snapshot(page, run, "spaces");

  const enterButtons = page.locator('[data-action="enter-space"]');
  if (await enterButtons.count()) {
    await enterButtons.first().click();
    await waitForAppIdle(page);
    run.steps.push("clicked:first-enter-space");
  } else {
    issue(run, "missing_control", "first-enter-space");
  }
  await snapshot(page, run, "entered-space");

  await clickIfVisible(page, '.nav-link:has-text("Team Knowledge")', "team-knowledge-nav", run);
  await snapshot(page, run, "knowledge-list");
  await clickIfVisible(page, '[data-action="open-kb"]', "open-kb", run);
  await snapshot(page, run, "knowledge-detail");
  await fillIfExists(page, '#kb-search-form input[name="keyword"]', "policy", "kb-search-keyword", run);
  await clickIfVisible(page, '#kb-search-form button[type="submit"]', "kb-search-submit", run);
  await snapshot(page, run, "knowledge-search");

  await clickIfVisible(page, '.nav-link:has-text("Chat")', "chat-nav", run);
  await snapshot(page, run, "chat");
  await fillIfExists(page, '#chat-message-form textarea[name="content"]', "请总结当前空间的关键研究主题。", "chat-message", run);
  await clickIfVisible(page, '#chat-message-form button[type="submit"]', "chat-send", run);
  await page.waitForTimeout(2500);
  await snapshot(page, run, "chat-after-send");

  await clickIfVisible(page, '.nav-link:has-text("Artifacts")', "artifacts-nav", run);
  await snapshot(page, run, "artifacts");
  await clickIfVisible(page, '[data-action="open-artifact"]', "open-artifact", run);
  await snapshot(page, run, "artifact-detail");
  await clickIfVisible(page, '#distill-artifact-form button[type="submit"]', "artifact-distill-preview", run);
  await page.waitForTimeout(1200);
  await snapshot(page, run, "artifact-distill");

  await clickIfVisible(page, '.nav-link:has-text("Wiki")', "wiki-nav", run);
  await snapshot(page, run, "wiki");
  await clickIfVisible(page, '[data-action="select-wiki-page"]', "select-wiki-page", run);
  await snapshot(page, run, "wiki-selected");
  await fillIfExists(page, '#wiki-search-form input[name="keyword"]', "研究", "wiki-search-keyword", run);
  await clickIfVisible(page, '#wiki-search-form button[type="submit"]', "wiki-search-submit", run);
  await snapshot(page, run, "wiki-search");

  await clickIfVisible(page, '.nav-link:has-text("Memory")', "memory-nav", run);
  await snapshot(page, run, "memory");
  await fillIfExists(page, '#space-memory-form input[name="topic"]', "Playwright Smoke", "space-memory-topic", run);
  await fillIfExists(page, '#space-memory-form textarea[name="summary"]', "浏览器实测写入的一条空间记忆。", "space-memory-summary", run);
  await clickIfVisible(page, '#space-memory-form button[type="submit"]', "space-memory-submit", run);
  await snapshot(page, run, "memory-space-saved");

  await clickIfVisible(page, '.nav-link:has-text("Tasks")', "admin-tasks-nav", run);
  await snapshot(page, run, "admin-tasks");
  await clickIfVisible(page, '[data-action="open-admin-task"]', "open-admin-task", run);
  await snapshot(page, run, "admin-task-detail");
  await closeDrawerIfOpen(page, run);

  await clickIfVisible(page, '.nav-link:has-text("Health")', "admin-health-nav", run);
  await snapshot(page, run, "admin-health");
  await clickIfVisible(page, '[data-action="open-health-detail"]', "open-health-detail", run);
  await snapshot(page, run, "admin-health-detail");
  await closeDrawerIfOpen(page, run);

  await clickIfVisible(page, '.nav-link:has-text("Evaluation")', "admin-evaluation-nav", run);
  await snapshot(page, run, "admin-evaluation");

  await clickIfVisible(page, '.nav-link:has-text("Logs")', "admin-logs-nav", run);
  await snapshot(page, run, "admin-logs");
}

async function aliceFlow(page, run) {
  await snapshot(page, run, "post-login");

  await clickIfVisible(page, '.nav-link:has-text("Personal Research")', "personal-research-nav", run);
  await snapshot(page, run, "projects");
  await clickIfVisible(page, '[data-action="open-project"]', "open-project", run);
  await snapshot(page, run, "project-detail");

  await clickIfVisible(page, '.tab-link:has-text("Sources")', "project-sources-tab", run);
  await snapshot(page, run, "project-sources");
  await fillIfExists(page, '#source-text-form input[name="title"]', "Smoke Source", "text-source-title", run);
  await fillIfExists(page, '#source-text-form textarea[name="content"]', "这是浏览器实测写入的一条文本资料源。", "text-source-content", run);
  await clickIfVisible(page, '#source-text-form button[type="submit"]', "project-add-text-source-submit", run);
  await snapshot(page, run, "project-sources-added");

  await clickIfVisible(page, '.tab-link:has-text("Cards")', "project-cards-tab", run);
  await snapshot(page, run, "project-cards");

  await clickIfVisible(page, '.tab-link:has-text("Generate")', "project-generate-tab", run);
  await snapshot(page, run, "project-generate");
  await fillIfExists(page, '#project-generate-form input[name="topic"]', "Smoke Artifact Topic", "generate-topic", run);
  await clickIfVisible(page, '#project-generate-form button[type="submit"]', "project-generate-submit", run);
  await page.waitForTimeout(1500);
  await snapshot(page, run, "project-generate-submitted");

  await clickIfVisible(page, '.nav-link:has-text("Memory")', "memory-nav", run);
  await snapshot(page, run, "memory");
  await fillIfExists(page, '#user-memory-form input[name="topic"]', "Alice Preference", "user-memory-topic", run);
  await fillIfExists(page, '#user-memory-form textarea[name="summary"]', "偏好结构化、带证据的研究结论。", "user-memory-summary", run);
  await clickIfVisible(page, '#user-memory-form button[type="submit"]', "user-memory-submit", run);
  await snapshot(page, run, "memory-user-saved");
}

async function runAccount(browser, username, password, flow) {
  const { context, page, run } = await setupRun(browser, username);
  try {
    await login(page, username, password, run);
    await flow(page, run);
  } catch (error) {
    issue(run, "fatal", error.message);
  } finally {
    OUTPUT.runs.push(run);
    await context.close();
  }
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  try {
    await runAccount(browser, ADMIN_USERNAME, ADMIN_PASSWORD, adminFlow);
    await runAccount(browser, ALICE_USERNAME, ALICE_PASSWORD, aliceFlow);
  } finally {
    await browser.close();
  }
  OUTPUT.finishedAt = new Date().toISOString();
  fs.writeFileSync(OUTPUT_PATH, JSON.stringify(OUTPUT, null, 2));
  process.stdout.write(JSON.stringify(OUTPUT, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
