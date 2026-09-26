import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const credentials = loadCredentials();

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await loginIfRequired(page);
  await expect(page.locator(".workbench-shell")).toBeVisible();
});

test("real login, workspace creation, and every workbench surface are wired", async ({ page }) => {
  await createWorkspace(page);

  await navigateFromRail(page, "Deep Research 工作台");
  await expect(page.locator(".research-page-shell")).toBeVisible();
  await expect(page.locator(".research-index")).toBeVisible();

  await navigateFromRail(page, "Wiki 知识库");
  await expect(page.locator(".wiki-workbench")).toBeVisible();

  await navigateFromRail(page, "Memory 审核");
  await expect(page.locator(".memory-workbench")).toBeVisible();

  await navigateFromRail(page, "笔记本：来源、对话与产物");
  await expect(page.locator(".chat-panel")).toBeVisible();
  await ensureStudioVisible(page);
  await expect(page.locator(".artifact-rail")).toBeVisible();
  await expect(page.locator(".artifact-studio-header")).toContainText("产物");
});

test("real source ingestion and research task reach terminal UI state through task SSE", async ({ page }) => {
  test.setTimeout(360_000);
  await createWorkspace(page);
  const marker = `playwright-real-source-${Date.now()}`;

  await navigateFromRail(page, "工作台资料库");
  await page.locator(".source-paste-disclosure > summary").click();
  await page.locator(".source-paste-disclosure textarea").fill(
    `${marker}\nNoteWeave uses a durable task event stream and workspace-scoped source ingestion.`
  );
  const uploadCompleted = page.waitForResponse((response) =>
    /\/api\/v2\/uploads\/[^/]+\/complete$/.test(response.url()) && response.status() === 200
  );
  await page.locator(".source-paste-disclosure .primary-action").click();
  await uploadCompleted;

  const uploadedSource = page.locator(".source-library-row").filter({ hasText: `${marker}.md` });
  await expect(uploadedSource).toBeVisible({
    timeout: 90_000
  });
  await expect(uploadedSource).toContainText("已建立检索索引", { timeout: 90_000 });
  await expect(page.locator(".source-task-disclosure")).toContainText(/COMPLETED|FAILED|已完成|失败/i, {
    timeout: 90_000
  });

  await navigateFromRail(page, "Deep Research 工作台");
  await expect(page.locator(".research-index")).toBeVisible();
  await page.locator(".research-index textarea").nth(0).fill(
    `Explain the task event flow with verifiable evidence. Marker: ${marker}`
  );
  await page.locator(".research-index textarea").nth(1).fill(
    "Produce a concise evidence-backed summary with one verified conclusion."
  );
  const retrievalModeButtons = page.locator('[aria-label="Research retrieval mode"] button');
  const sourceScopeButton = page.locator('[id^="research-source-scope-"]').first();
  if (await sourceScopeButton.isEnabled()) {
    await retrievalModeButtons.last().click();
    await sourceScopeButton.click();
  } else {
    await expect(sourceScopeButton).toBeDisabled();
    await retrievalModeButtons.first().click();
  }

  const researchCreated = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/research-runs$/.test(response.url())
      && response.request().method() === "POST"
  );
  const taskStream = page.waitForResponse((response) =>
    /\/api\/v2\/tasks\/[^/]+\/events(?:\?|$)/.test(response.url())
      && response.status() === 200
      && response.headers()["content-type"]?.includes("text/event-stream") === true
  );
  await page.locator(".research-index .research-inline-actions button").first().click();

  const researchResponse = await researchCreated;
  expect(researchResponse.status()).toBe(200);
  const createdEnvelope = await researchResponse.json() as {
    data: { research_run_id: string; task_id: string };
  };
  const createdResearch = createdEnvelope.data;
  expect(createdResearch.research_run_id).toBeTruthy();
  expect(createdResearch.task_id).toBeTruthy();
  await taskStream;
  const currentTaskStatus = page.locator(
    `.research-task-card[data-task-id="${createdResearch.task_id}"]`
  );
  await expect(currentTaskStatus).toContainText(/COMPLETED|FAILED|已完成|失败/i, {
    timeout: 300_000
  });
  const currentRunStatus = page.locator(".research-page .wiki-maintenance").filter({
    hasText: createdResearch.research_run_id
  }).first();
  await expect(currentRunStatus).toContainText(/COMPLETED|FAILED/i, { timeout: 15_000 });
});

test("real Markdown and PDF file uploads parse inside the selected workspace", async ({ page }) => {
  test.setTimeout(180_000);
  const marker = Date.now();
  const markdownName = `playwright-upload-${marker}.md`;
  const pdfName = `playwright-upload-${marker}.pdf`;

  await navigateFromRail(page, "工作台资料库");
  const fileInput = page.locator('.source-library-upload-tool input[type="file"]');
  await fileInput.setInputFiles([
    {
      name: markdownName,
      mimeType: "text/markdown",
      buffer: Buffer.from(
        `# NoteWeave upload acceptance\n\nMarker: ${marker}\n\nThis file verifies workspace-scoped Markdown parsing.`
      )
    },
    {
      name: pdfName,
      mimeType: "application/pdf",
      buffer: buildMinimalPdf(`NoteWeave PDF upload acceptance ${marker}`)
    }
  ]);

  const markdownRow = page.locator(".source-library-row").filter({ hasText: markdownName });
  const pdfRow = page.locator(".source-library-row").filter({ hasText: pdfName });
  try {
    await expect(markdownRow).toBeVisible({ timeout: 90_000 });
    await expect(pdfRow).toBeVisible({ timeout: 90_000 });
    await expect(markdownRow).toContainText("已解析", { timeout: 90_000 });
    await expect(pdfRow).toContainText("已解析", { timeout: 90_000 });
    await expect(markdownRow).toContainText("已建立检索索引", { timeout: 90_000 });
    await expect(pdfRow).toContainText("已建立检索索引", { timeout: 90_000 });
    await expect(page.locator(".source-task-disclosure")).toContainText(/COMPLETED|已完成/i, {
      timeout: 90_000
    });
  } finally {
    await deleteSourceIfPresent(page, markdownName);
    await deleteSourceIfPresent(page, pdfName);
  }
});

test("real QA guard, Note, and Wiki chat modes follow their available capabilities", async ({ page }) => {
  test.setTimeout(180_000);
  await createWorkspace(page);

  await expect(page.getByRole("button", { name: "回答模式：问答 RAG", exact: true })).toBeVisible();
  await page.locator(".composer-box textarea").fill(`qa-playwright-${Date.now()}`);
  await expect(page.getByRole("button", { name: "等待可检索资料" })).toBeDisabled();
  await submitChatMode(page, "Note", `note-playwright-${Date.now()}`);
  await submitChatMode(page, "Wiki", `wiki-playwright-${Date.now()}`);
});

test("real Wiki creation, Memory refresh, and workspace settings persist", async ({ page }) => {
  await createWorkspace(page);
  const marker = `playwright-wiki-${Date.now()}`;

  await navigateFromRail(page, "Wiki 知识库");
  await expect(page.locator(".wiki-workbench")).toBeVisible();
  if (await page.locator(".wiki-empty-workbench").isVisible()) {
    await expect(page.getByRole("heading", { name: "准备工作台知识网络" })).toBeVisible();
    await expect(page.getByRole("complementary", { name: "Wiki 准备状态" })).toBeVisible();
  } else {
    await page.getByText("维护工具", { exact: true }).click();
    await page.getByLabel("页面标题").fill(marker);
    await page.getByLabel("页面正文").fill(`${marker} verifies the real Wiki write path.`);
    const wikiCreated = page.waitForResponse((response) =>
      /\/api\/v2\/workspaces\/[^/]+\/knowledge-items$/.test(response.url())
        && response.request().method() === "POST"
    );
    await page.getByRole("button", { name: "提交补页 / 修正文案" }).click();
    expect((await wikiCreated).status()).toBe(200);
    await expect(page.locator(".wiki-page-card").filter({ hasText: marker })).toBeVisible();
  }

  await navigateFromRail(page, "Memory 审核");
  await expect(page.locator(".memory-workbench")).toBeVisible();
  const memoryRefreshed = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/memory\/review$/.test(response.url())
      && response.request().method() === "GET"
  );
  await page.getByRole("button", { name: "刷新审核队列" }).click();
  expect((await memoryRefreshed).status()).toBe(200);
  await expect(page.locator(".memory-queue-list")).toBeVisible();
  await expect(page.locator(".memory-error")).toHaveCount(0);

  const settingsLoaded = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/retrieval-settings$/.test(response.url())
      && response.request().method() === "GET"
  );
  await page.getByRole("button", { name: "工作台设置" }).click();
  expect((await settingsLoaded).status()).toBe(200);
  const retrievalToggle = page.locator('.workspace-settings-panel input[type="checkbox"]');
  await expect(retrievalToggle).toBeEnabled();
  const originallyChecked = await retrievalToggle.isChecked();
  const settingsUpdated = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/retrieval-settings$/.test(response.url())
      && response.request().method() === "PUT"
  );
  await page.locator(".workspace-toggle").click();
  expect((await settingsUpdated).status()).toBe(200);
  await expect(retrievalToggle).toBeChecked({ checked: !originallyChecked });

  const settingsRestored = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/retrieval-settings$/.test(response.url())
      && response.request().method() === "PUT"
  );
  await page.locator(".workspace-toggle").click();
  expect((await settingsRestored).status()).toBe(200);
  await expect(retrievalToggle).toBeChecked({ checked: originallyChecked });
});

test("real Artifact task reaches a terminal UI state or exposes provider wait", async ({ page }) => {
  test.setTimeout(180_000);
  await createWorkspace(page);
  await ensureStudioVisible(page);
  const artifactComposer = page.locator(".artifact-composer-view");
  const artifactSkillCard = page.locator(".artifact-action-card").first();
  await expect(artifactComposer.or(artifactSkillCard)).toBeVisible();
  if (await artifactSkillCard.isVisible()) {
    await artifactSkillCard.click();
  }
  await expect(artifactComposer).toBeVisible();
  await artifactComposer.locator("textarea").fill(
    `Create concise architecture highlights for the real demo. Marker: ${Date.now()}`
  );
  const artifactCreated = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/artifact-jobs$/.test(response.url())
      && response.request().method() === "POST"
  );
  await artifactComposer.getByRole("button", { name: "生成产物", exact: true }).click();
  const artifactResponse = await artifactCreated;
  expect(artifactResponse.status()).toBe(200);
  const artifactEnvelope = await artifactResponse.json() as {
    data: { artifact_job_id: string; task_id: string };
  };
  expect(artifactEnvelope.data.artifact_job_id).toBeTruthy();
  expect(artifactEnvelope.data.task_id).toBeTruthy();
  const artifactCard = page.locator(
    `[data-run-key="artifact-job-${artifactEnvelope.data.artifact_job_id}"]`
  );
  let observedArtifactState = "pending";
  await expect.poll(async () => {
    const text = await artifactCard.innerText();
    observedArtifactState = /COMPLETED|FAILED|已完成|失败/i.test(text)
      ? "terminal"
      : /等待|provider|配置/i.test(text)
        ? "provider_wait"
        : "pending";
    return observedArtifactState;
  }, { timeout: 30_000 }).toMatch(/terminal|provider_wait/);
  expect(observedArtifactState).toMatch(/terminal|provider_wait/);
  if (observedArtifactState === "provider_wait") {
    test.info().annotations.push({
      type: "environment-prerequisite",
      description: "Artifact provider is not configured in the local compose environment; the UI surfaces the task as waiting instead of claiming completion."
    });
    await expect(artifactCard).toContainText(/等待|provider|配置/i);
  }
});

async function loginIfRequired(page: Page) {
  const loginForm = page.locator(".auth-card");
  if (!await loginForm.isVisible()) {
    return;
  }
  await loginForm.locator('input[autocomplete="username"]').fill(credentials.username);
  await loginForm.locator('input[autocomplete="current-password"]').fill(credentials.password);
  await loginForm.locator('button[type="submit"], button').last().click();
}

async function createWorkspace(page: Page) {
  await page.getByRole("button", { name: "账户与工作台菜单" }).click();
  const createButton = page.getByRole("menuitem", { name: "新建工作台" });
  await expect(createButton).toBeEnabled();
  await createButton.click();
  const dialog = page.getByRole("dialog", { name: "创建研究工作台" });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel("工作台名称").fill(`Playwright 工作台 ${Date.now()}`);
  await dialog.getByLabel("用途说明").fill("真实浏览器回归用工作台");
  await dialog.getByRole("button", { name: "创建工作台", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator(".workspace-switcher-trigger")).toContainText("Playwright 工作台");
}

async function navigateFromRail(page: Page, name: string) {
  await page.getByRole("button", { name, exact: true }).click();
}

async function deleteSourceIfPresent(page: Page, title: string) {
  const row = page.locator(".source-library-row").filter({ hasText: title });
  if (await row.count() === 0) return;
  await row.getByRole("button", { name: `删除资料 ${title}` }).click();
  await row.getByRole("button", { name: "确认删除" }).click();
  await expect(row).toHaveCount(0, { timeout: 30_000 });
}

function buildMinimalPdf(text: string) {
  const stream = `BT\n/F1 12 Tf\n72 720 Td\n(${escapePdfText(text)}) Tj\nET\n`;
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
    `<< /Length ${Buffer.byteLength(stream, "ascii")} >>\nstream\n${stream}endstream`,
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
  ];
  let pdf = "%PDF-1.4\n";
  const offsets = [0];
  objects.forEach((object, index) => {
    offsets.push(Buffer.byteLength(pdf, "ascii"));
    pdf += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xrefOffset = Buffer.byteLength(pdf, "ascii");
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  pdf += offsets.slice(1).map((offset) => `${String(offset).padStart(10, "0")} 00000 n \n`).join("");
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`;
  return Buffer.from(pdf, "ascii");
}

function escapePdfText(value: string) {
  return value.replace(/([\\()])/g, "\\$1");
}

async function submitChatMode(page: Page, modeLabel: string, marker: string) {
  await page.getByRole("button", { name: /^回答模式：/ }).click();
  await page.getByRole("menuitemradio", { name: new RegExp(`^${modeLabel}`) }).click();
  const assistantCount = await page.locator(".bubble.assistant").count();
  await page.locator(".composer-box textarea").fill(
    `Return one concise conclusion for the real demo. Marker: ${marker}`
  );
  const answerCreated = page.waitForResponse((response) =>
    /\/api\/v2\/conversations\/[^/]+\/messages$/.test(response.url())
      && response.request().method() === "POST"
  );
  await page.locator(".composer-send-button").click();
  expect((await answerCreated).status()).toBe(200);
  await expect(page.locator(".bubble.user").filter({ hasText: marker })).toBeVisible();
  const assistant = page.locator(".bubble.assistant").nth(assistantCount);
  await expect(assistant.locator(".answer-run-spinner")).toHaveCount(0, {
    timeout: 60_000
  });
  await expect(assistant).toContainText(/.+/);
  await expect(page.locator(".chat-typing")).toHaveCount(0, { timeout: 60_000 });
}

function loadCredentials() {
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

/** 宽屏下产物栏常驻；较窄时通过“打开产物”抽屉打开。 */
async function ensureStudioVisible(page: Page) {
  const toggle = page.getByRole("button", { name: "打开产物", exact: true });
  if (await toggle.isVisible()) await toggle.click();
  await expect(page.locator(".studio-pane .artifact-rail")).toBeVisible();
}
