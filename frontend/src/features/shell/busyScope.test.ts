import { describe, expect, it } from "vitest";
import { isAnyBusy, isScopeBusy, setBusyScope } from "./busyScope";

describe("busyScope", () => {
  it("sets and clears scopes independently", () => {
    let busy = setBusyScope({}, "chat", true);
    busy = setBusyScope(busy, "upload", true);
    expect(isScopeBusy(busy, "chat")).toBe(true);
    expect(isAnyBusy(busy)).toBe(true);
    busy = setBusyScope(busy, "chat", false);
    expect(isScopeBusy(busy, "chat")).toBe(false);
    expect(isScopeBusy(busy, "upload")).toBe(true);
  });
});
