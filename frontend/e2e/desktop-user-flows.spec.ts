import { expect, test } from "@playwright/test";
import {
  ensureStudioVisible,
  loginIfRequired,
  navigateFromSidebar,
  openAccountMenu,
  openChat,
  useFixtureWorkspace
} from "./helpers";

const DESKTOP_WIDTH = 1280;

test("desktop auth validation and password visibility remain usable", async ({ page }) => {
  await page.setViewportSize({ width: DESKTOP_WIDTH, height: 720 });
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
  await page.setViewportSize({ width: DESKTOP_WIDTH, height: 720 });
  await page.goto("/");
  await loginIfRequired(page);

  // 新建工作台收在账户菜单里，关闭对话框后焦点要回到打开它的账户入口
  const accountTrigger = page.getByRole("button", { name: "账户与工作台菜单" });
  await openAccountMenu(page, DESKTOP_WIDTH);
  await page.getByRole("menuitem", { name: "新建工作台" }).click();
  const workspaceDialog = page.getByRole("dialog", { name: "创建研究工作台" });
  const workspaceName = workspaceDialog.getByLabel("工作台名称");
  await expect(workspaceName).toBeFocused();
  await expect(workspaceDialog.getByRole("button", { name: "创建工作台", exact: true })).toBeDisabled();
  await workspaceName.fill("Playwright 仅用于验证表单状态");
  await expect(workspaceDialog.getByRole("button", { name: "创建工作台", exact: true })).toBeEnabled();
  await workspaceName.press("Escape");
  await expect(workspaceDialog).toHaveCount(0);
  await expect(accountTrigger).toBeFocused();

  const conversationTrigger = page.getByRole("button", { name: "新建会话", exact: true });
  await conversationTrigger.click();
  const conversationDialog = page.getByRole("dialog", { name: "新建独立会话" });
  const conversationName = conversationDialog.getByLabel("会话名称");
  await expect(conversationName).toBeFocused();
  await conversationName.fill("Playwright 仅用于验证表单状态");
  await expect(conversationDialog.getByRole("button", { name: "创建会话", exact: true })).toBeEnabled();
  await conversationName.press("Escape");
  await expect(conversationDialog).toHaveCount(0);
  await expect(conversationTrigger).toBeFocused();

  // 明暗主题开关同样在账户菜单里，切换后要能跨刷新保持
  await openAccountMenu(page, DESKTOP_WIDTH);
  await page.getByRole("menuitem", { name: "切换明暗主题" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.reload();
  await expect(page.locator(".workbench-shell")).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await openAccountMenu(page, DESKTOP_WIDTH);
  await page.getByRole("menuitem", { name: "切换明暗主题" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
});

test("desktop library, Research, and Artifact controls enforce their real prerequisites", async ({ page }) => {
  test.setTimeout(240_000);
  await page.setViewportSize({ width: DESKTOP_WIDTH, height: 720 });
  await page.goto("/");
  await loginIfRequired(page);
  // 仅资料研究需要工作台里真的有已解析资料，夹具工作台保证了这一点
  await useFixtureWorkspace(page, DESKTOP_WIDTH);

  await navigateFromSidebar(page, "工作台资料库", DESKTOP_WIDTH);
  await page.locator('.source-library-upload-tool input[type="file"]').setInputFiles({
    name: "unsupported.exe",
    mimeType: "application/octet-stream",
    buffer: Buffer.from("not a supported source")
  });
  await expect(page.getByRole("alert")).toContainText("格式不受支持");

  await navigateFromSidebar(page, "Deep Research 工作台", DESKTOP_WIDTH);
  // 工作台里已有研究记录时会直接打开最近一条运行，这里无条件回到新建研究的表单
  await expect(page.locator(".research-runs")).toBeVisible();
  await page.getByRole("button", { name: "新研究" }).click();
  await expect(page.locator(".research-composer")).toBeVisible();
  const launchResearch = page.getByRole("button", { name: "启动 Deep Research" });
  await expect(launchResearch).toBeDisabled();
  await page.getByLabel("研究问题", { exact: true }).fill("验证桌面端启动条件是否真实生效");
  await expect(launchResearch).toBeEnabled();
  await page.getByRole("radio", { name: "仅资料", exact: true }).click();
  await expect(launchResearch).toBeDisabled();
  const firstSource = page.locator(".research-scope-picker button").first();
  await expect(firstSource).toBeEnabled();
  await firstSource.click();
  await expect(launchResearch).toBeEnabled();

  await openChat(page, DESKTOP_WIDTH);
  await ensureStudioVisible(page);
  await page.locator(".artifact-action-card").first().click();
  const artifactComposer = page.locator(".artifact-composer-view");
  await expect(artifactComposer).toBeVisible();
  await artifactComposer.locator("textarea").fill("验证产物表单，不提交生成任务");
  await expect(artifactComposer.getByRole("button", { name: "生成产物", exact: true })).toBeEnabled();
  await artifactComposer.getByRole("button", { name: "返回 Studio" }).click();
  await expect(page.locator(".artifact-action-card").first()).toBeVisible();
});
