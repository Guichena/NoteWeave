import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const credentials = loadCredentials();

test("desktop auth validation and password visibility remain usable", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto("/");

  const loginPassword = page.getByLabel("密码", { exact: true });
  await loginPassword.fill("short");
  await expect(page.getByText("密码至少 8 位", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "显示登录密码" }).click();
  await expect(loginPassword).toHaveAttribute("type", "text");
  await page.getByRole("button", { name: "隐藏登录密码" }).click();
  await expect(loginPassword).toHaveAttribute("type", "password");

  await page.getByRole("tab", { name: "注册" }).click();
  const registerSubmit = page.getByRole("button", { name: "创建账号并登录" });
  await page.getByLabel("用户名", { exact: true }).fill("ab");
  await page.getByLabel("注册邮箱", { exact: true }).fill("not-an-email");
  await page.getByLabel("设置密码", { exact: true }).fill("valid-pass-123");
  await page.getByLabel("确认密码", { exact: true }).fill("different-pass");
  await expect(page.getByText("3-80 位字母、数字或符号", { exact: true })).toBeVisible();
  await expect(page.getByText("请输入有效的邮箱格式", { exact: true })).toBeVisible();
  await expect(page.getByText("两次密码不一致", { exact: true })).toBeVisible();
  await expect(registerSubmit).toBeDisabled();
  await page.getByRole("button", { name: "显示设置密码" }).click();
  await expect(page.getByLabel("设置密码", { exact: true })).toHaveAttribute("type", "text");
});

test("desktop creation dialogs and theme persistence follow user expectations", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto("/");
  await login(page);

  const workspaceTrigger = page.getByRole("button", { name: "新建工作台", exact: true });
  await workspaceTrigger.click();
  const workspaceDialog = page.getByRole("dialog", { name: "创建研究工作台" });
  const workspaceName = workspaceDialog.getByLabel("工作台名称");
  await expect(workspaceName).toBeFocused();
  await expect(workspaceDialog.getByRole("button", { name: "创建工作台", exact: true })).toBeDisabled();
  await workspaceName.fill("仅用于验证表单状态");
  await expect(workspaceDialog.getByRole("button", { name: "创建工作台", exact: true })).toBeEnabled();
  await workspaceName.press("Escape");
  await expect(workspaceDialog).toHaveCount(0);
  await expect(workspaceTrigger).toBeFocused();

  const conversationTrigger = page.getByRole("button", { name: "新建会话", exact: true });
  await conversationTrigger.click();
  const conversationDialog = page.getByRole("dialog", { name: "新建独立会话" });
  const conversationName = conversationDialog.getByLabel("会话名称");
  await expect(conversationName).toBeFocused();
  await conversationName.fill("仅用于验证表单状态");
  await expect(conversationDialog.getByRole("button", { name: "创建会话", exact: true })).toBeEnabled();
  await conversationName.press("Escape");
  await expect(conversationDialog).toHaveCount(0);
  await expect(conversationTrigger).toBeFocused();

  await page.getByRole("button", { name: "切换明暗主题" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.reload();
  await expect(page.locator(".workbench-shell")).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("button", { name: "切换明暗主题" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
});

test("desktop library, Research, and Artifact controls enforce their real prerequisites", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto("/");
  await login(page);

  await page.getByRole("button", { name: "工作台资料库", exact: true }).click();
  await page.locator('.source-library-upload-tool input[type="file"]').setInputFiles({
    name: "unsupported.exe",
    mimeType: "application/octet-stream",
    buffer: Buffer.from("not a supported source")
  });
  await expect(page.getByRole("alert")).toContainText("格式不受支持");

  await page.getByRole("button", { name: "Deep Research 工作台", exact: true }).click();
  const launchResearch = page.getByRole("button", { name: "启动 Deep Research" });
  await expect(launchResearch).toBeDisabled();
  await page.getByLabel("研究问题", { exact: true }).fill("验证桌面端启动条件是否真实生效");
  await expect(launchResearch).toBeEnabled();
  await page.getByRole("button", { name: "仅资料", exact: true }).click();
  await expect(launchResearch).toBeDisabled();
  const firstSource = page.locator('[id^="research-source-scope-"]').first();
  if (await firstSource.count() > 0 && await firstSource.isEnabled()) {
    await firstSource.click();
    await expect(launchResearch).toBeEnabled();
  } else {
    await page.getByRole("button", { name: "仅网络", exact: true }).click();
    await expect(launchResearch).toBeEnabled();
  }

  await page.getByRole("button", { name: "笔记本：来源、对话与产物", exact: true }).click();
  await ensureStudioVisible(page);
  await page.locator(".artifact-action-card").first().click();
  const artifactComposer = page.locator(".artifact-composer-view");
  await expect(artifactComposer).toBeVisible();
  await artifactComposer.locator("textarea").fill("验证产物表单，不提交生成任务");
  await expect(artifactComposer.getByRole("button", { name: "生成产物", exact: true })).toBeEnabled();
  await artifactComposer.getByRole("button", { name: "返回 Studio" }).click();
  await expect(page.locator(".artifact-action-card").first()).toBeVisible();
});

async function login(page: Page) {
  const authCard = page.locator(".auth-card");
  if (!await authCard.isVisible()) return;
  await authCard.locator('input[autocomplete="username"]').fill(credentials.username);
  await authCard.locator('input[autocomplete="current-password"]').fill(credentials.password);
  await authCard.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.locator(".workbench-shell")).toBeVisible();
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
    throw new Error("Real Playwright E2E requires NoteWeave bootstrap credentials");
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
