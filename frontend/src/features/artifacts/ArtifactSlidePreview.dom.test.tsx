// @vitest-environment jsdom

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ArtifactSlidePreview } from "./ArtifactSlidePreview";
import type { ArtifactFileMetadata } from "./model";

const file = (id: string, name: string): ArtifactFileMetadata => ({
  file_id: id, file_format: "PNG", file_name: name, media_type: "image/png",
  storage_backend: "local", bucket_name: "", object_key: "", size_bytes: 100,
  checksum_sha256: "a".repeat(64), status: "READY", error_message: "",
  created_at: "2026-08-02T00:00:00Z"
});

afterEach(() => vi.unstubAllGlobals());

describe("ArtifactSlidePreview", () => {
  it("loads one slide at a time from the existing version file endpoint", async () => {
    const downloadFile = vi.fn().mockResolvedValue(new Blob(["png"], { type: "image/png" }));
    const createObjectURL = vi.fn()
      .mockReturnValueOnce("blob:slide-1").mockReturnValueOnce("blob:slide-2");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", { createObjectURL, revokeObjectURL });
    render(<ArtifactSlidePreview workspaceId="workspace" artifactJobId="job" versionNo={2}
      files={[file("slide-2", "slide-2.png"), file("slide-1", "slide-1.png")]}
      api={{ downloadFile }} />);

    expect(downloadFile).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "预览逐页画面（2 页）" }));
    await waitFor(() => expect(screen.getByRole("img", { name: /第 1 页/ }).getAttribute("src"))
      .toBe("blob:slide-1"));
    expect(downloadFile).toHaveBeenCalledWith("workspace", "job", 2, "slide-1");

    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await waitFor(() => expect(screen.getByRole("img", { name: /第 2 页/ }).getAttribute("src"))
      .toBe("blob:slide-2"));
    expect(downloadFile).toHaveBeenCalledWith("workspace", "job", 2, "slide-2");
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:slide-1");
  });
});
