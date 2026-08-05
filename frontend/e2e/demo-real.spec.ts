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

  const navItems = page.locator(".global-rail-item");
  await navItems.nth(1).click();
  await expect(page.locator(".research-page-shell")).toBeVisible();
  await expect(page.locator(".research-index")).toBeVisible();

  await navItems.nth(2).click();
  await expect(page.locator(".wiki-workbench")).toBeVisible();

  await navItems.nth(3).click();
  await expect(page.locator(".memory-workbench")).toBeVisible();

  await navItems.nth(0).click();
  await expect(page.locator(".chat-panel")).toBeVisible();
  await page.locator(".composer-actions button.secondary-button").click();
  await expect(page.locator(".artifact-rail")).toBeVisible();
  await expect(page.locator(".artifact-composer-header")).toContainText("创建产物");
});

test("real source ingestion and research task reach terminal UI state through task SSE", async ({ page }) => {
  test.setTimeout(180_000);
  await createWorkspace(page);
  const marker = `playwright-real-source-${Date.now()}`;

  await page.locator(".sources-pane textarea").fill(
    `${marker}\nNoteWeave uses a durable task event stream and workspace-scoped source ingestion.`
  );
  const uploadCompleted = page.waitForResponse((response) =>
    /\/api\/v2\/uploads\/[^/]+\/complete$/.test(response.url()) && response.status() === 200
  );
  await page.locator(".sources-pane button").first().click();
  await uploadCompleted;

  await expect(page.locator(".source-list-item").filter({ hasText: "frontend-source.md" })).toBeVisible({
    timeout: 90_000
  });
  await expect(page.locator(".source-task-card")).toContainText(/COMPLETED|FAILED|已完成|失败/i, {
    timeout: 90_000
  });

  await page.locator(".global-rail-item").nth(1).click();
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
    timeout: 120_000
  });
  const currentRunStatus = page.locator(".research-page .wiki-maintenance").filter({
    hasText: createdResearch.research_run_id
  }).first();
  await expect(currentRunStatus).toContainText(/COMPLETED|FAILED/i, { timeout: 15_000 });
});

test("real QA, Note, and Wiki chat modes all settle in the conversation UI", async ({ page }) => {
  test.setTimeout(180_000);
  await createWorkspace(page);

  await submitChatMode(page, "问答 RAG", `qa-playwright-${Date.now()}`);
  await submitChatMode(page, "Note", `note-playwright-${Date.now()}`);
  await submitChatMode(page, "Wiki", `wiki-playwright-${Date.now()}`);
});

test("real Wiki creation, Memory refresh, and workspace settings persist", async ({ page }) => {
  await createWorkspace(page);
  const marker = `playwright-wiki-${Date.now()}`;

  await page.locator(".global-rail-item").nth(2).click();
  await expect(page.locator(".wiki-workbench")).toBeVisible();
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

  await page.locator(".global-rail-item").nth(3).click();
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
  await retrievalToggle.click();
  expect((await settingsUpdated).status()).toBe(200);
  await expect(retrievalToggle).toBeChecked({ checked: !originallyChecked });

  const settingsRestored = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/retrieval-settings$/.test(response.url())
      && response.request().method() === "PUT"
  );
  await retrievalToggle.click();
  expect((await settingsRestored).status()).toBe(200);
  await expect(retrievalToggle).toBeChecked({ checked: originallyChecked });
});

test("real Artifact task reaches a terminal UI state", async ({ page }) => {
  test.setTimeout(180_000);
  await createWorkspace(page);
  await page.locator(".composer-actions button.secondary-button").click();
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
  await artifactComposer.getByRole("button", { name: "生成", exact: true }).click();
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
  await expect(artifactCard).toContainText(/COMPLETED|FAILED|已完成|失败/i, {
    timeout: 120_000
  });
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
  const createButton = page.locator(".global-rail-actions button").first();
  await expect(createButton).toBeEnabled();
  await createButton.click();
  await expect(page.locator(".global-rail-field select").first()).not.toHaveValue("");
  await expect(page.locator(".global-rail-field select").nth(1)).not.toHaveValue("");
}

async function submitChatMode(page: Page, modeLabel: string, marker: string) {
  await page.getByRole("tab", { name: modeLabel, exact: true }).click();
  const assistantCount = await page.locator(".bubble.assistant").count();
  await page.locator(".composer-block textarea").fill(
    `Return one concise conclusion for the real demo. Marker: ${marker}`
  );
  const answerCreated = page.waitForResponse((response) =>
    /\/api\/v2\/conversations\/[^/]+\/messages$/.test(response.url())
      && response.request().method() === "POST"
  );
  await page.getByRole("button", { name: `发送到 ${modeLabel}` }).click();
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
