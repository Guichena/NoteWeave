import { expect, test, type Page } from "@playwright/test";
import {
  createWorkspace,
  ensureStudioVisible,
  loginIfRequired,
  navigateFromSidebar,
  openAccountMenu,
  openChat,
  useFixtureWorkspace
} from "./helpers";

const DESKTOP_WIDTH = 1280;

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: DESKTOP_WIDTH, height: 800 });
  await page.goto("/");
  await loginIfRequired(page);
  await expect(page.locator(".workbench-shell")).toBeVisible();
});

test("real login, workspace creation, and every workbench surface are wired", async ({ page }) => {
  await newWorkspace(page);

  await navigateFromSidebar(page, "Deep Research 工作台", DESKTOP_WIDTH);
  await expect(page.locator(".research-page-shell")).toBeVisible();
  await expect(page.locator(".research-runs")).toBeVisible();

  await navigateFromSidebar(page, "Wiki 知识库", DESKTOP_WIDTH);
  await expect(page.locator(".wiki-workbench")).toBeVisible();

  await navigateFromSidebar(page, "Memory 审核", DESKTOP_WIDTH);
  await expect(page.locator(".memory-workbench")).toBeVisible();

  // 对话视图没有独立导航项，从侧边栏的会话条目进入
  await openChat(page, DESKTOP_WIDTH);
  await expect(page.locator(".chat-panel")).toBeVisible();
  await ensureStudioVisible(page);
  await expect(page.locator(".artifact-rail")).toBeVisible();
  await expect(page.locator(".artifact-studio-header")).toContainText("产物");
});

test("real source ingestion and research task reach terminal UI state through task SSE", async ({ page }) => {
  test.setTimeout(900_000);
  await newWorkspace(page);
  const marker = `playwright-real-source-${Date.now()}`;

  await navigateFromSidebar(page, "工作台资料库", DESKTOP_WIDTH);
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
  await expect(uploadedSource).toContainText(/可检索|向量化失败|写入索引失败/, { timeout: 90_000 });
  // 展开资料行查看解析任务的处理记录
  await uploadedSource.locator("[data-source-detail-trigger]").click();
  await expect(uploadedSource.getByRole("region", { name: "处理记录" })).toContainText(/处理完成|处理失败/, {
    timeout: 90_000
  });

  await navigateFromSidebar(page, "Deep Research 工作台", DESKTOP_WIDTH);
  const composer = page.locator(".research-composer");
  await expect(page.locator(".research-runs")).toBeVisible();
  await page.getByRole("button", { name: "新研究" }).click();
  await expect(composer).toBeVisible();
  await page.getByLabel("研究问题", { exact: true }).fill(
    `Explain the task event flow with verifiable evidence. Marker: ${marker}`
  );
  await page.locator(".research-composer-settings > summary").click();
  await page.getByLabel("研究目标", { exact: true }).fill(
    "Produce a concise evidence-backed summary with one verified conclusion."
  );
  await page.locator(".research-composer-settings > summary").click();
  // 有已解析资料时按仅资料研究并勾选第一份资料，否则退回仅网络。
  await page.getByRole("radio", { name: "仅资料" }).click();
  const sourceScopeButton = page.locator(".research-scope-picker button").first();
  if (await sourceScopeButton.count()) {
    await sourceScopeButton.click();
  } else {
    await page.getByRole("radio", { name: "仅网络" }).click();
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
  await page.getByRole("button", { name: "启动 Deep Research" }).click();

  const researchResponse = await researchCreated;
  expect(researchResponse.status()).toBe(200);
  const createdEnvelope = await researchResponse.json() as {
    data: { research_run_id: string; task_id: string };
  };
  const createdResearch = createdEnvelope.data;
  expect(createdResearch.research_run_id).toBeTruthy();
  expect(createdResearch.task_id).toBeTruthy();
  await taskStream;
  // 运行页标题区的状态标签会随任务事件从研究中变为终态。
  // 真实环境里一条仅资料研究要跑好几分钟，worker 排队时更久，这里给足预算
  await expect(page.locator(".research-run-state")).toContainText(/已完成|运行失败|证据不足/, {
    timeout: 600_000
  });
  await expect(page.locator(".research-runs-item.is-active")).toBeVisible();
});

test("real Markdown and PDF file uploads parse inside the selected workspace", async ({ page }) => {
  test.setTimeout(240_000);
  const marker = Date.now();
  const markdownName = `playwright-upload-${marker}.md`;
  const pdfName = `playwright-upload-${marker}.pdf`;

  // 固定用夹具工作台，避免受上一个用例留下的工作台选择影响
  await useFixtureWorkspace(page, DESKTOP_WIDTH);
  await navigateFromSidebar(page, "工作台资料库", DESKTOP_WIDTH);
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
    // 资料行的状态依次经过解析中、切片中、向量化中、写入索引中，最终变为可检索
    await expect(markdownRow).toContainText("可检索", { timeout: 90_000 });
    await expect(pdfRow).toContainText("可检索", { timeout: 90_000 });
    await expect(pdfRow).toContainText(/个切片/);
    await pdfRow.locator("[data-source-detail-trigger]").click();
    await expect(pdfRow.getByRole("region", { name: "处理记录" })).toContainText("处理完成", {
      timeout: 90_000
    });
  } finally {
    await deleteSourceIfPresent(page, markdownName);
    await deleteSourceIfPresent(page, pdfName);
  }
});

test("real QA guard, Note, and Wiki chat modes follow their available capabilities", async ({ page }) => {
  test.setTimeout(240_000);
  await newWorkspace(page);
  await openChat(page, DESKTOP_WIDTH);

  await expect(page.getByRole("radio", { name: /问答/ })).toHaveAttribute("aria-checked", "true");
  await page.locator(".composer-box textarea").fill(`qa-playwright-${Date.now()}`);
  await expect(page.getByRole("button", { name: "等待可检索资料" })).toBeDisabled();
  await submitChatMode(page, "精读", `note-playwright-${Date.now()}`);
  await submitChatMode(page, "Wiki", `wiki-playwright-${Date.now()}`);
});

test("real Wiki creation, Memory refresh, and workspace settings persist", async ({ page }) => {
  test.setTimeout(240_000);
  await newWorkspace(page);
  const marker = `playwright-wiki-${Date.now()}`;

  await navigateFromSidebar(page, "Wiki 知识库", DESKTOP_WIDTH);
  await expect(page.locator(".wiki-workbench")).toBeVisible();
  if (await page.locator(".wiki-empty-workbench").isVisible()) {
    await expect(page.getByRole("heading", { name: "准备工作台知识网络" })).toBeVisible();
    await expect(page.getByText("手动创建首个页面")).toBeVisible();
  } else {
    // 补页入口收在管理抽屉的补页 / 修正标签页里
    await page.getByRole("button", { name: /管理/ }).click();
    const manage = page.getByRole("dialog", { name: "知识库管理" });
    await expect(manage).toBeVisible();
    await manage.getByRole("tab", { name: "补页 / 修正" }).click();
    await manage.getByLabel("页面标题").fill(marker);
    await manage.getByLabel("页面正文").fill(`${marker} verifies the real Wiki write path.`);
    const wikiCreated = page.waitForResponse((response) =>
      /\/api\/v2\/workspaces\/[^/]+\/knowledge-items$/.test(response.url())
        && response.request().method() === "POST"
    );
    await manage.getByRole("button", { name: "提交补页 / 修正文案" }).click();
    expect((await wikiCreated).status()).toBe(200);
    await manage.getByRole("button", { name: "关闭知识库管理" }).click();
    await expect(page.locator(".wiki-page-card").filter({ hasText: marker })).toBeVisible();
  }

  await navigateFromSidebar(page, "Memory 审核", DESKTOP_WIDTH);
  await expect(page.locator(".memory-workbench")).toBeVisible();
  const memoryRefreshed = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/memory\/items$/.test(response.url())
      && response.request().method() === "GET"
  );
  await page.getByRole("button", { name: "刷新记忆" }).click();
  expect((await memoryRefreshed).status()).toBe(200);
  await expect(page.getByRole("heading", { name: "生效中" })).toBeVisible();
  await expect(page.locator(".memory-error")).toHaveCount(0);

  // 工作台设置在账户菜单里
  const settingsLoaded = page.waitForResponse((response) =>
    /\/api\/v2\/workspaces\/[^/]+\/retrieval-settings$/.test(response.url())
      && response.request().method() === "GET"
  );
  await openAccountMenu(page, DESKTOP_WIDTH);
  await page.getByRole("menuitem", { name: "工作台设置" }).click();
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
  test.setTimeout(420_000);
  await useFixtureWorkspace(page, DESKTOP_WIDTH);
  await openChat(page, DESKTOP_WIDTH);
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
  // 产物行通过 data-state 暴露状态：ready / failed / cancelled 为终态，waiting 表示等待外部服务。
  await expect.poll(async () => {
    const state = await artifactCard.getAttribute("data-state");
    observedArtifactState = state === "ready" || state === "failed" || state === "cancelled"
      ? "terminal"
      : state === "waiting"
        ? "provider_wait"
        : "pending";
    return observedArtifactState;
    // 真实大模型生成产物要几分钟，这里等到终态或等待外部服务为止
  }, { timeout: 300_000 }).toMatch(/terminal|provider_wait/);

  expect(observedArtifactState).toMatch(/terminal|provider_wait/);
  if (observedArtifactState === "provider_wait") {
    test.info().annotations.push({
      type: "environment-prerequisite",
      description: "Artifact provider is not configured in the local compose environment; the UI surfaces the task as waiting instead of claiming completion."
    });
    await expect(artifactCard).toContainText(/等待/);
  }
});

async function newWorkspace(page: Page) {
  return createWorkspace(page, `Playwright 工作台 ${Date.now()}`, DESKTOP_WIDTH);
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
  await page.getByRole("radiogroup", { name: "回答模式" }).getByRole("radio", { name: modeLabel }).click();
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
    timeout: 90_000
  });
  await expect(assistant).toContainText(/.+/);
  await expect(page.locator(".chat-typing")).toHaveCount(0, { timeout: 90_000 });
}
