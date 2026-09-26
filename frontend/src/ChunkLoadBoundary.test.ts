import { describe, expect, it } from "vitest";
import { canAutoReloadChunk, isChunkLoadError } from "./ChunkLoadBoundary";

describe("ChunkLoadBoundary helpers", () => {
  it("recognizes stale dynamic import failures", () => {
    expect(isChunkLoadError(new TypeError("Failed to fetch dynamically imported module: /assets/view-old.js"))).toBe(true);
    expect(isChunkLoadError(new Error("ordinary render failure"))).toBe(false);
  });

  it("allows one automatic reload per cooldown window", () => {
    expect(canAutoReloadChunk(null, 100_000)).toBe(true);
    expect(canAutoReloadChunk("50000", 100_000)).toBe(false);
    expect(canAutoReloadChunk("1000", 100_000)).toBe(true);
  });
});
