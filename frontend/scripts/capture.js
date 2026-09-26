import { chromium } from "@playwright/test";
import fs from "fs";
import path from "path";

async function run() {
  let browser;
  try {
    browser = await chromium.launch({ channel: "chrome", headless: true });
  } catch (e) {
    try {
      browser = await chromium.launch({ channel: "msedge", headless: true });
    } catch (e2) {
      browser = await chromium.launch({ headless: true });
    }
  }

  const viewports = [
    { name: "1280x720", width: 1280, height: 720 },
    { name: "1440x900", width: 1440, height: 900 },
    { name: "1024x768", width: 1024, height: 768 },
    { name: "375x812", width: 375, height: 812 }
  ];

  const prefix = process.argv[2] || "after";
  const port = process.argv[3] || "3001";
  const outDir = path.resolve(process.cwd(), "../auth_screenshots");
  if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

  for (const vp of viewports) {
    const page = await browser.newPage({ viewport: { width: vp.width, height: vp.height } });
    await page.goto(`http://127.0.0.1:${port}/?t=${Date.now()}`, { waitUntil: "networkidle" });
    await page.waitForTimeout(600);

    // Login screenshot
    await page.screenshot({ path: path.join(outDir, `${prefix}_login_${vp.name}.png`) });

    // Switch to register tab
    const regTab = page.locator('button[role="tab"]:has-text("注册")');
    if (await regTab.isVisible()) {
      await regTab.click();
      await page.waitForTimeout(400);
      await page.screenshot({ path: path.join(outDir, `${prefix}_register_${vp.name}.png`) });
    }
    await page.close();
  }

  await browser.close();
  console.log(`Screenshots captured successfully (${prefix} on port ${port})`);
}

run().catch(console.error);
