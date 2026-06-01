const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");
const crypto = require("crypto");
const { chromium } = require("playwright");

const BASE_URL = process.env.NOTEWEAVE_BASE_URL || "http://127.0.0.1:18082";
const ADMIN_USERNAME = process.env.NOTEWEAVE_ADMIN_USERNAME || "admin";
const ADMIN_PASSWORD = process.env.NOTEWEAVE_ADMIN_PASSWORD || "NoteWeave123!";
const ALICE_USERNAME = process.env.NOTEWEAVE_ALICE_USERNAME || "alice";
const ALICE_PASSWORD = process.env.NOTEWEAVE_ALICE_PASSWORD || "NoteWeave123!";
const OUTPUT_PATH = path.join(__dirname, "last-run.json");
const SCREENSHOT_DIR = process.env.NOTEWEAVE_SMOKE_SCREENSHOTS
  ? path.resolve(process.env.NOTEWEAVE_SMOKE_SCREENSHOTS)
  : null;

const OUTPUT = {
  startedAt: new Date().toISOString(),
  baseUrl: BASE_URL,
  runs: []
};

function runHasProblems(run) {
  return run.issues.length || run.consoleErrors.length || run.requestFailures.length;
}

function buildOutputSummary(output) {
  return {
    startedAt: output.startedAt,
    finishedAt: output.finishedAt,
    baseUrl: output.baseUrl,
    outputPath: OUTPUT_PATH,
    runCount: output.runs.length,
    runs: output.runs.map((run) => ({
      account: run.account,
      steps: run.steps.length,
      snapshots: run.snapshots.length,
      issues: run.issues.length,
      consoleErrors: run.consoleErrors.length,
      requestFailures: run.requestFailures.length,
      lastSnapshot: run.snapshots.at(-1)?.label || null
    })),
    issues: output.runs.flatMap((run) =>
      run.issues.map((item) => ({ account: run.account, ...item }))
    ),
    consoleErrors: output.runs.flatMap((run) =>
      run.consoleErrors.map((item) => ({ account: run.account, ...item }))
    ),
    requestFailures: output.runs.flatMap((run) =>
      run.requestFailures.map((item) => ({ account: run.account, ...item }))
    )
  };
}

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

async function uploadKnowledgeDocumentViaApi(page, knowledgeBaseId, fileName, content) {
  const md5 = crypto.createHash("md5").update(content).digest("hex");
  return page.evaluate(async ({ knowledgeBaseId, fileName, content, md5 }) => {
    const auth = JSON.parse(localStorage.getItem("noteweave.workspace.auth") || "{}");
    const authHeaders = auth.accessToken ? { Authorization: `${auth.tokenType || "Bearer"} ${auth.accessToken}` } : {};
    async function request(path, options = {}) {
      const response = await fetch(`/api/v1${path}`, {
        ...options,
        headers: {
          Accept: "application/json",
          ...(options.json ? { "Content-Type": "application/json" } : {}),
          ...authHeaders,
          ...(options.headers || {})
        },
        body: options.json ? JSON.stringify(options.json) : options.body
      });
      const text = await response.text();
      let json = null;
      try {
        json = text ? JSON.parse(text) : null;
      } catch {
        json = null;
      }
      if (!response.ok || json?.success === false) {
        throw new Error(json?.message || `${response.status} ${path}`);
      }
      return json?.data ?? null;
    }

    const bytes = new TextEncoder().encode(content);
    const init = await request(`/team/knowledge-bases/${knowledgeBaseId}/documents/uploads/init`, {
      method: "POST",
      json: {
        fileMd5: md5,
        fileName,
        contentType: "text/markdown",
        totalSize: bytes.byteLength,
        chunkSize: 1024 * 1024,
        totalChunks: 1
      }
    });
    if (!init.instantUpload) {
      const formData = new FormData();
      formData.append("file", new Blob([content], { type: "text/markdown" }), fileName);
      await request(`/team/document-uploads/${init.uploadId}/chunks?chunkIndex=0`, {
        method: "POST",
        body: formData
      });
    }
    return request(`/team/document-uploads/${init.uploadId}/merge`, { method: "POST" });
  }, { knowledgeBaseId, fileName, content, md5 });
}

function pageItems(response) {
  return response?.json?.data?.items || response?.json?.data?.content || response?.json?.data || [];
}

function sqlLiteral(value) {
  if (value === null || value === undefined) {
    return "NULL";
  }
  return `'${String(value).replace(/\\/g, "\\\\").replace(/'/g, "''")}'`;
}

function mysqlQuery(sql) {
  return execFileSync("docker", [
    "exec",
    "noteweave-mysql",
    "mysql",
    "-unoteweave",
    "-pnoteweave",
    "-N",
    "-B",
    "noteweave",
    "-e",
    sql
  ], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

function seedCitationState({ spaceId, sourceId, sourceType = "SOURCE", artifactId = null, articleCardId = null, conceptCardId = null, messageId = null }) {
  const suffix = Date.now();
  const quote = `State coverage quote ${suffix}: NoteWeave keeps cited evidence attached to generated artifacts.`;
  const title = `Smoke citation state ${suffix}`;
  const relationSql = [];
  if (artifactId) {
    relationSql.push(`INSERT IGNORE INTO artifact_citation (artifact_id, citation_id, created_at, updated_at) VALUES (${Number(artifactId)}, @citation_id, NOW(), NOW());`);
  }
  if (articleCardId) {
    relationSql.push(`INSERT IGNORE INTO article_card_citation (article_card_id, citation_id, relation_type, created_at, updated_at) VALUES (${Number(articleCardId)}, @citation_id, 'EVIDENCE', NOW(), NOW());`);
  }
  if (conceptCardId) {
    relationSql.push(`INSERT IGNORE INTO concept_card_citation (concept_card_id, citation_id, relation_type, created_at, updated_at) VALUES (${Number(conceptCardId)}, @citation_id, 'EVIDENCE', NOW(), NOW());`);
  }
  if (messageId) {
    relationSql.push(`INSERT IGNORE INTO message_citation (message_id, citation_id, retrieval_trace_id, created_at, updated_at) VALUES (${Number(messageId)}, @citation_id, NULL, NOW(), NOW());`);
  }
  const sql = `
START TRANSACTION;
INSERT INTO citation (
  space_id, source_type, source_id, chunk_id, page_no, start_offset, end_offset,
  title, quote_text, quote_hash, location_info, snapshot_object_key, source_version,
  created_at, updated_at
) VALUES (
  ${Number(spaceId)}, ${sqlLiteral(sourceType)}, ${Number(sourceId)}, NULL, 1, 0, ${quote.length},
  ${sqlLiteral(title)}, ${sqlLiteral(quote)}, SHA2(${sqlLiteral(quote)}, 256),
  ${sqlLiteral(`seeded smoke citation offset 0-${quote.length}`)},
  ${sqlLiteral(`dev/citations/smoke-seed/${suffix}.txt`)},
  'smoke-state-seed', NOW(), NOW()
);
SET @citation_id = LAST_INSERT_ID();
${relationSql.join("\n")}
COMMIT;
SELECT @citation_id;
`;
  const output = mysqlQuery(sql);
  const id = output.split(/\s+/).filter(Boolean).pop();
  return { id, title, quote };
}

function seedRetrievalTraceState({ spaceId, username = "admin" }) {
  const suffix = Date.now();
  const userIdOutput = mysqlQuery(`select id from users where username = ${sqlLiteral(username)} limit 1;`);
  const userId = Number(userIdOutput.split(/\s+/).filter(Boolean).pop() || 1);
  const sql = `
START TRANSACTION;
INSERT INTO retrieval_trace (
  user_id, space_id, session_id, message_id, task_id, scene, query_text, retriever_type,
  top_k, latency_ms, retrieved_chunk_count, created_at
) VALUES (
  ${Number(userId)},
  ${Number(spaceId)},
  NULL,
  NULL,
  NULL,
  'SMOKE',
  ${sqlLiteral(`Smoke retrieval trace ${suffix}`)},
  'SMOKE',
  3,
  12,
  1,
  NOW()
);
SET @trace_id = LAST_INSERT_ID();
INSERT INTO retrieval_trace_item (
  trace_id, source_type, source_id, document_id, chunk_id, wiki_page_id,
  score, rank_no, selected_as_evidence, metadata_json, created_at, updated_at
) VALUES (
  @trace_id,
  'SMOKE',
  NULL,
  NULL,
  NULL,
  NULL,
  0.87,
  1,
  b'1',
  ${sqlLiteral(JSON.stringify({ scenario: "smoke-admin-logs", createdAt: new Date().toISOString() }))},
  NOW(),
  NOW()
);
COMMIT;
SELECT @trace_id;
`;
  const output = mysqlQuery(sql);
  const id = output.split(/\s+/).filter(Boolean).pop();
  return { id, userId };
}

async function poll(page, producer, { timeoutMs = 30000, intervalMs = 1000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      const result = await producer();
      if (result) {
        return result;
      }
      lastError = null;
    } catch (error) {
      lastError = error;
    }
    await page.waitForTimeout(intervalMs);
  }
  if (lastError) {
    throw lastError;
  }
  return null;
}

async function waitForResponse(page, predicate, { timeoutMs = 30000 } = {}) {
  try {
    return await page.waitForResponse(predicate, { timeout: timeoutMs });
  } catch {
    return null;
  }
}

async function waitForKnowledgeBase(page, spaceId, name) {
  return poll(page, async () => {
    const response = await api(page, "GET", `/team/spaces/${spaceId}/knowledge-bases`);
    return pageItems(response).find((item) => item.name === name) || null;
  }, { timeoutMs: 15000, intervalMs: 800 });
}

async function waitForSpace(page, name) {
  return poll(page, async () => {
    const response = await api(page, "GET", "/spaces?page=1&pageSize=100");
    return pageItems(response).find((item) => item.name === name) || null;
  }, { timeoutMs: 15000, intervalMs: 800 });
}

async function waitForChatSession(page, spaceId, title) {
  return poll(page, async () => {
    const response = await api(page, "GET", `/spaces/${spaceId}/chat-sessions`);
    return pageItems(response).find((item) => item.title === title) || null;
  }, { timeoutMs: 15000, intervalMs: 800 });
}

async function waitForEvalCase(page, spaceId, name) {
  return poll(page, async () => {
    const response = await api(page, "GET", `/admin/spaces/${spaceId}/rag-eval-cases`);
    return pageItems(response).find((item) => item.name === name) || null;
  }, { timeoutMs: 15000, intervalMs: 800 });
}

async function waitForWikiPage(page, spaceId, title, { timeoutMs = 15000 } = {}) {
  return poll(page, async () => {
    const response = await api(page, "GET", `/team/spaces/${spaceId}/wiki-pages`);
    return pageItems(response).find((item) => item.title === title) || null;
  }, { timeoutMs, intervalMs: 800 });
}

async function waitForIndexedDocument(page, kbId, fileName, run, { timeoutMs = 120000 } = {}) {
  return poll(page, async () => {
    const response = await api(page, "GET", `/team/knowledge-bases/${kbId}/documents`);
    const document = pageItems(response).find((item) => item.originalFilename === fileName || item.title === fileName);
    if (!document) {
      return null;
    }
    const raw = `${document.status || ""} ${document.parseStatus || ""} ${document.indexStatus || ""}`.toUpperCase();
    if (raw.includes("FAIL")) {
      issue(run, "document_failed", `${fileName} entered failure state: ${raw}`);
      return document;
    }
    const status = String(document.status || "").toUpperCase();
    if (status === "INDEXED") {
      return document;
    }
    return null;
  }, { timeoutMs, intervalMs: 1500 });
}

async function waitForProject(page, title) {
  return poll(page, async () => {
    const response = await api(page, "GET", "/personal/research-projects");
    return pageItems(response).find((item) => item.title === title) || null;
  }, { timeoutMs: 30000, intervalMs: 1000 });
}

async function waitForProjectSource(page, projectId, title) {
  return poll(page, async () => {
    const response = await api(page, "GET", `/personal/research-projects/${projectId}/sources`);
    return pageItems(response).find((item) => item.title === title) || null;
  }, { timeoutMs: 15000, intervalMs: 800 });
}

async function waitForProjectSourceReady(page, projectId, title, run) {
  return poll(page, async () => {
    const response = await api(page, "GET", `/personal/research-projects/${projectId}/sources`);
    const source = pageItems(response).find((item) => item.title === title);
    if (!source) {
      return null;
    }
    const importStatus = String(source.importStatus || "").toUpperCase();
    if (importStatus.includes("FAIL")) {
      issue(run, "source_import_failed", `${title} entered ${importStatus}`);
      return source;
    }
    return importStatus === "READY" ? source : null;
  }, { timeoutMs: 30000, intervalMs: 1000 });
}

async function waitForKnowledgeSearchResult(page, keyword) {
  return poll(page, async () => {
    const text = await page.locator(".main-canvas").first().textContent().catch(() => "");
    if (text.includes(keyword) || await page.locator(".search-result-item").count() > 0) {
      return true;
    }
    return null;
  }, { timeoutMs: 20000, intervalMs: 1000 });
}

async function waitForProjectSourceCompiled(page, projectId, title, run, { recordIssue = true } = {}) {
  const source = await poll(page, async () => {
    const response = await api(page, "GET", `/personal/research-projects/${projectId}/sources`);
    const item = pageItems(response).find((candidate) => candidate.title === title);
    if (!item) {
      return null;
    }
    const compileStatus = String(item.compileStatus || "").toUpperCase();
    if (compileStatus.includes("FAIL")) {
      if (recordIssue) {
        issue(run, "source_compile_failed", `${title} entered ${compileStatus}: ${item.errorMessage || ""}`);
      }
      return item;
    }
    if (compileStatus === "READY" || compileStatus === "SUCCESS") {
      return item;
    }
    return null;
  }, { timeoutMs: 120000, intervalMs: 1500 });
  if (!source && recordIssue) {
    issue(run, "source_compile_timeout", title);
  }
  return source;
}

async function waitForProjectCards(page, projectId, run) {
  try {
    const cards = await poll(page, async () => {
      const [articles, concepts] = await Promise.all([
        api(page, "GET", `/personal/research-projects/${projectId}/article-cards`),
        api(page, "GET", `/personal/research-projects/${projectId}/concept-cards`)
      ]);
      const articleItems = pageItems(articles);
      const conceptItems = pageItems(concepts);
      if (articleItems.length || conceptItems.length) {
        return { articleItems, conceptItems };
      }
      return null;
    }, { timeoutMs: 30000, intervalMs: 1500 });
    if (!cards) {
      issue(run, "project_cards_timeout", "No article or concept cards were created.");
    }
    return cards;
  } catch (error) {
    issue(run, "project_cards_timeout", error.message);
    return null;
  }
}

async function waitForProjectArtifact(page, spaceId, projectId, title, run) {
  const artifact = await poll(page, async () => {
    const response = await api(page, "GET", `/spaces/${spaceId}/artifacts`);
    const artifacts = pageItems(response);
    return artifacts.find((item) =>
      Number(item.researchProjectId) === Number(projectId) &&
      (!title || String(item.title || "").includes(title))
    ) || artifacts.find((item) => Number(item.researchProjectId) === Number(projectId)) || null;
  }, { timeoutMs: 60000, intervalMs: 1500 });
  if (!artifact) {
    issue(run, "project_artifact_timeout", `${projectId}:${title}`);
  }
  return artifact;
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
  await openInlineFormForSelector(page, selector, run);
  const locator = await preferredVisibleLocator(page, selector);
  if (await locator.count()) {
    if (!(await locator.isEnabled())) {
      issue(run, "disabled_control", label);
      return false;
    }
    await locator.click();
    await waitForAppIdle(page);
    run.steps.push(`clicked:${label}`);
    return true;
  }
  issue(run, "missing_control", label);
  return false;
}

async function clickIfPresent(page, selector, label, run) {
  await openInlineFormForSelector(page, selector, run);
  const locator = await preferredVisibleLocator(page, selector);
  if (await locator.count()) {
    if (!(await locator.isEnabled())) {
      issue(run, "disabled_control", label);
      return false;
    }
    await locator.click();
    await waitForAppIdle(page);
    run.steps.push(`clicked:${label}`);
    return true;
  }
  run.steps.push(`skipped:${label}`);
  return false;
}

async function fillIfExists(page, selector, value, label, run) {
  await openInlineFormForSelector(page, selector, run);
  const locator = await preferredVisibleLocator(page, selector);
  if (await locator.count()) {
    await locator.fill(value);
    run.steps.push(`filled:${label}`);
    return true;
  }
  issue(run, "missing_field", label);
  return false;
}

async function selectIfExists(page, selector, value, label, run) {
  await openInlineFormForSelector(page, selector, run);
  const locator = await preferredVisibleLocator(page, selector);
  if (await locator.count()) {
    await locator.selectOption(String(value));
    run.steps.push(`selected:${label}`);
    return true;
  }
  issue(run, "missing_field", label);
  return false;
}

async function setInputFilesIfExists(page, selector, files, label, run) {
  await openInlineFormForSelector(page, selector, run);
  const locator = await preferredVisibleLocator(page, selector);
  if (await locator.count()) {
    await locator.setInputFiles(files);
    run.steps.push(`attached:${label}`);
    return true;
  }
  issue(run, "missing_field", label);
  return false;
}

async function clickAndDiscardDownloadIfPresent(page, selector, label, run) {
  const locator = page.locator(selector).filter({ visible: true }).first();
  if (!(await locator.count())) {
    run.steps.push(`skipped:${label}`);
    return false;
  }
  const downloadPromise = page.waitForEvent("download", { timeout: 15000 }).catch(() => null);
  await locator.click();
  const download = await downloadPromise;
  await waitForAppIdle(page);
  if (!download) {
    issue(run, "missing_download", label);
    return false;
  }
  await download.delete().catch(() => {});
  run.steps.push(`downloaded:${label}`);
  return true;
}

async function submitKnowledgeSearch(page, keyword, run) {
  const form = page.locator("form#kb-search-form").filter({ visible: true }).first();
  if (!(await form.count())) {
    issue(run, "missing_form", "kb-search-form");
    return false;
  }
  const input = form.locator('input[name="keyword"]').first();
  await input.evaluate((element, value) => {
    element.value = value;
    element.dispatchEvent(new Event("input", { bubbles: true }));
    element.dispatchEvent(new Event("change", { bubbles: true }));
  }, keyword);
  await form.evaluate((element) => element.requestSubmit());
  await waitForAppIdle(page);
  run.steps.push("submitted:kb-search");
  return true;
}

async function preferredVisibleLocator(page, selector) {
  const modal = page.locator(".modal-dialog").filter({ visible: true }).first();
  if (await modal.count()) {
    const modalLocator = modal.locator(selector).filter({ visible: true }).first();
    if (await modalLocator.count()) {
      return modalLocator;
    }
  }
  return page.locator(selector).filter({ visible: true }).first();
}

async function openInlineFormForSelector(page, selector, run) {
  const match = selector.match(/^#([A-Za-z0-9_-]+)\b/);
  if (!match) {
    return false;
  }
  const formId = match[1];
  const blockingModalFormId = await page.locator(".modal-dialog form.inline-form").filter({ visible: true }).first()
    .evaluate((form) => form.id || "")
    .catch(() => "");
  if (blockingModalFormId && blockingModalFormId !== formId) {
    issue(run, "blocking_inline_form", `${blockingModalFormId} blocked ${formId}`);
    await closeDrawerIfOpen(page, run);
  }
  const visibleForm = page.locator(`form#${formId}`).filter({ visible: true }).first();
  if (await visibleForm.count()) {
    return true;
  }
  const trigger = page.locator(`[data-action="open-inline-form"][data-form-id="${formId}"]`).filter({ visible: true }).first();
  if (await trigger.count()) {
    await trigger.click();
    await waitForAppIdle(page);
    run.steps.push(`opened-form:${formId}`);
    return true;
  }
  return false;
}

async function waitForInlineFormClosed(page, run, formId, label) {
  const modalForm = page.locator(`.modal-dialog form#${formId}`).filter({ visible: true }).first();
  try {
    await modalForm.waitFor({ state: "hidden", timeout: 5000 });
  } catch {
    if (await modalForm.count()) {
      issue(run, "inline_form_not_closed", label);
      await closeDrawerIfOpen(page, run);
    }
  }
}

async function snapshotInlineForm(page, run, selector, label) {
  await openInlineFormForSelector(page, selector, run);
  const locator = await preferredVisibleLocator(page, selector);
  if (await locator.count()) {
    await snapshot(page, run, label);
    return true;
  }
  issue(run, "missing_form", label);
  return false;
}

async function clickGlobalNav(page, target, label, run) {
  await closeDrawerIfOpen(page, run);
  const selectors = [
    `.global-rail [data-nav="${target}"]`,
    `[data-nav="${target}"]`
  ];
  let clicked = false;
  for (const selector of selectors) {
    const locator = page.locator(selector).filter({ visible: true }).first();
    if (await locator.count()) {
      if (!(await locator.isEnabled())) {
        issue(run, "disabled_control", label);
        return false;
      }
      await locator.click();
      await waitForAppIdle(page);
      run.steps.push(`clicked:${label}`);
      clicked = true;
      break;
    }
  }
  if (!clicked) {
    await page.goto(`${BASE_URL}${target}`, { waitUntil: "domcontentloaded" });
    await waitForAppIdle(page);
    run.steps.push(`goto:${label}`);
  }
  if (!page.url().endsWith(target)) {
    issue(run, "unexpected_route", `${label}: expected ${target}, got ${page.url()}`);
    return false;
  }
  return true;
}

async function closeDrawerIfOpen(page, run) {
  const closeButton = page.locator('.modal-dialog [data-action="close-drawer"], .modal-close-button').filter({ visible: true }).first();
  if (await closeButton.count() && await closeButton.isVisible()) {
    await closeButton.click();
    await waitForAppIdle(page);
    run.steps.push("clicked:close-drawer");
    return;
  }
  const backdrop = page.locator('.modal-backdrop[data-action="close-drawer"]').filter({ visible: true }).first();
  if (await backdrop.count() && await backdrop.isVisible()) {
    await backdrop.click({ position: { x: 8, y: 8 } });
    await waitForAppIdle(page);
    run.steps.push("clicked:close-drawer-backdrop");
  }
}

async function snapshot(page, run, label) {
  await waitForAppIdle(page);
  const h1 = await page.locator("h1").first().textContent().catch(() => "");
  const shellState = await page.evaluate(() => {
    const shell = document.querySelector(".workbench-shell");
    const rail = document.querySelector(".context-rail");
    return {
      contextRailCollapsed: Boolean(shell?.classList.contains("context-rail-collapsed")),
      contextRailWidth: rail ? Math.round(rail.getBoundingClientRect().width) : null
    };
  }).catch(() => ({ contextRailCollapsed: false, contextRailWidth: null }));
  const snapshotData = {
    label,
    url: page.url(),
    h1: h1 || "",
    hasWorkbenchShell: await page.locator(".workbench-shell").count() > 0,
    hasGlobalRail: await page.locator(".global-rail").count() > 0,
    hasContextRail: await page.locator(".context-rail").count() > 0,
    hasMainCanvas: await page.locator(".main-canvas").count() > 0,
    hasInspector: await page.locator(".inspector").count() > 0,
    hasModalDialog: await page.locator(".modal-dialog").filter({ visible: true }).count() > 0,
    hasErrorState: await page.locator(".error-state").count() > 0,
    hasEmptyState: await page.locator(".empty-state").count() > 0,
    ...shellState
  };
  if (SCREENSHOT_DIR) {
    fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
    const safeAccount = run.account.replace(/[^a-z0-9_-]+/gi, "-");
    const safeLabel = label.replace(/[^a-z0-9_-]+/gi, "-");
    const fileName = `${String(run.snapshots.length + 1).padStart(2, "0")}-${safeAccount}-${safeLabel}.png`;
    const screenshotPath = path.join(SCREENSHOT_DIR, fileName);
    await page.screenshot({ path: screenshotPath, fullPage: true });
    snapshotData.screenshot = screenshotPath;
  }
  run.snapshots.push(snapshotData);
}

async function assertContextRailCollapsed(page, run) {
  const state = await page.evaluate(() => {
    const shell = document.querySelector(".workbench-shell");
    const rail = document.querySelector(".context-rail");
    const content = document.querySelector(".context-rail-content");
    return {
      shellCollapsed: Boolean(shell?.classList.contains("context-rail-collapsed")),
      railWidth: rail ? Math.round(rail.getBoundingClientRect().width) : null,
      contentDisplay: content ? getComputedStyle(content).display : null
    };
  }).catch(() => ({ shellCollapsed: false, railWidth: null, contentDisplay: null }));
  if (!state.shellCollapsed || state.railWidth === null || state.railWidth > 72 || state.contentDisplay !== "none") {
    issue(run, "context_rail_not_collapsed", JSON.stringify(state));
  }
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

async function createSpaceChatSession(page, spaceId, title) {
  const created = await api(page, "POST", "/chat/sessions", {
    spaceId,
    sessionType: "TEAM_CHAT",
    sessionKind: "FORMAL",
    scopeType: "SPACE",
    scopeIds: [spaceId],
    title
  });
  if (created.status >= 400 || !created.json?.data?.id) {
    throw new Error(`Failed to create smoke space chat session: ${created.text}`);
  }
  return created.json.data;
}

async function waitForChatCompletion(page, run) {
  await page.waitForFunction(() => {
    const canvas = document.querySelector(".chat-main-canvas");
    const text = canvas ? canvas.innerText : "";
    return text.includes("COMPLETED") || text.includes("FAILED") || text.includes("STOPPED");
  }, null, { timeout: 90000 }).catch(() => {
    issue(run, "timeout", "chat stream did not reach a terminal status within 90s");
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
  const smokeSpaceName = `Smoke Space ${Date.now()}`;
  const kbName = `Smoke KB ${Date.now()}`;
  const uploadKeyword = `smoke-upload-keyword-${Date.now()}`;
  const uploadFileName = `smoke-upload-${Date.now()}.md`;
  const uploadContent = `# Smoke Upload\n\n${uploadKeyword}\n\nThis document validates team knowledge upload.\n`;
  const formSessionTitle = `Smoke Form Session ${Date.now()}`;
  const evalCaseName = `Smoke Eval Case ${Date.now()}`;
  const wikiSeedTitle = `Smoke Team Wiki ${Date.now()}`;
  const wikiSeedTargetTitle = `Smoke Wiki Target ${Date.now()}`;
  let retrievalTraceId = null;
  await snapshot(page, run, "post-login");
  await clickIfVisible(page, '[data-action="toggle-context-rail"]', "toggle-context-rail-collapse", run);
  await assertContextRailCollapsed(page, run);
  await snapshot(page, run, "context-rail-collapsed");
  await clickIfVisible(page, '[data-action="toggle-context-rail"]', "toggle-context-rail-expand", run);

  await clickGlobalNav(page, "/spaces", "spaces-nav", run);
  await snapshot(page, run, "spaces");
  await clickIfVisible(page, `[data-action="preview-space"][data-space-id="${spaceId}"]`, "preview-space-members", run);
  await snapshot(page, run, "space-members-preview");
  await snapshotInlineForm(page, run, '#create-space-form input[name="name"]', "modal-create-space");
  await fillIfExists(page, '#create-space-form input[name="name"]', smokeSpaceName, "create-space-name", run);
  await fillIfExists(page, '#create-space-form textarea[name="description"]', "Playwright smoke created team space.", "create-space-description", run);
  await clickIfVisible(page, '#create-space-form button[type="submit"]', "create-space-submit", run);
  const smokeSpace = await waitForSpace(page, smokeSpaceName);
  if (smokeSpace) {
    run.steps.push(`created-space:${smokeSpace.id}`);
  } else {
    issue(run, "missing_smoke_space", smokeSpaceName);
  }
  await snapshot(page, run, "space-created");

  await clickIfVisible(page, `[data-action="enter-space"][data-space-id="${spaceId}"]`, "enter-team-space", run);
  await snapshot(page, run, "entered-space");

  await clickGlobalNav(page, `/spaces/${spaceId}/team/knowledge-bases`, "team-knowledge-nav", run);
  await snapshot(page, run, "knowledge-list");
  await snapshotInlineForm(page, run, '#create-kb-form input[name="name"]', "modal-create-kb");
  await fillIfExists(page, '#create-kb-form input[name="name"]', kbName, "create-kb-name", run);
  await fillIfExists(page, '#create-kb-form textarea[name="description"]', "Playwright smoke knowledge base", "create-kb-description", run);
  await clickIfVisible(page, '#create-kb-form button[type="submit"]', "create-kb-submit", run);
  const smokeKb = await waitForKnowledgeBase(page, spaceId, kbName);
  if (!smokeKb) {
    issue(run, "missing_smoke_kb", kbName);
    return;
  }
  const openKbButton = page.locator(`[data-action="open-kb"][data-kb-id="${smokeKb.id}"]`).first();
  if (await openKbButton.count()) {
    await openKbButton.click();
    await waitForAppIdle(page);
    run.steps.push("clicked:open-smoke-kb");
  } else {
    await page.goto(`${BASE_URL}/spaces/${spaceId}/team/knowledge-bases/${smokeKb.id}`, { waitUntil: "domcontentloaded" });
    await waitForAppIdle(page);
    run.steps.push("goto:open-smoke-kb");
  }
  await snapshot(page, run, "knowledge-detail");
  await snapshotInlineForm(page, run, '#upload-document-form input[name="file"]', "modal-upload-document");
  await setInputFilesIfExists(page, '#upload-document-form input[name="file"]', {
    name: uploadFileName,
    mimeType: "text/markdown",
    buffer: Buffer.from(uploadContent, "utf8")
  }, uploadFileName, run);
  await clickIfVisible(page, '#upload-document-form button[type="submit"]', "upload-document-submit", run);
  let indexedDocument = await waitForIndexedDocument(page, smokeKb.id, uploadFileName, run, { timeoutMs: 30000 });
  if (!indexedDocument) {
    try {
      const uploaded = await uploadKnowledgeDocumentViaApi(page, smokeKb.id, uploadFileName, uploadContent);
      run.steps.push(`api-upload-document:${uploaded?.documentId || uploadFileName}`);
      indexedDocument = await waitForIndexedDocument(page, smokeKb.id, uploadFileName, run, { timeoutMs: 120000 });
    } catch (error) {
      issue(run, "document_api_upload_failed", error.message);
    }
  }
  if (!indexedDocument) {
    issue(run, "document_index_timeout", uploadFileName);
  } else {
    run.steps.push(`indexed-document:${indexedDocument.id}`);
  }
  await submitKnowledgeSearch(page, uploadKeyword, run);
  if (!await waitForKnowledgeSearchResult(page, uploadKeyword)) {
    issue(run, "knowledge_search_empty", uploadKeyword);
  }
  await snapshot(page, run, "knowledge-search");

  const session = await ensureSmokeChatSession(page, spaceId);
  await clickGlobalNav(page, `/spaces/${spaceId}/workbench/chat`, "chat-nav", run);
  await snapshotInlineForm(page, run, '#create-session-form input[name="title"]', "modal-create-session");
  await fillIfExists(page, '#create-session-form input[name="title"]', formSessionTitle, "create-session-title", run);
  await selectIfExists(page, '#create-session-form select[name="scopeType"]', "SPACE", "create-session-scope", run);
  await clickIfVisible(page, '#create-session-form button[type="submit"]', "create-session-submit", run);
  const formSession = await waitForChatSession(page, spaceId, formSessionTitle);
  if (formSession) {
    run.steps.push(`created-session:${formSession.id}`);
  } else {
    issue(run, "missing_form_session", formSessionTitle);
  }
  await snapshot(page, run, "chat-session-created");
  await clickIfVisible(page, `[data-action="select-session"][data-session-id="${session.id}"]`, "select-smoke-chat-session", run);
  await snapshot(page, run, "chat");
  await fillIfExists(page, '#chat-message-form textarea[name="content"]', "请用一句话概括当前知识库里关于新手引导或发布评审的关键结论。", "chat-message", run);
  await clickIfVisible(page, '#chat-message-form button[type="submit"]', "chat-send", run);
  await waitForChatCompletion(page, run);
  await clickIfPresent(page, '[data-action="feedback-up"]', "chat-feedback-up", run);
  await clickIfPresent(page, '[data-action="feedback-down"]', "chat-feedback-down", run);
  await clickIfPresent(page, '[data-action="reconnect-chat"]', "chat-reconnect", run);
  const chatMessagesResponse = await api(page, "GET", `/chat/sessions/${session.id}/messages`);
  const assistantMessage = pageItems(chatMessagesResponse)
    .filter((message) => String(message.role || "").toUpperCase().includes("ASSISTANT"))
    .pop();
  if (assistantMessage?.id) {
    if (indexedDocument?.id) {
      try {
        const seeded = seedCitationState({
          spaceId,
          sourceType: "DOCUMENT",
          sourceId: indexedDocument.id,
          messageId: assistantMessage.id
        });
        run.steps.push(`seeded:chat-message-citation:${seeded.id}`);
      } catch (error) {
        issue(run, "citation_state_seed_failed", error.message);
      }
    }
    const citationsResponse = await api(page, "GET", `/chat/messages/${assistantMessage.id}/citations`);
    const citationTrace = pageItems(citationsResponse).find((citation) => citation.retrievalTraceId);
    retrievalTraceId = citationTrace?.retrievalTraceId || null;
  }
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
    retrievalTraceId = await page.locator(".modal-dialog").evaluate((el) => {
      const text = el.innerText || "";
      const match = text.match(/Trace\s*[:#]?\s*(\d+)|traceId[^\d]*(\d+)/i);
      return match ? Number(match[1] || match[2]) : null;
    }).catch(() => null);
    await closeDrawerIfOpen(page, run);
  } else {
    run.steps.push("skipped:open-citation-no-citation-returned");
  }

  await clickGlobalNav(page, `/spaces/${spaceId}/artifacts`, "artifacts-nav", run);
  await snapshot(page, run, "artifacts");
  if (await clickIfPresent(page, '[data-action="open-artifact"]', "open-artifact", run)) {
    await snapshot(page, run, "artifact-detail");
  }

  await clickGlobalNav(page, `/spaces/${spaceId}/wiki`, "wiki-nav", run);
  await snapshot(page, run, "wiki");
  const seededTargetResponse = await api(page, "POST", `/team/spaces/${spaceId}/wiki-pages`, {
    title: wikiSeedTargetTitle,
    content: `# ${wikiSeedTargetTitle}\n\nThis page is the resolved target for the smoke Wiki graph.`
  });
  const seededTarget = seededTargetResponse?.json?.data;
  if (seededTarget?.id) {
    run.steps.push(`created-team-wiki-target:${seededTarget.id}`);
    await api(page, "POST", `/team/wiki-pages/${seededTarget.id}/publish`, { changeNote: "smoke graph target" });
    run.steps.push(`published-team-wiki-target:${seededTarget.id}`);
  }
  const seededWikiResponse = await api(page, "POST", `/team/spaces/${spaceId}/wiki-pages`, {
    title: wikiSeedTitle,
    content: `# ${wikiSeedTitle}\n\nThis seeded team Wiki page links to [[${wikiSeedTargetTitle}]] so graph, backlinks, and citation-state panels are deterministic.`
  });
  const seededWiki = seededWikiResponse?.json?.data;
  if (seededWiki?.id) {
    run.steps.push(`created-team-wiki:${seededWiki.id}`);
    await api(page, "POST", `/team/wiki-pages/${seededWiki.id}/publish`, { changeNote: "smoke state coverage" });
    run.steps.push(`published-team-wiki:${seededWiki.id}`);
    await page.goto(`${BASE_URL}/spaces/${spaceId}/wiki`, { waitUntil: "domcontentloaded" });
    await waitForAppIdle(page);
    await snapshot(page, run, "wiki-with-seeded-page");
    const wikiGraphResponse = await api(page, "GET", `/team/spaces/${spaceId}/wiki-graph`);
    const wikiGraph = wikiGraphResponse?.json?.data;
    if (!wikiGraph?.summary || (wikiGraph.summary.resolvedEdgeCount || 0) < 1) {
      issue(run, "missing_wiki_graph_summary", JSON.stringify(wikiGraph?.summary || null));
    }
    if (!(await page.locator(".wiki-insight-column").count())) {
      issue(run, "missing_wiki_insight_column", "Wiki page should expose graph/evidence side panel");
    }
    if (!(await page.locator(".wiki-mini-graph .wiki-graph-pill").count())) {
      issue(run, "missing_wiki_mini_graph", "Wiki page should render clickable page graph nodes");
    }
  } else {
    issue(run, "missing_team_wiki_seed", wikiSeedTitle);
  }
  if (await clickIfPresent(page, '[data-action="select-wiki-page"]', "select-wiki-page", run)) {
    await snapshot(page, run, "wiki-selected");
  }
  await fillIfExists(page, '#wiki-search-form input[name="keyword"]', "研究", "wiki-search-keyword", run);
  await clickIfVisible(page, '#wiki-search-form button[type="submit"]', "wiki-search-submit", run);
  await snapshot(page, run, "wiki-search");

  await clickGlobalNav(page, `/spaces/${spaceId}/graph`, "graph-nav", run);
  await snapshot(page, run, "graph");
  if (!(await page.locator(".graph-edge-svg .graph-edge-path").count())) {
    issue(run, "missing_graph_edge_lines", "Knowledge graph should draw relationship lines between visible nodes");
  }
  await fillIfExists(page, '#graph-filter-form input[name="nodeTypes"]', "DOCUMENT,WIKI_PAGE,ARTIFACT", "graph-filter-node-types", run);
  await clickIfVisible(page, '#graph-filter-form button[type="submit"]', "graph-filter-submit", run);
  await snapshot(page, run, "graph-filtered");
  await fillIfExists(page, '#graph-filter-form input[name="nodeTypes"]', "", "graph-filter-node-types-reset", run);
  await clickIfVisible(page, '#graph-filter-form button[type="submit"]', "graph-filter-reset-submit", run);
  await snapshot(page, run, "graph-filter-reset");
  await clickIfVisible(page, '[data-action="select-graph-node"]', "select-graph-node", run);
  await snapshot(page, run, "graph-node-selected");
  if (await page.locator(".modal-dialog").filter({ visible: true }).count()) {
    run.steps.push("graph-inspector-opened-by-select");
  } else {
    await clickIfVisible(page, '[data-action="open-graph-node-inspector"]', "open-graph-node-inspector", run);
  }
  await snapshot(page, run, "graph-inspector");
  await closeDrawerIfOpen(page, run);

  await clickGlobalNav(page, `/spaces/${spaceId}/memory`, "memory-nav", run);
  await snapshot(page, run, "memory");
  await snapshotInlineForm(page, run, '#space-memory-form input[name="topic"]', "modal-space-memory");
  await fillIfExists(page, '#space-memory-form input[name="topic"]', "Playwright Smoke", "space-memory-topic", run);
  await fillIfExists(page, '#space-memory-form textarea[name="summary"]', "浏览器实测写入的一条空间记忆。", "space-memory-summary", run);
  await clickIfVisible(page, '#space-memory-form button[type="submit"]', "space-memory-submit", run);
  await snapshot(page, run, "memory-space-saved");

  await clickGlobalNav(page, "/admin/tasks", "admin-tasks-nav", run);
  await snapshot(page, run, "admin-tasks");
  await selectIfExists(page, '#admin-task-filter-form select[name="taskStatus"]', "SUCCESS", "admin-task-status-filter", run);
  await clickIfVisible(page, '#admin-task-filter-form button[type="submit"]', "admin-task-filter-submit", run);
  await snapshot(page, run, "admin-tasks-filtered");
  await clickIfVisible(page, '[data-action="open-admin-task"]', "open-admin-task", run);
  await snapshot(page, run, "admin-task-detail");
  await closeDrawerIfOpen(page, run);

  await clickGlobalNav(page, "/admin/health", "admin-health-nav", run);
  await snapshot(page, run, "admin-health");
  await clickIfPresent(page, '[data-action="refresh-page"]', "admin-health-refresh", run);
  await snapshot(page, run, "admin-health-refreshed");
  await clickIfVisible(page, '[data-action="open-health-detail"]', "open-health-detail", run);
  await snapshot(page, run, "admin-health-detail");
  await closeDrawerIfOpen(page, run);

  await clickGlobalNav(page, "/admin/evaluation", "admin-evaluation-nav", run);
  await snapshot(page, run, "admin-evaluation");
  await snapshotInlineForm(page, run, '#eval-case-form input[name="name"]', "modal-eval-case");
  await fillIfExists(page, '#eval-case-form input[name="name"]', evalCaseName, "eval-case-name", run);
  await fillIfExists(page, '#eval-case-form textarea[name="queryText"]', "What is the smoke test validation question?", "eval-case-query", run);
  await fillIfExists(page, '#eval-case-form textarea[name="expectedAnswer"]', "A concise answer with cited evidence.", "eval-case-expected", run);
  await clickIfVisible(page, '#eval-case-form button[type="submit"]', "eval-case-submit", run);
  const evalCase = await waitForEvalCase(page, spaceId, evalCaseName);
  if (evalCase) {
    run.steps.push(`created-eval-case:${evalCase.id}`);
  } else {
    issue(run, "missing_eval_case", evalCaseName);
  }
  await snapshot(page, run, "admin-evaluation-case-created");
  await snapshotInlineForm(page, run, '#eval-run-form input[name="name"]', "modal-eval-run");
  await fillIfExists(page, '#eval-run-form input[name="name"]', `Smoke Eval Run ${Date.now()}`, "eval-run-name", run);
  await clickIfVisible(page, '#eval-run-form button[type="submit"]', "eval-run-submit", run);
  await snapshot(page, run, "admin-evaluation-run-started");

  await clickGlobalNav(page, "/admin/logs", "admin-logs-nav", run);
  if (!retrievalTraceId) {
    try {
      const seededTrace = seedRetrievalTraceState({ spaceId, username: ADMIN_USERNAME });
      retrievalTraceId = seededTrace.id;
      run.steps.push(`seeded:retrieval-trace:${retrievalTraceId}`);
    } catch (error) {
      issue(run, "retrieval_trace_seed_failed", error.message);
    }
  }
  if (retrievalTraceId) {
    await fillIfExists(page, '#retrieval-trace-form input[name="traceId"]', String(retrievalTraceId), "retrieval-trace-id", run);
    await clickIfVisible(page, '#retrieval-trace-form button[type="submit"]', "retrieval-trace-submit", run);
    await snapshot(page, run, "admin-logs-retrieval-trace");
  } else {
    run.steps.push("skipped:retrieval-trace-no-id");
  }
  await snapshot(page, run, "admin-logs");
}

async function aliceFlow(page, run) {
  const { personalSpace } = await currentWorkspace(page);
  const personalSpaceId = personalSpace.id;
  const projectTitle = `Smoke Project ${Date.now()}`;
  const sourceTitle = `Smoke Source ${Date.now()}`;
  const fileSourceTitle = `Smoke File Source ${Date.now()}`;
  const urlSourceTitle = `Smoke URL Source ${Date.now()}`;
  const wikiDraftTitle = `Smoke Wiki Draft ${Date.now()}`;
  const sourceBody = `This is a browser smoke text source for ${projectTitle}.`;
  await snapshot(page, run, "post-login");

  await clickGlobalNav(page, `/spaces/${personalSpaceId}/personal/projects`, "personal-research-nav", run);
  await snapshot(page, run, "projects");
  await snapshotInlineForm(page, run, '#create-project-form input[name="title"]', "modal-create-project");
  await fillIfExists(page, '#create-project-form input[name="title"]', projectTitle, "create-project-title", run);
  await fillIfExists(page, '#create-project-form textarea[name="description"]', "Playwright smoke personal research project", "create-project-description", run);
  await fillIfExists(page, '#create-project-form textarea[name="researchGoal"]', "Validate source import, card compile and generation entry.", "create-project-goal", run);
  const createProjectResponse = waitForResponse(page, (response) =>
    response.request().method() === "POST" && response.url().includes("/api/v1/personal/research-projects"),
    { timeoutMs: 15000 }
  );
  await clickIfVisible(page, '#create-project-form button[type="submit"]', "create-project-submit", run);
  const projectCreate = await createProjectResponse;
  if (!projectCreate || !projectCreate.ok()) {
    issue(run, "project_create_request_failed", projectCreate ? `${projectCreate.status()} ${projectCreate.url()}` : projectTitle);
  }
  const smokeProject = await waitForProject(page, projectTitle);
  if (!smokeProject) {
    issue(run, "missing_smoke_project", projectTitle);
    return;
  }
  const openProjectButton = page.locator(`[data-action="open-project"][data-project-id="${smokeProject.id}"]`).first();
  if (await openProjectButton.count()) {
    await openProjectButton.click();
    await waitForAppIdle(page);
    run.steps.push("clicked:open-smoke-project");
  } else {
    await page.goto(`${BASE_URL}/spaces/${personalSpaceId}/personal/projects/${smokeProject.id}`, { waitUntil: "domcontentloaded" });
    await waitForAppIdle(page);
    run.steps.push("goto:open-smoke-project");
  }
  await snapshot(page, run, "project-detail");

  await clickIfVisible(page, '.tab-link:has-text("Sources"), .tab-link:has-text("资料")', "project-sources-tab", run);
  await snapshot(page, run, "project-sources");
  await snapshotInlineForm(page, run, '#source-file-form input[name="file"]', "modal-source-file");
  await fillIfExists(page, '#source-file-form input[name="title"]', fileSourceTitle, "file-source-title", run);
  await setInputFilesIfExists(page, '#source-file-form input[name="file"]', {
    name: `${fileSourceTitle}.md`,
    mimeType: "text/markdown",
    buffer: Buffer.from(`# ${fileSourceTitle}\n\nFile source smoke content.\n`, "utf8")
  }, fileSourceTitle, run);
  await clickIfVisible(page, '#source-file-form button[type="submit"]', "project-add-file-source-submit", run);
  await waitForInlineFormClosed(page, run, "source-file-form", "project-add-file-source-submit");
  await waitForProjectSource(page, smokeProject.id, fileSourceTitle);
  await snapshot(page, run, "project-source-file-added");
  await snapshotInlineForm(page, run, '#source-url-form input[name="url"]', "modal-source-url");
  await fillIfExists(page, '#source-url-form input[name="url"]', "http://93.184.216.34/", "url-source-url", run);
  await fillIfExists(page, '#source-url-form input[name="title"]', urlSourceTitle, "url-source-title", run);
  await clickIfVisible(page, '#source-url-form button[type="submit"]', "project-add-url-source-submit", run);
  await waitForInlineFormClosed(page, run, "source-url-form", "project-add-url-source-submit");
  await waitForProjectSource(page, smokeProject.id, urlSourceTitle);
  await snapshot(page, run, "project-source-url-added");
  await snapshotInlineForm(page, run, '#source-text-form input[name="title"]', "modal-source-text");
  await fillIfExists(page, '#source-text-form input[name="title"]', sourceTitle, "text-source-title", run);
  await fillIfExists(page, '#source-text-form textarea[name="content"]', "这是浏览器实测写入的一条文本资料源。", "text-source-content", run);
  await clickIfVisible(page, '#source-text-form button[type="submit"]', "project-add-text-source-submit", run);
  await waitForInlineFormClosed(page, run, "source-text-form", "project-add-text-source-submit");
  const smokeSource = await waitForProjectSource(page, smokeProject.id, sourceTitle);
  if (!smokeSource) {
    issue(run, "missing_smoke_source", sourceTitle);
    return;
  }
  await waitForProjectSourceReady(page, smokeProject.id, sourceTitle, run);
  await snapshot(page, run, "project-sources-added");
  await clickIfVisible(page, `[data-action="source-compile"][data-source-id="${smokeSource.id}"]`, "project-source-compile", run);
  let compiledSource = await waitForProjectSourceCompiled(page, smokeProject.id, sourceTitle, run, { recordIssue: false });
  for (let retry = 1; retry <= 2; retry += 1) {
    const raw = `${compiledSource?.compileStatus || ""} ${compiledSource?.errorMessage || ""}`.toUpperCase();
    if (compiledSource && !raw.includes("FAIL")) {
      break;
    }
    if (compiledSource && !/LLM|503|502|504|TIMEOUT|UNAVAILABLE|FAILED/i.test(raw)) {
      break;
    }
    run.steps.push(`retry:project-source-compile:${retry}`);
    await api(page, "POST", `/personal/sources/${smokeSource.id}/compile`);
    compiledSource = await waitForProjectSourceCompiled(page, smokeProject.id, sourceTitle, run, { recordIssue: false });
  }
  if (!compiledSource) {
    issue(run, "source_compile_timeout", sourceTitle);
    return;
  }
  if (!compiledSource || String(compiledSource.compileStatus || "").toUpperCase().includes("FAIL")) {
    issue(run, "source_compile_failed", `${sourceTitle} entered ${compiledSource.compileStatus}: ${compiledSource.errorMessage || ""}`);
    return;
  }
  const cards = await waitForProjectCards(page, smokeProject.id, run);

  await clickIfVisible(page, '.tab-link:has-text("Cards"), .tab-link:has-text("卡片")', "project-cards-tab", run);
  await snapshot(page, run, "project-cards");
  if (cards?.articleItems?.length || cards?.conceptItems?.length) {
    try {
      const seeded = seedCitationState({
        spaceId: personalSpaceId,
        sourceId: smokeSource.id,
        articleCardId: cards.articleItems?.[0]?.id,
        conceptCardId: cards.conceptItems?.[0]?.id
      });
      run.steps.push(`seeded:project-card-citation:${seeded.id}`);
      await page.goto(`${BASE_URL}/spaces/${personalSpaceId}/personal/projects/${smokeProject.id}/cards`, { waitUntil: "domcontentloaded" });
      await waitForAppIdle(page);
      await snapshot(page, run, "project-cards-with-citations");
      if (await clickIfVisible(page, '[data-action="open-citation"]', "project-card-open-citation", run)) {
        await snapshot(page, run, "project-card-citation-inspector");
        await closeDrawerIfOpen(page, run);
      } else {
        issue(run, "missing_control", "project-card-open-citation");
      }
    } catch (error) {
      issue(run, "citation_state_seed_failed", error.message);
    }
  } else {
    issue(run, "missing_project_card_state", "No article or concept cards available for citation-state coverage.");
  }

  const artifactChatSession = await createSpaceChatSession(page, personalSpaceId, `Smoke Artifact Chat ${Date.now()}`);
  run.steps.push(`created-chat-artifact-session:${artifactChatSession.id}`);
  await page.goto(`${BASE_URL}/spaces/${personalSpaceId}/workbench/chat`, { waitUntil: "domcontentloaded" });
  await waitForAppIdle(page);
  await clickIfVisible(page, `[data-action="select-session"][data-session-id="${artifactChatSession.id}"]`, "select-chat-artifact-session", run);
  await snapshot(page, run, "chat-artifact-entry");
  await clickIfVisible(page, '[data-action="open-chat-artifact-dialog"]', "open-chat-artifact-dialog", run);
  await snapshot(page, run, "modal-chat-artifact");
  await selectIfExists(page, '#chat-artifact-form select[name="skillId"]', "research-report", "chat-artifact-skill", run);
  await selectIfExists(page, '#chat-artifact-form select[name="projectId"]', smokeProject.id, "chat-artifact-project", run);
  await fillIfExists(page, '#chat-artifact-form input[name="topic"]', "Smoke Chat Artifact Topic", "chat-artifact-topic", run);
  await clickIfVisible(page, '#chat-artifact-form button[type="submit"]', "chat-artifact-submit", run);
  await waitForChatCompletion(page, run);
  await snapshot(page, run, "chat-artifact-submitted");
  const chatArtifactButtonCount = await page.locator('[data-action="open-artifact"][data-artifact-id]').filter({ visible: true }).count();
  if (!chatArtifactButtonCount) {
    issue(run, "missing_chat_artifact_button", "Chat artifact dialog should return an open-artifact action.");
  }
  await page.goto(`${BASE_URL}/spaces/${personalSpaceId}/personal/projects/${smokeProject.id}`, { waitUntil: "domcontentloaded" });
  await waitForAppIdle(page);

  await clickIfVisible(page, '.tab-link:has-text("Generate"), .tab-link:has-text("生成")', "project-generate-tab", run);
  await snapshot(page, run, "project-generate");
  await snapshotInlineForm(page, run, '#project-generate-form input[name="topic"]', "modal-project-generate");
  await fillIfExists(page, '#project-generate-form input[name="topic"]', "Smoke Artifact Topic", "generate-topic", run);
  await clickIfVisible(page, '#project-generate-form button[type="submit"]', "project-generate-submit", run);
  await page.waitForTimeout(1500);
  await snapshot(page, run, "project-generate-submitted");
  const projectArtifact = await waitForProjectArtifact(page, personalSpaceId, smokeProject.id, "Smoke Artifact Topic", run);
  if (projectArtifact?.id) {
    try {
      const seeded = seedCitationState({
        spaceId: personalSpaceId,
        sourceId: smokeSource.id,
        artifactId: projectArtifact.id
      });
      run.steps.push(`seeded:artifact-citation:${seeded.id}`);
    } catch (error) {
      issue(run, "citation_state_seed_failed", error.message);
    }
    await page.goto(`${BASE_URL}/spaces/${personalSpaceId}/artifacts/${projectArtifact.id}`, { waitUntil: "domcontentloaded" });
    await waitForAppIdle(page);
    run.steps.push(`goto:personal-artifact:${projectArtifact.id}`);
    await snapshot(page, run, "personal-artifact-detail");
    if (await clickIfVisible(page, '[data-action="open-citation"]', "artifact-open-citation", run)) {
      await snapshot(page, run, "artifact-citation-inspector");
      await closeDrawerIfOpen(page, run);
    } else {
      issue(run, "missing_control", "artifact-open-citation");
    }
    await clickAndDiscardDownloadIfPresent(page, '[data-action="export-artifact"]', "artifact-export-markdown", run);
    await snapshotInlineForm(page, run, '#artifact-edit-form input[name="title"]', "modal-artifact-edit");
    await fillIfExists(page, '#artifact-edit-form textarea[name="content"]', "## Smoke edited artifact\n\nThis body is written by the browser smoke test before saving the artifact.", "artifact-content", run);
    await fillIfExists(page, '#artifact-edit-form input[name="changeNote"]', "Smoke edit note", "artifact-change-note", run);
    await clickIfVisible(page, '#artifact-edit-form button[type="submit"]', "artifact-edit-submit", run);
    await snapshot(page, run, "personal-artifact-edited");
    await closeDrawerIfOpen(page, run);
    const artifactResponse = await api(page, "GET", `/artifacts/${projectArtifact.id}`);
    const hasTraceableCitations = Array.isArray(artifactResponse?.json?.data?.citations) &&
      artifactResponse.json.data.citations.length > 0;
    if (hasTraceableCitations) {
      await snapshotInlineForm(page, run, '#distill-artifact-form select[name="cardType"]', "modal-distill-artifact");
      await clickIfVisible(page, '#distill-artifact-form button[type="submit"]', "distill-artifact-preview-submit", run);
      await snapshot(page, run, "artifact-distill-preview");
      const confirmDistill = page.locator('[data-action="confirm-distill"]').filter({ visible: true }).first();
      if (await confirmDistill.count()) {
        await confirmDistill.click();
        await waitForAppIdle(page);
        run.steps.push("clicked:confirm-distill");
        await snapshot(page, run, "artifact-distill-confirmed");
        await closeDrawerIfOpen(page, run);
        await page.goto(`${BASE_URL}/spaces/${personalSpaceId}/personal/projects/${smokeProject.id}/cards`, { waitUntil: "domcontentloaded" });
        await waitForAppIdle(page);
        await snapshot(page, run, "project-cards-after-distill");
        await page.goto(`${BASE_URL}/spaces/${personalSpaceId}/artifacts/${projectArtifact.id}`, { waitUntil: "domcontentloaded" });
        await waitForAppIdle(page);
      } else {
        issue(run, "missing_control", "confirm-distill");
      }
    } else {
      issue(run, "missing_artifact_citation_state", `Artifact ${projectArtifact.id} did not expose seeded citations through the API.`);
      await snapshot(page, run, "artifact-distill-unavailable");
    }
    await snapshotInlineForm(page, run, '#publish-artifact-wiki-form input[name="title"]', "modal-publish-artifact-wiki");
    await fillIfExists(page, '#publish-artifact-wiki-form input[name="title"]', `Smoke Published Wiki ${Date.now()}`, "publish-artifact-wiki-title", run);
    await clickIfVisible(page, '#publish-artifact-wiki-form button[type="submit"]', "publish-artifact-wiki-submit", run);
    await snapshot(page, run, "artifact-published-to-wiki");
  }

  await clickGlobalNav(page, `/spaces/${personalSpaceId}/workbench/studio`, "studio-nav", run);
  await snapshot(page, run, "studio");
  await snapshotInlineForm(page, run, '#studio-task-form select[name="projectId"]', "modal-studio-task");
  await selectIfExists(page, '#studio-task-form select[name="projectId"]', smokeProject.id, "studio-project", run);
  await fillIfExists(page, '#studio-task-form input[name="topic"]', "Smoke Studio Artifact Topic", "studio-topic", run);
  const studioTaskResponse = waitForResponse(page, (response) =>
    response.request().method() === "POST" && response.url().includes("/api/v1/studio/tasks"),
    { timeoutMs: 15000 }
  );
  await clickIfVisible(page, '#studio-task-form button[type="submit"]', "studio-task-submit", run);
  const studioTaskCreate = await studioTaskResponse;
  if (!studioTaskCreate || !studioTaskCreate.ok()) {
    issue(run, "studio_task_request_failed", studioTaskCreate ? `${studioTaskCreate.status()} ${studioTaskCreate.url()}` : smokeProject.title);
  }
  await page.waitForTimeout(1500);
  await snapshot(page, run, "studio-task-submitted");

  await clickGlobalNav(page, `/spaces/${personalSpaceId}/memory`, "memory-nav", run);
  await snapshot(page, run, "memory");
  await snapshotInlineForm(page, run, '#user-memory-form input[name="topic"]', "modal-user-memory");
  await fillIfExists(page, '#user-memory-form input[name="topic"]', "Alice Preference", "user-memory-topic", run);
  await fillIfExists(page, '#user-memory-form textarea[name="summary"]', "偏好结构化、带证据的研究结论。", "user-memory-summary", run);
  await clickIfVisible(page, '#user-memory-form button[type="submit"]', "user-memory-submit", run);
  await snapshot(page, run, "memory-user-saved");

  await clickGlobalNav(page, `/spaces/${personalSpaceId}/wiki`, "alice-wiki-nav", run);
  await snapshot(page, run, "alice-wiki");
  await clickIfVisible(page, '[data-action="open-wiki-create-form"]', "open-wiki-create-form", run);
  await snapshot(page, run, "modal-wiki-create");
  await fillIfExists(page, '#wiki-create-form input[name="title"]', wikiDraftTitle, "wiki-create-title", run);
  await fillIfExists(page, '#wiki-create-form textarea[name="content"]', "Smoke wiki draft content with [[Smoke Link]].", "wiki-create-content", run);
  const wikiCreateResponse = waitForResponse(page, (response) =>
    response.request().method() === "POST"
      && response.url().includes(`/api/v1/team/spaces/${personalSpaceId}/wiki-pages`),
    { timeoutMs: 15000 }
  );
  await clickIfVisible(page, '#wiki-create-form button[type="submit"]', "wiki-create-submit", run);
  const wikiCreateResult = await wikiCreateResponse;
  if (!wikiCreateResult || !wikiCreateResult.ok()) {
    issue(run, "wiki_create_request_failed", wikiCreateResult ? `${wikiCreateResult.status()} ${wikiCreateResult.url()}` : wikiDraftTitle);
  }
  const smokeWiki = await waitForWikiPage(page, personalSpaceId, wikiDraftTitle, { timeoutMs: 30000 });
  if (smokeWiki?.id) {
    run.steps.push(`created-wiki:${smokeWiki.id}`);
    await clickIfVisible(page, `[data-action="select-wiki-page"][data-page-id="${smokeWiki.id}"]`, "select-smoke-wiki-page", run);
    await snapshot(page, run, "alice-wiki-created");
    await snapshotInlineForm(page, run, '#wiki-edit-form input[name="title"]', "modal-wiki-edit");
    await fillIfExists(page, '#wiki-edit-form textarea[name="content"]', "Smoke wiki draft content with [[Smoke Link]] and an edited note.", "wiki-edit-content", run);
    await clickIfVisible(page, '#wiki-edit-form button[type="submit"]', "wiki-edit-submit", run);
    await snapshot(page, run, "alice-wiki-edited");
    await clickIfVisible(page, '[data-action="publish-wiki-page"]', "publish-wiki-page", run);
    await snapshot(page, run, "alice-wiki-published");
  } else {
    issue(run, "missing_wiki_page", wikiDraftTitle);
    await closeDrawerIfOpen(page, run);
  }
  await clickIfVisible(page, '[data-action="open-wiki-inspector"]', "open-wiki-inspector", run);
  await snapshot(page, run, "alice-wiki-inspector");
  await closeDrawerIfOpen(page, run);
  await clickIfVisible(page, '[data-action="open-wiki-graph-card"]', "open-wiki-graph-card", run);
  await snapshot(page, run, "alice-wiki-graph-card");
  await closeDrawerIfOpen(page, run);
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
  process.stdout.write(`${JSON.stringify(buildOutputSummary(OUTPUT), null, 2)}\n`);
  if (OUTPUT.runs.some(runHasProblems)) {
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
