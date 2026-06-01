const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const BASE_URL = process.env.NOTEWEAVE_BASE_URL || "http://127.0.0.1:18082";
const ADMIN_USERNAME = process.env.NOTEWEAVE_ADMIN_USERNAME || "admin";
const ADMIN_PASSWORD = process.env.NOTEWEAVE_ADMIN_PASSWORD || "NoteWeave123!";
const ALICE_USERNAME = process.env.NOTEWEAVE_ALICE_USERNAME || "alice";
const ALICE_PASSWORD = process.env.NOTEWEAVE_ALICE_PASSWORD || "NoteWeave123!";
const LAST_RUN_PATH = path.join(__dirname, "last-run.json");
const OUTPUT_PATH = path.join(__dirname, "visual-audit-last-run.json");
const SCREENSHOT_DIR = path.resolve(__dirname, "..", "..", "target", "ui-shots", "visual-audit");

function buildOutputSummary(output) {
  return {
    startedAt: output.startedAt,
    baseUrl: output.baseUrl,
    screenshotDir: output.screenshotDir,
    outputPath: OUTPUT_PATH,
    auditedPages: output.runs.reduce((count, run) => count + run.pages.length, 0),
    issueCount: output.issues.length,
    runs: output.runs.map((run) => ({
      account: run.account,
      pages: run.pages.length,
      issues: run.issues.length
    })),
    issues: output.issues
  };
}

const MOJIBAKE_PATTERN = /�|姝|鐮|璧|鍔|浣|缂|銆|绋|鍗|鐢|氭|曞|忓|勭|熸|栨|犺|/;

const MODAL_FORM_BY_LABEL = {
  "modal-create-space": "create-space-form",
  "modal-create-session": "create-session-form",
  "modal-create-kb": "create-kb-form",
  "modal-upload-document": "upload-document-form",
  "modal-space-memory": "space-memory-form",
  "modal-create-project": "create-project-form",
  "modal-source-file": "source-file-form",
  "modal-source-url": "source-url-form",
  "modal-source-text": "source-text-form",
  "modal-project-generate": "project-generate-form",
  "modal-studio-task": "studio-task-form",
  "modal-user-memory": "user-memory-form",
  "modal-artifact-edit": "artifact-edit-form",
  "modal-distill-artifact": "distill-artifact-form",
  "modal-publish-artifact-wiki": "publish-artifact-wiki-form",
  "modal-wiki-edit": "wiki-edit-form",
  "modal-eval-case": "eval-case-form",
  "modal-eval-run": "eval-run-form"
};

const MODAL_ACTION_BY_LABEL = {
  "modal-chat-artifact": "open-chat-artifact-dialog",
  "modal-wiki-create": "open-wiki-create-form"
};

function uniqueSnapshots(account) {
  const lastRun = JSON.parse(fs.readFileSync(LAST_RUN_PATH, "utf8"));
  const run = lastRun.runs.find((item) => item.account === account);
  if (!run) {
    return [];
  }
  const seen = new Set();
  return run.snapshots.filter((snapshot) => {
    const key = `${snapshot.label}:${snapshot.url}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

async function waitForAppIdle(page) {
  await page.waitForLoadState("domcontentloaded");
  await page.waitForTimeout(350);
  await page.waitForFunction(() => {
    const text = document.body ? document.body.innerText : "";
    return !text.includes("正在加载") && !text.includes("姝ｅ湪鍔犺浇");
  }, null, { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(350);
}

async function login(page, username, password) {
  await page.goto(`${BASE_URL}/login`, { waitUntil: "domcontentloaded" });
  await waitForAppIdle(page);
  await page.locator('input[name="usernameOrEmail"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.locator('#login-form button[type="submit"]').click();
  await page.waitForURL((url) => !url.pathname.endsWith("/login"), { timeout: 15000 });
  await waitForAppIdle(page);
}

async function inspectPage(page) {
  return page.evaluate((patternSource) => {
    const pattern = new RegExp(patternSource);
    const bodyText = document.body ? document.body.innerText : "";
    const firstText = (...values) => {
      for (const value of values) {
        const text = String(value || "").trim();
        if (text) {
          return text;
        }
      }
      return "";
    };
    const accessibleName = (el) => firstText(
      el.getAttribute("aria-label"),
      el.getAttribute("title"),
      el.innerText,
      el.textContent
    );
    const isVisible = (el) => {
      const style = window.getComputedStyle(el);
      const rect = el.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden";
    };
    const hasControlLabel = (control) => {
      if (control.type === "hidden") {
        return true;
      }
      if (control.getAttribute("aria-label") || control.getAttribute("aria-labelledby") || control.getAttribute("title")) {
        return true;
      }
      if (control.id && document.querySelector(`label[for="${CSS.escape(control.id)}"]`)) {
        return true;
      }
      return Boolean(control.closest("label,.field,.topbar-field,.checkbox-line"));
    };
    const suspiciousLines = bodyText
      .split(/\n+/)
      .map((line) => line.trim())
      .filter((line) => line && pattern.test(line))
      .slice(0, 12);
    const unnamedInteractive = Array.from(document.querySelectorAll('button,a[href],[role="button"]'))
      .filter(isVisible)
      .filter((el) => !accessibleName(el))
      .slice(0, 12)
      .map((el) => ({
        tag: el.tagName.toLowerCase(),
        className: String(el.className || "").slice(0, 120),
        action: el.getAttribute("data-action") || "",
        html: el.outerHTML.slice(0, 160)
      }));
    const unlabeledFields = Array.from(document.querySelectorAll("input,textarea,select"))
      .filter(isVisible)
      .filter((el) => !hasControlLabel(el))
      .slice(0, 12)
      .map((el) => ({
        tag: el.tagName.toLowerCase(),
        name: el.getAttribute("name") || "",
        type: el.getAttribute("type") || "",
        html: el.outerHTML.slice(0, 160)
      }));
    const ids = Array.from(document.querySelectorAll("[id]"))
      .map((el) => el.id)
      .filter(Boolean);
    const duplicateIds = Array.from(new Set(ids.filter((id, index) => ids.indexOf(id) !== index))).slice(0, 12);
    const oversizedText = Array.from(document.querySelectorAll("h2,h3,button,a,.context-list-title,.message,.panel,.metric"))
      .filter(isVisible)
      .map((el) => ({
        tag: el.tagName.toLowerCase(),
        className: String(el.className || "").slice(0, 120),
        text: accessibleName(el).slice(0, 120),
        fontSize: Number.parseFloat(window.getComputedStyle(el).fontSize)
      }))
      .filter((item) => item.fontSize > 24)
      .slice(0, 12);
    const overflowing = Array.from(document.querySelectorAll("body *"))
      .filter((el) => {
        const style = window.getComputedStyle(el);
        if (style.display === "none" || style.visibility === "hidden") {
          return false;
        }
        const rect = el.getBoundingClientRect();
        if (rect.width < 24 || rect.height < 12) {
          return false;
        }
        return el.scrollWidth > el.clientWidth + 4 && style.overflowX === "visible";
      })
      .slice(0, 12)
      .map((el) => ({
        tag: el.tagName.toLowerCase(),
        className: String(el.className || "").slice(0, 120),
        text: String(el.innerText || el.textContent || "").trim().slice(0, 120),
        clientWidth: el.clientWidth,
        scrollWidth: el.scrollWidth
      }));
    const shell = document.querySelector(".workbench-shell");
    const rail = document.querySelector(".context-rail");
    return {
      title: document.title,
      h1: document.querySelector("h1")?.innerText || "",
      bodyLength: bodyText.length,
      contextRailCollapsed: Boolean(shell?.classList.contains("context-rail-collapsed")),
      contextRailWidth: rail ? Math.round(rail.getBoundingClientRect().width) : null,
      hasMojibake: suspiciousLines.length > 0,
      suspiciousLines,
      unnamedInteractive,
      unlabeledFields,
      duplicateIds,
      oversizedText,
      hasModalDialog: document.querySelector(".modal-dialog") !== null,
      hasHorizontalOverflow: document.documentElement.scrollWidth > window.innerWidth + 4,
      viewportWidth: window.innerWidth,
      scrollWidth: document.documentElement.scrollWidth,
      overflowing
    };
  }, MOJIBAKE_PATTERN.source);
}

async function openInlineForm(page, formId) {
  const visibleForm = page.locator(`form#${formId}`).filter({ visible: true }).first();
  if (await visibleForm.count()) {
    return true;
  }
  const trigger = page.locator(`[data-action="open-inline-form"][data-form-id="${formId}"]`).filter({ visible: true }).first();
  if (!await trigger.count()) {
    return false;
  }
  await trigger.click();
  await waitForAppIdle(page);
  return await page.locator(`.modal-dialog form#${formId}`).filter({ visible: true }).count() > 0;
}

function wikiSpaceIdFromUrl(url) {
  const match = String(url || "").match(/\/spaces\/(\d+)\/wiki\b/);
  return match ? Number(match[1]) : null;
}

async function createAuditWikiDraft(page, snapshot) {
  const spaceId = wikiSpaceIdFromUrl(snapshot.url);
  if (!spaceId) {
    return false;
  }
  const title = `Visual Audit Draft ${Date.now()}`;
  const result = await page.evaluate(async ({ spaceId, title }) => {
    const auth = JSON.parse(localStorage.getItem("noteweave.workspace.auth") || "null");
    const headers = {
      Accept: "application/json",
      "Content-Type": "application/json"
    };
    if (auth?.accessToken) {
      headers.Authorization = `${auth.tokenType || "Bearer"} ${auth.accessToken}`;
    }
    const response = await fetch(`/api/v1/team/spaces/${spaceId}/wiki-pages`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        title,
        content: `# ${title}\n\nTemporary draft created by visual audit to verify the Wiki edit surface.`
      })
    });
    const payload = await response.json().catch(() => null);
    return {
      ok: response.ok && payload?.success !== false,
      pageId: payload?.data?.id || null,
      status: response.status
    };
  }, { spaceId, title });
  if (!result.ok || !result.pageId) {
    return false;
  }
  await page.goto(`${BASE_URL}/spaces/${spaceId}/wiki`, { waitUntil: "domcontentloaded" });
  await waitForAppIdle(page);
  const pageButton = page.locator(`[data-action="select-wiki-page"][data-page-id="${result.pageId}"]`).filter({ visible: true }).first();
  if (await pageButton.count()) {
    await pageButton.click();
    await waitForAppIdle(page);
  }
  return openInlineForm(page, "wiki-edit-form");
}

async function restoreContextRailState(page, snapshot) {
  const hasStoredState = Object.prototype.hasOwnProperty.call(snapshot, "contextRailCollapsed");
  const shouldBeCollapsed = hasStoredState
    ? Boolean(snapshot.contextRailCollapsed)
    : snapshot.label === "context-rail-collapsed";
  if (!hasStoredState && snapshot.label !== "context-rail-collapsed") {
    return true;
  }
  const current = await page.evaluate(() => {
    const shell = document.querySelector(".workbench-shell");
    const rail = document.querySelector(".context-rail");
    return {
      hasShell: Boolean(shell),
      collapsed: Boolean(shell?.classList.contains("context-rail-collapsed")),
      railWidth: rail ? Math.round(rail.getBoundingClientRect().width) : null
    };
  }).catch(() => ({ hasShell: false, collapsed: false, railWidth: null }));
  if (!current.hasShell || current.collapsed === shouldBeCollapsed) {
    return true;
  }
  const toggle = page.locator('[data-action="toggle-context-rail"]').filter({ visible: true }).first();
  if (!await toggle.count()) {
    return false;
  }
  await toggle.click();
  await waitForAppIdle(page);
  if (!shouldBeCollapsed) {
    return true;
  }
  const restored = await page.evaluate(() => {
    const shell = document.querySelector(".workbench-shell");
    const rail = document.querySelector(".context-rail");
    const content = document.querySelector(".context-rail-content");
    return {
      collapsed: Boolean(shell?.classList.contains("context-rail-collapsed")),
      railWidth: rail ? Math.round(rail.getBoundingClientRect().width) : null,
      contentDisplay: content ? getComputedStyle(content).display : null
    };
  });
  return restored.collapsed && restored.railWidth !== null && restored.railWidth <= 72 && restored.contentDisplay === "none";
}

async function restoreSnapshotState(page, snapshot) {
  if (!await restoreContextRailState(page, snapshot)) {
    return false;
  }
  if (snapshot.label === "modal-wiki-edit") {
    if (await openInlineForm(page, "wiki-edit-form")) {
      return true;
    }
    return createAuditWikiDraft(page, snapshot);
  }
  if (snapshot.label === "modal-distill-artifact") {
    return openInlineForm(page, "distill-artifact-form");
  }
  if (snapshot.label === "artifact-citation-inspector" || snapshot.label === "project-card-citation-inspector" || snapshot.label === "chat-citation-inspector") {
    const load = page.locator('[data-action="load-message-citations"]').filter({ visible: true }).first();
    if (snapshot.label === "chat-citation-inspector" && await load.count()) {
      await load.click();
      await waitForAppIdle(page);
    }
    const trigger = page.locator('[data-action="open-citation"]').filter({ visible: true }).first();
    if (!await trigger.count()) {
      return false;
    }
    await trigger.click();
    await waitForAppIdle(page);
    return await page.locator(".modal-dialog").filter({ visible: true }).count() > 0;
  }
  if (snapshot.label === "artifact-distill-preview" || snapshot.label === "artifact-distill-confirmed") {
    if (!await openInlineForm(page, "distill-artifact-form")) {
      return false;
    }
    const submit = page.locator('#distill-artifact-form button[type="submit"]').filter({ visible: true }).first();
    if (!await submit.count()) {
      return false;
    }
    await submit.click();
    await waitForAppIdle(page);
    if (snapshot.label === "artifact-distill-preview") {
      return await page.locator(".modal-dialog").filter({ visible: true }).count() > 0;
    }
    const confirm = page.locator('[data-action="confirm-distill"]').filter({ visible: true }).first();
    if (!await confirm.count()) {
      return false;
    }
    await confirm.click();
    await waitForAppIdle(page);
    return await page.locator(".modal-dialog").filter({ visible: true }).count() > 0;
  }
  const formId = MODAL_FORM_BY_LABEL[snapshot.label];
  if (formId) {
    const trigger = page.locator(`[data-action="open-inline-form"][data-form-id="${formId}"]`).filter({ visible: true }).first();
    if (!await trigger.count()) {
      return false;
    }
    await trigger.click();
    await waitForAppIdle(page);
    return await page.locator(".modal-dialog").filter({ visible: true }).count() > 0;
  }
  const action = MODAL_ACTION_BY_LABEL[snapshot.label];
  if (action) {
    const trigger = page.locator(`[data-action="${action}"]`).filter({ visible: true }).first();
    if (!await trigger.count()) {
      return false;
    }
    await trigger.click();
    await waitForAppIdle(page);
    return await page.locator(".modal-dialog").filter({ visible: true }).count() > 0;
  }
  return true;
}

async function auditAccount(browser, account, username, password) {
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 }, deviceScaleFactor: 1 });
  const page = await context.newPage();
  const issues = [];
  const pages = [];
  page.on("console", (msg) => {
    if (msg.type() === "error") {
      issues.push({ type: "console_error", account, detail: msg.text() });
    }
  });
  page.on("pageerror", (error) => {
    issues.push({ type: "page_error", account, detail: error.message });
  });
  page.on("response", (response) => {
    if (response.status() >= 400) {
      issues.push({ type: "request_failure", account, detail: `${response.status()} ${response.request().method()} ${response.url()}` });
    }
  });

  await login(page, username, password);
  fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
  const snapshots = uniqueSnapshots(account);
  for (const [index, snapshot] of snapshots.entries()) {
    await page.goto(snapshot.url, { waitUntil: "domcontentloaded" });
    await waitForAppIdle(page);
    const restored = await restoreSnapshotState(page, snapshot);
    const inspection = await inspectPage(page);
    const fileName = `${String(index + 1).padStart(2, "0")}-${account}-${snapshot.label.replace(/[^a-z0-9_-]+/gi, "-")}.png`;
    const screenshot = path.join(SCREENSHOT_DIR, fileName);
    await page.screenshot({ path: screenshot, fullPage: !inspection.hasModalDialog });
    if (inspection.hasMojibake) {
      issues.push({ type: "mojibake", account, label: snapshot.label, detail: inspection.suspiciousLines });
    }
    if (inspection.hasHorizontalOverflow) {
      issues.push({
        type: "horizontal_overflow",
        account,
        label: snapshot.label,
        detail: `${inspection.scrollWidth}px > ${inspection.viewportWidth}px`
      });
    }
    if (inspection.overflowing.length) {
      issues.push({ type: "element_overflow", account, label: snapshot.label, detail: inspection.overflowing });
    }
    if (inspection.unnamedInteractive.length) {
      issues.push({ type: "unnamed_interactive", account, label: snapshot.label, detail: inspection.unnamedInteractive });
    }
    if (inspection.unlabeledFields.length) {
      issues.push({ type: "unlabeled_field", account, label: snapshot.label, detail: inspection.unlabeledFields });
    }
    if (inspection.duplicateIds.length) {
      issues.push({ type: "duplicate_id", account, label: snapshot.label, detail: inspection.duplicateIds });
    }
    if (inspection.oversizedText.length) {
      issues.push({ type: "oversized_text", account, label: snapshot.label, detail: inspection.oversizedText });
    }
    if (!restored) {
      issues.push({ type: "snapshot_state_not_restored", account, label: snapshot.label });
    }
    pages.push({ account, label: snapshot.label, url: snapshot.url, screenshot, inspection });
  }
  await context.close();
  return { account, pages, issues };
}

async function main() {
  if (!fs.existsSync(LAST_RUN_PATH)) {
    throw new Error("Run npm run smoke before visual-audit so snapshot URLs are available.");
  }
  const browser = await chromium.launch({ headless: true });
  try {
    const runs = [];
    runs.push(await auditAccount(browser, "admin", ADMIN_USERNAME, ADMIN_PASSWORD));
    runs.push(await auditAccount(browser, "alice", ALICE_USERNAME, ALICE_PASSWORD));
    const output = {
      startedAt: new Date().toISOString(),
      baseUrl: BASE_URL,
      screenshotDir: SCREENSHOT_DIR,
      runs,
      issues: runs.flatMap((run) => run.issues)
    };
    fs.writeFileSync(OUTPUT_PATH, JSON.stringify(output, null, 2));
    console.log(JSON.stringify(buildOutputSummary(output), null, 2));
    if (output.issues.length) {
      process.exitCode = 1;
    }
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
