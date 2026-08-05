import { describe, expect, it } from "vitest";
import { parseAppLocation, pathForView } from "./viewRoute";

describe("viewRoute", () => {
  it("maps research and memory paths", () => {
    expect(parseAppLocation("/research").view).toBe("research");
    expect(parseAppLocation("/memory/reviews").view).toBe("memory");
    expect(pathForView("research")).toBe("/research");
    expect(pathForView("memory")).toBe("/memory/reviews");
  });

  it("maps wiki-like paths and chat default", () => {
    expect(parseAppLocation("/wiki").view).toBe("wiki");
    expect(parseAppLocation("/workspaces/workspace-1/wiki").view).toBe("wiki");
    expect(parseAppLocation("/workspaces/workspace-1/wiki/item-1").view).toBe("wiki");
    expect(parseAppLocation("/api-not").view).toBe("chat");
    expect(parseAppLocation("/").view).toBe("chat");
    expect(pathForView("chat")).toBe("/");
    expect(pathForView("wiki", "/wiki/ws-1")).toBe("/wiki/ws-1");
  });
});
