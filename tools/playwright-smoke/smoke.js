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
  await page.waitForTimeout(350);
  await page.waitForFunction(() => {
    const text = document.body ? document.body.innerText : "";
    return !text.includes("正在加载");
  }, null, { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(350);
}

async function api(page, method, path, body = null) {
  return page.evaluate(async ({ method, path, body }) => {
    const auth = JSON.parse(localStorage.getItem("noteweave.workspace.auth") || "{}");
    const response = await fetch(`/api/v1${path}`, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(auth.accessToken ? { Authorization: `${auth.tokenType || "Bearer"} ${auth.accessToken}` } : {})
      },
      body: body ? JSON.stringify(body) : undefined
    });
    const text = await response.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = null;
    }
    return { status: response.status, json, text };
  }, { method, path, body });
}

function pageItems(response) {
  return response?.json?.data?.items || response?.json?.data?.content || response?.json?.data || [];
}

async function currentWorkspace(page) {
  const spaces = await api(page, "GET", "/spaces?page=1&pageSize=100");
  const items = pageItems(spaces);
  const teamSpace = items.find((space) => space.type === "TEAM") || items[0];
  const personalSpace = items.find((space) => space.type === "PERSONAL") || items.find((space) => space.id !== teamSpace?.id) || teamSpace;
  if (!teamSpace) {
    throw new Error("No accessible space found.");
  }
  return { teamSpace, personalSpace };
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

async function clickGlobalNav(page, target, label, run) {
  const clicked = await clickIfVisible(page, `.global-rail [data-nav="${target}"]`, label, run);
  if (!clicked) {
    return false;
  }
  if (!page.url().endsWith(target)) {
    issue(run, "unexpected_route", `${label}: expected ${target}, got ${page.url()}`);
    return false;
  }
  return true;
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
  run.snapshots.push({
    label,
    url: page.url(),
    h1: h1 || "",
    hasWorkbenchShell: await page.locator(".workbench-shell").count() > 0,
    hasGlobalRail: await page.locator(".global-rail").count() > 0,
    hasContextRail: await page.locator(".context-rail").count() > 0,
    hasMainCanvas: await page.locator(".main-canvas").count() > 0,
    hasInspector: await page.locator(".inspector").count() > 0,
    hasErrorState: await page.locator(".error-state").count() > 0,
    hasEmptyState: await page.locator(".empty-state").count() > 0
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
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } });
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

async function ensureSmokeChatSession(page, spaceId) {
  const kbs = await api(page, "GET", `/team/spaces/${spaceId}/knowledge-bases`);
  const kbIds = pageItems(kbs).map((kb) => kb.id).filter(Boolean);
  if (!kbIds.length) {
    throw new Error(`No knowledge base found for space ${spaceId}.`);
  }
  const title = `Smoke Chat ${Date.now()}`;
  const created = await api(page, "POST", "/chat/sessions", {
    spaceId,
    sessionType: "TEAM_CHAT",
    sessionKind: "FORMAL",
    scopeType: "KNOWLEDGE_BASE",
    scopeIds: kbIds,
    title
  });
  if (created.status >= 400 || !created.json?.data?.id) {
    throw new Error(`Failed to create smoke chat session: ${created.text}`);
  }
  return created.json.data;
}

async function waitForChatCompletion(page, run) {
  await page.waitForFunction(() => {
    const canvas = document.querySelector(".chat-main-canvas");
    const text = canvas ? canvas.innerText : "";
    return text.includes("COMPLETED") || text.includes("FAILED") || text.includes("STOPPED");
  }, null, { timeout: 30000 }).catch(() => {
    issue(run, "timeout", "chat stream did not reach a terminal status within 30s");
  });
  await waitForAppIdle(page);
  const chatState = await page.locator(".chat-main-canvas").first().textContent().catch(() => "");
  if (chatState.includes("FAILED")) {
    issue(run, "chat_failed", "active smoke chat reached FAILED state");
  }
}

async function adminFlow(page, run) {
  const { teamSpace } = await currentWorkspace(page);
  const spaceId = teamSpace.id;
  await snapshot(page, run, "post-login");

  await clickGlobalNav(page, "/spaces", "spaces-nav", run);
  await snapshot(page, run, "spaces");

  await clickIfVisible(page, `[data-action="enter-space"][data-space-id="${spaceId}"]`, "enter-team-space", run);
  await snapshot(page, run, "entered-space");

  await clickGlobalNav(page, `/spaces/${spaceId}/team/knowledge-bases`, "team-knowledge-nav", run);
  await snapshot(page, run, "knowledge-list");
  await clickIfVisible(page, '[data-action="open-kb"]', "open-kb", run);
  await snapshot(page, run, "knowledge-detail");
  await fillIfExists(page, '#kb-search-form input[name="keyword"]', "policy", "kb-search-keyword", run);
  await clickIfVisible(page, '#kb-search-form button[type="submit"]', "kb-search-submit", run);
  await snapshot(page, run, "knowledge-search");

  const session = await ensureSmokeChatSession(page, spaceId);
  await clickGlobalNav(page, `/spaces/${spaceId}/workbench/chat`, "chat-nav", run);
  await clickIfVisible(page, `[data-action="select-session"][data-session-id="${session.id}"]`, "select-smoke-chat-session", run);
  await snapshot(page, run, "chat");
  await fillIfExists(page, '#chat-message-form textarea[name="content"]', "请用一句话概括当前知识库里关于新手引导或发布评审的关键结论。", "chat-message", run);
  await clickIfVisible(page, '#chat-message-form button[type="submit"]', "chat-send", run);
  await waitForChatCompletion(page, run);
  await snapshot(page, run, "chat-after-send");

  const loadCitation = page.locator('[data-action="load-message-citations"]').first();
  if (await loadCitation.count()) {
    await loadCitation.click();
    await waitForAppIdle(page);
    run.steps.push("clicked:load-message-citations");
  }
  const citationChip = page.locator('[data-action="open-citation"]').first();
  if (await citationChip.count()) {
    await citationChip.click();
    await waitForAppIdle(page);
    run.steps.push("clicked:open-citation");
    await snapshot(page, run, "chat-citation-inspector");
    await closeDrawerIfOpen(page, run);
  } else {
    run.steps.push("skipped:open-citation-no-citation-returned");
  }

  await clickGlobalNav(page, `/spaces/${spaceId}/artifacts`, "artifacts-nav", run);
  await snapshot(page, run, "artifacts");
  await clickIfVisible(page, '[data-action="open-artifact"]', "open-artifact", run);
  await snapshot(page, run, "artifact-detail");

  await clickGlobalNav(page, `/spaces/${spaceId}/wiki`, "wiki-nav", run);
  await snapshot(page, run, "wiki");
  await clickIfVisible(page, '[data-action="select-wiki-page"]', "select-wiki-page", run);
  await snapshot(page, run, "wiki-selected");
  await fillIfExists(page, '#wiki-search-form input[name="keyword"]', "研究", "wiki-search-keyword", run);
  await clickIfVisible(page, '#wiki-search-form button[type="submit"]', "wiki-search-submit", run);
  await snapshot(page, run, "wiki-search");

  await clickGlobalNav(page, `/spaces/${spaceId}/graph`, "graph-nav", run);
  await snapshot(page, run, "graph");
  await clickIfVisible(page, '[data-action="select-graph-node"]', "select-graph-node", run);
  await snapshot(page, run, "graph-node-selected");
  await clickIfVisible(page, '[data-action="open-graph-node-inspector"]', "open-graph-node-inspector", run);
  await snapshot(page, run, "graph-inspector");
  await closeDrawerIfOpen(page, run);

  await clickGlobalNav(page, `/spaces/${spaceId}/memory`, "memory-nav", run);
  await snapshot(page, run, "memory");
  await fillIfExists(page, '#space-memory-form input[name="topic"]', "Playwright Smoke", "space-memory-topic", run);
  await fillIfExists(page, '#space-memory-form textarea[name="summary"]', "浏览器实测写入的一条空间记忆。", "space-memory-summary", run);
  await clickIfVisible(page, '#space-memory-form button[type="submit"]', "space-memory-submit", run);
  await snapshot(page, run, "memory-space-saved");

  await clickGlobalNav(page, "/admin/tasks", "admin-tasks-nav", run);
  await snapshot(page, run, "admin-tasks");
  await clickIfVisible(page, '[data-action="open-admin-task"]', "open-admin-task", run);
  await snapshot(page, run, "admin-task-detail");
  await closeDrawerIfOpen(page, run);

  await clickGlobalNav(page, "/admin/health", "admin-health-nav", run);
  await snapshot(page, run, "admin-health");
  await clickIfVisible(page, '[data-action="open-health-detail"]', "open-health-detail", run);
  await snapshot(page, run, "admin-health-detail");
  await closeDrawerIfOpen(page, run);

  await clickGlobalNav(page, "/admin/evaluation", "admin-evaluation-nav", run);
  await snapshot(page, run, "admin-evaluation");

  await clickGlobalNav(page, "/admin/logs", "admin-logs-nav", run);
  await snapshot(page, run, "admin-logs");
}

async function aliceFlow(page, run) {
  const { personalSpace } = await currentWorkspace(page);
  const personalSpaceId = personalSpace.id;
  await snapshot(page, run, "post-login");

  await clickGlobalNav(page, `/spaces/${personalSpaceId}/personal/projects`, "personal-research-nav", run);
  await snapshot(page, run, "projects");
  await clickIfVisible(page, '[data-action="open-project"]', "open-project", run);
  await snapshot(page, run, "project-detail");

  await clickIfVisible(page, '.tab-link:has-text("Sources"), .tab-link:has-text("资料")', "project-sources-tab", run);
  await snapshot(page, run, "project-sources");
  await fillIfExists(page, '#source-text-form input[name="title"]', "Smoke Source", "text-source-title", run);
  await fillIfExists(page, '#source-text-form textarea[name="content"]', "这是浏览器实测写入的一条文本资料源。", "text-source-content", run);
  await clickIfVisible(page, '#source-text-form button[type="submit"]', "project-add-text-source-submit", run);
  await snapshot(page, run, "project-sources-added");

  await clickIfVisible(page, '.tab-link:has-text("Cards"), .tab-link:has-text("卡片")', "project-cards-tab", run);
  await snapshot(page, run, "project-cards");

  await clickIfVisible(page, '.tab-link:has-text("Generate"), .tab-link:has-text("生成")', "project-generate-tab", run);
  await snapshot(page, run, "project-generate");
  await fillIfExists(page, '#project-generate-form input[name="topic"]', "Smoke Artifact Topic", "generate-topic", run);
  await clickIfVisible(page, '#project-generate-form button[type="submit"]', "project-generate-submit", run);
  await page.waitForTimeout(1500);
  await snapshot(page, run, "project-generate-submitted");

  await clickGlobalNav(page, `/spaces/${personalSpaceId}/memory`, "memory-nav", run);
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
