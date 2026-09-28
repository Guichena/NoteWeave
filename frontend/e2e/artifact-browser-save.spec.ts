import { expect, test } from "@playwright/test";

test("artifact file save reaches the browser download shelf", async ({ page }) => {
  await page.goto("/");
  const downloadEvent = page.waitForEvent("download");
  await page.evaluate(async () => {
    const { downloadBlob } = await import("/src/features/artifacts/useArtifactWorkspace.ts");
    downloadBlob(new Blob(["# saved artifact\n"], { type: "text/markdown" }), "slide-notes.md");
  });
  const download = await downloadEvent;
  expect(download.suggestedFilename()).toBe("slide-notes.md");
  const contents = await download.createReadStream();
  const chunks: Buffer[] = [];
  for await (const chunk of contents) chunks.push(Buffer.from(chunk));
  expect(Buffer.concat(chunks).toString("utf8")).toBe("# saved artifact\n");
});
