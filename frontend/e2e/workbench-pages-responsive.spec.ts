import { expect, test } from "@playwright/test";
import {
  expectNoHorizontalOverflow,
  loginIfRequired,
  navigateFromSidebar,
  useFixtureWorkspace
} from "./helpers";

for (const viewport of [
  { width: 1024, height: 768, name: "compact desktop" },
  { width: 375, height: 812, name: "mobile" }
]) {
  test(`Wiki, Memory, and Library keep their domain layouts on ${viewport.name}`, async ({ page }) => {
    test.setTimeout(240_000);
    const consoleErrors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto("/");
    await loginIfRequired(page);
    await expect(page.locator(".workbench-shell")).toBeVisible();
    // 资料库的双栏布局只有工作台里有资料时才成立，先切到带资料的夹具工作台。
    await useFixtureWorkspace(page, viewport.width);

    await navigateFromSidebar(page, "Wiki 知识库", viewport.width);
    await expect(page.locator(".wiki-workbench")).toBeVisible();
    await expectNoHorizontalOverflow(page);

    await expect.poll(async () => (
      await page.locator(".wiki-empty-workbench").isVisible()
      || await page.getByRole("button", { name: /管理/ }).isVisible()
    )).toBe(true);
    const wikiIsEmpty = await page.locator(".wiki-empty-workbench").isVisible();
    if (wikiIsEmpty) {
      await expect(page.getByRole("heading", { name: "准备工作台知识网络" })).toBeVisible();
      await expect(page.getByText("手动创建首个页面")).toBeVisible();
    } else {
      // 总览里直接展示知识图谱
      await expect(page.getByRole("group", { name: "图谱视角" })).toBeVisible();
      // 管理抽屉收纳维护工具，打开后不应溢出视口
      await page.getByRole("button", { name: /管理/ }).click();
      const manage = page.getByRole("dialog", { name: "知识库管理" });
      await expect(manage).toBeVisible();
      // 抽屉从右侧滑入有 220ms 动画，等它停稳再量最终位置
      await expect.poll(async () => {
        const box = await manage.boundingBox();
        return box ? Math.round(box.x + box.width) : Number.POSITIVE_INFINITY;
      }).toBeLessThanOrEqual(viewport.width);
      const bounds = await manage.boundingBox();
      expect(bounds).not.toBeNull();
      expect(bounds!.x).toBeGreaterThanOrEqual(0);
      // 遮罩和抽屉头部的图标按钮同名，必须点抽屉内的那个，否则会被抽屉挡住
      await manage.getByRole("button", { name: "关闭知识库管理" }).click();
      await expect(manage).toHaveCount(0);
      // 打开第一个页面：文档 + 关系栏
      await page.locator(".wiki-page-card").first().click();
      await expect(page.locator(".wiki-page-detail h2")).toBeVisible();
      await expect(page.getByRole("complementary", { name: "Wiki 关系面板" })).toBeVisible();
    }
    await expectNoHorizontalOverflow(page);

    await navigateFromSidebar(page, "Memory 审核", viewport.width);
    await expect(page.locator(".memory-workbench")).toBeVisible();
    await expect(page.getByLabel("记忆与资料的分工")).toBeVisible();
    await expect(page.getByRole("form", { name: "添加偏好" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "生效中" })).toBeVisible();
    await expectNoHorizontalOverflow(page);

    await page.goto("/library");
    await expect(page.locator(".source-library-page")).toBeVisible();
    await expect(page.locator(".source-library-catalog")).toBeVisible();
    await expectNoHorizontalOverflow(page);
    const libraryLayout = await page.evaluate(() => {
      const layout = document.querySelector<HTMLElement>(".source-library-layout");
      const catalog = document.querySelector<HTMLElement>(".source-library-catalog");
      const tools = document.querySelector<HTMLElement>(".source-library-tools");
      return {
        columns: layout ? getComputedStyle(layout).gridTemplateColumns : "",
        catalogWidth: catalog?.getBoundingClientRect().width ?? 0,
        toolsWidth: tools?.getBoundingClientRect().width ?? 0
      };
    });
    expect(libraryLayout.catalogWidth).toBeGreaterThan(200);
    expect(libraryLayout.toolsWidth).toBeGreaterThan(200);
    // 1199px 以下资料目录与上传工具改为单栏堆叠
    expect(libraryLayout.columns.split(" ")).toHaveLength(1);
    expect(consoleErrors).toEqual([]);
  });
}
